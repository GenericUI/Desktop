package com.nexustools.genericui.linux.titlebar

/**
 * Decoded 32-bit ARGB (`0xAARRGGBB`) sprite buffer from an Xfwm4 `.xpm` or `.png` asset.
 */
data class LinuxXfwm4SpriteSet(
    val pixels: IntArray,
    val width: Int,
    val height: Int
) {
    val isEmpty: Boolean
        get() = width <= 0 || height <= 0 || pixels.size < width * height

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LinuxXfwm4SpriteSet) return false
        return width == other.width && height == other.height && pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int {
        var result = pixels.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        return result
    }
}
