package com.senseart.subsync

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import java.io.FileNotFoundException
import java.io.IOException

/** Обяснява грешките при четене/запис на разбираем език – с какво е причината и как се оправя. */
object Errors {
    const val SHARE_READ_ONLY =
        "Компютърът не позволява запис в тази папка – споделена е само за четене (така е по " +
            "подразбиране в Windows). Оправя се на компютъра: десен бутон върху споделената папка → " +
            "Properties (Свойства) → Sharing (Споделяне) → Advanced Sharing (Разширено споделяне) → " +
            "Permissions (Разрешения) → за вашия потребител или Everyone отметнете „Change“ (Промяна). " +
            "В раздел Security (Защита) същият потребител трябва да има „Modify“ (Промяна)."

    /** Дали грешката е забрана за запис (а не напр. прекъсната връзка). */
    fun isPermission(e: Throwable): Boolean {
        val chain = generateSequence(e) { it.cause }.take(10).toList()
        chain.filterIsInstance<SMBApiException>().firstOrNull()?.let {
            return it.status == NtStatus.STATUS_ACCESS_DENIED || it.status == NtStatus.STATUS_CANNOT_DELETE
        }
        return localDenied(chain)
    }

    private fun localDenied(chain: List<Throwable>) = chain.any {
        it is SecurityException ||
            it is java.nio.file.AccessDeniedException ||
            ((it is FileNotFoundException || it is IOException) &&
                (it.message?.contains("EACCES") == true || it.message?.contains("EROFS") == true ||
                    it.message?.contains("Permission denied") == true || it.message?.contains("Read-only") == true))
    }

    fun describe(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.take(10).toList()
        chain.filterIsInstance<SMBApiException>().firstOrNull()?.let { smb ->
            return when (smb.status) {
                NtStatus.STATUS_ACCESS_DENIED -> SHARE_READ_ONLY
                NtStatus.STATUS_SHARING_VIOLATION ->
                    "Файлът е отворен от друга програма на компютъра (напр. редактор на субтитри). Затворете я и опитайте пак."
                NtStatus.STATUS_DISK_FULL -> "Дискът на компютъра е пълен."
                NtStatus.STATUS_CANNOT_DELETE -> "Компютърът не позволява промяна на файла (защитен е само за четене)."
                else -> "Грешка от компютъра: ${smb.status}"
            }
        }
        if (localDenied(chain)) {
            return "Няма разрешение за запис в тази папка на приставката/USB устройството. " +
                "Проверете „Достъп до всички файлове“ и дали устройството не е само за четене."
        }
        return e.message ?: e.javaClass.simpleName
    }
}
