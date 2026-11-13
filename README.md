# LiveSub

**Android'de sistem sesinden canlı altyazı ve çeviri — tamamen cihaz üzerinde.**

[![Release](https://img.shields.io/github/v/release/tprumut/LiveSub?include_prereleases&label=release)](https://github.com/tprumut/LiveSub/releases/latest)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-10%2B%20%7C%20arm64--v8a-green.svg)](#gereksinimler)
[![On-device](https://img.shields.io/badge/AI-100%25%20on--device-purple.svg)](#nasıl-çalışır)

LiveSub, başka bir uygulamada video izlerken **cihazın ses çıkışını** yakalar, konuşmayı
**Whisper.cpp** ile metne çevirir, **Tencent Hy-MT 2** ile seçtiğiniz dile çevirir ve sonucu
videonun üzerinde **sürüklenebilir, yarı saydam, en fazla iki satırlık** bir kutuda gösterir.

Tüm yapay zekâ işlemleri cihazın içinde çalışır. Ses, transkript ve çeviri hiçbir sunucuya
gönderilmez. Google Play Services, ML Kit, Firebase veya bulut çeviri API'si kullanılmaz.
İnternet yalnızca **ilk açılışta model ağırlıklarını indirmek** için kullanılır.

> English summary: LiveSub captures Android system audio via MediaProjection +
> AudioPlaybackCapture, transcribes it locally with whisper.cpp, translates it locally with
> Tencent Hy-MT 2 (llama.cpp), and renders a draggable two-line overlay on top of any video
> app. No cloud AI, no Google Play Services, no telemetry.

---

## 🚀 Hızlı kurulum (APK)

Kod derlemek istemiyorsanız hazır APK'yı kullanabilirsiniz:

1. **[Releases](https://github.com/tprumut/LiveSub/releases/latest)** sayfasından
   `LiveSub-v0.4.0-arm64.apk` dosyasını indirin.
2. Telefonda/tablette **"Bilinmeyen kaynaklara izin ver"** seçeneğini açıp APK'yı kurun.
   - APK debug anahtarıyla imzalanmıştır. Play Protect'un "bilinmeyen uygulama" uyarısı
     normaldir; devam edebilirsiniz.
3. Uygulamayı açın. İlk açılışta modeller otomatik indirilir:
   - Whisper Base: **~57 MB**
   - Hy-MT 2 Q4_K_M: **~1,13 GB**
   - Toplam yaklaşık **1,2 GB** boş alan ve Wi-Fi önerilir. İndirme kesilirse kaldığı yerden
     devam eder ve SHA-256 ile doğrulanır.
4. **Ayarlar → Yüzen altyazı → "Diğer uygulamaların üzerinde göster"** iznini verin.
5. (Önerilir) **Ayarlar → Pil ve davranış** bölümünden bildirim iznini ve pil optimizasyonu
   muafiyetini açın.
6. **"Canlı dinle"** butonuna basın ve ekrana gelen **MediaProjection** onayını verin.
7. Altyazı görmek istediğiniz videoyu başka bir uygulamada (YouTube, VLC, tarayıcı, yerel
   oynatıcı...) açın. Çeviri ve orijinal metin, videonun üzerindeki kutuda görünür.
8. Kutuyu parmağınızla sürükleyerek istediğiniz yere taşıyabilirsiniz; konum hatırlanır.

> **Önemli:** Netflix, Disney+, ExoPlayer tabanlı bazı uygulamalar ve DRM korumalı içerikler
> sistem sesinin yakalanmasına izin vermez. Bu durumda ses yakalanamaz; DRM'siz video,
> tarayıcı veya VLC gibi oynatıcılar kullanın.

---

## 🧠 Nasıl çalışır?

```
┌─────────────────────────────────────────────────────────────────────┐
│  Başka bir uygulama (YouTube, VLC, tarayıcı, video oynatıcı...)     │
└───────────────────────────────┬─────────────────────────────────────┘
                                │  sistem ses çıkışı
                                ▼
        MediaProjection + AudioPlaybackCapture
        (mikrofon DEĞİL; cihazın kendi sesi)
                                │
                                ▼
        16 kHz mono PCM · 100 ms'lik bloklar
                                │
                                ▼
        VAD: RMS eşiği + ön tampon + sessizlik zaman aşımı
        (konuşma parçaları ~0,3–10 sn)
                                │
                                ▼
        Whisper.cpp · base q5_1 · 4 iş parçacığı
        ARM64 dotprod + fp16 optimizasyonlu GGML
                                │
                 ┌──────────────┴───────────────┐
                 │ orijinal metin               │
                 ▼                              ▼
        OverlayService (üst satır)      Tencent Hy-MT 2 (llama.cpp)
                 ▲                       Q4_K_M · 4 iş parçacığı
                 │                              │
                 └──────── çeviri ◄─────────────┘
                        OverlayService (alt satır)
                        + canlı döküm ekranı
```

### Tasarım kararları

| Konu | Neden böyle? |
|------|--------------|
| **MediaProjection + AudioPlaybackCapture** | Mikrofon yerine cihazın ses çıkışını yakalar; kulaklık/ hoparlör fark etmez, ortam gürültüsü karışmaz. Android 10 (API 29) ve üzeri gerekir. |
| **16 kHz mono PCM** | Whisper'ın beklediği giriş biçimi. Daha yüksek örnekleme hızı kaliteyi artırmaz, CPU ve belleği boşa harcar. |
| **RMS tabanlı VAD** | Sessiz kısımları Whisper'a göndermemek için. Ön tampon, kelimenin başının kesilmesini engeller; sessizlik zaman aşımı parçayı kapatır. |
| **Whisper.cpp + GGML** | Tamamen yerel, internet gerektirmez. `-O3` ve `armv8.2-a+dotprod+fp16` ile ARM64'te belirgin hızlanma sağlar. |
| **Hy-MT 2 (llama.cpp)** | 1,8B parametreli, çok dilli, yerel çalışan açık model. Q4_K_M dengeli boyut/kalite sunar. |
| **Programatik `TextView` overlay** | Compose penceresi yerine hafif bir `TYPE_APPLICATION_OVERLAY` penceresi; oyun/video üzerinde düşük gecikmeyle güncellenir, sürükleme ve 2 satır sınırı kolay yönetilir. |
| **Ayrı iş parçacıkları** | Ses yakalama, Whisper ve çeviri birbirini bloklamaz. Kuyruk dolduğunda en eski parça düşürülür; canlı akış geride kalmaz. |
| **Kısmi uyandırma kilidi** | Ekran kapansa bile dinleme sürer. İsteğe bağlı olarak "ekran kilitlenince durdur" açılabilir. |
| **Dosyaya log** | Bazı üretici ROM'ları (özellikle Huawei) üçüncü taraf logcat satırlarını gizler. LiveSub ayrıca `files/logs/livesub.log` dosyasına yazar. |

### Model indirme ve doğrulama

- Modeller APK'ya **gömülü değildir**; ilk kullanımda Hugging Face'ten indirilir.
- İndirme `.part` dosyasına yapılır; uygulama kapansa bile **kaldığı yerden devam eder**
  (`HTTP Range`).
- Tamamlanınca **boyut + SHA-256** doğrulanır. Doğrulama başarısızsa dosya silinip yeniden
  indirilir.
- Geçici ağ hatalarında **3 deneme** yapılır.
- Model değiştirmek için önce dinlemeyi durdurmak gerekir.

---

## ✨ Özellikler

- 🎙️ **Sistem sesi yakalama** — mikrofon değil, cihazın çıkış sesi (MediaProjection +
  AudioPlaybackCapture).
- 🧠 **Yerel Whisper.cpp** — `base` veya `small` q5_1 modeli, 20+ kaynak dil, otomatik dil
  algılama.
- 🌍 **Yerel Tencent Hy-MT 2** — 14 hedef dil, üç model varyantı.
- 📱 **Yüzen altyazı** — yarı saydam, sürüklenebilir, konumu hatırlanan, en fazla 2 satır.
- 🎨 **Görünüm ayarları** — yazı boyutu (12–34 sp), 6 renk, arka plan opaklığı (%20–95),
  "sadece orijinal / sadece çeviri / ikisi birden" modu.
- 🌓 **Material 3** — açık/koyu tema, tablet dostu ortalanmış düzen, erişilebilir dokunma
  hedefleri.
- 🔋 **Pil dostu** — ön plan servisi, kısmi uyandırma kilidi, isteğe bağlı kilitte durdurma,
  pil optimizasyonu muafiyeti, bellek baskısında otomatik temizlik.
- 🔔 **Arka plan hata bildirimi** — model/dinleme hataları bildirim olarak gösterilir.
- 🔒 **Gizlilik** — ses ve metin cihazdan çıkmaz; telemetri yok.

---

## ⚙️ Ayarlar

### Modeller

| Model | Boyut | Not |
|-------|-------|-----|
| **Whisper Base q5_1** | ~57 MB | Hızlı, düşük bellek; varsayılan |
| **Whisper Small q5_1** | ~190 MB | Daha doğru, daha yavaş |
| **Hy-MT 2 Q4_K_M** | ~1,13 GB | Dengeli; varsayılan ve önerilen |
| **Hy-MT 2 Q6_K** | ~1,47 GB | Daha yüksek çeviri kalitesi |
| **Hy-MT 2 Q8_0** | ~1,91 GB | En yüksek kalite; yüksek RAM gerektirir |

### Dil ve çeviri

- **Kaynak dil:** `Otomatik algıla` veya Türkçe, İngilizce, İspanyolca, Fransızca, Almanca,
  İtalyanca, Rusça, Japonca, Çince, Korece, Arapça, Portekizce, Felemenkçe, Lehçe, Çekçe.
- **Hedef dil:** Türkçe, İngilizce, Almanca, Fransızca, İspanyolca, İtalyanca, Portekizce,
  Rusça, Çince, Japonca, Korece, Arapça, Felemenkçe, Lehçe.
- **Yerel çeviri aç/kapa:** Kapalıysa yalnızca orijinal metin gösterilir; çeviri modeli
  indirilmez.

### Yüzen altyazı

- Yazı boyutu, renk, arka plan opaklığı.
- Görünüm modu: sadece çeviri / sadece orijinal / ikisi birden.
- 6 saniyelik **önizleme**.
- Sürükle-bırak ve konumu sıfırlama.

### Pil ve davranış

- **Ekran kilitlenince dinlemeyi durdur** (varsayılan: kapalı).
- Bildirim izni (Android 13+).
- Pil optimizasyonundan muafiyet.

---

## 📋 Gereksinimler

| Gereksinim | Değer |
|------------|-------|
| Android sürümü | **10 (API 29) veya üzeri** |
| İşlemci | **arm64-v8a**, ARMv8.2-A+ (dotprod + fp16) |
| RAM | En az **4 GB**; Q4_K_M için **6 GB+** önerilir |
| Boş alan | Modellere göre **~1,2–2,1 GB** |
| İzinler | Kayıt (AudioPlaybackCapture), MediaProjection, üstte gösterim, bildirim (isteğe bağlı) |
| Google Play Services | **Gerekmez** |

> **Not:** Native kütüphaneler `armv8.2-a+dotprod+fp16` için derlenir. Bu komut setlerini
> desteklemeyen eski arm64 cihazlarda uygulama açılırken `SIGILL` ile çökebilir. Test edilen
> cihazlar: Huawei MatePad 11 (Kirin 9000E) ve POCO F5 Pro (Snapdragon 8+ Gen 1).

---

## 📊 Performans

Huawei MatePad 11 (Android 12, 6 GB RAM) üzerinde, Q4_K_M ve Whisper Base ile ölçülen
değerler:

| Ölçüm | Değer |
|-------|-------|
| Whisper gecikmesi (parça başına) | **~3,5–7 sn** |
| Hy-MT çeviri gecikmesi | **~1,6–3,3 sn** |
| Süreç CPU kullanımı | ~%400–700 (4 Whisper + 4 çeviri iş parçacığı) |
| Toplam PSS | ~1,4–1,7 GB |

Whisper, kısa parçalarda bile sesi içeride 30 saniyelik pencereye tamamladığı için sabit bir
maliyet oluşur. Daha düşük gecikme için Whisper Base + Q4_K_M kombinasyonu önerilir.
Gecikme ve CPU değerleri cihazın soğutma durumuna ve arka plan yüküne göre değişebilir.

---

## 🔧 Kaynaktan derleme

### Gereksinimler

- **JDK 17**
- **Android SDK 35** (`compileSdk 35`, `targetSdk 34`)
- **Android NDK 27.2.12479018**
- **CMake 3.22.1**

### Derleme

```bash
git clone https://github.com/tprumut/LiveSub.git
cd LiveSub

# Debug APK
./gradlew assembleDebug

# Çıktı
# app/build/outputs/apk/debug/app-debug.apk
```

Windows'ta:

```powershell
.\gradlew.bat assembleDebug
```

### Yükleme

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

İmza çakışması olursa:

```bash
adb uninstall com.livesub.app
adb install app/build/outputs/apk/debug/app-debug.apk
```

> `adb uninstall` indirilmiş modelleri ve ayarları siler.

### Proje yapısı

```
LiveSub/
├── app/                          # Ana uygulama (Kotlin + Jetpack Compose)
│   ├── src/main/java/com/livesub/app/
│   │   ├── MainActivity.kt       # İzin akışı, ekran yönlendirme
│   │   ├── LiveScreen.kt         # Canlı durum, döküm, metrikler
│   │   ├── SettingsScreen.kt     # Model, dil, overlay, pil ayarları
│   │   ├── CaptureService.kt     # MediaProjection, VAD, iş kuyrukları
│   │   ├── OverlayService.kt     # Yüzen altyazı penceresi
│   │   ├── WhisperEngine.kt      # Whisper JNI + model indirme
│   │   ├── AppLog.kt             # Logcat + dosya günlüğü
│   │   └── LiveSubState.kt       # StateFlow tabanlı tek durum kaynağı
│   ├── src/main/cpp/             # whisper.cpp JNI ve CMake
│   └── src/main/res/
├── translation/                  # Hy-MT çeviri modülü
│   ├── src/main/java/com/livesub/translation/
│   └── src/main/cpp/             # llama.cpp JNI ve CMake
├── vendor/
│   ├── whisper.cpp/              # whisper.cpp + GGML
│   └── llama.cpp/                # llama.cpp + GGML
├── artifacts/                    # Sürüm APK'ları ve SHA-256
└── README.md
```

### Native derleme notları

- whisper.cpp ve llama.cpp Gradle tarafından otomatik derlenir; manuel adım gerekmez.
- CMake dosyalarında `-O3` ve `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16` ayarlıdır.
- `GGML_NATIVE=OFF` olduğu için derleme, derlendiği makinenin özelliklerine göre değişmez.

---

## 🐛 Sorun giderme

### Uygulama loglarını okuma

Huawei/HarmonyOS gibi bazı ROM'lar üçüncü taraf `logcat` satırlarını gizler. LiveSub ayrıca
dosyaya log yazar:

```bash
adb shell run-as com.livesub.app cat files/logs/livesub.log
```

Canlı izlemek için:

```powershell
adb shell "run-as com.livesub.app tail -f files/logs/livesub.log"
```

Standart logcat (logları gizlemeyen cihazlarda):

```bash
adb logcat -c
adb logcat -v time -s LiveSubCapture LiveSubWhisper LiveSubTranslation LiveSubOverlay LiveSubError AndroidRuntime
```

### Yaygın sorunlar

| Sorun | Çözüm |
|-------|-------|
| "Yüzen altyazı izni verilmedi" | Ayarlar → Uygulamalar → LiveSub → **Diğer uygulamaların üzerinde göster** |
| Ses yakalanmıyor | Kaynak uygulama DRM korumalı olabilir. VLC, tarayıcı veya DRM'siz video deneyin. |
| Model indirmesi takıldı | Uygulamayı açık tutun; indirme `.part` dosyasından devam eder. "Modelleri yeniden dene" butonunu kullanın. |
| Model doğrulaması başarısız | İndirilen dosya silinir ve yeniden indirilir. Ağ/proxy indirmeyi bozuyor olabilir. |
| Çeviri modeli yüklenemedi | RAM yetersiz olabilir. Q4_K_M deneyin, arka plan uygulamalarını kapatın. |
| Ekran kapanınca dinleme duruyor | Pil optimizasyonu muafiyetini açın; "ekran kilitlenince durdur" ayarının kapalı olduğundan emin olun. |
| Altyazı çok geç geliyor | Whisper Base + Q4_K_M kullanın; cihazı şarjda ve serin tutun. |
| Uygulama açılışta çöküyor | Cihaz ARMv8.2-A dotprod/fp16 desteklemiyor olabilir. |

---

## 🔐 Gizlilik ve izinler

| İzin | Neden? |
|------|--------|
| `RECORD_AUDIO` | AudioPlaybackCapture ile sistem sesini yakalamak için |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Dinleme sırasında servisin hayatta kalması |
| `SYSTEM_ALERT_WINDOW` | Yüzen altyazı penceresi |
| `POST_NOTIFICATIONS` | Ön plan bildirimi ve hata bildirimleri |
| `WAKE_LOCK` | Ekran kapalıyken dinlemeye devam |
| `INTERNET` | Yalnızca ilk model indirmesi |

- Ses kaydı ve transkript **cihazda** işlenir; hiçbir sunucuya gönderilmez.
- Uygulamada analitik, reklam veya telemetri yoktur.
- Model indirme dışında ağ bağlantısı kullanılmaz.

---

## ❓ SSS

**Mikrofonu mu kullanıyor?**  
Hayır. MediaProjection + AudioPlaybackCapture ile cihazın kendi ses çıkışını yakalar.

**İnternet olmadan çalışır mı?**  
Modeller bir kez indirildikten sonra evet, tamamen çevrimdışı çalışır.

**Neden Google Play Services / ML Kit yok?**  
Amaç tamamen cihaz üzerinde, hesap gerektirmeyen ve gizlilik odaklı bir çözüm sunmak.

**Hangi diller destekleniyor?**  
Whisper 20+ kaynak dili algılar; Hy-MT 14 hedef dile çeviri yapar. Listeler Ayarlar
ekranında görünür.

**APK neden debug imzalı?**  
Bu bir açık kaynak yan projedir; Play Store dağıtımı için release imzası henüz
oluşturulmamıştır. Debug imzası kurulum için yeterlidir.

**Altyazı neden 2 satırla sınırlı?**  
Videoyu kapatmamak için. Yeni satır geldiğinde eski satır yukarı kayar.

---

## 📄 Lisans

LiveSub kaynak kodu **MIT License** ile lisanslanmıştır. Ayrıntılar için [LICENSE](LICENSE).

Üçüncü taraf bileşenler:

- [whisper.cpp](https://github.com/ggerganov/whisper.cpp) — MIT
- [llama.cpp / GGML](https://github.com/ggerganov/llama.cpp) — MIT
- [Tencent Hy-MT 2](https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF) — Apache 2.0

Model ağırlıkları bu repoda yer almaz; ilk çalışmada Hugging Face'ten indirilir.

---

## 🙏 Teşekkürler

- **ggerganov** — whisper.cpp, llama.cpp ve GGML
- **Tencent** — Hy-MT 2 açık modeli
- **Hugging Face** — model barındırma
- **JetBrains / Android Team** — Kotlin, Compose, MediaProjection ve AudioPlaybackCapture

---

<div align="center">

**LiveSub — Sesinizi görünür kılın.**

*Tamamen açık kaynak, tamamen cihazınızda, tamamen sizin kontrolünüzde.*

</div>
