package com.tablet.temizleyici

import kotlin.system.exitProcess

/**
 * Bu sınıf Shizuku tarafından ayrı bir süreçte, "shell" (ADB) kullanıcısı
 * olarak çalıştırılır. Böylece am force-stop / pm uninstall gibi komutlar
 * normal bir uygulamanın yapamadığı şekilde çalışır.
 */
class ShellService : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(cmd: String?): String {
        if (cmd.isNullOrBlank()) return ""
        return try {
            val process = ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output
        } catch (e: Exception) {
            "HATA: ${e.message}"
        }
    }
}
