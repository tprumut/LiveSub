#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <algorithm>
#include <cmath>
#include "llama.h"

#define LOG_TAG "LiveSubTranslation"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct TranslationContext {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_livesub_translation_TranslationNative_nativeLoadModel(
        JNIEnv *env, jobject, jstring model_path, jint threads) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    if (!path) return 0;

    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // CPU inference on Android ARM64

    llama_model *model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);

    if (!model) {
        LOGE("Hy-MT translation model could not be loaded from path");
        return 0;
    }

    const llama_vocab *vocab = llama_model_get_vocab(model);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 1024; // Context size for subtitle translation
    ctx_params.n_batch = 256;
    ctx_params.n_threads = std::max(1, std::min(threads, 4));
    ctx_params.n_threads_batch = std::max(1, std::min(threads, 4));

    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("Could not create llama_context for translation model");
        llama_model_free(model);
        return 0;
    }

    auto *tc = new TranslationContext();
    tc->model = model;
    tc->ctx = ctx;
    tc->vocab = vocab;

    LOGI("Hy-MT translation model loaded successfully");
    return reinterpret_cast<jlong>(tc);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_livesub_translation_TranslationNative_nativeTranslate(
        JNIEnv *env, jobject, jlong context_pointer, jstring prompt_str, jint max_tokens, jint threads) {
    auto *tc = reinterpret_cast<TranslationContext *>(context_pointer);
    if (!tc || !tc->ctx || !tc->model || !tc->vocab) return env->NewStringUTF("");

    const char *prompt_raw = env->GetStringUTFChars(prompt_str, nullptr);
    if (!prompt_raw) return env->NewStringUTF("");
    std::string prompt_instruction(prompt_raw);
    env->ReleaseStringUTFChars(prompt_str, prompt_raw);

    if (prompt_instruction.empty()) return env->NewStringUTF("");

    // Format prompt using chat template or fallback to Hunyuan template
    std::string formatted_prompt;
    const char *tmpl = llama_model_chat_template(tc->model, nullptr);
    llama_chat_message chat_msg = {"user", prompt_instruction.c_str()};

    std::vector<char> template_buf(prompt_instruction.size() * 3 + 256);
    int32_t applied_len = llama_chat_apply_template(
        tmpl,
        &chat_msg,
        1,
        true,
        template_buf.data(),
        static_cast<int32_t>(template_buf.size())
    );

    if (applied_len > static_cast<int32_t>(template_buf.size())) {
        template_buf.resize(applied_len + 1);
        applied_len = llama_chat_apply_template(
            tmpl,
            &chat_msg,
            1,
            true,
            template_buf.data(),
            static_cast<int32_t>(template_buf.size())
        );
    }

    if (applied_len > 0) {
        formatted_prompt = std::string(template_buf.data(), applied_len);
    } else {
        // Fallback directly to the official Tencent Hy-MT template
        formatted_prompt = "<｜hy_begin of sentence｜><｜hy_User｜>" + prompt_instruction + "<｜hy_Assistant｜>";
    }

    // Clear previous KV cache memory to free RAM
    llama_memory_clear(llama_get_memory(tc->ctx), true);

    // Tokenize
    const int32_t max_prompt_capacity = 1024;
    std::vector<llama_token> tokens(max_prompt_capacity);
    int32_t n_tokens = llama_tokenize(
        tc->vocab,
        formatted_prompt.c_str(),
        static_cast<int32_t>(formatted_prompt.length()),
        tokens.data(),
        max_prompt_capacity,
        true,  // add_special
        true   // parse_special
    );

    if (n_tokens < 0) {
        LOGE("Prompt tokenization failed: code %d", n_tokens);
        return env->NewStringUTF("");
    }
    tokens.resize(n_tokens);
    if (tokens.empty()) return env->NewStringUTF("");

    // Initialize batch for evaluation
    const int32_t batch_alloc = std::max(n_tokens, 256);
    llama_batch batch = llama_batch_init(batch_alloc, 0, 1);
    for (int i = 0; i < n_tokens; ++i) {
        batch.token[i] = tokens[i];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = (i == n_tokens - 1);
    }
    batch.n_tokens = n_tokens;

    if (llama_decode(tc->ctx, batch) != 0) {
        LOGE("llama_decode failed on prompt");
        llama_batch_free(batch);
        return env->NewStringUTF("");
    }

    // Recommended sampler setup for Hunyuan translation:
    // top_k: 20, top_p: 0.6, temp: 0.7
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler *smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(20));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.6f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(42));

    std::string response;
    int32_t n_past = n_tokens;
    const int32_t max_gen = std::min(max_tokens, 256);

    for (int i = 0; i < max_gen; ++i) {
        const llama_token token_id = llama_sampler_sample(smpl, tc->ctx, -1);
        llama_sampler_accept(smpl, token_id);

        if (llama_vocab_is_eog(tc->vocab, token_id) || token_id == llama_vocab_eos(tc->vocab)) {
            break;
        }

        char piece_buf[128];
        const int32_t piece_len = llama_token_to_piece(tc->vocab, token_id, piece_buf, sizeof(piece_buf), 0, false);
        if (piece_len > 0) {
            response.append(piece_buf, piece_len);
        }

        batch.token[0] = token_id;
        batch.pos[0] = n_past++;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;
        batch.n_tokens = 1;

        if (llama_decode(tc->ctx, batch) != 0) {
            LOGE("llama_decode failed during token generation");
            break;
        }
    }

    llama_sampler_free(smpl);
    llama_batch_free(batch);

    // Release KV cache memory
    llama_memory_clear(llama_get_memory(tc->ctx), true);

    LOGI("Translation generated %zu chars across %d tokens", response.size(), n_past - n_tokens);
    return env->NewStringUTF(response.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_livesub_translation_TranslationNative_nativeFree(
        JNIEnv *, jobject, jlong context_pointer) {
    auto *tc = reinterpret_cast<TranslationContext *>(context_pointer);
    if (tc) {
        if (tc->ctx) llama_free(tc->ctx);
        if (tc->model) llama_model_free(tc->model);
        delete tc;
    }
}
