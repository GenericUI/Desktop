package com.nexustools.genericui.qt

import com.nexustools.genericui.resources.DefaultResourceResolverNativeImpl
import com.nexustools.genericui.resources.ResourceID

/**
 * Qt6 (`:desktop:qt`) implementation of [com.nexustools.genericui.resources.ResourceResolverNativeImpl]
 * backed by Qt `QResource` (`genericui_resources.qrc`) and [DefaultResourceResolverNativeImpl].
 */
class QtResourceResolverImpl : DefaultResourceResolverNativeImpl() {

    override fun resolveUrl(resId: ResourceID): String =
        super.resolveUrl(resId)

    override fun resolveBytes(resId: ResourceID): ByteArray? =
        super.resolveBytes(resId)
}
