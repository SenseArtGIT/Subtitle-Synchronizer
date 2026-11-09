package com.senseart.subsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkScanTest {
    private fun entry(name: String, suffix: Int, group: Boolean): ByteArray {
        val e = ByteArray(18)
        name.padEnd(15).toByteArray(Charsets.US_ASCII).copyInto(e)
        e[15] = suffix.toByte()
        e[16] = if (group) 0x84.toByte() else 0x04
        return e
    }

    @Test
    fun requestHasNetbiosWildcardName() {
        val r = NetworkScan.nbstatRequest(0x1234)
        assertEquals(50, r.size)
        assertEquals(0x20, r[12].toInt())
        assertEquals("CKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", String(r, 13, 32, Charsets.US_ASCII))
        assertEquals(0x21, r[47].toInt())
    }

    @Test
    fun parsesNodeStatusWithFullName() {
        val header = ByteArray(12)
        val name = NetworkScan.nbstatRequest(0).copyOfRange(12, 46) // 0x20 + 32 + 0x00
        val rest = ByteArray(10)
        val entries = entry("WORKGROUP", 0x00, true) + entry("DESKTOP-KINO", 0x00, false) + entry("DESKTOP-KINO", 0x20, false)
        val resp = header + name + rest + byteArrayOf(3) + entries
        assertEquals("DESKTOP-KINO", NetworkScan.parseNbstat(resp))
    }

    @Test
    fun parsesNodeStatusWithPointerName() {
        val resp = ByteArray(12) + byteArrayOf(0xC0.toByte(), 0x0C) + ByteArray(10) + byteArrayOf(1) +
            entry("NAS", 0x20, false)
        assertEquals("NAS", NetworkScan.parseNbstat(resp))
        assertNull(NetworkScan.parseNbstat(ByteArray(5)))
    }
}
