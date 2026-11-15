package com.senseart.subsync

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/**
 * Намира .srt файла за даден филм, където и да е филмът:
 * вътрешна памет, USB флашка/диск, карта или споделена папка на компютъра.
 */
object SubtitleLocator {

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

    /**
     * Името без разширението: "2.avi" → "2", "Film.2020.mkv" → "Film.2020".
     * Разширението се маха само ако е на видео или субтитри.
     */
    fun baseName(name: String): String {
        val n = name.substringAfterLast('/')
        val dot = n.lastIndexOf('.')
        if (dot <= 0) return n
        val ext = n.substring(dot + 1).lowercase(Locale.ROOT)
        return if (ext == "srt" || isVideo(n)) n.substring(0, dot) else n
    }

    /** Стандартното правило на плейърите: .srt със съвсем същото име като видеото ("2.avi" → "2.srt"). */
    fun sameName(srtName: String, videoName: String): Boolean =
        isSubtitle(srtName) && baseName(srtName).equals(baseName(videoName), ignoreCase = true)

    /** .srt до видеото със същото име. Ако няма такъв – празно (без догадки). */
    fun siblings(video: VFile): List<VFile> {
        val dir = video.parent ?: return emptyList()
        val all = dir.list() ?: return emptyList()
        return all.filter { !it.isDirectory && sameName(it.name, video.name) }
    }

    fun isVideo(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in setOf("mkv", "mp4", "avi", "ts", "m2ts", "mts", "mov", "wmv", "m4v", "mpg", "mpeg", "vob", "webm", "iso")
    }
}
