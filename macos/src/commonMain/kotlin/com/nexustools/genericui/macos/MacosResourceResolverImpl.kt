package com.nexustools.genericui.macos

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
}
