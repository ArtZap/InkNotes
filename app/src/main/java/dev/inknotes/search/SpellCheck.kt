package dev.inknotes.search

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import java.io.File
import java.util.Locale

/**
 * Possible misspellings found on a page, while its strokes are still [strokes] (the list the
 * page had when it was checked): writing anything there takes the marks away.
 */
class PageTypos(val strokes: List<dev.inknotes.data.PageStroke>, val items: List<Spelling.Typo>)

/**
 * Words looked up by the spell checker of the system (the keyboard's): offline, with no
 * dictionary of our own in the app. Words the writer told to skip are kept in a file on the
 * device. Everything runs on the main thread.
 */
class SpellCheck(private val context: Context) {

    sealed interface Result {
        /** Misspelt words, each with what the checker suggests (maybe nothing). */
        class Typos(val words: Map<String, List<String>>) : Result

        /** The system has no spell checker, or it is turned off. */
        data object NoChecker : Result

        /** There is a checker, but it does not know this language. */
        data object NoLanguage : Result

        /** It did not answer in time. */
        data object Silent : Result
    }

    private val main = Handler(Looper.getMainLooper())
    private val ignoredFile = File(context.filesDir, "spelling-ignore.txt")
    private val ignored: MutableSet<String> =
        runCatching { ignoredFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet() }.getOrDefault(HashSet())

    fun isIgnored(word: String) = word.lowercase() in ignored

    /** [word] is right: not marked again. */
    fun ignore(word: String) {
        if (ignored.add(word.lowercase())) runCatching { ignoredFile.writeText(ignored.sorted().joinToString("\n")) }
    }

    /** Looks [words] up in [lang]; [onResult] once, on the main thread. */
    fun check(words: Collection<String>, lang: Spelling.Lang, onResult: (Result) -> Unit) {
        val tsm = context.getSystemService(Context.TEXT_SERVICES_MANAGER_SERVICE) as? TextServicesManager
            ?: return onResult(Result.NoChecker)
        val list = words.filterNot(::isIgnored).distinct()
        if (list.isEmpty()) return onResult(Result.Typos(emptyMap()))
        // A made-up word first: a checker that lets it through does not know the language.
        val probe = if (lang == Spelling.Lang.RU) "ывапрлдж" else "qzxwvtrplk" // ru-only
        val all = listOf(probe) + list
        var done = false
        val typos = LinkedHashMap<String, List<String>>()
        var probeCaught = false
        var session: SpellCheckerSession? = null
        var next = 0
        fun finish(r: Result) {
            if (done) return
            done = true
            runCatching { session?.close() }
            onResult(r)
        }
        val timeout = Runnable { finish(Result.Silent) }
        fun take(info: SuggestionsInfo?, word: String) {
            if (info == null) return
            val typo = info.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO != 0
            if (word == probe) {
                probeCaught = typo
                return
            }
            if (typo) typos[word] = (0 until maxOf(info.suggestionsCount, 0)).map { info.getSuggestionAt(it) }.filter { it.isNotBlank() && it != word }
        }
        lateinit var send: () -> Unit
        val listener = object : SpellCheckerSession.SpellCheckerSessionListener {
            override fun onGetSuggestions(results: Array<out SuggestionsInfo?>?) {}

            override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo?>?) {
                if (done) return
                for (s in results.orEmpty()) {
                    if (s == null) continue
                    for (k in 0 until s.suggestionsCount) {
                        val info = s.getSuggestionsInfoAt(k) ?: continue
                        all.getOrNull(info.cookie)?.let { take(info, it) }
                    }
                }
                if (next < all.size) {
                    send()
                } else {
                    main.removeCallbacks(timeout)
                    finish(if (!probeCaught) Result.NoLanguage else Result.Typos(typos))
                }
            }
        }
        val locale = if (lang == Spelling.Lang.RU) Locale("ru", "RU") else Locale.US
        session = runCatching { tsm.newSpellCheckerSession(null, locale, listener, false) }.getOrNull()
            ?: return onResult(Result.NoChecker)
        send = {
            val from = next
            val to = minOf(all.size, from + BATCH)
            next = to
            val infos = (from until to).map { TextInfo(all[it], 0, all[it].length, it, it) }.toTypedArray()
            runCatching { session!!.getSentenceSuggestions(infos, SUGGESTIONS) }.onFailure { finish(Result.NoChecker) }
        }
        main.postDelayed(timeout, TIMEOUT_MS)
        send()
    }

    private companion object {
        const val BATCH = 40
        const val SUGGESTIONS = 4
        const val TIMEOUT_MS = 8000L
    }
}
