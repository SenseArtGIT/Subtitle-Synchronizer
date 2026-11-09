package com.senseart.subsync

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/**
 * Намира .srt файла за даден филм, където и да е филмът:
 * вътрешна памет, USB флашка/диск, карта или споделена папка на компютъра.
 */
object SubtitleLocator {

    private val SUB_DIRS = setOf("subs", "sub", "subtitles", "subtitle", "субтитри")

    fun isSubtitle(name: String) = name.lowercase(Locale.ROOT).endsWith(".srt")

    /** Опростява име за сравнение: малки букви, без разширение, само букви и цифри. */
    fun normalize(name: String): String {
        val base = name.substringAfterLast('/').let {
            val dot = it.lastIndexOf('.')
            if (dot > 0 && it.length - dot <= 5) it.substring(0, dot) else it
        }
        return base.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    }

    /** Дали .srt файлът [srtName] е за видеото [videoName] (напр. film.bg.srt за film.mkv). */
    fun matches(srtName: String, videoName: String): Boolean {
        val a = normalize(srtName)
        val b = normalize(videoName)
        if (a.length < 4 || b.length < 4) return false
        return a == b || a.startsWith(b) || b.startsWith(a)
    }

    /** По-малко = по-добре: точно същото име, после .bg/.bul, после останалите. */
    fun rank(srtName: String, videoName: String): Int {
        val a = normalize(srtName)
        val b = normalize(videoName)
        return when {
            a == b -> 0
            a == b + "bg" || a == b + "bul" || a == b + "bulgarian" -> 1
            a.startsWith(b) -> 2
            else -> 3
        }
    }

