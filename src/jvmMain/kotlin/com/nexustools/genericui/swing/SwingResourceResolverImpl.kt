package com.nexustools.genericui.swing

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.Readable
import com.nexustools.genericui.io.ReadableByteBuffer
import com.nexustools.genericui.jvm.JvmResourceResolverBase
import com.nexustools.genericui.res.Resources
import com.nexustools.genericui.resources.ResourceID
import javax.swing.SwingUtilities

/**
 * Java Swing implementation of [com.nexustools.genericui.resources.ResourceResolverNativeImpl]
 * backed by [JvmResourceResolverBase].
 */
class SwingResourceResolverImpl : JvmResourceResolverBase(
    isOnMainThread = { SwingUtilities.isEventDispatchThread() }
) {
    override suspend fun openResourceStream(resId: ResourceID): Readable? {
        val loader = resolveClassLoader()
        if (loader != null) {
            for (path in candidateClasspathPaths(resId)) {
                val stream = loader.getResourceAsStream(path)
                if (stream != null) {
                    return SwingFileReadableNativeImpl(stream, filePath = resolveUrl(resId))
                }
            }
        }
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
