package com.senseart.subsync

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Екран с настройки: разрешения, бутон за панела, вид на субтитрите. */
class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private lateinit var a11yStatus: TextView
    private lateinit var notifStatus: TextView
    private lateinit var filesStatus: TextView
    private lateinit var hotkeyLabel: TextView
    private lateinit var sizeLabel: TextView
    private lateinit var marginLabel: TextView
    private lateinit var defOffsetLabel: TextView
    private lateinit var calibLabel: TextView
    private lateinit var holdBtn: Button
    private lateinit var autoBtn: Button
    private lateinit var healStatus: TextView
    private lateinit var updateStatus: TextView
    private lateinit var updateBtn: Button
    private var latest: Updater.Release? = null
    private var updating = false
    private lateinit var sharesBox: LinearLayout

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(24), dp(48), dp(24))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF101418.toInt())
            addView(root)
        }
        setContentView(scroll)

        text("Subtitle Synchronizer", 26f, Color.WHITE, bold = true)
        text(
            "Показва субтитри от .srt файл над картината на ViMu и позволява да ги синхронизирате " +
                "с дистанционното, докато филмът върви. Във ViMu изключете неговите субтитри, " +
                "за да не се виждат двойно.",
            15f, 0xFFB0B8C0.toInt(),
        )

        section("1. Разрешения (еднократно)")
        a11yStatus = text("", 16f, Color.WHITE)
        button("Отвори „Достъпност“") { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
        autoBtn = button("") { prefs.autoA11y = !prefs.autoA11y }
        text(
            "„Само докато гледам филм“: докато има включена „Достъпност“, началният екран показва меню " +
                "„Отваряне/Преместване“ вместо да стартира приложенията. Затова тя се включва сама " +
                "10 секунди след като отворите филм през приложението и се изключва веднага щом " +
                "излезете от ViMu. Ако пуснете филм направо от ViMu: натиснете долния бутон и " +
                "в рамките на 10 секунди отидете във ViMu.",
            14f, 0xFFB0B8C0.toInt(),
        )
        button("Включи „Достъпност“ без компютър") {
            when {
                SelfHeal.isAccessibilityOn(this) ->
                    Toast.makeText(this, "„Достъпност“ вече е включена", Toast.LENGTH_SHORT).show()
                !SelfHeal.canWriteSecure(this) ->
                    Toast.makeText(this, "Нужна е еднократната adb команда pm grant (вижте по-долу)", Toast.LENGTH_LONG).show()
                SelfHeal.ensureAccessibility(this) ->
                    Toast.makeText(this, "„Достъпност“ е включена", Toast.LENGTH_SHORT).show()
                else -> Toast.makeText(this, "Не успях да я включа", Toast.LENGTH_LONG).show()
            }
            updateLabels()
        }
        text(
            "Ако пише, че е забранено от съображения за сигурност: отворете информацията за " +
                "приложението, изберете „Разреши ограничени настройки“ (в менюто ⋮ или най-долу) " +
                "и опитайте отново. Ако такава опция няма – ползвайте adb командите по-долу.",
            14f, 0xFFB0B8C0.toInt(),
        )
        button("Отвори информация за приложението") {
            open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        notifStatus = text("", 16f, Color.WHITE)
        button("Отвори „Достъп до известия“") { open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        healStatus = text("", 16f, Color.WHITE)
        filesStatus = text("", 16f, Color.WHITE)
        button("Отвори „Достъп до всички файлове“") {
            if (!open(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                open(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
        text(
            "Ако някой от бутоните не отваря нищо или „Достъпност“ е забранена (Android 13+ блокира " +
                "ръчно инсталирани приложения), изпълнете от компютъра:",
            14f, 0xFFB0B8C0.toInt(),
        )
        text(ADB_COMMANDS.trimIndent(), 13f, 0xFF9FD3FF.toInt(), mono = true)

        section("2. Бутон за панела")
        hotkeyLabel = text("", 16f, Color.WHITE)
        button("Задай бутон") {
            prefs.learning = true
            updateLabels()
        }
        holdBtn = button("") { prefs.holdHotkey = !prefs.holdHotkey }
        text(
            "Със задържане: задръжте бутона ~1 секунда, за да отворите панела. Краткото натискане " +
                "си остава с нормалната функция във ViMu. Най-чисто работи с OK или BACK – при тях " +
                "краткото натискане стига до ViMu съвсем нормално. При другите бутони ViMu може " +
                "да усети и началото на задържането.",
            14f, 0xFFB0B8C0.toInt(),
        )

        section("3. Мрежови папки (компютър)")
        text(
            "Натиснете „Търси в мрежата“, изберете компютъра и папките с филми – както във " +
                "файловия мениджър. Мрежова папка, от която пуснете филм през ES File Explorer, " +
                "се добавя и сама, ако адресът съдържа потребител и парола.",
            14f, 0xFFB0B8C0.toInt(),
        )
        sharesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(sharesBox)
        button("🔍 Търси в мрежата") { searchNetwork() }
        button("+ Добави ръчно (IP и име на папка)") { editShare(-1) }

        section("4. Отваряне на филми")
        text(
            "За да намира субтитрите сам, където и да е филмът (флашка, USB диск, вътрешна памет " +
                "или компютъра в мрежата): във файловия мениджър отворете видео файл, изберете " +
                "„Отвори с“ → „Subtitle Synchronizer“ → „Винаги“. Приложението запомня кой е файлът " +
                "и веднага го предава на ViMu – няма да го виждате на екрана.",
            15f, 0xFFD0D6DC.toInt(),
        )

        section("5. Вид на субтитрите")
        sizeLabel = text("", 16f, Color.WHITE)
        row(button("Размер −") { prefs.textSizeSp -= 2 }, button("Размер +") { prefs.textSizeSp += 2 })
        marginLabel = text("", 16f, Color.WHITE)
        row(button("По-ниско") { prefs.bottomMarginDp -= 8 }, button("По-високо") { prefs.bottomMarginDp += 8 })

        section("6. Допълнителни")
        defOffsetLabel = text("", 16f, Color.WHITE)
        row(
            button("−0.5 с") { prefs.defaultOffsetMs -= 500 },
            button("+0.5 с") { prefs.defaultOffsetMs += 500 },
        )
        calibLabel = text("", 16f, Color.WHITE)
        row(
            button("−50 ms") { prefs.calibrationMs -= 50 },
            button("+50 ms") { prefs.calibrationMs += 50 },
        )

        section("Обновяване")
        updateStatus = text("", 16f, Color.WHITE)
        updateBtn = button("Провери за нова версия") { onUpdateClick() }
        text(
            "Обновявайте оттук, а не с APK през файловия мениджър – така Android не изключва " +
                "„Достъпност“. Първия път Android ще поиска разрешение приложението да инсталира " +
                "обновления (Инсталиране на неизвестни приложения).",
            14f, 0xFFB0B8C0.toInt(),
        )

        section("Как се ползва")
        text(HELP.trimIndent(), 15f, 0xFFD0D6DC.toInt())
        text("Версия ${packageManager.getPackageInfo(packageName, 0).versionName}", 13f, 0xFF707880.toInt())
    }

    override fun onResume() {
        super.onResume()
        prefs.sp.registerOnSharedPreferenceChangeListener(this)
        updateLabels()
        // Тиха проверка за нова версия (най-много веднъж на 6 часа).
        val last = prefs.sp.getLong("update_check", 0)
        if (latest == null && System.currentTimeMillis() - last > 6 * 3600_000L) checkUpdate(silent = true)
    }

    // ---------------------------------------------------------------- обновяване

    private fun currentVersion(): String = packageManager.getPackageInfo(packageName, 0).versionName ?: "0"

    private fun checkUpdate(silent: Boolean) {
        if (!silent) updateStatus.text = "Проверка…"
        Thread {
            val res = runCatching { Updater.fetchLatest() }
            runOnUiThread {
                prefs.sp.edit().putLong("update_check", System.currentTimeMillis()).apply()
                res.onSuccess { rel ->
                    latest = rel
                    updateUpdateLabels()
                    if (!silent && (rel == null || !Updater.isNewer(rel.version, currentVersion()))) {
                        Toast.makeText(this, "Имате последната версия", Toast.LENGTH_SHORT).show()
                    }
                }.onFailure {
                    if (!silent) updateStatus.text = "Неуспешна проверка: ${it.message}"
                }
            }
        }.start()
    }

    private fun updateUpdateLabels() {
        if (updating) return
        val rel = latest
        val cur = currentVersion()
        if (rel != null && Updater.isNewer(rel.version, cur)) {
            updateStatus.text = "🔔 Налична е нова версия ${rel.version} (сега: $cur)"
            updateBtn.text = "Изтегли и инсталирай ${rel.version}"
        } else {
            updateStatus.text = "Версия $cur" + if (rel != null) " – последна" else ""
            updateBtn.text = "Провери за нова версия"
        }
    }

    private fun onUpdateClick() {
        val rel = latest
        if (updating) return
        if (rel == null || !Updater.isNewer(rel.version, currentVersion())) {
            checkUpdate(silent = false); return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                this, "Разрешете на Subtitle Synchronizer да инсталира приложения и натиснете пак „Изтегли“",
                Toast.LENGTH_LONG,
            ).show()
            if (!open(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) {
                updateStatus.text = "Отворете Настройки → Приложения → Специален достъп → Инсталиране на " +
                    "неизвестни приложения → Subtitle Synchronizer → Разреши (или adb командата по-горе)."
            }
            return
        }
        updating = true
        updateStatus.text = "Изтегляне… 0%"
        val apk = java.io.File(cacheDir, "update.apk")
        Thread {
            val res = runCatching {
                Updater.download(rel, apk) { p -> runOnUiThread { updateStatus.text = "Изтегляне… $p%" } }
                runOnUiThread { updateStatus.text = "Инсталиране…" }
                Updater.install(this, apk)
            }
            runOnUiThread {
                updating = false
                res.onFailure { updateStatus.text = "Неуспешно обновяване: ${it.message}" }
            }
        }.start()
    }

    override fun onPause() {
        prefs.sp.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onStop() {
        prefs.learning = false
        super.onStop()
    }

    override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) = updateLabels()

    /** Ако услугата още не е включена, бутонът се хваща тук. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (prefs.learning && !Keys.isForbiddenHotkey(keyCode, prefs.holdHotkey)) {
            prefs.hotkey = keyCode
            prefs.learning = false
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun refreshShares() {
        sharesBox.removeAllViews()
        val list = prefs.shares
        if (list.isEmpty()) {
            sharesBox.addView(TextView(this).apply {
                text = "(няма добавени)"
                setTextColor(0xFF707880.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
        }
        list.forEachIndexed { i, sh ->
            sharesBox.addView(Button(this).apply {
                text = "🖧  " + sh.label
                isAllCaps = false
                setOnClickListener { editShare(i) }
            })
        }
    }

    // ---------------------------------------------------------------- търсене в мрежата

    /** Стъпка 1: намира компютрите в мрежата и ги показва, докато се намират. */
    private fun searchNetwork() {
        val hosts = ArrayList<NetworkScan.Host>()
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, ArrayList())
        val dlg = AlertDialog.Builder(this)
            .setTitle("Търсене на компютри в мрежата…")
            .setAdapter(adapter) { _, which -> hosts.getOrNull(which)?.let { pickHost(it) } }
            .setNegativeButton("Отказ", null)
            .setNeutralButton("Въведи ръчно") { _, _ -> editShare(-1) }
            .show()
        val prefixes = NetworkScan.localPrefixes()
        if (prefixes.isEmpty()) {
            dlg.setTitle("Няма връзка с мрежата")
            return
        }
        Thread {
            NetworkScan.scan(prefixes) { h ->
                runOnUiThread {
                    hosts.add(h)
                    adapter.add("🖥   " + h.title)
                }
            }
            runOnUiThread {
                if (dlg.isShowing) dlg.setTitle(
                    if (hosts.isEmpty()) "Не намерих компютри със споделени папки"
                    else "Изберете компютър (${hosts.size})"
                )
            }
        }.start()
    }

    /** Стъпка 2: първо пробва без парола (гост); ако компютърът иска, пита за потребител и парола. */
    private fun pickHost(h: NetworkScan.Host) {
        val wait = Toast.makeText(this, "Свързване с ${h.name ?: h.ip}…", Toast.LENGTH_SHORT)
        wait.show()
        Thread {
            val shares = try {
                Smb.listShares(h.ip, "", "")
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (!shares.isNullOrEmpty()) chooseShares(h, "", "", shares)
                else askCredentials(h, null)
            }
        }.start()
    }

    private fun askCredentials(h: NetworkScan.Host, error: String?) {
        val known = prefs.shares.firstOrNull { it.host == h.ip && it.user.isNotBlank() }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        box.addView(TextView(this).apply {
            text = (if (error != null) "⚠ $error\n\n" else "") +
                "Въведете потребителя и паролата, с които влизате в Windows на този компютър " +
                "(при акаунт на Microsoft – имейлът и паролата му)."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
        val user = EditText(this).apply {
            hint = "Потребител"; isSingleLine = true; setText(known?.user ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val pass = EditText(this).apply {
            hint = "Парола"; isSingleLine = true; setText(known?.password ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(user)
        box.addView(pass)
        AlertDialog.Builder(this)
            .setTitle(h.name ?: h.ip)
            .setView(box)
            .setPositiveButton("Влез") { _, _ ->
                val u = user.text.toString().trim()
                val p = pass.text.toString()
                Toast.makeText(this, "Свързване…", Toast.LENGTH_SHORT).show()
                Thread {
                    val res = runCatching { Smb.listShares(h.ip, u, p) }
                    runOnUiThread {
                        res.onSuccess {
                            if (it.isEmpty()) askCredentials(h, "Компютърът няма споделени папки.")
                            else chooseShares(h, u, p, it)
                        }.onFailure {
                            askCredentials(h, "Неуспешно влизане: ${it.message ?: it.javaClass.simpleName}")
                        }
                    }
                }.start()
            }
            .setNegativeButton("Отказ", null)
            .show()
    }

    /** Стъпка 3: избор на споделените папки (може няколко). */
    private fun chooseShares(h: NetworkScan.Host, user: String, pass: String, names: List<String>) {
        val existing = prefs.shares
        val checked = BooleanArray(names.size) { i ->
            names.size == 1 || existing.any { it.host == h.ip && it.share.equals(names[i], true) }
        }
        AlertDialog.Builder(this)
            .setTitle("Споделени папки на ${h.name ?: h.ip}")
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Добави") { _, _ ->
                val list = prefs.shares.toMutableList()
                var added = 0
                names.forEachIndexed { i, n ->
                    if (!checked[i]) return@forEachIndexed
                    val sh = SmbShare(h.ip, n, user, pass, "", h.name ?: "")
                    val at = list.indexOfFirst { it.id == sh.id }
                    if (at >= 0) list[at] = sh else list.add(sh)
                    added++
                }
                prefs.shares = list
                refreshShares()
                Toast.makeText(this, "Добавени папки: $added", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отказ", null)
            .show()
    }

    /** Добавяне (index = -1) или промяна на мрежова папка. */
    private fun editShare(index: Int) {
        val list = prefs.shares.toMutableList()
        val old = list.getOrNull(index)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        fun field(label: String, value: String, password: Boolean = false) = EditText(this).apply {
            hint = label
            setText(value)
            isSingleLine = true
            inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            box.addView(TextView(this@MainActivity).apply { text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) })
            box.addView(this)
        }
        val host = field("Компютър – IP адрес или име (напр. 192.168.1.10)", old?.host ?: "")
        val share = field("Споделена папка (напр. Movies)", old?.share ?: "")
        val user = field("Потребител на компютъра (празно = гост)", old?.user ?: "")
        val pass = field("Парола", old?.password ?: "", password = true)
        val domain = field("Домейн (обикновено празно)", old?.domain ?: "")
        val compName = old?.name ?: ""
        val result = TextView(this).apply { setPadding(0, dp(8), 0, 0) }

        fun read(): SmbShare? {
            var h = host.text.toString().trim()
            var sh = share.text.toString().trim().trim('/', '\\')
            // Позволяваме и пълен път: \\PC\Movies или smb://PC/Movies
            SmbShare.splitUnc(h)?.let { (a, b) -> h = a; if (sh.isEmpty()) sh = b }
            if (h.isEmpty() || sh.isEmpty()) return null
            return SmbShare(h, sh, user.text.toString().trim(), pass.text.toString(), domain.text.toString().trim(), compName)
        }

        box.addView(Button(this).apply {
            text = "Провери връзката"
            isAllCaps = false
            setOnClickListener {
                val sh = read()
                if (sh == null) {
                    result.text = "Попълнете компютър и споделена папка."; return@setOnClickListener
                }
                result.text = "Свързване…"
                Thread {
                    val msg = Smb.test(sh)
                    runOnUiThread { result.text = msg }
                }.start()
            }
        })
        box.addView(result)

        val b = AlertDialog.Builder(this)
            .setTitle(if (old == null) "Нова мрежова папка" else "Мрежова папка")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Запази") { _, _ ->
                val sh = read() ?: return@setPositiveButton
                if (old == null) list.add(sh) else list[index] = sh
                prefs.shares = list
                refreshShares()
            }
            .setNegativeButton("Отказ", null)
        if (old != null) b.setNeutralButton("Изтрий") { _, _ ->
            list.removeAt(index)
            prefs.shares = list
            refreshShares()
        }
        b.show()
    }

    private fun updateLabels() {
        refreshShares()
        val a11y = isAccessibilityOn()
        a11yStatus.text = if (prefs.autoA11y) {
            mark(SelfHeal.canWriteSecure(this) || a11y) + "Достъпност – включва се сама при пускане на филм (сега: " +
                (if (a11y) "включена" else "изключена") + ")"
        } else {
            mark(a11y) + "Достъпност (показване над плейъра и бутоните)" +
                if (a11y && !SubSyncService.running) " – включена, стартира се…" else ""
        }
        autoBtn.text = "Режим на „Достъпност“: " +
            if (prefs.autoA11y) "само докато гледам филм (препоръчително)" else "винаги включена"
        notifStatus.text = mark(isNotificationAccessOn()) +
            "Достъп до известия (позицията на филма от ViMu – задължително)"
        filesStatus.text = mark(hasFilesAccess()) + "Достъп до всички файлове (четене и запис на .srt)"
        healStatus.text = mark(SelfHeal.canWriteSecure(this)) +
            "Включване на „Достъпност“ без компютър (и автоматично след обновяване)" +
            if (SelfHeal.canWriteSecure(this)) "" else " (еднократна adb команда – вижте по-долу)"
        updateUpdateLabels()
        hotkeyLabel.text = if (prefs.learning) "Натиснете сега бутона на дистанционното…"
        else "Бутон за панела: ${Keys.name(prefs.hotkey)}"
        holdBtn.text = "Отваряне на панела: " +
            if (prefs.holdHotkey) "със задържане на бутона" else "с кратко натискане"
        sizeLabel.text = "Размер на текста: ${prefs.textSizeSp}"
        marginLabel.text = "Височина от долния край: ${prefs.bottomMarginDp}"
        defOffsetLabel.text = "Отместване по подразбиране за нови файлове: ${SyncModel.formatOffset(prefs.defaultOffsetMs)}"
        calibLabel.text = "Корекция на позицията от плейъра: ${prefs.calibrationMs} ms"
    }

    private fun mark(ok: Boolean) = if (ok) "✅  " else "❌  "

    private fun isAccessibilityOn(): Boolean = SelfHeal.isAccessibilityOn(this)

    private fun isNotificationAccessOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return s.split(':').any { it.startsWith("$packageName/") }
    }

    private fun hasFilesAccess() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    private fun open(action: String, data: Uri? = null): Boolean = try {
        startActivity(Intent(action).apply { if (data != null) this.data = data })
        true
    } catch (e: Exception) {
        false
    }

    // ---------------------------------------------------------------- малки помощници за UI

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            if (mono) {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            }
            setPadding(0, dp(4), 0, dp(4))
            root.addView(this)
        }

    private fun section(title: String) {
        val v = text(title, 20f, 0xFFFFD76A.toInt(), bold = true)
        v.setPadding(0, dp(20), 0, dp(4))
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        root.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun row(vararg views: View) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (v in views) {
            root.removeView(v)
            r.addView(v)
        }
        root.addView(r)
    }

    companion object {
        const val ADB_COMMANDS = """
            adb shell pm grant com.senseart.subsync android.permission.WRITE_SECURE_SETTINGS
            adb shell appops set com.senseart.subsync REQUEST_INSTALL_PACKAGES allow
            adb shell appops set com.senseart.subsync ACCESS_RESTRICTED_SETTINGS allow
            adb shell appops set com.senseart.subsync MANAGE_EXTERNAL_STORAGE allow
            adb shell cmd notification allow_listener com.senseart.subsync/com.senseart.subsync.MediaListenerService
            adb shell settings put secure enabled_accessibility_services com.senseart.subsync/com.senseart.subsync.SubSyncService
            adb shell settings put secure accessibility_enabled 1
        """

        const val HELP = """
            • Отворете филма от файловия мениджър (с „Отвори с → Subtitle Synchronizer“). Филмът тръгва във ViMu, а приложението само намира .srt до него (или в подпапка Subs) и показва субтитрите.
            • Ако не намери файла, натиснете бутона за панела и го изберете – от устройствата или от мрежовите папки. Предложенията със същото име като филма са отбелязани с ★.
            • Задръжте бутона за панела (~1 с), за да го отворите. Краткото натискане работи нормално във ViMu.
            • ◀ ▶ – субтитрите по-рано / по-късно с 0.1 с. Задръжте за 1 с, после 10 с на стъпка.
            • Синхрон по реплика: щом чуете реплика, натиснете OK (моментът се запомня), изберете с ▲▼ тази реплика в списъка и натиснете OK. Отместването се изчислява само.
            • Ако разликата расте с времето, повторете синхрона по реплика по-късно във филма – тогава се изчислява и скоростта. Или в менюто изберете 25 ↔ 23.976 fps.
            • Бутонът за панела (задържан) още веднъж отваря менюто (скорост, запис в .srt, друг файл, размер, пауза).
            • BACK затваря панела. Докато панелът е затворен, всички бутони работят нормално във ViMu.
            • Настройката се помни за всеки .srt файл. „Запиши в .srt“ записва синхрона в самия файл (оригиналът остава като .bak).
        """
    }
}
