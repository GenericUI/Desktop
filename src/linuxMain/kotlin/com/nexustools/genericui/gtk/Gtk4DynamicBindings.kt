@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk

import com.nexustools.genericui.linux.LinuxDynamicLibraryLoader
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.linux.LinuxReentrantLock
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.F_OK
import platform.posix.access
import platform.posix.getenv
import platform.posix.setenv

internal object Gtk4DynamicBindings {
    const val CAIRO_FORMAT_ARGB32 = 0
    const val CAIRO_LINE_CAP_BUTT = 0
    const val CAIRO_LINE_CAP_ROUND = 1

    const val ADW_COLOR_SCHEME_DEFAULT = 0
    const val ADW_COLOR_SCHEME_FORCE_LIGHT = 1
    const val ADW_COLOR_SCHEME_FORCE_DARK = 4

    const val GTK_ORIENTATION_HORIZONTAL = 0
    const val GTK_ORIENTATION_VERTICAL = 1
    const val GTK_EVENT_CONTROLLER_SCROLL_BOTH_AXES = 3

    const val GDK_MEMORY_B8G8R8A8_PREMUL = 0
    const val GDK_MEMORY_B8G8R8A8 = 3
    const val GDK_MEMORY_R8G8B8A8 = 5

    const val GDK_SHIFT_MASK = 1u
    const val GDK_CONTROL_MASK = 4u
    const val GDK_ALT_MASK = 8u
    const val GDK_SUPER_MASK = 67108864u
    const val GDK_TOPLEVEL_STATE_MINIMIZED = 1u

    const val PANGO_SCALE = 1024
    const val PANGO_ALIGN_LEFT = 0
    const val PANGO_ALIGN_CENTER = 1
    const val PANGO_ALIGN_RIGHT = 2
    const val PANGO_WRAP_WORD_CHAR = 2
    const val PANGO_ELLIPSIZE_NONE = 0
    const val PANGO_ELLIPSIZE_END = 3
    const val PANGO_WEIGHT_NORMAL = 400
    const val PANGO_WEIGHT_SEMIBOLD = 600
    const val PANGO_WEIGHT_BOLD = 700
    const val PANGO_STYLE_NORMAL = 0
    const val PANGO_STYLE_ITALIC = 2

    private val lock = LinuxReentrantLock()
    private var librariesLoaded = false
    private var gtkLibrariesAvailable = false
    private var gtkInitializedAttempted = false
    private var gtkInitializedOk = false

    private var fnGtkInitCheck: CPointer<CFunction<() -> Int>>? = null
    private var fnAdwInit: CPointer<CFunction<() -> Unit>>? = null
    private var fnAdwStyleManagerGetDefault: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnAdwStyleManagerSetColorScheme: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnAdwStyleManagerGetDark: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null

