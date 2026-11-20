package dev.inknotes.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.inknotes.data.tr
import dev.inknotes.math.Composer
import dev.inknotes.math.FormulaKeys

/**
 * The formula keyboard (shown instead of the wheels): the formula with its cursor, tabs of
 * templates and signs, search by name, a number pad, and a field for LaTeX. [onChange] gets
 * the formula text after every key.
 */
@Composable
fun Calculator(initial: String, onChange: (String) -> Unit) {
    val c = remember { Composer.of(initial) }
    var version by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    var upper by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var latex by remember { mutableStateOf(false) }
    fun changed() {
        version++
        onChange(c.text())
    }
    val all = remember(upper) { FormulaKeys.tabs(upper) }
    // Read after every key: the formula as it stands.
    @Suppress("UNUSED_VARIABLE") val v = version

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The formula, the cursor in it.
        val color = MaterialTheme.colorScheme.onSurface.toArgb()
        val letterPx = with(LocalDensity.current) { 28.dp.toPx() }
        val shown = c.shown()
        val bmp = remember(shown, color) { FormulaArt.bitmap(shown, letterPx, color) }
        Box(
            Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = c.text(), modifier = Modifier.heightIn(max = 120.dp))
            else Text(shown, fontSize = 24.sp, fontFamily = FontFamily.Serif)
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((k, pair) in all.withIndex()) {
                FilterChip(selected = query.isBlank() && tab == k, onClick = { tab = k; query = "" }, label = { Text(pair.first) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(tr("Найти символ: «интеграл», «не равно», «тета», «ом»…; □ — пустое место, «след. □» — к нему", "Find a symbol: “integral”, “not equal”, “theta”, “ohm”…; □ is an empty place, “next □” goes to it")) },
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { latex = !latex }) { Text(if (latex) tr("Скрыть LaTeX", "Hide LaTeX") else tr("Ввести LaTeX", "Type LaTeX")) }
        }
        if (latex) {
            OutlinedTextField(
                value = c.text(),
                onValueChange = { text ->
                    // The same formula, taken apart again from the text typed.
                    c.set(text)
                    changed()
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                label = { Text(tr("Для тех, кто знает LaTeX: всё, чего нет на кнопках", "For those who know LaTeX: anything not on the keys")) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val q = query.trim().lowercase()
            val keys = if (q.isEmpty()) all[tab].second else all.flatMap { it.second }.filter { it.face != "⇧" && (q in it.name.lowercase() || it.face.lowercase() == q) }.distinctBy { it.face + it.name }.take(24)
            val compact = keys.size > 18
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (q.isNotEmpty() && keys.isEmpty()) {
                    Text(tr("Ничего не нашлось — попробуйте другое слово или LaTeX", "Nothing found — try another word or LaTeX"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                for (row in keys.chunked(if (compact) 9 else 6)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (k in row) {
                            KeyButton(k.face, if (compact) null else k.name, Modifier.weight(1f), highlighted = k.face == "⇧" && upper, art = k.art) {
                                if (k.face == "⇧") upper = !upper else {
                                    k.press(c)
                                    changed()
                                }
                            }
                        }
                        repeat((if (compact) 9 else 6) - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            NumberPad(Modifier.width(280.dp)) { key ->
                when (key) {
                    "⌫" -> c.erase()
                    "C" -> c.clear()
                    "←" -> c.left()
                    "→" -> c.right()
                    "□→" -> c.nextEmpty()
                    else -> c.type(key)
                }
                changed()
            }
        }
    }
}

@Composable
private fun KeyButton(face: String, name: String?, modifier: Modifier, highlighted: Boolean = false, art: String? = null, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurface
    Column(
        modifier.height(if (name == null) 46.dp else 74.dp)
            .background(if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // A fraction or derivative stacked as in print, its bar across.
        val px = with(LocalDensity.current) { 15.dp.toPx() }
        val argb = color.toArgb()
        val bmp = remember(art, argb) { art?.let { FormulaArt.bitmap(it, px, argb) } }
        if (bmp != null) {
            Image(bmp.asImageBitmap(), contentDescription = name ?: face, modifier = Modifier.height(if (name == null) 34.dp else 38.dp))
        } else {
            Text(face, fontFamily = FontFamily.Serif, fontSize = if (face.length > 5) 16.sp else 21.sp, color = color, maxLines = 1)
        }
        if (name != null) {
            Text(
                name,
                fontSize = 12.sp,
                lineHeight = 13.sp,
                color = color.copy(alpha = 0.78f),
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Digits, x and y, the four signs and "=", the cursor keys, erase and clear. */
@Composable
private fun NumberPad(modifier: Modifier, onKey: (String) -> Unit) {
    val comma = tr(",", ".")
    val rows = listOf(
        listOf("x", "y", "(", ")"),
        listOf("7", "8", "9", "÷"),
        listOf("4", "5", "6", "×"),
        listOf("1", "2", "3", "−"),
        listOf("0", comma, "=", "+"),
        listOf("←", "→", "□→", "⌫"),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in row) {
                    val sign = k in setOf("÷", "×", "−", "+", "=")
                    val control = k in setOf("←", "→", "□→", "⌫")
                    Box(
                        Modifier.weight(1f).height(44.dp)
                            .background(
                                when {
                                    sign -> MaterialTheme.colorScheme.secondaryContainer
                                    control -> MaterialTheme.colorScheme.surface
                                    else -> MaterialTheme.colorScheme.surfaceContainer
                                },
                                RoundedCornerShape(12.dp),
                            )
                            .border(if (control) 1.dp else 0.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .clickable { onKey(k) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (k == "□→") tr("след. □", "next □") else k, fontSize = if (k == "□→") 13.sp else 20.sp, fontFamily = if (control) FontFamily.Default else FontFamily.Serif)
                    }
                }
            }
        }
        TextButton(onClick = { onKey("C") }, modifier = Modifier.fillMaxWidth()) { Text(tr("Очистить всё", "Clear all")) }
    }
}
