package dev.inknotes.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's icon set: thin outline icons on a 24-unit grid, one consistent style, including
 * the ones Material lacks (fountain pen, eraser, lasso, hatched area). Paths are SVG syntax.
 */
object InkIcons {
    /**
     * Outline of an icon: [stroke] lines, solid [fill] parts and [accent] lines (a tool's tip,
     * a spark). Each interface style draws it its own way (see [styled]).
     */
    internal class Spec(val stroke: String, val fill: String?, val accent: String?, val tool: Boolean)

    private val specs = HashMap<String, Spec>()
    private val styledCache = HashMap<String, ImageVector>()

    private fun icon(name: String, stroke: String, fill: String? = null, accent: String? = null): ImageVector =
        Spec(stroke, fill, accent, tool = false).also { specs[name] = it }.let { build(name, it, UiStyle.CLASSIC, Color.Black, Color.Black) }

    /**
     * Drawing tools share one silhouette: the same rounded body drawn upright, differing only
     * in the tip, then turned 45° so the tip points to the lower left.
     */
    private fun tool(name: String, stroke: String, fill: String? = null, accent: String? = null): ImageVector =
        Spec(stroke, fill, accent, tool = true).also { specs[name] = it }.let { build(name, it, UiStyle.CLASSIC, Color.Black, Color.Black) }

