package dev.inknotes.ui

import dev.inknotes.data.tr
import dev.inknotes.data.InkKind
import android.content.Context
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.inknotes.data.DashPattern
import dev.inknotes.data.DashStyle
import dev.inknotes.data.FillPattern
import dev.inknotes.data.HatchStyle
import dev.inknotes.data.LineType

enum class ThemeMode(val title: String) {
    SYSTEM(tr("Как в системе", "System")),
    LIGHT(tr("Светлая", "Light")),
    DARK(tr("Тёмная", "Dark")),
}

/** Look of the whole interface, chosen in the library's settings. */
enum class UiStyle(val title: String, val note: String) {
    CLASSIC(tr("Классический", "Classic"), tr("Светлый и простой: синие акценты, цветные обложки", "Light and simple: blue accents, coloured covers")),
    PREMIUM(tr("Премиум", "Premium"), tr("Слоновая кость или чёрный шёлк, шампанское золото, шрифт с засечками, кожаные обложки с тиснением", "Ivory or black silk, champagne gold, a serif typeface, embossed leather covers")),
    MINIMAL(tr("Минимал", "Minimal"), tr("Монохром, тонкие шрифты и линии, острые углы и много воздуха — сдержанно и дорого", "Monochrome, thin type and lines, sharp corners and lots of air — restrained and refined")),
    MYSTIC(tr("Загадочный", "Mystic"), tr("Приглушённый бежевый пергамент или графит, золото, шесть обложек старинных книг: листья, арка, луна, овал, медальоны, венок", "Muted beige parchment or graphite, gold, six antique book covers: leaves, arch, moon, oval, medallions, wreath")),
    GLASS(tr("Жидкое стекло", "Liquid glass"), tr("Как в iPhone: полупрозрачные панели с бликами над цветным фоном, крупные скругления, иконки в два слоя", "iPhone-like: translucent glossy panels over a colourful background, large rounded corners, two-layer icons")),
    NOTEBOOK(tr("Тетрадки", "Copybooks"), tr("Пастель и пластик: матовые обложки на пружине, школьные тетради под плёнкой, голографические папки", "Pastel and plastic: matte spiral covers, laminated school notebooks, holographic folders")),
    ;

    /** Gold accents in icons and titles. */
    val gilded get() = this == PREMIUM || this == MYSTIC

    /** Serif headings. */
    val serif get() = this == PREMIUM || this == MYSTIC
}

/** Style in effect, for the few places that draw differently in it (covers, folders, titles). */
val LocalUiStyle = staticCompositionLocalOf { UiStyle.CLASSIC }

/** Champagne gold of the premium style: hairlines and accents, and the foil of titles. */
object Gold {
    val Deep = Color(0xFF9A7738)
    val Champagne = Color(0xFFD2B176)
    val Foil = Brush.linearGradient(listOf(Color(0xFFF4E4B8), Color(0xFFC9A35A), Color(0xFFF1DDA6), Color(0xFFB8914A)))

    /** Hairline colour on this theme's background. */
    fun line(dark: Boolean) = if (dark) Champagne.copy(alpha = 0.55f) else Deep.copy(alpha = 0.55f)
}

