# LiveSub teslim durumu

## Aşama 1 — Ses yakalama testi — tamamlandı

- [x] “Bir düğmeye basınca MediaProjection izni istenecek, başka bir uygulamada video çalarken sistem sesi 10 saniye kaydedilip WAV dosyası olarak kaydedilecek ve uygulama içinde oynatılabilecek.”
- [x] “Bu aşamada başka hiçbir şey yapma.” Aşama 1 APK’sında Whisper, çeviri ve yüzen altyazı yoktu.
- [x] “Her aşamanın sonunda nasıl derleneceğini, APK’yı tablete nasıl yükleyeceğimi ve neyi test edeceğimi yaz.” Aşama 1 test yönergeleri README’de bulunuyor.

## Aşama 2 — Yerel Whisper transkripsiyonu — kullanıcı cihaz testiyle tamamlandı

- [x] “Yakalanan sesi gerçek zamanlı parçalara bölüp whisper.cpp ile metne çevir. Sonucu uygulama içinde bir ekranda göster. Gecikmeyi ve işlemci kullanımını ölç.” Kullanıcı MatePad’de Türkçe transkriptin LiveSub içinde göründüğünü doğruladı.
- [x] “Konuşmayı metne çevirmek için whisper.cpp’yi JNI ile entegre et. Varsayılan model ggml base, çoklu dil sürümü, int8 ya da q5 sıkıştırılmış. Ayarlardan small modele geçilebilsin.” Aşama 4 ile Ayarlar ekranına Base/Small model seçici eklendi.
- [x] “Ses kaynağı dili otomatik algılansın, ama elle de seçilebilsin.” Kullanıcı loglarında seçili `tr` diliyle başarılı segment sonucu var.
- [x] “Ses yakalama, Whisper ve çeviri birbirini bloklamasın.” Çeviri Aşama 3’te eklendi; üç bağımsız iş parçacığı (`LiveSubAudioCapture`, `LiveSubWhisperWorker`, `LiveSubTranslationWorker`) ile entegrasyon sağlandı.
- [x] “Modeller APK’nın içinde olmasın. İlk açılışta Hugging Face’ten indirilsin, indirme ilerlemesi gösterilsin, devam ettirilebilsin ve dosya bütünlüğü kontrol edilsin.”
- [ ] “Bellek önemli, cihazda sadece 6 GB RAM var. Whisper ve çeviri modeli aynı anda yüklü kalabilir ama toplam bellek kullanımını ölç ve rapor et.” Toplam PSS göstergesi eklendi; iki model aynı anda çalışırken MatePad’de alınmış gerçek ölçüm henüz yok.
- [x] Bu aşama tamamlanınca Android Studio/Gradle ile derleme, APK yükleme ve MatePad testi yönergelerini yaz; cihazdaki çıkarım gecikmesi ve CPU kullanımını raporla. v0.2.2 logları: 10 sn ses için 2.235–2.489 sn çıkarım, 8.467–9.133 CPU-ms, 350,135–364,373 KB PSS; 2.6 sn ses için 2.576 sn çıkarım, 9,591 CPU-ms ve 362,638 KB PSS.

## Aşama 3 — Yerel Tencent Hy-MT çevirisi — kod ve APK hazır (cihaz testi bekliyor)

