package dev.inknotes.math

import dev.inknotes.data.tr

/** The keys of the formula keyboard, by tab: everything a formula can hold. */
object FormulaKeys {
    /**
     * A key of the formula keyboard: what it shows ([face]), what it is called ([name], also what
     * search finds), and what it puts in: a template with empty places (#0, #1… in [pattern]) or
     * plain [text].
     */
    /** [art]: the face set in print (a fraction stacked, with its bar) instead of [face] as text. */
    class Key(val face: String, val name: String, val text: String? = null, val pattern: String? = null, val art: String? = null) {
        fun press(c: Composer) {
            if (pattern != null) c.template(pattern) else if (text != null) c.type(text)
        }
    }

    private fun t(face: String, name: String, text: String = face) = Key(face, name, text = text)
    private fun p(face: String, name: String, pattern: String, art: String? = null) = Key(face, name, pattern = pattern, art = art)

    /** The tabs of templates and signs: everything a formula can hold, by kind. */
    fun tabs(upper: Boolean): List<Pair<String, List<Key>>> {
        val latin = ('a'..'z').map { if (upper) it.uppercaseChar() else it }.map { t(it.toString(), tr("буква $it", "letter $it")) }
        val greekLow = "αβγδεζηθικλμνξοπρστυφχψω"
        val greekUp = "ΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡΣΤΥΦΧΨΩ"
        val greekNames = listOf(
            tr("альфа", "alpha"), tr("бета", "beta"), tr("гамма", "gamma"), tr("дельта", "delta"), tr("эпсилон", "epsilon"), tr("дзета", "zeta"),
            tr("эта", "eta"), tr("тета", "theta"), tr("йота", "iota"), tr("каппа", "kappa"), tr("лямбда", "lambda"), tr("мю", "mu"),
            tr("ню", "nu"), tr("кси", "xi"), tr("омикрон", "omicron"), tr("пи", "pi"), tr("ро", "rho"), tr("сигма", "sigma"),
            tr("тау", "tau"), tr("ипсилон", "upsilon"), tr("фи", "phi"), tr("хи", "chi"), tr("пси", "psi"), tr("омега", "omega"),
        )
        val greek = greekLow.indices.map { k -> val ch = (if (upper) greekUp else greekLow)[k].toString(); t(ch, greekNames[k]) }
        return listOf(
            tr("Структура", "Structure") to listOf(
                p("□⁄□", tr("дробь", "fraction"), "\\frac{#0}{#1}", art = "\\frac{□}{□}"),
                p("□ⁿ", tr("степень", "power"), "^{#0}"),
                p("□ₙ", tr("индекс", "index"), "_{#0}"),
                p("□ₙᵐ", tr("индекс и степень", "index and power"), "_{#0}^{#1}"),
                p("√□", tr("корень", "root"), "\\sqrt{#0}"),
                p("ⁿ√□", tr("корень n-й степени", "n-th root"), "\\sqrt[#0]{#1}"),
                p("( □ )", tr("круглые скобки", "parentheses"), "(#0)"),
                p("[ □ ]", tr("квадратные скобки", "brackets"), "[#0]"),
                p("|□|", tr("модуль", "absolute value"), "|#0|"),
                p("⌊□⌋", tr("целая часть", "floor"), "\\lfloor #0\\rfloor"),
                p("⌈□⌉", tr("округление вверх", "ceiling"), "\\lceil #0\\rceil"),
                t("□!", tr("факториал", "factorial"), "!"),
                p("Cₙᵏ", tr("сочетания", "binomial"), "\\binom{#0}{#1}"),
                t("□′", tr("штрих", "prime"), "'"),
                t("□, □", tr("запятая", "comma"), ","),
                t("□; □", tr("точка с запятой", "semicolon"), ";"),
            ),
            tr("Анализ", "Calculus") to listOf(
                p("∫□ d□", tr("интеграл", "integral"), "\\int #0 d#1"),
                p("∫ₐᵇ", tr("интеграл с пределами", "definite integral"), "\\int_{#0}^{#1}#2 d#3"),
                p("∬", tr("двойной интеграл", "double integral"), "\\iint #0"),
                p("∭", tr("тройной интеграл", "triple integral"), "\\iiint #0"),
                p("∮", tr("интеграл по контуру", "contour integral"), "\\oint #0"),
                p("□|ₐᵇ", tr("подстановка", "evaluated at"), "\\Big|_{#0}^{#1}"),
                p("d/d□", tr("производная", "derivative"), "\\frac{d}{d#0}(#1)", art = "\\frac{d}{d□}"),
                p("dⁿ/dxⁿ", tr("производная n-го порядка", "n-th derivative"), "\\frac{d^{#0}}{d#1^{#0}}(#2)", art = "\\frac{d^{n}}{dx^{n}}"),
                p("∂□/∂□", tr("частная производная", "partial derivative"), "\\frac{\\partial #0}{\\partial #1}", art = "\\frac{∂□}{∂□}"),
                p("lim", tr("предел", "limit"), "\\lim_{#0\\to #1}#2"),
                p("lim⁺", tr("односторонний предел", "one-sided limit"), "\\lim_{#0\\to #1^{+}}#2"),
                p("Σ", tr("сумма", "sum"), "\\sum_{#0}^{#1}#2"),
                p("Π", tr("произведение", "product"), "\\prod_{#0}^{#1}#2"),
                t("∞", tr("бесконечность", "infinity")),
                t("→", tr("стремится", "tends to"), "\\to "),
                t("∇", tr("набла", "nabla"), "\\nabla "),
                t("Δ", tr("приращение", "increment")),
                t("dx", tr("дифференциал", "differential"), "d"),
            ),
            tr("Функции", "Functions") to listOf(
                p("sin", tr("синус", "sine"), "sin(#0)"), p("cos", tr("косинус", "cosine"), "cos(#0)"),
                p("tg", tr("тангенс", "tangent"), "tg(#0)"), p("ctg", tr("котангенс", "cotangent"), "ctg(#0)"),
                p("arcsin", tr("арксинус", "arcsine"), "arcsin(#0)"), p("arccos", tr("арккосинус", "arccosine"), "arccos(#0)"),
                p("arctg", tr("арктангенс", "arctangent"), "arctg(#0)"), p("arcctg", tr("арккотангенс", "arccotangent"), "arcctg(#0)"),
                p("sh", tr("гиперболический синус", "hyperbolic sine"), "sinh(#0)"), p("ch", tr("гиперболический косинус", "hyperbolic cosine"), "cosh(#0)"),
                p("th", tr("гиперболический тангенс", "hyperbolic tangent"), "tanh(#0)"), p("ln", tr("натуральный логарифм", "natural log"), "ln(#0)"),
                p("lg", tr("десятичный логарифм", "common log"), "lg(#0)"), p("log□", tr("логарифм по основанию", "log to a base"), "log_{#0}(#1)"),
                p("eˣ", tr("экспонента", "exponential"), "e^{#0}"), p("sgn", tr("знак", "sign"), "\\sgn(#0)"),
                p("max", tr("максимум", "maximum"), "max(#0)"), p("min", tr("минимум", "minimum"), "min(#0)"),
            ),
            tr("Графики", "Graphs") to listOf(
                t("y =", tr("график y от x", "graph y of x"), "y="),
                t("f(x) =", tr("функция", "function"), "f(x)="),
                t("g(x) =", tr("вторая функция", "second function"), "g(x)="),
                t("z =", tr("поверхность", "surface"), "z="),
                t("a =", tr("параметр", "parameter"), "a="),
                p("f(□)", tr("значение функции", "function value"), "f(#0)"),
                t("f′(x)", tr("производная f", "derivative of f"), "f'(x)"),
                t("x", tr("переменная x", "variable x")),
                t("ℝ", tr("все числа", "all reals")),
            ),
            tr("Буквы", "Letters") to latin + Key("⇧", if (upper) tr("строчные", "lower case") else tr("заглавные", "upper case")),
            tr("Греческие", "Greek") to greek + Key("⇧", if (upper) tr("строчные", "lower case") else tr("заглавные", "upper case")),
            tr("Множества, логика", "Sets, logic") to listOf(
                t("∈", tr("принадлежит", "element of")), t("∉", tr("не принадлежит", "not element of")),
                t("⊂", tr("подмножество", "subset")), t("⊆", tr("подмножество или равно", "subset or equal")),
                t("∪", tr("объединение", "union")), t("∩", tr("пересечение", "intersection")),
                t("∖", tr("разность множеств", "set difference")), t("∅", tr("пустое множество", "empty set")),
                t("ℕ", tr("натуральные", "naturals")), t("ℤ", tr("целые", "integers")),
                t("ℚ", tr("рациональные", "rationals")), t("ℝ", tr("вещественные", "reals")),
                t("ℂ", tr("комплексные", "complex")), t("∀", tr("для всех", "for all")),
                t("∃", tr("существует", "exists")), t("¬", tr("не", "not")),
                t("∧", tr("и", "and")), t("∨", tr("или", "or")),
            ),
            tr("Векторы, матрицы", "Vectors, matrices") to listOf(
                p("a⃗", tr("вектор", "vector"), "\\vec{#0}"), p("x̄", tr("черта сверху", "bar"), "\\bar{#0}"),
                p("x̂", tr("крышка", "hat"), "\\hat{#0}"), p("ẋ", tr("точка сверху", "dot"), "\\dot{#0}"),
                p("ẍ", tr("две точки сверху", "double dot"), "\\ddot{#0}"), p("‖□‖", tr("норма", "norm"), "‖#0‖"),
                t("a·b", tr("скалярное произведение", "dot product"), "·"), t("a×b", tr("векторное произведение", "cross product"), "×"),
                p("⟨□,□⟩", tr("угловые скобки", "angle brackets"), "⟨#0,#1⟩"),
                p("2×2", tr("матрица 2×2", "2×2 matrix"), "\\mat{#0&#1\\\\#2&#3}"),
                p("3×3", tr("матрица 3×3", "3×3 matrix"), "\\mat{#0&#1&#2\\\\#3&#4&#5\\\\#6&#7&#8}"),
                p("2×1", tr("столбец", "column"), "\\mat{#0\\\\#1}"),
                p("det", tr("определитель 2×2", "2×2 determinant"), "\\det{\\mat{#0&#1\\\\#2&#3}}"),
                p("det₃", tr("определитель 3×3", "3×3 determinant"), "\\det{\\mat{#0&#1&#2\\\\#3&#4&#5\\\\#6&#7&#8}}"),
                t("Aᵀ", tr("транспонирование", "transpose"), "^{T}"), t("A⁻¹", tr("обратная матрица", "inverse"), "^{-1}"),
                t("∠", tr("угол", "angle")), t("⊥", tr("перпендикулярно", "perpendicular")),
            ),
            tr("Сравнения, знаки", "Relations, signs") to listOf(
                t("≠", tr("не равно", "not equal")), t("≈", tr("примерно равно", "approximately")),
                t("≡", tr("тождественно", "identical")), t("∼", tr("эквивалентно", "equivalent")),
                t("≤", tr("меньше или равно", "less or equal")), t("≥", tr("больше или равно", "greater or equal")),
                t("<", tr("меньше", "less")), t(">", tr("больше", "greater")),
                t("±", tr("плюс-минус", "plus-minus")), t("∓", tr("минус-плюс", "minus-plus")),
                t("∝", tr("пропорционально", "proportional")), t("⇒", tr("следует", "implies")),
                t("⇔", tr("равносильно", "equivalent to")), t("°", tr("градус", "degree")),
                t("%", tr("процент", "percent")), t("∥", tr("параллельно", "parallel")),
                t("π", tr("пи", "pi")), t("e", tr("число e", "e")),
            ),
            tr("Единицы", "Units") to listOf(
                t(tr("м", "m"), tr("метр", "metre")), t(tr("см", "cm"), tr("сантиметр", "centimetre")),
                t(tr("мм", "mm"), tr("миллиметр", "millimetre")), t(tr("км", "km"), tr("километр", "kilometre")),
                t(tr("с", "s"), tr("секунда", "second")), t(tr("мин", "min"), tr("минута", "minute")),
                t(tr("ч", "h"), tr("час", "hour")), t(tr("кг", "kg"), tr("килограмм", "kilogram")),
                t(tr("г", "g"), tr("грамм", "gram")), t(tr("Н", "N"), tr("ньютон", "newton")),
                t(tr("Дж", "J"), tr("джоуль", "joule")), t(tr("Вт", "W"), tr("ватт", "watt")),
                t(tr("Па", "Pa"), tr("паскаль", "pascal")), t(tr("В", "V"), tr("вольт", "volt")),
                t(tr("А", "A"), tr("ампер", "ampere")), t(tr("Ом", "Ω"), tr("ом", "ohm")),
                t(tr("Гц", "Hz"), tr("герц", "hertz")), t(tr("моль", "mol"), tr("моль", "mole")),
            ),
        )
    }
}