/** Small persistent settings: theme, recent custom colours, hatch settings, handwriting search. */
class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    var uiStyle: UiStyle
        get() = runCatching { UiStyle.valueOf(prefs.getString(STYLE, null)!!) }.getOrDefault(UiStyle.CLASSIC)
        set(value) = prefs.edit().putString(STYLE, value.name).apply()

    /** Covers are tinted glass over the first page instead of opaque. */
    var glassCovers: Boolean
        get() = prefs.getBoolean(GLASS, false)
        set(value) = prefs.edit().putBoolean(GLASS, value).apply()

    var themeMode: ThemeMode
        get() = runCatching { ThemeMode.valueOf(prefs.getString(THEME, null)!!) }.getOrDefault(ThemeMode.SYSTEM)
        set(value) = prefs.edit().putString(THEME, value.name).apply()

    var recentColors: List<Int>
        get() = prefs.getString(RECENT, "").orEmpty().split(',').mapNotNull { it.toLongOrNull(16)?.toInt() }
        set(value) = prefs.edit().putString(RECENT, value.joinToString(",") { Integer.toHexString(it) }).apply()

    var hatchPattern: FillPattern
        get() = runCatching { FillPattern.valueOf(prefs.getString(HATCH_PATTERN, null)!!) }.getOrDefault(FillPattern.LINES)
        set(value) = prefs.edit().putString(HATCH_PATTERN, value.name).apply()

    var hatchStyle: HatchStyle
        get() {
            val parts = prefs.getString(HATCH_STYLE, null)?.split(',') ?: return HatchStyle.DEFAULT
            val v = parts.take(5).mapNotNull { it.toFloatOrNull() }
            if (v.size != 5 || v.any { !it.isFinite() }) return HatchStyle.DEFAULT
            val lineType = parts.getOrNull(5)?.let { name -> LineType.entries.find { it.name == name } } ?: LineType.SOLID
            return HatchStyle(v[0], v[1], v[2], v[3], v[4], lineType).sane()
        }
        set(value) = prefs.edit().putString(HATCH_STYLE, with(value) { "$width,$spacing,$angle,$scale,$opacity,${lineType.name}" }).apply()

    /** Last size of a tool (by name); [default] if never set. */
    fun toolSize(tool: String, default: Float): Float = prefs.getFloat("size.$tool", default)

    fun setToolSize(tool: String, size: Float) = prefs.edit().putFloat("size.$tool", size).apply()

    /** Handwriting recognition was switched on (its model downloaded) by the user. */
    var handwritingEnabled: Boolean
        get() = prefs.getBoolean(HANDWRITING, false)
        set(value) = prefs.edit().putBoolean(HANDWRITING, value).apply()

    /** Answers after "=" were switched on (their recognition model downloaded) by the user. */
    var mathEnabled: Boolean
        get() = prefs.getBoolean(MATH, false)
        set(value) = prefs.edit().putBoolean(MATH, value).apply()

    /** Highlighter passes are made level and fitted behind the text. */
    var straightHighlighter: Boolean
        get() = prefs.getBoolean(STRAIGHT_HIGHLIGHTER, true)
        set(value) = prefs.edit().putBoolean(STRAIGHT_HIGHLIGHTER, value).apply()

    /** The pen line's width follows the pressure (off: one width all along). */
    var penPressure: Boolean
        get() = prefs.getBoolean("penPressure", true)
        set(value) = prefs.edit().putBoolean("penPressure", value).apply()

    /** PDF pages shown dark (inverted, colours keep their hue). */
    var pdfDark: Boolean
        get() = prefs.getBoolean("pdfDark", false)
        set(value) = prefs.edit().putBoolean("pdfDark", value).apply()

    /** Last choices of the export dialog: "pdf"/"png", "all"/"page"/"range". */
    var exportFormat: String
        get() = prefs.getString("exportFormat", "pdf") ?: "pdf"
        set(value) = prefs.edit().putString("exportFormat", value).apply()
    var exportPages: String
        get() = prefs.getString("exportPages", "all") ?: "all"
        set(value) = prefs.edit().putString("exportPages", value).apply()

    /** A zigzag scribbled over writing erases it. */
    var scribbleErase: Boolean
        get() = prefs.getBoolean(SCRIBBLE, true)
        set(value) = prefs.edit().putBoolean(SCRIBBLE, value).apply()

    /** Answers as fractions (1/3, 5/2) or decimals (0,333333; 2,5). */
    var fractionAnswers: Boolean
        get() = prefs.getBoolean(FRACTIONS, true)
        set(value) = prefs.edit().putBoolean(FRACTIONS, value).apply()

    /** Units of measurement and physical constants in formulas (9,8 м/с² · 3 с). */
    var mathUnits: Boolean
        get() = prefs.getBoolean(UNITS, false)
        set(value) = prefs.edit().putBoolean(UNITS, value).apply()

    /** Where the editor's buttons are (see [dev.inknotes.data.ToolbarLayout]); null: the full layout. */
    var toolbarLayout: String?
        get() = prefs.getString(TOOLBAR, null)
        set(value) = prefs.edit().putString(TOOLBAR, value).apply()

    /** The choice of a ready-made layout was offered once. */
    var toolbarIntroShown: Boolean
        get() = prefs.getBoolean(TOOLBAR_INTRO, false)
        set(value) = prefs.edit().putBoolean(TOOLBAR_INTRO, value).apply()

    /** The folder (a document tree the user granted) for automatic backups, or null. */
    var backupFolder: String?
        get() = prefs.getString(BACKUP_FOLDER, null)
        set(value) = prefs.edit().putString(BACKUP_FOLDER, value).apply()

    // ---- Google Drive (0.37)
    /** The lasting key of the Drive sign-in (only in the app's private storage). */
    var driveRefreshToken: String?
        get() = prefs.getString("driveRefresh", null)?.let { stored ->
            dev.inknotes.data.Secrets.open(stored).also { plain ->
                // Kept in plain text by an older version: encrypted from now on.
                if (plain != null && plain == stored) prefs.edit().putString("driveRefresh", dev.inknotes.data.Secrets.seal(plain)).apply()
            }
        }
        set(value) = prefs.edit().putString("driveRefresh", value?.let(dev.inknotes.data.Secrets::seal)).apply()
    var driveEmail: String?
        get() = prefs.getString("driveEmail", null)
        set(value) = prefs.edit().putString("driveEmail", value).apply()
    /** Sync by itself when the app is opened and left. */
    var driveAuto: Boolean
        get() = prefs.getBoolean("driveAuto", true)
        set(value) = prefs.edit().putBoolean("driveAuto", value).apply()
    /** By itself only on Wi-Fi (PDF notebooks can be big). */
    var driveWifiOnly: Boolean
        get() = prefs.getBoolean("driveWifiOnly", true)
        set(value) = prefs.edit().putBoolean("driveWifiOnly", value).apply()
    var driveLastSync: Long
        get() = prefs.getLong("driveLastSync", 0L)
        set(value) = prefs.edit().putLong("driveLastSync", value).apply()
    var driveLastResult: String?
        get() = prefs.getString("driveLastResult", null)
        set(value) = prefs.edit().putString("driveLastResult", value).apply()

    /** The quick notes notebook (lock screen, tile, widget). */
    var quickNoteId: String?
        get() = prefs.getString("quickNoteId", null)
        set(value) = prefs.edit().putString("quickNoteId", value).apply()

    /** How many times it was said where the hidden tools went (said the first few times). */
    var toolsHiddenHints: Int
        get() = prefs.getInt("toolsHiddenHints", 0)
        set(value) = prefs.edit().putInt("toolsHiddenHints", value).apply()

    /** When an automatic copy in the backup folder was last read back whole (see Backup). */
    var backupReadBackAt: Long
        get() = prefs.getLong("backupReadBackAt", 0L)
        set(value) = prefs.edit().putLong("backupReadBackAt", value).apply()

    /** The notification permission was asked for once (with the first recording). */
    var askedNotifications: Boolean
        get() = prefs.getBoolean("askedNotifications", false)
        set(value) = prefs.edit().putBoolean("askedNotifications", value).apply()

    /** When a quick note was last written: the app re-reads that notebook if it has it open. */
    var quickNoteUsedAt: Long
        get() = prefs.getLong("quickNoteUsedAt", 0L)
        set(value) = prefs.edit().putLong("quickNoteUsedAt", value).apply()

    /** Tabs open at the last stop (see TabsMemory). */
    var openTabs: String?
        get() = prefs.getString("openTabs", null)
        set(value) = prefs.edit().putString("openTabs", value).apply()

    /** Names of automatic copies in the folder that were read back whole after writing. */
    var backupWhole: Set<String>
        get() = prefs.getStringSet("backupWhole", null)?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet("backupWhole", value.toSet()).apply()

    /** When the last automatic backup was written (0 = never). */
    var lastBackupAt: Long
        get() = prefs.getLong(BACKUP_AT, 0L)
        set(value) = prefs.edit().putLong(BACKUP_AT, value).apply()

    /** How the library orders notebooks (starred ones always first). */
    var librarySort: LibrarySort
        get() = runCatching { LibrarySort.valueOf(prefs.getString(SORT, null)!!) }.getOrDefault(LibrarySort.CHANGED)
        set(value) = prefs.edit().putString(SORT, value.name).apply()

    /** What the pen tool draws: pen, pencil or dashes. */
    var penKind: InkKind
        get() = runCatching { InkKind.valueOf(prefs.getString(PEN_KIND, null)!!) }.getOrDefault(InkKind.PEN)
            .takeIf { it == InkKind.PEN || it == InkKind.PENCIL || it == InkKind.DASHED } ?: InkKind.PEN
        set(value) = prefs.edit().putString(PEN_KIND, value.name).apply()

    /** Room beside the pages in the editor: share of the screen's width, + left, − right. */
    var pageShift: Float
        get() = prefs.getFloat(PAGE_SHIFT, 0f).takeIf { it.isFinite() } ?: 0f
        set(value) = prefs.edit().putFloat(PAGE_SHIFT, value).apply()

    /** Pattern of new dashed lines. */
    var dashStyle: DashStyle
        get() {
            val parts = prefs.getString(DASH_STYLE, null)?.split(',') ?: return DashStyle.DEFAULT
            val pattern = parts.getOrNull(0)?.let { name -> DashPattern.entries.find { it.name == name } } ?: return DashStyle.DEFAULT
            val dash = parts.getOrNull(1)?.toFloatOrNull()?.takeIf { it.isFinite() } ?: return DashStyle.DEFAULT
            val gap = parts.getOrNull(2)?.toFloatOrNull()?.takeIf { it.isFinite() } ?: return DashStyle.DEFAULT
            return DashStyle(pattern, dash, gap).sane()
        }
        set(value) = prefs.edit().putString(DASH_STYLE, with(value) { "${pattern.name},$dash,$gap" }).apply()

    private companion object {
        const val PEN_KIND = "penKind"
        const val DASH_STYLE = "dashStyle"
        const val PAGE_SHIFT = "pageShift"
        const val SCRIBBLE = "scribbleErase"
        const val GLASS = "glassCovers"
        const val SORT = "librarySort"
        const val BACKUP_FOLDER = "backupFolder"
        const val BACKUP_AT = "backupAt"
        const val FRACTIONS = "fractionAnswers"
        const val UNITS = "mathUnits"
        const val TOOLBAR = "toolbarLayout"
        const val TOOLBAR_INTRO = "toolbarIntroShown"
        const val STRAIGHT_HIGHLIGHTER = "straightHighlighter"
        const val MATH = "math"
        const val THEME = "theme"
        const val STYLE = "uiStyle"
        const val RECENT = "recentColors"
        const val HATCH_PATTERN = "hatchPattern"
        const val HATCH_STYLE = "hatchStyle"
        const val HANDWRITING = "handwriting"
    }
}

