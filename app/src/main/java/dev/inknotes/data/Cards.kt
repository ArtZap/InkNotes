package dev.inknotes.data

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Flash cards from the hint marker: every band of it in a notebook is a card (the line around
 * it is the question, what it hides the answer), reviewed with spaced repetition.
 *
 * A card is known by its page and the place of its band, so moving or redrawing a band makes a
 * new card, while the rest of the page may change freely.
 */
object Cards {

    enum class Grade(val title: String) {
        AGAIN(tr("Не помню", "Forgot")),
        HARD(tr("Трудно", "Hard")),
        GOOD(tr("Помню", "Good")),
        EASY(tr("Легко", "Easy")),
    }

    /**
     * Review state: [box] answers remembered in a row, due on [due] (days since 1970); FSRS's
     * memory of the card: [stability] (days until it is remembered with [RETENTION]),
     * [difficulty] (1…10), [last] review. A state saved before 0.67 has no stability (0).
     */
    data class State(val box: Int, val due: Long, val stability: Double = 0.0, val difficulty: Double = 0.0, val last: Long = due)

    fun key(pageId: String, left: Float, top: Float, right: Float, bottom: Float): String =
        "$pageId:${(left / 4).roundToInt()}:${(top / 4).roundToInt()}:${(right / 4).roundToInt()}:${(bottom / 4).roundToInt()}"

    /**
     * What of [states] goes to the file: cards of bands still on the pages ([current]), and every
     * card of a page that could not be read this time ([unreadPages]): its band may well be there.
     */
    fun kept(states: Map<String, State>, current: Set<String>, unreadPages: Set<String>): Map<String, State> =
        states.filterKeys { it in current || it.substringBefore(':') in unreadPages }

    const val MAX_BOX = 100

    /** A card never reviewed is due today. */
    fun isDue(state: State?, today: Long) = state == null || state.due <= today

    /**
     * The state after answering on [today], by FSRS-6 with its default parameters (the
     * open-spaced-repetition project; fit on the reviews of about 10 000 Anki users): the next
     * review when the card would be remembered nine times in ten. AGAIN keeps the card in
     * today's session.
     */
    fun grade(state: State?, grade: Grade, today: Long): State {
        val g = grade.ordinal + 1
        val old = state?.let(::memory)
        val (s, d) = if (old == null) {
            W[g - 1] to initialDifficulty(g)
        } else {
            val (s0, d0, last) = old
            val elapsed = (today - last).coerceAtLeast(0)
            val r = retrievability(elapsed.toDouble(), s0)
            val s1 = when {
                // Again the same day: short-term stability.
                elapsed == 0L -> (s0 * exp(W[17] * (g - 3 + W[18])) * s0.pow(-W[19])).let { if (g >= 3) max(it, s0) else it }
                g == 1 -> min(s0, W[11] * d0.pow(-W[12]) * ((s0 + 1).pow(W[13]) - 1) * exp(W[14] * (1 - r)))
                else -> s0 * (1 + exp(W[8]) * (11 - d0) * s0.pow(-W[9]) * (exp(W[10] * (1 - r)) - 1) * (if (g == 2) W[15] else 1.0) * (if (g == 4) W[16] else 1.0))
            }
            s1 to nextDifficulty(d0, g)
        }
        val stability = s.coerceIn(MIN_STABILITY, MAX_DAYS.toDouble())
        val days = if (g == 1) 0L else interval(stability)
        // Capped: a longer run is read back as damaged ([decode]) and the card would start over.
        return State(if (g == 1) 0 else ((state?.box ?: 0) + 1).coerceAtMost(MAX_BOX), today + days, stability, d, today)
    }

    /** Stability, difficulty and last review of [state]; one saved before FSRS is estimated from its box. */
    private fun memory(state: State): Triple<Double, Double, Long>? {
        if (state.stability > 0) return Triple(state.stability, state.difficulty.coerceIn(1.0, 10.0), state.last)
        if (state.box <= 0) return null
        val s = INTERVALS[(state.box - 1).coerceAtMost(INTERVALS.lastIndex)].toDouble()
        return Triple(s, 5.0, state.due - s.roundToLong())
    }

    private fun retrievability(days: Double, stability: Double) = (1 + FACTOR * days / stability).pow(-W[20])

    /** Days until the card is remembered with [RETENTION]: about its stability. */
    private fun interval(stability: Double): Long =
        (stability / FACTOR * (RETENTION.pow(-1 / W[20]) - 1)).roundToLong().coerceIn(1L, MAX_DAYS)

    private fun initialDifficulty(g: Int) = (W[4] - exp(W[5] * (g - 1)) + 1).coerceIn(1.0, 10.0)

    private fun nextDifficulty(d: Double, g: Int): Double {
        val damped = d - W[6] * (g - 3) * (10 - d) / 9
        return (W[7] * initialDifficulty(4) + (1 - W[7]) * damped).coerceIn(1.0, 10.0)
    }

    /** FSRS-6 defaults, w0…w20. */
    private val W = doubleArrayOf(
        0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796,
        1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
    )
    private const val RETENTION = 0.9
    private val FACTOR = 0.9.pow(-1 / W[20]) - 1
    private const val MIN_STABILITY = 0.01
    private const val MAX_DAYS = 36_500L

    /** The intervals before FSRS (0.67), by answers remembered in a row: to estimate old states. */
    private val INTERVALS = intArrayOf(1, 3, 7, 14, 30, 60, 120, 240)

    /** "через 3 дн." for the button of a grade. */
    fun interval(state: State?, grade: Grade, today: Long): String {
        val days = grade(state, grade, today).due - today
        return when {
            days <= 0 -> tr("сегодня", "today")
            days < 30 -> tr("через $days дн.", "in $days d")
            else -> tr("через ${(days + 15) / 30} мес.", "in ${(days + 15) / 30} mo")
        }
    }

    fun encode(states: Map<String, State>): String =
        states.entries.sortedBy { it.key }.joinToString("\n") { (k, s) ->
            if (s.stability > 0) {
                "$k\t${s.box}\t${s.due}\t${"%.4f".format(java.util.Locale.ROOT, s.stability)}\t${"%.4f".format(java.util.Locale.ROOT, s.difficulty)}\t${s.last}"
            } else {
                "$k\t${s.box}\t${s.due}"
            }
        }

    /** Lines of [encode]; those saved before 0.67 have the first three fields only. */
    fun decode(text: String): Map<String, State> {
        val out = HashMap<String, State>()
        for (line in text.lineSequence()) {
            val p = line.split('\t')
            if (p.size != 3 && p.size != 6) continue
            val box = p[1].toIntOrNull() ?: continue
            val due = p[2].toLongOrNull() ?: continue
            if (p[0].length > 200 || box !in 0..MAX_BOX) continue
            out[p[0]] = if (p.size == 6) {
                val s = p[3].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: continue
                val d = p[4].toDoubleOrNull()?.takeIf { it.isFinite() } ?: continue
                val last = p[5].toLongOrNull() ?: continue
                State(box, due, s, d.coerceIn(1.0, 10.0), last)
            } else {
                State(box, due)
            }
        }
        return out
    }

    fun today(now: Long = System.currentTimeMillis(), offsetMs: Int = java.util.TimeZone.getDefault().getOffset(now)): Long =
        Math.floorDiv(now + offsetMs, 86_400_000L)
}
