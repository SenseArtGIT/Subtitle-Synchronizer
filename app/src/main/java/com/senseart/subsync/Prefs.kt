package com.senseart.subsync

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("subsync", Context.MODE_PRIVATE)

    var hotkey: Int
        get() = sp.getInt(K_HOTKEY, 0)
        set(v) = sp.edit().putInt(K_HOTKEY, v).apply()

    var learning: Boolean
        get() = sp.getBoolean(K_LEARNING, false)
        set(v) = sp.edit().putBoolean(K_LEARNING, v).apply()

    var textSizeSp: Int
        get() = sp.getInt(K_TEXT_SIZE, 30)
        set(v) = sp.edit().putInt(K_TEXT_SIZE, v.coerceIn(12, 80)).apply()

    var bottomMarginDp: Int
        get() = sp.getInt(K_BOTTOM, 36)
        set(v) = sp.edit().putInt(K_BOTTOM, v.coerceIn(0, 400)).apply()

    var calibrationMs: Long
        get() = sp.getLong(K_CALIB, 0)
        set(v) = sp.edit().putLong(K_CALIB, v).apply()

    var defaultOffsetMs: Long
        get() = sp.getLong(K_DEF_OFFSET, 0)
        set(v) = sp.edit().putLong(K_DEF_OFFSET, v).apply()

    var lastDir: String?
        get() = sp.getString(K_LAST_DIR, null)
        set(v) = sp.edit().putString(K_LAST_DIR, v).apply()

    var lastFile: String?
        get() = sp.getString(K_LAST_FILE, null)
        set(v) = sp.edit().putString(K_LAST_FILE, v).apply()

    var playerPackage: String
        get() = sp.getString(K_PLAYER, VIMU_PACKAGE) ?: VIMU_PACKAGE
        set(v) = sp.edit().putString(K_PLAYER, v).apply()

    var forceManualClock: Boolean
        get() = sp.getBoolean(K_FORCE_MANUAL, false)
        set(v) = sp.edit().putBoolean(K_FORCE_MANUAL, v).apply()

    /** Ако OK на дистанционното слага пауза във ViMu (важи само за ръчния часовник). */
    var okPausesPlayer: Boolean
        get() = sp.getBoolean(K_OK_PAUSES, false)
        set(v) = sp.edit().putBoolean(K_OK_PAUSES, v).apply()

    fun loadModel(path: String): SyncModel? {
        if (!sp.contains("off:$path")) return null
        val off = sp.getLong("off:$path", 0)
        val scale = sp.getString("scale:$path", "1.0")?.toDoubleOrNull() ?: 1.0
        return SyncModel(off, scale)
    }

    fun saveModel(path: String, m: SyncModel) {
        sp.edit().putLong("off:$path", m.offsetMs).putString("scale:$path", m.scale.toString()).apply()
    }

    companion object {
        const val VIMU_PACKAGE = "net.gtvbox.videoplayer"
        const val K_HOTKEY = "hotkey"
        const val K_LEARNING = "learning"
        const val K_TEXT_SIZE = "text_size"
        const val K_BOTTOM = "bottom_margin"
        const val K_CALIB = "calibration"
        const val K_DEF_OFFSET = "default_offset"
        const val K_LAST_DIR = "last_dir"
        const val K_LAST_FILE = "last_file"
        const val K_PLAYER = "player_pkg"
        const val K_FORCE_MANUAL = "force_manual"
        const val K_OK_PAUSES = "ok_pauses"
    }
}
