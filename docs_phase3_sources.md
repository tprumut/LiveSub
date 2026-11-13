# Aşama 3 — model ve uygulama kaynakları

## Model

Tencent'in resmi Hugging Face deposunda llama.cpp uyumlu `tencent/Hy-MT2-1.8B-GGUF` yayımlanmış olduğu için HY-MT1.5 fallback'i kullanılmadı. Uygulama `Hy-MT2-1.8B-Q4_K_M.gguf` dosyasını seçiyor. Resmi dosya boyutu 1,133,080,448 bayt; SHA-256 `dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699`. Ağırlık APK'ya veya Git deposuna eklenmez; uygulamanın özel alanına indirilir ve doğrulanır. [1]

Model kartı tanıtımında 33 dili desteklediğini söylüyor; Türkçe (`tr` / `Turkish`) listede açıkça bulunuyor. [1]

## Prompt ve örnekleme

`TranslationEngine.kt`, kullanıcı tarafından istenen segment promptunu kullanıyor:

```text
Translate the following segment into {target_language}, without additional explanation.
{source_text}
```

Araştırma sırasında açılan güncel Hy-MT2 GGUF kartının İngilizce genel örnek promptu farklı bir ifade kullanıyor: “Translate the following text into {target_lang}. Note that you should only output the translated result without any additional explanation:”. Bu nedenle kaynak notu iki biçimi ayırt eder; mevcut kod kullanıcı tarafından özellikle verilen segment biçimini uygular. [1]

JNI örneklemesi `top_k=20`, `top_p=0.6`, `temperature=0.7` değerlerini kullanır. Uygulama segmentler için 128 yeni token ister; JNI üst sınırı 256'dır. [1]

## Android entegrasyonu

llama.cpp'nin resmi Android rehberi ve `llama.android` örneği ARM64 NDK/CMake entegrasyonu için incelendi. Projede llama.cpp, whisper.cpp'den ayrı `:translation` Android library/CMake modülünde derlenir; `liblivesub_translation.so` JNI üzerinden GGUF çıkarımını sağlar. Upstream llama.cpp commit'i `vendor/llama.cpp/UPSTREAM_COMMIT` içinde sabitlenmiştir. [2] [3]

## Kaynaklar

[1]: https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF "Tencent Hy-MT2-1.8B-GGUF resmi model kartı"
[2]: https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md "llama.cpp Android ve NDK derleme belgesi"
[3]: https://github.com/ggml-org/llama.cpp/tree/master/examples/llama.android "llama.cpp resmi Android örneği"
[4]: https://developer.android.com/ndk/guides/cmake "Android NDK CMake entegrasyon belgesi"