    /**
     * Classic: 1.8 round lines. Premium: finer 1.4 lines, tips, sparks and solid parts in gold.
     * Minimal: hairline 1.1 lines with square ends and sharp corners, everything in one colour.
     */
    private fun build(name: String, spec: Spec, style: UiStyle, base: Color, accent: Color): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            if (spec.tool) addGroup(rotate = 45f, pivotX = 12f, pivotY = 12f)
            val width = when (style) {
                UiStyle.CLASSIC -> 1.8f
                UiStyle.PREMIUM -> 1.4f
                UiStyle.MYSTIC -> 1.15f
                UiStyle.MINIMAL -> 1.1f
                UiStyle.GLASS -> 1.6f
                UiStyle.NOTEBOOK -> 2f
            }
            val cap = if (style == UiStyle.MINIMAL) StrokeCap.Square else StrokeCap.Round
            val join = if (style == UiStyle.MINIMAL) StrokeJoin.Miter else StrokeJoin.Round
            val gold = if (style.gilded || style == UiStyle.NOTEBOOK) accent else base
            // Glass draws its solid parts as a translucent second layer (like SF Symbols'
            // hierarchical rendering); notebooks as a marker swatch in the accent colour.
            val fillBrush = when (style) {
                UiStyle.GLASS -> SolidColor(base.copy(alpha = 0.3f))
                UiStyle.NOTEBOOK -> SolidColor(accent.copy(alpha = 0.35f))
                else -> SolidColor(gold)
            }
            if (spec.fill != null) addPath(addPathNodes(spec.fill), fill = fillBrush)
            if (spec.stroke.isNotEmpty()) {
                addPath(addPathNodes(spec.stroke), stroke = SolidColor(base), strokeLineWidth = width, strokeLineCap = cap, strokeLineJoin = join)
            }
            if (spec.accent != null) {
                addPath(addPathNodes(spec.accent), stroke = SolidColor(gold), strokeLineWidth = width, strokeLineCap = cap, strokeLineJoin = join)
            }
            if (spec.tool) clearGroup()
        }.build()

    /**
     * [icon] as drawn in [style]: its own drawing from that style's set ([IconSets]) where there
     * is one, else the shared outline in the style's line. Premium icons carry their colours
     * ([base] and gold [accent]).
     */
    fun styled(icon: ImageVector, style: UiStyle, base: Color, accent: Color): ImageVector {
        if (style == UiStyle.CLASSIC) return icon
        val own = when (style) {
            UiStyle.PREMIUM -> IconSets.premium
            UiStyle.MYSTIC -> IconSets.mystic
            UiStyle.GLASS -> IconSets.glass
            UiStyle.NOTEBOOK -> IconSets.notebook
            UiStyle.MINIMAL -> IconSets.minimal
            UiStyle.CLASSIC -> emptyMap()
        }
        val spec = own[icon.name] ?: specs[icon.name] ?: return icon
        val key = "${icon.name}|$style|${base.value}|${accent.value}"
        return styledCache.getOrPut(key) { build(icon.name, spec, style, base, accent) }
    }

    /** Body of every tool: from the rounded cap at the top down to y = 12.5. */
    private const val BODY = "M9,12.5V3.5a2,2 0,0 1,2 -2h2a2,2 0,0 1,2 2V12.5"

    val Picture by lazy {
        icon(
            "picture",
            "M5,4.5h14a1.5,1.5 0,0 1,1.5 1.5v12a1.5,1.5 0,0 1,-1.5 1.5H5a1.5,1.5 0,0 1,-1.5 -1.5V6a1.5,1.5 0,0 1,1.5 -1.5zM3.5,16.5l5,-5l4.5,4.5l2.5,-2.5l4.5,4.5",
            fill = "M15.5,7.4a1.6,1.6 0,1 0,0 3.2a1.6,1.6 0,1 0,0 -3.2z",
        )
    }
    val Table by lazy { icon("table", "M5,4.5h14a1.5,1.5 0,0 1,1.5 1.5v12a1.5,1.5 0,0 1,-1.5 1.5H5a1.5,1.5 0,0 1,-1.5 -1.5V6a1.5,1.5 0,0 1,1.5 -1.5zM3.5,9.5h17M3.5,14.5h17M9.5,4.5v15M15,4.5v15") }
    /** The table above a plus or a minus: a row under it, or a column beside it. */
    val TableAddRow by lazy { icon("table-add-row", "M4,3.5h16v10H4zM4,8.5h16M9.3,3.5v10M14.7,3.5v10", accent = "M12,16v6M9,19h6") }
    val TableAddColumn by lazy { icon("table-add-col", "M3.5,4h10v16h-10zM3.5,9.3h10M3.5,14.7h10M8.5,4v16", accent = "M19,9v6M16,12h6") }
    val TableRemoveRow by lazy { icon("table-remove-row", "M4,3.5h16v10H4zM4,8.5h16M9.3,3.5v10M14.7,3.5v10", accent = "M9,19h6") }
    val TableRemoveColumn by lazy { icon("table-remove-col", "M3.5,4h10v16h-10zM3.5,9.3h10M3.5,14.7h10M8.5,4v16", accent = "M16,12h6") }
    val Stamp by lazy { icon("stamp", "M9.5,4.8a2.5,2.5 0,0 1,5 0c0,2 -1,3 -1,5.2h-3c0,-2.2 -1,-3.2 -1,-5.2zM5,10h14a1,1 0,0 1,1 1v3.5H4V11a1,1 0,0 1,1 -1zM5.5,14.5v2h13v-2M6,20h12") }

    /** Equals sign with a spark: answers appear by themselves. */
    val Calc by lazy { icon("calc", "M3.5,10h9M3.5,15h9", accent = "M18,4.5v7M14.5,8h7M15.6,5.6l4.8,4.8M20.4,5.6l-4.8,4.8") }

    val Back by lazy { icon("back", "M19,12H5M11,6L5,12L11,18") }
    val Undo by lazy { icon("undo", "M9,14L4,9L9,4M4,9H14.5a5.5,5.5 0,0 1,0 11H11") }
    val Redo by lazy { icon("redo", "M15,14L20,9L15,4M20,9H9.5a5.5,5.5 0,0 0,0 11H13") }
    /** A ruler: size in millimetres. */
    /** Size of the selection: a frame with its width measured under it (not the ruler tool). */
    val Ruler by lazy { icon("ruler", "M5,3.5h14v10h-14z", accent = "M4,19h16M6.5,16.5L4,19l2.5,2.5M17.5,16.5L20,19l-2.5,2.5") }

    /** Chain links: keep proportions; [LinkOff] broken. */
    val Link by lazy { icon("link", "M10,14a4,4 0,0 0,5.6 0l3,-3a4,4 0,0 0,-5.6 -5.6l-1,1M14,10a4,4 0,0 0,-5.6 0l-3,3a4,4 0,0 0,5.6 5.6l1,-1") }
    val LinkOff by lazy { icon("link-off", "M15.6,14l3,-3a4,4 0,0 0,-5.6 -5.6l-1,1M8.4,10l-3,3a4,4 0,0 0,5.6 5.6l1,-1M4,4l16,16") }

    /** A sheet of paper with a corner size mark: page size and orientation. */
    val PageSize by lazy { icon("page-size", "M6,3h8l4,4v14h-12zM14,3v4h4", accent = "M9,17.5l6,-6M11.5,11.5H15V15") }

    val Pages by lazy { icon("pages", "M9,3h9a1,1 0,0 1,1 1v13a1,1 0,0 1,-1 1h-9a1,1 0,0 1,-1 -1v-13a1,1 0,0 1,1 -1zM5,7v13a1,1 0,0 0,1 1h10") }
    val Search by lazy { icon("search", "M11,5a6,6 0,1 0,0 12a6,6 0,1 0,0 -12zM20,20L15.3,15.3") }
    val Previous by lazy { icon("previous", "M15,5L8,12L15,19") }
    val Next by lazy { icon("next", "M9,5L16,12L9,19") }
    val AddPage by lazy { icon("add-page", "M14,3H7a1,1 0,0 0,-1 1v16a1,1 0,0 0,1 1h10a1,1 0,0 0,1 -1V7zM14,3v4h4", accent = "M12,10.5v6M9,13.5h6") }
    val More by lazy {
        icon(
            "more",
            // Dots as small rings in the line colour: a fill would be the faint second layer in some styles.
            "M12,4.3a0.7,0.7 0,1 0,0 1.4a0.7,0.7 0,1 0,0 -1.4zM12,11.3a0.7,0.7 0,1 0,0 1.4a0.7,0.7 0,1 0,0 -1.4zM12,18.3a0.7,0.7 0,1 0,0 1.4a0.7,0.7 0,1 0,0 -1.4z",
        )
    }
    /** Ballpoint: cone to a point. */
    val Pen by lazy { tool("pen", "$BODY H9z", accent = "M9,12.5L12,21.8L15,12.5") }

    /** Fountain pen: a nib with its slit and breather hole. */
    val Fountain by lazy {
        tool(
            "fountain",
            "$BODY H9z",
            accent = "M9,12.5C9,15.5 10.4,18 12,22.2C13.6,18 15,15.5 15,12.5M12,22.2V18.8",
            fill = "M12,14.7a1.15,1.15 0,1 0,0 2.3a1.15,1.15 0,1 0,0 -2.3z",
        )
    }

    /** Settings: a cog wheel. */
    val Settings by lazy {
        icon(
            "settings",
            "M18.84,9.19L21.37,9.90L21.37,14.10L18.84,14.81L18.83,14.85L20.11,17.14L17.14,20.11L14.85,18.83L14.81,18.84L14.10,21.37L9.90,21.37L9.19,18.84L9.15,18.83L6.86,20.11L3.89,17.14L5.17,14.85L5.16,14.81L2.63,14.10L2.63,9.90L5.16,9.19L5.17,9.15L3.89,6.86L6.86,3.89L9.15,5.17L9.19,5.16L9.90,2.63L14.10,2.63L14.81,5.16L14.85,5.17L17.14,3.89L20.11,6.86L18.83,9.15z",
            accent = "M12,8.6a3.4,3.4 0,1 0,0 6.8a3.4,3.4 0,1 0,0 -6.8z",
        )
    }

    /** Neon pen: the ballpoint with rays of light at its tip. */
    val Neon by lazy { tool("neon", "$BODY H9zM9,12.5L12,19.5L15,12.5", accent = "M6.4,17.6L4.6,17.1M17.6,17.6L19.4,17.1M8,21.2L6.8,22.4M16,21.2L17.2,22.4") }

    /** Laser pointer: a slim pointer with its button, the beam and a flash where it lands. */
    val Laser by lazy {
        icon(
            "laser",
            "M15.5,3.5l5,5l-8.5,8.5l-5,-5zM14.5,7.5l2,2",
            accent = "M7,17L3.5,20.5M2,17.5h2.2M6.5,22v-2.2M2.6,21.4l1.4,-1.4",
        )
    }

    /** Eyedropper: a squeeze bulb, its collar and the glass tube down to the tip. */
    val Eyedropper by lazy {
        icon(
            "eyedropper",
            "M15.6,8.4l-9,9l-2.6,0.9l0.9,-2.6l9,-9",
            accent = "M13.6,6.4l4,4",
            fill = "M15.1,4.9l2.4,-2.4a2.3,2.3 0,0 1,3.2 3.2l-2.4,2.4z",
        )
    }

    /** Highlighter: collar and a slanted chisel tip. */
    val Highlighter by lazy { tool("highlighter", "$BODY H9zM9,12.5L10.2,16H13.8L15,12.5", accent = "M10.2,16V21.6L13.8,19.6V16") }

    /** Eraser: the same body, blunt, with a band. */
    val Eraser by lazy { tool("eraser", "M9,12.5V3.5a2,2 0,0 1,2 -2h2a2,2 0,0 1,2 2V20.5a2,2 0,0 1,-2 2h-2a2,2 0,0 1,-2 -2z", accent = "M9,14.5h6") }

    /** Lasso: a loop with a trailing rope. */
    val Lasso by lazy { icon("lasso", "M12,4c-4.7,0 -8.5,2.5 -8.5,5.5s3.8,5.5 8.5,5.5s8.5,-2.5 8.5,-5.5S16.7,4 12,4z", accent = "M6.8,14c-1,1.8 -0.3,3.6 1.6,3.6c1.6,0 1.8,1.8 0.4,2.9") }

    /** Hint marker: an eye crossed out, the band hides what is under it. */
    val Spoiler by lazy {
        icon("spoiler", "M2.5,12c2.2,-3.7 5.5,-5.6 9.5,-5.6s7.3,1.9 9.5,5.6c-2.2,3.7 -5.5,5.6 -9.5,5.6s-7.3,-1.9 -9.5,-5.6zM12,9.4a2.6,2.6 0,1 0,0 5.2a2.6,2.6 0,1 0,0 -5.2z", accent = "M4.5,4.5L19.5,19.5")
    }

    /** The ruler laid on the page: a straight strip with marks. */
    val RulerTool by lazy { icon("ruler-tool", "M2.5,8h19v8h-19zM6,8v3M9.5,8v4.5M13,8v3M16.5,8v4.5M20,8v3") }

    /** Mirror left to right / top to bottom. */
    val FlipHorizontal by lazy { icon("flip-h", "M12,3v18M9,7.5L4,12l5,4.5zM15,7.5l5,4.5l-5,4.5z") }
    val FlipVertical by lazy { icon("flip-v", "M3,12h18M7.5,9L12,4l4.5,5zM7.5,15l4.5,5l4.5,-5z") }

    /** Area: a circle with diagonal hatching. */
    val Area by lazy { icon("area", "M12,4a8,8 0,1 0,0 16a8,8 0,1 0,0 -16z", accent = "M7.1,18.3L18.3,7.1M4.4,14.5L14.5,4.4M9.5,19.6L19.6,9.5") }
    val Tune by lazy {
        icon(
            "tune",
            "M4,7h8.5M17.5,7H20M4,17h3M11.5,17H20M15,4.8a2.2,2.2 0,1 0,0 4.4a2.2,2.2 0,1 0,0 -4.4zM9,14.8a2.2,2.2 0,1 0,0 4.4a2.2,2.2 0,1 0,0 -4.4z",
        )
    }
    val Duplicate by lazy { icon("duplicate", "M10,8h9a1,1 0,0 1,1 1v10a1,1 0,0 1,-1 1h-9a1,1 0,0 1,-1 -1v-10a1,1 0,0 1,1 -1zM16,8V5a1,1 0,0 0,-1 -1H5a1,1 0,0 0,-1 1v10a1,1 0,0 0,1 1h4") }
    val Delete by lazy { icon("delete", "M4,7h16M10,11v6M14,11v6M6,7l1,12a2,2 0,0 0,2 2h6a2,2 0,0 0,2 -2l1,-12M9,7V4h6v3") }
    val Close by lazy { icon("close", "M6,6L18,18M18,6L6,18") }

    // Buttons that can be put on the bars or in the menu (see Toolbars).
    val TextBox by lazy { icon("text-box", "M5,7V4.5h14V7M12,4.5V19.5M9,19.5h6") }
    val InsertSpace by lazy { icon("insert-space", "M4,4.5h16M4,19.5h16", accent = "M12,8v8M9.5,10.5L12,8l2.5,2.5M9.5,13.5L12,16l2.5,-2.5") }
    val PageText by lazy { icon("page-text", "M6,3h12v18h-12z", accent = "M9,8h6M9,11.5h6M9,15h4") }
    val Toc by lazy { icon("toc", "M9,6h11M9,12h11M9,18h11", fill = "M4,5h2v2h-2zM4,11h2v2h-2zM4,17h2v2h-2z") }
    val Layers by lazy { icon("layers", "M12,4l9,4.5l-9,4.5l-9,-4.5z", accent = "M3,12.5l9,4.5l9,-4.5M3,16.5l9,4.5l9,-4.5") }
    val Cards by lazy { icon("cards", "M8,7h12v13h-12zM4,4h12v3M4,4v13h4", accent = "M11,12h6M11,15h4") }
    val Mic by lazy { icon("mic", "M9,5.5a3,3 0,0 1,6 0v5.5a3,3 0,0 1,-6 0z", accent = "M5.5,11a6.5,6.5 0,0 0,13 0M12,17.5v3.5M9,21h6") }
    val Recordings by lazy { icon("recordings", "M4,10v4M7.5,7v10M11,4v16M14.5,8v8M18,10.5v3", accent = "M20.5,9v6") }
    val Paper by lazy { icon("paper", "M5,3h14v18h-14z", accent = "M5,8h14M5,13h14M5,18h14") }
    val PageShift by lazy { icon("page-shift", "M9,3h11v18h-11z", accent = "M2.5,12h4.5M4.5,10l-2,2l2,2") }
    val Fraction by lazy { icon("fraction", "M5,12h14", accent = "M10.5,5.5L12,4v5M9.5,16a2.5,2.5 0,1 1,5 0c0,1.5 -5,4 -5,4h5") }
    val Units by lazy { icon("units", "M3,14h18v6h-18zM6.5,14v2.5M10,14v3.5M13.5,14v2.5M17,14v3.5", accent = "M6,10.5V6.5M6,8a2,2 0,0 1,4 0v2.5M10,8a2,2 0,0 1,4 0v2.5M15.5,10.5l3,-6") }
    val Save by lazy { icon("save", "M5,4h11l3,3v13h-14zM8,4v5h7V4M8,20v-6h8v6") }
    val Pdf by lazy { icon("pdf", "M6,3h8l4,4v14h-12zM14,3v4h4", accent = "M12,10v7M9,14l3,3l3,-3") }
    val PenButton by lazy { tool("pen-button", "$BODY H9zM9,12.5L12,21.8L15,12.5", accent = "M15,4v5.5M16.5,5v3.5") }
    val Scribble by lazy { icon("scribble", "M4,8l3,8l3,-8l3,8l3,-8l3,8") }
    val Help by lazy { icon("help", "M12,3a9,9 0,1 0,0 18a9,9 0,1 0,0 -18z", accent = "M9.5,9.5a2.5,2.5 0,1 1,3.5 2.3c-0.7,0.3 -1,0.9 -1,1.7M12,16.5v0.5") }
    val AllFunctions by lazy { icon("all-functions", "M4,4h7v7h-7zM13,4h7v7h-7zM4,13h7v7h-7zM13,13h7v7h-7z") }
    val ZoomWindow by lazy { icon("zoom-window", "M3,4h18v16h-18zM3,13h18", accent = "M8,7.5h4v3h-4zM6,16.5c1.5,-1.5 3,1.5 4.5,0s3,1.5 4.5,0s2,1 3,0") }
    val PdfShare by lazy { icon("pdf-share", "M6,3h8l4,4v5M14,3v4h4M6,3v18h6", accent = "M14,18h7M18,15l3,3l-3,3") }
    val Export by lazy { icon("export", "M6,3h8l4,4v14h-12zM14,3v4h4", accent = "M9,12h6M9,15h6M9,18h4") }
    val Print by lazy { icon("print", "M7,9V3h10v6M5,9h14a2,2 0,0 1,2,2v6h-4M7,17h-4v-6a2,2 0,0 1,2,-2M7,14h10v7h-10z") }
    val Scan by lazy { icon("scan", "M4,8V4h4M16,4h4v4M20,16v4h-4M8,20h-4v-4", accent = "M8,8h8v8h-8zM10,11h4M10,13.5h3") }
    val PhotoEdit by lazy { icon("photo-edit", "M7,3v14h14M3,7h14v14", accent = "M10,14l2.5,-3l2,2.5l1.5,-1.5l1,2") }
    val Pressure by lazy { icon("pressure", "M9,3h6l-1,8h-4zM10,11l2,5l2,-5", accent = "M4,20h16") }
    val Formula by lazy { icon("formula", "M4,6h6M7,6v12", accent = "M13,9l6,0M13,15h6M16,12a1,1 0,1 0,0.01 0") }
    val Protractor by lazy { icon("protractor", "M3,17a9,9 0,0 1,18 0zM3,17v2h18v-2", accent = "M12,17l5,-6M7,14.5l0.8,0.8M12,10v1M16.2,12.5l-0.8,0.6") }
    val Compass by lazy { icon("compass", "M12,3v3M12,6l-5,14M12,6l5,14M8.5,15h7", accent = "M12,4.5a1.5,1.5 0,1 0,0.01 0") }
    val Timer by lazy { icon("timer", "M12,6a7.5,7.5 0,1 0,0.01 0zM10,2.5h4M12,2.5v3.5", accent = "M12,13.5V9.5M12,13.5l3,2") }
    val Cloud by lazy { icon("cloud", "M7,18h10a4,4 0,0 0,0.5 -7.97A6,6 0,0 0,6.1 10.6A3.75,3.75 0,0 0,7 18z", accent = "M12,16v-5M9.8,13l2.2,-2.2l2.2,2.2") }
    /** Show the hidden tool bar again: it comes down. */
    val ToolsShow by lazy { icon("tools-show", "M4,8h16", accent = "M8,12.5l4,4l4,-4") }

    /** Hide the tool bar (narrow panes): a bar going up. */
    val ToolsHide by lazy { icon("tools-hide", "M4,15h16", accent = "M8,10.5l4,-4l4,4") }
    val SideBySide by lazy { icon("side", "M3,5h18v14H3zM12,5v14", accent = "M5.5,9h4M5.5,12h4M14.5,9h4M14.5,12h3") }
    val Notebook by lazy { icon("notebook", "M6,3h12v18H6zM9,3v18", accent = "M11.5,8h4M11.5,11h4") }
    val Swap by lazy { icon("swap", "M4,9h14l-3,-3M20,15H6l3,3") }
    val QuickNote by lazy { icon("quick-note", "M5,4h10l4,4v12H5z", accent = "M8,16l6,-6l2,2l-6,6H8z") }
    val PdfText by lazy { icon("pdf-text", "M6,3h8l4,4v14H6zM14,3v4h4", accent = "M8.5,11h7M8.5,14h7M8.5,17h4") }
    val PdfPage by lazy { icon("pdf-page", "M7,6h8l3,3v12H7zM15,6v3h3", accent = "M3,10a6,6 0,0 1,6 -7M9,3l-1.5,-0.5M9,3l-0.6,1.4") }
    val PdfDark by lazy { icon("pdf-dark", "M6,3h8l4,4v14H6zM14,3v4h4", fill = "M12.5,10.5a3.5,3.5 0,1 0,3 5.2a2.8,2.8 0,0 1,-3 -5.2z") }
    val PageNav by lazy { icon("page-nav", "M9,6L4,12l5,6M15,6l5,6l-5,6") }
    val Add by lazy { icon("add", "M12,5v14M5,12h14") }
    val Minus by lazy { icon("minus", "M5,12h14") }
    val Light by lazy {
        icon(
            "light",
            "M12,8a4,4 0,1 0,0 8a4,4 0,1 0,0 -8z",
            accent = "M12,2.5v2M12,19.5v2M2.5,12h2M19.5,12h2M5.3,5.3l1.4,1.4M17.3,17.3l1.4,1.4M5.3,18.7l1.4,-1.4M17.3,6.7l1.4,-1.4",
        )
    }
    val Dark by lazy { icon("dark", "M19.5,14.5A8,8 0,1 1,9.5 4.5A6.3,6.3 0,0 0,19.5 14.5z") }
    val AutoTheme by lazy { icon("auto-theme", "M12,4a8,8 0,1 0,0 16a8,8 0,1 0,0 -16z", fill = "M12,4a8,8 0,0 1,0 16z") }
    val Import by lazy { icon("import", "M4,16.5v2.5a1,1 0,0 0,1 1h14a1,1 0,0 0,1 -1v-2.5", accent = "M12,4v11M7.5,10.5L12,15l4.5,-4.5") }
    val NewFolder by lazy { icon("new-folder", "M3,7a2,2 0,0 1,2 -2h4l2,2h8a2,2 0,0 1,2 2v8a2,2 0,0 1,-2 2H5a2,2 0,0 1,-2 -2z", accent = "M12,10v6M9,13h6") }
    val Share by lazy {
        icon(
            "share",
            "M18,3a2.5,2.5 0,1 0,0 5a2.5,2.5 0,1 0,0 -5zM6,9.5a2.5,2.5 0,1 0,0 5a2.5,2.5 0,1 0,0 -5zM18,16a2.5,2.5 0,1 0,0 5a2.5,2.5 0,1 0,0 -5zM8.2,10.8l7.6,-4.3M8.2,13.2l7.6,4.3",
        )
    }
}