enum class LibrarySort(val title: String) {
    CHANGED(tr("по изменению", "by change")),
    CREATED(tr("по созданию", "by creation")),
    TITLE(tr("по названию", "by title")),
}

private val Accent = Color(0xFF3D63C9)

private val LightColors = lightColorScheme(
    primary = Accent,
    secondaryContainer = Color(0xFFDCE4F7),
    onSecondaryContainer = Color(0xFF14254D),
    background = Color(0xFFF4F5F7),
    surface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF5F6670),
    outline = Color(0xFFC9CED6),
    outlineVariant = Color(0xFFDADDE2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9BEF2),
    onPrimary = Color(0xFF0E214D),
    secondaryContainer = Color(0xFF2C3A5C),
    onSecondaryContainer = Color(0xFFDCE4F7),
    background = Color(0xFF141517),
    surface = Color(0xFF1B1C1F),
    onSurfaceVariant = Color(0xFFA2A9B3),
    outline = Color(0xFF4A5059),
    outlineVariant = Color(0xFF2E3238),
)

/** Ivory paper, black ink, gold leaf. */
private val PremiumLight = lightColorScheme(
    primary = Color(0xFF8C6A2F),
    onPrimary = Color(0xFFFFFBF2),
    primaryContainer = Color(0xFFEFE2C4),
    onPrimaryContainer = Color(0xFF3A2C12),
    secondary = Color(0xFF6E6250),
    secondaryContainer = Color(0xFFEDE3CF),
    onSecondaryContainer = Color(0xFF3A2C12),
    tertiary = Color(0xFF9A7738),
    background = Color(0xFFF5F0E6),
    onBackground = Color(0xFF1C1914),
    surface = Color(0xFFFBF8F1),
    onSurface = Color(0xFF1C1914),
    surfaceVariant = Color(0xFFEDE6D8),
    onSurfaceVariant = Color(0xFF716756),
    surfaceContainerHigh = Color(0xFFF8F3E8),
    surfaceContainer = Color(0xFFF6F0E4),
    surfaceContainerHighest = Color(0xFFF1EADC),
    outline = Color(0xFFC9B994),
    outlineVariant = Color(0xFFE4D9C2),
)

