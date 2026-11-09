package com.senseart.subsync

import java.io.File

/**
 * Файл или папка – локално (вътрешна памет, USB, карта) или в мрежова папка (SMB).
 * Всички методи, които четат/пишат, може да ходят по мрежата: викайте ги извън главната нишка.
 */
interface VFile {
    val name: String

    /** Уникален път, напр. "/storage/1234-ABCD/Filmi/film.srt" или "smb://192.168.1.10/movies/film.srt". */
    val path: String
    val isDirectory: Boolean
    val parent: VFile?

    /** Съдържанието на папката или null, ако няма достъп. */
    fun list(): List<VFile>?
    fun child(name: String): VFile
    fun exists(): Boolean
    fun readBytes(): ByteArray
    fun writeBytes(data: ByteArray)
}

class LocalFile(val file: File) : VFile {
    override val name: String get() = file.name
    override val path: String get() = file.absolutePath
    override val isDirectory: Boolean get() = file.isDirectory
    override val parent: VFile? get() = file.parentFile?.let { LocalFile(it) }
    override fun list(): List<VFile>? = file.listFiles()?.map { LocalFile(it) }
    override fun child(name: String): VFile = LocalFile(File(file, name))
    override fun exists() = file.exists()
    override fun readBytes() = file.readBytes()

    override fun writeBytes(data: ByteArray) {
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(file)) {
            file.writeBytes(data)
            tmp.delete()
        }
    }

    override fun equals(other: Any?) = other is LocalFile && other.path == path
    override fun hashCode() = path.hashCode()
    override fun toString() = path
}
