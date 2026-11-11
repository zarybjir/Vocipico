package cz.prectimito.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ladicí výpis: jak obrazovku vidí Android a co z ní appka vybrala.
 * Spouští se jen ručně (jednou, z nastavení) a ukládá se do Stažených
 * souborů v telefonu. Nikam se neposílá. Obsah hesel se nevypisuje.
 */
object ScreenDump {

    private const val MAX_NODES = 4000

    fun build(root: AccessibilityNodeInfo, scanner: ScreenScanner, found: List<Item>): String {
        val sb = StringBuilder()
        sb.append("Přečti mi to — ladicí výpis\n")
        sb.append("Aplikace: ").append(root.packageName).append('\n')
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("Zařízení: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n\n")
        sb.append("=== Co appka vybrala ke čtení (").append(found.size).append(") ===\n")
        for (it in found) {
            sb.append(it.kind.name).append(": ").append(cut(it.label, 120))
            if (it.value.isNotEmpty()) sb.append(" = ").append(if (it.isPassword) "***" else cut(it.value, 40))
            if (it.state.isNotEmpty()) sb.append(" [").append(it.state).append(']')
            sb.append("  ").append(it.bounds.toShortString()).append('\n')
        }
        sb.append("\n=== Strom prvků (V=viditelný, C=klikatelný, E=pole, S=posuvný, H=nadpis, ")
            .append("X=appka by tuhle větev přeskočila jako zakrytou) ===\n")

        // Projde i neviditelné a zakryté větve, ať je vidět, proč se něco nečte.
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        var count = 0
        val r = Rect()
        while (stack.isNotEmpty()) {
            val (n, depth) = stack.removeLast()
            count++
            try {
                n.getBoundsInScreen(r)
                val cls = n.className?.toString().orEmpty().substringAfterLast('.')
                sb.append("  ".repeat(minOf(depth, 30)))
                sb.append(cls)
                val flags = StringBuilder()
                if (n.isVisibleToUser) flags.append('V')
                if (n.isClickable) flags.append('C')
                if (n.isEditable) flags.append('E')
                if (n.isScrollable) flags.append('S')
                if (Build.VERSION.SDK_INT >= 28 && n.isHeading) flags.append('H')
                if (flags.isNotEmpty()) sb.append(" [").append(flags).append(']')
                if (Build.VERSION.SDK_INT >= 24 && n.drawingOrder != 0) sb.append(" z=").append(n.drawingOrder)
                n.viewIdResourceName?.let { sb.append(" #").append(it.substringAfter(":id/")) }
                val text = n.text
                if (!text.isNullOrBlank()) {
                    sb.append(" \"").append(if (n.isPassword) "***" else cut(text.toString(), 100)).append('"')
                }
                val desc = n.contentDescription
                if (!desc.isNullOrBlank()) sb.append(" d=\"").append(cut(desc.toString(), 60)).append('"')
                sb.append(' ').append(r.toShortString()).append('\n')

                if (count < MAX_NODES) {
                    // Které děti by appka nechala (viditelné a nezakryté)?
                    val kept = scanner.children(n, true)
                    val keptBounds = kept.map { k -> Rect().also { k.getBoundsInScreen(it) } }
                    kept.forEach { ScreenScanner.recycle(it) }
                    val all = ArrayList<AccessibilityNodeInfo>()
                    for (i in 0 until n.childCount) n.getChild(i)?.let { all.add(it) }
                    for (i in all.indices.reversed()) {
                        val c = all[i]
                        val cb = Rect().also { c.getBoundsInScreen(it) }
                        if (c.isVisibleToUser && keptBounds.none { it == cb }) {
                            sb.append("  ".repeat(minOf(depth + 1, 30))).append("X zakryté: ")
                                .append(c.className?.toString().orEmpty().substringAfterLast('.'))
                                .append(' ').append(cb.toShortString()).append('\n')
                        }
                        stack.addLast(c to depth + 1)
                    }
                }
            } catch (_: Exception) {
            } finally {
                ScreenScanner.recycle(n)
            }
        }
        while (stack.isNotEmpty()) ScreenScanner.recycle(stack.removeLast().first)
        if (count >= MAX_NODES) sb.append("… (zkráceno na ").append(MAX_NODES).append(" prvků)\n")
        return sb.toString()
    }

    /** Uloží do Stažených souborů; vrátí, kde soubor najít, nebo null. */
    fun save(context: Context, content: String): String? {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())
        val name = "precti-vypis-$stamp.txt"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) } ?: return null
                "Stažené soubory, $name"
            } else {
                // Starší Android: složka appky (Android/data/cz.prectimito.app/files).
                val dir = context.getExternalFilesDir(null) ?: return null
                File(dir, name).writeText(content)
                "Android, data, cz.prectimito.app, files, $name"
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun cut(s: String, max: Int): String {
        val one = s.replace('\n', ' ')
        return if (one.length <= max) one else one.substring(0, max) + "…"
    }
}