/** Black silk and champagne. */
private val PremiumDark = darkColorScheme(
    primary = Color(0xFFD9BC82),
    onPrimary = Color(0xFF2A200C),
    primaryContainer = Color(0xFF3A3020),
    onPrimaryContainer = Color(0xFFF1E1BC),
    secondary = Color(0xFFB9AC92),
    secondaryContainer = Color(0xFF2C261B),
    onSecondaryContainer = Color(0xFFEBDDBD),
    tertiary = Color(0xFFD2B176),
    background = Color(0xFF0E0D0B),
    onBackground = Color(0xFFEDE6D6),
    surface = Color(0xFF161411),
    onSurface = Color(0xFFEDE6D6),
    surfaceVariant = Color(0xFF241F18),
    onSurfaceVariant = Color(0xFFA89D86),
    surfaceContainerHigh = Color(0xFF1D1A15),
    surfaceContainer = Color(0xFF191713),
    surfaceContainerHighest = Color(0xFF231F19),
    outline = Color(0xFF5E533F),
    outlineVariant = Color(0xFF2C271F),
)

/** Gallery white and graphite: no colour but the user's own. */
private val MinimalLight = lightColorScheme(
    primary = Color(0xFF111111),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDEDEA),
    onPrimaryContainer = Color(0xFF111111),
    secondary = Color(0xFF55554F),
    secondaryContainer = Color(0xFFEDEDEA),
    onSecondaryContainer = Color(0xFF111111),
    tertiary = Color(0xFF55554F),
    background = Color(0xFFFAFAF8),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFF1F1EE),
    onSurfaceVariant = Color(0xFF7C7C76),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFCFCFA),
    surfaceContainerHighest = Color(0xFFF5F5F2),
    outline = Color(0xFFD6D6D1),
    outlineVariant = Color(0xFFE9E9E5),
)

