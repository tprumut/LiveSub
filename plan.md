# LiveSub uygulama planı

## Amaç ve uygulama yaklaşımı

Native Android uygulaması Kotlin ve Jetpack Compose ile geliştirilecek. Hedef Huawei MatePad 11 DBY-W09, Snapdragon 865, 6 GB RAM ve HarmonyOS 3.0; Google Play Services veya Google ML Kit kullanılmayacak. `minSdk 29`, yalnızca `arm64-v8a` hedeflenecek. Sistem oynatma sesi `MediaProjection` + `AudioPlaybackCapture` ile 16 kHz mono PCM yakalanacak. Yakalama foreground service içinde çalışacak; ses yakalama, Whisper çıkarımı ve sonraki çeviri işleri ayrı yürütme kuyruklarında birbirini bloklamayacak.

Aşamalar: (1) 10 saniyelik WAV yakalama ve oynatma; (2) enerji tabanlı sessizlik bölütleme, yerel whisper.cpp JNI transkripsiyonu, gecikme/CPU gösterimi; (3) cihazda llama.cpp ile Tencent Hy-MT çeviri; (4) sürüklenebilir altyazı overlay ve birleşik ayarlar; (5) hata, servis/ekran kilidi ve pil iyileştirmeleri. Kullanıcı Aşama 2 ve Aşama 3 için açıkça onay verdi; 12 Kasım 2026’daki “devral ve bitir” talimatıyla Aşama 4 ve Aşama 5 kod olarak tamamlandı. Gerçek cihaz doğrulaması README’deki test adımlarıyla yapılacaktır.

Whisper modeli APK’ya eklenmeyecek. Aşama 2’de Hugging Face’ten çok dilli `ggml-base-q5_1.bin` varsayılan olarak indirilecek; model seçicisinden çok dilli `ggml-small-q5_1.bin` seçilebilecek. İndirme ilerlemesi ve devam ettirme sağlanacak; dosya boyutu ve SHA-256 özeti doğrulanacak. Her iki model de cihazda saklanacak, model değişiminde önceki native context serbest bırakılacak.

## Proje yapısı

- `app/src/main/java/com/livesub/app/`: Compose ekranı, izin akışı, foreground ses yakalama servisi, VAD/bölütleme, model indirme, ölçüm ve UI durumları.
- `app/src/main/cpp/`: JNI köprüsü ve whisper.cpp CMake entegrasyonu.
- `vendor/whisper.cpp/`: Sabitlenmiş upstream whisper.cpp kaynak ve GGML kodu/lisansları.
- `translation/`: llama.cpp JNI ve Hy-MT indirme/çeviri mantığı için izole Gradle/CMake Android Library modülü.
- `vendor/llama.cpp/`: Sabitlenmiş llama.cpp/GGML kaynakları; Whisper GGML kopyasından ayrı derlenir.
- `app/src/main/AndroidManifest.xml`: izinler, `mediaProjection` foreground service türü, uygulama bileşenleri.
- `artifacts/`: her kabul edilen aşamanın yeniden derlenebilir APK çıktısı ve SHA-256 özeti.
- `README.md`: hedef cihaz, derleme/yükleme ve aşama bazlı test notları.
- `TODO.md`: kullanıcının her aşama için verdiği davranış kabul ölçütleri ve durum.

## Tasarım

Üç yön: **Ses laboratuvarı / teknik** (olasılık 0,06); **sakin altyazı aracı** (olasılık 0,08); **minimal yayın kontrol paneli** (olasılık 0,04). Seçim: ses laboratuvarı/teknik.

- Tasarım hareketi: Modernist ölçüm cihazları ve taşınabilir ses kayıt terminallerinden esinlenen işlevsel arayüz.
- İlkeler: çalışma durumunu sürekli görünür kıl; işlem ölçümlerini metinle ve okunaklı göster; önemli işlemleri tek dokunuşta erişilebilir tut; çevrimdışı işleyişi ve olası ses yakalama kısıtlarını açık ifade et.
- Renk: koyu grafit zemin, soluk buz beyaz metin, tek imza rengi olarak canlı teal; ses ve etkin kayıt durumu için amber ikincil uyarı rengi.
- Yerleşim: üstte uygulama/çalışma durumu; ortada tek büyük kayıt eylemi ve dalga/işlem satırı; altta kayan transkripsiyon geçmişi ve ölçüm özeti. Ekran daraldığında öncelikli durum satırı korunur.
- İmza öğeleri: ince ses seviyesi/işlem şeridi; model durumu rozeti; zaman damgalı metin satırları.
- Etkileşim: izin, model hazır olma ve kayıt durumları adım adım açıklanır; başarısızlıkta teknik hata kodu saklanır ve kullanıcıya anlaşılır sebep sunulur.
- Animasyon: kayıtta yavaş, düşük genlikli seviye göstergesi; metin parçaları kısa ve yumuşak biçimde eklenir; dekoratif geçişlerle işlem gecikmesi yaratılmaz.
- Tipografi: sistem sans-serif; başlıklar orta ağırlıkta, zaman ve metrikler eş aralıklı rakamlarla.
- Marka: “LiveSub”, videoları cihaz üzerinde anlaşılır altyazıya dönüştüren, gizlilik odaklı yerel yardımcı; kişilik: ölçülü, güvenilir, teknik.
- Ses tonu: kısa, somut, kullanıcıyı yönlendiren. Örnekler: “Sistem sesini dinlemek için kaydı başlat.” “Model indiriliyor; bu işlem yalnızca ilk kullanımda gerekir.”
- Wordmark: küçük dalga çizgisi ile iki satır altyazı işaretini birleştiren teal simge; imza rengi `#16A394`.

