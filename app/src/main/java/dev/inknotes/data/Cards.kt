package dev.inknotes.data

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Flash cards from the hint marker: every band of it in a notebook is a card (the line around
 * it is the question, what it hides the answer), reviewed with spaced repetition.
 *
 * A card is known by its page and the place of its band, so moving or redrawing a band makes a
 * new card, while the rest of the page may change freely.
 */
object Cards {

    /** Days until a card is due again, by how many times in a row it was remembered. */
    val INTERVALS = intArrayOf(1, 3, 7, 14, 30, 60, 120, 240)

    enum class Grade(val title: String) {
        AGAIN(tr("Не помню", "Forgot")),
        HARD(tr("Трудно", "Hard")),
        GOOD(tr("Помню", "Good")),
        EASY(tr("Легко", "Easy")),
    }

    /** Review state: [box] answers remembered in a row, due on [due] (days since 1970). */
    data class State(val box: Int, val due: Long)

    fun key(pageId: String, left: Float, top: Float, right: Float, bottom: Float): String =
        "$pageId:${(left / 4).roundToInt()}:${(top / 4).roundToInt()}:${(right / 4).roundToInt()}:${(bottom / 4).roundToInt()}"

    /** A card never reviewed is due today. */
    fun isDue(state: State?, today: Long) = state == null || state.due <= today

    /** The state after answering on [today]; AGAIN keeps the card in today's session. */
    fun grade(state: State?, grade: Grade, today: Long): State {
        val box = state?.box ?: 0
        return when (grade) {
            Grade.AGAIN -> State(0, today)
            Grade.HARD -> State(max(0, box - 1), today + max(1, INTERVALS[max(0, box - 1).coerceAtMost(INTERVALS.lastIndex)] / 2))
            Grade.GOOD -> State(box + 1, today + INTERVALS[box.coerceAtMost(INTERVALS.lastIndex)])
            Grade.EASY -> State(box + 2, today + INTERVALS[(box + 1).coerceAtMost(INTERVALS.lastIndex)])
        }
    }

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
        states.entries.sortedBy { it.key }.joinToString("\n") { (k, s) -> "$k\t${s.box}\t${s.due}" }

    fun decode(text: String): Map<String, State> {
        val out = HashMap<String, State>()
        for (line in text.lineSequence()) {
            val p = line.split('\t')
            if (p.size != 3) continue
            val box = p[1].toIntOrNull() ?: continue
            val due = p[2].toLongOrNull() ?: continue
            if (p[0].length > 200 || box !in 0..100) continue
            out[p[0]] = State(box, due)
        }
        return out
    }

    fun today(now: Long = System.currentTimeMillis(), offsetMs: Int = java.util.TimeZone.getDefault().getOffset(now)): Long =
        Math.floorDiv(now + offsetMs, 86_400_000L)
}