private val MinimalDark = darkColorScheme(
    primary = Color(0xFFF2F2EE),
    onPrimary = Color(0xFF0B0B0B),
    primaryContainer = Color(0xFF242424),
    onPrimaryContainer = Color(0xFFF2F2EE),
    secondary = Color(0xFFB0B0AA),
    secondaryContainer = Color(0xFF242424),
    onSecondaryContainer = Color(0xFFF2F2EE),
    tertiary = Color(0xFFB0B0AA),
    background = Color(0xFF0B0B0B),
    onBackground = Color(0xFFF2F2EE),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFF2F2EE),
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFF8D8D88),
    surfaceContainerHigh = Color(0xFF161616),
    surfaceContainer = Color(0xFF131313),
    surfaceContainerHighest = Color(0xFF1C1C1C),
    outline = Color(0xFF3A3A38),
    outlineVariant = Color(0xFF222222),
)

/** Beige parchment, old gold, ink brown: everything muted. */
private val MysticLight = lightColorScheme(
    primary = Color(0xFF85693D),
    onPrimary = Color(0xFFF7F1E6),
    primaryContainer = Color(0xFFE2D6BF),
    onPrimaryContainer = Color(0xFF3B3022),
    secondary = Color(0xFF6E6555),
    secondaryContainer = Color(0xFFE0D6C4),
    onSecondaryContainer = Color(0xFF3B3022),
    tertiary = Color(0xFF8C7546),
    background = Color(0xFFE8E0D2),
    onBackground = Color(0xFF2E2922),
    surface = Color(0xFFEFE8DC),
    onSurface = Color(0xFF2E2922),
    surfaceVariant = Color(0xFFE2D9C9),
    onSurfaceVariant = Color(0xFF6D6456),
    surfaceContainerHigh = Color(0xFFEDE5D7),
    surfaceContainer = Color(0xFFEAE2D4),
    surfaceContainerHighest = Color(0xFFE4DBCB),
    outline = Color(0xFFB7A98F),
    outlineVariant = Color(0xFFD4C8B3),
)

/** Warm graphite, like the cloth a set of old books stands on; dim gold. */
private val MysticDark = darkColorScheme(
    primary = Color(0xFFC7AC74),
    onPrimary = Color(0xFF2B2416),
    primaryContainer = Color(0xFF4A4335),
    onPrimaryContainer = Color(0xFFEADCC0),
    secondary = Color(0xFFADA392),
    secondaryContainer = Color(0xFF45413A),
    onSecondaryContainer = Color(0xFFE6DDCC),
    tertiary = Color(0xFFBFA56E),
    background = Color(0xFF383430),
    onBackground = Color(0xFFE4DDD0),
    surface = Color(0xFF363431),
    onSurface = Color(0xFFE4DDD0),
    surfaceVariant = Color(0xFF403D38),
    onSurfaceVariant = Color(0xFFA39B8D),
    surfaceContainerHigh = Color(0xFF3A3834),
    surfaceContainer = Color(0xFF363431),
    surfaceContainerHighest = Color(0xFF423F3A),
    outline = Color(0xFF6A645A),
    outlineVariant = Color(0xFF47433D),
)

