package dev.inknotes.editor

import dev.inknotes.data.tr
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

    /** Keys from a full keyboard never count (typing must work), nor a typing key taught before. */
    fun matches(e: KeyEvent): Boolean =
        keyCode != KeyEvent.KEYCODE_UNKNOWN && e.keyCode == keyCode &&
            (deviceName == null || e.device?.name == deviceName) &&
            PenKeys.canLearn(e.keyCode, alphabetic(e))

    /** Returns false for keys that must keep their meaning: system, typing and keyboard keys. */
    fun learn(e: KeyEvent): Boolean {
        if (!PenKeys.canLearn(e.keyCode, alphabetic(e))) return false
        keyCode = e.keyCode
        deviceName = e.device?.name
        prefs.edit().putInt(KEY_CODE, keyCode).putString(KEY_DEVICE, deviceName).apply()
        return true
    }

    private fun alphabetic(e: KeyEvent) = e.device?.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC

    fun describe(): String =
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN) tr("не назначена", "not assigned")
        else tr("${KeyEvent.keyCodeToString(keyCode)} (${deviceName ?: "любое устройство"})", "${KeyEvent.keyCodeToString(keyCode)} (${deviceName ?: "any device"})")

    private companion object {
        const val KEY_CODE = "keyCode"
        const val KEY_DEVICE = "device"
        const val DEFAULT_KEY_CODE = 718
        const val DEFAULT_DEVICE = "M-Pencil"
    }
}
