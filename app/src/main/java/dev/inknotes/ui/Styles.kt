package dev.inknotes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CoverShape = RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp, topEnd = 6.dp, bottomEnd = 6.dp)

/**
 * Premium notebook cover: the cover colour as deep leather with a sheen, a darker spine with a
 * gold stitch, a double frame of gold tooling and the title stamped in gold foil.
 */
@Composable
fun LeatherCover(color: Int, title: String, modifier: Modifier = Modifier, compact: Boolean = false, glass: Glass? = null, overlay: @Composable () -> Unit = {}) {
    // Bright colours are tanned darker and a little greyer, so the gold always reads on them.
    val bright = (Color(color).luminance() - 0.3f).coerceAtLeast(0f)
    val base = lerp(Color(color), Color(0xFF3A2E22), bright * 0.5f)
    val alpha = if (glass != null) GLASS_ALPHA else 1f
    val rich = lerp(base, Color.Black, 0.18f + bright * 0.5f).copy(alpha = alpha)
    val deep = lerp(base, Color.Black, 0.48f + bright * 0.3f).copy(alpha = alpha)
    Box(
        modifier
            .shadow(if (compact) 3.dp else 10.dp, CoverShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CoverShape),
    ) {
    if (glass != null) GlassPage(glass)
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(rich, deep), start = Offset.Zero, end = Offset.Infinite))
            .drawWithContent {
                drawLeather(deep, if (compact) 0.6f else 1f)
                drawContent()
            },
        contentAlignment = Alignment.Center,
    ) {
        if (!compact) {
            Column(
                Modifier.padding(start = 34.dp, end = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Ornament()
                Spacer(Modifier.height(10.dp))
                Text(
                    title,
                    style = TextStyle(brush = Gold.Foil, shadow = glassShadow(glass, light = false)),
                    fontFamily = FontFamily.Serif,
                    fontSize = 18.sp,
                    letterSpacing = 0.8.sp,
                    lineHeight = 23.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Ornament()
            }
        }
        overlay()
    }
    }
}

/** Spine, stitch, frames and a soft light from the top left, scaled by [k] for small previews. */
private fun DrawScope.drawLeather(deep: Color, k: Float) {
    val spine = size.width * 0.12f
    // Light falling on the leather.
    drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.13f), Color.Transparent), center = Offset(size.width * 0.3f, 0f), radius = size.maxDimension * 0.9f))
    // Spine: darker, with an edge catching the light and a gold stitch.
    drawRect(lerp(deep, Color.Black, 0.35f).copy(alpha = 0.85f), size = Size(spine, size.height))
    drawLine(Color.White.copy(alpha = 0.10f), Offset(spine, 0f), Offset(spine, size.height), strokeWidth = 1.2f * k * density)
    val stitch = spine + 5f * k * density
    drawLine(
        Gold.Champagne.copy(alpha = 0.75f),
        Offset(stitch, 6f * k * density),
        Offset(stitch, size.height - 6f * k * density),
        strokeWidth = 1.1f * k * density,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * k * density, 3.5f * k * density)),
    )
    // Double gold frame tooled into the cover.
    val left = spine + 13f * k * density
    val m = 11f * k * density
    drawRect(Gold.Champagne.copy(alpha = 0.8f), Offset(left, m), Size(size.width - left - m, size.height - 2 * m), style = Stroke(0.9f * k * density))
    val g = 4f * k * density
    drawRect(Gold.Champagne.copy(alpha = 0.4f), Offset(left + g, m + g), Size(size.width - left - m - 2 * g, size.height - 2 * m - 2 * g), style = Stroke(0.6f * k * density))
    // Page edges peeking out on the right.
    drawLine(Color(0xFFEDE3CC).copy(alpha = 0.35f), Offset(size.width - 1.5f * density, 4f * density), Offset(size.width - 1.5f * density, size.height - 4f * density), strokeWidth = 1f * density)
}