/** Liquid glass: frosted white panes over colour, the iOS blue. */
private val GlassLight = lightColorScheme(
    primary = Color(0xFF0A7AFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E8FF),
    onPrimaryContainer = Color(0xFF002E66),
    secondary = Color(0xFF5B6275),
    secondaryContainer = Color.White.copy(alpha = 0.7f),
    onSecondaryContainer = Color(0xFF0A57C2),
    tertiary = Color(0xFFAF52DE),
    background = Color(0xFFEDEFF7),
    onBackground = Color(0xFF10131A),
    surface = Color.White.copy(alpha = 0.6f),
    onSurface = Color(0xFF10131A),
    surfaceVariant = Color.White.copy(alpha = 0.45f),
    onSurfaceVariant = Color(0xFF5B6275),
    surfaceContainerLowest = Color.White.copy(alpha = 0.5f),
    surfaceContainerLow = Color.White.copy(alpha = 0.6f),
    surfaceContainer = Color(0xFFF7F8FC).copy(alpha = 0.9f),
    surfaceContainerHigh = Color(0xFFF8F9FD).copy(alpha = 0.97f),
    surfaceContainerHighest = Color(0xFFF8F9FD).copy(alpha = 0.95f),
    outline = Color(0xFF9AA1B5).copy(alpha = 0.6f),
    outlineVariant = Color.White.copy(alpha = 0.75f),
)

private val GlassDark = darkColorScheme(
    primary = Color(0xFF3D9BFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF123A6E),
    onPrimaryContainer = Color(0xFFD8E8FF),
    secondary = Color(0xFFA3A9BC),
    secondaryContainer = Color.White.copy(alpha = 0.16f),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFBF5AF2),
    background = Color(0xFF0B0E1A),
    onBackground = Color(0xFFF2F4FA),
    surface = Color.White.copy(alpha = 0.09f),
    onSurface = Color(0xFFF2F4FA),
    surfaceVariant = Color.White.copy(alpha = 0.07f),
    onSurfaceVariant = Color(0xFFA3A9BC),
    surfaceContainerLowest = Color.White.copy(alpha = 0.05f),
    surfaceContainerLow = Color.White.copy(alpha = 0.08f),
    surfaceContainer = Color(0xFF1B2032).copy(alpha = 0.92f),
    surfaceContainerHigh = Color(0xFF1E2337).copy(alpha = 0.97f),
    surfaceContainerHighest = Color(0xFF232940).copy(alpha = 0.95f),
    outline = Color.White.copy(alpha = 0.28f),
    outlineVariant = Color.White.copy(alpha = 0.14f),
)

/** Notebooks: a pastel desk, white paper, a pink gel pen. */
private val NotebookLight = lightColorScheme(
    primary = Color(0xFFD9488F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFCE1EE),
    onPrimaryContainer = Color(0xFF5E1236),
    secondary = Color(0xFF7D8BC9),
    secondaryContainer = Color(0xFFFCE1EE),
    onSecondaryContainer = Color(0xFF6B1E44),
    tertiary = Color(0xFF6EC1E4),
    background = Color(0xFFFBF4F4),
    onBackground = Color(0xFF2A2226),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF2A2226),
    surfaceVariant = Color(0xFFF6ECEF),
    onSurfaceVariant = Color(0xFF8A7780),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFBFC),
    surfaceContainer = Color(0xFFFFF8FA),
    surfaceContainerHigh = Color(0xFFFFF6F9),
    surfaceContainerHighest = Color(0xFFFBEEF3),
    outline = Color(0xFFE5C6D4),
    outlineVariant = Color(0xFFF2E2E9),
)

private val NotebookDark = darkColorScheme(
    primary = Color(0xFFF49AC1),
    onPrimary = Color(0xFF4A1430),
    primaryContainer = Color(0xFF5A2840),
    onPrimaryContainer = Color(0xFFFCE1EE),
    secondary = Color(0xFFA9B4E8),
    secondaryContainer = Color(0xFF4A2A39),
    onSecondaryContainer = Color(0xFFFCE1EE),
    tertiary = Color(0xFF8ED3EF),
    background = Color(0xFF1C1719),
    onBackground = Color(0xFFF5EAEE),
    surface = Color(0xFF262023),
    onSurface = Color(0xFFF5EAEE),
    surfaceVariant = Color(0xFF332A2E),
    onSurfaceVariant = Color(0xFFB9A3AD),
    surfaceContainerLowest = Color(0xFF181315),
    surfaceContainerLow = Color(0xFF221C1F),
    surfaceContainer = Color(0xFF282124),
    surfaceContainerHigh = Color(0xFF2E262A),
    surfaceContainerHighest = Color(0xFF362D31),
    outline = Color(0xFF5C4651),
    outlineVariant = Color(0xFF3A2E33),
)

/**
 * What the screen is painted with behind everything, when a style has more than a flat
 * colour: liquid glass needs colour to refract (soft blobs of it), notebooks lie on a
 * pastel desk.
 */
