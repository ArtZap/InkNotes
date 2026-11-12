package dev.inknotes.editor

/**
 * Which keys may be taught as the pen button. Typing and moving keys must keep their meaning
 * everywhere (in a notebook title, in text boxes), so they and whole alphabetic keyboards are
 * refused. Key codes as Android's KeyEvent numbers them; plain JVM.
 */
object PenKeys {
    private val SYSTEM = setOf(3, 4, 24, 25, 26) // home, back, volume up/down, power
    private val LETTERS = 29..54 // A…Z
    private val DIGITS = 7..16 // 0…9
    private val NUMPAD = 144..163 // numpad digits, signs, enter, equals, parentheses
    private val TYPING = setOf(
        19, 20, 21, 22, 23, // arrows, centre
        55, 56, 61, 62, 66, 67, // comma, period, tab, space, enter, backspace
        68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 81, // ` - = [ ] \ ; ' / @ +
        111, 112, 122, 123, 124, // escape, forward delete, home, end, insert
        57, 58, 59, 60, 113, 114, 115, 116, 117, 118, 119, // alt, shift, ctrl, caps lock, scroll lock, meta, function
    )

    /** [alphabetic]: the key came from a full keyboard (KeyCharacterMap's ALPHA type). */
    fun canLearn(keyCode: Int, alphabetic: Boolean): Boolean =
        !alphabetic && keyCode > 0 && keyCode !in SYSTEM && keyCode !in LETTERS && keyCode !in DIGITS &&
            keyCode !in NUMPAD && keyCode !in TYPING
}
