package com.senseart.subsync

import java.io.File
import java.util.Locale

/** Прост файлов браузър, управляван от стрелките на дистанционното. */
class FileBrowser {

    enum class Kind { UP, DIR, FILE, SUGGESTION, INFO }

    data class Entry(val file: File?, val label: String, val kind: Kind)

    /** null = началният екран (предложения + устройства за съхранение). */
    var dir: File? = null
        private set
    var entries: List<Entry> = emptyList()
        private set
    var sel = 0

    /** Последно ползваната папка – показва се на началния екран. */
    var recentDir: File? = null

    var suggestions: List<File> = emptyList()
        set(v) {
            field = v
            if (dir == null) {
                val keep = sel
                open(null)
                sel = keep.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
            }
        }

    fun open(d: File?) {
        dir = d
        val list = ArrayList<Entry>()
        if (d == null) {
            for (f in suggestions) list.add(Entry(f, "★ " + f.name, Kind.SUGGESTION))
            recentDir?.takeIf { it.isDirectory }?.let { list.add(Entry(it, "◷ Последна папка: " + it.name, Kind.DIR)) }
            for (r in roots()) list.add(Entry(r, "▸ " + rootLabel(r), Kind.DIR))
        } else {
            list.add(Entry(d.parentFile, "⬑ ..", Kind.UP))
            val children = d.listFiles()
            if (children == null) {
                list.add(Entry(null, "(няма достъп – дайте „Достъп до всички файлове“)", Kind.INFO))
            } else {
                children.filter { it.isDirectory && !it.name.startsWith(".") }
                    .sortedBy { it.name.lowercase(Locale.ROOT) }
                    .forEach { list.add(Entry(it, "▸ " + it.name, Kind.DIR)) }
                children.filter { it.isFile && isSubtitle(it) }
                    .sortedBy { it.name.lowercase(Locale.ROOT) }
                    .forEach { list.add(Entry(it, it.name, Kind.FILE)) }
                if (list.size == 1) list.add(Entry(null, "(няма папки и .srt файлове)", Kind.INFO))
            }
        }
        entries = list
        sel = if (list.size > 1 && list[0].kind == Kind.UP) 1 else 0
    }

    /** Нагоре с една папка. false, ако вече сме на началния екран. */
    fun up(): Boolean {
        val d = dir ?: return false
        val isRoot = roots().any { it.absolutePath == d.absolutePath }
        val prev = d
        open(if (isRoot) null else d.parentFile)
        val i = entries.indexOfFirst { it.file?.absolutePath == prev.absolutePath }
        if (i >= 0) sel = i
        return true
    }

    companion object {
        fun isSubtitle(f: File) = f.name.lowercase(Locale.ROOT).endsWith(".srt")

        fun roots(): List<File> {
            val res = ArrayList<File>()
            val internal = File("/storage/emulated/0")
            if (internal.exists()) res.add(internal)
            File("/storage").listFiles()?.forEach {
                if (it.isDirectory && it.name != "emulated" && it.name != "self" && it.canRead()) res.add(it)
            }
            File("/mnt/media_rw").listFiles()?.forEach { if (it.isDirectory && it.canRead()) res.add(it) }
            return res.distinctBy { it.absolutePath }
        }

        private fun rootLabel(f: File) =
            if (f.absolutePath == "/storage/emulated/0") "Вътрешна памет" else "USB / карта (${f.name})"

        /** Опростява име за сравнение: малки букви, без разширение, само букви и цифри. */
        fun normalize(name: String): String {
            val base = name.substringAfterLast('/').let {
                val dot = it.lastIndexOf('.')
                if (dot > 0 && it.length - dot <= 5) it.substring(0, dot) else it
            }
            return base.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        }

        fun matches(srt: File, title: String): Boolean {
            val a = normalize(srt.name).removeSuffix("bg").removeSuffix("bul")
            val b = normalize(title)
            if (a.length < 4 || b.length < 4) return false
            return a == b || a.startsWith(b) || b.startsWith(a)
        }

        /**
         * Търси .srt файлове, които съвпадат с името на филма.
         * Ако плейърът е дал пълния път до видеото, гледа първо в неговата папка.
         */
        fun findSuggestions(title: String?, videoPath: String?, extraDirs: List<File>): List<File> {
            val found = LinkedHashSet<File>()
            if (videoPath != null) {
                val v = File(videoPath)
                v.parentFile?.listFiles()?.filter { it.isFile && isSubtitle(it) && matches(it, v.name) }
                    ?.sortedBy { it.name.length }?.forEach { found.add(it) }
            }
            if (title.isNullOrBlank()) return found.toList()
            for (d in extraDirs) {
                d.listFiles()?.filter { it.isFile && isSubtitle(it) && matches(it, title) }?.forEach { found.add(it) }
            }
            var budget = 4000
            fun walk(d: File, depth: Int) {
                if (budget-- <= 0 || depth > 5 || found.size >= 10) return
                val ch = d.listFiles() ?: return
                for (f in ch) {
                    if (f.isFile && isSubtitle(f) && matches(f, title)) found.add(f)
                }
                for (f in ch) {
                    if (f.isDirectory && !f.name.startsWith(".") && f.name != "Android") walk(f, depth + 1)
                }
            }
            for (r in roots()) walk(r, 0)
            return found.toList()
        }
    }
}
