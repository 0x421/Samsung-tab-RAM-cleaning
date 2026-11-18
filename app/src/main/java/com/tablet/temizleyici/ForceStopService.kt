package com.tablet.temizleyici

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Shizuku'suz mod: her uygulamanın "Uygulama bilgisi" sayfasını açar ve
 * "Zorla durdur" düğmesine, ardından onay penceresine kullanıcı yerine basar.
 */
class ForceStopService : AccessibilityService() {

    enum class Result { STOPPED, ALREADY_STOPPED, FAILED }

    companion object {
        @Volatile
        var instance: ForceStopService? = null
            private set

        fun isEnabled(ctx: Context): Boolean {
            val list = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(ctx, ForceStopService::class.java)
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        private val TR = Locale.forLanguageTag("tr")

        // Samsung (One UI), Xiaomi (MIUI/HyperOS) ve standart Android metinleri
        private val STOP_TEXTS = listOf(
            "zorla durdur", "durmaya zorla", "durdurmaya zorla", "zorla kapat", "force stop"
        )
        private val CONFIRM_TEXTS = setOf(
            "tamam", "ok", "evet", "durdur", "zorla durdur", "durmaya zorla",
            "durdurmaya zorla", "zorla kapat", "force stop"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var overlay: TextView? = null

    var busy = false
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        removeOverlay()
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /**
     * apps: (paket adı, görünen ad) listesi.
     * onDone: kapatılan sayısı ve artık arka planda olmayan paketler.
     */
    fun forceStopAll(
        apps: List<Pair<String, String>>,
        onDone: (stopped: Int, cleaned: List<String>) -> Unit,
    ) {
        if (busy) return
        busy = true
        scope.launch {
            var stopped = 0
            val cleaned = ArrayList<String>()
            try {
                showOverlay()
                apps.forEachIndexed { i, (pkg, label) ->
                    updateOverlay("Temizleniyor  ${i + 1} / ${apps.size}\n\n$label")
                    when (forceStopOne(pkg, label)) {
                        Result.STOPPED -> {
                            stopped++
                            cleaned += pkg
                        }
                        Result.ALREADY_STOPPED -> cleaned += pkg
                        Result.FAILED -> {}
                    }
                }
            } finally {
                removeOverlay()
                busy = false
                try {
                    startActivity(
                        Intent(this@ForceStopService, MainActivity::class.java).addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                    )
                } catch (_: Exception) {
                }
                onDone(stopped, cleaned)
            }
        }
    }

    private suspend fun forceStopOne(pkg: String, label: String): Result {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
            )
        } catch (_: Exception) {
            return Result.FAILED
        }

        // 1) "Zorla durdur" düğmesini bekle
        val start = SystemClock.uptimeMillis()
        var readyAt = 0L
        var button: AccessibilityNodeInfo? = null
        while (SystemClock.uptimeMillis() - start < 5000) {
            delay(150)
            val roots = roots()
            val found = roots.firstNotNullOfOrNull { findStopButton(it) } ?: continue
            val now = SystemClock.uptimeMillis()
            // Bir önceki uygulamanın sayfasını yanlışlıkla kullanmamak için
            // sayfada bu uygulamanın adını ara (bulunamazsa 2 sn sonra yine de devam et)
            val pageReady = (label.isNotBlank() && roots.any { hasText(it, label) }) || now - start > 2000
            if (!pageReady) continue
            if (readyAt == 0L) readyAt = now
            val target = clickableOf(found)
            if (target.isEnabled) {
                button = target
                break
            }
            // Düğme pasif: uygulama zaten durmuş
            if (now - readyAt > 700) return Result.ALREADY_STOPPED
        }
        val btn = button ?: return Result.FAILED
        val stopBounds = Rect().also { btn.getBoundsInScreen(it) }
        if (!btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return Result.FAILED

        // 2) Onay penceresindeki "Tamam" / "Zorla durdur" düğmesine bas
        val t2 = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - t2 < 3000) {
            delay(150)
            val confirm = roots().firstNotNullOfOrNull { findConfirm(it, stopBounds) } ?: continue
            clickableOf(confirm).performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(300)
            return Result.STOPPED
        }
        // Bazı cihazlarda onay penceresi çıkmaz: düğme pasifleştiyse durmuştur
        val after = roots().firstNotNullOfOrNull { findStopButton(it) }
        return if (after != null && !clickableOf(after).isEnabled) Result.STOPPED else Result.FAILED
    }

    // ---------- Ekran ağacında arama ----------

    private fun roots(): List<AccessibilityNodeInfo> {
        val list = ArrayList<AccessibilityNodeInfo>()
        try {
            windows?.forEach { w -> w.root?.let { list += it } }
        } catch (_: Exception) {
        }
        if (list.isEmpty()) rootInActiveWindow?.let { list += it }
        return list.filter { it.packageName?.toString() != packageName }
    }

    private fun textOf(n: AccessibilityNodeInfo): String =
        (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase(TR).orEmpty()

    private fun find(
        node: AccessibilityNodeInfo?,
        depth: Int = 0,
        pred: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null
        if (pred(node)) return node
        for (i in 0 until node.childCount) {
            find(node.getChild(i), depth + 1, pred)?.let { return it }
        }
        return null
    }

    private fun findStopButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        find(root) { n ->
            val t = textOf(n)
            t.isNotEmpty() && t.length <= 30 && STOP_TEXTS.any { t == it || t.contains(it) }
        }

    private fun findConfirm(root: AccessibilityNodeInfo, stopBounds: Rect): AccessibilityNodeInfo? {
        // Standart iletişim kutusunun "olumlu" düğmesi
        root.findAccessibilityNodeInfosByViewId("android:id/button1")
            .firstOrNull { it.isEnabled }?.let { return it }
        val r = Rect()
        return find(root) { n ->
            val t = textOf(n)
            if (t !in CONFIRM_TEXTS) return@find false
            n.getBoundsInScreen(r)
            r != stopBounds && clickableOf(n).isEnabled
        }
    }

    private fun hasText(root: AccessibilityNodeInfo, text: String): Boolean =
        try {
            root.findAccessibilityNodeInfosByText(text).isNotEmpty()
        } catch (_: Exception) {
            false
        }

    private fun clickableOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 6) {
            if (n.isClickable) return n
            n = n.parent
            depth++
        }
        return node
    }

    // ---------- Temizlik sırasında ekranı kaplayan perde ----------

    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WindowManager::class.java) ?: return
        val tv = TextView(this).apply {
            setBackgroundColor(Color.argb(240, 16, 20, 32))
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            text = "Temizleniyor…"
        }
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(tv, lp)
            overlay = tv
        } catch (_: Exception) {
        }
    }

    private fun updateOverlay(text: String) {
        overlay?.text = text
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        try {
            getSystemService(WindowManager::class.java)?.removeView(v)
        } catch (_: Exception) {
        }
    }
}
