package com.nexustools.genericui.gtk

import com.nexustools.genericui.resources.DefaultResourceResolverNativeImpl
import com.nexustools.genericui.resources.ResourceID

/**
 * Linux GTK4 (`:desktop:gtk`) implementation of [com.nexustools.genericui.resources.ResourceResolverNativeImpl]
 * backed by GLib `GResource` (`genericui.gresource.xml`) and [DefaultResourceResolverNativeImpl].
 */
class GtkResourceResolverImpl : DefaultResourceResolverNativeImpl() {

    override fun resolveUrl(resId: ResourceID): String =
        super.resolveUrl(resId)

    override fun resolveBytes(resId: ResourceID): ByteArray? =
        super.resolveBytes(resId)
}
