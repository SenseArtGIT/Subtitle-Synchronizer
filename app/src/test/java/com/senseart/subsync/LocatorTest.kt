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
    fun loadsOnlySrtWithExactlyTheSameName() {
        val root = createTempDir()
        try {
            // Сериал с кратки имена, както при Fisher.S01: 1.avi без .srt, 2.avi + 2.srt …
            val d = File(root, "Fisher.S01.2022").apply { mkdirs() }
            for (n in 1..8) File(d, "$n.avi").writeText("")
            for (n in 2..8) File(d, "$n.srt").writeText("")
            File(d, "vvv").mkdirs()
            assertEquals(listOf("2.srt"), SubtitleLocator.siblings(LocalFile(File(d, "2.avi"))).map { it.name })
            assertEquals(listOf("8.srt"), SubtitleLocator.siblings(LocalFile(File(d, "8.avi"))).map { it.name })
            assertTrue(SubtitleLocator.siblings(LocalFile(File(d, "1.avi"))).isEmpty())

            // Без главни/малки разлики, но иначе – точно същото име.
            val f = File(root, "F").apply { mkdirs() }
            File(f, "Film.2020.mkv").writeText("")
            File(f, "film.2020.SRT").writeText("")
            File(f, "Film.2020.bg.srt").writeText("")
            File(f, "Other.srt").writeText("")
            assertEquals(listOf("film.2020.SRT"), SubtitleLocator.siblings(LocalFile(File(f, "Film.2020.mkv"))).map { it.name })

            // Различно име – нищо (без догадки, дори ако .srt е единствен).
            val g = File(root, "G").apply { mkdirs() }
            File(g, "movie.mkv").writeText("")
            File(g, "subs.srt").writeText("")
            assertTrue(SubtitleLocator.siblings(LocalFile(File(g, "movie.mkv"))).isEmpty())

            // Име без разширение също се сравнява правилно.
            assertEquals(listOf("3.srt"), SubtitleLocator.siblings(LocalFile(d).child("3")).map { it.name })
            assertEquals(listOf("3.srt"), SubtitleLocator.siblings(LocalFile(d).child("3.avi")).map { it.name })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun baseNames() {
        assertEquals("2", SubtitleLocator.baseName("2.avi"))
        assertEquals("2", SubtitleLocator.baseName("2.srt"))
        assertEquals("Film.2020", SubtitleLocator.baseName("Film.2020.mkv"))
        assertEquals("Fisher S01E02", SubtitleLocator.baseName("Fisher S01E02"))
    }
}
