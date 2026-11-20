package dev.inknotes.ui

import dev.inknotes.data.tr
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.inknotes.data.PageSize
import dev.inknotes.data.PaperFormat
import java.math.BigDecimal
import java.math.RoundingMode

/** Units for sizes on paper; [mm] millimetres in one unit. */
enum class LengthUnit(val title: String, val mm: Float, val decimals: Int) {
    MM(tr("мм", "mm"), 1f, 1),
    CM(tr("см", "cm"), 10f, 2),
    INCH(tr("дюймы", "inches"), 25.4f, 2),
    PT(tr("пт", "pt"), 25.4f / 72f, 1),
}

private fun format(v: Float, decimals: Int): String {
    if (!v.isFinite()) return ""
    return BigDecimal(v.toDouble()).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().let { if (dev.inknotes.data.Lang.english) it else it.replace('.', ',') }
}

/** A positive size as typed (comma or point); null for anything else or absurdly large. */
private fun parse(s: String): Float? = s.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it > 0f && it < MAX_TYPED }

private const val MAX_TYPED = 100_000f

/**
 * Width and height of the selection in millimetres (or another unit), with a lock that keeps
 * the proportions: changing one side then changes the other.
 */
@Composable
fun SelectionSizeDialog(c: EditorController, onDismiss: () -> Unit) {
    val start = remember { c.selectionSizeMm() } ?: run {
        // Nothing selected any more: close, but not in the middle of composing.
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val ratio = if (start.first > 0f && start.second > 0f) start.second / start.first else 0f
    var unit by remember { mutableStateOf(c.sizeUnit) }
    var w by remember { mutableStateOf(format(start.first / unit.mm, unit.decimals)) }
    var h by remember { mutableStateOf(format(start.second / unit.mm, unit.decimals)) }
    val keep = c.keepProportions && ratio > 0f
    val wv = parse(w)
    val hv = parse(h)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Размер выделенного", "Size of the selection")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = w,
                        onValueChange = { s ->
                            w = s
                            if (keep) parse(s)?.let { h = format(it * ratio, unit.decimals) }
                        },
                        label = { Text(tr("Ширина", "Width")) },
                        suffix = { Text(unit.title) },
                        isError = wv == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f).widthIn(max = 150.dp),
                    )
                    IconAction(
                        if (c.keepProportions) InkIcons.Link else InkIcons.LinkOff,
                        if (c.keepProportions) tr("Пропорции сохраняются", "Proportions kept") else tr("Пропорции не сохраняются", "Proportions not kept"),
                        {
                            c.keepProportions = !c.keepProportions
                            // Locking takes the width as it is and fits the height to it.
                            if (c.keepProportions && ratio > 0f) parse(w)?.let { h = format(it * ratio, unit.decimals) }
                        },
                        selected = c.keepProportions,
                    )
                    OutlinedTextField(
                        value = h,
                        onValueChange = { s ->
                            h = s
                            if (keep) parse(s)?.let { w = format(it / ratio, unit.decimals) }
                        },
                        label = { Text(tr("Высота", "Height")) },
                        suffix = { Text(unit.title) },
                        isError = hv == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f).widthIn(max = 150.dp),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (u in LengthUnit.entries) {
                        FilterChip(
                            selected = u == unit,
                            onClick = {
                                parse(w)?.let { w = format(it * unit.mm / u.mm, u.decimals) }
                                parse(h)?.let { h = format(it * unit.mm / u.mm, u.decimals) }
                                unit = u
                                c.sizeUnit = u
                            },
                            label = { Text(u.title) },
                        )
                    }
                }
                Text(
                    if (c.keepProportions) tr("Пропорции сохраняются: измените одну сторону, вторая подстроится.", "Proportions kept: change one side, the other follows.") else tr("Стороны меняются независимо.", "The sides change independently."),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = wv != null && hv != null,
                onClick = {
                    if (wv != null && hv != null) c.resizeSelectionMm(wv * unit.mm, hv * unit.mm)
                    onDismiss()
                },
            ) { Text(tr("Применить", "Apply")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена", "Cancel")) } },
    )
}