/**
 * Icon button with its name as accessibility label and as a tooltip (long press or pen
 * hover), so every icon can be identified. [selected] non-null makes it a toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean? = null,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        if (selected != null) {
            // Unselected tools stay plain; the selected one sits on a soft tonal circle.
            IconToggleButton(
                checked = selected,
                onCheckedChange = { onClick() },
                enabled = enabled,
                colors = IconButtonDefaults.iconToggleButtonColors(
                    checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                InkIcon(icon, contentDescription = label)
            }
        } else {
            IconButton(onClick = onClick, enabled = enabled) { InkIcon(icon, contentDescription = label) }
        }
    }
}

/** An [InkIcons] icon in the current interface style, in the content colour (gold accents in premium). */
@Composable
fun InkIcon(icon: ImageVector, contentDescription: String?, modifier: Modifier = Modifier) {
    val style = LocalUiStyle.current
    val base = LocalContentColor.current
    if (style.gilded || style == UiStyle.NOTEBOOK) {
        val gold = MaterialTheme.colorScheme.primary.copy(alpha = base.alpha)
        Icon(InkIcons.styled(icon, style, base, gold), contentDescription, modifier, tint = Color.Unspecified)
    } else {
        Icon(InkIcons.styled(icon, style, Color.Black, Color.Black), contentDescription, modifier)
    }
}
