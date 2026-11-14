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
        if (Lang.fixed) return
        Lang.english = effective(context) == EN
    }

    /**
     * The screen's context in the chosen language (before Android 13, where the system does
     * not do it): its own resources (the app's name, the widget) follow the choice too.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = if (Lang.fixed) (if (Lang.english) EN else RU) else known(prefs(base).getString(KEY, SYSTEM).orEmpty())
        if (tag.isEmpty()) return base
        val locale = Locale(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /**
     * The language handwriting is read in for search: [SYSTEM] here means as the interface. Set
     * apart from the interface: an English phone can hold Russian notes.
     */
    fun handwriting(context: Context): String = known(prefs(context).getString(KEY_HANDWRITING, SYSTEM).orEmpty())

    /** True when handwriting is read with the English model. */
    fun handwritingEnglish(context: Context): Boolean = if (Lang.fixed) Lang.english else handwriting(context).ifEmpty { effective(context) } == EN

    /** Changes the handwriting language; the app starts again, as it reads with one model per run. */
    fun setHandwriting(activity: Activity, tag: String) {
        if (tag == handwriting(activity)) return
        prefs(activity).edit().putString(KEY_HANDWRITING, tag).commit()
        restart(activity)
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
        if (Lang.fixed || (effective(activity) == EN) == Lang.english) return false
        restart(activity)
        return true
    }

    /**
     * Starts the app anew in the language now set: names made once (enums, objects) are made
     * again. Open pages and a recording are saved first: the process is ended on purpose.
     */
    private fun restart(activity: Activity) {
        (activity as? MainActivity)?.saveBeforeRestart()
        val intent = Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(intent)
        activity.finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    /** The screen behind [context]: a dialog's context only wraps it. */
    fun activityOf(context: Context): Activity? {
        var c: Context? = context
        while (c != null) {
            if (c is Activity) return c
            c = (c as? android.content.ContextWrapper)?.baseContext
        }
        return null
    }

    private fun prefs(context: Context) = context.getSharedPreferences("language", Context.MODE_PRIVATE)

    private const val KEY = "language"
    private const val KEY_HANDWRITING = "handwriting"
}
