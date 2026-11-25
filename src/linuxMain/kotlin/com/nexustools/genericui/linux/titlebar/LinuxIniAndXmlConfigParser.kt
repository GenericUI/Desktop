package com.nexustools.genericui.linux.titlebar

import com.nexustools.genericui.style.color.RgbaColor

/**
 * Pure-Kotlin parser for Linux desktop INI configs (`kwinrc`, `breezerc`, `kdeglobals`,
 * `settings.ini`), flat `themerc` key-value files, and Xfce4 `xfconf` XML channel files
 * (`xfwm4.xml`, `xsettings.xml`).
 */
object LinuxIniAndXmlConfigParser {

    fun parseIni(text: String): Map<String, Map<String, String>> {
        val result = LinkedHashMap<String, LinkedHashMap<String, String>>()
        var currentSection = ""
        val lines = text.lineSequence()
        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue
            }
            if (line.startsWith("[") && line.endsWith("]") && line.length >= 2) {
                currentSection = line.substring(1, line.length - 1).trim()
                result.getOrPut(currentSection) { LinkedHashMap() }
                continue
            }
            val eqIdx = line.indexOf('=')
            if (eqIdx <= 0) continue
            val rawKey = line.substring(0, eqIdx).trim()
            // KDE INI keys may carry immutable markers such as `Theme[$i]` or `ButtonsOnLeft[$e]`.
            val key = rawKey.substringBefore("[$").trim()
            if (key.isEmpty()) continue
            val value = line.substring(eqIdx + 1).trim()
            val sectionMap = result.getOrPut(currentSection) { LinkedHashMap() }
            sectionMap[key] = value
        }
        return result
    }

    fun getIniValue(
        sections: Map<String, Map<String, String>>,
        section: String,
        key: String,
        ignoreCaseSection: Boolean = false,
        ignoreCaseKey: Boolean = false
    ): String? {
        val sectionMap = if (!ignoreCaseSection) {
            sections[section]
        } else {
            sections.entries.firstOrNull { it.key.equals(section, ignoreCase = true) }?.value
        } ?: return null

        if (!ignoreCaseKey) {
            return sectionMap[key]
        }
        return sectionMap.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
    }

    fun hasIniKey(
        sections: Map<String, Map<String, String>>,
        section: String,
        key: String,
        ignoreCaseSection: Boolean = false,
        ignoreCaseKey: Boolean = false
    ): Boolean {
        val sectionMap = if (!ignoreCaseSection) {
            sections[section]
        } else {
            sections.entries.firstOrNull { it.key.equals(section, ignoreCase = true) }?.value
        } ?: return false

        return if (!ignoreCaseKey) {
            sectionMap.containsKey(key)
        } else {
            sectionMap.keys.any { it.equals(key, ignoreCase = true) }
        }
    }

    fun parseKeyValueLines(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            val eqIdx = line.indexOf('=')
            if (eqIdx <= 0) continue
            val key = line.substring(0, eqIdx).trim()
            val value = line.substring(eqIdx + 1).trim()
            if (key.isNotEmpty()) {
                result[key] = value
            }
        }
        return result
    }

    /**
     * Parses an Xfce4 `xfconf` XML document (`xfwm4.xml`, `xsettings.xml`) into a flat map
     * keyed by slash-delimited property paths (e.g., `"general/theme"`, `"Net/ThemeName"`).
     */
    fun parseXfconfXml(xml: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val pathStack = ArrayList<String>()
        var i = 0
        val len = xml.length
        while (i < len) {
            val lt = xml.indexOf('<', i)
            if (lt < 0) break
            if (lt + 4 <= len && xml.startsWith("<!--", lt)) {
                val endComment = xml.indexOf("-->", lt + 4)
                i = if (endComment < 0) len else endComment + 3
                continue
            }
            if (lt + 2 <= len && (xml[lt + 1] == '?' || xml[lt + 1] == '!')) {
                val gt = findTagEnd(xml, lt + 2)
                i = if (gt < 0) len else gt + 1
                continue
            }
            val gt = findTagEnd(xml, lt + 1)
            if (gt < 0) break
            val tagBody = xml.substring(lt + 1, gt).trim()
            i = gt + 1
            if (tagBody.isEmpty()) continue

            if (tagBody.startsWith("/")) {
                val closeName = tagBody.substring(1).trim().lowercase()
                if (closeName == "property" && pathStack.isNotEmpty()) {
                    pathStack.removeAt(pathStack.size - 1)
                }
                continue
            }

            val selfClosing = tagBody.endsWith("/")
            val cleanBody = if (selfClosing) tagBody.substring(0, tagBody.length - 1).trim() else tagBody
            val spaceIdx = cleanBody.indexOfFirst { it.isWhitespace() }
            val tagName = (if (spaceIdx < 0) cleanBody else cleanBody.substring(0, spaceIdx)).lowercase()
            if (tagName != "property") continue

            val attrsText = if (spaceIdx < 0) "" else cleanBody.substring(spaceIdx + 1)
            val attrs = parseXmlAttributes(attrsText)
            val propName = attrs["name"]?.takeIf { it.isNotEmpty() } ?: continue
            pathStack.add(propName)
            val value = attrs["value"]
            if (value != null) {
                out[pathStack.joinToString("/")] = decodeXmlEntities(value)
            }
            if (selfClosing) {
                pathStack.removeAt(pathStack.size - 1)
            }
        }
        return out
    }

    fun parseColor(raw: String?): RgbaColor? {
        val cleaned = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // KDE palette format: "R,G,B" or "R,G,B,A"
        if (!cleaned.startsWith("#") && !cleaned.startsWith("rgb", ignoreCase = true) && cleaned.contains(',')) {
            val parts = cleaned.split(',').map { it.trim() }
            if (parts.size == 3 || parts.size == 4) {
                val r = parts[0].toIntOrNull()
                val g = parts[1].toIntOrNull()
                val b = parts[2].toIntOrNull()
                val a = if (parts.size == 4) parts[3].toIntOrNull() else 255
                if (r != null && g != null && b != null && a != null) {
                    return RgbaColor(
                        r = r.coerceIn(0, 255),
                        g = g.coerceIn(0, 255),
                        b = b.coerceIn(0, 255),
                        a = a.coerceIn(0, 255)
                    )
                }
            }
        }
        return RgbaColor.fromCss(cleaned)
    }

    fun parseBoolean(raw: String?, default: Boolean): Boolean {
        val v = raw?.trim()?.lowercase() ?: return default
        return when (v) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> default
        }
    }

    private fun findTagEnd(xml: String, startIdx: Int): Int {
        var inSingle = false
        var inDouble = false
        for (idx in startIdx until xml.length) {
            val c = xml[idx]
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble
            } else if (c == '>' && !inSingle && !inDouble) {
                return idx
            }
        }
        return -1
    }

    private fun parseXmlAttributes(text: String): Map<String, String> {
        val attrs = LinkedHashMap<String, String>()
        var i = 0
        val len = text.length
        while (i < len) {
            while (i < len && text[i].isWhitespace()) i++
            if (i >= len) break
            val nameStart = i
            while (i < len && text[i] != '=' && !text[i].isWhitespace()) i++
            val attrName = text.substring(nameStart, i).trim()
            while (i < len && text[i].isWhitespace()) i++
            if (i >= len || text[i] != '=') {
                continue
            }
            i++ // skip '='
            while (i < len && text[i].isWhitespace()) i++
            if (i >= len) break
            val quote = text[i]
            val attrValue: String
            if (quote == '"' || quote == '\'') {
                i++
                val valStart = i
                while (i < len && text[i] != quote) i++
                attrValue = text.substring(valStart, i)
                if (i < len && text[i] == quote) i++
            } else {
                val valStart = i
                while (i < len && !text[i].isWhitespace()) i++
                attrValue = text.substring(valStart, i)
            }
            if (attrName.isNotEmpty()) {
                attrs[attrName] = attrValue
            }
        }
        return attrs
    }

    private fun decodeXmlEntities(value: String): String {
        if (!value.contains('&')) return value
        return value
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }
}
