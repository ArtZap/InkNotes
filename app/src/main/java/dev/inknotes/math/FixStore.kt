package dev.inknotes.math

import java.io.File

/**
 * What the writer corrected by hand, on the device only: formula text pinned to the strokes it
 * was written with ([Correct.fingerprint]), the symbols they chose instead of the reader's
 * ([Learned]), and the two settings of the correction window. Small files in [dir].
 *
 * Every file is written whole to a side file and renamed over the old one, so a write cut off
 * leaves the old file. With [background] the writing goes on one thread of its own, in order,
 * the latest text of each file only; the caller never waits for the disk. A file that cannot
 * be read is kept as `….broken` instead of being written over.
 */
class FixStore(private val dir: File?, private val background: Boolean = false) {

    enum class Ask { UNSURE, SOMETIMES, NEVER }

    /** The thread files are written on, in order (declared first: init already writes). */
    private val io = if (background) java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "fix-store").apply { isDaemon = true } } else null

    private val pins = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX_PINS
    }

    /** Numbers set on a scale ([Knob]), by the fingerprint of the strokes they replace. */
    private val scales = object : LinkedHashMap<String, Double>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Double>?) = size > MAX_PINS
    }

    val learned: Learned

    /** The writer's own symbols (shape and what it is), a second voice beside ML Kit. */
    val samples: Samples

    /** Where the writer puts exponents and indexes. */
    val placements: Placements

    /** Points the writer put on graphs, by the fingerprint of the graph's formula: x, y. */
    private val graphPoints = LinkedHashMap<String, List<Pair<Double, Double>>>()

    /** Graphs moved or sized on the page by hand, by the same key as [graphPoints]: dx, dy, size. */
    private val graphPlaces = LinkedHashMap<String, FloatArray>()

    @Synchronized
    fun place(key: String): FloatArray? = graphPlaces[key]?.copyOf()

    /** [p]: dx, dy, size; null puts the graph back where it was drawn. */
    @Synchronized
    fun setPlace(key: String, p: FloatArray?) {
        if (p == null) graphPlaces.remove(key) else graphPlaces[key] = p.copyOf()
        write(PLACED, printPlaces())
    }

    private fun printPlaces() = graphPlaces.entries.joinToString("") { (k, p) -> k + "\t" + p.joinToString(",") + "\n" }

    private fun parsePlaces(text: String): Map<String, FloatArray> {
        val out = LinkedHashMap<String, FloatArray>()
        for (line in text.lines()) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val v = line.substring(tab + 1).split(',').mapNotNull { it.toFloatOrNull() }
            if (v.size == 3 && v.all { it.isFinite() } && v[2] > 0f) out[line.substring(0, tab)] = v.toFloatArray()
        }
        return out
    }

    /** The writer's digits and signs for answers in their hand. */
    val hand: HandGlyphs

    /** Answers after "=" are drawn with [hand] where it has every symbol of them. */
    @Volatile
    var inHand: Boolean = false
        set(value) {
            field = value
            saveSettings()
        }

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

    /** The text of file [name], or null; one there but unreadable is set aside, not lost. */
    private fun read(name: String): String? {
        val f = file(name)?.takeIf { it.exists() } ?: return null
        return runCatching { f.readText() }.getOrElse {
            runCatching { f.renameTo(File(f.parentFile, f.name + ".broken")) }
            null
        }
    }

    init {
        learned = read(LEARNED)?.let { runCatching { Learned.parse(it) }.getOrNull() } ?: Learned()
        samples = read(SAMPLES)?.let { runCatching { Samples.parse(it) }.getOrNull() } ?: Samples()
        placements = read(PLACES)?.let { runCatching { Placements.parse(it) }.getOrNull() } ?: Placements()
        hand = read(HAND)?.let { runCatching { HandGlyphs.parse(it) }.getOrNull() } ?: HandGlyphs()
        read(PINS)?.let { text ->
            for (line in text.lines()) {
                val tab = line.indexOf('\t')
                if (tab > 0) pins[line.substring(0, tab)] = line.substring(tab + 1)
            }
        }
        read(POINTS)?.let { text -> graphPoints.putAll(parsePoints(text)) }
        read(PLACED)?.let { text -> graphPlaces.putAll(parsePlaces(text)) }
        read(SCALES)?.let { text ->
            for (line in text.lines()) {
                val tab = line.indexOf('\t')
                val v = line.substring(tab + 1).toDoubleOrNull()
                if (tab > 0 && v != null && v.isFinite()) scales[line.substring(0, tab)] = v
            }
        }
        read(SETTINGS)?.let { text ->
            for (line in text.lines()) {
                val (k, v) = line.split('=', limit = 2).takeIf { it.size == 2 } ?: continue
                when (k) {
                    "learn" -> learn = v == "1"
                    "ask" -> ask = Ask.valueOf(v)
                    "hand" -> inHand = v == "1"
                }
            }
        }
    }

    private fun file(name: String) = dir?.let { File(it, name) }

    /** The pinned texts, a copy safe to read on another thread. */
    @Synchronized
    fun pins(): Map<String, String> = HashMap(pins)

    /** The points put on the graph of [key] (see [graphPoints]). */
    @Synchronized
    fun points(key: String): List<Pair<Double, Double>> = graphPoints[key].orEmpty()

    @Synchronized
    fun setPoints(key: String, points: List<Pair<Double, Double>>) {
        if (points.isEmpty()) graphPoints.remove(key) else graphPoints[key] = points.take(MAX_POINTS)
        write(POINTS, printPoints())
    }

    private fun printPoints() = graphPoints.entries.joinToString("") { (k, ps) -> k + "\t" + ps.joinToString(";") { (x, y) -> "$x,$y" } + "\n" }

    private fun parsePoints(text: String): Map<String, List<Pair<Double, Double>>> {
        val out = LinkedHashMap<String, List<Pair<Double, Double>>>()
        for (line in text.lines()) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val ps = line.substring(tab + 1).split(';').mapNotNull { p ->
                val xy = p.split(',')
                val x = xy.getOrNull(0)?.toDoubleOrNull()
                val y = xy.getOrNull(1)?.toDoubleOrNull()
                if (x != null && y != null && x.isFinite() && y.isFinite()) x to y else null
            }
            if (ps.isNotEmpty()) out[line.substring(0, tab)] = ps
        }
        return out
    }

    /** The numbers set on scales, a copy. */
    @Synchronized
    fun scales(): Map<String, Double> = HashMap(scales)

    /** The number written as the strokes of [key] is taken as [value]; null: as written again. */
    @Synchronized
    fun scale(key: String, value: Double?) {
        if (value == null) scales.remove(key) else scales[key] = value
        write(SCALES, scales.entries.joinToString("") { (k, v) -> k + "\t" + v + "\n" })
    }

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
    fun learn(read: List<String>, chosen: List<String>, strokes: List<List<Ink>> = emptyList(), places: List<Placements.P> = emptyList()) {
        if (!learn) return
        // Counting and the shapes (32 points each) are work too: done where the writing is.
        later {
            places.forEach(placements::add)
            for (i in read.indices) learned.record(read[i], chosen.getOrElse(i) { read[i] })
            for ((i, s) in strokes.withIndex()) chosen.getOrNull(i)?.let { samples.add(it, s) }
            if (places.isNotEmpty()) put(PLACES, placements.serialize())
            put(LEARNED, learned.serialize())
            if (strokes.isNotEmpty()) put(SAMPLES, samples.serialize())
        }
    }

    /** Writes [hand] down after a change. */
    fun saveHand() = later { put(HAND, hand.serialize()) }

    /** Forgets the handwriting learned (the pinned formulas stay: they are the writer's text). */
    fun forget() = later {
        learned.clear()
        samples.clear()
        placements.clear()
        for (name in listOf(PLACES, LEARNED, SAMPLES)) runCatching { file(name)?.delete() }
    }

    @Synchronized
    private fun savePins() {
        write(PINS, pins.entries.joinToString("") { (k, v) -> k + "\t" + v.replace('\n', ' ') + "\n" })
    }

    private fun saveSettings() {
        write(SETTINGS, "learn=${if (learn) 1 else 0}\nask=${ask.name}\nhand=${if (inHand) 1 else 0}\n")
    }

    // --- writing

    /** [job] on the writing thread (at once without [background]), after what came before. */
    private fun later(job: () -> Unit) {
        val run = { runCatching(job) }
        if (io == null) run() else io.execute { run() }
    }

    /** [text] into file [name], on the writing thread. */
    private fun write(name: String, text: String) = later { put(name, text) }

    /** On the writing thread: the whole file to a side file, then renamed over the old one. */
    private fun put(name: String, text: String) {
        val f = file(name) ?: return
        val tmp = File(f.parentFile, f.name + ".tmp")
        runCatching {
            java.io.FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray())
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(f)) throw java.io.IOException("rename failed: $tmp")
        }.onFailure { runCatching { tmp.delete() } }
    }

    /** Waits until everything asked before is on disk (for a backup, a restart, tests). */
    fun awaitWrites(timeoutMs: Long = 5000) {
        val q = io ?: return
        val done = java.util.concurrent.CountDownLatch(1)
        runCatching { q.execute { done.countDown() } }
        runCatching { done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
    }

    // --- backup

    /** The files a backup takes, as they are on disk now: name to text. */
    fun backupFiles(): Map<String, String> {
        awaitWrites()
        return FILES.mapNotNull { n -> file(n)?.takeIf { it.isFile }?.let { f -> runCatching { n to f.readText() }.getOrNull() } }.toMap()
    }

    /**
     * Files from a backup ([backupFiles]), into what is empty here only: a new tablet, or the
     * app installed again. What this device has learned already is never replaced.
     * Returns whether anything came in.
     */
    fun restore(files: Map<String, String>): Boolean {
        var any = false
        files[LEARNED]?.takeIf { learned.isEmpty() }?.let { runCatching { Learned.parse(it) }.getOrNull() }?.takeIf { !it.isEmpty() }?.let { learned.absorb(it); write(LEARNED, learned.serialize()); any = true }
        files[SAMPLES]?.takeIf { samples.isEmpty() }?.let { runCatching { Samples.parse(it) }.getOrNull() }?.takeIf { !it.isEmpty() }?.let { samples.absorb(it); write(SAMPLES, samples.serialize()); any = true }
        files[PLACES]?.takeIf { placements.isEmpty() }?.let { runCatching { Placements.parse(it) }.getOrNull() }?.takeIf { !it.isEmpty() }?.let { placements.absorb(it); write(PLACES, placements.serialize()); any = true }
        files[HAND]?.takeIf { hand.isEmpty() }?.let { runCatching { HandGlyphs.parse(it) }.getOrNull() }?.takeIf { !it.isEmpty() }?.let { hand.absorb(it); write(HAND, hand.serialize()); any = true }
        synchronized(this) {
            files[PINS]?.takeIf { pins.isEmpty() }?.let { text ->
                for (line in text.lines()) {
                    val tab = line.indexOf('\t')
                    if (tab > 0) pins[line.substring(0, tab)] = line.substring(tab + 1)
                }
                if (pins.isNotEmpty()) {
                    savePins()
                    any = true
                }
            }
            files[PLACED]?.takeIf { graphPlaces.isEmpty() }?.let { text ->
                graphPlaces.putAll(parsePlaces(text))
                if (graphPlaces.isNotEmpty()) {
                    write(PLACED, printPlaces())
                    any = true
                }
            }
            files[POINTS]?.takeIf { graphPoints.isEmpty() }?.let { text ->
                graphPoints.putAll(parsePoints(text))
                if (graphPoints.isNotEmpty()) {
                    write(POINTS, printPoints())
                    any = true
                }
            }
            files[SCALES]?.takeIf { scales.isEmpty() }?.let { text ->
                for (line in text.lines()) {
                    val tab = line.indexOf('\t')
                    val v = line.substring(tab + 1).toDoubleOrNull()
                    if (tab > 0 && v != null && v.isFinite()) scales[line.substring(0, tab)] = v
                }
                if (scales.isNotEmpty()) {
                    write(SCALES, scales.entries.joinToString("") { (k, v) -> k + "\t" + v + "\n" })
                    any = true
                }
            }
        }
        return any
    }

    private companion object {
        const val PINS = "formula-pins.tsv"
        const val LEARNED = "formula-learned.tsv"
        const val SAMPLES = "formula-samples.tsv"
        const val PLACES = "formula-places.tsv"
        const val SETTINGS = "formula-settings.txt"
        const val SCALES = "formula-scales.tsv"
        const val HAND = "hand-glyphs.tsv"
        const val POINTS = "graph-points.tsv"
        const val PLACED = "graph-places.tsv"
        const val MAX_POINTS = 50

        /** What a backup takes (the settings stay with the device). */
        val FILES = listOf(PINS, LEARNED, SAMPLES, PLACES, SCALES, HAND, POINTS, PLACED)
        const val MAX_PINS = 5000
    }
}
