package dev.inknotes.math

/** Simple handwriting-like shapes of symbols in a 10×20 box (y down); each list is one stroke. */
object TestGlyphs {
    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float) = (0..24).map { k ->
        // Drawn like a written 0: from the top, counter-clockwise.
        val a = -Math.PI / 2 - Math.PI * 2 * k / 24
        (cx + rx * Math.cos(a).toFloat()) to (cy + ry * Math.sin(a).toFloat())
    }

    val shapes: Map<Char, List<List<Pair<Float, Float>>>> = mapOf(
        '0' to listOf(ellipse(5f, 10f, 4.5f, 9.5f)),
        '1' to listOf(listOf(2f to 5f, 6f to 0f, 6f to 20f)),
        '2' to listOf(listOf(1f to 5f, 2f to 1.5f, 5f to 0f, 8.5f to 1.5f, 9f to 5f, 7f to 10f, 1f to 20f, 10f to 20f)),
        '3' to listOf(listOf(1f to 2f, 4f to 0f, 8f to 1f, 9f to 4.5f, 5f to 9f, 9f to 12f, 9.5f to 16f, 6f to 20f, 1.5f to 19f)),
        '4' to listOf(listOf(7f to 20f, 7f to 0f, 0f to 14f, 10f to 14f)),
        '5' to listOf(listOf(9f to 0f, 2f to 0f, 1.5f to 9f, 5f to 7.5f, 8.5f to 10f, 9f to 15f, 6.5f to 19.5f, 3f to 20f, 0.5f to 17.5f)),
        '6' to listOf(listOf(8.5f to 1f, 5f to 0f, 1.5f to 5f, 0.5f to 13f, 2f to 19f, 5f to 20f, 8.5f to 17.5f, 9f to 13f, 6f to 10f, 2.5f to 11f, 0.8f to 14f)),
        '7' to listOf(listOf(0f to 0f, 10f to 0f, 4f to 20f)),
        '8' to listOf(listOf(8f to 2f, 5f to 0f, 2f to 2f, 2.5f to 6f, 8f to 13f, 8.5f to 17f, 5f to 20f, 1.5f to 17f, 2f to 13f, 8f to 6f, 8f to 2f)),
        '9' to listOf(listOf(9f to 6f, 6f to 9f, 2f to 8f, 1f to 4f, 4f to 0f, 8f to 1f, 9f to 6f, 8f to 20f)),
        '+' to listOf(listOf(5f to 5f, 5f to 17f), listOf(-1f to 11f, 11f to 11f)),
        '-' to listOf(listOf(0f to 11f, 10f to 11f)),
        'x' to listOf(listOf(1f to 8f, 9f to 20f), listOf(9f to 8f, 1f to 20f)),
        'y' to listOf(listOf(1f to 8f, 5f to 16f), listOf(9f to 8f, 5f to 16f, 2f to 26f)),
        'd' to listOf(ellipse(5f, 14f, 4f, 6f), listOf(9f to 0f, 9f to 20f)),
        '(' to listOf(listOf(7f to 0f, 3f to 5f, 2f to 10f, 3f to 15f, 7f to 20f)),
        ')' to listOf(listOf(3f to 0f, 7f to 5f, 8f to 10f, 7f to 15f, 3f to 20f)),
        '\'' to listOf(listOf(6f to -2f, 4f to 5f)),
        '=' to listOf(listOf(0f to 8f, 10f to 8f), listOf(0f to 12.5f, 10f to 12.5f)),
    )

    /** Strokes of [text] at ([x], [top]) with symbols [h] tall, and the x after it. */
    fun write(text: String, x: Float, top: Float, h: Float): Pair<List<List<Pair<Float, Float>>>, Float> {
        val k = h / 20f
        var cx = x
        val out = ArrayList<List<Pair<Float, Float>>>()
        for (c in text) {
            if (c == ' ') {
                cx += 0.5f * h
                continue
            }
            for (s in shapes.getValue(c)) out += s.map { (px, py) -> cx + px * k to top + py * k }
            cx += when (c) {
                '=' -> 0.75f
                '\'' -> 0.4f
                else -> 0.72f
            } * h
        }
        return out to cx
    }
}
