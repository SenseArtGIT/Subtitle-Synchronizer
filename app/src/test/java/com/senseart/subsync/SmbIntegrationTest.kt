package com.senseart.subsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Истинска SMB връзка. Пуска се само ако е зададен SMB_TEST_HOST
 * (и по желание SMB_TEST_SHARE, SMB_TEST_USER, SMB_TEST_PASS).
 */
class SmbIntegrationTest {
    private val host = System.getenv("SMB_TEST_HOST")

    private fun share() = SmbShare(
        host!!, System.getenv("SMB_TEST_SHARE") ?: "movies",
        System.getenv("SMB_TEST_USER") ?: "", System.getenv("SMB_TEST_PASS") ?: "",
    )

    @Test
    fun browseReadWriteAndLocate() {
        assumeTrue(host != null)
        val sh = share()
        assertTrue(Smb.test(sh), Smb.test(sh).startsWith("Връзката е успешна"))

        // Списък на споделените папки (за „Търси в мрежата“)
        val names = Smb.listShares(sh.host, sh.user, sh.password)
        assertTrue(names.toString(), names.any { it.equals(sh.share, true) })
        assertTrue(names.none { it.endsWith("$") })
        assertTrue(NetworkScan.isOpen(sh.host, 445, 1000))
        System.getenv("SMB_TEST_NETBIOS")?.let { assertEquals(it, NetworkScan.netbiosName(sh.host)) }

        val dir = sh.root.child("Test Film (2021)")
        val files = dir.list()!!.map { it.name }.toSet()
        assertTrue(files.toString(), "Test.Film.2021.mkv" in files)

        // Намиране до видеото по smb:// адрес
        val video = SubtitleLocator.resolveVideo("smb://$host/${sh.share}/Test%20Film%20(2021)/Test.Film.2021.mkv", null, listOf(sh))!!
        val subs = SubtitleLocator.siblings(video)
        assertEquals("Test.Film.2021.srt", subs.first().name)

        // Търсене по име из цялата папка
        assertEquals("Test.Film.2021.srt", SubtitleLocator.searchByName("Test.Film.2021.mkv", listOf(sh.root)).first().name)

        // Четене (windows-1251) и запис с резервно копие
        val sub = Srt.load(subs.first())
        assertEquals("windows-1251", sub.charset.name())
        assertEquals("Здравей!", sub.cues[0].text)
        val before = sub.cues[0].startMs
        Srt.saveShifted(sub, 1.0, 1_500)
        assertTrue(dir.child("Test.Film.2021.srt.bak").exists())
        val again = Srt.load(subs.first())
        assertEquals(before + 1_500L, again.cues[0].startMs)
        assertEquals("Здравей!", again.cues[0].text)
        assertEquals("windows-1251", again.charset.name())
    }
}

class SmbReadOnlyTest {
    @Test
    fun saveToReadOnlyShareExplainsWhy() {
        val host = System.getenv("SMB_TEST_HOST")
        val ro = System.getenv("SMB_TEST_RO_SHARE")
        org.junit.Assume.assumeTrue(host != null && ro != null)
        val sh = SmbShare(host!!, ro!!, System.getenv("SMB_TEST_USER") ?: "", System.getenv("SMB_TEST_PASS") ?: "")
        val f = sh.root.child("Film RO").child("film.srt")
        val sub = Srt.load(f) // четенето работи
        org.junit.Assert.assertTrue(sub.cues.isNotEmpty())
        val err = runCatching { Srt.saveShifted(sub, 1.0, 1000) }.exceptionOrNull()
        org.junit.Assert.assertNotNull("записът трябваше да се провали", err)
        org.junit.Assert.assertEquals(Errors.SHARE_READ_ONLY, Errors.describe(err!!))
        org.junit.Assert.assertTrue(Errors.isPermission(err))
    }
}