- [x] “Çeviri için llama.cpp’yi JNI ile entegre et ve Tencent’in Hy-MT çeviri modelini GGUF formatında çalıştır.” Bağımsız `:translation` Android Library modülü, izole CMake ve llama.cpp JNI wrapper (`liblivesub_translation.so`) ile entegre edildi.
- [x] “Önce Tencent-Hunyuan/Hy-MT2 deposunda Hy-MT2-1.8B için GGUF sürümü olup olmadığını kontrol et. Yoksa tencent/HY-MT1.5-1.8B-GGUF modelini kullan, bu Hugging Face’te var.” `tencent/Hy-MT2-1.8B-GGUF` deposu doğrulandı ve `Hy-MT2-1.8B-Q4_K_M.gguf` (1,133,080,448 bayt, SHA-256: `dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699`) varsayılan çeviri modeli olarak entegre edildi.
- [x] “Modelin resmi çeviri komut şablonunu model sayfasından alıp birebir uygula.” Resmi `Translate the following segment into {target_language}, without additional explanation.` şablonu ve GGUF chat template formatı birebir uygulandı.
- [x] “Çeviri açılıp kapatılabilsin. Kapalıyken sadece orijinal dildeki metin gösterilsin.” UI’a Switch kontrolü eklendi; kapalıyken yalnızca orijinal konuşma dökümü gösterilir ve çeviri işlemi tetiklenmez.
- [x] “Kaynak ve hedef dil seçimi koy. Türkçe desteğini model sayfasından doğrula ve desteklenmiyorsa bana söyle. Çeviri süresini ölç.” Model kartında Türkçe (`tr` / `Turkish`) doğrulandı. Kaynak/hedef seçicileri ve parça başına gecikme ölçer eklendi; gerçek cihaz gecikme sonucu bekleniyor.
- [ ] “Whisper ve çeviri modeli aynı anda yüklü kalabilir ama toplam bellek kullanımını ölç ve rapor et.” İki modelin toplam PSS göstergesi arayüz ve logcat’te bulunuyor; MatePad’de eşzamanlı ölçüm sonucu henüz alınmadı.
- [x] “İş parçacıklarını ayır: ses yakalama, Whisper ve çeviri birbirini bloklamasın.” Ses yakalama, Whisper transkripsiyonu ve Hy-MT çevirisi 3 ayrı iş parçacığında (`LiveSubAudioCapture`, `LiveSubWhisperWorker`, `LiveSubTranslationWorker`) sınırlı kuyruklarla çalışır.
- [x] Aşama 3 `assembleDebug` başarıyla tamamlandı; APK yalnızca ARM64 kütüphanelerini içerir, GGUF model ağırlığı APK’ya eklenmedi. `artifacts/LiveSub-phase3-debug.apk` gerçek cihaz testi içindir.

## Aşama 4 — Yüzen altyazı ve bütünleşik arayüz — kod ve APK hazır (v0.4.0, cihaz testi bekliyor)

- [x] “Yüzen altyazı için SYSTEM_ALERT_WINDOW iznini ve bir overlay servisi kullan.” `OverlayService` + `TYPE_APPLICATION_OVERLAY` penceresi eklendi; izin akışı “Canlı dinle” ve Ayarlar ekranından yönetiliyor.
- [x] “Kutu yarı saydam olsun, sürüklenip konumu değiştirilebilsin, yazı boyutu ve renk ayarlanabilsin.” Arka plan opaklığı (%20–95), 12–34 sp yazı boyutu, 6 hazır yazı rengi ve sürükleme eklendi; konum SharedPreferences’ta saklanıyor.
- [x] “Ekranda en fazla 2 satır gösterilsin ve eski satırlar kayarak silinsin.” Satır havuzu en fazla 2 `TextView` kullanır; yeni satır 200 ms’lik kayma/opaklık animasyonuyla eklenir.
- [x] “İstenirse hem orijinal hem çeviri üst üste gösterilebilsin.” “Orijinal metni göster” ve “Çeviriyi göster” anahtarları eklendi; ikisi açıkken son parçanın kaynak ve hedef metni iki satır olarak gösterilir.
- [x] “Ayarlar ekranını tamamla.” Modeller, dil/çeviri, yüzen altyazı görünümü, pil/davranış, izin durumları ve hakkında bölümleriyle ayrı Ayarlar ekranı eklendi.
- [ ] Gerçek MatePad’de yüzen kutu, sürükleme, iki satır sınırı ve orijinal+çeviri görünümü kullanıcı tarafından doğrulanmalı.

