#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <string>
#include "whisper.h"

#define LOG_TAG "LiveSubWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_com_livesub_app_WhisperNative_nativeLoadModel(JNIEnv *env, jobject, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) return 0;

    whisper_context_params context_params = whisper_context_default_params();
    context_params.use_gpu = false;
    whisper_context *context = whisper_init_from_file_with_params(path, context_params);
    env->ReleaseStringUTFChars(model_path, path);

    if (context == nullptr) {
        LOGE("Whisper model could not be loaded");
        return 0;
    }
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_livesub_app_WhisperNative_nativeTranscribe(
        JNIEnv *env, jobject, jlong context_pointer, jfloatArray samples, jstring language, jint threads) {
    auto *context = reinterpret_cast<whisper_context *>(context_pointer);
    if (context == nullptr || samples == nullptr) return env->NewStringUTF("");

    const jsize sample_count = env->GetArrayLength(samples);
    jfloat *pcm = env->GetFloatArrayElements(samples, nullptr);
    if (pcm == nullptr) return env->NewStringUTF("");
    double sumSquares = 0.0;
    float peak = 0.0f;
    for (jsize i = 0; i < sample_count; ++i) {
        const float absoluteValue = std::abs(pcm[i]);
        sumSquares += static_cast<double>(pcm[i]) * pcm[i];
        peak = std::max(peak, absoluteValue);
    }
    const double rms = sample_count > 0 ? std::sqrt(sumSquares / sample_count) : 0.0;

    const char *language_value = env->GetStringUTFChars(language, nullptr);
    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    const bool automatic_language = language_value == nullptr
            || language_value[0] == '\0'
            || std::string(language_value) == "auto";
    // Not: whisper.cpp v1.9.x'te params.detect_language = true, whisper_full içinde dil tespitinden
    // sonra hemen return 0 yapar; transkripsiyon hiç çalışmaz ve metin boş döner. Bu yüzden
    // otomatik algılama için yalnızca language = nullptr kullanılır, detect_language false kalır.
    params.language = automatic_language ? nullptr : language_value;
    params.detect_language = false;
    params.n_threads = std::max(1, std::min(threads, 4));
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;

    const int result = whisper_full(context, params, pcm, sample_count);
    const std::string usedLanguage = params.language != nullptr ? params.language : "?";
    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
    if (language_value != nullptr) env->ReleaseStringUTFChars(language, language_value);
    if (result != 0) {
        LOGE("whisper_full failed with code %d", result);
        return env->NewStringUTF("");
    }

    std::string text;
    const int segments = whisper_full_n_segments(context);
    for (int i = 0; i < segments; ++i) {
        const char *segment = whisper_full_get_segment_text(context, i);
        if (segment != nullptr) text.append(segment);
    }
    LOGI("result=%d lang=%s samples=%d rms=%.5f peak=%.5f segments=%d chars=%zu threads=%d",
         result, usedLanguage.c_str(), sample_count, rms, peak, segments, text.size(), params.n_threads);
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_livesub_app_WhisperNative_nativeFree(JNIEnv *, jobject, jlong context_pointer) {
    auto *context = reinterpret_cast<whisper_context *>(context_pointer);
    if (context != nullptr) whisper_free(context);
}
