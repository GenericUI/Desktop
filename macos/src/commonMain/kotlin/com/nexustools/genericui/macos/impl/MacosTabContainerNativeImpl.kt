package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.macos.MacosViewHandle

/**
 * macOS AppKit per-component native implementation for `TabContainer` (`NSTabView`),
 * delegating rendering to [com.nexustools.genericui.macos.render.MacosControlRenderUtil].
 */
class MacosTabContainerNativeImpl(
    nativeWidget: MacosViewHandle = MacosViewHandle("NSTabView")
) : com.nexustools.genericui.macos.MacosTabContainerNativeImpl(nativeWidget)
