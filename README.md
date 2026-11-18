# RAM Temizleyici (Galaxy Tab S7 FE için)

Arka planda çalışan uygulamaları gerçekten kapatır, RAM kullanımını gösterir ve
istediğiniz uygulamayı (sistem uygulamaları dahil) kaldırmanızı sağlar.

## Özellikler
- Canlı RAM göstergesi (2 saniyede bir güncellenir)
- **Arka planı temizle**: çalışan tüm kullanıcı uygulamalarını zorla durdurur, ne kadar RAM boşaldığını söyler
- Uygulama listesi: çalışanlar en üstte, her birinin kullandığı bellekle birlikte
- Her uygulama için **Durdur** ve **Kaldır** düğmeleri
- Sistem uygulamalarını kaldırma (yalnızca sizin kullanıcınız için; geri alınabilir)
- **Kaldırılanları geri yükle** menüsü
- Uzun basarak bir uygulamayı **koruma** altına alma: temizlikte kapatılmaz
- Ana ekran, klavye, Shizuku ve bu uygulama hiçbir zaman kapatılmaz

## Neden Shizuku?
Android 14'ten itibaren normal uygulamalar başka uygulamaları kapatamıyor.
Shizuku, uygulamaya ADB (geliştirici) yetkisi verir; böylece `am force-stop`
ve `pm uninstall` komutları çalışabilir. Shizuku olmadan uygulama yine açılır
ama "Durdur" ayarlar ekranını açar, "Kaldır" ise normal kaldırma penceresini gösterir.

### Shizuku kurulumu (bir kerelik)
1. Play Store'dan **Shizuku**'yu yükleyin.
2. Ayarlar → Tablet hakkında → Yazılım bilgileri → **Yapım numarası**na 7 kez dokunun.
3. Ayarlar → Geliştirici seçenekleri → **Kablosuz hata ayıklama**yı açın.
4. Shizuku → "Kablosuz hata ayıklama ile başlat" → eşleştirme adımlarını izleyin.
5. Shizuku'da **Başlat** → RAM Temizleyici'ye dönüp **İzin ver**.

Tablet yeniden başlatılınca Shizuku'da yalnızca tekrar **Başlat**'a basmanız yeterli.

## APK nasıl alınır?

### Yol 1: GitHub (bilgisayar gerekmez)
1. github.com'da yeni bir depo açın ve bu klasördeki tüm dosyaları yükleyin
   (`.github` klasörü dahil).
2. **Actions** sekmesinde "APK derle" işi otomatik çalışır (~5 dk).
3. Bittiğinde deponun **Releases → Son sürüm** sayfasından `RamTemizleyici.apk`'yı
   tabletten indirip kurun ("Bilinmeyen kaynaklar"a izin vermeniz istenir).

### Yol 2: Android Studio
Klasörü Android Studio ile açın → **Build → Build APK(s)**.
APK: `app/build/outputs/apk/debug/app-debug.apk`

## Dikkat
- Sistem uygulamalarını kaldırırken dikkatli olun. Bilmediğiniz Samsung/Google
  bileşenlerini kaldırmak sorunlara yol açabilir. Geri yükleme menüsü bunun için var.
- Android RAM'i zaten kendisi yönetir. Sürekli temizlik bazen pil tüketimini artırabilir,
  çünkü kapatılan uygulamalar sıfırdan açılır. Tablet yavaşladığında kullanmanız yeterli.
- `app/debug.keystore` sadece imzalama için kullanılan bir test anahtarıdır.
  Yeni sürümlerin eskisinin üzerine kurulabilmesi için projede duruyor.
