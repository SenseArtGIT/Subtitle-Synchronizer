package com.senseart.subsync

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * „Търси в мрежата“: намира компютрите в домашната мрежа, които споделят папки (SMB, порт 445),
 * и техните имена (NetBIOS – така ги показват Windows и файловите мениджъри).
 */
object NetworkScan {

    data class Host(val ip: String, val name: String?) {
        val title: String get() = if (name.isNullOrBlank()) ip else "$name   ($ip)"
    }

    /** Префиксите на локалните мрежи, напр. "192.168.1." (приемаме маска /24 – типично за домашна мрежа). */
    fun localPrefixes(): List<String> {
        val res = LinkedHashSet<String>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && a.isSiteLocalAddress) {
                        res.add(a.hostAddress!!.substringBeforeLast('.') + ".")
                    }
                }
            }
        } catch (_: Exception) {
        }
        return res.toList()
    }

    /** Сканира мрежата. [onFound] се вика (от фонова нишка) за всеки намерен компютър. */
    fun scan(prefixes: List<String> = localPrefixes(), onFound: (Host) -> Unit): List<Host> {
        val found = java.util.Collections.synchronizedList(ArrayList<Host>())
        val pool = Executors.newFixedThreadPool(64)
        for (p in prefixes) {
            for (i in 1..254) {
                val ip = p + i
                pool.execute {
                    if (isOpen(ip, 445, 400)) {
                        val h = Host(ip, netbiosName(ip) ?: reverseDns(ip))
                        found.add(h)
                        onFound(h)
                    }
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(40, TimeUnit.SECONDS)
        pool.shutdownNow()
        return found.sortedBy { ipKey(it.ip) }
    }

    private fun ipKey(ip: String) = ip.split('.').fold(0L) { acc, s -> acc * 256 + (s.toLongOrNull() ?: 0) }

    fun isOpen(ip: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs); true }
    } catch (_: Exception) {
        false
    }

    private fun reverseDns(ip: String): String? = try {
        val n = InetAddress.getByName(ip).canonicalHostName
        if (n == ip) null else n.substringBefore('.').uppercase()
    } catch (_: Exception) {
        null
    }

    /** NetBIOS „node status“ заявка (UDP 137) – връща името на компютъра. */
    fun netbiosName(ip: String): String? = try {
        DatagramSocket().use { s ->
            s.soTimeout = 800
            val req = nbstatRequest(0x5353)
            s.send(DatagramPacket(req, req.size, InetAddress.getByName(ip), 137))
            val buf = ByteArray(1024)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            parseNbstat(buf.copyOf(p.length))
        }
    } catch (_: Exception) {
        null
    }

    fun nbstatRequest(id: Int): ByteArray {
        val b = ArrayList<Byte>()
        fun u16(v: Int) {
            b.add((v shr 8).toByte()); b.add(v.toByte())
        }
        u16(id); u16(0); u16(1); u16(0); u16(0); u16(0)
        b.add(0x20)
        // Името „*“, допълнено с нули до 16 байта, кодирано по NetBIOS (всяка половинка + 'A').
        val raw = ByteArray(16).also { it[0] = '*'.code.toByte() }
        for (c in raw) {
            val v = c.toInt() and 0xFF
            b.add(('A'.code + (v shr 4)).toByte())
            b.add(('A'.code + (v and 0x0F)).toByte())
        }
        b.add(0)
        u16(0x21) // NBSTAT
        u16(0x01) // IN
        return b.toByteArray()
    }

    fun parseNbstat(r: ByteArray): String? {
        if (r.size < 12 + 2 + 10 + 1) return null
        var pos = 12
        // Пропускаме името във въпроса/отговора (пълно или указател 0xC0xx).
        while (pos < r.size) {
            val len = r[pos].toInt() and 0xFF
            if (len == 0) {
                pos++; break
            }
            if (len and 0xC0 == 0xC0) {
                pos += 2; break
            }
            pos += len + 1
        }
        pos += 10 // type, class, ttl, rdlength
        if (pos >= r.size) return null
        val count = r[pos].toInt() and 0xFF
        pos++
        for (i in 0 until count) {
            val at = pos + i * 18
            if (at + 18 > r.size) break
            val name = String(r, at, 15, Charsets.US_ASCII).trim()
            val suffix = r[at + 15].toInt() and 0xFF
            val group = (r[at + 16].toInt() and 0x80) != 0
            if (!group && (suffix == 0x00 || suffix == 0x20) && name.isNotEmpty()) return name
        }
        return null
    }
}
