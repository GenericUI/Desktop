@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux.titlebar

import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.codec.LibPngCodec
import com.nexustools.genericui.linux.LinuxPosixPaths
import com.nexustools.genericui.style.color.RgbaColor
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Loads Xfwm4 window decoration themes (`themerc` + `.xpm` / `.png` sprites),
 * reusing `:common`'s [LibPngCodec] for PNG decoding and [LinuxXpmPixmapDecoder] for ASCII XPM sprites.
 */
object LinuxXfwm4ThemeLoader {

    data class LoadResult(
        val assets: LinuxXfwm4ThemeAssets?,
        val errorReason: String?
    )

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    private val SYMBOLIC_THEMERC_KEYS = listOf(
        "active_color_1",
        "active_color_2",
        "active_hilight_1",
        "active_hilight_2",
        "active_shadow_1",
        "active_shadow_2",
        "active_mid_1",
        "active_mid_2",
        "inactive_color_1",
        "inactive_color_2",
        "inactive_hilight_1",
        "inactive_hilight_2",
        "inactive_shadow_1",
        "inactive_shadow_2",
        "inactive_mid_1",
        "inactive_mid_2",
        "active_text_color",
        "inactive_text_color",
        "active_text_shadow_color",
        "inactive_text_shadow_color",
        "active_border_color",
        "inactive_border_color"
    )

    private val BUTTON_FILE_PREFIXES: List<Pair<LinuxTitleButtonKind, String>> = listOf(
        LinuxTitleButtonKind.MENU to "menu",
        LinuxTitleButtonKind.MINIMIZE to "hide",
        LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE to "maximize",
        LinuxTitleButtonKind.CLOSE to "close",
        LinuxTitleButtonKind.SHADE to "shade",
        LinuxTitleButtonKind.ON_ALL_DESKTOPS to "stick"
    )

    fun loadFromSystemThemes(themeName: String): LoadResult {
        val candidates = resolveSystemThemeDirectories(themeName)
        for (dir in candidates) {
            if (LinuxPosixPaths.isDirectory(dir)) {
                val res = loadFromDirectory(themeName, dir)
                if (res.assets != null) return res
            }
        }
        return LoadResult(
            assets = null,
            errorReason = "Xfwm4 theme '$themeName' directory not found in ${candidates.joinToString(", ")}"
        )
    }

    fun loadFromDirectory(themeName: String, themeDir: String): LoadResult {
        val clean = LinuxPosixPaths.trimTrailingSeparators(themeDir)
        val xfwm4Dir = if (clean.endsWith("/xfwm4") && LinuxPosixPaths.isDirectory(clean)) {
            clean
        } else if (LinuxPosixPaths.isDirectory("$clean/xfwm4")) {
            "$clean/xfwm4"
        } else {
            clean
        }

        if (!LinuxPosixPaths.isDirectory(xfwm4Dir)) {
            return LoadResult(
                assets = null,
                errorReason = "Xfwm4 theme directory '$xfwm4Dir' does not exist"
            )
        }

        val files = LinkedHashMap<String, ByteArray>()
        val stems = collectCandidateStems()
        LinuxPosixPaths.readAllBytes("$xfwm4Dir/themerc")?.let { files["themerc"] = it }
        for (stem in stems) {
            LinuxPosixPaths.readAllBytes("$xfwm4Dir/$stem.png")?.let { files["$stem.png"] = it }
            LinuxPosixPaths.readAllBytes("$xfwm4Dir/$stem.xpm")?.let { files["$stem.xpm"] = it }
        }
        return loadFromFileMap(themeName, files)
    }

