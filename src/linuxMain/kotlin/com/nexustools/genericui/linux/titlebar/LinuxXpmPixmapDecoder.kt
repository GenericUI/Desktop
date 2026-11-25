package com.nexustools.genericui.linux.titlebar

import com.nexustools.genericui.style.color.RgbaColor

/**
 * Pure-Kotlin ASCII XPM3 (`/* XPM */`) pixmap decoder converting Xfwm4 `.xpm` sprites into
 * 32-bit ARGB (`0xAARRGGBB`) [LinuxXfwm4SpriteSet] buffers with Xfwm4 `themerc` symbolic color support.
 */
object LinuxXpmPixmapDecoder {

    private val XPM_COLOR_MODE_KEYS = setOf("c", "g", "g4", "m", "s")

    private val EXTRA_X11_COLORS: Map<String, RgbaColor> = mapOf(
        "none" to RgbaColor.TRANSPARENT,
        "black" to RgbaColor(0, 0, 0, 255),
        "white" to RgbaColor(255, 255, 255, 255),
        "red" to RgbaColor(255, 0, 0, 255),
        "green" to RgbaColor(0, 255, 0, 255),
        "blue" to RgbaColor(0, 0, 255, 255),
        "yellow" to RgbaColor(255, 255, 0, 255),
        "cyan" to RgbaColor(0, 255, 255, 255),
        "magenta" to RgbaColor(255, 0, 255, 255),
        "gray" to RgbaColor(190, 190, 190, 255),
        "grey" to RgbaColor(190, 190, 190, 255),
        "darkgray" to RgbaColor(169, 169, 169, 255),
        "darkgrey" to RgbaColor(169, 169, 169, 255),
        "lightgray" to RgbaColor(211, 211, 211, 255),
        "lightgrey" to RgbaColor(211, 211, 211, 255),
        "dimgray" to RgbaColor(105, 105, 105, 255),
        "dimgrey" to RgbaColor(105, 105, 105, 255),
        "navy" to RgbaColor(0, 0, 128, 255),
        "silver" to RgbaColor(192, 192, 192, 255)
    )

    fun decode(
        xpmText: String,
        symbolicColors: Map<String, RgbaColor> = emptyMap()
    ): LinuxXfwm4SpriteSet {
        val strings = extractQuotedStrings(xpmText)
        require(strings.isNotEmpty()) { "Invalid XPM data: no quoted strings found" }

        val headerTokens = strings[0].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        require(headerTokens.size >= 4) { "Invalid XPM header: '${strings[0]}'" }

        val width = headerTokens[0].toIntOrNull()
            ?: throw IllegalArgumentException("Invalid XPM width: '${headerTokens[0]}'")
        val height = headerTokens[1].toIntOrNull()
            ?: throw IllegalArgumentException("Invalid XPM height: '${headerTokens[1]}'")
        val numColors = headerTokens[2].toIntOrNull()
            ?: throw IllegalArgumentException("Invalid XPM color count: '${headerTokens[2]}'")
        val cpp = headerTokens[3].toIntOrNull()
            ?: throw IllegalArgumentException("Invalid XPM chars-per-pixel: '${headerTokens[3]}'")

        require(width > 0 && height > 0 && numColors > 0 && cpp > 0) {
            "Invalid XPM dimensions/palette: width=$width height=$height colors=$numColors cpp=$cpp"
        }
        val expectedStrings = 1 + numColors + height
        require(strings.size >= expectedStrings) {
            "Truncated XPM data: expected at least $expectedStrings strings, got ${strings.size}"
        }

        val palette = HashMap<String, Int>(numColors * 2)
        for (cIdx in 0 until numColors) {
            val line = strings[1 + cIdx]
            require(line.length >= cpp) {
                "Invalid XPM color entry at index $cIdx: shorter than cpp=$cpp"
            }
            val symbolKey = line.substring(0, cpp)
            val spec = line.substring(cpp)
            palette[symbolKey] = resolveXpmColorEntry(spec, symbolicColors)
        }

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = strings[1 + numColors + y]
            val rowOffset = y * width
            for (x in 0 until width) {
                val charStart = x * cpp
                if (charStart + cpp <= row.length) {
                    val code = row.substring(charStart, charStart + cpp)
                    pixels[rowOffset + x] = palette[code] ?: 0
                } else {
                    pixels[rowOffset + x] = 0
                }
            }
        }

        return LinuxXfwm4SpriteSet(pixels = pixels, width = width, height = height)
    }

    fun decodeOrNull(
        xpmText: String,
        symbolicColors: Map<String, RgbaColor> = emptyMap()
    ): LinuxXfwm4SpriteSet? = runCatching { decode(xpmText, symbolicColors) }.getOrNull()

    private fun extractQuotedStrings(xpmText: String): List<String> {
        val result = ArrayList<String>()
        var i = 0
        val len = xpmText.length
        while (i < len) {
            val c = xpmText[i]
            if (c == '/' && i + 1 < len && xpmText[i + 1] == '*') {
                val end = xpmText.indexOf("*/", i + 2)
                i = if (end < 0) len else end + 2
                continue
            }
            if (c == '/' && i + 1 < len && xpmText[i + 1] == '/') {
                val nl = xpmText.indexOf('\n', i + 2)
                i = if (nl < 0) len else nl + 1
                continue
            }
            if (c == '"') {
                i++
                val sb = StringBuilder()
                while (i < len) {
                    val ch = xpmText[i]
                    if (ch == '\\' && i + 1 < len) {
                        sb.append(xpmText[i + 1])
                        i += 2
                    } else if (ch == '"') {
                        i++
                        break
                    } else {
                        sb.append(ch)
                        i++
                    }
                }
                result.add(sb.toString())
                continue
            }
            i++
        }
        return result
    }

    private fun resolveXpmColorEntry(
        spec: String,
        symbolicColors: Map<String, RgbaColor>
    ): Int {
        val tokens = spec.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return 0

        var colorSpec: String? = null
        var graySpec: String? = null
        var monoSpec: String? = null
        var symbolicName: String? = null

        var i = 0
        while (i < tokens.size) {
            val mode = tokens[i].lowercase()
            if (mode in XPM_COLOR_MODE_KEYS && i + 1 < tokens.size) {
                i++
                val valParts = ArrayList<String>()
                while (i < tokens.size && tokens[i].lowercase() !in XPM_COLOR_MODE_KEYS) {
                    valParts.add(tokens[i])
                    i++
                }
                val joined = valParts.joinToString(" ")
                when (mode) {
                    "c" -> colorSpec = joined
                    "g", "g4" -> if (graySpec == null) graySpec = joined
                    "m" -> if (monoSpec == null) monoSpec = joined
                    "s" -> symbolicName = joined
                }
            } else {
                i++
            }
        }

        if (symbolicName != null) {
            symbolicColors[symbolicName]?.let { return it.toArgbInt() }
            symbolicColors.entries.firstOrNull { it.key.equals(symbolicName, ignoreCase = true) }
                ?.value?.let { return it.toArgbInt() }
        }

        val chosen = colorSpec ?: graySpec ?: monoSpec ?: return 0
        return parseXpmColorValue(chosen).toArgbInt()
    }

    private fun parseXpmColorValue(raw: String): RgbaColor {
        val cleaned = raw.trim()
        val lower = cleaned.lowercase()
        if (lower == "none") return RgbaColor.TRANSPARENT

        EXTRA_X11_COLORS[lower]?.let { return it }

        val grayMatch = Regex("""gr[ae]y(\d{1,3})""").matchEntire(lower)
        if (grayMatch != null) {
            val pct = grayMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 100) ?: 50
            val v = (pct * 255 + 50) / 100
            return RgbaColor(v, v, v, 255)
        }

        if (cleaned.startsWith("#")) {
            val hex = cleaned.substring(1)
            when (hex.length) {
                3, 6 -> RgbaColor.fromCss(cleaned)?.let { return it }
                9 -> {
                    val r = hex.substring(0, 2).toIntOrNull(16)
                    val g = hex.substring(3, 5).toIntOrNull(16)
                    val b = hex.substring(6, 8).toIntOrNull(16)
                    if (r != null && g != null && b != null) return RgbaColor(r, g, b, 255)
                }
                12 -> {
                    val r = hex.substring(0, 2).toIntOrNull(16)
                    val g = hex.substring(4, 6).toIntOrNull(16)
                    val b = hex.substring(8, 10).toIntOrNull(16)
                    if (r != null && g != null && b != null) return RgbaColor(r, g, b, 255)
                }
            }
        }

        return RgbaColor.fromCss(cleaned) ?: RgbaColor.TRANSPARENT
    }
}
