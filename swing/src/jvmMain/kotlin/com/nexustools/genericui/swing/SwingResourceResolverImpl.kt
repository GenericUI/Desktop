package com.nexustools.genericui.swing

import com.nexustools.genericui.res.Resources
import com.nexustools.genericui.resources.DefaultResourceResolverNativeImpl
import com.nexustools.genericui.resources.ResourceID

/**
 * Java Swing (`:desktop:swing`) implementation of [com.nexustools.genericui.resources.ResourceResolverNativeImpl]
 * that resolves packaged JAR/classpath resources via `ClassLoader` with fallback to [DefaultResourceResolverNativeImpl].
 */
class SwingResourceResolverImpl : DefaultResourceResolverNativeImpl() {

    private fun candidateClasspathPaths(resId: ResourceID): List<String> {
        val registered = Resources.activeTable.findResourceId(resId.id)
            ?: Resources.activeTable.findResourceId(resId.type, resId.name)
            ?: resId
        val rel = registered.relativePath.ifEmpty { resId.relativePath }.trimStart('/')
        val fileName = rel.substringAfterLast('/').ifEmpty { resId.name }
        return buildList {
            if (rel.isNotEmpty()) {
                add(rel)
                add("assets/$rel")
            }
            add("assets/${resId.type.directoryName}/$fileName")
            add("${resId.type.directoryName}/$fileName")
        }.distinct()
    }

    override fun resolveUrl(resId: ResourceID): String {
        val loader = Thread.currentThread().contextClassLoader ?: SwingResourceResolverImpl::class.java.classLoader
        if (loader != null) {
            for (path in candidateClasspathPaths(resId)) {
                val url = loader.getResource(path)
                if (url != null) return url.toExternalForm()
            }
        }
        return super.resolveUrl(resId)
    }

    override fun resolveBytes(resId: ResourceID): ByteArray? {
        val loader = Thread.currentThread().contextClassLoader ?: SwingResourceResolverImpl::class.java.classLoader
        if (loader != null) {
            for (path in candidateClasspathPaths(resId)) {
                val stream = loader.getResourceAsStream(path)
                if (stream != null) {
                    return stream.use { it.readBytes() }
                }
            }
        }
        return super.resolveBytes(resId)
    }
}