    fun loadFromFileMap(themeName: String, files: Map<String, ByteArray>): LoadResult {
        if (files.isEmpty()) {
            return LoadResult(
                assets = null,
                errorReason = "No Xfwm4 theme files found for '$themeName'"
            )
        }

        val normalized = LinkedHashMap<String, ByteArray>(files.size)
        for ((k, v) in files) {
            normalized[k.substringAfterLast('/')] = v
        }

        val themercBytes = normalized["themerc"]
        val properties = if (themercBytes != null) {
            LinuxIniAndXmlConfigParser.parseKeyValueLines(themercBytes.decodeToString())
        } else {
            emptyMap()
        }

        val symbolicColors = LinkedHashMap<String, RgbaColor>()
        for (key in SYMBOLIC_THEMERC_KEYS) {
            val col = LinuxIniAndXmlConfigParser.parseColor(properties[key])
            if (col != null) {
                symbolicColors[key] = col
            }
        }

        val errors = ArrayList<String>()

        fun decodeStem(stem: String): LinuxXfwm4SpriteSet? {
            val xpmBytes = normalized["$stem.xpm"]
            val pngBytes = normalized["$stem.png"]
            val xpmSprite = if (xpmBytes != null) {
                try {
                    LinuxXpmPixmapDecoder.decode(xpmBytes.decodeToString(), symbolicColors)
                } catch (t: Throwable) {
                    errors.add("$stem.xpm: ${t.message ?: t::class.simpleName}")
                    null
                }
            } else null

            val pngSprite = if (pngBytes != null) {
                try {
                    decodePngSprite(pngBytes)
                } catch (t: Throwable) {
                    errors.add("$stem.png: ${t.message ?: t::class.simpleName}")
                    null
                }
            } else null

            return when {
                pngSprite != null && xpmSprite != null -> compositeSprites(xpmSprite, pngSprite)
                pngSprite != null -> pngSprite
                xpmSprite != null -> xpmSprite
                else -> null
            }
        }

        val topLeftActive = decodeStem("top-left-active")
        val topLeftInactive = decodeStem("top-left-inactive") ?: topLeftActive
        val topRightActive = decodeStem("top-right-active")
        val topRightInactive = decodeStem("top-right-inactive") ?: topRightActive

        val titleActiveSlices = (1..5).mapNotNull { idx -> decodeStem("title-$idx-active") }
        val titleInactiveSlices = (1..5).mapNotNull { idx -> decodeStem("title-$idx-inactive") }
            .ifEmpty { titleActiveSlices }

        val buttonActive = LinkedHashMap<LinuxTitleButtonKind, LinuxXfwm4SpriteSet>()
        val buttonInactive = LinkedHashMap<LinuxTitleButtonKind, LinuxXfwm4SpriteSet>()
        val buttonPrelight = LinkedHashMap<LinuxTitleButtonKind, LinuxXfwm4SpriteSet>()
        val buttonPressed = LinkedHashMap<LinuxTitleButtonKind, LinuxXfwm4SpriteSet>()
        val buttonToggledActive = LinkedHashMap<LinuxTitleButtonKind, LinuxXfwm4SpriteSet>()
        val buttonToggledInactive = LinkedHashMap<LinuxTitleButtonKind, LinuxXfwm4SpriteSet>()
        val buttonToggledPrelight = LinkedHashMap<LinuxTitleButtonKind, LinuxXfwm4SpriteSet>()
        val buttonToggledPressed = LinkedHashMap<LinuxTitleButtonKind, LinuxXfwm4SpriteSet>()

        for ((kind, prefix) in BUTTON_FILE_PREFIXES) {
            decodeStem("$prefix-active")?.let { buttonActive[kind] = it }
            decodeStem("$prefix-inactive")?.let { buttonInactive[kind] = it }
            decodeStem("$prefix-prelight")?.let { buttonPrelight[kind] = it }
            decodeStem("$prefix-pressed")?.let { buttonPressed[kind] = it }
            decodeStem("$prefix-toggled-active")?.let { buttonToggledActive[kind] = it }
            decodeStem("$prefix-toggled-inactive")?.let { buttonToggledInactive[kind] = it }
            decodeStem("$prefix-toggled-prelight")?.let { buttonToggledPrelight[kind] = it }
            decodeStem("$prefix-toggled-pressed")?.let { buttonToggledPressed[kind] = it }
        }

        val hasTitleFrame = titleActiveSlices.isNotEmpty() || topLeftActive != null || topRightActive != null
        val hasButtons = buttonActive.isNotEmpty()
        if (!hasTitleFrame || !hasButtons) {
            val detail = if (errors.isNotEmpty()) "; errors: ${errors.joinToString("; ")}" else ""
            return LoadResult(
                assets = null,
                errorReason = "Incomplete Xfwm4 theme assets for '$themeName' (hasTitleFrame=$hasTitleFrame, hasButtons=$hasButtons)$detail"
            )
        }

        if (errors.isNotEmpty()) {
            return LoadResult(
                assets = null,
                errorReason = "Failed decoding Xfwm4 sprites for '$themeName': ${errors.joinToString("; ")}"
            )
        }

        return LoadResult(
            assets = LinuxXfwm4ThemeAssets(
                themeName = themeName,
                properties = properties,
                topLeftActive = topLeftActive,
                topLeftInactive = topLeftInactive,
                topRightActive = topRightActive,
                topRightInactive = topRightInactive,
                titleActiveSlices = titleActiveSlices,
                titleInactiveSlices = titleInactiveSlices,
                buttonActive = buttonActive,
                buttonInactive = buttonInactive,
                buttonPrelight = buttonPrelight,
                buttonPressed = buttonPressed,
                buttonToggledActive = buttonToggledActive,
                buttonToggledInactive = buttonToggledInactive,
                buttonToggledPrelight = buttonToggledPrelight,
                buttonToggledPressed = buttonToggledPressed
            ),
            errorReason = null
        )
    }

