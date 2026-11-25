@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.nexustools.genericui.desktop.image

import com.nexustools.genericui.desktop.system.DesktopConfigDirectoryHelper
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.cache.ImageCacheManager
import kotlin.concurrent.atomics.AtomicInt

/**
 * Shared desktop codec registration wiring [DesktopLibJpegImageCodecImpl] into
 * [ImageLoader.activeNativeCodec] and registering OS file, resource, and HTTP URL
 * fetchers on [ImageCacheManager] for `:windows`, `:macos`, `:gtk`, and `:kde`.
 */
object DesktopImageCodecHooks {

    private val tempDownloadSeq = AtomicInt(0)

    var activeDesktopBackend: String = "Desktop"
        private set

    var activeCodec: DesktopLibJpegImageCodecImpl? = null
        private set

    fun install(backendName: String): DesktopLibJpegImageCodecImpl {
        activeDesktopBackend = backendName
        if (ImageCacheManager.localResourceReaderHook == null) {
            ImageCacheManager.localResourceReaderHook = { uriOrPath ->
                DesktopConfigDirectoryHelper.readResourceOrLocalFile(uriOrPath)
            }
        }
        if (ImageCacheManager.urlFetcherHook == null) {
            ImageCacheManager.urlFetcherHook = { url ->
                if (url.startsWith("http://", ignoreCase = true) ||
                    url.startsWith("https://", ignoreCase = true)
                ) {
                    ImageCacheManager.cacheDirectory.ensureExists()
                    val seq = tempDownloadSeq.fetchAndAdd(1) and 0x7FFF_FFFF
                    val tempPath = ImageCacheManager.cacheDirectory.resolvePath(".tmp_download_$seq.bin")
                    DesktopConfigDirectoryHelper.fetchHttpUrlBytes(url, tempPath)
                } else {
                    DesktopConfigDirectoryHelper.readResourceOrLocalFile(url)
                }
            }
        }
        val codec = DesktopLibJpegImageCodecImpl("$backendName Native Image Loader + libjpeg-turbo/libpng/libwebp")
        activeCodec = codec
        ImageLoader.activeNativeCodec = codec
        return codec
    }
}
