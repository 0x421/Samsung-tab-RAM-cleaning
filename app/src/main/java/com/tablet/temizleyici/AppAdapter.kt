package com.tablet.temizleyici

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppItem(
    val pkg: String,
    val label: String,
    val icon: Drawable,
    val isSystem: Boolean,
) {
    var running: Boolean = false
    var rssKb: Long = 0L
    var isProtected: Boolean = false
}

class AppAdapter(
    private val onStop: (AppItem) -> Unit,
    private val onUninstall: (AppItem) -> Unit,
    private val onToggleProtect: (AppItem) -> Unit,
) : RecyclerView.Adapter<AppAdapter.VH>() {

    private var items: List<AppItem> = emptyList()

    fun submit(list: List<AppItem>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val name: TextView = v.findViewById(R.id.name)
        val pkg: TextView = v.findViewById(R.id.pkg)
        val info: TextView = v.findViewById(R.id.info)
        val stop: Button = v.findViewById(R.id.stop)
        val remove: Button = v.findViewById(R.id.remove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val a = items[position]
        h.icon.setImageDrawable(a.icon)
        h.name.text = a.label
        h.pkg.text = a.pkg

        val parts = mutableListOf<String>()
        if (a.running) {
            parts += if (a.rssKb > 0) "● Çalışıyor · ${a.rssKb / 1024} MB" else "● Arka planda"
        }
        if (a.isSystem) parts += "Sistem"
        if (a.isProtected) parts += "🛡 Korunuyor"
        h.info.text = parts.joinToString("   ")
        h.info.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE

        h.stop.setOnClickListener { onStop(a) }
        h.remove.setOnClickListener { onUninstall(a) }
        h.itemView.setOnLongClickListener {
            onToggleProtect(a)
            true
        }
    }
}