    private var fnGtkWindowNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkWindowSetTitle: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnGtkWindowSetDefaultSize: CPointer<CFunction<(COpaquePointer?, Int, Int) -> Unit>>? = null
    private var fnGtkWindowSetChild: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkWindowSetTitlebar: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkWindowPresent: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGtkWindowClose: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGtkWindowDestroy: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGtkWindowSetModal: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkWindowSetTransientFor: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkWindowFullscreen: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGtkWindowUnfullscreen: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGtkWindowMaximize: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGtkWindowUnmaximize: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGtkWindowMinimize: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGtkWindowIsFullscreen: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGtkWindowIsMaximized: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGtkWindowSetResizable: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkWindowSetDecorated: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkWindowSetHideOnClose: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkWidgetGetRealized: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGtkWidgetGetNative: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkNativeGetSurface: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGdkToplevelGetType: CPointer<CFunction<() -> ULong>>? = null
    private var fnGdkToplevelGetState: CPointer<CFunction<(COpaquePointer?) -> UInt>>? = null
    private var fnGdkToplevelBeginMove: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, Int, Double, Double, UInt) -> Unit>>? = null
    private var fnGdkDisplayGetDefaultSeat: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGdkSeatGetPointer: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGdkSurfaceGetDevicePosition: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, CPointer<DoubleVar>?, CPointer<DoubleVar>?, COpaquePointer?) -> Int>>? = null
    private var fnGdkX11SurfaceGetType: CPointer<CFunction<() -> ULong>>? = null
    private var fnGdkX11SurfaceGetXid: CPointer<CFunction<(COpaquePointer?) -> ULong>>? = null

    private var fnGtkHeaderBarNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkHeaderBarSetShowTitleButtons: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkHeaderBarSetDecorationLayout: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnGtkHeaderBarSetTitleWidget: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null

    private var fnGtkDrawingAreaNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkDrawingAreaSetDrawFunc: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkWidgetSetSizeRequest: CPointer<CFunction<(COpaquePointer?, Int, Int) -> Unit>>? = null
    private var fnGtkWidgetGetWidth: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGtkWidgetGetHeight: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGtkWidgetSetVisible: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkWidgetQueueDraw: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGtkWidgetAddController: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkWidgetSetFocusable: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkWidgetGrabFocus: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGtkWidgetSetCursor: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkWidgetSetCursorFromName: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnGtkWidgetSetOpacity: CPointer<CFunction<(COpaquePointer?, Double) -> Unit>>? = null
    private var fnGtkWidgetGetDisplay: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkWidgetSetHexpand: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkWidgetSetVexpand: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkWidgetSetMargins: Array<CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>?> = arrayOfNulls(4)

    private var fnGtkBoxNew: CPointer<CFunction<(Int, Int) -> COpaquePointer?>>? = null
    private var fnGtkBoxAppend: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkLabelNew: CPointer<CFunction<(CPointer<ByteVar>?) -> COpaquePointer?>>? = null
    private var fnGtkLabelSetWrap: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkEntryNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkEditableGetText: CPointer<CFunction<(COpaquePointer?) -> CPointer<ByteVar>?>>? = null
    private var fnGtkEditableSetText: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnGtkButtonNewWithLabel: CPointer<CFunction<(CPointer<ByteVar>?) -> COpaquePointer?>>? = null
    private var fnGtkDropDownNewFromStrings: CPointer<CFunction<(CPointer<CPointerVar<ByteVar>>?) -> COpaquePointer?>>? = null
    private var fnGtkDropDownGetSelected: CPointer<CFunction<(COpaquePointer?) -> UInt>>? = null
    private var fnGtkDropDownSetSelected: CPointer<CFunction<(COpaquePointer?, UInt) -> Unit>>? = null

    private var fnGtkGestureClickNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkGestureSingleSetButton: CPointer<CFunction<(COpaquePointer?, UInt) -> Unit>>? = null
    private var fnGtkEventControllerMotionNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkEventControllerScrollNew: CPointer<CFunction<(UInt) -> COpaquePointer?>>? = null
    private var fnGtkEventControllerKeyNew: CPointer<CFunction<() -> COpaquePointer?>>? = null

    private var fnGdkKeyvalName: CPointer<CFunction<(UInt) -> CPointer<ByteVar>?>>? = null
    private var fnGdkKeyvalToUnicode: CPointer<CFunction<(UInt) -> UInt>>? = null
    private var fnGdkCursorNewFromName: CPointer<CFunction<(CPointer<ByteVar>?, COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGdkMemoryTextureNew: CPointer<CFunction<(Int, Int, Int, COpaquePointer?, ULong) -> COpaquePointer?>>? = null
    private var fnGdkCursorNewFromTexture: CPointer<CFunction<(COpaquePointer?, Int, Int, COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGdkDisplayGetClipboard: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGdkDisplayGetDefault: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGdkDisplayGetMonitors: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGdkMonitorGetGeometry: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGdkClipboardSetText: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnGdkClipboardReadTextAsync: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGdkClipboardReadTextFinish: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, CPointer<COpaquePointerVar>?) -> CPointer<ByteVar>?>>? = null
    private var fnGdkRgbaFree: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null

    private var fnAlertDialogNew: CPointer<CFunction<(CPointer<ByteVar>?, COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnAlertDialogSetDetail: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnAlertDialogSetButtons: CPointer<CFunction<(COpaquePointer?, CPointer<CPointerVar<ByteVar>>?) -> Unit>>? = null
    private var fnAlertDialogSetDefaultButton: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnAlertDialogSetCancelButton: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnAlertDialogChoose: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnAlertDialogChooseFinish: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, CPointer<COpaquePointerVar>?) -> Int>>? = null

    private var fnFileDialogNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnFileDialogSetTitle: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnFileDialogSetInitialFolder: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnFileDialogSetInitialName: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnFileDialogSetFilters: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnFileDialogOpen: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnFileDialogOpenFinish: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, CPointer<COpaquePointerVar>?) -> COpaquePointer?>>? = null
    private var fnFileDialogOpenMultiple: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnFileDialogOpenMultipleFinish: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, CPointer<COpaquePointerVar>?) -> COpaquePointer?>>? = null
    private var fnFileDialogSelectFolder: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnFileDialogSelectFolderFinish: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, CPointer<COpaquePointerVar>?) -> COpaquePointer?>>? = null
    private var fnFileDialogSelectMultipleFolders: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnFileDialogSelectMultipleFoldersFinish: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, CPointer<COpaquePointerVar>?) -> COpaquePointer?>>? = null
    private var fnFileDialogSave: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnFileDialogSaveFinish: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, CPointer<COpaquePointerVar>?) -> COpaquePointer?>>? = null
    private var fnFileFilterNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnFileFilterSetName: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnFileFilterAddPattern: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnFileFilterGetType: CPointer<CFunction<() -> ULong>>? = null

    private var fnColorDialogNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnColorDialogSetTitle: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnColorDialogSetWithAlpha: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnColorDialogChooseRgba: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnColorDialogChooseRgbaFinish: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, CPointer<COpaquePointerVar>?) -> COpaquePointer?>>? = null

    private var fnCairoCreate: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnCairoDestroy: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnCairoSave: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnCairoRestore: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnCairoRectangle: CPointer<CFunction<(COpaquePointer?, Double, Double, Double, Double) -> Unit>>? = null
    private var fnCairoClip: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnCairoNewPath: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnCairoNewSubPath: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnCairoClosePath: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnCairoMoveTo: CPointer<CFunction<(COpaquePointer?, Double, Double) -> Unit>>? = null
    private var fnCairoLineTo: CPointer<CFunction<(COpaquePointer?, Double, Double) -> Unit>>? = null
    private var fnCairoArc: CPointer<CFunction<(COpaquePointer?, Double, Double, Double, Double, Double) -> Unit>>? = null
    private var fnCairoFill: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnCairoFillPreserve: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnCairoStroke: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnCairoPaint: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnCairoPaintWithAlpha: CPointer<CFunction<(COpaquePointer?, Double) -> Unit>>? = null
    private var fnCairoSetSourceRgba: CPointer<CFunction<(COpaquePointer?, Double, Double, Double, Double) -> Unit>>? = null
    private var fnCairoSetLineWidth: CPointer<CFunction<(COpaquePointer?, Double) -> Unit>>? = null
    private var fnCairoSetLineCap: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnCairoPatternCreateLinear: CPointer<CFunction<(Double, Double, Double, Double) -> COpaquePointer?>>? = null
    private var fnCairoPatternAddColorStopRgba: CPointer<CFunction<(COpaquePointer?, Double, Double, Double, Double, Double) -> Unit>>? = null
    private var fnCairoSetSource: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnCairoPatternDestroy: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnCairoImageSurfaceCreateForData: CPointer<CFunction<(COpaquePointer?, Int, Int, Int, Int) -> COpaquePointer?>>? = null
    private var fnCairoGetTarget: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnCairoUserToDevice: CPointer<CFunction<(COpaquePointer?, CPointer<DoubleVar>?, CPointer<DoubleVar>?) -> Unit>>? = null
    private var fnCairoImageSurfaceGetData: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnCairoImageSurfaceGetFormat: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnCairoImageSurfaceGetWidth: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnCairoImageSurfaceGetHeight: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnCairoImageSurfaceGetStride: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnCairoSurfaceFlush: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnCairoSurfaceMarkDirty: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnCairoSurfaceDestroy: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnCairoSetSourceSurface: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, Double, Double) -> Unit>>? = null
    private var fnCairoTranslate: CPointer<CFunction<(COpaquePointer?, Double, Double) -> Unit>>? = null
    private var fnCairoScale: CPointer<CFunction<(COpaquePointer?, Double, Double) -> Unit>>? = null

    private var fnGtkSnapshotNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkSnapshotPushBlur: CPointer<CFunction<(COpaquePointer?, Double) -> Unit>>? = null
    private var fnGtkSnapshotPop: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGtkSnapshotAppendCairo: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkSnapshotFreeToNode: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGskBlurNodeNew: CPointer<CFunction<(COpaquePointer?, Float) -> COpaquePointer?>>? = null
    private var fnGskCairoNodeNew: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGskCairoNodeGetDrawContext: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGskRenderNodeDraw: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGskRenderNodeUnref: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null

    private var cairoScratchArgb = IntArray(0)

    private var fnPangoCairoCreateLayout: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnPangoCairoShowLayout: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnPangoLayoutSetText: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?, Int) -> Unit>>? = null
    private var fnPangoLayoutSetWidth: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnPangoLayoutSetAlignment: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnPangoLayoutSetEllipsize: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnPangoLayoutSetWrap: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnPangoLayoutGetPixelSize: CPointer<CFunction<(COpaquePointer?, CPointer<IntVar>?, CPointer<IntVar>?) -> Unit>>? = null
    private var fnPangoLayoutSetFontDescription: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnPangoFontDescriptionNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnPangoFontDescriptionSetFamily: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnPangoFontDescriptionSetWeight: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnPangoFontDescriptionSetStyle: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnPangoFontDescriptionSetAbsoluteSize: CPointer<CFunction<(COpaquePointer?, Double) -> Unit>>? = null
    private var fnPangoFontDescriptionFree: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnPangoLayoutSetAttributes: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnPangoAttrListNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnPangoAttrUnderlineNew: CPointer<CFunction<(Int) -> COpaquePointer?>>? = null
    private var fnPangoAttrStrikethroughNew: CPointer<CFunction<(Int) -> COpaquePointer?>>? = null
    private var fnPangoAttrListInsert: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnPangoAttrListUnref: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null

    fun ensureLibrariesLoaded(): Boolean = lock.withLock {
        if (librariesLoaded) return gtkLibrariesAvailable
        librariesLoaded = true
        LinuxGlibGioBindings.ensureLoaded()

        if (getenv("GSK_RENDERER") == null && access("/dev/dri/card0", F_OK) != 0 && access("/dev/dri/renderD128", F_OK) != 0) {
            setenv("GSK_RENDERER", "cairo", 0)
        }

        val gtk = LinuxDynamicLibraryLoader.openFirst("libgtk-4.so.1", "libgtk-4.so", global = true)
        val adw = LinuxDynamicLibraryLoader.openFirst("libadwaita-1.so.0", "libadwaita-1.so", global = true)
        val cairo = LinuxDynamicLibraryLoader.openFirst("libcairo.so.2", "libcairo.so", global = true)
        val pango = LinuxDynamicLibraryLoader.openFirst("libpango-1.0.so.0", "libpango-1.0.so", global = true)
        val pangocairo = LinuxDynamicLibraryLoader.openFirst("libpangocairo-1.0.so.0", "libpangocairo-1.0.so", global = true)

        if (gtk != null) {
            fnGtkInitCheck = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_init_check")?.reinterpret()
            fnGtkWindowNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_window_new")?.reinterpret()
            fnGtkWindowSetTitle = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_window_set_title")?.reinterpret()
            fnGtkWindowSetDefaultSize = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_window_set_default_size")?.reinterpret()
            fnGtkWindowSetChild = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_window_set_child")?.reinterpret()
            fnGtkWindowSetTitlebar = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_window_set_titlebar")?.reinterpret()
            fnGtkWindowPresent = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_window_present")?.reinterpret()
            fnGtkWindowClose = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_window_close")?.reinterpret()
            fnGtkWindowDestroy = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_window_destroy")?.reinterpret()
            fnGtkWindowSetModal = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_window_set_modal")?.reinterpret()
            fnGtkWindowSetTransientFor = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_window_set_transient_for")?.reinterpret()
            fnGtkWindowFullscreen = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_window_fullscreen")?.reinterpret()
            fnGtkWindowUnfullscreen = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_window_unfullscreen")?.reinterpret()
            fnGtkWindowMaximize = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_window_maximize")?.reinterpret()
            fnGtkWindowUnmaximize = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_window_unmaximize")?.reinterpret()
            fnGtkWindowMinimize = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_window_minimize")?.reinterpret()
            fnGtkWindowIsFullscreen = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_window_is_fullscreen")?.reinterpret()
            fnGtkWindowIsMaximized = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_window_is_maximized")?.reinterpret()
            fnGtkWindowSetResizable = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_window_set_resizable")?.reinterpret()
            fnGtkWindowSetDecorated = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_window_set_decorated")?.reinterpret()
            fnGtkWindowSetHideOnClose = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_window_set_hide_on_close")?.reinterpret()
            fnGtkWidgetGetRealized = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_get_realized")?.reinterpret()
            fnGtkWidgetGetNative = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_get_native")?.reinterpret()
            fnGtkNativeGetSurface = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_native_get_surface")?.reinterpret()
            fnGdkToplevelGetType = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_toplevel_get_type")?.reinterpret()
            fnGdkToplevelGetState = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_toplevel_get_state")?.reinterpret()
            fnGdkToplevelBeginMove = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_toplevel_begin_move")?.reinterpret()
            fnGdkDisplayGetDefaultSeat = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_display_get_default_seat")?.reinterpret()
            fnGdkSeatGetPointer = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_seat_get_pointer")?.reinterpret()
            fnGdkSurfaceGetDevicePosition = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_surface_get_device_position")?.reinterpret()
            fnGdkX11SurfaceGetType = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_x11_surface_get_type")?.reinterpret()
            fnGdkX11SurfaceGetXid = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_x11_surface_get_xid")?.reinterpret()
            fnGtkHeaderBarNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_header_bar_new")?.reinterpret()
            fnGtkHeaderBarSetShowTitleButtons = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_header_bar_set_show_title_buttons")?.reinterpret()
            fnGtkHeaderBarSetDecorationLayout = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_header_bar_set_decoration_layout")?.reinterpret()
            fnGtkHeaderBarSetTitleWidget = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_header_bar_set_title_widget")?.reinterpret()
            fnGtkDrawingAreaNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_drawing_area_new")?.reinterpret()
            fnGtkDrawingAreaSetDrawFunc = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_drawing_area_set_draw_func")?.reinterpret()
            fnGtkWidgetSetSizeRequest = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_set_size_request")?.reinterpret()
            fnGtkWidgetGetWidth = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_get_width")?.reinterpret()
            fnGtkWidgetGetHeight = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_get_height")?.reinterpret()
            fnGtkWidgetSetVisible = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_set_visible")?.reinterpret()
            fnGtkWidgetQueueDraw = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_queue_draw")?.reinterpret()
            fnGtkWidgetAddController = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_add_controller")?.reinterpret()
            fnGtkWidgetSetFocusable = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_set_focusable")?.reinterpret()
            fnGtkWidgetGrabFocus = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_grab_focus")?.reinterpret()
            fnGtkWidgetSetCursor = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_set_cursor")?.reinterpret()
            fnGtkWidgetSetCursorFromName = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_set_cursor_from_name")?.reinterpret()
            fnGtkWidgetSetOpacity = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_set_opacity")?.reinterpret()
            fnGtkWidgetGetDisplay = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_get_display")?.reinterpret()
            fnGtkWidgetSetHexpand = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_set_hexpand")?.reinterpret()
            fnGtkWidgetSetVexpand = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_set_vexpand")?.reinterpret()
            fnGtkWidgetSetMargins[0] = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_set_margin_top")?.reinterpret()
            fnGtkWidgetSetMargins[1] = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_set_margin_bottom")?.reinterpret()
            fnGtkWidgetSetMargins[2] = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_set_margin_start")?.reinterpret()
            fnGtkWidgetSetMargins[3] = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_set_margin_end")?.reinterpret()
            fnGtkBoxNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_box_new")?.reinterpret()
            fnGtkBoxAppend = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_box_append")?.reinterpret()
            fnGtkLabelNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_label_new")?.reinterpret()
            fnGtkLabelSetWrap = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_label_set_wrap")?.reinterpret()
            fnGtkEntryNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_entry_new")?.reinterpret()
            fnGtkEditableGetText = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_editable_get_text")?.reinterpret()
            fnGtkEditableSetText = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_editable_set_text")?.reinterpret()
            fnGtkButtonNewWithLabel = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_button_new_with_label")?.reinterpret()
            fnGtkDropDownNewFromStrings = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_drop_down_new_from_strings")?.reinterpret()
            fnGtkDropDownGetSelected = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_drop_down_get_selected")?.reinterpret()
            fnGtkDropDownSetSelected = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_drop_down_set_selected")?.reinterpret()
            fnGtkGestureClickNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_gesture_click_new")?.reinterpret()
            fnGtkGestureSingleSetButton = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_gesture_single_set_button")?.reinterpret()
            fnGtkEventControllerMotionNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_event_controller_motion_new")?.reinterpret()
            fnGtkEventControllerScrollNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_event_controller_scroll_new")?.reinterpret()
            fnGtkEventControllerKeyNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_event_controller_key_new")?.reinterpret()
            fnGdkKeyvalName = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_keyval_name")?.reinterpret()
            fnGdkKeyvalToUnicode = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_keyval_to_unicode")?.reinterpret()
            fnGdkCursorNewFromName = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_cursor_new_from_name")?.reinterpret()
            fnGdkMemoryTextureNew = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_memory_texture_new")?.reinterpret()
            fnGdkCursorNewFromTexture = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_cursor_new_from_texture")?.reinterpret()
            fnGdkDisplayGetClipboard = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_display_get_clipboard")?.reinterpret()
            fnGdkDisplayGetDefault = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_display_get_default")?.reinterpret()
            fnGdkDisplayGetMonitors = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_display_get_monitors")?.reinterpret()
            fnGdkMonitorGetGeometry = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_monitor_get_geometry")?.reinterpret()
            fnGdkClipboardSetText = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_clipboard_set_text")?.reinterpret()
            fnGdkClipboardReadTextAsync = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_clipboard_read_text_async")?.reinterpret()
            fnGdkClipboardReadTextFinish = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_clipboard_read_text_finish")?.reinterpret()
            fnGdkRgbaFree = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_rgba_free")?.reinterpret()
            fnAlertDialogNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_alert_dialog_new")?.reinterpret()
            fnAlertDialogSetDetail = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_alert_dialog_set_detail")?.reinterpret()
            fnAlertDialogSetButtons = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_alert_dialog_set_buttons")?.reinterpret()
            fnAlertDialogSetDefaultButton = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_alert_dialog_set_default_button")?.reinterpret()
            fnAlertDialogSetCancelButton = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_alert_dialog_set_cancel_button")?.reinterpret()
            fnAlertDialogChoose = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_alert_dialog_choose")?.reinterpret()
            fnAlertDialogChooseFinish = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_alert_dialog_choose_finish")?.reinterpret()
            fnFileDialogNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_file_dialog_new")?.reinterpret()
            fnFileDialogSetTitle = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_file_dialog_set_title")?.reinterpret()
            fnFileDialogSetInitialFolder = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_file_dialog_set_initial_folder")?.reinterpret()
            fnFileDialogSetInitialName = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_file_dialog_set_initial_name")?.reinterpret()
            fnFileDialogSetFilters = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_file_dialog_set_filters")?.reinterpret()
            fnFileDialogOpen = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_file_dialog_open")?.reinterpret()
            fnFileDialogOpenFinish = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_file_dialog_open_finish")?.reinterpret()
            fnFileDialogOpenMultiple = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_file_dialog_open_multiple")?.reinterpret()
            fnFileDialogOpenMultipleFinish = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_file_dialog_open_multiple_finish")?.reinterpret()
            fnFileDialogSelectFolder = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_file_dialog_select_folder")?.reinterpret()
            fnFileDialogSelectFolderFinish = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_file_dialog_select_folder_finish")?.reinterpret()
            fnFileDialogSelectMultipleFolders = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_file_dialog_select_multiple_folders")?.reinterpret()
            fnFileDialogSelectMultipleFoldersFinish = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_file_dialog_select_multiple_folders_finish")?.reinterpret()
            fnFileDialogSave = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_file_dialog_save")?.reinterpret()
            fnFileDialogSaveFinish = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_file_dialog_save_finish")?.reinterpret()
            fnFileFilterNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_file_filter_new")?.reinterpret()
            fnFileFilterSetName = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_file_filter_set_name")?.reinterpret()
            fnFileFilterAddPattern = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_file_filter_add_pattern")?.reinterpret()
            fnFileFilterGetType = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_file_filter_get_type")?.reinterpret()
            fnColorDialogNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_color_dialog_new")?.reinterpret()
            fnColorDialogSetTitle = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_color_dialog_set_title")?.reinterpret()
            fnColorDialogSetWithAlpha = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_color_dialog_set_with_alpha")?.reinterpret()
            fnColorDialogChooseRgba = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_color_dialog_choose_rgba")?.reinterpret()
            fnColorDialogChooseRgbaFinish = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_color_dialog_choose_rgba_finish")?.reinterpret()
            fnGtkSnapshotNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_snapshot_new")?.reinterpret()
            fnGtkSnapshotPushBlur = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_snapshot_push_blur")?.reinterpret()
            fnGtkSnapshotPop = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_snapshot_pop")?.reinterpret()
            fnGtkSnapshotAppendCairo = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_snapshot_append_cairo")?.reinterpret()
            fnGtkSnapshotFreeToNode = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_snapshot_free_to_node")?.reinterpret()
            fnGskBlurNodeNew = LinuxDynamicLibraryLoader.symbol(gtk, "gsk_blur_node_new")?.reinterpret()
            fnGskCairoNodeNew = LinuxDynamicLibraryLoader.symbol(gtk, "gsk_cairo_node_new")?.reinterpret()
            fnGskCairoNodeGetDrawContext = LinuxDynamicLibraryLoader.symbol(gtk, "gsk_cairo_node_get_draw_context")?.reinterpret()
            fnGskRenderNodeDraw = LinuxDynamicLibraryLoader.symbol(gtk, "gsk_render_node_draw")?.reinterpret()
            fnGskRenderNodeUnref = LinuxDynamicLibraryLoader.symbol(gtk, "gsk_render_node_unref")?.reinterpret()
        }

        if (adw != null) {
            fnAdwInit = LinuxDynamicLibraryLoader.symbol(adw, "adw_init")?.reinterpret()
            fnAdwStyleManagerGetDefault = LinuxDynamicLibraryLoader.symbol(adw, "adw_style_manager_get_default")?.reinterpret()
            fnAdwStyleManagerSetColorScheme = LinuxDynamicLibraryLoader.symbol(adw, "adw_style_manager_set_color_scheme")?.reinterpret()
            fnAdwStyleManagerGetDark = LinuxDynamicLibraryLoader.symbol(adw, "adw_style_manager_get_dark")?.reinterpret()
        }

        if (cairo != null) {
            fnCairoCreate = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_create")?.reinterpret()
            fnCairoDestroy = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_destroy")?.reinterpret()
            fnCairoSave = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_save")?.reinterpret()
            fnCairoRestore = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_restore")?.reinterpret()
            fnCairoRectangle = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_rectangle")?.reinterpret()
            fnCairoClip = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_clip")?.reinterpret()
            fnCairoNewPath = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_new_path")?.reinterpret()
            fnCairoNewSubPath = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_new_sub_path")?.reinterpret()
            fnCairoClosePath = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_close_path")?.reinterpret()
            fnCairoMoveTo = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_move_to")?.reinterpret()
            fnCairoLineTo = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_line_to")?.reinterpret()
            fnCairoArc = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_arc")?.reinterpret()
            fnCairoFill = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_fill")?.reinterpret()
            fnCairoFillPreserve = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_fill_preserve")?.reinterpret()
            fnCairoStroke = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_stroke")?.reinterpret()
            fnCairoPaint = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_paint")?.reinterpret()
            fnCairoPaintWithAlpha = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_paint_with_alpha")?.reinterpret()
            fnCairoSetSourceRgba = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_set_source_rgba")?.reinterpret()
            fnCairoSetLineWidth = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_set_line_width")?.reinterpret()
            fnCairoSetLineCap = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_set_line_cap")?.reinterpret()
            fnCairoPatternCreateLinear = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_pattern_create_linear")?.reinterpret()
            fnCairoPatternAddColorStopRgba = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_pattern_add_color_stop_rgba")?.reinterpret()
            fnCairoSetSource = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_set_source")?.reinterpret()
            fnCairoPatternDestroy = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_pattern_destroy")?.reinterpret()
            fnCairoImageSurfaceCreateForData = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_image_surface_create_for_data")?.reinterpret()
            fnCairoGetTarget = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_get_target")?.reinterpret()
            fnCairoUserToDevice = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_user_to_device")?.reinterpret()
            fnCairoImageSurfaceGetData = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_image_surface_get_data")?.reinterpret()
            fnCairoImageSurfaceGetFormat = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_image_surface_get_format")?.reinterpret()
            fnCairoImageSurfaceGetWidth = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_image_surface_get_width")?.reinterpret()
            fnCairoImageSurfaceGetHeight = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_image_surface_get_height")?.reinterpret()
            fnCairoImageSurfaceGetStride = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_image_surface_get_stride")?.reinterpret()
            fnCairoSurfaceFlush = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_surface_flush")?.reinterpret()
            fnCairoSurfaceMarkDirty = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_surface_mark_dirty")?.reinterpret()
            fnCairoSurfaceDestroy = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_surface_destroy")?.reinterpret()
            fnCairoSetSourceSurface = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_set_source_surface")?.reinterpret()
            fnCairoTranslate = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_translate")?.reinterpret()
            fnCairoScale = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_scale")?.reinterpret()
        }

        if (pango != null && pangocairo != null) {
            fnPangoCairoCreateLayout = LinuxDynamicLibraryLoader.symbol(pangocairo, "pango_cairo_create_layout")?.reinterpret()
            fnPangoCairoShowLayout = LinuxDynamicLibraryLoader.symbol(pangocairo, "pango_cairo_show_layout")?.reinterpret()
            fnPangoLayoutSetText = LinuxDynamicLibraryLoader.symbol(pango, "pango_layout_set_text")?.reinterpret()
            fnPangoLayoutSetWidth = LinuxDynamicLibraryLoader.symbol(pango, "pango_layout_set_width")?.reinterpret()
            fnPangoLayoutSetAlignment = LinuxDynamicLibraryLoader.symbol(pango, "pango_layout_set_alignment")?.reinterpret()
            fnPangoLayoutSetEllipsize = LinuxDynamicLibraryLoader.symbol(pango, "pango_layout_set_ellipsize")?.reinterpret()
            fnPangoLayoutSetWrap = LinuxDynamicLibraryLoader.symbol(pango, "pango_layout_set_wrap")?.reinterpret()
            fnPangoLayoutGetPixelSize = LinuxDynamicLibraryLoader.symbol(pango, "pango_layout_get_pixel_size")?.reinterpret()
            fnPangoLayoutSetFontDescription = LinuxDynamicLibraryLoader.symbol(pango, "pango_layout_set_font_description")?.reinterpret()
            fnPangoFontDescriptionNew = LinuxDynamicLibraryLoader.symbol(pango, "pango_font_description_new")?.reinterpret()
            fnPangoFontDescriptionSetFamily = LinuxDynamicLibraryLoader.symbol(pango, "pango_font_description_set_family")?.reinterpret()
            fnPangoFontDescriptionSetWeight = LinuxDynamicLibraryLoader.symbol(pango, "pango_font_description_set_weight")?.reinterpret()
            fnPangoFontDescriptionSetStyle = LinuxDynamicLibraryLoader.symbol(pango, "pango_font_description_set_style")?.reinterpret()
            fnPangoFontDescriptionSetAbsoluteSize = LinuxDynamicLibraryLoader.symbol(pango, "pango_font_description_set_absolute_size")?.reinterpret()
            fnPangoFontDescriptionFree = LinuxDynamicLibraryLoader.symbol(pango, "pango_font_description_free")?.reinterpret()
            fnPangoLayoutSetAttributes = LinuxDynamicLibraryLoader.symbol(pango, "pango_layout_set_attributes")?.reinterpret()
            fnPangoAttrListNew = LinuxDynamicLibraryLoader.symbol(pango, "pango_attr_list_new")?.reinterpret()
            fnPangoAttrUnderlineNew = LinuxDynamicLibraryLoader.symbol(pango, "pango_attr_underline_new")?.reinterpret()
            fnPangoAttrStrikethroughNew = LinuxDynamicLibraryLoader.symbol(pango, "pango_attr_strikethrough_new")?.reinterpret()
            fnPangoAttrListInsert = LinuxDynamicLibraryLoader.symbol(pango, "pango_attr_list_insert")?.reinterpret()
            fnPangoAttrListUnref = LinuxDynamicLibraryLoader.symbol(pango, "pango_attr_list_unref")?.reinterpret()
        }

        gtkLibrariesAvailable = fnGtkInitCheck != null && fnGtkWindowNew != null && fnGtkDrawingAreaNew != null && fnCairoCreate != null
        gtkLibrariesAvailable
    }

    fun ensureGtkInitialized(): Boolean = lock.withLock {
        if (gtkInitializedAttempted) return gtkInitializedOk
        if (!ensureLibrariesLoaded()) {
            gtkInitializedAttempted = true
            gtkInitializedOk = false
            return false
        }
        val hasDisplay = !getenv("DISPLAY")?.toKString().isNullOrBlank() ||
            !getenv("WAYLAND_DISPLAY")?.toKString().isNullOrBlank()
        if (!hasDisplay) {
            gtkInitializedAttempted = true
            gtkInitializedOk = false
            return false
        }
        val ok = (fnGtkInitCheck?.invoke() ?: 0) != 0
        if (ok) {
            fnAdwInit?.invoke()
        }
        gtkInitializedAttempted = true
        gtkInitializedOk = ok
        ok
    }

    fun adwStyleManagerGetDark(): Boolean? {
        if (!gtkInitializedOk) return null
        val mgr = fnAdwStyleManagerGetDefault?.invoke() ?: return null
        return (fnAdwStyleManagerGetDark?.invoke(mgr) ?: 0) != 0
    }

    fun adwStyleManagerSetColorScheme(scheme: Int) {
        if (!gtkInitializedOk) return
        val mgr = fnAdwStyleManagerGetDefault?.invoke() ?: return
        fnAdwStyleManagerSetColorScheme?.invoke(mgr, scheme)
    }

    fun gtkWindowNew(): COpaquePointer? = fnGtkWindowNew?.invoke()
    fun gtkWindowSetTitle(win: COpaquePointer, title: String) = memScoped { fnGtkWindowSetTitle?.invoke(win, title.cstr.ptr) }
    fun gtkWindowSetDefaultSize(win: COpaquePointer, w: Int, h: Int) { fnGtkWindowSetDefaultSize?.invoke(win, w, h) }
    fun gtkWindowSetChild(win: COpaquePointer, child: COpaquePointer?) { fnGtkWindowSetChild?.invoke(win, child) }
    fun gtkWindowSetTitlebar(win: COpaquePointer, titlebar: COpaquePointer?) { fnGtkWindowSetTitlebar?.invoke(win, titlebar) }
    fun gtkWindowPresent(win: COpaquePointer) { fnGtkWindowPresent?.invoke(win) }
    fun gtkWindowClose(win: COpaquePointer) { fnGtkWindowClose?.invoke(win) }
    fun gtkWindowDestroy(win: COpaquePointer) { fnGtkWindowDestroy?.invoke(win) }
    fun gtkWindowSetModal(win: COpaquePointer, modal: Boolean) { fnGtkWindowSetModal?.invoke(win, if (modal) 1 else 0) }
    fun gtkWindowSetTransientFor(win: COpaquePointer, parent: COpaquePointer?) { fnGtkWindowSetTransientFor?.invoke(win, parent) }
    fun gtkWindowFullscreen(win: COpaquePointer) { fnGtkWindowFullscreen?.invoke(win) }
    fun gtkWindowUnfullscreen(win: COpaquePointer) { fnGtkWindowUnfullscreen?.invoke(win) }
    fun gtkWindowMaximize(win: COpaquePointer) { fnGtkWindowMaximize?.invoke(win) }
    fun gtkWindowUnmaximize(win: COpaquePointer) { fnGtkWindowUnmaximize?.invoke(win) }
    fun gtkWindowMinimize(win: COpaquePointer) { fnGtkWindowMinimize?.invoke(win) }
    fun gtkWindowIsFullscreen(win: COpaquePointer): Boolean = (fnGtkWindowIsFullscreen?.invoke(win) ?: 0) != 0
    fun gtkWindowIsMaximized(win: COpaquePointer): Boolean = (fnGtkWindowIsMaximized?.invoke(win) ?: 0) != 0
    fun gtkWindowSurface(win: COpaquePointer): COpaquePointer? {
        if ((fnGtkWidgetGetRealized?.invoke(win) ?: 0) == 0) return null
        val native = fnGtkWidgetGetNative?.invoke(win) ?: win
        return fnGtkNativeGetSurface?.invoke(native)
    }
    fun gtkWindowIsMinimized(win: COpaquePointer): Boolean {
        val surface = gtkWindowSurface(win) ?: return false
        val toplevelType = fnGdkToplevelGetType?.invoke() ?: 0uL
        if (toplevelType == 0uL || !LinuxGlibGioBindings.typeCheckInstanceIsA(surface, toplevelType)) return false
        val state = fnGdkToplevelGetState?.invoke(surface) ?: 0u
        return (state and GDK_TOPLEVEL_STATE_MINIMIZED) != 0u
    }
    fun gtkWindowBeginMove(
        win: COpaquePointer,
        fallbackX: Double = 0.0,
        fallbackY: Double = 0.0,
        button: Int = 1,
        timestamp: UInt = 0u
    ): Boolean = memScoped {
        val surface = gtkWindowSurface(win) ?: return@memScoped false
        val toplevelType = fnGdkToplevelGetType?.invoke() ?: 0uL
        if (toplevelType == 0uL || !LinuxGlibGioBindings.typeCheckInstanceIsA(surface, toplevelType)) return@memScoped false
        val display = fnGtkWidgetGetDisplay?.invoke(win) ?: fnGdkDisplayGetDefault?.invoke() ?: return@memScoped false
        val seat = fnGdkDisplayGetDefaultSeat?.invoke(display) ?: return@memScoped false
        val pointer = fnGdkSeatGetPointer?.invoke(seat) ?: return@memScoped false
        val xVar = alloc<DoubleVar>()
        val yVar = alloc<DoubleVar>()
        xVar.value = fallbackX
        yVar.value = fallbackY
        fnGdkSurfaceGetDevicePosition?.invoke(surface, pointer, xVar.ptr, yVar.ptr, null)
        fnGdkToplevelBeginMove?.invoke(surface, pointer, button, xVar.value, yVar.value, timestamp)
        fnGdkToplevelBeginMove != null
    }
    fun gtkWindowX11Xid(win: COpaquePointer): ULong {
        if ((fnGtkWidgetGetRealized?.invoke(win) ?: 0) == 0) return 0uL
        val native = fnGtkWidgetGetNative?.invoke(win) ?: win
        val surface = fnGtkNativeGetSurface?.invoke(native) ?: return 0uL
        val x11Type = fnGdkX11SurfaceGetType?.invoke() ?: 0uL
        if (x11Type == 0uL || !LinuxGlibGioBindings.typeCheckInstanceIsA(surface, x11Type)) return 0uL
        return fnGdkX11SurfaceGetXid?.invoke(surface) ?: 0uL
    }
    fun gtkWindowSetResizable(win: COpaquePointer, resizable: Boolean) { fnGtkWindowSetResizable?.invoke(win, if (resizable) 1 else 0) }
    fun gtkWindowSetDecorated(win: COpaquePointer, decorated: Boolean) { fnGtkWindowSetDecorated?.invoke(win, if (decorated) 1 else 0) }
    fun gtkWindowSetHideOnClose(win: COpaquePointer, hide: Boolean) { fnGtkWindowSetHideOnClose?.invoke(win, if (hide) 1 else 0) }

    fun gtkHeaderBarNew(): COpaquePointer? = fnGtkHeaderBarNew?.invoke()
    fun gtkHeaderBarSetShowTitleButtons(hb: COpaquePointer, show: Boolean) { fnGtkHeaderBarSetShowTitleButtons?.invoke(hb, if (show) 1 else 0) }
    fun gtkHeaderBarSetDecorationLayout(hb: COpaquePointer, layout: String?) = memScoped {
        fnGtkHeaderBarSetDecorationLayout?.invoke(hb, layout?.cstr?.ptr)
    }
    fun gtkHeaderBarSetTitleWidget(hb: COpaquePointer, widget: COpaquePointer?) { fnGtkHeaderBarSetTitleWidget?.invoke(hb, widget) }

    fun gtkDrawingAreaNew(): COpaquePointer? = fnGtkDrawingAreaNew?.invoke()
    fun gtkDrawingAreaSetDrawFunc(da: COpaquePointer, drawFunc: COpaquePointer?, userData: COpaquePointer?, destroy: COpaquePointer? = null) {
        fnGtkDrawingAreaSetDrawFunc?.invoke(da, drawFunc, userData, destroy)
    }

    fun gtkWidgetSetSizeRequest(widget: COpaquePointer, w: Int, h: Int) { fnGtkWidgetSetSizeRequest?.invoke(widget, w, h) }
    fun gtkWidgetGetWidth(widget: COpaquePointer): Int = fnGtkWidgetGetWidth?.invoke(widget) ?: 0
    fun gtkWidgetGetHeight(widget: COpaquePointer): Int = fnGtkWidgetGetHeight?.invoke(widget) ?: 0
    fun gtkWidgetSetVisible(widget: COpaquePointer, visible: Boolean) { fnGtkWidgetSetVisible?.invoke(widget, if (visible) 1 else 0) }
    fun gtkWidgetQueueDraw(widget: COpaquePointer) { fnGtkWidgetQueueDraw?.invoke(widget) }
    fun gtkWidgetAddController(widget: COpaquePointer, controller: COpaquePointer) { fnGtkWidgetAddController?.invoke(widget, controller) }
    fun gtkWidgetSetFocusable(widget: COpaquePointer, focusable: Boolean) { fnGtkWidgetSetFocusable?.invoke(widget, if (focusable) 1 else 0) }
    fun gtkWidgetGrabFocus(widget: COpaquePointer) { fnGtkWidgetGrabFocus?.invoke(widget) }
    fun gtkWidgetSetCursor(widget: COpaquePointer, cursor: COpaquePointer?) { fnGtkWidgetSetCursor?.invoke(widget, cursor) }
    fun gtkWidgetSetCursorFromName(widget: COpaquePointer, name: String?) = memScoped {
        fnGtkWidgetSetCursorFromName?.invoke(widget, name?.cstr?.ptr)
    }
    fun gtkWidgetSetOpacity(widget: COpaquePointer, opacity: Double) { fnGtkWidgetSetOpacity?.invoke(widget, opacity) }
    fun gtkWidgetGetDisplay(widget: COpaquePointer): COpaquePointer? = fnGtkWidgetGetDisplay?.invoke(widget)
    fun gtkWidgetSetExpand(widget: COpaquePointer, hexpand: Boolean, vexpand: Boolean) {
        fnGtkWidgetSetHexpand?.invoke(widget, if (hexpand) 1 else 0)
        fnGtkWidgetSetVexpand?.invoke(widget, if (vexpand) 1 else 0)
    }
    fun gtkWidgetSetMargins(widget: COpaquePointer, top: Int, bottom: Int, start: Int, end: Int) {
        fnGtkWidgetSetMargins[0]?.invoke(widget, top)
        fnGtkWidgetSetMargins[1]?.invoke(widget, bottom)
        fnGtkWidgetSetMargins[2]?.invoke(widget, start)
        fnGtkWidgetSetMargins[3]?.invoke(widget, end)
    }

    fun gtkBoxNew(orientation: Int, spacing: Int): COpaquePointer? = fnGtkBoxNew?.invoke(orientation, spacing)
    fun gtkBoxAppend(box: COpaquePointer, child: COpaquePointer) { fnGtkBoxAppend?.invoke(box, child) }
    fun gtkLabelNew(text: String): COpaquePointer? = memScoped { fnGtkLabelNew?.invoke(text.cstr.ptr) }
    fun gtkLabelSetWrap(label: COpaquePointer, wrap: Boolean) { fnGtkLabelSetWrap?.invoke(label, if (wrap) 1 else 0) }
    fun gtkEntryNew(): COpaquePointer? = fnGtkEntryNew?.invoke()
    fun gtkEditableGetText(editable: COpaquePointer): String = fnGtkEditableGetText?.invoke(editable)?.toKString() ?: ""
    fun gtkEditableSetText(editable: COpaquePointer, text: String) = memScoped { fnGtkEditableSetText?.invoke(editable, text.cstr.ptr) }
    fun gtkButtonNewWithLabel(label: String): COpaquePointer? = memScoped { fnGtkButtonNewWithLabel?.invoke(label.cstr.ptr) }

    fun gtkDropDownNewFromStrings(options: List<String>): COpaquePointer? = memScoped {
        val arr = allocArray<CPointerVar<ByteVar>>(options.size + 1)
        for (i in options.indices) arr[i] = options[i].cstr.ptr
        arr[options.size] = null
        fnGtkDropDownNewFromStrings?.invoke(arr)
    }
    fun gtkDropDownGetSelected(dropdown: COpaquePointer): Int = (fnGtkDropDownGetSelected?.invoke(dropdown) ?: 0u).toInt()
    fun gtkDropDownSetSelected(dropdown: COpaquePointer, index: Int) { fnGtkDropDownSetSelected?.invoke(dropdown, index.toUInt()) }

    fun gtkGestureClickNew(): COpaquePointer? = fnGtkGestureClickNew?.invoke()
    fun gtkGestureSingleSetButton(gesture: COpaquePointer, button: UInt) { fnGtkGestureSingleSetButton?.invoke(gesture, button) }
    fun gtkEventControllerMotionNew(): COpaquePointer? = fnGtkEventControllerMotionNew?.invoke()
    fun gtkEventControllerScrollNew(flags: UInt): COpaquePointer? = fnGtkEventControllerScrollNew?.invoke(flags)
    fun gtkEventControllerKeyNew(): COpaquePointer? = fnGtkEventControllerKeyNew?.invoke()

    fun gdkKeyvalName(keyval: UInt): String? = fnGdkKeyvalName?.invoke(keyval)?.toKString()
    fun gdkKeyvalToUnicode(keyval: UInt): UInt = fnGdkKeyvalToUnicode?.invoke(keyval) ?: 0u
    fun gdkCursorNewFromName(name: String): COpaquePointer? = memScoped { fnGdkCursorNewFromName?.invoke(name.cstr.ptr, null) }

    fun gdkMemoryTextureNew(width: Int, height: Int, rgbaBytes: ByteArray): COpaquePointer? {
        val gbytes = LinuxGlibGioBindings.bytesNew(rgbaBytes) ?: return null
        return try {
            fnGdkMemoryTextureNew?.invoke(width, height, GDK_MEMORY_R8G8B8A8, gbytes, (width * 4).convert())
        } finally {
            LinuxGlibGioBindings.bytesUnref(gbytes)
        }
    }

    fun gdkCursorNewFromTexture(texture: COpaquePointer, hotX: Int, hotY: Int, fallback: COpaquePointer? = null): COpaquePointer? =
        fnGdkCursorNewFromTexture?.invoke(texture, hotX, hotY, fallback)

    fun gdkDisplayGetClipboard(display: COpaquePointer): COpaquePointer? = fnGdkDisplayGetClipboard?.invoke(display)

    fun getPrimaryMonitorBounds(): com.nexustools.genericui.geometry.Rect? {
        if (!ensureGtkInitialized()) return null
        val dpy = fnGdkDisplayGetDefault?.invoke() ?: return null
        val monitors = fnGdkDisplayGetMonitors?.invoke(dpy) ?: return null
        val count = LinuxGlibGioBindings.listModelGetNItems(monitors)
        if (count <= 0) return null
        val mon = LinuxGlibGioBindings.listModelGetItem(monitors, 0) ?: return null
        return try {
            memScoped {
                val rectBuf = allocArray<IntVar>(4)
                rectBuf[0] = 0; rectBuf[1] = 0; rectBuf[2] = 0; rectBuf[3] = 0
                fnGdkMonitorGetGeometry?.invoke(mon, rectBuf)
                val x = rectBuf[0]
                val y = rectBuf[1]
                val w = rectBuf[2]
                val h = rectBuf[3]
                if (w > 0 && h > 0) com.nexustools.genericui.geometry.Rect(x, y, w, h) else null
            }
        } finally {
            LinuxGlibGioBindings.objectUnref(mon)
        }
    }
    fun gdkClipboardSetText(clipboard: COpaquePointer, text: String) = memScoped { fnGdkClipboardSetText?.invoke(clipboard, text.cstr.ptr) }
    fun gdkClipboardReadTextAsync(clipboard: COpaquePointer, callback: COpaquePointer, userData: COpaquePointer?) {
        fnGdkClipboardReadTextAsync?.invoke(clipboard, null, callback, userData)
    }
    fun gdkClipboardReadTextFinish(clipboard: COpaquePointer, res: COpaquePointer): String? = memScoped {
        val err = alloc<COpaquePointerVar>()
        err.value = null
        val txtPtr = fnGdkClipboardReadTextFinish?.invoke(clipboard, res, err.ptr)
        LinuxGlibGioBindings.readAndFreeError(err.ptr)
        val result = txtPtr?.toKString()
        LinuxGlibGioBindings.free(txtPtr)
        result
    }

    fun gtkAlertDialogNew(message: String): COpaquePointer? = memScoped {
        fnAlertDialogNew?.invoke("%s".cstr.ptr, message.cstr.ptr)
    }
    fun gtkAlertDialogSetDetail(dlg: COpaquePointer, detail: String) = memScoped { fnAlertDialogSetDetail?.invoke(dlg, detail.cstr.ptr) }
    fun gtkAlertDialogSetButtons(dlg: COpaquePointer, labels: List<String>) = memScoped {
        val arr = allocArray<CPointerVar<ByteVar>>(labels.size + 1)
        for (i in labels.indices) arr[i] = labels[i].cstr.ptr
        arr[labels.size] = null
        fnAlertDialogSetButtons?.invoke(dlg, arr)
    }
    fun gtkAlertDialogSetDefaultButton(dlg: COpaquePointer, idx: Int) { fnAlertDialogSetDefaultButton?.invoke(dlg, idx) }
    fun gtkAlertDialogSetCancelButton(dlg: COpaquePointer, idx: Int) { fnAlertDialogSetCancelButton?.invoke(dlg, idx) }
    fun gtkAlertDialogChoose(dlg: COpaquePointer, parent: COpaquePointer?, callback: COpaquePointer, userData: COpaquePointer?) {
        fnAlertDialogChoose?.invoke(dlg, parent, null, callback, userData)
    }
    fun gtkAlertDialogChooseFinish(dlg: COpaquePointer, res: COpaquePointer): Int = memScoped {
        val err = alloc<COpaquePointerVar>()
        err.value = null
        val idx = fnAlertDialogChooseFinish?.invoke(dlg, res, err.ptr) ?: -1
        LinuxGlibGioBindings.readAndFreeError(err.ptr)
        idx
    }

    fun gtkFileDialogNew(): COpaquePointer? = fnFileDialogNew?.invoke()
    fun gtkFileDialogSetTitle(dlg: COpaquePointer, title: String) = memScoped { fnFileDialogSetTitle?.invoke(dlg, title.cstr.ptr) }
    fun gtkFileDialogSetInitialFolder(dlg: COpaquePointer, folder: COpaquePointer?) { fnFileDialogSetInitialFolder?.invoke(dlg, folder) }
    fun gtkFileDialogSetInitialName(dlg: COpaquePointer, name: String) = memScoped { fnFileDialogSetInitialName?.invoke(dlg, name.cstr.ptr) }
    fun gtkFileDialogSetFilters(dlg: COpaquePointer, filtersStore: COpaquePointer?) { fnFileDialogSetFilters?.invoke(dlg, filtersStore) }
    fun gtkFileDialogOpen(dlg: COpaquePointer, parent: COpaquePointer?, callback: COpaquePointer, userData: COpaquePointer?) {
        fnFileDialogOpen?.invoke(dlg, parent, null, callback, userData)
    }
    fun gtkFileDialogOpenFinish(dlg: COpaquePointer, res: COpaquePointer): COpaquePointer? = memScoped {
        val err = alloc<COpaquePointerVar>(); err.value = null
        val r = fnFileDialogOpenFinish?.invoke(dlg, res, err.ptr)
        LinuxGlibGioBindings.readAndFreeError(err.ptr); r
    }
    fun gtkFileDialogOpenMultiple(dlg: COpaquePointer, parent: COpaquePointer?, callback: COpaquePointer, userData: COpaquePointer?) {
        fnFileDialogOpenMultiple?.invoke(dlg, parent, null, callback, userData)
    }
    fun gtkFileDialogOpenMultipleFinish(dlg: COpaquePointer, res: COpaquePointer): COpaquePointer? = memScoped {
        val err = alloc<COpaquePointerVar>(); err.value = null
        val r = fnFileDialogOpenMultipleFinish?.invoke(dlg, res, err.ptr)
        LinuxGlibGioBindings.readAndFreeError(err.ptr); r
    }
    fun gtkFileDialogSelectFolder(dlg: COpaquePointer, parent: COpaquePointer?, callback: COpaquePointer, userData: COpaquePointer?) {
        fnFileDialogSelectFolder?.invoke(dlg, parent, null, callback, userData)
    }
    fun gtkFileDialogSelectFolderFinish(dlg: COpaquePointer, res: COpaquePointer): COpaquePointer? = memScoped {
        val err = alloc<COpaquePointerVar>(); err.value = null
        val r = fnFileDialogSelectFolderFinish?.invoke(dlg, res, err.ptr)
        LinuxGlibGioBindings.readAndFreeError(err.ptr); r
    }
    fun gtkFileDialogSelectMultipleFolders(dlg: COpaquePointer, parent: COpaquePointer?, callback: COpaquePointer, userData: COpaquePointer?) {
        fnFileDialogSelectMultipleFolders?.invoke(dlg, parent, null, callback, userData)
    }
    fun gtkFileDialogSelectMultipleFoldersFinish(dlg: COpaquePointer, res: COpaquePointer): COpaquePointer? = memScoped {
        val err = alloc<COpaquePointerVar>(); err.value = null
        val r = fnFileDialogSelectMultipleFoldersFinish?.invoke(dlg, res, err.ptr)
        LinuxGlibGioBindings.readAndFreeError(err.ptr); r
    }
    fun gtkFileDialogSave(dlg: COpaquePointer, parent: COpaquePointer?, callback: COpaquePointer, userData: COpaquePointer?) {
        fnFileDialogSave?.invoke(dlg, parent, null, callback, userData)
    }
    fun gtkFileDialogSaveFinish(dlg: COpaquePointer, res: COpaquePointer): COpaquePointer? = memScoped {
        val err = alloc<COpaquePointerVar>(); err.value = null
        val r = fnFileDialogSaveFinish?.invoke(dlg, res, err.ptr)
        LinuxGlibGioBindings.readAndFreeError(err.ptr); r
    }
    fun gtkFileFilterNew(): COpaquePointer? = fnFileFilterNew?.invoke()
    fun gtkFileFilterSetName(filter: COpaquePointer, name: String) = memScoped { fnFileFilterSetName?.invoke(filter, name.cstr.ptr) }
    fun gtkFileFilterAddPattern(filter: COpaquePointer, pattern: String) = memScoped { fnFileFilterAddPattern?.invoke(filter, pattern.cstr.ptr) }
    fun gtkFileFilterGetType(): ULong = fnFileFilterGetType?.invoke() ?: 0uL

    fun gtkColorDialogNew(): COpaquePointer? = fnColorDialogNew?.invoke()
    fun gtkColorDialogSetTitle(dlg: COpaquePointer, title: String) = memScoped { fnColorDialogSetTitle?.invoke(dlg, title.cstr.ptr) }
    fun gtkColorDialogSetWithAlpha(dlg: COpaquePointer, withAlpha: Boolean) { fnColorDialogSetWithAlpha?.invoke(dlg, if (withAlpha) 1 else 0) }
    fun gtkColorDialogChooseRgba(dlg: COpaquePointer, parent: COpaquePointer?, r: Float, g: Float, b: Float, a: Float, callback: COpaquePointer, userData: COpaquePointer?) {
        memScoped {
            val rgba = allocArray<FloatVar>(4)
            rgba[0] = r; rgba[1] = g; rgba[2] = b; rgba[3] = a
            fnColorDialogChooseRgba?.invoke(dlg, parent, rgba, null, callback, userData)
        }
    }
    fun gtkColorDialogChooseRgbaFinish(dlg: COpaquePointer, res: COpaquePointer): FloatArray? = memScoped {
        val err = alloc<COpaquePointerVar>(); err.value = null
        val ptr = fnColorDialogChooseRgbaFinish?.invoke(dlg, res, err.ptr)
        LinuxGlibGioBindings.readAndFreeError(err.ptr)
        if (ptr == null) return@memScoped null
        val floatPtr = ptr.reinterpret<FloatVar>()
        val result = floatArrayOf(floatPtr[0], floatPtr[1], floatPtr[2], floatPtr[3])
        fnGdkRgbaFree?.invoke(ptr)
        result
    }

    fun cairoSave(cr: COpaquePointer) { fnCairoSave?.invoke(cr) }
    fun cairoRestore(cr: COpaquePointer) { fnCairoRestore?.invoke(cr) }
    fun cairoRectangle(cr: COpaquePointer, x: Double, y: Double, w: Double, h: Double) { fnCairoRectangle?.invoke(cr, x, y, w, h) }
    fun cairoClip(cr: COpaquePointer) { fnCairoClip?.invoke(cr) }
    fun cairoNewPath(cr: COpaquePointer) { fnCairoNewPath?.invoke(cr) }
    fun cairoNewSubPath(cr: COpaquePointer) { fnCairoNewSubPath?.invoke(cr) }
    fun cairoClosePath(cr: COpaquePointer) { fnCairoClosePath?.invoke(cr) }
    fun cairoMoveTo(cr: COpaquePointer, x: Double, y: Double) { fnCairoMoveTo?.invoke(cr, x, y) }
    fun cairoLineTo(cr: COpaquePointer, x: Double, y: Double) { fnCairoLineTo?.invoke(cr, x, y) }
    fun cairoArc(cr: COpaquePointer, xc: Double, yc: Double, radius: Double, angle1: Double, angle2: Double) {
        fnCairoArc?.invoke(cr, xc, yc, radius, angle1, angle2)
    }
    fun cairoFill(cr: COpaquePointer) { fnCairoFill?.invoke(cr) }
    fun cairoFillPreserve(cr: COpaquePointer) { fnCairoFillPreserve?.invoke(cr) }
    fun cairoStroke(cr: COpaquePointer) { fnCairoStroke?.invoke(cr) }
    fun cairoPaint(cr: COpaquePointer) { fnCairoPaint?.invoke(cr) }
    fun cairoPaintWithAlpha(cr: COpaquePointer, alpha: Double) { fnCairoPaintWithAlpha?.invoke(cr, alpha) }
    fun cairoSetSourceRgba(cr: COpaquePointer, r: Double, g: Double, b: Double, a: Double) { fnCairoSetSourceRgba?.invoke(cr, r, g, b, a) }
    fun cairoSetLineWidth(cr: COpaquePointer, width: Double) { fnCairoSetLineWidth?.invoke(cr, width) }
    fun cairoSetLineCap(cr: COpaquePointer, cap: Int) { fnCairoSetLineCap?.invoke(cr, cap) }
    fun cairoPatternCreateLinear(x0: Double, y0: Double, x1: Double, y1: Double): COpaquePointer? =
        fnCairoPatternCreateLinear?.invoke(x0, y0, x1, y1)
    fun cairoPatternAddColorStopRgba(pattern: COpaquePointer, offset: Double, r: Double, g: Double, b: Double, a: Double) {
        fnCairoPatternAddColorStopRgba?.invoke(pattern, offset, r, g, b, a)
    }
    fun cairoSetSource(cr: COpaquePointer, pattern: COpaquePointer) { fnCairoSetSource?.invoke(cr, pattern) }
    fun cairoPatternDestroy(pattern: COpaquePointer?) { if (pattern != null) fnCairoPatternDestroy?.invoke(pattern) }
    fun cairoTranslate(cr: COpaquePointer, tx: Double, ty: Double) { fnCairoTranslate?.invoke(cr, tx, ty) }
    fun cairoScale(cr: COpaquePointer, sx: Double, sy: Double) { fnCairoScale?.invoke(cr, sx, sy) }

    fun cairoPaintPremulArgbSurface(cr: COpaquePointer, premulArgb: IntArray, width: Int, height: Int, dstX: Double, dstY: Double, dstW: Double, dstH: Double) {
        if (width <= 0 || height <= 0 || premulArgb.size < width * height) return
        val fnSurf = fnCairoImageSurfaceCreateForData ?: return
        premulArgb.usePinned { pinned ->
            val surface = fnSurf.invoke(pinned.addressOf(0), CAIRO_FORMAT_ARGB32, width, height, width * 4) ?: return@usePinned
            try {
                fnCairoSurfaceMarkDirty?.invoke(surface)
                cairoSave(cr)
                cairoTranslate(cr, dstX, dstY)
                if (dstW != width.toDouble() || dstH != height.toDouble()) {
                    cairoScale(cr, dstW / width.toDouble(), dstH / height.toDouble())
                }
                fnCairoSetSourceSurface?.invoke(cr, surface, 0.0, 0.0)
                cairoPaint(cr)
                cairoRestore(cr)
            } finally {
                fnCairoSurfaceDestroy?.invoke(surface)
            }
        }
    }

    fun cairoApplyBackdropFilterRegion(
        cr: COpaquePointer,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        radiusPx: Float,
        filter: com.nexustools.genericui.style.css.CssFilter
    ) {
        if (w <= 0 || h <= 0 || !filter.hasActiveEffects) return
        val surface = fnCairoGetTarget?.invoke(cr) ?: return
        fnCairoSurfaceFlush?.invoke(surface)
        val dataPtr = fnCairoImageSurfaceGetData?.invoke(surface)?.reinterpret<ByteVar>() ?: return
        val fmt = fnCairoImageSurfaceGetFormat?.invoke(surface) ?: return
        // CAIRO_FORMAT_ARGB32 (0) or CAIRO_FORMAT_RGB24 (1)
        if (fmt != 0 && fmt != 1) return
        val surfW = fnCairoImageSurfaceGetWidth?.invoke(surface) ?: 0
        val surfH = fnCairoImageSurfaceGetHeight?.invoke(surface) ?: 0
        val strideBytes = fnCairoImageSurfaceGetStride?.invoke(surface) ?: 0
        if (surfW <= 0 || surfH <= 0 || strideBytes < surfW * 4) return

        var devX0 = x.toDouble()
        var devY0 = y.toDouble()
        var devX1 = (x + w).toDouble()
        var devY1 = (y + h).toDouble()
        memScoped {
            val dxVar = alloc<DoubleVar>()
            val dyVar = alloc<DoubleVar>()
            fnCairoUserToDevice?.let { fn ->
                dxVar.value = devX0; dyVar.value = devY0; fn.invoke(cr, dxVar.ptr, dyVar.ptr)
                devX0 = dxVar.value; devY0 = dyVar.value
                dxVar.value = devX1; dyVar.value = devY1; fn.invoke(cr, dxVar.ptr, dyVar.ptr)
                devX1 = dxVar.value; devY1 = dyVar.value
            }
        }
        val scale = ((devX1 - devX0) / w.toDouble()).toFloat().coerceAtLeast(1f)
        val rawBlur = filter.blur?.resolvePx() ?: 0f
        val effFilter = if (rawBlur > 0f && scale != 1f) {
            filter.copy(blur = com.nexustools.genericui.style.css.CssLength.px(rawBlur * scale))
        } else {
            filter
        }

        val x0 = kotlin.math.min(devX0, devX1).toInt().coerceIn(0, surfW)
        val y0 = kotlin.math.min(devY0, devY1).toInt().coerceIn(0, surfH)
        val x1 = kotlin.math.max(devX0, devX1).toInt().coerceIn(0, surfW)
        val y1 = kotlin.math.max(devY0, devY1).toInt().coerceIn(0, surfH)
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw <= 0 || rh <= 0) return

        val blurScaled = (effFilter.blur?.resolvePx() ?: 0f).coerceIn(0f, 48f)
        val pad = if (blurScaled > 0f) kotlin.math.ceil(blurScaled * 1.5f).toInt().coerceIn(1, 32) else 0
        val capX0 = (x0 - pad).coerceAtLeast(0)
        val capY0 = (y0 - pad).coerceAtLeast(0)
        val capX1 = (x1 + pad).coerceAtMost(surfW)
        val capY1 = (y1 + pad).coerceAtMost(surfH)
        val capW = capX1 - capX0
        val capH = capY1 - capY0
        if (capW <= 0 || capH <= 0) return

        val needed = capW * capH
        if (cairoScratchArgb.size < needed) {
            var cap = kotlin.math.max(4096, cairoScratchArgb.size)
            while (cap < needed) cap = cap shl 1
            cairoScratchArgb = IntArray(cap)
        }
        val subArgb = cairoScratchArgb
        var dstIdx = 0
        for (py in capY0 until capY1) {
            val rowWords = (dataPtr + (py * strideBytes))!!.reinterpret<IntVar>()
            for (px in capX0 until capX1) {
                val word = rowWords[px]
                val a = if (fmt == 1) 255 else (word ushr 24) and 0xFF
                var r = (word ushr 16) and 0xFF
                var g = (word ushr 8) and 0xFF
                var b = word and 0xFF
                if (fmt == 0 && a in 1..254) {
                    r = ((r * 255) / a).coerceIn(0, 255)
                    g = ((g * 255) / a).coerceIn(0, 255)
                    b = ((b * 255) / a).coerceIn(0, 255)
                }
                subArgb[dstIdx++] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val applied = com.nexustools.genericui.style.css.FastBackdropBlurKernel.applyBackdropToArgbRegion(
            filter = effFilter,
            argbPixels = subArgb,
            bufferWidth = capW,
            bufferHeight = capH,
            regionX = x0 - capX0,
            regionY = y0 - capY0,
            regionWidth = rw,
            regionHeight = rh,
            cornerRadiusPx = (radiusPx * scale).coerceAtLeast(0f)
        )
        if (!applied) return

        for (py in y0 until y1) {
            val rowWords = (dataPtr + (py * strideBytes))!!.reinterpret<IntVar>()
            val srcRow = (py - capY0) * capW
            for (px in x0 until x1) {
                val argb = subArgb[srcRow + (px - capX0)]
                val a = (argb ushr 24) and 0xFF
                var r = (argb ushr 16) and 0xFF
                var g = (argb ushr 8) and 0xFF
                var b = argb and 0xFF
                if (fmt == 0 && a < 255) {
                    r = (r * a + 127) / 255
                    g = (g * a + 127) / 255
                    b = (b * a + 127) / 255
                }
                val outA = if (fmt == 1) 255 else a
                rowWords[px] = (outA shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        fnCairoSurfaceMarkDirty?.invoke(surface)
    }

    fun hasHardwareGskBlur(): Boolean =
        fnGtkSnapshotPushBlur != null || (fnGskBlurNodeNew != null && fnGskRenderNodeDraw != null)

    fun applyGskBlurNodeToCairoSurface(
        cr: COpaquePointer,
        sourceSurface: COpaquePointer,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        blurRadiusPx: Float
    ): Boolean {
        val gskCairoNew = fnGskCairoNodeNew ?: return false
        val gskCairoCtx = fnGskCairoNodeGetDrawContext ?: return false
        val gskBlurNew = fnGskBlurNodeNew ?: return false
        val gskDraw = fnGskRenderNodeDraw ?: return false
        val gskUnref = fnGskRenderNodeUnref ?: return false
        if (w <= 0.0 || h <= 0.0 || blurRadiusPx <= 0f) return false

        return memScoped {
            val rect = allocArray<FloatVar>(4)
            rect[0] = 0f
            rect[1] = 0f
            rect[2] = w.toFloat()
            rect[3] = h.toFloat()
            val cairoNode = gskCairoNew(rect) ?: return@memScoped false
            val nodeCr = gskCairoCtx(cairoNode)
            if (nodeCr == null) {
                gskUnref(cairoNode)
                return@memScoped false
            }
            try {
                fnCairoSetSourceSurface?.invoke(nodeCr, sourceSurface, -x, -y)
                cairoPaint(nodeCr)
            } finally {
                fnCairoDestroy?.invoke(nodeCr)
            }
            val blurNode = gskBlurNew(cairoNode, blurRadiusPx)
            gskUnref(cairoNode)
            if (blurNode == null) return@memScoped false
            try {
                cairoSave(cr)
                cairoTranslate(cr, x, y)
                gskDraw(blurNode, cr)
                cairoRestore(cr)
                true
            } finally {
                gskUnref(blurNode)
            }
        }
    }

    fun pangoCairoCreateLayout(cr: COpaquePointer): COpaquePointer? = fnPangoCairoCreateLayout?.invoke(cr)
    fun pangoCairoShowLayout(cr: COpaquePointer, layout: COpaquePointer) { fnPangoCairoShowLayout?.invoke(cr, layout) }
    fun pangoLayoutSetText(layout: COpaquePointer, text: String) {
        val utf8 = text.encodeToByteArray()
        if (utf8.isEmpty()) {
            memScoped { fnPangoLayoutSetText?.invoke(layout, "".cstr.ptr, 0) }
        } else {
            utf8.usePinned { pinned -> fnPangoLayoutSetText?.invoke(layout, pinned.addressOf(0), utf8.size) }
        }
    }
    fun pangoLayoutSetWidth(layout: COpaquePointer, widthScaled: Int) { fnPangoLayoutSetWidth?.invoke(layout, widthScaled) }
    fun pangoLayoutSetAlignment(layout: COpaquePointer, align: Int) { fnPangoLayoutSetAlignment?.invoke(layout, align) }
    fun pangoLayoutSetEllipsize(layout: COpaquePointer, mode: Int) { fnPangoLayoutSetEllipsize?.invoke(layout, mode) }
    fun pangoLayoutSetWrap(layout: COpaquePointer, wrap: Int) { fnPangoLayoutSetWrap?.invoke(layout, wrap) }
    fun pangoLayoutGetPixelSize(layout: COpaquePointer): Pair<Int, Int> = memScoped {
        val w = alloc<IntVar>()
        val h = alloc<IntVar>()
        w.value = 0; h.value = 0
        fnPangoLayoutGetPixelSize?.invoke(layout, w.ptr, h.ptr)
        w.value to h.value
    }
    fun pangoLayoutSetFontDescription(layout: COpaquePointer, desc: COpaquePointer?) { fnPangoLayoutSetFontDescription?.invoke(layout, desc) }
    fun pangoFontDescriptionNew(): COpaquePointer? = fnPangoFontDescriptionNew?.invoke()
    fun pangoFontDescriptionSetFamily(desc: COpaquePointer, family: String) = memScoped { fnPangoFontDescriptionSetFamily?.invoke(desc, family.cstr.ptr) }
    fun pangoFontDescriptionSetWeight(desc: COpaquePointer, weight: Int) { fnPangoFontDescriptionSetWeight?.invoke(desc, weight) }
    fun pangoFontDescriptionSetStyle(desc: COpaquePointer, style: Int) { fnPangoFontDescriptionSetStyle?.invoke(desc, style) }
    fun pangoFontDescriptionSetAbsoluteSize(desc: COpaquePointer, scaledSize: Double) { fnPangoFontDescriptionSetAbsoluteSize?.invoke(desc, scaledSize) }
    fun pangoFontDescriptionFree(desc: COpaquePointer?) { if (desc != null) fnPangoFontDescriptionFree?.invoke(desc) }
    fun pangoLayoutSetDecoration(layout: COpaquePointer, underline: Boolean, strikethrough: Boolean) {
        if (!underline && !strikethrough) return
        val attrList = fnPangoAttrListNew?.invoke() ?: return
        try {
            if (underline) {
                fnPangoAttrUnderlineNew?.invoke(1)?.let { fnPangoAttrListInsert?.invoke(attrList, it) }
            }
            if (strikethrough) {
                fnPangoAttrStrikethroughNew?.invoke(1)?.let { fnPangoAttrListInsert?.invoke(attrList, it) }
            }
            fnPangoLayoutSetAttributes?.invoke(layout, attrList)
        } finally {
            fnPangoAttrListUnref?.invoke(attrList)
        }
    }
}
