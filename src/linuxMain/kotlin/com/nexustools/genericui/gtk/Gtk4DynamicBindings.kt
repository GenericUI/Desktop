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
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

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

    const val GDK_SURFACE_EDGE_NORTH_WEST = 0
    const val GDK_SURFACE_EDGE_NORTH = 1
    const val GDK_SURFACE_EDGE_NORTH_EAST = 2
    const val GDK_SURFACE_EDGE_WEST = 3
    const val GDK_SURFACE_EDGE_EAST = 4
    const val GDK_SURFACE_EDGE_SOUTH_WEST = 5
    const val GDK_SURFACE_EDGE_SOUTH = 6
    const val GDK_SURFACE_EDGE_SOUTH_EAST = 7
    const val GTK_STYLE_PROVIDER_PRIORITY_USER = 800u

    const val GTK_STATE_FLAG_NORMAL = 0u
    const val GTK_STATE_FLAG_ACTIVE = 1u
    const val GTK_STATE_FLAG_PRELIGHT = 2u
    const val GTK_STATE_FLAG_SELECTED = 4u
    const val GTK_STATE_FLAG_INSENSITIVE = 8u
    const val GTK_STATE_FLAG_INCONSISTENT = 16u
    const val GTK_STATE_FLAG_FOCUSED = 32u
    const val GTK_STATE_FLAG_CHECKED = 2048u
    const val GTK_STATE_FLAG_FOCUS_VISIBLE = 8192u

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

    const val GTK_SIZE_REQUEST_HEIGHT_FOR_WIDTH = 0
    const val GTK_SIZE_REQUEST_WIDTH_FOR_HEIGHT = 1
    const val GTK_SIZE_REQUEST_CONSTANT_SIZE = 2
    const val GTK_OVERFLOW_VISIBLE = 0
    const val GTK_OVERFLOW_HIDDEN = 1
    const val GTK_POLICY_ALWAYS = 0
    const val GTK_POLICY_AUTOMATIC = 1
    const val GTK_POLICY_NEVER = 2
    const val GTK_POLICY_EXTERNAL = 3
    const val GTK_SELECTION_NONE = 0
    const val GTK_SELECTION_SINGLE = 1
    const val GTK_SELECTION_BROWSE = 2
    const val GTK_SELECTION_MULTIPLE = 3
    const val GTK_POS_LEFT = 0
    const val GTK_POS_RIGHT = 1
    const val GTK_POS_TOP = 2
    const val GTK_POS_BOTTOM = 3
    const val GTK_CONTENT_FIT_FILL = 0
    const val GTK_CONTENT_FIT_CONTAIN = 1
    const val GTK_CONTENT_FIT_COVER = 2
    const val GTK_CONTENT_FIT_SCALE_DOWN = 3
    const val GTK_WRAP_NONE = 0
    const val GTK_WRAP_CHAR = 1
    const val GTK_WRAP_WORD = 2
    const val GTK_WRAP_WORD_CHAR = 3
    const val GTK_STACK_TRANSITION_TYPE_NONE = 0
    const val GTK_STACK_TRANSITION_TYPE_CROSSFADE = 1
    const val GTK_ALIGN_FILL = 0
    const val GTK_ALIGN_START = 1
    const val GTK_ALIGN_END = 2
    const val GTK_ALIGN_CENTER = 3
    const val GDK_BUTTON_PRIMARY = 1u
    const val GDK_BUTTON_MIDDLE = 2u
    const val GDK_BUTTON_SECONDARY = 3u
    const val CAIRO_OPERATOR_CLEAR = 0
    const val CAIRO_OPERATOR_SOURCE = 1
    const val CAIRO_OPERATOR_OVER = 2

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
    private var fnAdwCallbackAnimationTargetNew: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnAdwTimedAnimationNew: CPointer<CFunction<(COpaquePointer?, Double, Double, UInt, COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnAdwTimedAnimationSetEasing: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnAdwAnimationPlay: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnAdwAnimationPause: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnAdwAnimationSkip: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnAdwAnimationGetState: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null

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
    private var fnGtkWindowIsActive: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGtkWindowSetResizable: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkWindowSetDecorated: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkWindowSetHideOnClose: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkWidgetGetRealized: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGtkWidgetGetNative: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkNativeGetSurface: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGdkToplevelGetType: CPointer<CFunction<() -> ULong>>? = null
    private var fnGdkToplevelGetState: CPointer<CFunction<(COpaquePointer?) -> UInt>>? = null
    private var fnGdkToplevelBeginMove: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, Int, Double, Double, UInt) -> Unit>>? = null
    private var fnGdkToplevelBeginResize: CPointer<CFunction<(COpaquePointer?, Int, COpaquePointer?, Int, Double, Double, UInt) -> Unit>>? = null
    private var fnGdkToplevelShowWindowMenu: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Int>>? = null
    private var fnGtkEventControllerGetCurrentEvent: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkCssProviderNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkCssProviderLoadFromData: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?, Long) -> Unit>>? = null
    private var fnGtkStyleContextAddProviderForDisplay: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, UInt) -> Unit>>? = null
    private var fnGtkWidgetAddCssClass: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnGtkWidgetRemoveCssClass: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var undecoratedCssInstalled = false
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
    private var fnGtkWidgetGetVisible: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGtkWidgetQueueDraw: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGtkWidgetAddController: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkWidgetSetFocusable: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkWidgetGetFocusable: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGtkWidgetGrabFocus: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGtkWidgetSetCursor: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkWidgetSetCursorFromName: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnGtkWidgetSetOpacity: CPointer<CFunction<(COpaquePointer?, Double) -> Unit>>? = null
    private var fnGtkWidgetGetDisplay: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkWidgetSetHexpand: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkWidgetSetVexpand: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkWidgetSetMargins: Array<CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>?> = arrayOfNulls(4)
    private var fnGtkWidgetAddTickCallback: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?) -> UInt>>? = null
    private var fnGtkWidgetRemoveTickCallback: CPointer<CFunction<(COpaquePointer?, UInt) -> Unit>>? = null
    private var fnGtkWidgetGetFrameClock: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGdkFrameClockGetFrameTime: CPointer<CFunction<(COpaquePointer?) -> Long>>? = null

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
    private var fnGtkButtonNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkProgressBarNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkProgressBarSetFraction: CPointer<CFunction<(COpaquePointer?, Double) -> Unit>>? = null
    private var fnGtkProgressBarPulse: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGtkAdjustmentNew: CPointer<CFunction<(Double, Double, Double, Double, Double, Double) -> COpaquePointer?>>? = null
    private var fnGtkAdjustmentConfigure: CPointer<CFunction<(COpaquePointer?, Double, Double, Double, Double, Double, Double) -> Unit>>? = null
    private var fnGtkScrollbarNew: CPointer<CFunction<(Int, COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkCheckButtonNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkCheckButtonSetActive: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkCheckButtonSetInconsistent: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkCheckButtonSetGroup: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkSwitchNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkSwitchSetActive: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkScaleNewWithRange: CPointer<CFunction<(Int, Double, Double, Double) -> COpaquePointer?>>? = null
    private var fnGtkRangeSetValue: CPointer<CFunction<(COpaquePointer?, Double) -> Unit>>? = null
    private var fnGtkDropDownNew: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkSpinnerNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkSpinnerSetSpinning: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkEntrySetPlaceholderText: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnGtkEntrySetVisibility: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkTextViewNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkNotebookNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkNotebookAppendPage: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?) -> Int>>? = null
    private var fnGtkNotebookRemovePage: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkNotebookSetCurrentPage: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkNotebookGetNPages: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGtkFrameNew: CPointer<CFunction<(CPointer<ByteVar>?) -> COpaquePointer?>>? = null
    private var fnGtkWidgetSetSensitive: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkWidgetSetStateFlags: CPointer<CFunction<(COpaquePointer?, UInt, Int) -> Unit>>? = null
    private var fnGtkWidgetUnsetStateFlags: CPointer<CFunction<(COpaquePointer?, UInt) -> Unit>>? = null
    private var fnGtkWidgetMeasure: CPointer<CFunction<(COpaquePointer?, Int, Int, CPointer<IntVar>?, CPointer<IntVar>?, CPointer<IntVar>?, CPointer<IntVar>?) -> Unit>>? = null
    private var fnGtkWidgetAllocate: CPointer<CFunction<(COpaquePointer?, Int, Int, Int, COpaquePointer?) -> Unit>>? = null
    private var fnGtkWidgetSnapshotChild: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkWidgetGetFirstChild: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkWidgetGetNextSibling: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkWidgetGetCssName: CPointer<CFunction<(COpaquePointer?) -> CPointer<ByteVar>?>>? = null
    private var fnGtkWidgetPaintableNew: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGdkPaintableSnapshot: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, Double, Double) -> Unit>>? = null
    private var fnGtkWidgetGetStyleContext: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkStyleContextSave: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGtkStyleContextRestore: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGtkStyleContextSetState: CPointer<CFunction<(COpaquePointer?, UInt) -> Unit>>? = null
    private var fnGtkStyleContextAddClass: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnGtkStyleContextRemoveClass: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnGtkStyleContextGetColor: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkRenderBackground: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, Double, Double, Double, Double) -> Unit>>? = null
    private var fnGtkRenderFrame: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, Double, Double, Double, Double) -> Unit>>? = null
    private var fnGtkRenderCheck: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, Double, Double, Double, Double) -> Unit>>? = null
    private var fnGtkRenderOption: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, Double, Double, Double, Double) -> Unit>>? = null
    private var fnGtkRenderActivity: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, Double, Double, Double, Double) -> Unit>>? = null
    private var fnGtkRenderFocus: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, Double, Double, Double, Double) -> Unit>>? = null
    private var fnGtkRenderArrow: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, Double, Double, Double, Double) -> Unit>>? = null
    private var fnGtkRenderSlider: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, Double, Double, Double, Double, Int) -> Unit>>? = null

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
    private var fnGdkDisplayGetMonitorAtSurface: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGdkMonitorGetGeometry: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGdkMonitorGetRefreshRate: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
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
    private var fnGskCairoRendererNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGskRendererNewForSurface: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGskRendererRealize: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, CPointer<COpaquePointerVar>?) -> Int>>? = null
    private var fnGskRendererRealizeForDisplay: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, CPointer<COpaquePointerVar>?) -> Int>>? = null
    private var fnGskRendererUnrealize: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGskRendererRenderTexture: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGdkTextureDownload: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, ULong) -> Unit>>? = null
    private var fnGdkTextureGetWidth: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGdkTextureGetHeight: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnCairoImageSurfaceCreate: CPointer<CFunction<(Int, Int, Int) -> COpaquePointer?>>? = null

    private var fnGtkCustomLayoutNew: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkWidgetSetLayoutManager: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkWidgetGetLayoutManager: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkLayoutManagerLayoutChanged: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGtkWidgetQueueAllocate: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGtkWidgetQueueResize: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGtkWidgetSizeAllocate: CPointer<CFunction<(COpaquePointer?, CPointer<IntVar>?, Int) -> Unit>>? = null
    private var fnGtkWidgetInsertAfter: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkWidgetInsertBefore: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkWidgetUnparent: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGtkWidgetSetParent: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkWidgetGetParent: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkWidgetSetCanTarget: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkWidgetGetCanTarget: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGtkWidgetSetCanFocus: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkWidgetGetCanFocus: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGtkWidgetHasFocus: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGtkWidgetSetFocusOnClick: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkWidgetGetFocusOnClick: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGtkWidgetGetRoot: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkWindowSetFocus: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkWidgetComputeBounds: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, CPointer<FloatVar>?) -> Int>>? = null
    private var fnGtkWidgetSetOverflow: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkWidgetSetTooltipText: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnGtkWidgetHasCssClass: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Int>>? = null
    private var fnGtkWidgetSetName: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnGtkWidgetGetName: CPointer<CFunction<(COpaquePointer?) -> CPointer<ByteVar>?>>? = null
    private var fnGtkWidgetSetHalign: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkWidgetSetValign: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkWidgetGetColor: CPointer<CFunction<(COpaquePointer?, CPointer<FloatVar>?) -> Unit>>? = null
    private var fnGtkCssProviderLoadFromString: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnGtkBoxRemove: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkWindowSetDefaultWidget: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null

    private var fnGtkOverlayNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkOverlaySetChild: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkOverlayAddOverlay: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkOverlayRemoveOverlay: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkScrolledWindowNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkScrolledWindowSetChild: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkScrolledWindowSetPolicy: CPointer<CFunction<(COpaquePointer?, Int, Int) -> Unit>>? = null
    private var fnGtkScrolledWindowGetVadjustment: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkScrolledWindowGetHadjustment: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkAdjustmentGetValue: CPointer<CFunction<(COpaquePointer?) -> Double>>? = null
    private var fnGtkAdjustmentSetValue: CPointer<CFunction<(COpaquePointer?, Double) -> Unit>>? = null
    private var fnGtkAdjustmentGetUpper: CPointer<CFunction<(COpaquePointer?) -> Double>>? = null
    private var fnGtkAdjustmentGetLower: CPointer<CFunction<(COpaquePointer?) -> Double>>? = null
    private var fnGtkAdjustmentGetPageSize: CPointer<CFunction<(COpaquePointer?) -> Double>>? = null
    private var fnGtkPanedNew: CPointer<CFunction<(Int) -> COpaquePointer?>>? = null
    private var fnGtkPanedSetStartChild: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkPanedSetEndChild: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkPanedSetPosition: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkPanedGetPosition: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGtkPanedSetResizeStartChild: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkPanedSetResizeEndChild: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkPanedSetShrinkStartChild: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkPanedSetShrinkEndChild: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkStackNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkStackAddNamed: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, CPointer<ByteVar>?) -> COpaquePointer?>>? = null
    private var fnGtkStackRemove: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkStackSetVisibleChildName: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnGtkStackSetTransitionType: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkNotebookInsertPage: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?, Int) -> Int>>? = null
    private var fnGtkNotebookSetTabPos: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkNotebookSetScrollable: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkNotebookGetCurrentPage: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGtkNotebookSetShowTabs: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkFlowBoxNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkFlowBoxInsert: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkFlowBoxRemove: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkFlowBoxSetSelectionMode: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkFlowBoxSetMinChildrenPerLine: CPointer<CFunction<(COpaquePointer?, UInt) -> Unit>>? = null
    private var fnGtkFlowBoxSetMaxChildrenPerLine: CPointer<CFunction<(COpaquePointer?, UInt) -> Unit>>? = null

    private var fnGtkMenuButtonNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkMenuButtonSetPopover: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkMenuButtonSetMenuModel: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkMenuButtonSetLabel: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnGtkMenuButtonSetChild: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkMenuButtonPopup: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGtkMenuButtonPopdown: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGtkPopoverNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkPopoverSetChild: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkPopoverSetPointingTo: CPointer<CFunction<(COpaquePointer?, CPointer<IntVar>?) -> Unit>>? = null
    private var fnGtkPopoverPopup: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGtkPopoverPopdown: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGtkPopoverSetPosition: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkPopoverSetAutohide: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkPopoverSetHasArrow: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkPopoverMenuNewFromModel: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkPopoverMenuSetMenuModel: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkPopoverMenuBarNewFromModel: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkPopoverMenuBarSetMenuModel: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null

    private var fnGtkButtonSetLabel: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnGtkButtonSetChild: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkButtonGetChild: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkPasswordEntryNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkPasswordEntrySetShowPeekIcon: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkSearchEntryNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkEditableSetEditable: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkSpinButtonNewWithRange: CPointer<CFunction<(Double, Double, Double) -> COpaquePointer?>>? = null
    private var fnGtkSpinButtonSetValue: CPointer<CFunction<(COpaquePointer?, Double) -> Unit>>? = null
    private var fnGtkSpinButtonGetValue: CPointer<CFunction<(COpaquePointer?) -> Double>>? = null
    private var fnGtkSpinButtonSetRange: CPointer<CFunction<(COpaquePointer?, Double, Double) -> Unit>>? = null
    private var fnGtkSpinButtonSetIncrements: CPointer<CFunction<(COpaquePointer?, Double, Double) -> Unit>>? = null
    private var fnGtkSpinButtonSetDigits: CPointer<CFunction<(COpaquePointer?, UInt) -> Unit>>? = null
    private var fnGtkTextViewGetBuffer: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkTextViewSetEditable: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkTextViewSetWrapMode: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkTextViewSetMonospace: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkTextBufferSetText: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?, Int) -> Unit>>? = null
    private var fnGtkTextBufferGetStartIter: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkTextBufferGetEndIter: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkTextBufferGetText: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?, Int) -> CPointer<ByteVar>?>>? = null
    private var fnGtkPictureNewForPaintable: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkPictureSetPaintable: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkPictureSetContentFit: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkPictureSetCanShrink: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkSeparatorNew: CPointer<CFunction<(Int) -> COpaquePointer?>>? = null
    private var fnGtkColumnViewNew: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkColumnViewSetModel: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkColumnViewAppendColumn: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkColumnViewRemoveColumn: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkColumnViewColumnNew: CPointer<CFunction<(CPointer<ByteVar>?, COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkColumnViewColumnSetTitle: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnGtkColumnViewColumnSetExpand: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkColumnViewColumnSetFixedWidth: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkColumnViewColumnSetResizable: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkSingleSelectionNew: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkNoSelectionNew: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkStringListNew: CPointer<CFunction<(CPointer<CPointerVar<ByteVar>>?) -> COpaquePointer?>>? = null
    private var fnGtkSignalListItemFactoryNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkListItemSetChild: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkListItemGetChild: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkListItemGetItem: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkStringObjectGetString: CPointer<CFunction<(COpaquePointer?) -> CPointer<ByteVar>?>>? = null
    private var fnGtkDropDownSetModel: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkDropDownSetEnableSearch: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkListBoxNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkListBoxAppend: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkListBoxInsert: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkListBoxRemove: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkListBoxSelectRow: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkListBoxGetRowAtIndex: CPointer<CFunction<(COpaquePointer?, Int) -> COpaquePointer?>>? = null
    private var fnGtkListBoxSetSelectionMode: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkListBoxRowNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkListBoxRowSetChild: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkExpanderNew: CPointer<CFunction<(CPointer<ByteVar>?) -> COpaquePointer?>>? = null
    private var fnGtkExpanderSetChild: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkExpanderSetExpanded: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkExpanderGetExpanded: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGtkExpanderSetLabelWidget: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkCalendarNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkCalendarGetDate: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkCalendarSelectDay: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkColorDialogButtonNew: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGtkColorDialogButtonSetRgba: CPointer<CFunction<(COpaquePointer?, CPointer<FloatVar>?) -> Unit>>? = null
    private var fnGtkColorDialogButtonGetRgba: CPointer<CFunction<(COpaquePointer?) -> CPointer<FloatVar>?>>? = null
    private var fnGtkCheckButtonGetActive: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGtkCheckButtonSetLabel: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnGtkSwitchGetActive: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGtkRangeGetValue: CPointer<CFunction<(COpaquePointer?) -> Double>>? = null
    private var fnGtkRangeSetRange: CPointer<CFunction<(COpaquePointer?, Double, Double) -> Unit>>? = null
    private var fnGtkProgressBarSetText: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnGtkProgressBarSetShowText: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkSpinnerStart: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGtkSpinnerStop: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnAdwSpinnerNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkLabelSetText: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnGtkLabelSetMarkup: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnGtkLabelSetXalign: CPointer<CFunction<(COpaquePointer?, Float) -> Unit>>? = null
    private var fnGtkLabelSetYalign: CPointer<CFunction<(COpaquePointer?, Float) -> Unit>>? = null
    private var fnGtkLabelSetWrapMode: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkLabelSetEllipsize: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkLabelSetSelectable: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnGtkGestureSingleGetCurrentButton: CPointer<CFunction<(COpaquePointer?) -> UInt>>? = null
    private var fnGtkEventControllerFocusNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGtkEventControllerFocusContainsFocus: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGtkEventControllerFocusIsFocus: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGtkWidgetInsertActionGroup: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?, COpaquePointer?) -> Unit>>? = null

    private var fnGskTransformTranslate: CPointer<CFunction<(COpaquePointer?, CPointer<FloatVar>?) -> COpaquePointer?>>? = null
    private var fnGskTransformRotate: CPointer<CFunction<(COpaquePointer?, Float) -> COpaquePointer?>>? = null
    private var fnGskTransformScale: CPointer<CFunction<(COpaquePointer?, Float, Float) -> COpaquePointer?>>? = null
    private var fnGskTransformSkew: CPointer<CFunction<(COpaquePointer?, Float, Float) -> COpaquePointer?>>? = null
    private var fnGskTransformUnref: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGtkSnapshotPushRoundedClip: CPointer<CFunction<(COpaquePointer?, CPointer<FloatVar>?) -> Unit>>? = null
    private var fnGtkSnapshotPushColorMatrix: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGtkSnapshotPushOpacity: CPointer<CFunction<(COpaquePointer?, Double) -> Unit>>? = null
    private var fnGdkPaintableGetIntrinsicWidth: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGdkPaintableGetIntrinsicHeight: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null

    private var fnGQuarkFromString: CPointer<CFunction<(CPointer<ByteVar>?) -> UInt>>? = null
    private var fnGObjectSetQdataFull: CPointer<CFunction<(COpaquePointer?, UInt, COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGObjectGetQdata: CPointer<CFunction<(COpaquePointer?, UInt) -> COpaquePointer?>>? = null
    private var fnGFree: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGDateTimeNewLocal: CPointer<CFunction<(Int, Int, Int, Int, Int, Double) -> COpaquePointer?>>? = null
    private var fnGDateTimeGetYear: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGDateTimeGetMonth: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGDateTimeGetDayOfMonth: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGDateTimeUnref: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGMenuNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGMenuAppend: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnGMenuAppendSubmenu: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?, COpaquePointer?) -> Unit>>? = null
    private var fnGMenuRemoveAll: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnGSimpleActionGroupNew: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGSimpleActionNew: CPointer<CFunction<(CPointer<ByteVar>?, COpaquePointer?) -> COpaquePointer?>>? = null
    private var fnGActionMapAddAction: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnGrapheneMatrixInitFromFloat: CPointer<CFunction<(COpaquePointer?, CPointer<FloatVar>?) -> COpaquePointer?>>? = null
    private var fnGrapheneVec4Init: CPointer<CFunction<(COpaquePointer?, Float, Float, Float, Float) -> COpaquePointer?>>? = null
    private var fnCairoClipPreserve: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnCairoSetDash: CPointer<CFunction<(COpaquePointer?, CPointer<DoubleVar>?, Int, Double) -> Unit>>? = null
    private var fnCairoPatternCreateRadial: CPointer<CFunction<(Double, Double, Double, Double, Double, Double) -> COpaquePointer?>>? = null
    private var fnCairoSetOperator: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null

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
        val gobject = LinuxDynamicLibraryLoader.openFirst("libgobject-2.0.so.0", "libgobject-2.0.so", global = true)
        val gio = LinuxDynamicLibraryLoader.openFirst("libgio-2.0.so.0", "libgio-2.0.so", global = true)
        val glib = LinuxDynamicLibraryLoader.openFirst("libglib-2.0.so.0", "libglib-2.0.so", global = true)
        val graphene = LinuxDynamicLibraryLoader.openFirst("libgraphene-1.0.so.0", "libgraphene-1.0.so", global = true) ?: gtk

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
            fnGtkWindowIsActive = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_window_is_active")?.reinterpret()
            fnGtkWindowSetResizable = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_window_set_resizable")?.reinterpret()
            fnGtkWindowSetDecorated = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_window_set_decorated")?.reinterpret()
            fnGtkWindowSetHideOnClose = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_window_set_hide_on_close")?.reinterpret()
            fnGtkWidgetGetRealized = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_get_realized")?.reinterpret()
            fnGtkWidgetGetNative = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_get_native")?.reinterpret()
            fnGtkNativeGetSurface = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_native_get_surface")?.reinterpret()
            fnGdkToplevelGetType = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_toplevel_get_type")?.reinterpret()
            fnGdkToplevelGetState = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_toplevel_get_state")?.reinterpret()
            fnGdkToplevelBeginMove = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_toplevel_begin_move")?.reinterpret()
            fnGdkToplevelBeginResize = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_toplevel_begin_resize")?.reinterpret()
            fnGdkToplevelShowWindowMenu = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_toplevel_show_window_menu")?.reinterpret()
            fnGtkEventControllerGetCurrentEvent = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_event_controller_get_current_event")?.reinterpret()
            fnGtkCssProviderNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_css_provider_new")?.reinterpret()
            fnGtkCssProviderLoadFromData = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_css_provider_load_from_data")?.reinterpret()
            fnGtkStyleContextAddProviderForDisplay = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_style_context_add_provider_for_display")?.reinterpret()
            fnGtkWidgetAddCssClass = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_add_css_class")?.reinterpret()
            fnGtkWidgetRemoveCssClass = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_remove_css_class")?.reinterpret()
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
            fnGtkWidgetGetVisible = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_get_visible")?.reinterpret()
            fnGtkWidgetQueueDraw = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_queue_draw")?.reinterpret()
            fnGtkWidgetAddController = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_add_controller")?.reinterpret()
            fnGtkWidgetSetFocusable = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_set_focusable")?.reinterpret()
            fnGtkWidgetGetFocusable = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_get_focusable")?.reinterpret()
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
            fnGtkWidgetAddTickCallback = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_add_tick_callback")?.reinterpret()
            fnGtkWidgetRemoveTickCallback = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_remove_tick_callback")?.reinterpret()
            fnGtkWidgetGetFrameClock = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_get_frame_clock")?.reinterpret()
            fnGdkFrameClockGetFrameTime = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_frame_clock_get_frame_time")?.reinterpret()
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
            fnGtkButtonNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_button_new")?.reinterpret()
            fnGtkProgressBarNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_progress_bar_new")?.reinterpret()
            fnGtkProgressBarSetFraction = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_progress_bar_set_fraction")?.reinterpret()
            fnGtkProgressBarPulse = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_progress_bar_pulse")?.reinterpret()
            fnGtkAdjustmentNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_adjustment_new")?.reinterpret()
            fnGtkAdjustmentConfigure = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_adjustment_configure")?.reinterpret()
            fnGtkScrollbarNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_scrollbar_new")?.reinterpret()
            fnGtkCheckButtonNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_check_button_new")?.reinterpret()
            fnGtkCheckButtonSetActive = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_check_button_set_active")?.reinterpret()
            fnGtkCheckButtonSetInconsistent = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_check_button_set_inconsistent")?.reinterpret()
            fnGtkCheckButtonSetGroup = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_check_button_set_group")?.reinterpret()
            fnGtkSwitchNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_switch_new")?.reinterpret()
            fnGtkSwitchSetActive = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_switch_set_active")?.reinterpret()
            fnGtkScaleNewWithRange = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_scale_new_with_range")?.reinterpret()
            fnGtkRangeSetValue = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_range_set_value")?.reinterpret()
            fnGtkDropDownNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_drop_down_new")?.reinterpret()
            fnGtkSpinnerNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_spinner_new")?.reinterpret()
            fnGtkSpinnerSetSpinning = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_spinner_set_spinning")?.reinterpret()
            fnGtkEntrySetPlaceholderText = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_entry_set_placeholder_text")?.reinterpret()
            fnGtkEntrySetVisibility = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_entry_set_visibility")?.reinterpret()
            fnGtkTextViewNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_text_view_new")?.reinterpret()
            fnGtkNotebookNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_notebook_new")?.reinterpret()
            fnGtkNotebookAppendPage = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_notebook_append_page")?.reinterpret()
            fnGtkNotebookRemovePage = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_notebook_remove_page")?.reinterpret()
            fnGtkNotebookSetCurrentPage = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_notebook_set_current_page")?.reinterpret()
            fnGtkNotebookGetNPages = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_notebook_get_n_pages")?.reinterpret()
            fnGtkFrameNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_frame_new")?.reinterpret()
            fnGtkWidgetSetSensitive = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_set_sensitive")?.reinterpret()
            fnGtkWidgetSetStateFlags = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_set_state_flags")?.reinterpret()
            fnGtkWidgetUnsetStateFlags = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_unset_state_flags")?.reinterpret()
            fnGtkWidgetMeasure = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_measure")?.reinterpret()
            fnGtkWidgetAllocate = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_allocate")?.reinterpret()
            fnGtkWidgetSnapshotChild = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_snapshot_child")?.reinterpret()
            fnGtkWidgetGetFirstChild = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_get_first_child")?.reinterpret()
            fnGtkWidgetGetNextSibling = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_get_next_sibling")?.reinterpret()
            fnGtkWidgetGetCssName = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_get_css_name")?.reinterpret()
            fnGtkWidgetPaintableNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_paintable_new")?.reinterpret()
            fnGdkPaintableSnapshot = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_paintable_snapshot")?.reinterpret()
            fnGtkWidgetGetStyleContext = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_get_style_context")?.reinterpret()
            fnGtkStyleContextSave = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_style_context_save")?.reinterpret()
            fnGtkStyleContextRestore = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_style_context_restore")?.reinterpret()
            fnGtkStyleContextSetState = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_style_context_set_state")?.reinterpret()
            fnGtkStyleContextAddClass = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_style_context_add_class")?.reinterpret()
            fnGtkStyleContextRemoveClass = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_style_context_remove_class")?.reinterpret()
            fnGtkStyleContextGetColor = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_style_context_get_color")?.reinterpret()
            fnGtkRenderBackground = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_render_background")?.reinterpret()
            fnGtkRenderFrame = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_render_frame")?.reinterpret()
            fnGtkRenderCheck = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_render_check")?.reinterpret()
            fnGtkRenderOption = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_render_option")?.reinterpret()
            fnGtkRenderActivity = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_render_activity")?.reinterpret()
            fnGtkRenderFocus = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_render_focus")?.reinterpret()
            fnGtkRenderArrow = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_render_arrow")?.reinterpret()
            fnGtkRenderSlider = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_render_slider")?.reinterpret()
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
            fnGdkDisplayGetMonitorAtSurface = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_display_get_monitor_at_surface")?.reinterpret()
            fnGdkMonitorGetGeometry = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_monitor_get_geometry")?.reinterpret()
            fnGdkMonitorGetRefreshRate = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_monitor_get_refresh_rate")?.reinterpret()
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
            fnGskCairoRendererNew = LinuxDynamicLibraryLoader.symbol(gtk, "gsk_cairo_renderer_new")?.reinterpret()
            fnGskRendererRealize = LinuxDynamicLibraryLoader.symbol(gtk, "gsk_renderer_realize")?.reinterpret()
            fnGskRendererRealizeForDisplay = LinuxDynamicLibraryLoader.symbol(gtk, "gsk_renderer_realize_for_display")?.reinterpret()
            fnGskRendererUnrealize = LinuxDynamicLibraryLoader.symbol(gtk, "gsk_renderer_unrealize")?.reinterpret()
            fnGskRendererRenderTexture = LinuxDynamicLibraryLoader.symbol(gtk, "gsk_renderer_render_texture")?.reinterpret()
            fnGskRendererNewForSurface = LinuxDynamicLibraryLoader.symbol(gtk, "gsk_renderer_new_for_surface")?.reinterpret()
            fnGdkTextureDownload = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_texture_download")?.reinterpret()
            fnGdkTextureGetWidth = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_texture_get_width")?.reinterpret()
            fnGdkTextureGetHeight = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_texture_get_height")?.reinterpret()
            fnGtkCustomLayoutNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_custom_layout_new")?.reinterpret()
            fnGtkWidgetSetLayoutManager = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_set_layout_manager")?.reinterpret()
            fnGtkWidgetGetLayoutManager = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_get_layout_manager")?.reinterpret()
            fnGtkLayoutManagerLayoutChanged = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_layout_manager_layout_changed")?.reinterpret()
            fnGtkWidgetQueueAllocate = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_queue_allocate")?.reinterpret()
            fnGtkWidgetQueueResize = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_queue_resize")?.reinterpret()
            fnGtkWidgetSizeAllocate = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_size_allocate")?.reinterpret()
            fnGtkWidgetInsertAfter = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_insert_after")?.reinterpret()
            fnGtkWidgetInsertBefore = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_insert_before")?.reinterpret()
            fnGtkWidgetUnparent = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_unparent")?.reinterpret()
            fnGtkWidgetSetParent = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_set_parent")?.reinterpret()
            fnGtkWidgetGetParent = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_get_parent")?.reinterpret()
            fnGtkWidgetSetCanTarget = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_set_can_target")?.reinterpret()
            fnGtkWidgetGetCanTarget = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_get_can_target")?.reinterpret()
            fnGtkWidgetSetCanFocus = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_set_can_focus")?.reinterpret()
            fnGtkWidgetGetCanFocus = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_get_can_focus")?.reinterpret()
            fnGtkWidgetHasFocus = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_has_focus")?.reinterpret()
            fnGtkWidgetSetFocusOnClick = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_set_focus_on_click")?.reinterpret()
            fnGtkWidgetGetFocusOnClick = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_get_focus_on_click")?.reinterpret()
            fnGtkWidgetGetRoot = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_get_root")?.reinterpret()
            fnGtkWindowSetFocus = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_window_set_focus")?.reinterpret()
            fnGtkWidgetComputeBounds = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_compute_bounds")?.reinterpret()
            fnGtkWidgetSetOverflow = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_set_overflow")?.reinterpret()
            fnGtkWidgetSetTooltipText = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_set_tooltip_text")?.reinterpret()
            fnGtkWidgetHasCssClass = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_has_css_class")?.reinterpret()
            fnGtkWidgetSetName = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_set_name")?.reinterpret()
            fnGtkWidgetGetName = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_get_name")?.reinterpret()
            fnGtkWidgetSetHalign = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_set_halign")?.reinterpret()
            fnGtkWidgetSetValign = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_set_valign")?.reinterpret()
            fnGtkWidgetGetColor = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_get_color")?.reinterpret()
            fnGtkCssProviderLoadFromString = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_css_provider_load_from_string")?.reinterpret()
            fnGtkBoxRemove = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_box_remove")?.reinterpret()
            fnGtkWindowSetDefaultWidget = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_window_set_default_widget")?.reinterpret()
            fnGtkOverlayNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_overlay_new")?.reinterpret()
            fnGtkOverlaySetChild = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_overlay_set_child")?.reinterpret()
            fnGtkOverlayAddOverlay = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_overlay_add_overlay")?.reinterpret()
            fnGtkOverlayRemoveOverlay = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_overlay_remove_overlay")?.reinterpret()
            fnGtkScrolledWindowNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_scrolled_window_new")?.reinterpret()
            fnGtkScrolledWindowSetChild = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_scrolled_window_set_child")?.reinterpret()
            fnGtkScrolledWindowSetPolicy = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_scrolled_window_set_policy")?.reinterpret()
            fnGtkScrolledWindowGetVadjustment = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_scrolled_window_get_vadjustment")?.reinterpret()
            fnGtkScrolledWindowGetHadjustment = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_scrolled_window_get_hadjustment")?.reinterpret()
            fnGtkAdjustmentGetValue = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_adjustment_get_value")?.reinterpret()
            fnGtkAdjustmentSetValue = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_adjustment_set_value")?.reinterpret()
            fnGtkAdjustmentGetUpper = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_adjustment_get_upper")?.reinterpret()
            fnGtkAdjustmentGetLower = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_adjustment_get_lower")?.reinterpret()
            fnGtkAdjustmentGetPageSize = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_adjustment_get_page_size")?.reinterpret()
            fnGtkPanedNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_paned_new")?.reinterpret()
            fnGtkPanedSetStartChild = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_paned_set_start_child")?.reinterpret()
            fnGtkPanedSetEndChild = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_paned_set_end_child")?.reinterpret()
            fnGtkPanedSetPosition = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_paned_set_position")?.reinterpret()
            fnGtkPanedGetPosition = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_paned_get_position")?.reinterpret()
            fnGtkPanedSetResizeStartChild = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_paned_set_resize_start_child")?.reinterpret()
            fnGtkPanedSetResizeEndChild = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_paned_set_resize_end_child")?.reinterpret()
            fnGtkPanedSetShrinkStartChild = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_paned_set_shrink_start_child")?.reinterpret()
            fnGtkPanedSetShrinkEndChild = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_paned_set_shrink_end_child")?.reinterpret()
            fnGtkStackNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_stack_new")?.reinterpret()
            fnGtkStackAddNamed = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_stack_add_named")?.reinterpret()
            fnGtkStackRemove = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_stack_remove")?.reinterpret()
            fnGtkStackSetVisibleChildName = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_stack_set_visible_child_name")?.reinterpret()
            fnGtkStackSetTransitionType = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_stack_set_transition_type")?.reinterpret()
            fnGtkNotebookInsertPage = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_notebook_insert_page")?.reinterpret()
            fnGtkNotebookSetTabPos = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_notebook_set_tab_pos")?.reinterpret()
            fnGtkNotebookSetScrollable = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_notebook_set_scrollable")?.reinterpret()
            fnGtkNotebookGetCurrentPage = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_notebook_get_current_page")?.reinterpret()
            fnGtkNotebookSetShowTabs = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_notebook_set_show_tabs")?.reinterpret()
            fnGtkFlowBoxNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_flow_box_new")?.reinterpret()
            fnGtkFlowBoxInsert = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_flow_box_insert")?.reinterpret()
            fnGtkFlowBoxRemove = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_flow_box_remove")?.reinterpret()
            fnGtkFlowBoxSetSelectionMode = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_flow_box_set_selection_mode")?.reinterpret()
            fnGtkFlowBoxSetMinChildrenPerLine = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_flow_box_set_min_children_per_line")?.reinterpret()
            fnGtkFlowBoxSetMaxChildrenPerLine = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_flow_box_set_max_children_per_line")?.reinterpret()
            fnGtkMenuButtonNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_menu_button_new")?.reinterpret()
            fnGtkMenuButtonSetPopover = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_menu_button_set_popover")?.reinterpret()
            fnGtkMenuButtonSetMenuModel = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_menu_button_set_menu_model")?.reinterpret()
            fnGtkMenuButtonSetLabel = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_menu_button_set_label")?.reinterpret()
            fnGtkMenuButtonSetChild = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_menu_button_set_child")?.reinterpret()
            fnGtkMenuButtonPopup = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_menu_button_popup")?.reinterpret()
            fnGtkMenuButtonPopdown = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_menu_button_popdown")?.reinterpret()
            fnGtkPopoverNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_popover_new")?.reinterpret()
            fnGtkPopoverSetChild = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_popover_set_child")?.reinterpret()
            fnGtkPopoverSetPointingTo = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_popover_set_pointing_to")?.reinterpret()
            fnGtkPopoverPopup = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_popover_popup")?.reinterpret()
            fnGtkPopoverPopdown = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_popover_popdown")?.reinterpret()
            fnGtkPopoverSetPosition = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_popover_set_position")?.reinterpret()
            fnGtkPopoverSetAutohide = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_popover_set_autohide")?.reinterpret()
            fnGtkPopoverSetHasArrow = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_popover_set_has_arrow")?.reinterpret()
            fnGtkPopoverMenuNewFromModel = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_popover_menu_new_from_model")?.reinterpret()
            fnGtkPopoverMenuSetMenuModel = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_popover_menu_set_menu_model")?.reinterpret()
            fnGtkPopoverMenuBarNewFromModel = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_popover_menu_bar_new_from_model")?.reinterpret()
            fnGtkPopoverMenuBarSetMenuModel = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_popover_menu_bar_set_menu_model")?.reinterpret()
            fnGtkButtonSetLabel = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_button_set_label")?.reinterpret()
            fnGtkButtonSetChild = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_button_set_child")?.reinterpret()
            fnGtkButtonGetChild = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_button_get_child")?.reinterpret()
            fnGtkPasswordEntryNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_password_entry_new")?.reinterpret()
            fnGtkPasswordEntrySetShowPeekIcon = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_password_entry_set_show_peek_icon")?.reinterpret()
            fnGtkSearchEntryNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_search_entry_new")?.reinterpret()
            fnGtkEditableSetEditable = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_editable_set_editable")?.reinterpret()
            fnGtkSpinButtonNewWithRange = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_spin_button_new_with_range")?.reinterpret()
            fnGtkSpinButtonSetValue = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_spin_button_set_value")?.reinterpret()
            fnGtkSpinButtonGetValue = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_spin_button_get_value")?.reinterpret()
            fnGtkSpinButtonSetRange = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_spin_button_set_range")?.reinterpret()
            fnGtkSpinButtonSetIncrements = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_spin_button_set_increments")?.reinterpret()
            fnGtkSpinButtonSetDigits = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_spin_button_set_digits")?.reinterpret()
            fnGtkTextViewGetBuffer = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_text_view_get_buffer")?.reinterpret()
            fnGtkTextViewSetEditable = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_text_view_set_editable")?.reinterpret()
            fnGtkTextViewSetWrapMode = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_text_view_set_wrap_mode")?.reinterpret()
            fnGtkTextViewSetMonospace = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_text_view_set_monospace")?.reinterpret()
            fnGtkTextBufferSetText = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_text_buffer_set_text")?.reinterpret()
            fnGtkTextBufferGetStartIter = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_text_buffer_get_start_iter")?.reinterpret()
            fnGtkTextBufferGetEndIter = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_text_buffer_get_end_iter")?.reinterpret()
            fnGtkTextBufferGetText = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_text_buffer_get_text")?.reinterpret()
            fnGtkPictureNewForPaintable = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_picture_new_for_paintable")?.reinterpret()
            fnGtkPictureSetPaintable = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_picture_set_paintable")?.reinterpret()
            fnGtkPictureSetContentFit = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_picture_set_content_fit")?.reinterpret()
            fnGtkPictureSetCanShrink = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_picture_set_can_shrink")?.reinterpret()
            fnGtkSeparatorNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_separator_new")?.reinterpret()
            fnGtkColumnViewNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_column_view_new")?.reinterpret()
            fnGtkColumnViewSetModel = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_column_view_set_model")?.reinterpret()
            fnGtkColumnViewAppendColumn = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_column_view_append_column")?.reinterpret()
            fnGtkColumnViewRemoveColumn = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_column_view_remove_column")?.reinterpret()
            fnGtkColumnViewColumnNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_column_view_column_new")?.reinterpret()
            fnGtkColumnViewColumnSetTitle = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_column_view_column_set_title")?.reinterpret()
            fnGtkColumnViewColumnSetExpand = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_column_view_column_set_expand")?.reinterpret()
            fnGtkColumnViewColumnSetFixedWidth = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_column_view_column_set_fixed_width")?.reinterpret()
            fnGtkColumnViewColumnSetResizable = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_column_view_column_set_resizable")?.reinterpret()
            fnGtkSingleSelectionNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_single_selection_new")?.reinterpret()
            fnGtkNoSelectionNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_no_selection_new")?.reinterpret()
            fnGtkStringListNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_string_list_new")?.reinterpret()
            fnGtkSignalListItemFactoryNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_signal_list_item_factory_new")?.reinterpret()
            fnGtkListItemSetChild = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_list_item_set_child")?.reinterpret()
            fnGtkListItemGetChild = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_list_item_get_child")?.reinterpret()
            fnGtkListItemGetItem = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_list_item_get_item")?.reinterpret()
            fnGtkStringObjectGetString = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_string_object_get_string")?.reinterpret()
            fnGtkDropDownSetModel = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_drop_down_set_model")?.reinterpret()
            fnGtkDropDownSetEnableSearch = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_drop_down_set_enable_search")?.reinterpret()
            fnGtkListBoxNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_list_box_new")?.reinterpret()
            fnGtkListBoxAppend = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_list_box_append")?.reinterpret()
            fnGtkListBoxInsert = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_list_box_insert")?.reinterpret()
            fnGtkListBoxRemove = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_list_box_remove")?.reinterpret()
            fnGtkListBoxSelectRow = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_list_box_select_row")?.reinterpret()
            fnGtkListBoxGetRowAtIndex = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_list_box_get_row_at_index")?.reinterpret()
            fnGtkListBoxSetSelectionMode = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_list_box_set_selection_mode")?.reinterpret()
            fnGtkListBoxRowNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_list_box_row_new")?.reinterpret()
            fnGtkListBoxRowSetChild = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_list_box_row_set_child")?.reinterpret()
            fnGtkExpanderNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_expander_new")?.reinterpret()
            fnGtkExpanderSetChild = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_expander_set_child")?.reinterpret()
            fnGtkExpanderSetExpanded = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_expander_set_expanded")?.reinterpret()
            fnGtkExpanderGetExpanded = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_expander_get_expanded")?.reinterpret()
            fnGtkExpanderSetLabelWidget = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_expander_set_label_widget")?.reinterpret()
            fnGtkCalendarNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_calendar_new")?.reinterpret()
            fnGtkCalendarGetDate = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_calendar_get_date")?.reinterpret()
            fnGtkCalendarSelectDay = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_calendar_select_day")?.reinterpret()
            fnGtkColorDialogButtonNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_color_dialog_button_new")?.reinterpret()
            fnGtkColorDialogButtonSetRgba = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_color_dialog_button_set_rgba")?.reinterpret()
            fnGtkColorDialogButtonGetRgba = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_color_dialog_button_get_rgba")?.reinterpret()
            fnGtkCheckButtonGetActive = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_check_button_get_active")?.reinterpret()
            fnGtkCheckButtonSetLabel = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_check_button_set_label")?.reinterpret()
            fnGtkSwitchGetActive = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_switch_get_active")?.reinterpret()
            fnGtkRangeGetValue = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_range_get_value")?.reinterpret()
            fnGtkRangeSetRange = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_range_set_range")?.reinterpret()
            fnGtkProgressBarSetText = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_progress_bar_set_text")?.reinterpret()
            fnGtkProgressBarSetShowText = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_progress_bar_set_show_text")?.reinterpret()
            fnGtkSpinnerStart = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_spinner_start")?.reinterpret()
            fnGtkSpinnerStop = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_spinner_stop")?.reinterpret()
            fnGtkLabelSetText = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_label_set_text")?.reinterpret()
            fnGtkLabelSetMarkup = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_label_set_markup")?.reinterpret()
            fnGtkLabelSetXalign = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_label_set_xalign")?.reinterpret()
            fnGtkLabelSetYalign = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_label_set_yalign")?.reinterpret()
            fnGtkLabelSetWrapMode = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_label_set_wrap_mode")?.reinterpret()
            fnGtkLabelSetEllipsize = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_label_set_ellipsize")?.reinterpret()
            fnGtkLabelSetSelectable = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_label_set_selectable")?.reinterpret()
            fnGtkGestureSingleGetCurrentButton = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_gesture_single_get_current_button")?.reinterpret()
            fnGtkEventControllerFocusNew = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_event_controller_focus_new")?.reinterpret()
            fnGtkEventControllerFocusContainsFocus = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_event_controller_focus_contains_focus")?.reinterpret()
            fnGtkEventControllerFocusIsFocus = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_event_controller_focus_is_focus")?.reinterpret()
            fnGtkWidgetInsertActionGroup = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_widget_insert_action_group")?.reinterpret()
            fnGskTransformTranslate = LinuxDynamicLibraryLoader.symbol(gtk, "gsk_transform_translate")?.reinterpret()
            fnGskTransformRotate = LinuxDynamicLibraryLoader.symbol(gtk, "gsk_transform_rotate")?.reinterpret()
            fnGskTransformScale = LinuxDynamicLibraryLoader.symbol(gtk, "gsk_transform_scale")?.reinterpret()
            fnGskTransformSkew = LinuxDynamicLibraryLoader.symbol(gtk, "gsk_transform_skew")?.reinterpret()
            fnGskTransformUnref = LinuxDynamicLibraryLoader.symbol(gtk, "gsk_transform_unref")?.reinterpret()
            fnGtkSnapshotPushRoundedClip = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_snapshot_push_rounded_clip")?.reinterpret()
            fnGtkSnapshotPushColorMatrix = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_snapshot_push_color_matrix")?.reinterpret()
            fnGtkSnapshotPushOpacity = LinuxDynamicLibraryLoader.symbol(gtk, "gtk_snapshot_push_opacity")?.reinterpret()
            fnGdkPaintableGetIntrinsicWidth = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_paintable_get_intrinsic_width")?.reinterpret()
            fnGdkPaintableGetIntrinsicHeight = LinuxDynamicLibraryLoader.symbol(gtk, "gdk_paintable_get_intrinsic_height")?.reinterpret()
        }

        if (adw != null) {
            fnAdwSpinnerNew = LinuxDynamicLibraryLoader.symbol(adw, "adw_spinner_new")?.reinterpret()
            fnAdwInit = LinuxDynamicLibraryLoader.symbol(adw, "adw_init")?.reinterpret()
            fnAdwStyleManagerGetDefault = LinuxDynamicLibraryLoader.symbol(adw, "adw_style_manager_get_default")?.reinterpret()
            fnAdwStyleManagerSetColorScheme = LinuxDynamicLibraryLoader.symbol(adw, "adw_style_manager_set_color_scheme")?.reinterpret()
            fnAdwStyleManagerGetDark = LinuxDynamicLibraryLoader.symbol(adw, "adw_style_manager_get_dark")?.reinterpret()
            fnAdwCallbackAnimationTargetNew = LinuxDynamicLibraryLoader.symbol(adw, "adw_callback_animation_target_new")?.reinterpret()
            fnAdwTimedAnimationNew = LinuxDynamicLibraryLoader.symbol(adw, "adw_timed_animation_new")?.reinterpret()
            fnAdwTimedAnimationSetEasing = LinuxDynamicLibraryLoader.symbol(adw, "adw_timed_animation_set_easing")?.reinterpret()
            fnAdwAnimationPlay = LinuxDynamicLibraryLoader.symbol(adw, "adw_animation_play")?.reinterpret()
            fnAdwAnimationPause = LinuxDynamicLibraryLoader.symbol(adw, "adw_animation_pause")?.reinterpret()
            fnAdwAnimationSkip = LinuxDynamicLibraryLoader.symbol(adw, "adw_animation_skip")?.reinterpret()
            fnAdwAnimationGetState = LinuxDynamicLibraryLoader.symbol(adw, "adw_animation_get_state")?.reinterpret()
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
            fnCairoImageSurfaceCreate = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_image_surface_create")?.reinterpret()
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
            fnCairoClipPreserve = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_clip_preserve")?.reinterpret()
            fnCairoSetDash = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_set_dash")?.reinterpret()
            fnCairoPatternCreateRadial = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_pattern_create_radial")?.reinterpret()
            fnCairoSetOperator = LinuxDynamicLibraryLoader.symbol(cairo, "cairo_set_operator")?.reinterpret()
        }

        if (gobject != null) {
            fnGObjectSetQdataFull = LinuxDynamicLibraryLoader.symbol(gobject, "g_object_set_qdata_full")?.reinterpret()
            fnGObjectGetQdata = LinuxDynamicLibraryLoader.symbol(gobject, "g_object_get_qdata")?.reinterpret()
        }

        if (glib != null) {
            fnGQuarkFromString = LinuxDynamicLibraryLoader.symbol(glib, "g_quark_from_string")?.reinterpret()
            fnGFree = LinuxDynamicLibraryLoader.symbol(glib, "g_free")?.reinterpret()
            fnGDateTimeNewLocal = LinuxDynamicLibraryLoader.symbol(glib, "g_date_time_new_local")?.reinterpret()
            fnGDateTimeGetYear = LinuxDynamicLibraryLoader.symbol(glib, "g_date_time_get_year")?.reinterpret()
            fnGDateTimeGetMonth = LinuxDynamicLibraryLoader.symbol(glib, "g_date_time_get_month")?.reinterpret()
            fnGDateTimeGetDayOfMonth = LinuxDynamicLibraryLoader.symbol(glib, "g_date_time_get_day_of_month")?.reinterpret()
            fnGDateTimeUnref = LinuxDynamicLibraryLoader.symbol(glib, "g_date_time_unref")?.reinterpret()
        }

        if (gio != null) {
            fnGMenuNew = LinuxDynamicLibraryLoader.symbol(gio, "g_menu_new")?.reinterpret()
            fnGMenuAppend = LinuxDynamicLibraryLoader.symbol(gio, "g_menu_append")?.reinterpret()
            fnGMenuAppendSubmenu = LinuxDynamicLibraryLoader.symbol(gio, "g_menu_append_submenu")?.reinterpret()
            fnGMenuRemoveAll = LinuxDynamicLibraryLoader.symbol(gio, "g_menu_remove_all")?.reinterpret()
            fnGSimpleActionGroupNew = LinuxDynamicLibraryLoader.symbol(gio, "g_simple_action_group_new")?.reinterpret()
            fnGSimpleActionNew = LinuxDynamicLibraryLoader.symbol(gio, "g_simple_action_new")?.reinterpret()
            fnGActionMapAddAction = LinuxDynamicLibraryLoader.symbol(gio, "g_action_map_add_action")?.reinterpret()
        }

        if (graphene != null) {
            fnGrapheneMatrixInitFromFloat = LinuxDynamicLibraryLoader.symbol(graphene, "graphene_matrix_init_from_float")?.reinterpret()
            fnGrapheneVec4Init = LinuxDynamicLibraryLoader.symbol(graphene, "graphene_vec4_init")?.reinterpret()
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

    const val ADW_EASING_LINEAR = 0
    const val ADW_ANIMATION_IDLE = 0
    const val ADW_ANIMATION_PAUSED = 1
    const val ADW_ANIMATION_PLAYING = 2
    const val ADW_ANIMATION_FINISHED = 3

    fun hasAdwTimedAnimation(): Boolean =
        fnAdwCallbackAnimationTargetNew != null && fnAdwTimedAnimationNew != null && fnAdwAnimationPlay != null

    fun adwStyleManagerSetColorScheme(scheme: Int) {
        if (!gtkInitializedOk) return
        val mgr = fnAdwStyleManagerGetDefault?.invoke() ?: return
        fnAdwStyleManagerSetColorScheme?.invoke(mgr, scheme)
    }

    fun adwCallbackAnimationTargetNew(
        callback: COpaquePointer,
        userData: COpaquePointer?,
        destroyNotify: COpaquePointer? = null
    ): COpaquePointer? = fnAdwCallbackAnimationTargetNew?.invoke(callback, userData, destroyNotify)

    fun adwTimedAnimationNew(
        widget: COpaquePointer,
        from: Double,
        to: Double,
        durationMs: UInt,
        target: COpaquePointer
    ): COpaquePointer? = fnAdwTimedAnimationNew?.invoke(widget, from, to, durationMs, target)

    fun adwTimedAnimationSetEasing(animation: COpaquePointer, easing: Int = ADW_EASING_LINEAR) {
        fnAdwTimedAnimationSetEasing?.invoke(animation, easing)
    }

    fun adwAnimationPlay(animation: COpaquePointer) { fnAdwAnimationPlay?.invoke(animation) }
    fun adwAnimationPause(animation: COpaquePointer) { fnAdwAnimationPause?.invoke(animation) }
    fun adwAnimationSkip(animation: COpaquePointer) { fnAdwAnimationSkip?.invoke(animation) }
    fun adwAnimationGetState(animation: COpaquePointer): Int = fnAdwAnimationGetState?.invoke(animation) ?: ADW_ANIMATION_IDLE

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
    fun gtkWindowIsActive(win: COpaquePointer): Boolean = (fnGtkWindowIsActive?.invoke(win) ?: 0) != 0
    fun gtkEventControllerGetCurrentEvent(controller: COpaquePointer?): COpaquePointer? =
        controller?.let { fnGtkEventControllerGetCurrentEvent?.invoke(it) }
    fun gtkWindowShowWindowMenu(win: COpaquePointer, gdkEvent: COpaquePointer?): Boolean {
        val ev = gdkEvent ?: return false
        val surface = gtkWindowSurface(win) ?: return false
        val toplevelType = fnGdkToplevelGetType?.invoke() ?: 0uL
        if (toplevelType == 0uL || !LinuxGlibGioBindings.typeCheckInstanceIsA(surface, toplevelType)) return false
        return (fnGdkToplevelShowWindowMenu?.invoke(surface, ev) ?: 0) != 0
    }
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
    fun gtkWindowBeginResize(
        win: COpaquePointer,
        edge: Int,
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
        fnGdkToplevelBeginResize?.invoke(surface, edge, pointer, button, xVar.value, yVar.value, timestamp)
        fnGdkToplevelBeginResize != null
    }
    fun gtkWindowSetUndecoratedRectClass(win: COpaquePointer, enabled: Boolean) = memScoped {
        if (!undecoratedCssInstalled) {
            val display = fnGtkWidgetGetDisplay?.invoke(win) ?: fnGdkDisplayGetDefault?.invoke()
            val provider = fnGtkCssProviderNew?.invoke()
            if (display != null && provider != null && fnGtkCssProviderLoadFromData != null && fnGtkStyleContextAddProviderForDisplay != null) {
                val css = "window.genericui-undecorated-rect, window.genericui-undecorated-rect decoration { border-radius: 0px; }"
                fnGtkCssProviderLoadFromData?.invoke(provider, css.cstr.ptr, -1L)
                fnGtkStyleContextAddProviderForDisplay?.invoke(display, provider, GTK_STYLE_PROVIDER_PRIORITY_USER)
                LinuxGlibGioBindings.objectUnref(provider)
                undecoratedCssInstalled = true
            }
        }
        val cssClass = "genericui-undecorated-rect"
        if (enabled) {
            fnGtkWidgetAddCssClass?.invoke(win, cssClass.cstr.ptr)
        } else {
            fnGtkWidgetRemoveCssClass?.invoke(win, cssClass.cstr.ptr)
        }
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
    fun gtkWidgetGetVisible(widget: COpaquePointer): Boolean = (fnGtkWidgetGetVisible?.invoke(widget) ?: 0) != 0
    fun gtkWidgetQueueDraw(widget: COpaquePointer) { fnGtkWidgetQueueDraw?.invoke(widget) }
    fun gtkWidgetAddController(widget: COpaquePointer, controller: COpaquePointer) { fnGtkWidgetAddController?.invoke(widget, controller) }
    fun gtkWidgetSetFocusable(widget: COpaquePointer, focusable: Boolean) { fnGtkWidgetSetFocusable?.invoke(widget, if (focusable) 1 else 0) }
    fun gtkWidgetGetFocusable(widget: COpaquePointer): Boolean = (fnGtkWidgetGetFocusable?.invoke(widget) ?: 1) != 0
    fun gtkWidgetGrabFocus(widget: COpaquePointer) { fnGtkWidgetGrabFocus?.invoke(widget) }
    fun gtkWidgetSetCursor(widget: COpaquePointer, cursor: COpaquePointer?) { fnGtkWidgetSetCursor?.invoke(widget, cursor) }
    fun gtkWidgetSetCursorFromName(widget: COpaquePointer, name: String?) = memScoped {
        fnGtkWidgetSetCursorFromName?.invoke(widget, name?.cstr?.ptr)
    }
    fun gtkWidgetSetOpacity(widget: COpaquePointer, opacity: Double) { fnGtkWidgetSetOpacity?.invoke(widget, opacity) }
    fun gtkWidgetGetDisplay(widget: COpaquePointer): COpaquePointer? = fnGtkWidgetGetDisplay?.invoke(widget)
    fun gtkWidgetGetRealized(widget: COpaquePointer): Boolean = (fnGtkWidgetGetRealized?.invoke(widget) ?: 0) != 0
    fun gtkWidgetAddTickCallback(
        widget: COpaquePointer,
        callback: COpaquePointer,
        userData: COpaquePointer?,
        destroyNotify: COpaquePointer? = null
    ): UInt = fnGtkWidgetAddTickCallback?.invoke(widget, callback, userData, destroyNotify) ?: 0u
    fun gtkWidgetRemoveTickCallback(widget: COpaquePointer, tickId: UInt) {
        if (tickId != 0u) fnGtkWidgetRemoveTickCallback?.invoke(widget, tickId)
    }
    fun gtkWidgetGetFrameClock(widget: COpaquePointer): COpaquePointer? = fnGtkWidgetGetFrameClock?.invoke(widget)
    fun gdkFrameClockGetFrameTime(frameClock: COpaquePointer): Long = fnGdkFrameClockGetFrameTime?.invoke(frameClock) ?: 0L
    fun gdkDisplayGetMonitorAtSurface(display: COpaquePointer, surface: COpaquePointer): COpaquePointer? =
        fnGdkDisplayGetMonitorAtSurface?.invoke(display, surface)
    fun gdkMonitorGetRefreshRateMilliHz(monitor: COpaquePointer): Int =
        fnGdkMonitorGetRefreshRate?.invoke(monitor) ?: 0
    fun queryMonitorRefreshRateHz(widget: COpaquePointer? = null): Double? {
        if (!ensureGtkInitialized()) return null
        if (widget != null) {
            val surface = gtkWindowSurface(widget)
            val display = fnGtkWidgetGetDisplay?.invoke(widget) ?: fnGdkDisplayGetDefault?.invoke()
            if (surface != null && display != null) {
                val mon = fnGdkDisplayGetMonitorAtSurface?.invoke(display, surface)
                if (mon != null) {
                    val mHz = fnGdkMonitorGetRefreshRate?.invoke(mon) ?: 0
                    if (mHz >= 10_000) return mHz / 1000.0
                }
            }
        }
        val dpy = fnGdkDisplayGetDefault?.invoke() ?: return null
        val monitors = fnGdkDisplayGetMonitors?.invoke(dpy) ?: return null
        val count = LinuxGlibGioBindings.listModelGetNItems(monitors)
        for (i in 0 until count) {
            val mon = LinuxGlibGioBindings.listModelGetItem(monitors, i) ?: continue
            try {
                val mHz = fnGdkMonitorGetRefreshRate?.invoke(mon) ?: 0
                if (mHz >= 10_000) return mHz / 1000.0
            } finally {
                LinuxGlibGioBindings.objectUnref(mon)
            }
        }
        return null
    }
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

    fun cairoPaintPremulArgbSurface(
        cr: COpaquePointer,
        premulArgb: IntArray,
        width: Int,
        height: Int,
        dstX: Double,
        dstY: Double,
        dstW: Double,
        dstH: Double,
        alpha: Double = 1.0
    ) {
        if (width <= 0 || height <= 0 || premulArgb.size < width * height) return
        val effAlpha = alpha.coerceIn(0.0, 1.0)
        if (effAlpha <= 0.002) return
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
                val paintWithAlpha = fnCairoPaintWithAlpha
                if (effAlpha < 0.998 && paintWithAlpha != null) {
                    paintWithAlpha.invoke(cr, effAlpha)
                } else {
                    cairoPaint(cr)
                }
                cairoRestore(cr)
            } finally {
                fnCairoSurfaceDestroy?.invoke(surface)
            }
        }
    }

    private var cairoScratchArgb: IntArray = IntArray(0)

    fun cairoApplyBackdropFilterRegion(
        cr: COpaquePointer,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        radiusPx: Float,
        filter: com.nexustools.genericui.style.css.CssFilter,
        opacity: Float = 1f
    ) {
        if (w <= 0 || h <= 0 || !filter.hasActiveEffects) return
        val effOpacity = opacity.coerceIn(0f, 1f)
        if (effOpacity <= 0.002f) return
        val op255 = (effOpacity * 255f + 0.5f).toInt().coerceIn(0, 255)
        if (op255 <= 0) return
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

        val invOp = 255 - op255
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
                val blurA = if (fmt == 1) 255 else a
                if (op255 >= 255) {
                    rowWords[px] = (blurA shl 24) or (r shl 16) or (g shl 8) or b
                } else {
                    val orig = rowWords[px]
                    val origA = if (fmt == 1) 255 else (orig ushr 24) and 0xFF
                    val origR = (orig ushr 16) and 0xFF
                    val origG = (orig ushr 8) and 0xFF
                    val origB = orig and 0xFF
                    val outA = if (fmt == 1) 255 else ((origA * invOp + blurA * op255 + 127) / 255).coerceIn(0, 255)
                    val outR = ((origR * invOp + r * op255 + 127) / 255).coerceIn(0, 255)
                    val outG = ((origG * invOp + g * op255 + 127) / 255).coerceIn(0, 255)
                    val outB = ((origB * invOp + b * op255 + 127) / 255).coerceIn(0, 255)
                    rowWords[px] = (outA shl 24) or (outR shl 16) or (outG shl 8) or outB
                }
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

    fun buildGtkStateFlags(
        enabled: Boolean = true,
        hovered: Boolean = false,
        pressed: Boolean = false,
        focused: Boolean = false,
        checked: Boolean = false,
        inconsistent: Boolean = false
    ): UInt {
        var flags = GTK_STATE_FLAG_NORMAL
        if (!enabled) {
            flags = flags or GTK_STATE_FLAG_INSENSITIVE
        } else {
            if (hovered) flags = flags or GTK_STATE_FLAG_PRELIGHT
            if (pressed) flags = flags or GTK_STATE_FLAG_ACTIVE
            if (focused) flags = flags or GTK_STATE_FLAG_FOCUSED or GTK_STATE_FLAG_FOCUS_VISIBLE
        }
        if (checked) flags = flags or GTK_STATE_FLAG_CHECKED
        if (inconsistent) flags = flags or GTK_STATE_FLAG_INCONSISTENT
        return flags
    }

    private fun syncGtkColorScheme(darkMode: Boolean) {
        adwStyleManagerSetColorScheme(
            if (darkMode) ADW_COLOR_SCHEME_FORCE_DARK else ADW_COLOR_SCHEME_FORCE_LIGHT
        )
    }

    fun gtkCustomLayoutNew(
        requestMode: COpaquePointer? = null,
        measure: COpaquePointer,
        allocate: COpaquePointer
    ): COpaquePointer? = fnGtkCustomLayoutNew?.invoke(requestMode, measure, allocate)

    fun gtkWidgetSetLayoutManager(widget: COpaquePointer, layoutManager: COpaquePointer?) {
        fnGtkWidgetSetLayoutManager?.invoke(widget, layoutManager)
    }

    fun gtkWidgetGetLayoutManager(widget: COpaquePointer): COpaquePointer? =
        fnGtkWidgetGetLayoutManager?.invoke(widget)

    fun gtkLayoutManagerLayoutChanged(manager: COpaquePointer?) {
        if (manager != null) fnGtkLayoutManagerLayoutChanged?.invoke(manager)
    }

    fun gtkWidgetQueueAllocate(widget: COpaquePointer) {
        fnGtkWidgetQueueAllocate?.invoke(widget)
    }

    fun gtkWidgetQueueResize(widget: COpaquePointer) {
        fnGtkWidgetQueueResize?.invoke(widget)
    }

    fun gtkWidgetMeasure(widget: COpaquePointer, orientation: Int, forSize: Int = -1): Pair<Int, Int> = memScoped {
        val minVar = alloc<IntVar>()
        val natVar = alloc<IntVar>()
        fnGtkWidgetMeasure?.invoke(widget, orientation, forSize, minVar.ptr, natVar.ptr, null, null)
        minVar.value to natVar.value
    }

    fun gtkWidgetSizeAllocate(widget: COpaquePointer, x: Int, y: Int, width: Int, height: Int, baseline: Int = -1) = memScoped {
        val allocRect = allocArray<IntVar>(4)
        allocRect[0] = x
        allocRect[1] = y
        allocRect[2] = width.coerceAtLeast(0)
        allocRect[3] = height.coerceAtLeast(0)
        fnGtkWidgetSizeAllocate?.invoke(widget, allocRect, baseline)
    }

    fun gtkWidgetAllocate(
        widget: COpaquePointer,
        width: Int,
        height: Int,
        baseline: Int = -1,
        transform: COpaquePointer? = null
    ) {
        fnGtkWidgetAllocate?.invoke(widget, width.coerceAtLeast(0), height.coerceAtLeast(0), baseline, transform)
    }

    fun gtkWidgetInsertAfter(widget: COpaquePointer, parent: COpaquePointer, previousSibling: COpaquePointer? = null) {
        fnGtkWidgetInsertAfter?.invoke(widget, parent, previousSibling)
    }

    fun gtkWidgetInsertBefore(widget: COpaquePointer, parent: COpaquePointer, nextSibling: COpaquePointer? = null) {
        fnGtkWidgetInsertBefore?.invoke(widget, parent, nextSibling)
    }

    fun gtkWidgetUnparent(widget: COpaquePointer) {
        fnGtkWidgetUnparent?.invoke(widget)
    }

    fun gtkWidgetSetParent(widget: COpaquePointer, parent: COpaquePointer) {
        fnGtkWidgetSetParent?.invoke(widget, parent)
    }

    fun gtkWidgetGetParent(widget: COpaquePointer): COpaquePointer? = fnGtkWidgetGetParent?.invoke(widget)

    fun gtkWidgetGetFirstChild(widget: COpaquePointer): COpaquePointer? = fnGtkWidgetGetFirstChild?.invoke(widget)

    fun gtkWidgetGetNextSibling(widget: COpaquePointer): COpaquePointer? = fnGtkWidgetGetNextSibling?.invoke(widget)

    fun gtkWidgetSetCanTarget(widget: COpaquePointer, canTarget: Boolean) {
        fnGtkWidgetSetCanTarget?.invoke(widget, if (canTarget) 1 else 0)
    }

    fun gtkWidgetGetCanTarget(widget: COpaquePointer): Boolean = (fnGtkWidgetGetCanTarget?.invoke(widget) ?: 1) != 0

    fun gtkWidgetSetSensitive(widget: COpaquePointer, sensitive: Boolean) {
        fnGtkWidgetSetSensitive?.invoke(widget, if (sensitive) 1 else 0)
    }

    fun gtkWidgetSetCanFocus(widget: COpaquePointer, canFocus: Boolean) {
        fnGtkWidgetSetCanFocus?.invoke(widget, if (canFocus) 1 else 0)
    }

    fun gtkWidgetGetCanFocus(widget: COpaquePointer): Boolean = (fnGtkWidgetGetCanFocus?.invoke(widget) ?: 1) != 0

    fun gtkWidgetHasFocus(widget: COpaquePointer): Boolean = (fnGtkWidgetHasFocus?.invoke(widget) ?: 0) != 0

    fun gtkWidgetSetFocusOnClick(widget: COpaquePointer, focusOnClick: Boolean) {
        fnGtkWidgetSetFocusOnClick?.invoke(widget, if (focusOnClick) 1 else 0)
    }

    fun gtkWidgetGetFocusOnClick(widget: COpaquePointer): Boolean = (fnGtkWidgetGetFocusOnClick?.invoke(widget) ?: 1) != 0

    fun gtkWidgetClearWindowFocus(widget: COpaquePointer) {
        val root = fnGtkWidgetGetRoot?.invoke(widget) ?: return
        fnGtkWindowSetFocus?.invoke(root, null)
    }

    fun gtkWidgetComputeBounds(widget: COpaquePointer, target: COpaquePointer): FloatArray? = memScoped {
        val rect = allocArray<FloatVar>(4)
        val ok = fnGtkWidgetComputeBounds?.invoke(widget, target, rect) ?: 0
        if (ok == 0) return@memScoped null
        floatArrayOf(rect[0], rect[1], rect[2], rect[3])
    }

    fun gtkWidgetSetOverflow(widget: COpaquePointer, overflow: Int) {
        fnGtkWidgetSetOverflow?.invoke(widget, overflow)
    }

    fun gtkWidgetSetTooltipText(widget: COpaquePointer, tooltip: String?) = memScoped {
        fnGtkWidgetSetTooltipText?.invoke(widget, tooltip?.cstr?.ptr)
    }

    fun gtkWidgetAddCssClass(widget: COpaquePointer, cssClass: String) = memScoped {
        fnGtkWidgetAddCssClass?.invoke(widget, cssClass.cstr.ptr)
    }

    fun gtkWidgetRemoveCssClass(widget: COpaquePointer, cssClass: String) = memScoped {
        fnGtkWidgetRemoveCssClass?.invoke(widget, cssClass.cstr.ptr)
    }

    fun gtkWidgetHasCssClass(widget: COpaquePointer, cssClass: String): Boolean = memScoped {
        (fnGtkWidgetHasCssClass?.invoke(widget, cssClass.cstr.ptr) ?: 0) != 0
    }

    fun gtkWidgetGetCssName(widget: COpaquePointer): String? =
        fnGtkWidgetGetCssName?.invoke(widget)?.toKString()

    fun gtkWidgetSetName(widget: COpaquePointer, name: String?) = memScoped {
        fnGtkWidgetSetName?.invoke(widget, name?.cstr?.ptr)
    }

    fun gtkWidgetGetName(widget: COpaquePointer): String? =
        fnGtkWidgetGetName?.invoke(widget)?.toKString()

    fun gtkWidgetSetHalign(widget: COpaquePointer, align: Int) {
        fnGtkWidgetSetHalign?.invoke(widget, align)
    }

    fun gtkWidgetSetValign(widget: COpaquePointer, align: Int) {
        fnGtkWidgetSetValign?.invoke(widget, align)
    }

    fun gtkWidgetGetColorArgb(widget: COpaquePointer): Int? = memScoped {
        val fn = fnGtkWidgetGetColor ?: return@memScoped null
        val rgba = allocArray<FloatVar>(4)
        fn.invoke(widget, rgba)
        val a = (rgba[3].coerceIn(0f, 1f) * 255f + 0.5f).toInt() and 0xFF
        val r = (rgba[0].coerceIn(0f, 1f) * 255f + 0.5f).toInt() and 0xFF
        val g = (rgba[1].coerceIn(0f, 1f) * 255f + 0.5f).toInt() and 0xFF
        val b = (rgba[2].coerceIn(0f, 1f) * 255f + 0.5f).toInt() and 0xFF
        (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun gtkCssProviderNew(): COpaquePointer? = fnGtkCssProviderNew?.invoke()

    fun gtkCssProviderLoadCss(provider: COpaquePointer, css: String) = memScoped {
        val fnStr = fnGtkCssProviderLoadFromString
        if (fnStr != null) {
            fnStr.invoke(provider, css.cstr.ptr)
        } else {
            fnGtkCssProviderLoadFromData?.invoke(provider, css.cstr.ptr, -1L)
        }
    }

    fun gtkStyleContextAddProviderForDisplay(display: COpaquePointer, provider: COpaquePointer, priority: UInt = GTK_STYLE_PROVIDER_PRIORITY_USER) {
        fnGtkStyleContextAddProviderForDisplay?.invoke(display, provider, priority)
    }

    fun gdkDisplayGetDefault(): COpaquePointer? = fnGdkDisplayGetDefault?.invoke()

    fun gtkBoxRemove(box: COpaquePointer, child: COpaquePointer) {
        fnGtkBoxRemove?.invoke(box, child)
    }

    fun gtkWindowSetDefaultWidget(win: COpaquePointer, widget: COpaquePointer?) {
        fnGtkWindowSetDefaultWidget?.invoke(win, widget)
    }

    fun gtkWidgetGetNative(widget: COpaquePointer): COpaquePointer? = fnGtkWidgetGetNative?.invoke(widget)

    fun gtkNativeGetSurface(nativeObj: COpaquePointer): COpaquePointer? = fnGtkNativeGetSurface?.invoke(nativeObj)

    fun gtkOverlayNew(): COpaquePointer? = fnGtkOverlayNew?.invoke()

    fun gtkOverlaySetChild(overlay: COpaquePointer, child: COpaquePointer?) {
        fnGtkOverlaySetChild?.invoke(overlay, child)
    }

    fun gtkOverlayAddOverlay(overlay: COpaquePointer, widget: COpaquePointer) {
        fnGtkOverlayAddOverlay?.invoke(overlay, widget)
    }

    fun gtkOverlayRemoveOverlay(overlay: COpaquePointer, widget: COpaquePointer) {
        fnGtkOverlayRemoveOverlay?.invoke(overlay, widget)
    }

    fun gtkScrolledWindowNew(): COpaquePointer? = fnGtkScrolledWindowNew?.invoke()

    fun gtkScrolledWindowSetChild(sw: COpaquePointer, child: COpaquePointer?) {
        fnGtkScrolledWindowSetChild?.invoke(sw, child)
    }

    fun gtkScrolledWindowSetPolicy(sw: COpaquePointer, hPolicy: Int, vPolicy: Int) {
        fnGtkScrolledWindowSetPolicy?.invoke(sw, hPolicy, vPolicy)
    }

    fun gtkScrolledWindowGetVadjustment(sw: COpaquePointer): COpaquePointer? =
        fnGtkScrolledWindowGetVadjustment?.invoke(sw)

    fun gtkScrolledWindowGetHadjustment(sw: COpaquePointer): COpaquePointer? =
        fnGtkScrolledWindowGetHadjustment?.invoke(sw)

    fun gtkAdjustmentGetValue(adj: COpaquePointer): Double = fnGtkAdjustmentGetValue?.invoke(adj) ?: 0.0

    fun gtkAdjustmentSetValue(adj: COpaquePointer, value: Double) {
        fnGtkAdjustmentSetValue?.invoke(adj, value)
    }

    fun gtkAdjustmentGetUpper(adj: COpaquePointer): Double = fnGtkAdjustmentGetUpper?.invoke(adj) ?: 0.0

    fun gtkAdjustmentGetLower(adj: COpaquePointer): Double = fnGtkAdjustmentGetLower?.invoke(adj) ?: 0.0

    fun gtkAdjustmentGetPageSize(adj: COpaquePointer): Double = fnGtkAdjustmentGetPageSize?.invoke(adj) ?: 0.0

    fun gtkPanedNew(orientation: Int): COpaquePointer? = fnGtkPanedNew?.invoke(orientation)

    fun gtkPanedSetStartChild(paned: COpaquePointer, child: COpaquePointer?) {
        fnGtkPanedSetStartChild?.invoke(paned, child)
    }

    fun gtkPanedSetEndChild(paned: COpaquePointer, child: COpaquePointer?) {
        fnGtkPanedSetEndChild?.invoke(paned, child)
    }

    fun gtkPanedSetPosition(paned: COpaquePointer, position: Int) {
        fnGtkPanedSetPosition?.invoke(paned, position)
    }

    fun gtkPanedGetPosition(paned: COpaquePointer): Int = fnGtkPanedGetPosition?.invoke(paned) ?: 0

    fun gtkPanedSetResizeAndShrink(
        paned: COpaquePointer,
        resizeStart: Boolean = true,
        resizeEnd: Boolean = true,
        shrinkStart: Boolean = false,
        shrinkEnd: Boolean = false
    ) {
        fnGtkPanedSetResizeStartChild?.invoke(paned, if (resizeStart) 1 else 0)
        fnGtkPanedSetResizeEndChild?.invoke(paned, if (resizeEnd) 1 else 0)
        fnGtkPanedSetShrinkStartChild?.invoke(paned, if (shrinkStart) 1 else 0)
        fnGtkPanedSetShrinkEndChild?.invoke(paned, if (shrinkEnd) 1 else 0)
    }

    fun gtkStackNew(): COpaquePointer? = fnGtkStackNew?.invoke()

    fun gtkStackAddNamed(stack: COpaquePointer, child: COpaquePointer, name: String): COpaquePointer? = memScoped {
        fnGtkStackAddNamed?.invoke(stack, child, name.cstr.ptr)
    }

    fun gtkStackRemove(stack: COpaquePointer, child: COpaquePointer) {
        fnGtkStackRemove?.invoke(stack, child)
    }

    fun gtkStackSetVisibleChildName(stack: COpaquePointer, name: String) = memScoped {
        fnGtkStackSetVisibleChildName?.invoke(stack, name.cstr.ptr)
    }

    fun gtkStackSetTransitionType(stack: COpaquePointer, transition: Int) {
        fnGtkStackSetTransitionType?.invoke(stack, transition)
    }

    fun gtkNotebookNew(): COpaquePointer? = fnGtkNotebookNew?.invoke()

    fun gtkNotebookAppendPage(nb: COpaquePointer, child: COpaquePointer, tabLabel: COpaquePointer?): Int =
        fnGtkNotebookAppendPage?.invoke(nb, child, tabLabel) ?: -1

    fun gtkNotebookInsertPage(nb: COpaquePointer, child: COpaquePointer, tabLabel: COpaquePointer?, position: Int): Int =
        fnGtkNotebookInsertPage?.invoke(nb, child, tabLabel, position) ?: -1

    fun gtkNotebookRemovePage(nb: COpaquePointer, pageNum: Int) {
        fnGtkNotebookRemovePage?.invoke(nb, pageNum)
    }

    fun gtkNotebookSetCurrentPage(nb: COpaquePointer, pageNum: Int) {
        fnGtkNotebookSetCurrentPage?.invoke(nb, pageNum)
    }

    fun gtkNotebookGetCurrentPage(nb: COpaquePointer): Int = fnGtkNotebookGetCurrentPage?.invoke(nb) ?: -1

    fun gtkNotebookGetNPages(nb: COpaquePointer): Int = fnGtkNotebookGetNPages?.invoke(nb) ?: 0

    fun gtkNotebookSetTabPos(nb: COpaquePointer, pos: Int) {
        fnGtkNotebookSetTabPos?.invoke(nb, pos)
    }

    fun gtkNotebookSetScrollable(nb: COpaquePointer, scrollable: Boolean) {
        fnGtkNotebookSetScrollable?.invoke(nb, if (scrollable) 1 else 0)
    }

    fun gtkNotebookSetShowTabs(nb: COpaquePointer, showTabs: Boolean) {
        fnGtkNotebookSetShowTabs?.invoke(nb, if (showTabs) 1 else 0)
    }

    fun gtkFlowBoxNew(): COpaquePointer? = fnGtkFlowBoxNew?.invoke()

    fun gtkFlowBoxInsert(fb: COpaquePointer, child: COpaquePointer, position: Int = -1) {
        fnGtkFlowBoxInsert?.invoke(fb, child, position)
    }

    fun gtkFlowBoxRemove(fb: COpaquePointer, child: COpaquePointer) {
        fnGtkFlowBoxRemove?.invoke(fb, child)
    }

    fun gtkFlowBoxSetSelectionMode(fb: COpaquePointer, mode: Int) {
        fnGtkFlowBoxSetSelectionMode?.invoke(fb, mode)
    }

    fun gtkFlowBoxSetColumnsRange(fb: COpaquePointer, minPerLine: Int, maxPerLine: Int) {
        fnGtkFlowBoxSetMinChildrenPerLine?.invoke(fb, minPerLine.coerceAtLeast(1).toUInt())
        fnGtkFlowBoxSetMaxChildrenPerLine?.invoke(fb, maxPerLine.coerceAtLeast(1).toUInt())
    }

    fun gtkMenuButtonNew(): COpaquePointer? = fnGtkMenuButtonNew?.invoke()

    fun gtkMenuButtonSetPopover(mb: COpaquePointer, popover: COpaquePointer?) {
        fnGtkMenuButtonSetPopover?.invoke(mb, popover)
    }

    fun gtkMenuButtonSetMenuModel(mb: COpaquePointer, menuModel: COpaquePointer?) {
        fnGtkMenuButtonSetMenuModel?.invoke(mb, menuModel)
    }

    fun gtkMenuButtonSetLabel(mb: COpaquePointer, label: String) = memScoped {
        fnGtkMenuButtonSetLabel?.invoke(mb, label.cstr.ptr)
    }

    fun gtkMenuButtonSetChild(mb: COpaquePointer, child: COpaquePointer?) {
        fnGtkMenuButtonSetChild?.invoke(mb, child)
    }

    fun gtkMenuButtonPopup(mb: COpaquePointer) {
        fnGtkMenuButtonPopup?.invoke(mb)
    }

    fun gtkMenuButtonPopdown(mb: COpaquePointer) {
        fnGtkMenuButtonPopdown?.invoke(mb)
    }

    fun gtkPopoverNew(): COpaquePointer? = fnGtkPopoverNew?.invoke()

    fun gtkPopoverSetChild(popover: COpaquePointer, child: COpaquePointer?) {
        fnGtkPopoverSetChild?.invoke(popover, child)
    }

    fun gtkPopoverSetPointingTo(popover: COpaquePointer, x: Int, y: Int, width: Int, height: Int) = memScoped {
        val rect = allocArray<IntVar>(4)
        rect[0] = x
        rect[1] = y
        rect[2] = width.coerceAtLeast(1)
        rect[3] = height.coerceAtLeast(1)
        fnGtkPopoverSetPointingTo?.invoke(popover, rect)
    }

    fun gtkPopoverPopup(popover: COpaquePointer) {
        fnGtkPopoverPopup?.invoke(popover)
    }

    fun gtkPopoverPopdown(popover: COpaquePointer) {
        fnGtkPopoverPopdown?.invoke(popover)
    }

    fun gtkPopoverSetPosition(popover: COpaquePointer, pos: Int) {
        fnGtkPopoverSetPosition?.invoke(popover, pos)
    }

    fun gtkPopoverSetAutohide(popover: COpaquePointer, autohide: Boolean) {
        fnGtkPopoverSetAutohide?.invoke(popover, if (autohide) 1 else 0)
    }

    fun gtkPopoverSetHasArrow(popover: COpaquePointer, hasArrow: Boolean) {
        fnGtkPopoverSetHasArrow?.invoke(popover, if (hasArrow) 1 else 0)
    }

    fun gtkPopoverMenuNewFromModel(model: COpaquePointer?): COpaquePointer? =
        fnGtkPopoverMenuNewFromModel?.invoke(model)

    fun gtkPopoverMenuSetMenuModel(pm: COpaquePointer, model: COpaquePointer?) {
        fnGtkPopoverMenuSetMenuModel?.invoke(pm, model)
    }

    fun gtkPopoverMenuBarNewFromModel(model: COpaquePointer?): COpaquePointer? =
        fnGtkPopoverMenuBarNewFromModel?.invoke(model)

    fun gtkPopoverMenuBarSetMenuModel(pmb: COpaquePointer, model: COpaquePointer?) {
        fnGtkPopoverMenuBarSetMenuModel?.invoke(pmb, model)
    }

    fun gtkButtonNew(): COpaquePointer? = fnGtkButtonNew?.invoke()

    fun gtkButtonSetLabel(btn: COpaquePointer, label: String) = memScoped {
        fnGtkButtonSetLabel?.invoke(btn, label.cstr.ptr)
    }

    fun gtkButtonSetChild(btn: COpaquePointer, child: COpaquePointer?) {
        fnGtkButtonSetChild?.invoke(btn, child)
    }

    fun gtkButtonGetChild(btn: COpaquePointer): COpaquePointer? = fnGtkButtonGetChild?.invoke(btn)

    fun gtkPasswordEntryNew(): COpaquePointer? = fnGtkPasswordEntryNew?.invoke()

    fun gtkPasswordEntrySetShowPeekIcon(entry: COpaquePointer, show: Boolean) {
        fnGtkPasswordEntrySetShowPeekIcon?.invoke(entry, if (show) 1 else 0)
    }

    fun gtkSearchEntryNew(): COpaquePointer? = fnGtkSearchEntryNew?.invoke()

    fun gtkEntrySetPlaceholderText(entry: COpaquePointer, placeholder: String?) = memScoped {
        fnGtkEntrySetPlaceholderText?.invoke(entry, placeholder?.cstr?.ptr)
    }

    fun gtkEntrySetVisibility(entry: COpaquePointer, visible: Boolean) {
        fnGtkEntrySetVisibility?.invoke(entry, if (visible) 1 else 0)
    }

    fun gtkEditableSetEditable(editable: COpaquePointer, isEditable: Boolean) {
        fnGtkEditableSetEditable?.invoke(editable, if (isEditable) 1 else 0)
    }

    fun gtkSpinButtonNewWithRange(min: Double, max: Double, step: Double): COpaquePointer? =
        fnGtkSpinButtonNewWithRange?.invoke(min, max, step)

    fun gtkSpinButtonSetValue(sb: COpaquePointer, value: Double) {
        fnGtkSpinButtonSetValue?.invoke(sb, value)
    }

    fun gtkSpinButtonGetValue(sb: COpaquePointer): Double = fnGtkSpinButtonGetValue?.invoke(sb) ?: 0.0

    fun gtkSpinButtonSetRange(sb: COpaquePointer, min: Double, max: Double) {
        fnGtkSpinButtonSetRange?.invoke(sb, min, max)
    }

    fun gtkSpinButtonSetIncrements(sb: COpaquePointer, step: Double, page: Double) {
        fnGtkSpinButtonSetIncrements?.invoke(sb, step, page)
    }

    fun gtkSpinButtonSetDigits(sb: COpaquePointer, digits: Int) {
        fnGtkSpinButtonSetDigits?.invoke(sb, digits.coerceAtLeast(0).toUInt())
    }

    fun gtkTextViewNew(): COpaquePointer? = fnGtkTextViewNew?.invoke()

    fun gtkTextViewGetBuffer(tv: COpaquePointer): COpaquePointer? = fnGtkTextViewGetBuffer?.invoke(tv)

    fun gtkTextViewSetEditable(tv: COpaquePointer, editable: Boolean) {
        fnGtkTextViewSetEditable?.invoke(tv, if (editable) 1 else 0)
    }

    fun gtkTextViewSetWrapMode(tv: COpaquePointer, wrapMode: Int) {
        fnGtkTextViewSetWrapMode?.invoke(tv, wrapMode)
    }

    fun gtkTextViewSetMonospace(tv: COpaquePointer, monospace: Boolean) {
        fnGtkTextViewSetMonospace?.invoke(tv, if (monospace) 1 else 0)
    }

    fun gtkTextBufferSetText(buf: COpaquePointer, text: String) = memScoped {
        fnGtkTextBufferSetText?.invoke(buf, text.cstr.ptr, -1)
    }

    fun gtkTextBufferGetText(buf: COpaquePointer): String = memScoped {
        val getStart = fnGtkTextBufferGetStartIter ?: return@memScoped ""
        val getEnd = fnGtkTextBufferGetEndIter ?: return@memScoped ""
        val getText = fnGtkTextBufferGetText ?: return@memScoped ""
        val startIter = allocArray<ByteVar>(128)
        val endIter = allocArray<ByteVar>(128)
        getStart.invoke(buf, startIter)
        getEnd.invoke(buf, endIter)
        val raw = getText.invoke(buf, startIter, endIter, 1) ?: return@memScoped ""
        val str = raw.toKString()
        fnGFree?.invoke(raw)
        str
    }

    fun gtkPictureNewForPaintable(paintable: COpaquePointer?): COpaquePointer? =
        fnGtkPictureNewForPaintable?.invoke(paintable)

    fun gtkPictureSetPaintable(pic: COpaquePointer, paintable: COpaquePointer?) {
        fnGtkPictureSetPaintable?.invoke(pic, paintable)
    }

    fun gtkPictureSetContentFit(pic: COpaquePointer, fit: Int) {
        fnGtkPictureSetContentFit?.invoke(pic, fit)
    }

    fun gtkPictureSetCanShrink(pic: COpaquePointer, canShrink: Boolean) {
        fnGtkPictureSetCanShrink?.invoke(pic, if (canShrink) 1 else 0)
    }

    fun gdkMemoryTextureNewBgraPremul(width: Int, height: Int, premulBgra: ByteArray): COpaquePointer? {
        if (width <= 0 || height <= 0) return null
        val fnNew = fnGdkMemoryTextureNew ?: return null
        val gbytes = LinuxGlibGioBindings.bytesNew(premulBgra) ?: return null
        return try {
            fnNew.invoke(width, height, GDK_MEMORY_B8G8R8A8_PREMUL, gbytes, (width * 4).convert())
        } finally {
            LinuxGlibGioBindings.bytesUnref(gbytes)
        }
    }

    fun gtkSeparatorNew(orientation: Int): COpaquePointer? = fnGtkSeparatorNew?.invoke(orientation)

    fun gtkColumnViewNew(selectionModel: COpaquePointer?): COpaquePointer? = fnGtkColumnViewNew?.invoke(selectionModel)

    fun gtkColumnViewSetModel(cv: COpaquePointer, selectionModel: COpaquePointer?) {
        fnGtkColumnViewSetModel?.invoke(cv, selectionModel)
    }

    fun gtkColumnViewAppendColumn(cv: COpaquePointer, col: COpaquePointer) {
        fnGtkColumnViewAppendColumn?.invoke(cv, col)
    }

    fun gtkColumnViewRemoveColumn(cv: COpaquePointer, col: COpaquePointer) {
        fnGtkColumnViewRemoveColumn?.invoke(cv, col)
    }

    fun gtkColumnViewColumnNew(title: String?, factory: COpaquePointer? = null): COpaquePointer? = memScoped {
        fnGtkColumnViewColumnNew?.invoke(title?.cstr?.ptr, factory)
    }

    fun gtkColumnViewColumnSetTitle(col: COpaquePointer, title: String) = memScoped {
        fnGtkColumnViewColumnSetTitle?.invoke(col, title.cstr.ptr)
    }

    fun gtkColumnViewColumnSetExpand(col: COpaquePointer, expand: Boolean) {
        fnGtkColumnViewColumnSetExpand?.invoke(col, if (expand) 1 else 0)
    }

    fun gtkColumnViewColumnSetFixedWidth(col: COpaquePointer, width: Int) {
        fnGtkColumnViewColumnSetFixedWidth?.invoke(col, width)
    }

    fun gtkColumnViewColumnSetResizable(col: COpaquePointer, resizable: Boolean) {
        fnGtkColumnViewColumnSetResizable?.invoke(col, if (resizable) 1 else 0)
    }

    fun gtkStringListNew(items: List<String>): COpaquePointer? = memScoped {
        val ptrs = allocArray<CPointerVar<ByteVar>>(items.size + 1)
        for (i in items.indices) {
            ptrs[i] = items[i].cstr.ptr
        }
        ptrs[items.size] = null
        fnGtkStringListNew?.invoke(ptrs)
    }

    fun gtkSignalListItemFactoryNew(): COpaquePointer? = fnGtkSignalListItemFactoryNew?.invoke()

    fun gtkListItemSetChild(listItem: COpaquePointer, child: COpaquePointer?) {
        fnGtkListItemSetChild?.invoke(listItem, child)
    }

    fun gtkListItemGetChild(listItem: COpaquePointer): COpaquePointer? =
        fnGtkListItemGetChild?.invoke(listItem)

    fun gtkListItemGetItem(listItem: COpaquePointer): COpaquePointer? =
        fnGtkListItemGetItem?.invoke(listItem)

    fun gtkStringObjectGetString(stringObj: COpaquePointer): String? =
        fnGtkStringObjectGetString?.invoke(stringObj)?.toKString()

    fun gtkSingleSelectionNew(model: COpaquePointer?): COpaquePointer? = fnGtkSingleSelectionNew?.invoke(model)

    fun gtkNoSelectionNew(model: COpaquePointer?): COpaquePointer? = fnGtkNoSelectionNew?.invoke(model)

    fun gtkDropDownSetModel(dd: COpaquePointer, model: COpaquePointer?) {
        fnGtkDropDownSetModel?.invoke(dd, model)
    }

    fun gtkDropDownSetEnableSearch(dd: COpaquePointer, enable: Boolean) {
        fnGtkDropDownSetEnableSearch?.invoke(dd, if (enable) 1 else 0)
    }

    fun gtkListBoxNew(): COpaquePointer? = fnGtkListBoxNew?.invoke()

    fun gtkListBoxAppend(lb: COpaquePointer, child: COpaquePointer) {
        fnGtkListBoxAppend?.invoke(lb, child)
    }

    fun gtkListBoxInsert(lb: COpaquePointer, child: COpaquePointer, position: Int) {
        fnGtkListBoxInsert?.invoke(lb, child, position)
    }

    fun gtkListBoxRemove(lb: COpaquePointer, child: COpaquePointer) {
        fnGtkListBoxRemove?.invoke(lb, child)
    }

    fun gtkListBoxSelectRow(lb: COpaquePointer, row: COpaquePointer?) {
        fnGtkListBoxSelectRow?.invoke(lb, row)
    }

    fun gtkListBoxGetRowAtIndex(lb: COpaquePointer, index: Int): COpaquePointer? =
        fnGtkListBoxGetRowAtIndex?.invoke(lb, index)

    fun gtkListBoxSetSelectionMode(lb: COpaquePointer, mode: Int) {
        fnGtkListBoxSetSelectionMode?.invoke(lb, mode)
    }

    fun gtkListBoxRowNew(): COpaquePointer? = fnGtkListBoxRowNew?.invoke()

    fun gtkListBoxRowSetChild(row: COpaquePointer, child: COpaquePointer?) {
        fnGtkListBoxRowSetChild?.invoke(row, child)
    }

    fun gtkExpanderNew(label: String? = null): COpaquePointer? = memScoped {
        fnGtkExpanderNew?.invoke(label?.cstr?.ptr)
    }

    fun gtkExpanderSetChild(exp: COpaquePointer, child: COpaquePointer?) {
        fnGtkExpanderSetChild?.invoke(exp, child)
    }

    fun gtkExpanderSetExpanded(exp: COpaquePointer, expanded: Boolean) {
        fnGtkExpanderSetExpanded?.invoke(exp, if (expanded) 1 else 0)
    }

    fun gtkExpanderGetExpanded(exp: COpaquePointer): Boolean = (fnGtkExpanderGetExpanded?.invoke(exp) ?: 0) != 0

    fun gtkExpanderSetLabelWidget(exp: COpaquePointer, widget: COpaquePointer?) {
        fnGtkExpanderSetLabelWidget?.invoke(exp, widget)
    }

    fun gtkCalendarNew(): COpaquePointer? = fnGtkCalendarNew?.invoke()

    fun gtkCalendarSelectYmd(cal: COpaquePointer, year: Int, month: Int, day: Int) {
        val dt = fnGDateTimeNewLocal?.invoke(year, month.coerceIn(1, 12), day.coerceIn(1, 31), 0, 0, 0.0) ?: return
        try {
            fnGtkCalendarSelectDay?.invoke(cal, dt)
        } finally {
            fnGDateTimeUnref?.invoke(dt)
        }
    }

    fun gtkCalendarGetYmd(cal: COpaquePointer): Triple<Int, Int, Int>? {
        val dt = fnGtkCalendarGetDate?.invoke(cal) ?: return null
        return try {
            val y = fnGDateTimeGetYear?.invoke(dt) ?: return null
            val m = fnGDateTimeGetMonth?.invoke(dt) ?: return null
            val d = fnGDateTimeGetDayOfMonth?.invoke(dt) ?: return null
            Triple(y, m, d)
        } finally {
            fnGDateTimeUnref?.invoke(dt)
        }
    }

    fun gtkColorDialogButtonNew(dialog: COpaquePointer?): COpaquePointer? =
        fnGtkColorDialogButtonNew?.invoke(dialog)

    fun gtkColorDialogButtonSetArgb(btn: COpaquePointer, argb: Int) = memScoped {
        val rgba = allocArray<FloatVar>(4)
        rgba[0] = ((argb ushr 16) and 0xFF) / 255f
        rgba[1] = ((argb ushr 8) and 0xFF) / 255f
        rgba[2] = (argb and 0xFF) / 255f
        rgba[3] = ((argb ushr 24) and 0xFF) / 255f
        fnGtkColorDialogButtonSetRgba?.invoke(btn, rgba)
    }

    fun gtkColorDialogButtonGetArgb(btn: COpaquePointer): Int? {
        val rgba = fnGtkColorDialogButtonGetRgba?.invoke(btn) ?: return null
        val r = (rgba[0].coerceIn(0f, 1f) * 255f + 0.5f).toInt() and 0xFF
        val g = (rgba[1].coerceIn(0f, 1f) * 255f + 0.5f).toInt() and 0xFF
        val b = (rgba[2].coerceIn(0f, 1f) * 255f + 0.5f).toInt() and 0xFF
        val a = (rgba[3].coerceIn(0f, 1f) * 255f + 0.5f).toInt() and 0xFF
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun gtkCheckButtonNew(label: String? = null): COpaquePointer? = fnGtkCheckButtonNew?.invoke()?.also { cb ->
        if (label != null) gtkCheckButtonSetLabel(cb, label)
    }

    fun gtkCheckButtonSetActive(cb: COpaquePointer, active: Boolean) {
        fnGtkCheckButtonSetActive?.invoke(cb, if (active) 1 else 0)
    }

    fun gtkCheckButtonGetActive(cb: COpaquePointer): Boolean =
        (fnGtkCheckButtonGetActive?.invoke(cb) ?: 0) != 0

    fun gtkCheckButtonSetInconsistent(cb: COpaquePointer, inconsistent: Boolean) {
        fnGtkCheckButtonSetInconsistent?.invoke(cb, if (inconsistent) 1 else 0)
    }

    fun gtkCheckButtonSetGroup(cb: COpaquePointer, groupLeader: COpaquePointer?) {
        fnGtkCheckButtonSetGroup?.invoke(cb, groupLeader)
    }

    fun gtkCheckButtonSetLabel(cb: COpaquePointer, label: String) = memScoped {
        fnGtkCheckButtonSetLabel?.invoke(cb, label.cstr.ptr)
    }

    fun gtkSwitchNew(): COpaquePointer? = fnGtkSwitchNew?.invoke()

    fun gtkSwitchSetActive(sw: COpaquePointer, active: Boolean) {
        fnGtkSwitchSetActive?.invoke(sw, if (active) 1 else 0)
    }

    fun gtkSwitchGetActive(sw: COpaquePointer): Boolean = (fnGtkSwitchGetActive?.invoke(sw) ?: 0) != 0

    fun gtkScaleNewWithRange(orientation: Int, min: Double, max: Double, step: Double): COpaquePointer? =
        fnGtkScaleNewWithRange?.invoke(orientation, min, max, step)

    fun gtkRangeSetValue(range: COpaquePointer, value: Double) {
        fnGtkRangeSetValue?.invoke(range, value)
    }

    fun gtkRangeGetValue(range: COpaquePointer): Double = fnGtkRangeGetValue?.invoke(range) ?: 0.0

    fun gtkRangeSetRange(range: COpaquePointer, min: Double, max: Double) {
        fnGtkRangeSetRange?.invoke(range, min, max)
    }

    fun gtkProgressBarNew(): COpaquePointer? = fnGtkProgressBarNew?.invoke()

    fun gtkProgressBarSetFraction(pb: COpaquePointer, fraction: Double) {
        fnGtkProgressBarSetFraction?.invoke(pb, fraction.coerceIn(0.0, 1.0))
    }

    fun gtkProgressBarPulse(pb: COpaquePointer) {
        fnGtkProgressBarPulse?.invoke(pb)
    }

    fun gtkProgressBarSetText(pb: COpaquePointer, text: String?) = memScoped {
        fnGtkProgressBarSetText?.invoke(pb, text?.cstr?.ptr)
    }

    fun gtkProgressBarSetShowText(pb: COpaquePointer, show: Boolean) {
        fnGtkProgressBarSetShowText?.invoke(pb, if (show) 1 else 0)
    }

    fun gtkSpinnerOrAdwSpinnerNew(): Pair<COpaquePointer?, Boolean> {
        val adw = fnAdwSpinnerNew?.invoke()
        if (adw != null) return adw to true
        return fnGtkSpinnerNew?.invoke() to false
    }

    fun gtkSpinnerSetSpinning(sp: COpaquePointer, spinning: Boolean) {
        if (spinning) fnGtkSpinnerStart?.invoke(sp) else fnGtkSpinnerStop?.invoke(sp)
        fnGtkSpinnerSetSpinning?.invoke(sp, if (spinning) 1 else 0)
    }

    fun gtkLabelSetText(label: COpaquePointer, text: String) = memScoped {
        fnGtkLabelSetText?.invoke(label, text.cstr.ptr)
    }

    fun gtkLabelSetMarkup(label: COpaquePointer, markup: String) = memScoped {
        fnGtkLabelSetMarkup?.invoke(label, markup.cstr.ptr)
    }

    fun gtkLabelSetXalign(label: COpaquePointer, xalign: Float) {
        fnGtkLabelSetXalign?.invoke(label, xalign)
    }

    fun gtkLabelSetYalign(label: COpaquePointer, yalign: Float) {
        fnGtkLabelSetYalign?.invoke(label, yalign)
    }

    fun gtkLabelSetWrapMode(label: COpaquePointer, mode: Int) {
        fnGtkLabelSetWrapMode?.invoke(label, mode)
    }

    fun gtkLabelSetEllipsize(label: COpaquePointer, mode: Int) {
        fnGtkLabelSetEllipsize?.invoke(label, mode)
    }

    fun gtkLabelSetSelectable(label: COpaquePointer, selectable: Boolean) {
        fnGtkLabelSetSelectable?.invoke(label, if (selectable) 1 else 0)
    }

    fun gtkGestureSingleGetCurrentButton(gesture: COpaquePointer): UInt =
        fnGtkGestureSingleGetCurrentButton?.invoke(gesture) ?: 0u

    fun gtkEventControllerFocusNew(): COpaquePointer? = fnGtkEventControllerFocusNew?.invoke()

    fun gtkEventControllerFocusContainsFocus(ctrl: COpaquePointer): Boolean =
        (fnGtkEventControllerFocusContainsFocus?.invoke(ctrl) ?: 0) != 0

    fun gtkEventControllerFocusIsFocus(ctrl: COpaquePointer): Boolean =
        (fnGtkEventControllerFocusIsFocus?.invoke(ctrl) ?: 0) != 0

    fun gtkWidgetInsertActionGroup(widget: COpaquePointer, name: String, group: COpaquePointer?) = memScoped {
        fnGtkWidgetInsertActionGroup?.invoke(widget, name.cstr.ptr, group)
    }

    fun gMenuNew(): COpaquePointer? = fnGMenuNew?.invoke()

    fun gMenuAppend(menu: COpaquePointer, label: String?, detailedAction: String?) = memScoped {
        fnGMenuAppend?.invoke(menu, label?.cstr?.ptr, detailedAction?.cstr?.ptr)
    }

    fun gMenuAppendSubmenu(menu: COpaquePointer, label: String?, submenu: COpaquePointer) = memScoped {
        fnGMenuAppendSubmenu?.invoke(menu, label?.cstr?.ptr, submenu)
    }

    fun gMenuRemoveAll(menu: COpaquePointer) {
        fnGMenuRemoveAll?.invoke(menu)
    }

    fun gSimpleActionGroupNew(): COpaquePointer? = fnGSimpleActionGroupNew?.invoke()

    fun gSimpleActionNew(name: String): COpaquePointer? = memScoped {
        fnGSimpleActionNew?.invoke(name.cstr.ptr, null)
    }

    fun gActionMapAddAction(actionMap: COpaquePointer, action: COpaquePointer) {
        fnGActionMapAddAction?.invoke(actionMap, action)
    }

    fun gQuarkFromString(name: String): UInt = memScoped {
        fnGQuarkFromString?.invoke(name.cstr.ptr) ?: 0u
    }

    fun gObjectSetQdataFull(obj: COpaquePointer, quark: UInt, data: COpaquePointer?, destroy: COpaquePointer?) {
        fnGObjectSetQdataFull?.invoke(obj, quark, data, destroy)
    }

    fun gObjectGetQdata(obj: COpaquePointer, quark: UInt): COpaquePointer? =
        fnGObjectGetQdata?.invoke(obj, quark)

    fun gskTransformTranslate(next: COpaquePointer?, x: Float, y: Float): COpaquePointer? = memScoped {
        val pt = allocArray<FloatVar>(2)
        pt[0] = x
        pt[1] = y
        fnGskTransformTranslate?.invoke(next, pt)
    }

    fun gskTransformRotate(next: COpaquePointer?, angleDeg: Float): COpaquePointer? =
        fnGskTransformRotate?.invoke(next, angleDeg)

    fun gskTransformScale(next: COpaquePointer?, sx: Float, sy: Float): COpaquePointer? =
        fnGskTransformScale?.invoke(next, sx, sy)

    fun gskTransformSkew(next: COpaquePointer?, skewX: Float, skewY: Float): COpaquePointer? =
        fnGskTransformSkew?.invoke(next, skewX, skewY)

    fun gskTransformUnref(transform: COpaquePointer?) {
        if (transform != null) fnGskTransformUnref?.invoke(transform)
    }

    fun gtkSnapshotNew(): COpaquePointer? = fnGtkSnapshotNew?.invoke()

    fun gtkSnapshotFreeToNode(snapshot: COpaquePointer): COpaquePointer? = fnGtkSnapshotFreeToNode?.invoke(snapshot)

    fun gtkSnapshotPushBlur(snapshot: COpaquePointer, radiusPx: Double) {
        fnGtkSnapshotPushBlur?.invoke(snapshot, radiusPx)
    }

    fun gtkSnapshotPushOpacity(snapshot: COpaquePointer, opacity: Double) {
        fnGtkSnapshotPushOpacity?.invoke(snapshot, opacity)
    }

    fun gtkSnapshotPushRoundedClip(
        snapshot: COpaquePointer,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        tl: Float,
        tr: Float,
        br: Float,
        bl: Float
    ) = memScoped {
        val rect = allocArray<FloatVar>(12)
        rect[0] = x; rect[1] = y; rect[2] = width; rect[3] = height
        rect[4] = tl; rect[5] = tl
        rect[6] = tr; rect[7] = tr
        rect[8] = br; rect[9] = br
        rect[10] = bl; rect[11] = bl
        fnGtkSnapshotPushRoundedClip?.invoke(snapshot, rect)
    }

    fun gtkSnapshotPushColorMatrix(snapshot: COpaquePointer, matrix4x5: FloatArray): Boolean {
        if (matrix4x5.size < 20) return false
        val fnPush = fnGtkSnapshotPushColorMatrix ?: return false
        val matMem = LinuxGlibGioBindings.malloc0(64uL) ?: return false
        val vecMem = LinuxGlibGioBindings.malloc0(16uL)
        if (vecMem == null) {
            LinuxGlibGioBindings.free(matMem)
            return false
        }
        return try {
            memScoped {
                val colMajor = allocArray<FloatVar>(16)
                for (col in 0 until 4) {
                    for (row in 0 until 4) {
                        colMajor[col * 4 + row] = matrix4x5[row * 5 + col]
                    }
                }
                val fnMat = fnGrapheneMatrixInitFromFloat
                if (fnMat != null) {
                    fnMat.invoke(matMem, colMajor)
                } else {
                    val dst = matMem.reinterpret<FloatVar>()
                    for (i in 0 until 16) dst[i] = colMajor[i]
                }
                val fnVec = fnGrapheneVec4Init
                if (fnVec != null) {
                    fnVec.invoke(vecMem, matrix4x5[4], matrix4x5[9], matrix4x5[14], matrix4x5[19])
                } else {
                    val dst = vecMem.reinterpret<FloatVar>()
                    dst[0] = matrix4x5[4]
                    dst[1] = matrix4x5[9]
                    dst[2] = matrix4x5[14]
                    dst[3] = matrix4x5[19]
                }
            }
            fnPush.invoke(snapshot, matMem, vecMem)
            true
        } finally {
            LinuxGlibGioBindings.free(matMem)
            LinuxGlibGioBindings.free(vecMem)
        }
    }

    fun gtkSnapshotPop(snapshot: COpaquePointer) {
        fnGtkSnapshotPop?.invoke(snapshot)
    }

    fun gtkSnapshotAppendCairo(snapshot: COpaquePointer, x: Float, y: Float, w: Float, h: Float): COpaquePointer? = memScoped {
        val rect = allocArray<FloatVar>(4)
        rect[0] = x; rect[1] = y; rect[2] = w; rect[3] = h
        fnGtkSnapshotAppendCairo?.invoke(snapshot, rect)
    }

    fun gskRenderNodeDraw(node: COpaquePointer, cr: COpaquePointer) {
        fnGskRenderNodeDraw?.invoke(node, cr)
    }

    fun gskRenderNodeUnref(node: COpaquePointer?) {
        if (node != null) fnGskRenderNodeUnref?.invoke(node)
    }

    fun gtkWidgetPaintableNew(widget: COpaquePointer): COpaquePointer? = fnGtkWidgetPaintableNew?.invoke(widget)

    fun gdkPaintableSnapshot(paintable: COpaquePointer, snapshot: COpaquePointer, width: Double, height: Double) {
        fnGdkPaintableSnapshot?.invoke(paintable, snapshot, width, height)
    }

    fun gskRendererNewForSurface(surface: COpaquePointer?): COpaquePointer? =
        fnGskRendererNewForSurface?.invoke(surface)

    fun gskCairoRendererNew(): COpaquePointer? = fnGskCairoRendererNew?.invoke()

    fun gskRendererRealizeForDisplay(renderer: COpaquePointer, display: COpaquePointer): Boolean = memScoped {
        val err = alloc<COpaquePointerVar>()
        val ok = (fnGskRendererRealizeForDisplay?.invoke(renderer, display, err.ptr) ?: 0) != 0
        if (err.value != null) LinuxGlibGioBindings.readAndFreeError(err.ptr)
        ok
    }

    fun gskRendererRealize(renderer: COpaquePointer, surface: COpaquePointer?): Boolean = memScoped {
        val err = alloc<COpaquePointerVar>()
        val ok = (fnGskRendererRealize?.invoke(renderer, surface, err.ptr) ?: 0) != 0
        if (err.value != null) LinuxGlibGioBindings.readAndFreeError(err.ptr)
        ok
    }

    fun gskRendererUnrealize(renderer: COpaquePointer) {
        fnGskRendererUnrealize?.invoke(renderer)
    }

    fun gskRendererRenderTexture(
        renderer: COpaquePointer,
        rootNode: COpaquePointer,
        x: Float,
        y: Float,
        width: Float,
        height: Float
    ): COpaquePointer? = memScoped {
        val vp = allocArray<FloatVar>(4)
        vp[0] = x; vp[1] = y; vp[2] = width; vp[3] = height
        fnGskRendererRenderTexture?.invoke(renderer, rootNode, vp)
    }

    fun gdkTextureDownloadBgraPremul(texture: COpaquePointer, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height * 4)
        val fn = fnGdkTextureDownload ?: return out
        out.usePinned { pinned ->
            fn.invoke(texture, pinned.addressOf(0), (width * 4).convert())
        }
        return out
    }

    fun cairoImageSurfaceCreate(width: Int, height: Int): COpaquePointer? =
        fnCairoImageSurfaceCreate?.invoke(CAIRO_FORMAT_ARGB32, width.coerceAtLeast(1), height.coerceAtLeast(1))

    fun cairoCreate(surface: COpaquePointer): COpaquePointer? = fnCairoCreate?.invoke(surface)

    fun cairoDestroy(cr: COpaquePointer?) {
        if (cr != null) fnCairoDestroy?.invoke(cr)
    }

    fun cairoSurfaceDestroy(surface: COpaquePointer?) {
        if (surface != null) fnCairoSurfaceDestroy?.invoke(surface)
    }

    fun cairoSurfaceFlush(surface: COpaquePointer) {
        fnCairoSurfaceFlush?.invoke(surface)
    }

    fun cairoSurfaceMarkDirty(surface: COpaquePointer) {
        fnCairoSurfaceMarkDirty?.invoke(surface)
    }

    fun cairoImageSurfaceGetData(surface: COpaquePointer): COpaquePointer? =
        fnCairoImageSurfaceGetData?.invoke(surface)

    fun cairoImageSurfaceGetWidth(surface: COpaquePointer): Int =
        fnCairoImageSurfaceGetWidth?.invoke(surface) ?: 0

    fun cairoImageSurfaceGetHeight(surface: COpaquePointer): Int =
        fnCairoImageSurfaceGetHeight?.invoke(surface) ?: 0

    fun cairoImageSurfaceGetStride(surface: COpaquePointer): Int =
        fnCairoImageSurfaceGetStride?.invoke(surface) ?: 0

    fun cairoClipPreserve(cr: COpaquePointer) {
        fnCairoClipPreserve?.invoke(cr)
    }

    fun cairoSetDash(cr: COpaquePointer, dashes: DoubleArray, offset: Double = 0.0) = memScoped {
        if (dashes.isEmpty()) {
            fnCairoSetDash?.invoke(cr, null, 0, 0.0)
            return@memScoped
        }
        val arr = allocArray<DoubleVar>(dashes.size)
        for (i in dashes.indices) arr[i] = dashes[i]
        fnCairoSetDash?.invoke(cr, arr, dashes.size, offset)
    }

    fun cairoPatternCreateRadial(cx0: Double, cy0: Double, r0: Double, cx1: Double, cy1: Double, r1: Double): COpaquePointer? =
        fnCairoPatternCreateRadial?.invoke(cx0, cy0, r0, cx1, cy1, r1)

    fun cairoSetOperator(cr: COpaquePointer, op: Int) {
        fnCairoSetOperator?.invoke(cr, op)
    }

    fun cairoSetSourceSurface(cr: COpaquePointer, surface: COpaquePointer, x: Double, y: Double) {
        fnCairoSetSourceSurface?.invoke(cr, surface, x, y)
    }

    fun gtkWidgetSetClipPathForRoundedCorners(cr: COpaquePointer, x: Double, y: Double, w: Double, h: Double, tl: Double, tr: Double, br: Double, bl: Double) {
        cairoNewPath(cr)
        val rTl = min(tl, min(w * 0.5, h * 0.5)).coerceAtLeast(0.0)
        val rTr = min(tr, min(w * 0.5, h * 0.5)).coerceAtLeast(0.0)
        val rBr = min(br, min(w * 0.5, h * 0.5)).coerceAtLeast(0.0)
        val rBl = min(bl, min(w * 0.5, h * 0.5)).coerceAtLeast(0.0)
        val halfPi = PI * 0.5
        cairoNewSubPath(cr)
        if (rTr > 0.0) cairoArc(cr, x + w - rTr, y + rTr, rTr, -halfPi, 0.0) else cairoMoveTo(cr, x + w, y)
        if (rBr > 0.0) cairoArc(cr, x + w - rBr, y + h - rBr, rBr, 0.0, halfPi) else cairoLineTo(cr, x + w, y + h)
        if (rBl > 0.0) cairoArc(cr, x + rBl, y + h - rBl, rBl, halfPi, PI) else cairoLineTo(cr, x, y + h)
        if (rTl > 0.0) cairoArc(cr, x + rTl, y + rTl, rTl, PI, PI + halfPi) else cairoLineTo(cr, x, y)
        cairoClosePath(cr)
    }
}
