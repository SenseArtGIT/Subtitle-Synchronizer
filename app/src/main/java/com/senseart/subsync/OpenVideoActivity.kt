package com.senseart.subsync

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast

/**
 * Невидим „посредник“: файловият мениджър отваря филма с това приложение,
 * то запомня кой точно файл е (за да намери .srt до него) и веднага
 * предава филма на ViMu със същия адрес и права за достъп.
 */
class OpenVideoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val src = intent
        val uri = src?.data
        if (uri == null) {
            finish(); return
        }
        val prefs = Prefs(this)
        prefs.setCurrentVideo(uri.toString(), displayName(uri), dataColumn(uri))
        // „Достъпност“ се включва 10 с след пускането на филма (изключва се сама при излизане от ViMu).
        // Така при бързо прескачане между филми не се включва и изключва всеки път.
        if (prefs.autoA11y && !SelfHeal.isAccessibilityOn(this)) SelfHeal.enableLater(this, 10_000)

        val fwd = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, src.type ?: guessType(uri))
            src.extras?.let { putExtras(it) }
            src.clipData?.let { clipData = it }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage(Prefs.VIMU_PACKAGE)
        }
        try {
            startActivity(fwd)
        } catch (e: ActivityNotFoundException) {
            // ViMu не е инсталиран – оставяме системата да предложи плейър.
            fwd.setPackage(null)
            try {
                startActivity(Intent.createChooser(fwd, "Пусни с…"))
            } catch (e2: Exception) {
                Toast.makeText(this, "Няма плейър за този файл", Toast.LENGTH_LONG).show()
            }
        } catch (e: SecurityException) {
            Log.w("SubSync", "forward", e)
            Toast.makeText(this, "Плейърът няма достъп до файла: ${e.message}", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { return it }
                }
            } catch (_: Exception) {
            }
        }
        return SubtitleLocator.nameFromUri(uri.toString()).ifBlank { null }
    }

    /** Някои файлови мениджъри дават истинския път в колоната _data. */
    private fun dataColumn(uri: Uri): String? {
        if (uri.scheme != "content") return null
        return try {
            contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun guessType(uri: Uri): String {
        val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase() ?: ""
        return when (ext) {
            "mkv" -> "video/x-matroska"
            "mp4", "m4v" -> "video/mp4"
            "avi" -> "video/x-msvideo"
            "ts", "m2ts", "mts" -> "video/mp2t"
            "mov" -> "video/quicktime"
            "wmv" -> "video/x-ms-wmv"
            "webm" -> "video/webm"
            "mpg", "mpeg", "vob" -> "video/mpeg"
            else -> "video/*"
        }
    }
}