/** A gold rule with a lozenge in the middle. */
@Composable
private fun Ornament() {
    Canvas(Modifier.size(width = 64.dp, height = 8.dp)) {
        val y = size.height / 2
        val c = size.width / 2
        val gold = Gold.Champagne.copy(alpha = 0.85f)
        drawLine(gold, Offset(0f, y), Offset(c - 7.dp.toPx(), y), strokeWidth = 0.8.dp.toPx())
        drawLine(gold, Offset(c + 7.dp.toPx(), y), Offset(size.width, y), strokeWidth = 0.8.dp.toPx())
        val d = 3.dp.toPx()
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(c, y - d)
            lineTo(c + d, y)
            lineTo(c, y + d)
            lineTo(c - d, y)
            close()
        }
        drawPath(p, gold)
    }
}

/** Premium folder: a slim portfolio with gold hairlines and the count set in gold serif. */
@Composable
fun PortfolioFolder(count: Int, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.3f
    val gold = Gold.line(dark)
    Box(
        modifier
            .shadow(4.dp, RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
            .border(0.8.dp, gold, RoundedCornerShape(4.dp))
            .padding(7.dp)
            .border(0.5.dp, gold.copy(alpha = 0.35f), RoundedCornerShape(2.dp)),
        contentAlignment = Alignment.Center,
    ) {
        // The flap of the portfolio.
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height * 0.22f
            drawLine(gold, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.6.dp.toPx())
            drawCircle(gold, 3.dp.toPx(), Offset(size.width / 2, y))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$count", style = TextStyle(brush = Gold.Foil), fontFamily = FontFamily.Serif, fontSize = 44.sp)
            Text(
                "ТЕТРАДЕЙ",
                fontSize = 10.sp,
                letterSpacing = 3.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Color.luminance() = 0.299f * red + 0.587f * green + 0.114f * blue

/** "I N K N O T E S" over the library title in the premium style. */
@Composable
fun Monogram() {
    val style = LocalUiStyle.current
    val minimal = style == UiStyle.MINIMAL
    Text(
        when (style) {
            UiStyle.MINIMAL -> "INKNOTES"
            UiStyle.NOTEBOOK -> "✿ INKNOTES · МОИ ТЕТРАДКИ"
            UiStyle.MYSTIC -> "✦ INKNOTES · КНИЖНАЯ ЛАВКА ТАЙН ✦"
            else -> "INKNOTES · ЛИЧНАЯ БИБЛИОТЕКА"
        },
        fontSize = 10.sp,
        letterSpacing = if (minimal) 5.sp else 3.5.sp,
        fontWeight = if (minimal) FontWeight.Normal else FontWeight.Medium,
        color = if (minimal) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
    )
}

/**
 * Minimal notebook cover: the colour as a quiet matte block, square, a catalogue number at the
 * top and the title in a light face under a short rule, like a gallery label.
 */
@Composable
fun MatteCover(color: Int, title: String, number: Int, modifier: Modifier = Modifier, compact: Boolean = false, glass: Glass? = null, overlay: @Composable () -> Unit = {}) {
    val c = Color(color)
    // Muted towards stone grey, so any colour looks considered next to the others.
    val matte = lerp(c, Color(0xFF8E8A82), 0.36f)
    val light = glassSeen(matte, glass).luminance() > 0.62f
    val ink = if (light) Color(0xFF111111) else Color.White
    Box(modifier.clip(androidx.compose.ui.graphics.RectangleShape)) {
        if (glass != null) GlassPage(glass)
        Box(Modifier.fillMaxSize().background(if (glass != null) matte.copy(alpha = GLASS_ALPHA) else matte)) {
        if (!compact) {
            Text(
                "%02d".format(number),
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                color = ink.copy(alpha = 0.7f),
                fontSize = 12.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Light,
            )
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Box(Modifier.width(22.dp).height(0.8.dp).background(ink.copy(alpha = 0.8f)))
                Spacer(Modifier.height(10.dp))
                Text(
                    title,
                    color = ink,
                    style = TextStyle(shadow = glassShadow(glass, light = light)),
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 0.3.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        overlay()
        }
    }
}

/** Minimal folder: an outlined square with the count in a thin, large figure. */
@Composable
fun LineFolder(count: Int, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
    Box(modifier.border(0.8.dp, line), contentAlignment = Alignment.Center) {
        // The fold of a paper folder.
        Box(Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 16.dp).width(36.dp).height(0.8.dp).background(line))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$count", fontSize = 56.sp, fontWeight = FontWeight.ExtraLight, color = MaterialTheme.colorScheme.onSurface)
            Text("ТЕТРАДЕЙ", fontSize = 10.sp, letterSpacing = 3.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Library settings: the interface style (with previews) and the theme. */
@Composable
fun SettingsDialog(
    style: UiStyle,
    themeMode: ThemeMode,
    dark: Boolean,
    onStyle: (UiStyle) -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
    glass: Boolean = false,
    onGlass: (Boolean) -> Unit = {},
) {
    // Wider than an alert dialog: the styles side by side.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(24.dp).widthIn(max = 860.dp),
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Настройки", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text("Стиль интерфейса", style = MaterialTheme.typography.titleMedium)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    for (s in UiStyle.entries) {
                        StyleChoice(s, dark, selected = s == style, onClick = { onStyle(s) })
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { onGlass(!glass) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Полупрозрачные обложки", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Обложка остаётся своего цвета, но сквозь неё видно первую страницу тетради",
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = glass, onCheckedChange = onGlass)
                }
                Spacer(Modifier.height(4.dp))
                Text("Тема", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in ThemeMode.entries) {
                        FilterChip(selected = m == themeMode, onClick = { onTheme(m) }, label = { Text(m.title) })
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onDismiss) { Text("Готово") }
                }
            }
        }
    }
}

@Composable
private fun StyleChoice(style: UiStyle, dark: Boolean, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(184.dp).clickable(onClick = onClick)) {
        StylePreview(
            style,
            dark,
            Modifier
                .fillMaxWidth()
                .height(130.dp)
                .border(
                    if (selected) 2.5.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(10.dp),
                )
                .padding(if (selected) 2.5.dp else 1.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(style.title, fontFamily = displayFont(style), fontSize = 18.sp, fontWeight = if (style == UiStyle.CLASSIC) FontWeight.SemiBold else titleWeight(style))
            if (selected) Text("  ✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
        Text(style.note, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A tiny library in [style]: its background, title face and three covers. */
@Composable
private fun StylePreview(style: UiStyle, dark: Boolean, modifier: Modifier) {
    val colors = colorsFor(style, dark)
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .then(if (hasBackdrop(style)) Modifier.backdrop(style, dark) else Modifier.background(colors.background))
            .padding(12.dp),
    ) {
        if (style != UiStyle.CLASSIC && style != UiStyle.GLASS) {
            Text(if (style == UiStyle.MYSTIC) "✦ INKNOTES ✦" else "INKNOTES", fontSize = 6.sp, letterSpacing = 2.sp, color = if (style.gilded) colors.primary else colors.onSurfaceVariant)
        }
        Text(
            "Тетради",
            color = colors.onBackground,
            fontFamily = displayFont(style),
            fontWeight = titleWeight(style),
            fontSize = 15.sp,
            letterSpacing = if (style.serif) 0.8.sp else 0.sp,
        )
        if (style == UiStyle.PREMIUM) {
            Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(0.6.dp).background(Gold.line(dark)))
        } else if (style == UiStyle.MYSTIC) {
            Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(0.6.dp).background(colors.primary.copy(alpha = 0.6f)))
        } else if (style == UiStyle.MINIMAL) {
            Box(Modifier.padding(vertical = 5.dp).fillMaxWidth().height(0.5.dp).background(colors.outline))
        } else {
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((i, c) in listOf(0xFF3E6D8E, 0xFF8B5A3C, 0xFF4F7F55).map { it.toInt() }.withIndex()) {
                val m = Modifier.size(width = 42.dp, height = 60.dp)
                if (style == UiStyle.GLASS) {
                    GlassCover(c, "", dark, m, compact = true)
                } else if (style == UiStyle.NOTEBOOK) {
                    PlasticCover(c, "", 0, PlasticKind.entries[i], m, compact = true)
                } else if (style == UiStyle.MYSTIC) {
                    MysticCover(c, "", listOf(CoverArt.LEAVES, CoverArt.ARCH, CoverArt.MOON)[i], m, compact = true)
                } else if (style == UiStyle.PREMIUM) {
                    LeatherCover(c, "", m, compact = true)
                } else if (style == UiStyle.MINIMAL) {
                    MatteCover(c, "", 0, m, compact = true)
                } else {
                    Box(m.background(Color(c), RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp, topEnd = 6.dp, bottomEnd = 6.dp)))
                }
            }
        }
    }
}