## Aşama 2 uygulama kararı

Resmi whisper.cpp Android örneğindeki C API/JNI yaklaşımı ve upstream CMake yapısı temel alınacak. JNI, dosyadan model yükleme ve FloatArray ses transkripsiyonunu sağlayacak. Yerel ses servisi 16 kHz PCM akışında enerji eşiği ve sessizlik süresi ile konuşma bölümleri çıkaracak; sınırda kısa örtüşme korunacak. Sınırlı kuyruk, yakalama iş parçacığının çıkarım tarafından bekletilmesini önleyecek. Ses kaydından önce `RECORD_AUDIO` runtime izni ve MediaProjection onayı istenecek. Ekranda kaynak dili otomatik seçilecek ve elle dil seçimi sunulacak. Her parça için ses süresi ve çıkarım gecikmesi ölçülecek; süreç CPU zamanı ve Android PSS bellek ölçümü raporlanacak. Gerçek MatePad ölçümleri cihazda ayrıca doğrulanmalı.

## Aşama 3 uygulama ve test durumu

Tencent'in `tencent/Hy-MT2-1.8B-GGUF` deposundaki Q4_K_M modeli seçildi. 1,133,080,448 bayt dosya uygulama özel alanına Hugging Face Range indirmesiyle alınır ve SHA-256 doğrulanır; APK ve kaynak deposuna model ağırlığı eklenmez. Kullanıcının belirttiği `Translate the following segment into {target_language}, without additional explanation.` promptu, modelin chat template'iyle sarmalanarak JNI'ye gönderilir. Çeviri açık/kapalı, kaynak/hedef dil, Whisper/Hy-MT latency ve süreç PSS göstergeleri eklendi. Ses yakalama, Whisper ve çeviri worker'ları ayrı kuyruk/iş parçacıkları kullanır.

GitHub'daki Aşama 3 commit'i Android ARM64 temiz derlemesinden geçti. v0.3.0 APK'sı test içindir. MatePad'de ilk model indirimi, gerçek çeviri sonucu, çeviri süresi ve Whisper+Hy-MT toplam PSS henüz kullanıcı tarafından doğrulanmadı; Aşama 4'e başlamadan önce cihaz testi ve kullanıcı onayı beklenir.

## Aşama 4 uygulama kararı ve durumu

Yüzen altyazı, `TYPE_APPLICATION_OVERLAY` penceresi kullanan ayrı bir `OverlayService` içinde programatik `TextView` satırlarıyla uygulandı. Compose'u overlay penceresine taşımak yerine iki satırlık hafif bir görünüm seçildi; böylece pencere her altyazı güncellemesinde yeniden kompoze edilmez. `SYSTEM_ALERT_WINDOW` izni yoksa kutu gösterilmez ve altyazılar yalnızca uygulama içinde kalır. Kutu sürüklenebilir, konumu SharedPreferences'ta saklanır. Satır havuzu en fazla iki `TextView` tutar; yeni satır 200 ms'lik kayma/opaklık animasyonuyla eklenir. "Orijinal + çeviri" modunda son parçanın iki dili, tek dil modunda önceki ve son parça gösterilir. Ayarlar ekranı modeller, dil, kutu görünümü (12–34 sp, 6 renk, %20–95 opaklık), pil/davranış ve hakkında bölümlerine ayrıldı. v0.4.0 APK'sı ARM64 olarak derlendi; gerçek MatePad'de sürükleme ve iki satır davranışı doğrulanmalıdır.

## Aşama 5 uygulama kararı ve durumu

Hatalar `LiveSubState.setError` (oturumu sonlandıran) ve `reportError` (tek parçayı etkileyen) olarak ayrıldı; her ikisi de kısa mesaj, teknik ayrıntı ve arka plan bildirimi üretir. Model indirmeleri 3 denemeli, kaldığı yerden devam eden ve ilerleme güncellemelerini 200–250 ms'de bir yayan bir döngüye alındı. Dinleme sırasında `PARTIAL_WAKE_LOCK` alınır, işçi iş parçacıkları `THREAD_PRIORITY_BACKGROUND` önceliğinde çalışır ve durdurma sırasında kesilir. Ekran kapanması/açılması `BroadcastReceiver` ile izlenir; varsayılan davranış dinlemeyi sürdürmek, isteğe bağlı ayarla kilitte durdurmaktır. `onDestroy`, `onTaskRemoved` ve MediaProjection iptali kaynakları (ses kaydı, projeksiyon, uyandırma kilidi, alıcı, yüzen kutu) temizler. Bellek baskısında döküm 20 satıra indirilir. Cihazda uzun süreli pil tüketimi ve ekran kilidi testi beklenmektedir.

## Teknik kaynaklar

- [Whisper.cpp Android örneği](https://github.com/ggml-org/whisper.cpp/tree/master/examples/whisper.android)
- [Whisper.cpp Android örnek README](https://github.com/ggml-org/whisper.cpp/blob/master/examples/whisper.android/README.md)
- [Whisper.cpp modelleri ve nicemleme notları](https://github.com/ggml-org/whisper.cpp/blob/master/models/README.md)
- [Android NDK ve CMake](https://developer.android.com/ndk/guides/cmake)
- Android demo için incelenen upstream commit: `bd9e1a12352fa57e448bdea6a0649aa9c24c64aa`.
- `ggerganov/whisper.cpp` deposundaki `ggml-base-q5_1.bin` boyutu HEAD yanıtında 59,707,625 bayt; `ggml-base-q5_1.bin` çok dilli `base` modelin q5 nicemlenmiş biçimidir.
