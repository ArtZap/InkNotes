package dev.inknotes.math

import java.io.File

/**
 * What the writer corrected by hand, on the device only: formula text pinned to the strokes it
 * was written with ([Correct.fingerprint]), the symbols they chose instead of the reader's
 * ([Learned]), and the two settings of the correction window. Small files in [dir], written
 * at once on each change.
 */
class FixStore(private val dir: File?) {

    enum class Ask { UNSURE, SOMETIMES, NEVER }

    private val pins = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_PINS
    }

    val learned: Learned

    /** The writer's own symbols (shape and what it is), a second voice beside ML Kit. */
    val samples: Samples

    @Volatile
    var learn: Boolean = true
        set(value) {
            field = value
            saveSettings()
        }

    @Volatile
    var ask: Ask = Ask.UNSURE
        set(value) {
            field = value
            saveSettings()
        }

    init {
        learned = runCatching { Learned.parse(file(LEARNED)!!.readText()) }.getOrDefault(Learned())
        samples = runCatching { Samples.parse(file(SAMPLES)!!.readText()) }.getOrDefault(Samples())
        runCatching {
            for (line in file(PINS)!!.readLines()) {
                val tab = line.indexOf('\t')
                if (tab > 0) pins[line.substring(0, tab)] = line.substring(tab + 1)
            }
        }
        runCatching {
            for (line in file(SETTINGS)!!.readLines()) {
                val (k, v) = line.split('=', limit = 2).takeIf { it.size == 2 } ?: continue
                when (k) {
                    "learn" -> learn = v == "1"
                    "ask" -> ask = Ask.valueOf(v)
                }
            }
        }
    }

    private fun file(name: String) = dir?.let { File(it, name) }

    /** The pinned texts, a copy safe to read on another thread. */
    @Synchronized
    fun pins(): Map<String, String> = HashMap(pins)

    @Synchronized
    fun pin(fingerprint: String, text: String) {
        pins[fingerprint] = text
        savePins()
    }

    @Synchronized
    fun unpin(fingerprint: String) {
        if (pins.remove(fingerprint) != null) savePins()
    }

    /**
     * The writer took [chosen] for symbols the reader read first as [read] (same order), drawn
     * as [strokes] (each symbol's): the choices counted, the shapes kept as samples.
     */
    fun learn(read: List<String>, chosen: List<String>, strokes: List<List<Ink>> = emptyList()) {
        if (!learn) return
        for (i in read.indices) learned.record(read[i], chosen.getOrElse(i) { read[i] })
        for ((i, s) in strokes.withIndex()) chosen.getOrNull(i)?.let { samples.add(it, s) }
        runCatching { file(LEARNED)?.writeText(learned.serialize()) }
        if (strokes.isNotEmpty()) runCatching { file(SAMPLES)?.writeText(samples.serialize()) }
    }

    /** Forgets the handwriting learned (the pinned formulas stay: they are the writer's text). */
    fun forget() {
        learned.clear()
        samples.clear()
        runCatching { file(LEARNED)?.delete() }
        runCatching { file(SAMPLES)?.delete() }
    }

    @Synchronized
    private fun savePins() {
        runCatching { file(PINS)?.writeText(pins.entries.joinToString("") { (k, v) -> k + "\t" + v.replace('\n', ' ') + "\n" }) }
    }

    private fun saveSettings() {
        runCatching { file(SETTINGS)?.writeText("learn=${if (learn) 1 else 0}\nask=${ask.name}\n") }
    }

    private companion object {
        const val PINS = "formula-pins.tsv"
        const val LEARNED = "formula-learned.tsv"
        const val SAMPLES = "formula-samples.tsv"
        const val SETTINGS = "formula-settings.txt"
        const val MAX_PINS = 5000
    }
}
