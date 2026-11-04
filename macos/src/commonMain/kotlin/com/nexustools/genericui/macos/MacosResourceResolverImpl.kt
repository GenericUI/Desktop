package com.nexustools.genericui.macos

import com.nexustools.genericui.res.Resources
import com.nexustools.genericui.resources.DefaultResourceResolverNativeImpl
import com.nexustools.genericui.resources.ResourceID

/**
 * macOS (`:desktop:macos`) implementation of [com.nexustools.genericui.resources.ResourceResolverNativeImpl]
 * backed by AppKit `NSBundle.mainBundle` and [DefaultResourceResolverNativeImpl].
 */
class MacosResourceResolverImpl : DefaultResourceResolverNativeImpl() {

    override fun resolveUrl(resId: ResourceID): String =
        super.resolveUrl(resId)

    override fun resolveBytes(resId: ResourceID): ByteArray? =
        super.resolveBytes(resId)

    override suspend fun openResourceStream(resId: ResourceID): MacosFileReadableNativeImpl? {
        val rawBytes = resolveBytes(resId)
        if (rawBytes != null) {
            return MacosFileReadableNativeImpl(
                initialBytes = rawBytes,
                filePath = resolveUrl(resId)
            )
        }
        val rawText = Resources.activeTable.getRawText(resId)
            ?: Resources.activeTable.getStringOrNull(resId)
            ?: Resources.activeTable.getDrawable(resId)?.svgSource
        if (rawText != null) {
            return MacosFileReadableNativeImpl(
                initialBytes = rawText.encodeToByteArray(),
                filePath = resolveUrl(resId)
            )
        }
        return null
    }
}