/**
 * Paper format chips, portrait or landscape, and the size in millimetres they give; or the
 * canvas ([canvas] true; [canvasSize] its size now, if it is one already).
 */
@Composable
fun PaperSizeChoices(
    format: PaperFormat,
    landscape: Boolean,
    onFormat: (PaperFormat) -> Unit,
    onLandscape: (Boolean) -> Unit,
    canvas: Boolean = false,
    onCanvas: ((Boolean) -> Unit)? = null,
    canvasSize: PageSize? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Формат страницы", "Page format"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (f in PaperFormat.entries) {
                FilterChip(
                    selected = !canvas && f == format,
                    onClick = {
                        onCanvas?.invoke(false)
                        onFormat(f)
                    },
                    label = { Text(f.title) },
                )
            }
            if (onCanvas != null) {
                FilterChip(selected = canvas, onClick = { onCanvas(true) }, label = { Text(tr("Холст ∞", "Canvas ∞")) })
            }
        }
        if (canvas) {
            val size = canvasSize?.takeIf { it.isCanvas } ?: PageSize.CANVAS
            Text(
                tr("Страница без формата: растёт вправо и вниз, когда пишете у края (до 2 м). ", "A page without a format: it grows right and down as you write near the edge (up to 2 m). ") +
                    tr("Для схем, карт понятий и задач, которым тесно на листе. Отдалите пальцами, чтобы увидеть всё.", "For diagrams, concept maps and problems that don't fit a sheet. Pinch out to see everything."),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(tr("Сейчас ${format(size.widthMm, 0)} × ${format(size.heightMm, 0)} мм", "Now ${format(size.widthMm, 0)} × ${format(size.heightMm, 0)} mm"), fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            return@Column
        }
        Text(tr("Ориентация", "Orientation"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !landscape, onClick = { onLandscape(false) }, label = { Text(tr("Книжная", "Portrait")) })
            FilterChip(selected = landscape, onClick = { onLandscape(true) }, label = { Text(tr("Альбомная", "Landscape")) })
        }
        val size = PageSize(format, landscape)
        Text(tr("${format(size.widthMm, 1)} × ${format(size.heightMm, 1)} мм", "${format(size.widthMm, 1)} × ${format(size.heightMm, 1)} mm"), fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    }
}

/** Paper format and orientation of page [index], or of all pages. */
@Composable
fun PageSizeDialog(c: EditorController, index: Int, onDismiss: () -> Unit) {
    val current = remember { c.pageSize(index) }
    var format by remember { mutableStateOf(current.format) }
    var landscape by remember { mutableStateOf(current.landscape) }
    var canvas by remember { mutableStateOf(current.isCanvas) }
    var all by remember { mutableStateOf(false) }
    val pdf = c.isPdfPage(index)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Размер страницы ${index + 1}", "Size of page ${index + 1}")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (pdf) {
                    Text(
                        tr("Это страница PDF: её размер задан документом. Можно применить размер к остальным страницам.", "This is a PDF page: its size comes from the document. You can apply the size to the other pages."),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PaperSizeChoices(format, landscape, onFormat = { format = it }, onLandscape = { landscape = it }, canvas = canvas, onCanvas = { canvas = it }, canvasSize = current)
                if (canvas && current.isCanvas) {
                    TextButton(onClick = {
                        c.fitCanvas(index)
                        onDismiss()
                    }) { Text(tr("Обрезать по написанному", "Crop to the writing")) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = all || pdf, enabled = !pdf, onCheckedChange = { all = it })
                    Text(tr("Ко всем страницам тетради", "To all pages of the notebook"))
                }
                Text(
                    tr("Записи сохраняют свой размер в миллиметрах и место от левого верхнего угла; то, что окажется за краем меньшей страницы, не пропадёт и вернётся при большем размере.", "Writing keeps its size in millimetres and its place from the top left corner; whatever ends up beyond the edge of a smaller page is not lost and comes back at a larger size."),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val size = when {
                    !canvas -> PageSize(format, landscape)
                    current.isCanvas -> current
                    else -> PageSize.CANVAS
                }
                c.setPageSize(index, size, all = all || pdf)
                onDismiss()
            }) { Text(tr("Применить", "Apply")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена", "Cancel")) } },
    )
}
