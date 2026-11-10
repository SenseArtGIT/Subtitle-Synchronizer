package com.senseart.subsync

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Самопоправяне на „Достъпност“.
 *
 * Android изключва услугата за достъпност на ръчно инсталирани приложения при обновяване.
 * Ако веднъж е дадено разрешението WRITE_SECURE_SETTINGS (с adb, еднократно – запазва се
 * и при обновяване), приложението само се включва обратно.
 */
object SelfHeal {
    private const val TAG = "SubSync"

    fun component(ctx: Context) = ComponentName(ctx, SubSyncService::class.java).flattenToString()

    fun isAccessibilityOn(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return s.split(':').any { it.startsWith(ctx.packageName + "/") }
    }

    fun canWriteSecure(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Включва услугата, ако е изключена и имаме право. Връща true, ако я е включила сега. */
    fun ensureAccessibility(ctx: Context): Boolean {
        if (isAccessibilityOn(ctx) || !canWriteSecure(ctx)) return false
        return try {
            val cr = ctx.contentResolver
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.split(':')?.filter { it.isNotBlank() && !it.startsWith(ctx.packageName + "/") } ?: emptyList()
            val all = (current + component(ctx)).joinToString(":")
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, all)
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "Достъпността е включена отново автоматично")
            true
        } catch (e: Exception) {
            Log.w(TAG, "ensureAccessibility", e)
            false
        }
    }
}

/** След обновяване на приложението. */
class SelfHealReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // Само след обновяване – ако потребителят сам я е изключил, не я пипаме.
            // В режим „само докато гледам филм“ не я включваме – ще се включи при следващия филм.
            Intent.ACTION_MY_PACKAGE_REPLACED ->
                if (!Prefs(context).autoA11y) SelfHeal.ensureAccessibility(context)
        }
    }
}
