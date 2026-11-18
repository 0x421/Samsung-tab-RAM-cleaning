package com.tablet.temizleyici

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SHIZUKU_REQ = 42
        private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
        private val PKG_REGEX = Regex("^[A-Za-z0-9._]+$")
    }

    private lateinit var ramText: TextView
    private lateinit var ramBar: LinearProgressIndicator
    private lateinit var statusText: TextView
    private lateinit var shizukuButton: Button
    private lateinit var cleanButton: Button
    private lateinit var searchBox: EditText
    private lateinit var runningSwitch: MaterialSwitch
    private lateinit var systemSwitch: MaterialSwitch
    private lateinit var countText: TextView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var adapter: AppAdapter

    private var shell: IShellService? = null
    private var allApps: List<AppItem> = emptyList()
    private val iconCache = ConcurrentHashMap<String, AppItem>()
    private var busy = false
    private var binding = false

    private val prefs by lazy { getSharedPreferences("ayarlar", MODE_PRIVATE) }

    // ---------- Shizuku bağlantısı ----------

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val shellConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            binding = false
            if (binder != null && binder.pingBinder()) {
                shell = IShellService.Stub.asInterface(binder)
                updateShizukuState()
                refresh()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binding = false
            shell = null
            updateShizukuState()
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { updateShizukuState() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        binding = false
        shell = null
        updateShizukuState()
    }
    private val permissionResult =
        Shizuku.OnRequestPermissionResultListener { _, result ->
            if (result == PackageManager.PERMISSION_GRANTED) bindShell() else updateShizukuState()
        }

    // ---------- Yaşam döngüsü ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ramText = findViewById(R.id.ramText)
        ramBar = findViewById(R.id.ramBar)
        statusText = findViewById(R.id.statusText)
        shizukuButton = findViewById(R.id.shizukuButton)
        cleanButton = findViewById(R.id.cleanButton)
        searchBox = findViewById(R.id.searchBox)
        runningSwitch = findViewById(R.id.runningSwitch)
        systemSwitch = findViewById(R.id.systemSwitch)
        countText = findViewById(R.id.countText)
        swipe = findViewById(R.id.swipe)

        adapter = AppAdapter(
            onStop = ::stopApp,
            onUninstall = ::confirmUninstall,
            onToggleProtect = ::toggleProtect,
        )
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        cleanButton.setOnClickListener { cleanAll() }
        shizukuButton.setOnClickListener { onShizukuButton() }
        swipe.setOnRefreshListener { refresh() }
        searchBox.doAfterTextChanged { applyFilter() }
        runningSwitch.setOnCheckedChangeListener { _, _ -> applyFilter() }
        systemSwitch.setOnCheckedChangeListener { _, _ -> applyFilter() }

        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)

        // RAM göstergesini her 2 saniyede bir güncelle
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    updateRam()
                    delay(2000)
                }
            }
        }

        updateShizukuState()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // Kaldırma ekranından ya da ayarlardan dönünce listeyi tazele
        if (allApps.isNotEmpty()) refresh()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        if (shell != null) {
            try {
                Shizuku.unbindUserService(userServiceArgs, shellConnection, true)
            } catch (_: Throwable) {
            }
        }
        super.onDestroy()
    }

    // ---------- Menü ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_refresh -> refresh()
            R.id.action_restore -> showRestoreDialog()
            R.id.action_help -> showHelp()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ---------- Shizuku durumu ----------

    private fun updateShizukuState() {
        when {
            shell != null -> {
                statusText.text = "✓ Shizuku bağlı: tam yetki açık"
                shizukuButton.visibility = View.GONE
            }
            !Shizuku.pingBinder() -> {
                statusText.text = "Shizuku çalışmıyor. Arka plan temizliği ve sistem " +
                    "uygulamalarını kaldırmak için Shizuku'yu başlatın (menü → Yardım)."
                shizukuButton.text = "Shizuku'yu aç"
                shizukuButton.visibility = View.VISIBLE
            }
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> {
                statusText.text = "Shizuku çalışıyor, bu uygulamaya izin vermeniz gerekiyor."
                shizukuButton.text = "İzin ver"
                shizukuButton.visibility = View.VISIBLE
            }
            else -> {
                statusText.text = "Shizuku'ya bağlanılıyor…"
                shizukuButton.visibility = View.GONE
                bindShell()
            }
        }
    }

    private fun bindShell() {
        if (binding || shell != null) return
        binding = true
        try {
            Shizuku.bindUserService(userServiceArgs, shellConnection)
        } catch (e: Throwable) {
            binding = false
            statusText.text = "Shizuku hatası: ${e.message}"
        }
    }

    private fun onShizukuButton() {
        if (!Shizuku.pingBinder()) {
            val launch = packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
            try {
                if (launch != null) {
                    startActivity(launch)
                } else {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU_PKG")
                        )
                    )
                }
            } catch (_: Exception) {
                toast("Shizuku'yu Play Store'dan yükleyin")
            }
        } else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SHIZUKU_REQ)
        }
    }

    private suspend fun sh(cmd: String): String? = withContext(Dispatchers.IO) {
        val s = shell ?: return@withContext null
        try {
            s.exec(cmd)
        } catch (e: Exception) {
            null
        }
    }

    // ---------- RAM ----------

    private fun memInfo(): ActivityManager.MemoryInfo {
        val am = getSystemService(ActivityManager::class.java)
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    }

    private fun updateRam() {
        val m = memInfo()
        val used = m.totalMem - m.availMem
        val pct = (used * 100 / m.totalMem).toInt()
        ramBar.setProgressCompat(pct, true)
        ramText.text = "RAM: ${gb(used)} / ${gb(m.totalMem)} kullanılıyor (%$pct)"
    }

    private fun gb(bytes: Long) =
        String.format(Locale.forLanguageTag("tr"), "%.1f GB", bytes / 1073741824.0)

    // ---------- Uygulama listesi ----------

    /** Çalışan süreçleri bulur: paket adı → toplam bellek (KB). */
    private suspend fun runningUsage(): Map<String, Long> {
        val out = sh("ps -A -o RSS,NAME") ?: return emptyMap()
        val map = HashMap<String, Long>()
        out.lineSequence().drop(1).forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2) {
                val rss = parts[0].toLongOrNull() ?: return@forEach
                val pkg = parts.last().substringBefore(':')
                map[pkg] = (map[pkg] ?: 0L) + rss
            }
        }
        return map
    }

    private fun protectedSet(): MutableSet<String> =
        prefs.getStringSet("korunan", emptySet())!!.toMutableSet()

    /** Asla kapatılmayacaklar: bu uygulama, Shizuku, ana ekran, klavye. */
    private fun autoProtected(): Set<String> {
        val s = mutableSetOf(packageName, SHIZUKU_PKG)
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName?.let { s += it }
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')?.let { s += it }
        return s
    }

    private fun refresh() {
        lifecycleScope.launch {
            swipe.isRefreshing = true
            val pm = packageManager
            val apps = withContext(Dispatchers.Default) {
                pm.getInstalledApplications(0)
                    .filter { it.packageName != packageName }
                    .map { ai ->
                        iconCache.getOrPut(ai.packageName) {
                            AppItem(
                                pkg = ai.packageName,
                                label = ai.loadLabel(pm).toString(),
                                icon = ai.loadIcon(pm),
                                isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                            )
                        }
                    }
            }
            val usage = runningUsage()
            val prot = protectedSet() + autoProtected()
            apps.forEach {
                it.running = usage.containsKey(it.pkg)
                it.rssKb = usage[it.pkg] ?: 0L
                it.isProtected = it.pkg in prot
            }
            allApps = apps
            applyFilter()
            swipe.isRefreshing = false
        }
    }

    private fun applyFilter() {
        val q = searchBox.text?.toString()?.trim()?.lowercase().orEmpty()
        val list = allApps.asSequence()
            .filter { systemSwitch.isChecked || !it.isSystem }
            .filter { !runningSwitch.isChecked || it.running }
            .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q) }
            .sortedWith(
                compareByDescending<AppItem> { it.running }
                    .thenByDescending { it.rssKb }
                    .thenBy { it.label.lowercase() }
            )
            .toList()
        adapter.submit(list)
        val runningCount = list.count { it.running }
        countText.text = if (shell != null) {
            "${list.size} uygulama · $runningCount tanesi çalışıyor · Korumak için uzun basın"
        } else {
            "${list.size} uygulama · Korumak için uzun basın"
        }
    }

    // ---------- İşlemler ----------

    private fun cleanAll() {
        if (shell == null) {
            toast("Önce Shizuku'yu bağlayın")
            onShizukuButton()
            return
        }
        if (busy) return
        busy = true
        cleanButton.isEnabled = false
        cleanButton.text = "Temizleniyor…"
        lifecycleScope.launch {
            val before = memInfo().availMem
            val usage = runningUsage()
            val skip = protectedSet() + autoProtected()
            val targets = allApps
                .filter { !it.isSystem && it.pkg in usage && it.pkg !in skip && it.pkg.matches(PKG_REGEX) }
                .map { it.pkg }
            targets.forEach { sh("am force-stop $it") }
            sh("am kill-all") // önbellekteki arka plan süreçlerini de boşalt
            delay(1500)
            val freedMb = ((memInfo().availMem - before) / 1048576).coerceAtLeast(0)
            toast("${targets.size} uygulama kapatıldı · yaklaşık $freedMb MB boşaldı")
            cleanButton.text = "Arka planı temizle"
            cleanButton.isEnabled = true
            busy = false
            refresh()
        }
    }

    private fun stopApp(app: AppItem) {
        if (shell == null || !app.pkg.matches(PKG_REGEX)) {
            // Shizuku yoksa: uygulama bilgisi ekranını aç, oradan "Zorla durdur"
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))
            )
            return
        }
        lifecycleScope.launch {
            sh("am force-stop ${app.pkg}")
            toast("${app.label} durduruldu")
            refresh()
        }
    }

    private fun confirmUninstall(app: AppItem) {
        val message = if (app.isSystem) {
            "Bu bir sistem uygulaması. Sadece sizin kullanıcınız için kaldırılır; " +
                "menüdeki \"Kaldırılanları geri yükle\" ile geri getirebilirsiniz.\n\n" +
                "Dikkat: Önemli sistem bileşenlerini kaldırmak tabletin düzgün çalışmamasına yol açabilir."
        } else {
            "Uygulama ve tüm verileri silinecek."
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("${app.label} kaldırılsın mı?")
            .setMessage(message)
            .setPositiveButton("Kaldır") { _, _ -> uninstall(app) }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun uninstall(app: AppItem) {
        if (shell == null || !app.pkg.matches(PKG_REGEX)) {
            if (app.isSystem) {
                toast("Sistem uygulamalarını kaldırmak için Shizuku gerekli")
                return
            }
            try {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
            } catch (_: Exception) {
                toast("Kaldırma ekranı açılamadı")
            }
            return
        }
        lifecycleScope.launch {
            val cmd = if (app.isSystem) "pm uninstall -k --user 0 ${app.pkg}" else "pm uninstall ${app.pkg}"
            val result = sh(cmd).orEmpty()
            if (result.contains("Success")) {
                toast("${app.label} kaldırıldı")
                iconCache.remove(app.pkg)
            } else {
                toast("Kaldırılamadı: ${result.trim().take(120)}")
            }
            refresh()
        }
    }

    private fun toggleProtect(app: AppItem) {
        if (app.pkg in autoProtected()) {
            toast("${app.label} zaten her zaman korunuyor")
            return
        }
        val set = protectedSet()
        if (!set.add(app.pkg)) set.remove(app.pkg)
        prefs.edit().putStringSet("korunan", set).apply()
        app.isProtected = app.pkg in set
        toast(if (app.isProtected) "${app.label} korunuyor: temizlikte kapatılmaz" else "${app.label} koruması kaldırıldı")
        applyFilter()
    }

    private fun showRestoreDialog() {
        if (shell == null) {
            toast("Bu işlem için Shizuku gerekli")
            return
        }
        lifecycleScope.launch {
            val all = parsePackages(sh("pm list packages -u"))
            val installed = parsePackages(sh("pm list packages"))
            val removed = (all - installed).filter { it.matches(PKG_REGEX) }.sorted()
            if (removed.isEmpty()) {
                toast("Geri yüklenecek uygulama yok")
                return@launch
            }
            val labels = removed.map { pkg ->
                val label = runCatching {
                    packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES)
                        .loadLabel(packageManager).toString()
                }.getOrNull()
                if (label != null && label != pkg) "$label\n$pkg" else pkg
            }.toTypedArray()

            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Kaldırılanları geri yükle")
                .setItems(labels) { _, i ->
                    lifecycleScope.launch {
                        val r = sh("cmd package install-existing ${removed[i]}").orEmpty()
                        toast(if (r.contains("installed", ignoreCase = true)) "Geri yüklendi" else "Olmadı: ${r.trim().take(120)}")
                        refresh()
                    }
                }
                .setNegativeButton("Kapat", null)
                .show()
        }
    }

    private fun parsePackages(out: String?): Set<String> =
        out.orEmpty().lineSequence()
            .map { it.trim().removePrefix("package:") }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun showHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Shizuku nasıl kurulur?")
            .setMessage(
                "1. Play Store'dan \"Shizuku\" uygulamasını yükleyin.\n\n" +
                    "2. Ayarlar → Tablet hakkında → Yazılım bilgileri → \"Yapım numarası\"na 7 kez dokunun " +
                    "(Geliştirici seçenekleri açılır).\n\n" +
                    "3. Ayarlar → Geliştirici seçenekleri → \"Kablosuz hata ayıklama\"yı açın. Tablet Wi-Fi'a bağlı olmalı.\n\n" +
                    "4. Shizuku'yu açın → \"Kablosuz hata ayıklama ile başlat\" → \"Eşleştirme\" adımlarını izleyin " +
                    "(eşleştirme kodu bildirimden girilir; yalnızca ilk seferde).\n\n" +
                    "5. Shizuku'da \"Başlat\"a basın, sonra bu uygulamaya dönüp \"İzin ver\"e dokunun.\n\n" +
                    "Not: Tablet yeniden başlatılınca Shizuku'da tekrar \"Başlat\"a basmanız gerekir."
            )
            .setPositiveButton("Tamam", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