## Aşama 5 — Hata ve pil iyileştirmeleri — kod ve APK hazır (v0.4.0)

- [x] “Hata durumlarını ve pil kullanımını iyileştir.” Hatalar kısa mesaj + teknik ayrıntı + logcat etiketiyle gösteriliyor; arka planda sistem bildirimi gönderiliyor. Model indirmesi 3 kez kaldığı yerden yeniden deneniyor, ilerleme güncellemeleri sınırlandırıldı. Dinleme sırasında kısmi uyandırma kilidi alınıyor, işçi iş parçacıkları arka plan önceliğinde çalışıyor.
- [x] “Servis kapanırsa ya da ekran kilitlenirse ne olacağını kontrol et.” `onDestroy`/`onTaskRemoved` temizliği, MediaProjection iptal geri çağrısı, ekran açma/kapama alıcısı, “ekran kilitlenince durdur” ayarı ve ekran açıldığında kutu yenilemesi eklendi. Servis `START_NOT_STICKY`; süreç ölürse oturum güvenli biçimde sonlanır ve bir sonraki açılışta durum sıfırlanır.
- [x] Pil optimizasyonundan muafiyet ve bildirim izni için Ayarlar ekranında yönlendirme eklendi.
- [x] Bellek baskısında döküm geçmişi 20 satıra düşürülüyor (`onTrimMemory`/`onLowMemory`).
- [ ] Gerçek cihazda ekran kilidi, uzun süreli dinleme ve pil tüketimi ölçülmeli.

## Genel gereksinimler

- [x] “Android için ‘LiveSub’ adında, kayıtlı videoları izlerken başka uygulamaların üstünde yüzen canlı altyazı gösteren bir uygulama yap.” Aşama 4 ile tamamlandı.
- [x] “Uygulama telefonda tamamen çevrimdışı çalışacak: sistem sesini yakalayacak, yerel Whisper ile metne çevirecek, yerel Tencent Hy-MT modeliyle istenen dile çevirecek ve sonucu ekranda yüzen bir kutuda gösterecek.” Ağ yalnızca modelin ilk indirilmesinde kullanılıyor; işlem ve sonraki kullanım cihazda kalıyor.
- [x] “Huawei MatePad 11 (2021), model DBY-W09, Snapdragon 865, 6 GB RAM, HarmonyOS 3.0. Bu cihazda Google Play Hizmetleri yok. Bu yüzden Google ML Kit, Firebase, Google Cloud ve Google Play Services’e bağlı hiçbir kütüphane kullanma. Sadece arm64-v8a için derle. minSdk 29, Kotlin kullan, arayüz için Jetpack Compose kullan.” Bağımlılıklar yalnızca AndroidX/Compose ve kotlinx-coroutines.
- [x] “Ses yakalama için MediaProjection ve AudioPlaybackCapture API’sini kullan, 16 kHz mono PCM formatında. Bu bir foreground service olarak çalışacak ve manifestte foregroundServiceType mediaProjection tanımlı olacak. Konuşma parçalarını ayırmak için Silero VAD ya da basit bir enerji tabanlı sessizlik algılama kullan.” Enerji tabanlı VAD ve ön rulo/örtüşme korunuyor.
- [x] “Yüzen altyazı kutusu için SYSTEM_ALERT_WINDOW iznini ve bir overlay servisi kullan.” Eklendi.
- [x] “Kod yorumları ve açıklamalar Türkçe olsun, değişken ve sınıf adları İngilizce olsun.”
- [x] “Gerçek, derlenebilir proje üret.” v0.4.0 `assembleDebug` ARM64 derlemesi başarılı; APK `artifacts/LiveSub-phase4-debug.apk`.
- [x] “Her aşamayı bitirip bana bildir, sonraki aşamaya ancak ben onay verince geç.” Aşama 4 ve 5 kullanıcının “devral ve bitir” talimatıyla tamamlandı; cihaz doğrulaması için test adımları README’de.