    internal fun decodePngSprite(pngBytes: ByteArray): LinuxXfwm4SpriteSet {
        require(pngBytes.size >= 33) { "Truncated PNG data (${pngBytes.size} bytes)" }
        for (i in PNG_SIGNATURE.indices) {
            require(pngBytes[i] == PNG_SIGNATURE[i]) { "Invalid PNG signature" }
        }
        val ihdrTag = pngBytes[12] == 'I'.code.toByte() &&
            pngBytes[13] == 'H'.code.toByte() &&
            pngBytes[14] == 'D'.code.toByte() &&
            pngBytes[15] == 'R'.code.toByte()
        require(ihdrTag) { "Missing PNG IHDR chunk" }
        val buffer: ImageBuffer = LibPngCodec.decode(pngBytes)
        val w = buffer.width
        val h = buffer.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val rowOffset = y * w
            for (x in 0 until w) {
                pixels[rowOffset + x] = buffer.getPixel(x, y).toArgbInt()
            }
        }
        return LinuxXfwm4SpriteSet(pixels = pixels, width = w, height = h)
    }

    private fun compositeSprites(
        base: LinuxXfwm4SpriteSet,
        overlay: LinuxXfwm4SpriteSet
    ): LinuxXfwm4SpriteSet {
        val w = maxOf(base.width, overlay.width)
        val h = maxOf(base.height, overlay.height)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val basePixel = if (x < base.width && y < base.height) base.pixels[y * base.width + x] else 0
                val overPixel = if (x < overlay.width && y < overlay.height) overlay.pixels[y * overlay.width + x] else 0
                out[y * w + x] = alphaBlendArgb(basePixel, overPixel)
            }
        }
        return LinuxXfwm4SpriteSet(pixels = out, width = w, height = h)
    }

    private fun alphaBlendArgb(dst: Int, src: Int): Int {
        val sa = (src ushr 24) and 0xFF
        if (sa == 0) return dst
        if (sa == 255) return src
        val da = (dst ushr 24) and 0xFF
        val invSa = 255 - sa
        val outA = sa + ((da * invSa + 127) / 255)
        if (outA == 0) return 0
        val sr = (src ushr 16) and 0xFF
        val sg = (src ushr 8) and 0xFF
        val sb = src and 0xFF
        val dr = (dst ushr 16) and 0xFF
        val dg = (dst ushr 8) and 0xFF
        val db = dst and 0xFF
        val outR = ((sr * sa * 255 + dr * da * invSa) / (outA * 255)).coerceIn(0, 255)
        val outG = ((sg * sa * 255 + dg * da * invSa) / (outA * 255)).coerceIn(0, 255)
        val outB = ((sb * sa * 255 + db * da * invSa) / (outA * 255)).coerceIn(0, 255)
        return (outA shl 24) or (outR shl 16) or (outG shl 8) or outB
    }

    private fun resolveSystemThemeDirectories(themeName: String): List<String> {
        val dirs = ArrayList<String>()
        val home = getenv("HOME")?.toKString()?.trim()?.trimEnd('/')
        if (!home.isNullOrEmpty()) {
            dirs.add("$home/.themes/$themeName/xfwm4")
            dirs.add("$home/.local/share/themes/$themeName/xfwm4")
        }
        val dataDirs = getenv("XDG_DATA_DIRS")?.toKString()?.trim()
            ?.takeIf { it.isNotEmpty() } ?: "/usr/local/share:/usr/share"
        for (raw in dataDirs.split(':')) {
            val base = raw.trim().trimEnd('/')
            if (base.isNotEmpty()) {
                dirs.add("$base/themes/$themeName/xfwm4")
            }
        }
        return dirs.distinct()
    }

    private fun collectCandidateStems(): List<String> {
        val stems = ArrayList<String>()
        stems.add("top-left-active")
        stems.add("top-left-inactive")
        stems.add("top-right-active")
        stems.add("top-right-inactive")
        for (i in 1..5) {
            stems.add("title-$i-active")
            stems.add("title-$i-inactive")
        }
        for ((_, prefix) in BUTTON_FILE_PREFIXES) {
            stems.add("$prefix-active")
            stems.add("$prefix-inactive")
            stems.add("$prefix-prelight")
            stems.add("$prefix-pressed")
            stems.add("$prefix-toggled-active")
            stems.add("$prefix-toggled-inactive")
            stems.add("$prefix-toggled-prelight")
            stems.add("$prefix-toggled-pressed")
        }
        return stems
    }
}
