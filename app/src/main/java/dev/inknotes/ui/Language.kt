package dev.inknotes.ui

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import dev.inknotes.data.Lang
import java.util.Locale

/**
 * The language the app is shown in: as the system's, Russian or English. English is used for
 * every system language other than Russian.
 *
 * On Android 13+ the choice is the system's own per-app language (also in the system
 * settings of the app); before, it is kept in the app's settings and put on each screen by
 * [wrap]. A change restarts the app: texts kept in objects and enums are made anew.
 */
object Language {
    const val SYSTEM = ""
    const val RU = "ru"
    const val EN = "en"

    /** What was chosen: [SYSTEM], [RU] or [EN]. */
    fun chosen(context: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales?.get(0)?.language?.let(::known) ?: SYSTEM
        } else {
            known(prefs(context).getString(KEY, SYSTEM).orEmpty())
        }

    private fun known(tag: String) = when (tag) {
        RU, EN -> tag
        else -> SYSTEM
    }

    /** The language shown: the chosen one, or the system's (Russian, or else English). */
    fun effective(context: Context): String = chosen(context).ifEmpty {
        if (systemLanguage() == RU) RU else EN
    }

    private fun systemLanguage(): String =
        (if (Build.VERSION.SDK_INT >= 24) LocaleList.getDefault()[0] else Locale.getDefault()).language

    /** Sets [Lang] for this run of the app. Called once, before any text is made. */
    fun init(context: Context) {
        Lang.english = effective(context) == EN
    }

    /**
     * The screen's context in the chosen language (before Android 13, where the system does
     * not do it): its own resources (the app's name, the widget) follow the choice too.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = known(prefs(base).getString(KEY, SYSTEM).orEmpty())
        if (tag.isEmpty()) return base
        val locale = Locale(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /** Changes the language and starts the app again in it, on the screen it was on. */
    fun set(activity: Activity, tag: String) {
        if (tag == chosen(activity)) return
        prefs(activity).edit().putString(KEY, tag).commit()
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        }
        restart(activity)
    }

    /**
     * The language the app runs in is not the one chosen now (changed in the system settings
     * of the app while it ran): it starts again. True if it does.
     */
    fun restartIfChanged(activity: Activity): Boolean {
        if ((effective(activity) == EN) == Lang.english) return false
        restart(activity)
        return true
    }

    private fun restart(activity: Activity) {
        val intent = Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(intent)
        activity.finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    private fun prefs(context: Context) = context.getSharedPreferences("language", Context.MODE_PRIVATE)

    private const val KEY = "language"
}
