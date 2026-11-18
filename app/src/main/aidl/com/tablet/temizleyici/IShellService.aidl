package com.tablet.temizleyici;

interface IShellService {
    // Shizuku'nun servisi kapatmak için kullandığı sabit kod
    void destroy() = 16777114;

    // Shell (ADB) yetkisiyle komut çalıştırır, çıktıyı döndürür
    String exec(String cmd) = 1;
}
