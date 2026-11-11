package com.senseart.subsync

import java.io.File
import java.util.Locale

/**
 * Файлов браузър за панела: вътрешна памет, USB/карти и мрежови папки.
 * Съдържанието се подготвя с [build] (може да ходи по мрежата – извън главната нишка),
 * а се показва с [show] (в главната нишка).
 */
class FileBrowser {

    enum class Kind { UP, DIR, FILE, SUGGESTION, INFO, SAVE_HERE }

    data class Entry(val file: VFile?, val label: String, val kind: Kind)

    /** null = началният екран (предложения + устройства + мрежови папки). */
    var dir: VFile? = null
        private set
    var entries: List<Entry> = listOf(Entry(null, "Зареждане…", Kind.INFO))
        private set
    var sel = 0

    fun show(d: VFile?, list: List<Entry>, selectPath: String? = null) {
        dir = d
        entries = list
        val i = if (selectPath != null) list.indexOfFirst { it.file?.path == selectPath } else -1
        sel = when {
            i >= 0 -> i
            list.size > 1 && list[0].kind == Kind.UP -> 1
            else -> 0
        }
    }

    fun showLoading(d: VFile?) {
        dir = d
        entries = listOf(Entry(null, "Зареждане…", Kind.INFO))
        sel = 0
    }

    companion object {
        fun localRoots(): List<VFile> {
            val res = ArrayList<File>()
            val internal = File("/storage/emulated/0")
            if (internal.exists()) res.add(internal)
            File("/storage").listFiles()?.forEach {
                if (it.isDirectory && it.name != "emulated" && it.name != "self" && it.canRead()) res.add(it)
            }
            File("/mnt/media_rw").listFiles()?.forEach { if (it.isDirectory && it.canRead()) res.add(it) }
            return res.distinctBy { it.absolutePath }.map { LocalFile(it) }
        }

        /** Всички места, в които може да има филми: локални устройства + мрежови папки. */
        fun allRoots(shares: List<SmbShare>): List<VFile> = localRoots() + shares.map { it.root }

        fun isRoot(d: VFile): Boolean = when (d) {
            is SmbFile -> d.rel.isEmpty()
            else -> localRoots().any { it.path == d.path }
        }

        private fun rootLabel(f: VFile) = when {
            f is SmbFile -> "🖧 Компютър: " + f.share.label
            f.path == "/storage/emulated/0" -> "▸ Вътрешна памет"
            else -> "▸ USB / карта (${f.name})"
        }

        fun build(
            d: VFile?,
            suggestions: List<VFile>,
            recent: VFile?,
            shares: List<SmbShare>,
            foldersOnly: Boolean = false,
            saveName: String = "",
            filmDir: VFile? = null,
        ): List<Entry> {
            val list = ArrayList<Entry>()
            if (d == null && foldersOnly) {
                // Избор на папка за копие: само вътрешната памет и USB/карти на приставката.
                for (r in localRoots()) list.add(Entry(r, rootLabel(r), Kind.DIR))
                return list
            }
            if (d == null) {
                for (f in suggestions) list.add(Entry(f, "★ " + f.name + "   (" + shortParent(f) + ")", Kind.SUGGESTION))
                // Папката на текущия филм; „последна папка“ (от предишния филм) – само ако тя не е известна.
                if (filmDir != null) list.add(Entry(filmDir, "🎬 Папката на филма: " + filmDir.name, Kind.DIR))
                else if (recent != null) list.add(Entry(recent, "◷ Последна папка: " + recent.name, Kind.DIR))
                for (r in allRoots(shares)) list.add(Entry(r, rootLabel(r), Kind.DIR))
                if (shares.isEmpty()) {
                    list.add(Entry(null, "(мрежови папки се добавят от приложението → „Мрежови папки“)", Kind.INFO))
                }
                return list
            }
            list.add(Entry(d.parent, "⬑ ..", Kind.UP))
            if (foldersOnly) list.add(Entry(d, "💾 Запиши тук:  $saveName", Kind.SAVE_HERE))
            val children = d.list()
            if (children == null) {
                val why = if (d is SmbFile) "(няма връзка с компютъра или няма достъп)"
                else "(няма достъп – дайте „Достъп до всички файлове“)"
                list.add(Entry(null, why, Kind.INFO))
                return list
            }
            children.filter { it.isDirectory && !it.name.startsWith(".") && !it.name.startsWith("$") }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
                .forEach { list.add(Entry(it, "▸ " + it.name, Kind.DIR)) }
            if (!foldersOnly) {
                children.filter { !it.isDirectory && SubtitleLocator.isSubtitle(it.name) }
                    .sortedBy { it.name.lowercase(Locale.ROOT) }
                    .forEach { list.add(Entry(it, it.name, Kind.FILE)) }
            }
            if (list.size == 1) list.add(Entry(null, "(няма папки и .srt файлове)", Kind.INFO))
            return list
        }

        private fun shortParent(f: VFile): String {
            val p = f.parent ?: return ""
            return if (f is SmbFile) f.share.share + "/" + p.name else p.name
        }
    }
}
