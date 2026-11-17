package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.macos.MacosViewHandle

/**
 * macOS AppKit per-component native implementation for `Button` (`NSButton` / `NSButtonCell`),
 * delegating rendering and [com.nexustools.genericui.enums.bootstrap.BootstrapType] tinting to
 * [com.nexustools.genericui.macos.render.MacosControlRenderUtil].
 */
class MacosButtonNativeImpl(
    nativeWidget: MacosViewHandle = MacosViewHandle("NSButton"),
    classPrefix: String = "btn"
) : com.nexustools.genericui.macos.MacosButtonNativeImpl(nativeWidget, classPrefix)
