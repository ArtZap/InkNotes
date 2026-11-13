package dev.inknotes.data

/** Where a button of the editor is: the tool bar, the bar above it, the "⋮" menu, or nowhere. */
enum class Zone(val title: String) {
    TOOLS("Панель инструментов"),
    TOP("Верхняя панель"),
    MENU("Меню ⋮"),
    HIDDEN("Скрыто"),
}

/**
 * Which buttons of the editor go where, in what order: the user's own arrangement (kept on
 * the device, the same for every notebook). Buttons in no list are hidden; they are still
 * reachable from the menu's "Все функции…".
 */
data class ToolbarLayout(val tools: List<String>, val top: List<String>, val menu: List<String>) {

    fun list(zone: Zone): List<String> = when (zone) {
        Zone.TOOLS -> tools
        Zone.TOP -> top
        Zone.MENU -> menu
        Zone.HIDDEN -> Toolbars.ALL.filter { it !in tools && it !in top && it !in menu }
    }

    fun zoneOf(id: String): Zone = when (id) {
        in tools -> Zone.TOOLS
        in top -> Zone.TOP
        in menu -> Zone.MENU
        else -> Zone.HIDDEN
    }

    val hidden: List<String> get() = list(Zone.HIDDEN)

    /**
     * [id] moved to [zone] at [index] (its end when out of range). Buttons that must stay in
     * sight (undo, the help) are not put away into the menu or hidden: the layout stays as it was.
     */
    fun moved(id: String, zone: Zone, index: Int = Int.MAX_VALUE): ToolbarLayout {
        if (id !in Toolbars.ALL || zone !in Toolbars.allowed(id)) return this
        fun without(l: List<String>) = l.filter { it != id }
        fun into(l: List<String>) = without(l).toMutableList().apply { add(index.coerceIn(0, size), id) }
        return ToolbarLayout(
            if (zone == Zone.TOOLS) into(tools) else without(tools),
            if (zone == Zone.TOP) into(top) else without(top),
            if (zone == Zone.MENU) into(menu) else without(menu),
        )
    }

    /**
     * `tools=pen,eraser;top=undo;menu=help;seen=…`: kept in the app's settings, with the list of
     * buttons known now, so that a later version can tell its new buttons apart.
     */
    fun encode(): String =
        "tools=${tools.joinToString(",")};top=${top.joinToString(",")};menu=${menu.joinToString(",")};seen=${Toolbars.ALL.joinToString(",")}"

    companion object {
        /**
         * A layout from settings; unknown buttons are dropped, repeats kept once, and buttons
         * added to the app since it was saved go where the full layout has them.
         */
        fun decode(text: String?): ToolbarLayout {
            if (text.isNullOrBlank()) return Toolbars.FULL
            val parts = text.split(';').mapNotNull { p ->
                val k = p.substringBefore('=', "")
                if (k.isEmpty()) null else k to p.substringAfter('=').split(',').filter { it.isNotBlank() }
            }.toMap()
            val known = parts.keys.containsAll(listOf("tools", "top", "menu"))
            if (!known) return Toolbars.FULL
            return normalized(parts["tools"].orEmpty(), parts["top"].orEmpty(), parts["menu"].orEmpty(), parts["seen"])
        }

        internal fun normalized(tools: List<String>, top: List<String>, menu: List<String>, seen: List<String>? = null): ToolbarLayout {
            val used = HashSet<String>()
            fun clean(l: List<String>) = l.filter { it in Toolbars.ALL && used.add(it) }
            var layout = ToolbarLayout(clean(tools), clean(top), clean(menu))
            // Buttons the saved layout has never heard of: where the full layout has them.
            val known = seen?.toSet() ?: Toolbars.KNOWN_AT_START
            for (id in Toolbars.ALL) if (id !in used && id !in known) layout = layout.moved(id, Toolbars.FULL.zoneOf(id))
            // Buttons that must stay in sight.
            for (id in Toolbars.ALL) {
                val z = layout.zoneOf(id)
                if (z !in Toolbars.allowed(id)) layout = layout.moved(id, Toolbars.FULL.zoneOf(id))
            }
            return layout
        }
    }
}

