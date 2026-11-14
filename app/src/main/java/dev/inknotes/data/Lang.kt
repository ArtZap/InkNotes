package dev.inknotes.data

/**
 * The language of the interface. Texts are written in the code in both languages, `tr("Ru", "En")`:
 * it works the same in the Android app and in the plain JVM tests of the math and data code,
 * which have no Android resources. Russian is the default until the app sets the language
 * (see dev.inknotes.ui.Language); changing it restarts the app, so texts kept in objects and
 * enums are made again in the new language.
 *
 * Another language later: a third parameter with English as its fallback.
 */
object Lang {
    @Volatile
    var english: Boolean = false
}

/** [ru] or [en], in the language of the interface. */
fun tr(ru: String, en: String): String = if (Lang.english) en else ru
