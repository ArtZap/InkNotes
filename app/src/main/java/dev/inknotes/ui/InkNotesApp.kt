package dev.inknotes.ui

import android.app.Application

/**
 * Sets the interface language before anything else of the app runs: names kept in enums and
 * objects (themes, styles, paper kinds) are made once, in the language set at that moment.
 */
class InkNotesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Language.init(this)
    }
}
