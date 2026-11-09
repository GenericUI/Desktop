package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopContainerNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32InputFieldRenderUtil
import com.nexustools.genericui.windows.render.Win32RenderContext

/**
 * Win32 native implementation for `FileDropZone` file picker / drag-and-drop surfaces.
 */
class WindowsFilePickerDropZoneNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIFileDropZoneClass"),
    classPrefix: String = "file-drop-zone"
) : DesktopContainerNativeImpl<Win32HwndHandle>(widget, classPrefix), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32InputFieldRenderUtil.renderFilePickerDropZone(
        ctx = ctx,
        widget = nativeWidget,
        left = left,
        top = top,
        right = right,
        height = fixedHeight ?: 88
    )
}