fun hasBackdrop(style: UiStyle) = style == UiStyle.GLASS || style == UiStyle.NOTEBOOK

fun Modifier.backdrop(style: UiStyle, dark: Boolean): Modifier = drawWithCache {
    val w = size.width
    val h = size.height
    val base = colorsFor(style, dark).background
    // Soft blobs of colour: what liquid glass bends and tints.
    val blobs: List<Triple<Offset, Float, Color>> = when {
        style == UiStyle.GLASS && !dark -> listOf(
            Triple(Offset(0.08f * w, 0.1f * h), 0.55f, Color(0xFFFF9CCB)),
            Triple(Offset(0.9f * w, 0.15f * h), 0.6f, Color(0xFF84B8FF)),
            Triple(Offset(0.25f * w, 0.95f * h), 0.6f, Color(0xFF8FE6CB)),
            Triple(Offset(0.85f * w, 0.85f * h), 0.55f, Color(0xFFB9A0FF)),
            Triple(Offset(0.55f * w, 0.5f * h), 0.45f, Color(0xFFFFE1B8)),
        )
        style == UiStyle.GLASS -> listOf(
            Triple(Offset(0.08f * w, 0.1f * h), 0.6f, Color(0xFF5B3BB8).copy(alpha = 0.85f)),
            Triple(Offset(0.92f * w, 0.18f * h), 0.6f, Color(0xFF1A66C4).copy(alpha = 0.85f)),
            Triple(Offset(0.2f * w, 0.98f * h), 0.6f, Color(0xFF13807A).copy(alpha = 0.8f)),
            Triple(Offset(0.88f * w, 0.9f * h), 0.55f, Color(0xFFA2308A).copy(alpha = 0.8f)),
        )
        dark -> listOf(
            Triple(Offset(0.1f * w, 0.1f * h), 0.5f, Color(0xFF3A2430)),
            Triple(Offset(0.9f * w, 0.9f * h), 0.5f, Color(0xFF23283F)),
        )
        else -> listOf(
            Triple(Offset(0.05f * w, 0.05f * h), 0.55f, Color(0xFFFCE0EA)),
            Triple(Offset(0.95f * w, 0.3f * h), 0.5f, Color(0xFFE3E8FB)),
            Triple(Offset(0.4f * w, 1f * h), 0.55f, Color(0xFFFFF0DE)),
        )
    }
    val d = maxOf(w, h)
    onDrawBehind {
        drawRect(base)
        for ((c, r, color) in blobs) drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), c, r * d), r * d, c)
    }
}

/** Colours of [style] in light or dark, also for the previews in settings. */
fun colorsFor(style: UiStyle, dark: Boolean): ColorScheme = when (style) {
    UiStyle.CLASSIC -> if (dark) DarkColors else LightColors
    UiStyle.PREMIUM -> if (dark) PremiumDark else PremiumLight
    UiStyle.MINIMAL -> if (dark) MinimalDark else MinimalLight
    UiStyle.MYSTIC -> if (dark) MysticDark else MysticLight
    UiStyle.GLASS -> if (dark) GlassDark else GlassLight
    UiStyle.NOTEBOOK -> if (dark) NotebookDark else NotebookLight
}

/** Display face of [style]: serif for the premium one. */
fun displayFont(style: UiStyle): FontFamily = if (style.serif) FontFamily.Serif else FontFamily.Default

/** Weight of headings: light in the premium and minimal styles. */
fun titleWeight(style: UiStyle): FontWeight = when (style) {
    UiStyle.CLASSIC -> FontWeight.Bold
    UiStyle.PREMIUM, UiStyle.MYSTIC -> FontWeight.Normal
    UiStyle.MINIMAL -> FontWeight.Light
    UiStyle.GLASS -> FontWeight.SemiBold
    UiStyle.NOTEBOOK -> FontWeight.ExtraBold
}

/** Headings in a light serif with air between the letters; buttons and labels slightly tracked. */
private val PremiumType = Typography().let { t ->
    fun serif(s: TextStyle, tracking: Float) = s.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, letterSpacing = tracking.sp)
    t.copy(
        displayLarge = serif(t.displayLarge, 0.5f),
        displayMedium = serif(t.displayMedium, 0.5f),
        displaySmall = serif(t.displaySmall, 0.5f),
        headlineLarge = serif(t.headlineLarge, 0.6f),
        headlineMedium = serif(t.headlineMedium, 0.6f),
        headlineSmall = serif(t.headlineSmall, 0.6f),
        titleLarge = serif(t.titleLarge, 0.5f),
        titleMedium = t.titleMedium.copy(letterSpacing = 0.4.sp),
        bodyLarge = t.bodyLarge.copy(letterSpacing = 0.3.sp),
        bodyMedium = t.bodyMedium.copy(letterSpacing = 0.3.sp),
        labelLarge = t.labelLarge.copy(letterSpacing = 1.1.sp),
        labelMedium = t.labelMedium.copy(letterSpacing = 1f.sp),
    )
}

