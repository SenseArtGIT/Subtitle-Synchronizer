package com.senseart.subsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocatorTest {
    private val pc = SmbShare("192.168.1.10", "Movies", "ivan", "x")

    @Test
    fun resolvesLocalPathsAndFileUris() {
        assertEquals("/storage/ABCD-1234/Filmi/a.mkv",
            SubtitleLocator.resolveVideo("file:///storage/ABCD-1234/Filmi/a.mkv", null, emptyList())?.path)
        assertEquals("/storage/emulated/0/Movies/Филм 1.mkv",
            SubtitleLocator.resolveVideo("file:///storage/emulated/0/Movies/%D0%A4%D0%B8%D0%BB%D0%BC%201.mkv", null, emptyList())?.path)
        // content:// адрес на файлов мениджър с пътя вътре
        assertEquals("/storage/ABCD-1234/x/b.mkv",
            SubtitleLocator.resolveVideo("content://com.some.fm.provider/root/storage/ABCD-1234/x/b.mkv", null, emptyList())?.path)
        // колоната _data е с предимство
        assertEquals("/storage/emulated/0/c.mkv",
            SubtitleLocator.resolveVideo("content://media/external/video/5", "/storage/emulated/0/c.mkv", emptyList())?.path)
    }

    @Test
    fun resolvesSmbUris() {
        val v = SubtitleLocator.resolveVideo("smb://192.168.1.10/Movies/Action/Film.2020.mkv", null, listOf(pc))
        assertTrue(v is SmbFile)
        v as SmbFile
        assertEquals("Action/Film.2020.mkv", v.rel)
        assertEquals(pc, v.share)
        // smb:// адрес вграден в адреса на файлов мениджър
        val w = SubtitleLocator.resolveVideo(
            "content://fm.provider/smb%3A%2F%2Fivan%40192.168.1.10%2FMovies%2FA%2Fb.mkv", null, listOf(pc)
        ) as SmbFile
        assertEquals("A/b.mkv", w.rel)
        // непознат компютър: папката се сглобява от адреса (като гост)
        val g = SubtitleLocator.resolveVideo("smb://10.0.0.5/Other/x.mkv", null, listOf(pc)) as SmbFile
        assertEquals("10.0.0.5", g.share.host)
        assertEquals("", g.share.user)
        // адрес с потребител и парола (ES File Explorer): ползват се директно
        val es = SubtitleLocator.resolveVideo(
            "http://127.0.0.1:59777/smb%3A%2F%2FWORK%3Bpesho%3Ap%40ss%40192.168.1.20%2FFilmi%2FX%2Fy.mkv", null, listOf(pc)
        ) as SmbFile
        assertEquals(SmbShare("192.168.1.20", "Filmi", "pesho", "p@ss", "WORK"), es.share)
        assertEquals("X/y.mkv", es.rel)
        // разпознаване по име на компютъра
        val named = pc.copy(name = "DESKTOP-KINO")
        val n = SubtitleLocator.resolveVideo("smb://desktop-kino/Movies/a.mkv", null, listOf(named)) as SmbFile
        assertEquals(named, n.share)
    }

    @Test
    fun resolvesLocalhostStreamingUrls() {
        val v = SubtitleLocator.resolveVideo(
            "http://127.0.0.1:2222/smb/192.168.1.10/Movies/Drama/Film%20Name.mkv", null, listOf(pc)
        ) as SmbFile
        assertEquals("Drama/Film Name.mkv", v.rel)
    }

    @Test
    fun pathRoundTrip() {
        val f = SmbFile(pc, "Drama/Film.srt")
        assertEquals("smb://192.168.1.10/movies/Drama/Film.srt", f.path)
        assertEquals(f, SubtitleLocator.fromPath(f.path, listOf(pc)))
        assertEquals(LocalFile(File("/storage/x/a.srt")), SubtitleLocator.fromPath("/storage/x/a.srt", listOf(pc)))
        assertEquals(SmbShare.splitUnc("\\\\PC\\Filmi\\x"), "PC" to "Filmi")
        assertEquals(SmbShare.splitUnc("smb://pc/Filmi"), "pc" to "Filmi")
    }

    @Test
    fun percentDecodeKeepsPlusAndUnicode() {
        assertEquals("a+b c.mkv", SubtitleLocator.percentDecode("a+b%20c.mkv"))
        assertEquals("Филм.mkv", SubtitleLocator.percentDecode("%D0%A4%D0%B8%D0%BB%D0%BC.mkv"))
        assertEquals("100%.mkv", SubtitleLocator.percentDecode("100%.mkv"))
    }

    @Test
    fun findsSiblingsSubsFolderAndByName() {
        val root = createTempDir()
        try {
            val d1 = File(root, "Film One (2020)").apply { mkdirs() }
            File(d1, "Film.One.2020.1080p.mkv").writeText("")
            File(d1, "Film.One.2020.1080p.en.srt").writeText("")
            File(d1, "Film.One.2020.1080p.srt").writeText("")
            File(d1, "Film.One.2020.1080p.bg.srt").writeText("")
            val s1 = SubtitleLocator.siblings(LocalFile(File(d1, "Film.One.2020.1080p.mkv"))).map { it.name }
            assertEquals("Film.One.2020.1080p.srt", s1[0])
            assertEquals("Film.One.2020.1080p.bg.srt", s1[1])

            val d2 = File(root, "Film Two").apply { mkdirs() }
            File(d2, "Film.Two.mkv").writeText("")
            File(d2, "Subs").mkdirs()
            File(d2, "Subs/2_Bulgarian.srt").writeText("")
            assertEquals(listOf("2_Bulgarian.srt"),
                SubtitleLocator.siblings(LocalFile(File(d2, "Film.Two.mkv"))).map { it.name })

            val d3 = File(root, "Three").apply { mkdirs() }
            File(d3, "movie.mkv").writeText("")
            File(d3, "totally-different.srt").writeText("")
            assertEquals(listOf("totally-different.srt"),
                SubtitleLocator.siblings(LocalFile(File(d3, "movie.mkv"))).map { it.name })

            val found = SubtitleLocator.searchByName("Film.Two.mkv", listOf(LocalFile(root)))
            assertTrue(found.isEmpty()) // в Subs името е различно – търсенето по име не го намира
            val f2 = SubtitleLocator.searchByName("Film.One.2020.1080p.mkv", listOf(LocalFile(root)))
            assertEquals("Film.One.2020.1080p.srt", f2[0].name)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun singleSrtOrBulgarianIsPicked() {
        val root = createTempDir()
        try {
            val a = File(root, "A").apply { mkdirs() }
            File(a, "Film.2020.1080p.mkv").writeText("")
            File(a, "sample.mkv").writeText("")
            File(a, "Bulgarian subs.srt").writeText("")
            assertEquals(listOf("Bulgarian subs.srt"),
                SubtitleLocator.siblings(LocalFile(File(a, "Film.2020.1080p.mkv"))).map { it.name })

            val b = File(root, "B").apply { mkdirs() }
            File(b, "movie.mkv").writeText("")
            File(b, "English.srt").writeText("")
            File(b, "Movie BG.srt").writeText("")
            assertEquals(listOf("Movie BG.srt"),
                SubtitleLocator.siblings(LocalFile(File(b, "movie.mkv"))).map { it.name })

            val c = File(root, "C").apply { mkdirs() }
            File(c, "x.mkv").writeText("")
            File(c, "one.srt").writeText("")
            File(c, "two.srt").writeText("")
            assertTrue(SubtitleLocator.siblings(LocalFile(File(c, "x.mkv"))).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
