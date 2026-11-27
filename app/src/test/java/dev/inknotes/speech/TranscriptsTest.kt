package dev.inknotes.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class TranscriptsTest {
    @Test
    fun voskWordsAreRead() {
        val json = """{
  "result" : [{
      "conf" : 1.000000,
      "end" : 1.110000,
      "start" : 0.840000,
      "word" : "производная"
    }, {
      "conf" : 0.8,
      "end" : 1.53,
      "start" : 1.11,
      "word" : "\"x\""
    }],
  "text" : "производная x"
}"""
        val w = Transcripts.words(json, offsetMs = 60_000)
        assertEquals(listOf(Word("производная", 60_840, 61_110), Word("\"x\"", 61_110, 61_530)), w)
        assertTrue(Transcripts.words("""{"text" : ""}""").isEmpty())
    }

    @Test
    fun aPauseOrALongRunStartsANewLine() {
        val words = (0 until 40).map { k -> Word("w$k", k * 300L, k * 300L + 250) } +
            Word("после", 20_000, 20_300)
        val p = Transcripts.phrases(words)
        assertTrue(p.all { it.text.split(' ').size <= Transcripts.MAX_WORDS })
        assertEquals("После", p.last().text)
        assertEquals(20_000L, p.last().startMs)
        assertEquals("W0", p.first().text.substringBefore(' '))
    }

    @Test
    fun transcriptsGoThroughTheFile() {
        val t = Transcript("rec-a.m4a", "ru", 125_000, false, listOf(Phrase(0, 1200, "Первая\tфраза"), Phrase(1500, 3000, "вторая")))
        val back = Transcripts.decode(Transcripts.encode(listOf(t)))["rec-a.m4a"]!!
        assertEquals(125_000L, back.doneMs)
        assertEquals(false, back.complete)
        assertEquals(listOf(Phrase(0, 1200, "Первая фраза"), Phrase(1500, 3000, "вторая")), back.phrases)
        assertEquals("вторая", back.phraseAt(2000)?.text)
        assertEquals("вторая", back.phraseAt(5000)?.text)
        assertEquals(null, back.phraseAt(60_000))
        assertTrue(Transcripts.decode("garbage").isEmpty())
    }

    @Test
    fun soundGoesTo16kHzMono() {
        val rate = 44_100
        val tone = 440.0
        val n = rate // one second, stereo
        val pcm = ShortArray(2 * n) { i -> (10_000 * sin(2 * PI * tone * (i / 2) / rate)).toInt().toShort() }
        val whole = Downsampler(rate, 2).feed(pcm)
        assertTrue("${whole.size}", abs(whole.size - 16_000) <= 3)
        var crossings = 0
        for (k in 1 until whole.size) if ((whole[k - 1] < 0) != (whole[k] < 0)) crossings++
        assertTrue("$crossings", abs(crossings - 880) <= 6)
        assertTrue(whole.maxOrNull()!! > 9_000)
        // In odd pieces (a frame split between two of them) the same sound comes out.
        val d = Downsampler(rate, 2)
        val parts = ArrayList<Short>()
        var i = 0
        var len = 1
        while (i < pcm.size) {
            val m = minOf(len, pcm.size - i)
            parts += d.feed(pcm.copyOfRange(i, i + m)).toList()
            i += m
            len = len * 3 % 1999 + 1
        }
        assertEquals(whole.toList(), parts)
    }

    @Test
    fun slowerSoundIsSpreadOut() {
        val pcm = ShortArray(8_000) { (it % 100).toShort() }
        val out = Downsampler(8_000, 1).feed(pcm)
        assertTrue("${out.size}", abs(out.size - 16_000) <= 3)
    }
}
