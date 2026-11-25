package com.nexustools.genericui.linux.titlebar

/**
 * Inspects user GTK stylesheets (`~/.config/gtk-4.0/gtk.css` and `~/.config/gtk-3.0/gtk.css`)
 * for structural headerbar/titlebar decoration overrides that break native titlebar replication.
 */
object LinuxGtkCssOverrideInspector {

    data class InspectionResult(
        val hasStructuralOverride: Boolean,
        val matchedSelectors: List<String> = emptyList(),
        val sourceLabels: List<String> = emptyList()
    )

    private val STRUCTURAL_SELECTOR_REGEX = Regex(
        """(?:(?:^|[\s>+~,:().])(headerbar|windowcontrols|decoration)|\.titlebar)(?=$|[\s>+~,:().#\[])""",
        RegexOption.IGNORE_CASE
    )

    fun inspect(gtk4Css: String?, gtk3Css: String?): InspectionResult {
        val matched = ArrayList<String>()
        val sources = ArrayList<String>()

        if (!gtk4Css.isNullOrBlank()) {
            val hits = findStructuralSelectors(gtk4Css)
            if (hits.isNotEmpty()) {
                sources.add("gtk-4.0/gtk.css")
                matched.addAll(hits)
            }
        }
        if (!gtk3Css.isNullOrBlank()) {
            val hits = findStructuralSelectors(gtk3Css)
            if (hits.isNotEmpty()) {
                sources.add("gtk-3.0/gtk.css")
                matched.addAll(hits)
            }
        }

        return InspectionResult(
            hasStructuralOverride = matched.isNotEmpty(),
            matchedSelectors = matched.distinct(),
            sourceLabels = sources
        )
    }

    fun findStructuralSelectors(cssText: String): List<String> {
        val stripped = stripCssComments(cssText)
        val selectors = ArrayList<String>()
        collectSelectorsFromBlock(stripped, selectors)
        return selectors.filter { STRUCTURAL_SELECTOR_REGEX.containsMatchIn(it) }.distinct()
    }

    private fun stripCssComments(input: String): String {
        val sb = StringBuilder(input.length)
        var i = 0
        var inSingle = false
        var inDouble = false
        while (i < input.length) {
            val c = input[i]
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle
                sb.append(c)
                i++
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble
                sb.append(c)
                i++
            } else if (!inSingle && !inDouble && c == '/' && i + 1 < input.length && input[i + 1] == '*') {
                val close = input.indexOf("*/", i + 2)
                if (close < 0) break
                sb.append(' ')
                i = close + 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun collectSelectorsFromBlock(css: String, outSelectors: MutableList<String>) {
        var i = 0
        val len = css.length
        val headerBuf = StringBuilder()
        var inSingle = false
        var inDouble = false

        while (i < len) {
            val c = css[i]
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle
                headerBuf.append(c)
                i++
                continue
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble
                headerBuf.append(c)
                i++
                continue
            }
            if (!inSingle && !inDouble && c == ';') {
                // Top-level statement (e.g. `@import`, `@define-color`) — discard buffer.
                headerBuf.clear()
                i++
                continue
            }
            if (!inSingle && !inDouble && c == '{') {
                val selectorText = headerBuf.toString().trim()
                headerBuf.clear()
                val closeIdx = findMatchingBrace(css, i + 1)
                if (selectorText.startsWith("@")) {
                    // Recurse into `@media` / `@supports` blocks.
                    val inner = css.substring(i + 1, closeIdx)
                    collectSelectorsFromBlock(inner, outSelectors)
                } else if (selectorText.isNotEmpty()) {
                    for (part in selectorText.split(',')) {
                        val trimmed = part.trim()
                        if (trimmed.isNotEmpty()) outSelectors.add(trimmed)
                    }
                }
                i = (closeIdx + 1).coerceAtMost(len)
                continue
            }
            headerBuf.append(c)
            i++
        }
    }

    private fun findMatchingBrace(css: String, startIdx: Int): Int {
        var depth = 1
        var i = startIdx
        var inSingle = false
        var inDouble = false
        while (i < css.length) {
            val c = css[i]
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble
            } else if (!inSingle && !inDouble) {
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return css.length
    }
}
