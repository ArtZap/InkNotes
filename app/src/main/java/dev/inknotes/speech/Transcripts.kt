package dev.inknotes.speech

/** A word heard in a recording: from [startMs] to [endMs] into it. */
data class Word(val text: String, val startMs: Long, val endMs: Long)

/** A phrase of a transcript, shown as one line: tap it to hear it. */
data class Phrase(val startMs: Long, val endMs: Long, val text: String)

/**
 * The transcript of one recording: its phrases; [doneMs]: how far the sound has been read
 * (a stopped transcription goes on from there); [complete]: read to the end; [lang]: the
 * model's language ("ru", "en").
 */
data class Transcript(val file: String, val lang: String, val doneMs: Long, val complete: Boolean, val phrases: List<Phrase>) {
    /** The phrase being said at [ms] (or the last one before it, within a few seconds). */
    fun phraseAt(ms: Long): Phrase? =
        phrases.firstOrNull { ms in it.startMs..it.endMs } ?: phrases.lastOrNull { it.endMs <= ms && ms - it.endMs < NEAR_MS }

    val text: String get() = phrases.joinToString("\n") { it.text }

    companion object {
        const val NEAR_MS = 4_000L
    }
}

/**
 * Transcripts of a notebook's recordings in one small text file (`speech.tsv`), kept on this
 * device only: like the handwriting index, they can always be made again.
 *
 *     R <tab> file <tab> lang <tab> doneMs <tab> complete(0/1)
 *     P <tab> file <tab> startMs <tab> endMs <tab> text
 */
object Transcripts {
    private const val HEADER = "#inknotes-speech 1"

    fun encode(all: Collection<Transcript>): String = buildString {
        append(HEADER).append('\n')
        for (t in all) {
            append("R\t").append(clean(t.file)).append('\t').append(clean(t.lang)).append('\t').append(t.doneMs).append('\t').append(if (t.complete) 1 else 0).append('\n')
            for (p in t.phrases) append("P\t").append(clean(t.file)).append('\t').append(p.startMs).append('\t').append(p.endMs).append('\t').append(clean(p.text)).append('\n')
        }
    }

    /** By recording file; lines that don't parse are skipped. */
    fun decode(text: String?): Map<String, Transcript> {
        if (text == null || !text.startsWith(HEADER)) return emptyMap()
        val heads = LinkedHashMap<String, Transcript>()
        val phrases = HashMap<String, MutableList<Phrase>>()
        for (line in text.lineSequence()) {
            val f = line.split('\t')
            when (f.firstOrNull()) {
                "R" -> if (f.size >= 5) {
                    val done = f[3].toLongOrNull() ?: continue
                    heads[f[1]] = Transcript(f[1], f[2], done.coerceAtLeast(0), f[4] == "1", emptyList())
                }
                "P" -> if (f.size >= 5) {
                    val a = f[2].toLongOrNull() ?: continue
                    val b = f[3].toLongOrNull() ?: continue
                    if (b < a || f[4].isBlank()) continue
                    phrases.getOrPut(f[1]) { ArrayList() } += Phrase(a, b, f[4])
                }
            }
        }
        return heads.mapValues { (file, t) -> t.copy(phrases = phrases[file].orEmpty().sortedBy { it.startMs }) }
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    /**
     * The words of one Vosk result (`{"result": [{"conf":…, "end":1.1, "start":0.8,
     * "word":"…"}, …], "text": "…"}`), shifted by [offsetMs]: where the sound fed to the
     * recognizer started in the recording.
     */
    fun words(json: String, offsetMs: Long = 0): List<Word> {
        val list = json.indexOf("\"result\"").takeIf { it >= 0 } ?: return emptyList()
        val out = ArrayList<Word>()
        val obj = Regex("\\{[^{}]*\\}")
        for (m in obj.findAll(json, list)) {
            val o = m.value
            val word = string(o, "word") ?: continue
            val start = number(o, "start") ?: continue
            val end = number(o, "end") ?: continue
            if (word.isBlank() || end < start) continue
            out += Word(word, offsetMs + (start * 1000).toLong(), offsetMs + (end * 1000).toLong())
        }
        return out
    }

    private fun number(o: String, key: String): Double? =
        Regex("\"$key\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?)").find(o)?.groupValues?.get(1)?.toDoubleOrNull()

    private fun string(o: String, key: String): String? {
        val m = Regex("\"$key\"\\s*:\\s*\"").find(o) ?: return null
        val sb = StringBuilder()
        var i = m.range.last + 1
        while (i < o.length) {
            val c = o[i]
            when {
                c == '"' -> return sb.toString()
                c == '\\' && i + 1 < o.length -> {
                    when (val e = o[i + 1]) {
                        'n', 't', 'r' -> sb.append(' ')
                        'u' -> if (i + 5 < o.length) {
                            o.substring(i + 2, i + 6).toIntOrNull(16)?.let { sb.append(it.toChar()) }
                            i += 4
                        }
                        else -> sb.append(e)
                    }
                    i += 2
                    continue
                }
                else -> sb.append(c)
            }
            i++
        }
        return null
    }

    /**
     * Words into lines to read and tap: a new line after a pause, or when the line grows long
     * (a lecture is one long utterance between pauses for breath).
     */
    fun phrases(words: List<Word>): List<Phrase> {
        val out = ArrayList<Phrase>()
        var cur = ArrayList<Word>()
        fun flush() {
            if (cur.isEmpty()) return
            val text = cur.joinToString(" ") { it.text }.replaceFirstChar { it.uppercaseChar() }
            out += Phrase(cur.first().startMs, cur.last().endMs, text)
            cur = ArrayList()
        }
        for (w in words) {
            val last = cur.lastOrNull()
            if (last != null && (w.startMs - last.endMs > PAUSE_MS || cur.size >= MAX_WORDS || w.endMs - cur.first().startMs > MAX_PHRASE_MS)) flush()
            cur += w
        }
        flush()
        return out
    }

    const val PAUSE_MS = 900L
    const val MAX_WORDS = 16
    const val MAX_PHRASE_MS = 10_000L
}

/**
 * 16-bit PCM of any rate and channel count to 16 kHz mono for the recognizer, fed in pieces as
 * the decoder gives them. Each output sample is the mean of the input samples it covers (a box
 * low-pass), or between two of them when the input is slower.
 */
class Downsampler(private val inRate: Int, private val channels: Int, private val outRate: Int = 16_000) {
    private val step = inRate.toDouble() / outRate
    private val half = step / 2
    private var buf = FloatArray(4096)
    private var bufStart = 0L // absolute index of buf[0]
    private var bufLen = 0
    private var pos = 0.0 // absolute input index of the next output sample
    private var partial = FloatArray(0) // samples of a frame cut between two pieces
    private var partialN = 0

