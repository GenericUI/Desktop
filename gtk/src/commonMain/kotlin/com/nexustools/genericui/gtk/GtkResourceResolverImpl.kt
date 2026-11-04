package com.nexustools.genericui.gtk

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.Readable
import com.nexustools.genericui.io.ReadableByteBuffer
import com.nexustools.genericui.res.Resources
import com.nexustools.genericui.resources.DefaultResourceResolverNativeImpl
import com.nexustools.genericui.resources.ResourceID

/**
 * Linux GTK4 (`:desktop:gtk`) implementation of [com.nexustools.genericui.resources.ResourceResolverNativeImpl]
 * backed by GLib `GResource` (`genericui.gresource.xml`, `g_resources_open_stream`) and [DefaultResourceResolverNativeImpl].
 */
class GtkResourceResolverImpl : DefaultResourceResolverNativeImpl() {

    override fun resolveUrl(resId: ResourceID): String =
        super.resolveUrl(resId)

    override fun resolveBytes(resId: ResourceID): ByteArray? =
        super.resolveBytes(resId)

    override suspend fun openResourceStream(resId: ResourceID): Readable? {
        val rawBytes = resolveBytes(resId)
        if (rawBytes != null) {
            return ReadableByteBuffer(ByteBuffer.wrap(rawBytes))
        }
        val rawText = Resources.activeTable.getRawText(resId)
            ?: Resources.activeTable.getStringOrNull(resId)
            ?: Resources.activeTable.getDrawable(resId)?.svgSource
        if (rawText != null) {
            return ReadableByteBuffer(ByteBuffer.wrap(rawText.encodeToByteArray()))
        }
        return null
    }
}
