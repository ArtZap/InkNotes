package dev.inknotes.editor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PenKeysTest {
    @Test
    fun typingKeysCanNotBecomeThePenButton() {
        // A, Z, 0, Enter, Tab, Space, arrows, backspace, numpad 5, Escape, Back, Volume.
        for (code in listOf(29, 54, 7, 66, 61, 62, 19, 22, 67, 149, 111, 4, 24)) assertFalse("$code", PenKeys.canLearn(code, alphabetic = false))
    }

    @Test
    fun penKeysCanUnlessFromAKeyboard() {
        // Huawei M-Pencil's 718, page down, a media key.
        for (code in listOf(718, 93, 85)) assertTrue("$code", PenKeys.canLearn(code, alphabetic = false))
        assertFalse(PenKeys.canLearn(718, alphabetic = true))
        assertFalse(PenKeys.canLearn(0, alphabetic = false))
    }
}