    init {
        require(inRate > 0 && channels > 0)
    }

    fun feed(input: ShortArray, n: Int = input.size): ShortArray {
        // Channels mixed to mono, a frame split between pieces carried over.
        var i = 0
        if (partialN > 0) {
            if (partial.size < channels) partial = FloatArray(channels)
            while (partialN < channels && i < n) partial[partialN++] = input[i++].toFloat()
            if (partialN < channels) return ShortArray(0)
            push(partial.sum() / channels)
            partialN = 0
        }
        while (i + channels <= n) {
            var sum = 0f
            for (c in 0 until channels) sum += input[i + c]
            push(sum / channels)
            i += channels
        }
        if (i < n) {
            if (partial.size < channels) partial = FloatArray(channels)
            while (i < n) partial[partialN++] = input[i++].toFloat()
        }
        return drain()
    }

    private fun push(v: Float) {
        if (bufLen == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[bufLen++] = v
    }

    private fun at(abs: Long): Float = buf[(abs - bufStart).toInt()]

    private fun drain(): ShortArray {
        val end = bufStart + bufLen // exclusive
        var out = ShortArray(((end - pos) / step).toInt().coerceAtLeast(0) + 2)
        var n = 0
        while (true) {
            val v: Float
            if (half >= 0.5) {
                val a = Math.ceil(pos - half).toLong().coerceAtLeast(bufStart)
                val b = Math.floor(pos + half).toLong()
                if (b >= end) break
                var sum = 0f
                for (k in a..b) sum += at(k)
                v = sum / (b - a + 1).coerceAtLeast(1)
            } else {
                val a = Math.floor(pos).toLong()
                if (a + 1 >= end) break
                val t = (pos - a).toFloat()
                v = at(a.coerceAtLeast(bufStart)) * (1 - t) + at(a + 1) * t
            }
            if (n == out.size) out = out.copyOf(out.size * 2 + 2)
            out[n++] = v.coerceIn(-32768f, 32767f).toInt().toShort()
            pos += step
        }
        // What no later output needs is dropped.
        val keepFrom = (Math.floor(pos - half).toLong() - 1).coerceAtLeast(bufStart)
        val drop = (keepFrom - bufStart).toInt().coerceIn(0, bufLen)
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, bufLen - drop)
            bufLen -= drop
            bufStart += drop
        }
        return if (n == out.size) out else out.copyOf(n)
    }
}