    /** Декодира %XX като UTF-8 (без да превръща „+“ в интервал). */
    fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        val out = ByteArrayOutputStream()
        var i = 0
        var runStart = 0
        while (i < s.length) {
            if (s[i] == '%' && i + 2 < s.length) {
                val h = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (h != null) {
                    out.write(s.substring(runStart, i).toByteArray(Charsets.UTF_8))
                    out.write(h)
                    i += 3
                    runStart = i
                    continue
                }
            }
            i++
        }
        out.write(s.substring(runStart).toByteArray(Charsets.UTF_8))
        return out.toString("UTF-8")
    }

    /**
     * Превръща адреса, с който е отворен филмът, в истински файл, ако може.
     * [uri] – каквото е подал файловият мениджър или плейърът; [dataPath] – колоната _data, ако я има.
     */
    fun resolveVideo(uri: String?, dataPath: String?, shares: List<SmbShare>): VFile? {
        val candidates = listOfNotNull(dataPath, uri).map { percentDecode(it) }
        for (raw in candidates) {
            resolveOne(raw, shares)?.let { return it }
        }
        return null
    }

    private fun resolveOne(raw: String, shares: List<SmbShare>): VFile? {
        // 1. Обикновен път или file://
        if (raw.startsWith("/")) return LocalFile(File(raw))
        if (raw.startsWith("file://")) return LocalFile(File(raw.removePrefix("file://").substringBefore('?')))

        // 2. smb://хост/папка/път (може и вътре в друг адрес)
        val smbAt = raw.indexOf("smb://", ignoreCase = true)
        if (smbAt >= 0) {
            val rest = raw.substring(smbAt + 6).substringBefore('?')
            val parts = rest.split('/').filter { it.isNotEmpty() }
            if (parts.size >= 3) {
                // smb://[домейн;]потребител:парола@хост/папка/... – ES File Explorer понякога ги вгражда.
                val auth = if (parts[0].contains('@')) parts[0].substringBeforeLast('@') else ""
                val host = parts[0].substringAfterLast('@')
                val cfg = shares.firstOrNull { it.isHost(host) && it.share.equals(parts[1], true) }
                    ?: fromUrlAuth(host, parts[1], auth)
                return SmbFile(cfg, parts.drop(2).joinToString("/"))
            }
        }

        // 3. Локален път, скрит в content:// или http адрес
        val storageAt = raw.indexOf("/storage/")
        if (storageAt >= 0) return LocalFile(File(raw.substring(storageAt).substringBefore('?')))
        val mntAt = raw.indexOf("/mnt/media_rw/")
        if (mntAt >= 0) return LocalFile(File(raw.substring(mntAt).substringBefore('?')))

        // 4. Адрес от файлов мениджър, който сам стриймва от компютъра (напр. http://127.0.0.1:port/...).
        //    Търсим име на настроена споделена папка като част от пътя.
        val path = raw.substringAfter("://", raw).substringAfter('/', "").substringBefore('?')
        val segs = path.split('/').filter { it.isNotEmpty() }
        var best: VFile? = null
        var bestScore = -1
        for (cfg in shares) {
            for (i in segs.indices) {
                if (!segs[i].equals(cfg.share, true) || i == segs.lastIndex) continue
                val hostBefore = segs.take(i).any { cfg.isHost(it) }
                val score = (if (hostBefore) 10 else 0) + i
                if (score > bestScore) {
                    bestScore = score
                    best = SmbFile(cfg, segs.drop(i + 1).joinToString("/"))
                }
            }
        }
        return best
    }

    /** Мрежова папка, сглобена от адреса (ако в него има потребител/парола – с тях, иначе като гост). */
    private fun fromUrlAuth(host: String, share: String, auth: String): SmbShare {
        var userPart = auth.substringBefore(':')
        val pass = if (auth.contains(':')) auth.substringAfter(':') else ""
        var domain = ""
        if (userPart.contains(';')) {
            domain = userPart.substringBefore(';'); userPart = userPart.substringAfter(';')
        }
        return SmbShare(host, share, userPart, pass, domain)
    }

    /** Обратното на [VFile.path]: прави файл от запазен път (локален или smb://). */
    fun fromPath(path: String, shares: List<SmbShare>): VFile? {
        if (path.startsWith("/")) return LocalFile(File(path))
        val low = path.lowercase(Locale.ROOT)
        for (cfg in shares) {
            if (low == cfg.id || low.startsWith(cfg.id + "/")) {
                return SmbFile(cfg, path.substring(cfg.id.length).trim('/'))
            }
        }
        return null
    }

    /** Името на видео файла от адреса, ако не е известно иначе. */
    fun nameFromUri(uri: String): String =
        percentDecode(uri.substringBefore('?').substringBefore('#')).trimEnd('/').substringAfterLast('/')

    /** .srt файлове до видеото и в подпапка Subs/Subtitles до него. */
    fun siblings(video: VFile): List<VFile> {
        val dir = video.parent ?: return emptyList()
        val all = dir.list() ?: return emptyList()
        val found = all.filter { !it.isDirectory && isSubtitle(it.name) && matches(it.name, video.name) }
            .toMutableList()
        if (found.isEmpty()) {
            for (d in all) {
                if (d.isDirectory && d.name.lowercase(Locale.ROOT) in SUB_DIRS) {
                    d.list()?.filter { !it.isDirectory && isSubtitle(it.name) }?.let { subs ->
                        val m = subs.filter { matches(it.name, video.name) }
                        // В папка Subs често името е различно – ако има само един .srt, взимаме него.
                        found.addAll(if (m.isNotEmpty()) m else if (subs.size == 1) subs else emptyList())
                    }
                }
            }
        }
        // Ако в папката има само един филм и само един .srt, той е почти сигурно правилният.
        if (found.isEmpty()) {
            val videos = all.count { !it.isDirectory && isVideo(it.name) }
            val srts = all.filter { !it.isDirectory && isSubtitle(it.name) }
            if (videos == 1 && srts.size == 1) found.add(srts[0])
        }
        return found.sortedWith(compareBy({ rank(it.name, video.name) }, { it.name.length }))
    }

    fun isVideo(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in setOf("mkv", "mp4", "avi", "ts", "m2ts", "mts", "mov", "wmv", "m4v", "mpg", "mpeg", "vob", "webm", "iso")
    }

    /**
     * Търси по име в дадените места (папки). [budget] ограничава броя прегледани папки,
     * за да не се бави с часове при огромни дискове.
     */
    fun searchByName(videoName: String, roots: List<VFile>, budget: Int = 2000, maxDepth: Int = 5): List<VFile> {
        val found = LinkedHashSet<VFile>()
        var left = budget
        fun walk(d: VFile, depth: Int) {
            if (left-- <= 0 || depth > maxDepth || found.size >= 10) return
            val ch = d.list() ?: return
            for (f in ch) if (!f.isDirectory && isSubtitle(f.name) && matches(f.name, videoName)) found.add(f)
            for (f in ch) {
                if (f.isDirectory && !f.name.startsWith(".") && !f.name.startsWith("$") &&
                    f.name != "Android" && f.name != "LOST.DIR" && f.name != "System Volume Information"
                ) walk(f, depth + 1)
            }
        }
        for (r in roots) walk(r, 0)
        return found.sortedWith(compareBy({ rank(it.name, videoName) }, { it.name.length }))
    }
}