/** Large light headings set tight, small text and labels with air between the letters. */
private val MinimalType = Typography().let { t ->
    fun light(s: TextStyle, tracking: Float) = s.copy(fontWeight = FontWeight.Light, letterSpacing = tracking.sp)
    t.copy(
        displayLarge = light(t.displayLarge, -0.5f),
        displayMedium = light(t.displayMedium, -0.4f),
        displaySmall = light(t.displaySmall, -0.3f),
        headlineLarge = light(t.headlineLarge, -0.2f),
        headlineMedium = light(t.headlineMedium, -0.2f),
        headlineSmall = light(t.headlineSmall, 0f),
        titleLarge = light(t.titleLarge, 0f),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.Normal, letterSpacing = 0.3.sp),
        bodyLarge = t.bodyLarge.copy(letterSpacing = 0.2.sp),
        bodyMedium = t.bodyMedium.copy(letterSpacing = 0.2.sp),
        labelLarge = t.labelLarge.copy(letterSpacing = 1.3.sp, fontWeight = FontWeight.Normal),
        labelMedium = t.labelMedium.copy(letterSpacing = 1.2.sp),
    )
}

/** San Francisco feel: tight bold headings, plain body. */
private val GlassType = Typography().let { t ->
    fun head(s: TextStyle, tracking: Float, weight: FontWeight = FontWeight.Bold) = s.copy(fontWeight = weight, letterSpacing = tracking.sp)
    t.copy(
        displayLarge = head(t.displayLarge, -1f),
        displayMedium = head(t.displayMedium, -0.8f),
        displaySmall = head(t.displaySmall, -0.6f),
        headlineLarge = head(t.headlineLarge, -0.6f),
        headlineMedium = head(t.headlineMedium, -0.5f),
        headlineSmall = head(t.headlineSmall, -0.4f),
        titleLarge = head(t.titleLarge, -0.3f, FontWeight.SemiBold),
        titleMedium = head(t.titleMedium, -0.2f, FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    )
}

/** Round and chunky, like the print on a school notebook. */
private val NotebookType = Typography().let { t ->
    fun head(s: TextStyle, tracking: Float) = s.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = tracking.sp)
    t.copy(
        displayLarge = head(t.displayLarge, -1.2f),
        displayMedium = head(t.displayMedium, -1f),
        displaySmall = head(t.displaySmall, -0.8f),
        headlineLarge = head(t.headlineLarge, -0.8f),
        headlineMedium = head(t.headlineMedium, -0.6f),
        headlineSmall = head(t.headlineSmall, -0.5f),
        titleLarge = head(t.titleLarge, -0.3f),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.Bold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.Bold),
    )
}

/** Big continuous corners. */
private val GlassShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

private val NotebookShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Square corners, softened only for dialogs. */
private val MinimalShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(2.dp),
    large = RoundedCornerShape(2.dp),
    extraLarge = RoundedCornerShape(6.dp),
)

/** Crisper corners: cut like stationery rather than rounded like toys. */
private val PremiumShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(14.dp),
)

@Composable
fun InkNotesTheme(dark: Boolean, style: UiStyle = UiStyle.CLASSIC, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colorsFor(style, dark),
        typography = when (style) {
            UiStyle.CLASSIC -> Typography()
            UiStyle.PREMIUM, UiStyle.MYSTIC -> PremiumType
            UiStyle.MINIMAL -> MinimalType
            UiStyle.GLASS -> GlassType
            UiStyle.NOTEBOOK -> NotebookType
        },
        shapes = when (style) {
            UiStyle.CLASSIC -> Shapes()
            UiStyle.PREMIUM, UiStyle.MYSTIC -> PremiumShapes
            UiStyle.MINIMAL -> MinimalShapes
            UiStyle.GLASS -> GlassShapes
            UiStyle.NOTEBOOK -> NotebookShapes
        },
    ) {
        CompositionLocalProvider(LocalUiStyle provides style) {
            // Surface provides the content colour; without it bare Text stays black on dark backgrounds.
            if (!hasBackdrop(style)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
            } else {
                Surface(Modifier.fillMaxSize().backdrop(style, dark), color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground, content = content)
            }
        }
    }
}
