package dev.inknotes.editor

import android.content.Context
import android.view.KeyEvent

/**
 * Pen buttons arrive differently per vendor: as MotionEvent button state (handled in
 * [PageEditorView]) or as key events from a separate Bluetooth device (Huawei M-Pencil
 * sends keycode 718). This stores whichever key the user taught us.
 */
class PenButtonBinding(context: Context) {
    private val prefs = context.getSharedPreferences("pen_button", Context.MODE_PRIVATE)

    var keyCode: Int = prefs.getInt(KEY_CODE, DEFAULT_KEY_CODE)
        private set
    var deviceName: String? = prefs.getString(KEY_DEVICE, DEFAULT_DEVICE)
        private set

    fun matches(e: KeyEvent): Boolean =
        keyCode != KeyEvent.KEYCODE_UNKNOWN && e.keyCode == keyCode &&
            (deviceName == null || e.device?.name == deviceName)

    /** Returns false for keys that must keep their system meaning. */
    fun learn(e: KeyEvent): Boolean {
        if (e.keyCode in RESERVED) return false
        keyCode = e.keyCode
        deviceName = e.device?.name
        prefs.edit().putInt(KEY_CODE, keyCode).putString(KEY_DEVICE, deviceName).apply()
        return true
    }

    fun describe(): String =
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) "не назначена"
        else "${KeyEvent.keyCodeToString(keyCode)} (${deviceName ?: "любое устройство"})"

    private companion object {
        const val KEY_CODE = "keyCode"
        const val KEY_DEVICE = "device"
        const val DEFAULT_KEY_CODE = 718
        const val DEFAULT_DEVICE = "M-Pencil"
        val RESERVED = setOf(
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
        )
    }
}
