package com.senseart.subsync

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Настройка на една споделена папка на компютъра. */
data class SmbShare(
    val host: String,
    val share: String,
    val user: String = "",
    val password: String = "",
    val domain: String = "",
    /** Името на компютъра (от търсенето в мрежата), само за показване и за разпознаване в адреси. */
    val name: String = "",
) {
    /** Стабилен ключ: "smb://хост/папка" с малки букви. */
    val id: String get() = "smb://${host.lowercase(Locale.ROOT)}/${share.lowercase(Locale.ROOT)}"
    val label: String
        get() = (if (name.isNotBlank()) "$name ($host)" else host) + " / $share" +
            if (user.isNotBlank()) "  – $user" else "  – гост"

    /** Дали [h] (IP или име) е този компютър. */
    fun isHost(h: String) = host.equals(h, true) || (name.isNotBlank() && name.equals(h, true))
    val root: SmbFile get() = SmbFile(this, "", true)

    companion object {
        /**
         * Разбира и пълни пътища: "\\PC\Movies", "smb://PC/Movies/Filmi" или просто "PC" + "Movies".
         * Връща (хост, споделена папка) или null.
         */
        fun splitUnc(text: String): Pair<String, String>? {
            val t = text.trim().removePrefix("smb:").replace('\\', '/').trim('/')
            val parts = t.split('/').filter { it.isNotBlank() }
            return if (parts.size >= 2) parts[0] to parts[1] else null
        }
    }
}

/** Връзките към компютъра се пазят отворени и се подновяват при прекъсване. */
object Smb {
    private val client by lazy {
        SMBClient(
            SmbConfig.builder()
                .withTimeout(20, TimeUnit.SECONDS)
                .withSoTimeout(30, TimeUnit.SECONDS)
                .withMultiProtocolNegotiate(true)
                // BouncyCastle директно – Android няма MD4, нужен за вход в Windows (NTLM).
                .withSecurityProvider(BCSecurityProvider())
                .build()
        )
    }
    private val disks = HashMap<String, DiskShare>()

    private fun key(s: SmbShare) = s.id + "|" + s.user + "|" + s.domain

    /** Потребител като „DOMAIN\\user“ се разделя на домейн и потребител. */
    private fun splitUser(user: String, domain: String): Pair<String, String?> {
        val i = user.indexOf('\\')
        return if (i > 0) user.substring(i + 1) to user.substring(0, i)
        else user to domain.ifBlank { null }
    }

    /** Влиза в компютъра; без потребител пробва като гост и анонимно. */
    private fun <T> withSession(host: String, user: String, password: String, domain: String, use: (Session) -> T): T {
        val conn = client.connect(host)
        val auths = if (user.isBlank()) {
            listOf(AuthenticationContext.guest(), AuthenticationContext.anonymous())
        } else {
            val (u, d) = splitUser(user, domain)
            listOf(AuthenticationContext(u, password.toCharArray(), d))
        }
        var last: Exception? = null
        for (a in auths) {
            try {
                return use(conn.authenticate(a))
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("Неуспешно влизане")
    }

    @Synchronized
    private fun disk(s: SmbShare): DiskShare {
        disks[key(s)]?.let { if (it.isConnected) return it }
        disks.remove(key(s))
        val d = withSession(s.host, s.user, s.password, s.domain) { session ->
            session.connectShare(s.share) as? DiskShare
                ?: throw IllegalStateException("„${s.share}“ не е споделена папка с файлове")
        }
        disks[key(s)] = d
        return d
    }

    /**
     * Споделените папки на компютъра (без скритите като C$ и без принтери).
     * Хвърля изключение при грешна парола или липса на връзка.
     */
    fun listShares(host: String, user: String, password: String, domain: String = ""): List<String> =
        withSession(host, user, password, domain) { session ->
            val srv = ServerService(SMBTransportFactories.SRVSVC.getTransport(session))
            srv.shares1
                .filter { (it.type and 0x0FFFFFFF) == 0 && !it.netName.endsWith("$") }
                .map { it.netName }
                .sortedBy { it.lowercase(Locale.ROOT) }
        }

    @Synchronized
    fun invalidate(s: SmbShare) {
        disks.remove(key(s))?.let {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
    }

    /** Изпълнява операция; при прекъсната връзка се свързва наново и опитва още веднъж. */
    fun <T> run(s: SmbShare, block: (DiskShare) -> T): T = try {
        block(disk(s))
    } catch (e: Exception) {
        invalidate(s)
        block(disk(s))
    }

    /** Проверка от екрана с настройки. Връща текст за потребителя. */
    fun test(s: SmbShare): String = try {
        invalidate(s)
        val n = s.root.list()?.size ?: throw IllegalStateException("няма достъп до съдържанието")
        "Връзката е успешна – $n папки/файла в „${s.share}“."
    } catch (e: Exception) {
        "Неуспешна връзка: " + (e.message ?: e.javaClass.simpleName)
    }
}

/** Файл или папка в споделена папка. [rel] е пътят вътре в нея с „/“, "" = самата папка. */
class SmbFile(val share: SmbShare, val rel: String, private val dirHint: Boolean? = null) : VFile {

    private val smbPath get() = rel.replace('/', '\\')

    override val name: String get() = if (rel.isEmpty()) share.share else rel.substringAfterLast('/')
    override val path: String get() = share.id + if (rel.isEmpty()) "" else "/$rel"

    override val isDirectory: Boolean
        get() = dirHint ?: (rel.isEmpty() || Smb.run(share) { it.folderExists(smbPath) })

    override val parent: VFile?
        get() = if (rel.isEmpty()) null else SmbFile(share, rel.substringBeforeLast('/', ""), true)

    override fun list(): List<VFile>? = try {
        Smb.run(share) { d ->
            d.list(smbPath)
                .filter { it.fileName != "." && it.fileName != ".." }
                .map {
                    val dir = it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                    SmbFile(share, join(it.fileName), dir)
                }
        }
    } catch (e: Exception) {
        null
    }

    private fun join(n: String) = if (rel.isEmpty()) n else "$rel/$n"

    override fun child(name: String): VFile = SmbFile(share, join(name))

    override fun exists(): Boolean =
        rel.isEmpty() || Smb.run(share) { it.fileExists(smbPath) || it.folderExists(smbPath) }

    override fun readBytes(): ByteArray = Smb.run(share) { d ->
        d.openFile(
            smbPath, EnumSet.of(AccessMask.GENERIC_READ), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        ).use { f -> f.inputStream.use { it.readBytes() } }
    }

    override fun writeBytes(data: ByteArray) {
        Smb.run(share) { d ->
            d.openFile(
                smbPath, EnumSet.of(AccessMask.GENERIC_WRITE), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null,
            ).use { f -> f.outputStream.use { it.write(data) } }
        }
    }

    override fun equals(other: Any?) = other is SmbFile && other.path == path
    override fun hashCode() = path.hashCode()
    override fun toString() = path
}
