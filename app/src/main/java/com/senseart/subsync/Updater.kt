package com.senseart.subsync

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Обновяване от самото приложение: проверява последния Release в GitHub, изтегля APK
 * и го инсталира през PackageInstaller (сесия). Така Android не го смята за
 * „ръчно инсталирано“ и не блокира „Достъпност“.
 */
object Updater {
    private const val API = "https://api.github.com/repos/SenseArtGIT/Subtitle-Synchronizer/releases/latest"

    data class Release(val version: String, val apkUrl: String, val size: Long)

    /** "1.10" > "1.9" */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val l = local.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    fun parseRelease(json: String): Release? {
        val o = JSONObject(json)
        val tag = o.optString("tag_name").removePrefix("v")
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) {
                return Release(tag, a.getString("browser_download_url"), a.optLong("size"))
            }
        }
        return null
    }

    /** Мрежова операция – извън главната нишка. */
    fun fetchLatest(): Release? {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "SubtitleSynchronizer")
        try {
            if (c.responseCode != 200) throw IllegalStateException("GitHub отговори с ${c.responseCode}")
            return parseRelease(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }

    /** Изтегля APK (следва пренасочванията на GitHub). [progress] – 0..100. */
    fun download(rel: Release, dest: File, progress: (Int) -> Unit) {
        var url = URL(rel.apkUrl)
        var c: HttpURLConnection
        var hops = 0
        while (true) {
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "SubtitleSynchronizer")
            val code = c.responseCode
            if (code in 300..399 && hops++ < 5) {
                url = URL(url, c.getHeaderField("Location"))
                c.disconnect()
                continue
            }
            if (code != 200) throw IllegalStateException("Изтеглянето върна $code")
            break
        }
        val total = if (c.contentLengthLong > 0) c.contentLengthLong else rel.size
        c.inputStream.use { inp ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val p = if (total > 0) (done * 100 / total).toInt() else 0
                    if (p != last) {
                        last = p; progress(p)
                    }
                }
            }
        }
        c.disconnect()
    }

    /** Подава APK на системния инсталатор (сесия). Потвърждението идва през [InstallReceiver]. */
    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallReceiver::class.java).setPackage(ctx.packageName), flags,
            )
            s.commit(pending.intentSender)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                } catch (e: Exception) {
                    Toast.makeText(context, "Не мога да отворя инсталатора: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "неизвестна грешка"
                Toast.makeText(context, "Обновяването не успя: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
