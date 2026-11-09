package com.senseart.subsync

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.KeyEvent

object Keys {
    /**
     * Бутони, които не могат да станат бутон за панела. Стрелките са нужни в самия панел.
     * OK и BACK са позволени само при отваряне със задържане (краткото натискане си остава нормално).
     */
    fun isForbiddenHotkey(code: Int, holdMode: Boolean) = when (code) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_UNKNOWN -> true

        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BACK -> !holdMode

        else -> false
    }

    /**
     * Системно действие, с което приложението може само да „натисне“ бутона вместо потребителя
     * (за кратко натискане при режим „задържане“). null – за този бутон Android не позволява.
     */
    fun replayAction(code: Int): Int? = when (code) {
        KeyEvent.KEYCODE_BACK -> AccessibilityService.GLOBAL_ACTION_BACK
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
            if (Build.VERSION.SDK_INT >= 33) AccessibilityService.GLOBAL_ACTION_DPAD_CENTER else null

        KeyEvent.KEYCODE_HEADSETHOOK ->
            if (Build.VERSION.SDK_INT >= 31) AccessibilityService.GLOBAL_ACTION_KEYCODE_HEADSETHOOK else null

        else -> null
    }

    fun name(code: Int): String =
        if (code == 0) "не е избран" else KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")

    fun isOk(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER ||
        code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
}
