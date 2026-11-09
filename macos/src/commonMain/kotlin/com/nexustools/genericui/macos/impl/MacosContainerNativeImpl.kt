package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.macos.MacosViewHandle

/**
 * macOS AppKit per-component native implementation for `Container` / `Card` (`NSStackView` / `NSBox`),
 * delegating rendering and [com.nexustools.genericui.enums.bootstrap.BootstrapType] tinting to
 * [com.nexustools.genericui.macos.render.MacosControlRenderUtil].
 */
class MacosContainerNativeImpl(
    nativeWidget: MacosViewHandle = MacosViewHandle("NSStackView"),
    classPrefix: String = "container"
) : com.nexustools.genericui.macos.MacosContainerNativeImpl(nativeWidget, classPrefix)