/** The editor's buttons and ready-made layouts. */
object Toolbars {
    /** Every button, in the order of the full layout. */
    val ALL = listOf(
        // Writing tools.
        "pen", "highlighter", "neon", "eraser", "lasso", "area",
        // The bar above.
        "spoiler", "laser", "picture", "table", "stamps", "ruler", "undo", "redo", "pages", "search", "math", "page-nav", "add-page",
        // The menu.
        "text", "space", "page-text", "toc", "layers", "cards", "record", "recordings",
        "background", "page-size", "page-shift", "fractions", "units", "share", "save", "pdf", "theme", "pen-button", "scribble", "help",
        // Added in 0.32.
        "zoom-window", "scan", "pdf-share", "print", "pressure",
        // Added in 0.33.
        "formula", "protractor", "compass", "timer",
        // Added in 0.34.
        "pdf-text", "pdf-page", "pdf-dark",
        // Added in 0.36.
        "side", "swap",
    )

    /** Buttons there were when layouts could first be saved (later ones are placed by [FULL] once). */
    val KNOWN_AT_START: Set<String> = ALL.toSet() - setOf("zoom-window", "scan", "pdf-share", "print", "pressure", "formula", "protractor", "compass", "timer", "pdf-text", "pdf-page", "pdf-dark", "side", "swap")

    /** Undo always stays on a bar; the help is never hidden (the menu is enough for it). */
    fun allowed(id: String): Set<Zone> = when (id) {
        "undo" -> setOf(Zone.TOOLS, Zone.TOP)
        "help" -> setOf(Zone.TOOLS, Zone.TOP, Zone.MENU)
        else -> Zone.entries.toSet()
    }

    val FULL = ToolbarLayout(
        tools = listOf("pen", "highlighter", "neon", "eraser", "lasso", "area"),
        top = listOf("spoiler", "laser", "picture", "table", "stamps", "ruler", "zoom-window", "undo", "redo", "pages", "search", "math", "page-nav", "add-page"),
        menu = listOf(
            "text", "space", "scan", "formula", "protractor", "compass", "page-text", "toc", "layers", "cards", "timer", "record", "recordings",
            "pdf-text", "pdf-page", "pdf-dark", "side", "swap",
            "background", "page-size", "page-shift", "fractions", "units", "pdf-share", "pdf", "print", "share", "save", "theme", "pen-button", "pressure", "scribble", "help",
        ),
    )

    enum class Preset(val title: String, val note: String, val layout: ToolbarLayout) {
        MINIMUM(
            "Минимум",
            "Ручка, маркер, ластик, лассо и страницы; остальное — в «Все функции…»",
            ToolbarLayout(
                tools = listOf("pen", "highlighter", "eraser", "lasso"),
                top = listOf("undo", "redo", "pages", "page-nav", "add-page"),
                menu = listOf("picture", "search", "text", "zoom-window", "pdf-page", "background", "page-size", "pdf-share", "share", "pdf", "help"),
            ),
        ),
        NOTES(
            "Конспекты",
            "Лекции: маркер подсказок и карточки, звук, оглавление, поиск",
            ToolbarLayout(
                tools = listOf("pen", "highlighter", "spoiler", "eraser", "lasso"),
                top = listOf("picture", "record", "zoom-window", "undo", "redo", "pages", "search", "page-nav", "add-page"),
                menu = listOf(
                    "text", "space", "scan", "toc", "cards", "timer", "recordings", "page-text", "layers", "table", "formula", "pdf-text", "pdf-page", "pdf-dark", "side", "swap",
                    "background", "page-size", "pdf-share", "pdf", "print", "share", "save", "theme", "scribble", "help",
                ),
            ),
        ),
        MATH(
            "Математика и физика",
            "Ответы после «=», линейка, штампы, таблицы, единицы измерения",
            ToolbarLayout(
                tools = listOf("pen", "highlighter", "eraser", "lasso", "area"),
                top = listOf("math", "formula", "ruler", "protractor", "compass", "stamps", "table", "picture", "undo", "redo", "pages", "page-nav", "add-page"),
                menu = listOf(
                    "units", "fractions", "text", "space", "zoom-window", "scan", "search", "toc", "layers", "spoiler", "cards", "timer", "pdf-text", "pdf-page", "pdf-dark", "side", "swap",
                    "background", "page-size", "page-shift", "pdf-share", "pdf", "print", "share", "save", "theme", "help",
                ),
            ),
        ),
        FULL("Всё", "Все инструменты на виду, как раньше", Toolbars.FULL),
    }
}
