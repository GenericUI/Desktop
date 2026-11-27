@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.kde.abi.kde_invoke_qmetaobject_connect
import com.nexustools.genericui.kde.abi.kde_invoke_sret24_1
import com.nexustools.genericui.linux.LinuxDynamicLibraryLoader
import com.nexustools.genericui.linux.LinuxPosixPaths
import com.nexustools.genericui.linux.LinuxReentrantLock
import com.nexustools.genericui.linux.titlebar.LinuxIniAndXmlConfigParser
import com.nexustools.genericui.style.color.RgbaColor
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.calloc
import platform.posix.fprintf
import platform.posix.free
import platform.posix.getenv
import platform.posix.getuid
import platform.posix.memcpy
import platform.posix.mkdir
import platform.posix.setenv
import platform.posix.stderr

/**
 * Dynamic `dlopen`/`dlsym` bindings for core Qt 6 (`libQt6Core.so.6`, `libQt6Gui.so.6`,
 * `libQt6Widgets.so.6`) plus optional KDE Frameworks 6 (`KWidgetsAddons`, `KStatusNotifierItem`,
 * `KColorScheme`, `KConfigCore`, `KWindowSystem`) and Breeze style (`breeze6.so`).
 *
 * Core Qt 6 symbols are required for the KDE backend and fail fast if absent. Optional KF6/Breeze
 * libraries degrade cleanly once-logged to standard Qt 6 / Fusion primitives on stripped containers.
 */
internal object KdeDynamicBindings {

    // Qt::WidgetAttribute constants verified on Qt 6
    const val WA_DISABLED: Int = 0
    const val WA_NO_SYSTEM_BACKGROUND: Int = 9
    const val WA_TRANSPARENT_FOR_MOUSE_EVENTS: Int = 51
    const val WA_HOVER: Int = 74
    const val WA_STYLED_BACKGROUND: Int = 93
    const val WA_SHOW_WITHOUT_ACTIVATING: Int = 98
    const val WA_NATIVE_WINDOW: Int = 100
    const val WA_DONT_SHOW_ON_SCREEN: Int = 103
    const val WA_TRANSLUCENT_BACKGROUND: Int = 120

    // Qt::WindowType flags
    const val WINDOW_WIDGET: Int = 0x00000000
    const val WINDOW_WINDOW: Int = 0x00000001
    const val WINDOW_DIALOG: Int = 0x00000003
    const val WINDOW_POPUP: Int = 0x00000009
    const val WINDOW_TOOL: Int = 0x0000000b
    const val WINDOW_FRAMELESS_HINT: Int = 0x00000800
    const val WINDOW_STAYS_ON_TOP_HINT: Int = 0x00040000
    const val WINDOW_NO_DROP_SHADOW_HINT: Int = 0x40000000

    // Qt::WindowState flags
    const val WINDOW_NO_STATE: Int = 0x00
    const val WINDOW_MINIMIZED: Int = 0x01
    const val WINDOW_MAXIMIZED: Int = 0x02
    const val WINDOW_FULLSCREEN: Int = 0x04
    const val WINDOW_ACTIVE: Int = 0x08

    // Qt::FocusPolicy
    const val FOCUS_NO_FOCUS: Int = 0
    const val FOCUS_TAB_FOCUS: Int = 1
    const val FOCUS_CLICK_FOCUS: Int = 2
    const val FOCUS_STRONG_FOCUS: Int = 11

    // Qt::Orientation
    const val HORIZONTAL: Int = 1
    const val VERTICAL: Int = 2

    // Qt::PenStyle / BrushStyle / ClipOperation / FillRule / RenderHint / LineEdit EchoMode
    const val PEN_NO_PEN: Int = 0
    const val PEN_SOLID_LINE: Int = 1
    const val PEN_DASH_LINE: Int = 2
    const val PEN_DOT_LINE: Int = 3
    const val BRUSH_SOLID_PATTERN: Int = 1
    const val CLIP_NO_CLIP: Int = 0
    const val CLIP_REPLACE: Int = 1
    const val CLIP_INTERSECT: Int = 2
    const val FILL_WINDING: Int = 1
    const val RENDER_ANTIALIASING: Int = 0x01
    const val RENDER_TEXT_ANTIALIASING: Int = 0x02
    const val RENDER_SMOOTH_PIXMAP_TRANSFORM: Int = 0x04

    // Qt::Alignment
    const val ALIGN_LEFT: Int = 0x0001
    const val ALIGN_RIGHT: Int = 0x0002
    const val ALIGN_HCENTER: Int = 0x0004
    const val ALIGN_TOP: Int = 0x0020
    const val ALIGN_BOTTOM: Int = 0x0040
    const val ALIGN_VCENTER: Int = 0x0080
    const val ALIGN_CENTER: Int = ALIGN_HCENTER or ALIGN_VCENTER

    // Qt::Aspect / Widget RenderFlag / QImage Format
    const val RENDER_DRAW_WINDOW_BACKGROUND: Int = 0x1
    const val RENDER_DRAW_CHILDREN: Int = 0x2
    const val IMAGE_FORMAT_ARGB32_PREMULTIPLIED: Int = 6
    const val IMAGE_FORMAT_RGBA8888: Int = 17

    // QPaintDevice::PaintDeviceMetric
    const val PDM_WIDTH: Int = 1
    const val PDM_HEIGHT: Int = 2
    const val PDM_DEVICE_PIXEL_RATIO: Int = 11

    // QToolButton::ToolButtonPopupMode
    const val POPUP_DELAYED: Int = 0
    const val POPUP_MENU_BUTTON: Int = 1
    const val POPUP_INSTANT: Int = 2

    // QTabWidget::TabPosition & QTabBar::Shape
    const val TAB_NORTH: Int = 0
    const val TAB_SOUTH: Int = 1
    const val TAB_WEST: Int = 2
    const val TAB_EAST: Int = 3

    // QFrame::Shape & Shadow
    const val FRAME_NO_FRAME: Int = 0
    const val FRAME_BOX: Int = 1
    const val FRAME_PANEL: Int = 2
    const val FRAME_HLINE: Int = 4
    const val FRAME_VLINE: Int = 5
    const val FRAME_STYLED_PANEL: Int = 6
    const val FRAME_PLAIN: Int = 0x0010

    // ScrollBarPolicy / CursorShape
    const val SCROLLBAR_AS_NEEDED: Int = 0
    const val SCROLLBAR_ALWAYS_OFF: Int = 1
    const val SCROLLBAR_ALWAYS_ON: Int = 2

    // QLineEdit::EchoMode
    const val ECHO_NORMAL: Int = 0
    const val ECHO_PASSWORD: Int = 2

    // QHeaderView::ResizeMode / QAbstractItemView
    const val HEADER_INTERACTIVE: Int = 0
    const val HEADER_STRETCH: Int = 1
    const val HEADER_FIXED: Int = 2
    const val HEADER_RESIZE_TO_CONTENTS: Int = 3

    // KMessageWidget::MessageType
    const val KMSG_POSITIVE: Int = 0
    const val KMSG_INFORMATION: Int = 1
    const val KMSG_WARNING: Int = 2
    const val KMSG_ERROR: Int = 3

    // Qt::CursorShape for resize edges
    const val CURSOR_ARROW: Int = 0
    const val CURSOR_SIZE_VER: Int = 5
    const val CURSOR_SIZE_HOR: Int = 6
    const val CURSOR_SIZE_BDIAG: Int = 7
    const val CURSOR_SIZE_FDIAG: Int = 8

    // KStatusNotifierItem
    const val KSNI_CAT_APP_STATUS: Int = 0
    const val KSNI_STATUS_PASSIVE: Int = 0
    const val KSNI_STATUS_ACTIVE: Int = 1

    // QWidget/QObject generous C++ heap size (>= 128 bytes as mandated)
    const val NATIVE_QOBJECT_ALLOC_BYTES: Int = 512
    private const val QT6_APPLICATION_FLAGS: Int = 0x060000

    private val lock = LinuxReentrantLock()
    private val loggedNotices = HashSet<String>()

    private var coreLoaded = false
    private var appCreated = false
    private var activeStyleName: String = "Fusion"
    private var qAppStorage: COpaquePointer? = null
    private var qAppArgcPtr: CPointer<IntVar>? = null
    private var qAppArgvPtr: CPointer<COpaquePointerVar>? = null
    private var qAppArg0Ptr: CPointer<ByteVar>? = null

    private var hCore: COpaquePointer? = null
    private var hGui: COpaquePointer? = null
    private var hWidgets: COpaquePointer? = null
    private var hKfWidgets: COpaquePointer? = null
    private var hKfSni: COpaquePointer? = null
    private var hKfColorScheme: COpaquePointer? = null
    private var hKfConfigCore: COpaquePointer? = null
    private var hKfWindowSystem: COpaquePointer? = null
    private var hBreezeStyle: COpaquePointer? = null

    var kf6WidgetsAvailable: Boolean = false
        private set
    var kf6StatusNotifierAvailable: Boolean = false
        private set
    var kf6ColorSchemeAvailable: Boolean = false
        private set
    var kf6ConfigCoreAvailable: Boolean = false
        private set
    var kf6WindowSystemAvailable: Boolean = false
        private set
    var breezeStyleAvailable: Boolean = false
        private set

    // --- Core QString / QArrayData / QObject / QMetaObject / QTimer / QApplication ---
    private var fnQStringCtorUtf16: CPointer<CFunction<(COpaquePointer, COpaquePointer?, Long) -> Unit>>? = null
    private var fnQArrayDataDealloc: CPointer<CFunction<(COpaquePointer, Long, Long) -> Unit>>? = null

    var symQObjectD1: COpaquePointer? = null
        private set
    var symQObjectD0: COpaquePointer? = null
        private set
    var symQObjectQtMetacall: COpaquePointer? = null
        private set
    var symQObjectEvent: COpaquePointer? = null
        private set
    var symQWidgetQtMetacall: COpaquePointer? = null
        private set
    var symQWidgetD1: COpaquePointer? = null
        private set
    var symQWidgetD0: COpaquePointer? = null
        private set
    var symQWidgetDevType: COpaquePointer? = null
        private set
    var symQWidgetEvent: COpaquePointer? = null
        private set
    var symQWidgetPaintEvent: COpaquePointer? = null
        private set
    var symQWidgetResizeEvent: COpaquePointer? = null
        private set
    var symQWidgetSizeHint: COpaquePointer? = null
        private set
    var symQWidgetMinSizeHint: COpaquePointer? = null
        private set
    var symQWidgetMousePress: COpaquePointer? = null
        private set
    var symQWidgetMouseRelease: COpaquePointer? = null
        private set
    var symQWidgetMouseDoubleClick: COpaquePointer? = null
        private set
    var symQWidgetMouseMove: COpaquePointer? = null
        private set
    var symQWidgetWheel: COpaquePointer? = null
        private set
    var symQWidgetEnter: COpaquePointer? = null
        private set
    var symQWidgetLeave: COpaquePointer? = null
        private set
    var symQWidgetKeyPress: COpaquePointer? = null
        private set
    var symQWidgetKeyRelease: COpaquePointer? = null
        private set
    var symQWidgetFocusIn: COpaquePointer? = null
        private set
    var symQWidgetFocusOut: COpaquePointer? = null
        private set
    var symQWidgetClose: COpaquePointer? = null
        private set

    private var fnQObjectCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQMetaObjectConnect: COpaquePointer? = null
    private var fnQMetaObjectConnDtor: COpaquePointer? = null
    private var fnQMetaObjectIndexOfSignal: CPointer<CFunction<(COpaquePointer, CPointer<ByteVar>) -> Int>>? = null
    private var fnQMetaObjectNormalizeSig: COpaquePointer? = null

    private var fnQAppCtor: CPointer<CFunction<(COpaquePointer, CPointer<IntVar>, COpaquePointer, Int) -> Unit>>? = null
    private var fnQAppDtor: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQAppExec: CPointer<CFunction<() -> Int>>? = null
    private var fnQAppQuit: CPointer<CFunction<() -> Unit>>? = null
    private var fnQAppProcessEvents: CPointer<CFunction<(Int) -> Unit>>? = null
    private var fnQAppProcessEventsMax: CPointer<CFunction<(Int, Int) -> Unit>>? = null
    private var fnQAppSetStylePtr: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQAppSetPalette: CPointer<CFunction<(COpaquePointer, CPointer<ByteVar>?) -> Unit>>? = null
    private var fnQStyleFactoryCreate: CPointer<CFunction<(COpaquePointer) -> COpaquePointer?>>? = null

    private var fnQGuiPrimaryScreen: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnQScreenRefreshRate: CPointer<CFunction<(COpaquePointer) -> Double>>? = null
    private var fnQCorePostEvent: CPointer<CFunction<(COpaquePointer, COpaquePointer, Int) -> Unit>>? = null
    private var fnQEventCtor: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQGuiSetOverrideCursor: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQGuiRestoreOverrideCursor: CPointer<CFunction<() -> Unit>>? = null

    // --- QTimer ---
    private var fnQTimerCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQTimerStart: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQTimerStop: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQTimerSetInterval: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null

    // --- QWidget ---
    private var fnQWidgetCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?, Int) -> Unit>>? = null
    private var fnQWidgetSetParent: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQWidgetSetParentFlags: CPointer<CFunction<(COpaquePointer, COpaquePointer?, Int) -> Unit>>? = null
    private var fnQWidgetSetGeometryRect: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQWidgetMovePt: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQWidgetResizeSz: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQWidgetSetVisible: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnQWidgetIsVisible: CPointer<CFunction<(COpaquePointer) -> Byte>>? = null
    private var fnQWidgetSetEnabled: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnQWidgetIsEnabled: CPointer<CFunction<(COpaquePointer) -> Byte>>? = null
    private var fnQWidgetSetAttribute: CPointer<CFunction<(COpaquePointer, Int, Byte) -> Unit>>? = null
    private var fnQWidgetSetWindowFlags: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQWidgetSetWindowState: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQWidgetWindowState: CPointer<CFunction<(COpaquePointer) -> Int>>? = null
    private var fnQWidgetSetWindowTitle: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQWidgetSetWindowOpacity: CPointer<CFunction<(COpaquePointer, Double) -> Unit>>? = null
    private var fnQWidgetSetWindowIcon: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQWidgetSetToolTip: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQWidgetSetCursor: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQWidgetUnsetCursor: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQWidgetSetFocusReason: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQWidgetClearFocus: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQWidgetSetFocusPolicy: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQWidgetFocusPolicy: CPointer<CFunction<(COpaquePointer) -> Int>>? = null
    private var fnQWidgetHasFocus: CPointer<CFunction<(COpaquePointer) -> Byte>>? = null
    private var fnQWidgetSetMinSize: CPointer<CFunction<(COpaquePointer, Int, Int) -> Unit>>? = null
    private var fnQWidgetSetMaxSize: CPointer<CFunction<(COpaquePointer, Int, Int) -> Unit>>? = null
    private var fnQWidgetSetFixedWidth: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQWidgetSetFixedHeight: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQWidgetSetPalette: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQWidgetSetStyleSheet: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQWidgetSetAutoFillBackground: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnQWidgetSetFont: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQWidgetRaise: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQWidgetLower: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQWidgetStackUnder: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQWidgetUpdate: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQWidgetRepaint: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQWidgetShowMinimized: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQWidgetShowMaximized: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQWidgetShowFullScreen: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQWidgetShowNormal: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQWidgetActivateWindow: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQWidgetIsActiveWindow: CPointer<CFunction<(COpaquePointer) -> Byte>>? = null
    private var fnQWindowStartSystemMove: CPointer<CFunction<(COpaquePointer) -> Byte>>? = null
    private var fnQWidgetClose: CPointer<CFunction<(COpaquePointer) -> Byte>>? = null
    private var fnQWidgetWinId: CPointer<CFunction<(COpaquePointer) -> ULong>>? = null
    private var fnQWidgetEffectiveWinId: CPointer<CFunction<(COpaquePointer) -> ULong>>? = null
    private var fnQWidgetWindowHandle: CPointer<CFunction<(COpaquePointer) -> COpaquePointer?>>? = null
    private var fnQWidgetX: CPointer<CFunction<(COpaquePointer) -> Int>>? = null
    private var fnQWidgetY: CPointer<CFunction<(COpaquePointer) -> Int>>? = null
    private var fnQWidgetRender: CPointer<CFunction<(COpaquePointer, COpaquePointer, COpaquePointer, COpaquePointer, Int) -> Unit>>? = null
    private var fnQWidgetSetGraphicsEffect: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQWidgetPos: CPointer<CFunction<(COpaquePointer) -> Long>>? = null
    private var fnQWidgetMapToGlobalPt: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Long>>? = null
    private var fnQWidgetMapFromGlobalPt: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Long>>? = null
    private var fnQPaintDeviceMetric: CPointer<CFunction<(COpaquePointer, Int) -> Int>>? = null

    // --- QImage / QPixmap / QIcon / QCursor / QColor / QFont / QFontMetricsF / QPalette / QRegion ---
    private var fnQImageCtorEmpty: CPointer<CFunction<(COpaquePointer, Int, Int, Int) -> Unit>>? = null
    private var fnQImageCtorDataConst: CPointer<CFunction<(COpaquePointer, COpaquePointer, Int, Int, Long, Int, COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnQImageDtor: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQImageFillRgba: CPointer<CFunction<(COpaquePointer, UInt) -> Unit>>? = null
    private var fnQImageBits: CPointer<CFunction<(COpaquePointer) -> COpaquePointer?>>? = null
    private var fnQImageBytesPerLine: CPointer<CFunction<(COpaquePointer) -> Long>>? = null
    private var fnQImageLoadFromData: CPointer<CFunction<(COpaquePointer, COpaquePointer, Int, CPointer<ByteVar>?) -> Byte>>? = null
    private var fnQImageSave: CPointer<CFunction<(COpaquePointer, COpaquePointer, CPointer<ByteVar>?, Int) -> Byte>>? = null
    private var fnQPixmapCtorEmpty: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQPixmapDtor: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQPixmapConvertFromImage: CPointer<CFunction<(COpaquePointer, COpaquePointer, Int) -> Byte>>? = null
    private var fnQIconCtorEmpty: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQIconCtorPixmap: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQIconAddFile: CPointer<CFunction<(COpaquePointer, COpaquePointer, COpaquePointer, Int, Int) -> Unit>>? = null
    private var fnQIconDtor: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQCursorCtorShape: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQCursorDtor: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQCursorPos: CPointer<CFunction<() -> Long>>? = null

    private var fnQColorCtorRgba: CPointer<CFunction<(COpaquePointer, UInt) -> Unit>>? = null
    private var fnQColorSetRgb: CPointer<CFunction<(COpaquePointer, Int, Int, Int, Int) -> Unit>>? = null
    private var fnQColorRed: CPointer<CFunction<(COpaquePointer) -> Int>>? = null
    private var fnQColorGreen: CPointer<CFunction<(COpaquePointer) -> Int>>? = null
    private var fnQColorBlue: CPointer<CFunction<(COpaquePointer) -> Int>>? = null
    private var fnQColorAlpha: CPointer<CFunction<(COpaquePointer) -> Int>>? = null

    private var fnQFontCtor: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQFontDtor: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQFontSetFamily: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQFontSetPixelSize: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQFontSetPointSizeF: CPointer<CFunction<(COpaquePointer, Double) -> Unit>>? = null
    private var fnQFontSetWeight: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQFontSetStyle: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQFontMetricsFCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQFontMetricsFDtor: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQFontMetricsFHorizAdvance: CPointer<CFunction<(COpaquePointer, COpaquePointer, Int) -> Double>>? = null
    private var fnQFontMetricsFHeight: CPointer<CFunction<(COpaquePointer) -> Double>>? = null
    private var fnQFontMetricsFAscent: CPointer<CFunction<(COpaquePointer) -> Double>>? = null

    private var fnQPaletteCtor: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQPaletteDtor: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQPaletteSetBrush: CPointer<CFunction<(COpaquePointer, Int, Int, COpaquePointer) -> Unit>>? = null
    private var fnQRegionCtorRect: CPointer<CFunction<(COpaquePointer, Int, Int, Int, Int, Int) -> Unit>>? = null
    private var fnQRegionDtor: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null

    // --- QPainter / QPainterPath / QPen / QBrush / Gradients / QTransform ---
    private var fnQPainterCtor: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQPainterDtor: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQPainterBegin: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Byte>>? = null
    private var fnQPainterEnd: CPointer<CFunction<(COpaquePointer) -> Byte>>? = null
    private var fnQPainterSave: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQPainterRestore: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQPainterSetRenderHint: CPointer<CFunction<(COpaquePointer, Int, Byte) -> Unit>>? = null
    private var fnQPainterSetOpacity: CPointer<CFunction<(COpaquePointer, Double) -> Unit>>? = null
    private var fnQPainterSetFont: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQPainterSetBrushObj: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQPainterSetPenObj: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQPainterSetPenStyle: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQPainterSetBrushStyle: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQPainterSetClipPath: CPointer<CFunction<(COpaquePointer, COpaquePointer, Int) -> Unit>>? = null
    private var fnQPainterSetClipRect: CPointer<CFunction<(COpaquePointer, COpaquePointer, Int) -> Unit>>? = null
    private var fnQPainterFillPath: CPointer<CFunction<(COpaquePointer, COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQPainterStrokePath: CPointer<CFunction<(COpaquePointer, COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQPainterFillRectColor: CPointer<CFunction<(COpaquePointer, COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQPainterDrawRoundedRect: CPointer<CFunction<(COpaquePointer, COpaquePointer, Double, Double, Int) -> Unit>>? = null
    private var fnQPainterDrawRects: CPointer<CFunction<(COpaquePointer, COpaquePointer, Int) -> Unit>>? = null
    private var fnQPainterDrawLines: CPointer<CFunction<(COpaquePointer, COpaquePointer, Int) -> Unit>>? = null
    private var fnQPainterDrawEllipse: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQPainterDrawTextRect: CPointer<CFunction<(COpaquePointer, COpaquePointer, Int, COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQPainterDrawImageRects: CPointer<CFunction<(COpaquePointer, COpaquePointer, COpaquePointer, COpaquePointer, Int) -> Unit>>? = null
    private var fnQPainterSetTransform: CPointer<CFunction<(COpaquePointer, COpaquePointer, Byte) -> Unit>>? = null
    private var fnQPainterTranslate: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQPainterScale: CPointer<CFunction<(COpaquePointer, Double, Double) -> Unit>>? = null
    private var fnQPainterRotate: CPointer<CFunction<(COpaquePointer, Double) -> Unit>>? = null

    private var fnQPathCtor: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQPathDtor: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQPathSetFillRule: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQPathMoveTo: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQPathLineTo: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQPathArcMoveTo: CPointer<CFunction<(COpaquePointer, COpaquePointer, Double) -> Unit>>? = null
    private var fnQPathArcTo: CPointer<CFunction<(COpaquePointer, COpaquePointer, Double, Double) -> Unit>>? = null
    private var fnQPathCubicTo: CPointer<CFunction<(COpaquePointer, COpaquePointer, COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQPathAddRect: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQPathAddRoundedRect: CPointer<CFunction<(COpaquePointer, COpaquePointer, Double, Double, Int) -> Unit>>? = null
    private var fnQPathCloseSubpath: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null

    private var fnQPenCtorColor: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQPenDtor: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQPenSetStyle: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQPenSetWidthF: CPointer<CFunction<(COpaquePointer, Double) -> Unit>>? = null
    private var fnQBrushCtorColor: CPointer<CFunction<(COpaquePointer, COpaquePointer, Int) -> Unit>>? = null
    private var fnQBrushCtorGradient: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQBrushDtor: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQLinearGradCtor: CPointer<CFunction<(COpaquePointer, Double, Double, Double, Double) -> Unit>>? = null
    private var fnQLinearGradDtor: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQRadialGradCtor: CPointer<CFunction<(COpaquePointer, Double, Double, Double, Double, Double) -> Unit>>? = null
    private var fnQRadialGradDtor: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQGradientSetColorAt: CPointer<CFunction<(COpaquePointer, Double, COpaquePointer) -> Unit>>? = null
    private var fnQTransformSetMatrix: CPointer<CFunction<(COpaquePointer, Double, Double, Double, Double, Double, Double, Double, Double, Double) -> Unit>>? = null
    private var fnQGraphicsBlurCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQGraphicsBlurSetRadius: CPointer<CFunction<(COpaquePointer, Double) -> Unit>>? = null

    // --- Qt6 Control Widgets ---
    private var fnQPushButtonCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQPushButtonSetDefault: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnQPushButtonSetMenu: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQPushButtonSetFlat: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnQToolButtonCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQToolButtonSetPopupMode: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQToolButtonSetMenu: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQToolButtonSetDefaultAction: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQAbstractBtnSetText: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQAbstractBtnSetCheckable: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnQAbstractBtnSetChecked: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnQAbstractBtnIsChecked: CPointer<CFunction<(COpaquePointer) -> Byte>>? = null
    private var fnQCheckBoxCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQCheckBoxSetCheckState: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQRadioButtonCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null

    private var fnQComboBoxCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQComboBoxClear: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQComboBoxSetEditable: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnQComboBoxSetCurrentIndex: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQComboBoxCurrentIndex: CPointer<CFunction<(COpaquePointer) -> Int>>? = null
    private var fnQComboBoxInsertItem: CPointer<CFunction<(COpaquePointer, Int, COpaquePointer, COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQComboBoxSetEditText: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQComboBoxSetPlaceholder: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null

    private var fnQLineEditCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQLineEditSetText: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQLineEditSetPlaceholder: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQLineEditSetReadOnly: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnQLineEditSetEchoMode: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQLineEditSetFrame: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnQPlainTextCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQPlainTextSetText: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQPlainTextSetPlaceholder: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQPlainTextSetReadOnly: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnQPlainTextDocument: CPointer<CFunction<(COpaquePointer) -> COpaquePointer?>>? = null
    private var fnQTextDocCharCount: CPointer<CFunction<(COpaquePointer) -> Int>>? = null
    private var fnQTextDocCharAt: CPointer<CFunction<(COpaquePointer, Int) -> UShort>>? = null

    private var fnQSpinBoxCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQSpinBoxSetRange: CPointer<CFunction<(COpaquePointer, Int, Int) -> Unit>>? = null
    private var fnQSpinBoxSetSingleStep: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQSpinBoxSetValue: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQSpinBoxValue: CPointer<CFunction<(COpaquePointer) -> Int>>? = null
    private var fnQDoubleSpinBoxCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQDoubleSpinBoxSetRange: CPointer<CFunction<(COpaquePointer, Double, Double) -> Unit>>? = null
    private var fnQDoubleSpinBoxSetSingleStep: CPointer<CFunction<(COpaquePointer, Double) -> Unit>>? = null
    private var fnQDoubleSpinBoxSetValue: CPointer<CFunction<(COpaquePointer, Double) -> Unit>>? = null
    private var fnQDoubleSpinBoxValue: CPointer<CFunction<(COpaquePointer) -> Double>>? = null

    private var fnQDateEditCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQTimeEditCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQDateTimeEditSetDate: CPointer<CFunction<(COpaquePointer, Long) -> Unit>>? = null
    private var fnQDateTimeEditSetTime: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQDateTimeEditSetDisplayFmt: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQDateYear: CPointer<CFunction<(COpaquePointer) -> Int>>? = null
    private var fnQDateMonth: CPointer<CFunction<(COpaquePointer) -> Int>>? = null
    private var fnQDateDay: CPointer<CFunction<(COpaquePointer) -> Int>>? = null
    private var fnQTimeHour: CPointer<CFunction<(COpaquePointer) -> Int>>? = null
    private var fnQTimeMinute: CPointer<CFunction<(COpaquePointer) -> Int>>? = null
    private var fnQTimeSecond: CPointer<CFunction<(COpaquePointer) -> Int>>? = null

    private var fnQSliderCtorOrient: CPointer<CFunction<(COpaquePointer, Int, COpaquePointer?) -> Unit>>? = null
    private var fnQAbstractSliderSetRange: CPointer<CFunction<(COpaquePointer, Int, Int) -> Unit>>? = null
    private var fnQAbstractSliderSetValue: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQAbstractSliderSetSingleStep: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQAbstractSliderValue: CPointer<CFunction<(COpaquePointer) -> Int>>? = null

    private var fnQProgressBarCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQProgressBarSetRange: CPointer<CFunction<(COpaquePointer, Int, Int) -> Unit>>? = null
    private var fnQProgressBarSetValue: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQProgressBarSetTextVisible: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null

    private var fnQLabelCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?, Int) -> Unit>>? = null
    private var fnQLabelSetText: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQLabelSetWordWrap: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnQLabelSetAlignment: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQTextBrowserCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQTextEditSetHtml: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQTextEditSetMarkdown: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQTextEditSetReadOnly: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnQTextBrowserSetOpenExtLinks: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null

    private var fnQFrameCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?, Int) -> Unit>>? = null
    private var fnQFrameSetShape: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQFrameSetShadow: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQFrameSetLineWidth: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null

    private var fnQScrollAreaCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQScrollAreaSetWidget: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQScrollAreaSetWidgetResizable: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnQScrollAreaSetHBarPolicy: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQScrollAreaSetVBarPolicy: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQScrollAreaVBar: CPointer<CFunction<(COpaquePointer) -> COpaquePointer?>>? = null
    private var fnQScrollAreaHBar: CPointer<CFunction<(COpaquePointer) -> COpaquePointer?>>? = null

    private var fnQSplitterCtor: CPointer<CFunction<(COpaquePointer, Int, COpaquePointer?) -> Unit>>? = null
    private var fnQSplitterAddWidget: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQSplitterSetOrientation: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQSplitterSetHandleWidth: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null

    private var fnQTabBarCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQTabBarAddTab: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Int>>? = null
    private var fnQTabBarInsertTab: CPointer<CFunction<(COpaquePointer, Int, COpaquePointer) -> Int>>? = null
    private var fnQTabBarRemoveTab: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQTabBarSetCurrentIndex: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQTabBarSetTabText: CPointer<CFunction<(COpaquePointer, Int, COpaquePointer) -> Unit>>? = null
    private var fnQTabBarSetTabsClosable: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnQTabBarSetShape: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQTabBarSetExpanding: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnQTabBarSetTabIcon: CPointer<CFunction<(COpaquePointer, Int, COpaquePointer) -> Unit>>? = null
    private var fnQTabBarSetTabButton: CPointer<CFunction<(COpaquePointer, Int, Int, COpaquePointer?) -> Unit>>? = null
    private var fnQTabBarSetIconSize: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQStackedWidgetCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQStackedWidgetAddWidget: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Int>>? = null
    private var fnQStackedWidgetRemoveWidget: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQStackedWidgetSetCurrentIndex: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null

    private var fnQTableWidgetCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQTableWidgetSetRowCount: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQTableWidgetSetColCount: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQTableWidgetSetItem: CPointer<CFunction<(COpaquePointer, Int, Int, COpaquePointer) -> Unit>>? = null
    private var fnQTableWidgetSetHHeaderItem: CPointer<CFunction<(COpaquePointer, Int, COpaquePointer) -> Unit>>? = null
    private var fnQTableWidgetClear: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQTableViewHHeader: CPointer<CFunction<(COpaquePointer) -> COpaquePointer?>>? = null
    private var fnQTableViewVHeader: CPointer<CFunction<(COpaquePointer) -> COpaquePointer?>>? = null
    private var fnQHeaderViewSetVisible: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnQHeaderViewSetResizeMode: CPointer<CFunction<(COpaquePointer, Int, Int) -> Unit>>? = null
    private var fnQTableItemCtorText: CPointer<CFunction<(COpaquePointer, COpaquePointer, Int) -> Unit>>? = null
    private var fnQItemViewSetEditTriggers: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQItemViewSetSelectionBehavior: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQItemViewSetSelectionMode: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQItemViewSetAltRows: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null

    private var fnQTreeWidgetCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQTreeWidgetSetColCount: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQTreeWidgetAddTopItem: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQTreeWidgetClear: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQTreeWidgetSetCurrentItem: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQTreeViewSetHeaderHidden: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnQTreeViewSetIndentation: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQTreeItemCtorType: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQTreeItemSetData: CPointer<CFunction<(COpaquePointer, Int, Int, COpaquePointer) -> Unit>>? = null
    private var fnQTreeItemAddChild: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQTreeItemSetExpanded: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnQVariantCtorString: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQVariantDtor: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null

    // --- Menus / Actions / Tray / Dialogs ---
    private var fnQMenuBarCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQMenuBarAddMenu: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> COpaquePointer?>>? = null
    private var fnQMenuBarClear: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQMenuCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQMenuSetTitle: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQMenuAddMenu: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> COpaquePointer?>>? = null
    private var fnQMenuAddActionText: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> COpaquePointer?>>? = null
    private var fnQMenuAddSeparator: CPointer<CFunction<(COpaquePointer) -> COpaquePointer?>>? = null
    private var fnQMenuClear: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQMenuPopup: CPointer<CFunction<(COpaquePointer, COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQActionSetText: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQActionSetCheckable: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnQActionSetChecked: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnQActionSetEnabled: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnQActionSetVisible: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnQActionSetShortcut: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQKeySequenceCtorText: CPointer<CFunction<(COpaquePointer, COpaquePointer, Int) -> Unit>>? = null
    private var fnQKeySequenceDtor: CPointer<CFunction<(COpaquePointer) -> Unit>>? = null
    private var fnQSplitterSetChildrenCollapsible: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null

    private var fnQSniCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQSniSetTitle: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQSniSetTipTitle: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQSniSetTipSub: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQSniSetIconName: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQSniSetIconPixmap: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQSniSetCategory: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQSniSetStatus: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQSniSetMenu: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQSniShowMessage: CPointer<CFunction<(COpaquePointer, COpaquePointer, COpaquePointer, COpaquePointer, Int) -> Unit>>? = null
    private var fnQSniSetStdActions: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null

    private var fnQTrayIconCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQTrayIconSetIcon: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQTrayIconSetToolTip: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQTrayIconSetMenu: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQTrayIconSetVisible: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnQTrayIconShowMessage: CPointer<CFunction<(COpaquePointer, COpaquePointer, COpaquePointer, Int, Int) -> Unit>>? = null

    private var fnQDialogCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?, Int) -> Unit>>? = null
    private var fnQDialogExec: CPointer<CFunction<(COpaquePointer) -> Int>>? = null
    private var fnQDialogSetModal: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnQMessageBoxInfo: CPointer<CFunction<(COpaquePointer?, COpaquePointer, COpaquePointer, Int, Int) -> Int>>? = null
    private var fnQMessageBoxWarn: CPointer<CFunction<(COpaquePointer?, COpaquePointer, COpaquePointer, Int, Int) -> Int>>? = null
    private var fnQMessageBoxCrit: CPointer<CFunction<(COpaquePointer?, COpaquePointer, COpaquePointer, Int, Int) -> Int>>? = null
    private var fnQMessageBoxQuestion: CPointer<CFunction<(COpaquePointer?, COpaquePointer, COpaquePointer, Int, Int) -> Int>>? = null
    private var fnQFileDialogCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?, Int) -> Unit>>? = null
    private var fnQFileDialogSetFileMode: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQFileDialogSetOptions: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQFileDialogSetDirectory: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQFileDialogSetFilter: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnQFileDialogSelectFile: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQColorDialogCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnQColorDialogSetColor: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnQColorDialogCurrColor: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null

    fun createQFileDialog(owner: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQFileDialogCtor!!.invoke(ptr, owner, WINDOW_DIALOG)
        return ptr
    }

    fun configureFileDialog(
        dlg: COpaquePointer,
        title: String,
        directoryOnly: Boolean,
        saveMode: Boolean,
        allowMultiple: Boolean,
        initialDir: String?
    ) {
        setWindowTitle(dlg, title)
        // QFileDialog::FileMode: AnyFile=0, ExistingFile=1, Directory=2, ExistingFiles=3
        val mode = when {
            directoryOnly -> 2
            saveMode -> 0
            allowMultiple -> 3
            else -> 1
        }
        fnQFileDialogSetFileMode?.invoke(dlg, mode)
        if (directoryOnly) {
            // Option ShowDirsOnly = 0x1 | DontResolveSymlinks = 0x2
            fnQFileDialogSetOptions?.invoke(dlg, 0x1)
        }
        if (!initialDir.isNullOrBlank()) {
            withTempQString(initialDir) { q -> fnQFileDialogSetDirectory?.invoke(dlg, q) }
        }
    }

    fun readQStringList(qListPtr: COpaquePointer?): List<String> {
        if (qListPtr == null) return emptyList()
        val slots = qListPtr.reinterpret<LongVar>()
        val ptrLong = slots[1]
        val size = slots[2].toInt()
        if (ptrLong == 0L || size <= 0 || size > 65536) return emptyList()
        val result = ArrayList<String>(size)
        for (i in 0 until size) {
            val elemPtr = (ptrLong + i.toLong() * 24L).toCPointer<ByteVar>()
            result.add(readBorrowedQString(elemPtr))
        }
        return result
    }

    fun createQColorDialog(owner: COpaquePointer?, initial: RgbaColor): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        memScoped {
            val col = allocArray<LongVar>(4)
            setQColorRgba(col, initial)
            fnQColorDialogCtor!!.invoke(ptr, col, owner)
        }
        return ptr
    }

    // --- KF6 WidgetsAddons & WindowSystem ---
    private var fnKBusyCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnKBusySetRunning: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnKPassCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnKPassLineEdit: CPointer<CFunction<(COpaquePointer) -> COpaquePointer?>>? = null
    private var fnKPassSetRevealAvail: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnKDateCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnKDateSetDateRef: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnKTimeCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnKTimeSetTimeRef: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnKColorBtnCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnKColorBtnSetColor: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnKSepCtorOrient: CPointer<CFunction<(COpaquePointer, Int, COpaquePointer?) -> Unit>>? = null
    private var fnKSepSetOrient: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnKUrlLabelCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnKUrlLabelSetUrl: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnKMsgCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnKMsgSetText: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnKMsgSetType: CPointer<CFunction<(COpaquePointer, Int) -> Unit>>? = null
    private var fnKMsgSetCloseVisible: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnKMsgSetWordWrap: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnKCollapsibleCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnKCollapsibleSetTitle: CPointer<CFunction<(COpaquePointer, COpaquePointer) -> Unit>>? = null
    private var fnKCollapsibleSetExpanded: CPointer<CFunction<(COpaquePointer, Byte) -> Unit>>? = null
    private var fnKActionMenuCtor: CPointer<CFunction<(COpaquePointer, COpaquePointer, COpaquePointer?) -> Unit>>? = null
    private var fnKActionMenuMenu: CPointer<CFunction<(COpaquePointer) -> COpaquePointer?>>? = null
    private var fnKWinEnableBlurBehind: CPointer<CFunction<(COpaquePointer, Byte, COpaquePointer) -> Unit>>? = null

    private fun logOnce(key: String, msg: String) {
        if (loggedNotices.add(key)) {
            memScoped {
                fprintf(stderr, "%s\n".cstr.ptr, "[GenericUI/KDE] $msg".cstr.ptr)
            }
        }
    }

    private fun requireSym(lib: COpaquePointer?, name: String): COpaquePointer {
        return LinuxDynamicLibraryLoader.symbol(lib, name)
            ?: throw IllegalStateException("Required Qt 6 symbol '$name' not found in loaded shared library.")
    }

    private fun optSym(lib: COpaquePointer?, vararg names: String): COpaquePointer? {
        if (lib == null) return null
        for (n in names) {
            val s = LinuxDynamicLibraryLoader.symbol(lib, n)
            if (s != null) return s
        }
        return null
    }

    fun ensureLoaded(): Boolean = lock.withLock {
        if (coreLoaded) return true
        LinuxDynamicLibraryLoader.ensureNoSigPipe()

        hCore = LinuxDynamicLibraryLoader.openFirst("libQt6Core.so.6", "libQt6Core.so", global = true)
            ?: throw IllegalStateException("KDE backend requires libQt6Core.so.6, which could not be loaded.")
        hGui = LinuxDynamicLibraryLoader.openFirst("libQt6Gui.so.6", "libQt6Gui.so", global = true)
            ?: throw IllegalStateException("KDE backend requires libQt6Gui.so.6, which could not be loaded.")
        hWidgets = LinuxDynamicLibraryLoader.openFirst("libQt6Widgets.so.6", "libQt6Widgets.so", global = true)
            ?: throw IllegalStateException("KDE backend requires libQt6Widgets.so.6, which could not be loaded.")

        // Optional KDE Frameworks 6 + Breeze style plugins (degrade cleanly if absent)
        hKfWidgets = LinuxDynamicLibraryLoader.openFirst("libKF6WidgetsAddons.so.6", "libKF6WidgetsAddons.so", global = true)
        if (hKfWidgets == null) {
            logOnce("kf6_widgets", "Optional libKF6WidgetsAddons.so.6 unavailable; using standard Qt 6 widget fallbacks.")
        }
        hKfSni = LinuxDynamicLibraryLoader.openFirst("libKF6StatusNotifierItem.so.6", "libKF6StatusNotifierItem.so", global = true)
        if (hKfSni == null) {
            logOnce("kf6_sni", "Optional libKF6StatusNotifierItem.so.6 unavailable; falling back to QSystemTrayIcon.")
        }
        hKfColorScheme = LinuxDynamicLibraryLoader.openFirst("libKF6ColorScheme.so.6", "libKF6ColorScheme.so", global = true)
        if (hKfColorScheme == null) {
            logOnce("kf6_colorscheme", "Optional libKF6ColorScheme.so.6 unavailable; using Breeze/kdeglobals QPalette.")
        }
        hKfConfigCore = LinuxDynamicLibraryLoader.openFirst("libKF6ConfigCore.so.6", "libKF6ConfigCore.so", global = true)
        hKfWindowSystem = LinuxDynamicLibraryLoader.openFirst("libKF6WindowSystem.so.6", "libKF6WindowSystem.so", global = true)
        if (hKfWindowSystem == null) {
            logOnce("kf6_ws", "Optional libKF6WindowSystem.so.6 unavailable; KWindowEffects blur-behind disabled.")
        }
        hBreezeStyle = LinuxDynamicLibraryLoader.openFirst(
            "breeze6.so",
            "/usr/lib/qt6/plugins/styles/breeze6.so",
            "/usr/lib64/qt6/plugins/styles/breeze6.so",
            "/usr/lib/x86_64-linux-gnu/qt6/plugins/styles/breeze6.so",
            "/usr/lib/aarch64-linux-gnu/qt6/plugins/styles/breeze6.so",
            global = true
        )
        breezeStyleAvailable = hBreezeStyle != null

        // Resolve required Qt6 Core/Gui/Widgets symbols
        fnQStringCtorUtf16 = requireSym(hCore, "_ZN7QStringC1EPK5QCharx").reinterpret()
        fnQArrayDataDealloc = requireSym(hCore, "_ZN10QArrayData10deallocateEPS_xx").reinterpret()

        symQObjectD1 = requireSym(hCore, "_ZN7QObjectD1Ev")
        symQObjectD0 = requireSym(hCore, "_ZN7QObjectD0Ev")
        symQObjectQtMetacall = requireSym(hCore, "_ZN7QObject11qt_metacallEN11QMetaObject4CallEiPPv")
        symQObjectEvent = requireSym(hCore, "_ZN7QObject5eventEP6QEvent")
        symQWidgetQtMetacall = requireSym(hWidgets, "_ZN7QWidget11qt_metacallEN11QMetaObject4CallEiPPv")
        symQWidgetD1 = requireSym(hWidgets, "_ZN7QWidgetD1Ev")
        symQWidgetD0 = requireSym(hWidgets, "_ZN7QWidgetD0Ev")
        symQWidgetDevType = requireSym(hWidgets, "_ZNK7QWidget7devTypeEv")
        symQWidgetEvent = requireSym(hWidgets, "_ZN7QWidget5eventEP6QEvent")
        symQWidgetPaintEvent = requireSym(hWidgets, "_ZN7QWidget10paintEventEP11QPaintEvent")
        symQWidgetResizeEvent = requireSym(hWidgets, "_ZN7QWidget11resizeEventEP12QResizeEvent")
        symQWidgetSizeHint = requireSym(hWidgets, "_ZNK7QWidget8sizeHintEv")
        symQWidgetMinSizeHint = requireSym(hWidgets, "_ZNK7QWidget15minimumSizeHintEv")
        symQWidgetMousePress = requireSym(hWidgets, "_ZN7QWidget15mousePressEventEP11QMouseEvent")
        symQWidgetMouseRelease = requireSym(hWidgets, "_ZN7QWidget17mouseReleaseEventEP11QMouseEvent")
        symQWidgetMouseDoubleClick = requireSym(hWidgets, "_ZN7QWidget21mouseDoubleClickEventEP11QMouseEvent")
        symQWidgetMouseMove = requireSym(hWidgets, "_ZN7QWidget14mouseMoveEventEP11QMouseEvent")
        symQWidgetWheel = requireSym(hWidgets, "_ZN7QWidget10wheelEventEP11QWheelEvent")
        symQWidgetEnter = requireSym(hWidgets, "_ZN7QWidget10enterEventEP11QEnterEvent")
        symQWidgetLeave = requireSym(hWidgets, "_ZN7QWidget10leaveEventEP6QEvent")
        symQWidgetKeyPress = requireSym(hWidgets, "_ZN7QWidget13keyPressEventEP9QKeyEvent")
        symQWidgetKeyRelease = requireSym(hWidgets, "_ZN7QWidget15keyReleaseEventEP9QKeyEvent")
        symQWidgetFocusIn = requireSym(hWidgets, "_ZN7QWidget12focusInEventEP11QFocusEvent")
        symQWidgetFocusOut = requireSym(hWidgets, "_ZN7QWidget13focusOutEventEP11QFocusEvent")
        symQWidgetClose = requireSym(hWidgets, "_ZN7QWidget10closeEventEP11QCloseEvent")

        fnQObjectCtor = requireSym(hCore, "_ZN7QObjectC1EPS_").reinterpret()
        fnQMetaObjectConnect = requireSym(hCore, "_ZN11QMetaObject7connectEPK7QObjectiS2_iiPi")
        fnQMetaObjectConnDtor = optSym(hCore, "_ZN11QMetaObject10ConnectionD1Ev")
        fnQMetaObjectIndexOfSignal = requireSym(hCore, "_ZNK11QMetaObject13indexOfSignalEPKc").reinterpret()
        fnQMetaObjectNormalizeSig = optSym(hCore, "_ZN11QMetaObject19normalizedSignatureEPKc")

        fnQAppCtor = requireSym(hWidgets, "_ZN12QApplicationC1ERiPPci").reinterpret()
        fnQAppDtor = optSym(hWidgets, "_ZN12QApplicationD1Ev")?.reinterpret()
        fnQAppExec = requireSym(hCore, "_ZN16QCoreApplication4execEv").reinterpret()
        fnQAppQuit = requireSym(hCore, "_ZN16QCoreApplication4quitEv").reinterpret()
        fnQAppProcessEvents = requireSym(hCore, "_ZN16QCoreApplication13processEventsE6QFlagsIN10QEventLoop17ProcessEventsFlagEE").reinterpret()
        fnQAppProcessEventsMax = optSym(hCore, "_ZN16QCoreApplication13processEventsE6QFlagsIN10QEventLoop17ProcessEventsFlagEEi")?.reinterpret()
        fnQAppSetStylePtr = requireSym(hWidgets, "_ZN12QApplication8setStyleEP6QStyle").reinterpret()
        fnQAppSetPalette = requireSym(hWidgets, "_ZN12QApplication10setPaletteERK8QPalettePKc").reinterpret()
        fnQStyleFactoryCreate = requireSym(hWidgets, "_ZN13QStyleFactory6createERK7QString").reinterpret()

        fnQGuiPrimaryScreen = optSym(hGui, "_ZN15QGuiApplication13primaryScreenEv")?.reinterpret()
        fnQScreenRefreshRate = optSym(hGui, "_ZNK7QScreen11refreshRateEv")?.reinterpret()
        fnQCorePostEvent = requireSym(hCore, "_ZN16QCoreApplication9postEventEP7QObjectP6QEventi").reinterpret()
        fnQEventCtor = requireSym(hCore, "_ZN6QEventC1ENS_4TypeE").reinterpret()
        fnQGuiSetOverrideCursor = optSym(hGui, "_ZN15QGuiApplication16setOverrideCursorERK7QCursor")?.reinterpret()
        fnQGuiRestoreOverrideCursor = optSym(hGui, "_ZN15QGuiApplication20restoreOverrideCursorEv")?.reinterpret()

        fnQTimerCtor = requireSym(hCore, "_ZN6QTimerC1EP7QObject").reinterpret()
        fnQTimerStart = requireSym(hCore, "_ZN6QTimer5startEi").reinterpret()
        fnQTimerStop = requireSym(hCore, "_ZN6QTimer4stopEv").reinterpret()
        fnQTimerSetInterval = requireSym(hCore, "_ZN6QTimer11setIntervalEi").reinterpret()

        fnQWidgetCtor = requireSym(hWidgets, "_ZN7QWidgetC1EPS_6QFlagsIN2Qt10WindowTypeEE").reinterpret()
        fnQWidgetSetParent = requireSym(hWidgets, "_ZN7QWidget9setParentEPS_").reinterpret()
        fnQWidgetSetParentFlags = requireSym(hWidgets, "_ZN7QWidget9setParentEPS_6QFlagsIN2Qt10WindowTypeEE").reinterpret()
        fnQWidgetSetGeometryRect = requireSym(hWidgets, "_ZN7QWidget11setGeometryERK5QRect").reinterpret()
        fnQWidgetMovePt = requireSym(hWidgets, "_ZN7QWidget4moveERK6QPoint").reinterpret()
        fnQWidgetResizeSz = requireSym(hWidgets, "_ZN7QWidget6resizeERK5QSize").reinterpret()
        fnQWidgetSetVisible = requireSym(hWidgets, "_ZN7QWidget10setVisibleEb").reinterpret()
        fnQWidgetIsVisible = optSym(hWidgets, "_ZNK7QWidget9isVisibleEv", "_ZNK7QWidget11isVisibleToEPKS_")?.reinterpret()
        fnQWidgetSetEnabled = requireSym(hWidgets, "_ZN7QWidget10setEnabledEb").reinterpret()
        fnQWidgetIsEnabled = optSym(hWidgets, "_ZNK7QWidget9isEnabledEv")?.reinterpret()
        fnQWidgetSetAttribute = requireSym(hWidgets, "_ZN7QWidget12setAttributeEN2Qt15WidgetAttributeEb").reinterpret()
        fnQWidgetSetWindowFlags = requireSym(hWidgets, "_ZN7QWidget14setWindowFlagsE6QFlagsIN2Qt10WindowTypeEE").reinterpret()
        fnQWidgetSetWindowState = requireSym(hWidgets, "_ZN7QWidget14setWindowStateE6QFlagsIN2Qt11WindowStateEE").reinterpret()
        fnQWidgetWindowState = requireSym(hWidgets, "_ZNK7QWidget11windowStateEv").reinterpret()
        fnQWidgetSetWindowTitle = requireSym(hWidgets, "_ZN7QWidget14setWindowTitleERK7QString").reinterpret()
        fnQWidgetSetWindowOpacity = requireSym(hWidgets, "_ZN7QWidget16setWindowOpacityEd").reinterpret()
        fnQWidgetSetWindowIcon = optSym(hWidgets, "_ZN7QWidget13setWindowIconERK5QIcon")?.reinterpret()
        fnQWidgetSetToolTip = requireSym(hWidgets, "_ZN7QWidget10setToolTipERK7QString").reinterpret()
        fnQWidgetSetCursor = requireSym(hWidgets, "_ZN7QWidget9setCursorERK7QCursor").reinterpret()
        fnQWidgetUnsetCursor = requireSym(hWidgets, "_ZN7QWidget11unsetCursorEv").reinterpret()
        fnQWidgetSetFocusReason = requireSym(hWidgets, "_ZN7QWidget8setFocusEN2Qt11FocusReasonE").reinterpret()
        fnQWidgetClearFocus = requireSym(hWidgets, "_ZN7QWidget10clearFocusEv").reinterpret()
        fnQWidgetSetFocusPolicy = requireSym(hWidgets, "_ZN7QWidget14setFocusPolicyEN2Qt11FocusPolicyE").reinterpret()
        fnQWidgetFocusPolicy = optSym(hWidgets, "_ZNK7QWidget11focusPolicyEv")?.reinterpret()
        fnQWidgetHasFocus = optSym(hWidgets, "_ZNK7QWidget8hasFocusEv")?.reinterpret()
        fnQWidgetSetMinSize = requireSym(hWidgets, "_ZN7QWidget14setMinimumSizeEii").reinterpret()
        fnQWidgetSetMaxSize = requireSym(hWidgets, "_ZN7QWidget14setMaximumSizeEii").reinterpret()
        fnQWidgetSetFixedWidth = requireSym(hWidgets, "_ZN7QWidget13setFixedWidthEi").reinterpret()
        fnQWidgetSetFixedHeight = requireSym(hWidgets, "_ZN7QWidget14setFixedHeightEi").reinterpret()
        fnQWidgetSetPalette = requireSym(hWidgets, "_ZN7QWidget10setPaletteERK8QPalette").reinterpret()
        fnQWidgetSetStyleSheet = optSym(hWidgets, "_ZN7QWidget13setStyleSheetERK7QString")?.reinterpret()
        fnQWidgetSetAutoFillBackground = optSym(hWidgets, "_ZN7QWidget21setAutoFillBackgroundEb")?.reinterpret()
        fnQWidgetSetFont = requireSym(hWidgets, "_ZN7QWidget7setFontERK5QFont").reinterpret()
        fnQWidgetRaise = requireSym(hWidgets, "_ZN7QWidget5raiseEv").reinterpret()
        fnQWidgetLower = requireSym(hWidgets, "_ZN7QWidget5lowerEv").reinterpret()
        fnQWidgetStackUnder = requireSym(hWidgets, "_ZN7QWidget10stackUnderEPS_").reinterpret()
        fnQWidgetUpdate = requireSym(hWidgets, "_ZN7QWidget6updateEv").reinterpret()
        fnQWidgetRepaint = requireSym(hWidgets, "_ZN7QWidget7repaintEv").reinterpret()
        fnQWidgetShowMinimized = requireSym(hWidgets, "_ZN7QWidget13showMinimizedEv").reinterpret()
        fnQWidgetShowMaximized = requireSym(hWidgets, "_ZN7QWidget13showMaximizedEv").reinterpret()
        fnQWidgetShowFullScreen = requireSym(hWidgets, "_ZN7QWidget14showFullScreenEv").reinterpret()
        fnQWidgetShowNormal = requireSym(hWidgets, "_ZN7QWidget10showNormalEv").reinterpret()
        fnQWidgetActivateWindow = optSym(hWidgets, "_ZN7QWidget14activateWindowEv")?.reinterpret()
        fnQWidgetIsActiveWindow = optSym(hWidgets, "_ZNK7QWidget14isActiveWindowEv")?.reinterpret()
        fnQWindowStartSystemMove = optSym(hGui, "_ZN7QWindow15startSystemMoveEv")?.reinterpret()
        fnQWidgetClose = requireSym(hWidgets, "_ZN7QWidget5closeEv").reinterpret()
        fnQWidgetWinId = optSym(hWidgets, "_ZNK7QWidget5winIdEv")?.reinterpret()
        fnQWidgetEffectiveWinId = optSym(hWidgets, "_ZNK7QWidget14effectiveWinIdEv")?.reinterpret()
        fnQWidgetWindowHandle = requireSym(hWidgets, "_ZNK7QWidget12windowHandleEv").reinterpret()
        fnQWidgetX = requireSym(hWidgets, "_ZNK7QWidget1xEv").reinterpret()
        fnQWidgetY = requireSym(hWidgets, "_ZNK7QWidget1yEv").reinterpret()
        fnQWidgetRender = requireSym(hWidgets, "_ZN7QWidget6renderEP8QPainterRK6QPointRK7QRegion6QFlagsINS_10RenderFlagEE").reinterpret()
        fnQWidgetSetGraphicsEffect = optSym(hWidgets, "_ZN7QWidget17setGraphicsEffectEP15QGraphicsEffect")?.reinterpret()
        fnQWidgetPos = optSym(hWidgets, "_ZNK7QWidget3posEv")?.reinterpret()
        fnQWidgetMapToGlobalPt = optSym(hWidgets, "_ZNK7QWidget11mapToGlobalERK6QPoint")?.reinterpret()
        fnQWidgetMapFromGlobalPt = optSym(hWidgets, "_ZNK7QWidget13mapFromGlobalERK6QPoint")?.reinterpret()
        fnQPaintDeviceMetric = requireSym(hGui, "_ZNK12QPaintDevice6metricENS_17PaintDeviceMetricE").reinterpret()

        // --- Drawing & Graphics ---
        fnQImageCtorEmpty = requireSym(hGui, "_ZN6QImageC1EiiNS_6FormatE").reinterpret()
        fnQImageCtorDataConst = optSym(
            hGui,
            "_ZN6QImageC1EPKhiixNS_6FormatEPFvPvES3_",
            "_ZN6QImageC1EPKhiiNS_6FormatEPFvPvES3_",
            "_ZN6QImageC1EPKhiiiNS_6FormatEPFvPvES3_"
        )?.reinterpret()
        fnQImageDtor = optSym(hGui, "_ZN6QImageD1Ev")?.reinterpret()
        fnQImageFillRgba = requireSym(hGui, "_ZN6QImage4fillEj").reinterpret()
        fnQImageBits = requireSym(hGui, "_ZN6QImage4bitsEv").reinterpret()
        fnQImageBytesPerLine = optSym(hGui, "_ZNK6QImage12bytesPerLineEv")?.reinterpret()
        fnQImageLoadFromData = optSym(hGui, "_ZN6QImage12loadFromDataEPKhiPKc")?.reinterpret()
        fnQImageSave = optSym(hGui, "_ZNK6QImage4saveERK7QStringPKci")?.reinterpret()
        fnQPixmapCtorEmpty = requireSym(hGui, "_ZN7QPixmapC1Ev").reinterpret()
        fnQPixmapDtor = requireSym(hGui, "_ZN7QPixmapD1Ev").reinterpret()
        fnQPixmapConvertFromImage = requireSym(hGui, "_ZN7QPixmap16convertFromImageERK6QImage6QFlagsIN2Qt19ImageConversionFlagEE").reinterpret()
        fnQIconCtorEmpty = requireSym(hGui, "_ZN5QIconC1Ev").reinterpret()
        fnQIconCtorPixmap = requireSym(hGui, "_ZN5QIconC1ERK7QPixmap").reinterpret()
        fnQIconAddFile = optSym(hGui, "_ZN5QIcon7addFileERK7QStringRK5QSizeNS_4ModeENS_5StateE")?.reinterpret()
        fnQIconDtor = requireSym(hGui, "_ZN5QIconD1Ev").reinterpret()
        fnQCursorCtorShape = requireSym(hGui, "_ZN7QCursorC1EN2Qt11CursorShapeE").reinterpret()
        fnQCursorDtor = requireSym(hGui, "_ZN7QCursorD1Ev").reinterpret()
        fnQCursorPos = optSym(hGui, "_ZN7QCursor3posEv")?.reinterpret()

        fnQColorCtorRgba = requireSym(hGui, "_ZN6QColorC1Ej").reinterpret()
        fnQColorSetRgb = requireSym(hGui, "_ZN6QColor6setRgbEiiii").reinterpret()
        fnQColorRed = optSym(hGui, "_ZNK6QColor3redEv")?.reinterpret()
        fnQColorGreen = optSym(hGui, "_ZNK6QColor5greenEv")?.reinterpret()
        fnQColorBlue = optSym(hGui, "_ZNK6QColor4blueEv")?.reinterpret()
        fnQColorAlpha = optSym(hGui, "_ZNK6QColor5alphaEv")?.reinterpret()

        fnQFontCtor = requireSym(hGui, "_ZN5QFontC1Ev").reinterpret()
        fnQFontDtor = requireSym(hGui, "_ZN5QFontD1Ev").reinterpret()
        fnQFontSetFamily = requireSym(hGui, "_ZN5QFont9setFamilyERK7QString").reinterpret()
        fnQFontSetPixelSize = requireSym(hGui, "_ZN5QFont12setPixelSizeEi").reinterpret()
        fnQFontSetPointSizeF = optSym(hGui, "_ZN5QFont13setPointSizeFEd")?.reinterpret()
        fnQFontSetWeight = requireSym(hGui, "_ZN5QFont9setWeightENS_6WeightE").reinterpret()
        fnQFontSetStyle = requireSym(hGui, "_ZN5QFont8setStyleENS_5StyleE").reinterpret()
        fnQFontMetricsFCtor = requireSym(hGui, "_ZN13QFontMetricsFC1ERK5QFont").reinterpret()
        fnQFontMetricsFDtor = requireSym(hGui, "_ZN13QFontMetricsFD1Ev").reinterpret()
        fnQFontMetricsFHorizAdvance = requireSym(hGui, "_ZNK13QFontMetricsF17horizontalAdvanceERK7QStringi").reinterpret()
        fnQFontMetricsFHeight = requireSym(hGui, "_ZNK13QFontMetricsF6heightEv").reinterpret()
        fnQFontMetricsFAscent = requireSym(hGui, "_ZNK13QFontMetricsF6ascentEv").reinterpret()

        fnQPaletteCtor = requireSym(hGui, "_ZN8QPaletteC1Ev").reinterpret()
        fnQPaletteDtor = requireSym(hGui, "_ZN8QPaletteD1Ev").reinterpret()
        fnQPaletteSetBrush = requireSym(hGui, "_ZN8QPalette8setBrushENS_10ColorGroupENS_9ColorRoleERK6QBrush").reinterpret()
        fnQRegionCtorRect = requireSym(hGui, "_ZN7QRegionC1EiiiiNS_10RegionTypeE").reinterpret()
        fnQRegionDtor = requireSym(hGui, "_ZN7QRegionD1Ev").reinterpret()

        fnQPainterCtor = requireSym(hGui, "_ZN8QPainterC1Ev").reinterpret()
        fnQPainterDtor = requireSym(hGui, "_ZN8QPainterD1Ev").reinterpret()
        fnQPainterBegin = requireSym(hGui, "_ZN8QPainter5beginEP12QPaintDevice").reinterpret()
        fnQPainterEnd = requireSym(hGui, "_ZN8QPainter3endEv").reinterpret()
        fnQPainterSave = requireSym(hGui, "_ZN8QPainter4saveEv").reinterpret()
        fnQPainterRestore = requireSym(hGui, "_ZN8QPainter7restoreEv").reinterpret()
        fnQPainterSetRenderHint = requireSym(hGui, "_ZN8QPainter13setRenderHintENS_10RenderHintEb").reinterpret()
        fnQPainterSetOpacity = requireSym(hGui, "_ZN8QPainter10setOpacityEd").reinterpret()
        fnQPainterSetFont = requireSym(hGui, "_ZN8QPainter7setFontERK5QFont").reinterpret()
        fnQPainterSetBrushObj = requireSym(hGui, "_ZN8QPainter8setBrushERK6QBrush").reinterpret()
        fnQPainterSetPenObj = requireSym(hGui, "_ZN8QPainter6setPenERK4QPen").reinterpret()
        fnQPainterSetPenStyle = requireSym(hGui, "_ZN8QPainter6setPenEN2Qt8PenStyleE").reinterpret()
        fnQPainterSetBrushStyle = requireSym(hGui, "_ZN8QPainter8setBrushEN2Qt10BrushStyleE").reinterpret()
        fnQPainterSetClipPath = requireSym(hGui, "_ZN8QPainter11setClipPathERK12QPainterPathN2Qt13ClipOperationE").reinterpret()
        fnQPainterSetClipRect = requireSym(hGui, "_ZN8QPainter11setClipRectERK6QRectFN2Qt13ClipOperationE").reinterpret()
        fnQPainterFillPath = requireSym(hGui, "_ZN8QPainter8fillPathERK12QPainterPathRK6QBrush").reinterpret()
        fnQPainterStrokePath = requireSym(hGui, "_ZN8QPainter10strokePathERK12QPainterPathRK4QPen").reinterpret()
        fnQPainterFillRectColor = requireSym(hGui, "_ZN8QPainter8fillRectERK6QRectFRK6QColor").reinterpret()
        fnQPainterDrawRoundedRect = requireSym(hGui, "_ZN8QPainter15drawRoundedRectERK6QRectFddN2Qt8SizeModeE").reinterpret()
        fnQPainterDrawRects = requireSym(hGui, "_ZN8QPainter9drawRectsEPK6QRectFi").reinterpret()
        fnQPainterDrawLines = requireSym(hGui, "_ZN8QPainter9drawLinesEPK6QLineFi").reinterpret()
        fnQPainterDrawEllipse = requireSym(hGui, "_ZN8QPainter11drawEllipseERK6QRectF").reinterpret()
        fnQPainterDrawTextRect = requireSym(hGui, "_ZN8QPainter8drawTextERK6QRectFiRK7QStringPS0_").reinterpret()
        fnQPainterDrawImageRects = requireSym(hGui, "_ZN8QPainter9drawImageERK6QRectFRK6QImageS2_6QFlagsIN2Qt19ImageConversionFlagEE").reinterpret()
        fnQPainterSetTransform = requireSym(hGui, "_ZN8QPainter12setTransformERK10QTransformb").reinterpret()
        fnQPainterTranslate = requireSym(hGui, "_ZN8QPainter9translateERK7QPointF").reinterpret()
        fnQPainterScale = requireSym(hGui, "_ZN8QPainter5scaleEdd").reinterpret()
        fnQPainterRotate = requireSym(hGui, "_ZN8QPainter6rotateEd").reinterpret()

        fnQPathCtor = requireSym(hGui, "_ZN12QPainterPathC1Ev").reinterpret()
        fnQPathDtor = requireSym(hGui, "_ZN12QPainterPathD1Ev").reinterpret()
        fnQPathSetFillRule = requireSym(hGui, "_ZN12QPainterPath11setFillRuleEN2Qt8FillRuleE").reinterpret()
        fnQPathMoveTo = requireSym(hGui, "_ZN12QPainterPath6moveToERK7QPointF").reinterpret()
        fnQPathLineTo = requireSym(hGui, "_ZN12QPainterPath6lineToERK7QPointF").reinterpret()
        fnQPathArcMoveTo = requireSym(hGui, "_ZN12QPainterPath9arcMoveToERK6QRectFd").reinterpret()
        fnQPathArcTo = requireSym(hGui, "_ZN12QPainterPath5arcToERK6QRectFdd").reinterpret()
        fnQPathCubicTo = requireSym(hGui, "_ZN12QPainterPath7cubicToERK7QPointFS2_S2_").reinterpret()
        fnQPathAddRect = requireSym(hGui, "_ZN12QPainterPath7addRectERK6QRectF").reinterpret()
        fnQPathAddRoundedRect = requireSym(hGui, "_ZN12QPainterPath14addRoundedRectERK6QRectFddN2Qt8SizeModeE").reinterpret()
        fnQPathCloseSubpath = requireSym(hGui, "_ZN12QPainterPath12closeSubpathEv").reinterpret()

        fnQPenCtorColor = requireSym(hGui, "_ZN4QPenC1ERK6QColor").reinterpret()
        fnQPenDtor = requireSym(hGui, "_ZN4QPenD1Ev").reinterpret()
        fnQPenSetStyle = requireSym(hGui, "_ZN4QPen8setStyleEN2Qt8PenStyleE").reinterpret()
        fnQPenSetWidthF = requireSym(hGui, "_ZN4QPen9setWidthFEd").reinterpret()
        fnQBrushCtorColor = requireSym(hGui, "_ZN6QBrushC1ERK6QColorN2Qt10BrushStyleE").reinterpret()
        fnQBrushCtorGradient = requireSym(hGui, "_ZN6QBrushC1ERK9QGradient").reinterpret()
        fnQBrushDtor = requireSym(hGui, "_ZN6QBrushD1Ev").reinterpret()
        fnQLinearGradCtor = requireSym(hGui, "_ZN15QLinearGradientC1Edddd").reinterpret()
        fnQLinearGradDtor = requireSym(hGui, "_ZN15QLinearGradientD1Ev").reinterpret()
        fnQRadialGradCtor = requireSym(hGui, "_ZN15QRadialGradientC1Eddddd").reinterpret()
        fnQRadialGradDtor = requireSym(hGui, "_ZN15QRadialGradientD1Ev").reinterpret()
        fnQGradientSetColorAt = requireSym(hGui, "_ZN9QGradient10setColorAtEdRK6QColor").reinterpret()
        fnQTransformSetMatrix = optSym(hGui, "_ZN10QTransform9setMatrixEddddddddd")?.reinterpret()
        fnQGraphicsBlurCtor = optSym(hWidgets, "_ZN19QGraphicsBlurEffectC1EP7QObject")?.reinterpret()
        fnQGraphicsBlurSetRadius = optSym(hWidgets, "_ZN19QGraphicsBlurEffect13setBlurRadiusEd")?.reinterpret()

        // --- Qt6 Control Widgets ---
        fnQPushButtonCtor = requireSym(hWidgets, "_ZN11QPushButtonC1EP7QWidget").reinterpret()
        fnQPushButtonSetDefault = requireSym(hWidgets, "_ZN11QPushButton10setDefaultEb").reinterpret()
        fnQPushButtonSetMenu = requireSym(hWidgets, "_ZN11QPushButton7setMenuEP5QMenu").reinterpret()
        fnQPushButtonSetFlat = optSym(hWidgets, "_ZN11QPushButton7setFlatEb")?.reinterpret()
        fnQToolButtonCtor = requireSym(hWidgets, "_ZN11QToolButtonC1EP7QWidget").reinterpret()
        fnQToolButtonSetPopupMode = requireSym(hWidgets, "_ZN11QToolButton12setPopupModeENS_19ToolButtonPopupModeE").reinterpret()
        fnQToolButtonSetMenu = requireSym(hWidgets, "_ZN11QToolButton7setMenuEP5QMenu").reinterpret()
        fnQToolButtonSetDefaultAction = requireSym(hWidgets, "_ZN11QToolButton16setDefaultActionEP7QAction").reinterpret()
        fnQAbstractBtnSetText = requireSym(hWidgets, "_ZN15QAbstractButton7setTextERK7QString").reinterpret()
        fnQAbstractBtnSetCheckable = requireSym(hWidgets, "_ZN15QAbstractButton12setCheckableEb").reinterpret()
        fnQAbstractBtnSetChecked = requireSym(hWidgets, "_ZN15QAbstractButton10setCheckedEb").reinterpret()
        fnQAbstractBtnIsChecked = requireSym(hWidgets, "_ZNK15QAbstractButton9isCheckedEv").reinterpret()
        fnQCheckBoxCtor = requireSym(hWidgets, "_ZN9QCheckBoxC1EP7QWidget").reinterpret()
        fnQCheckBoxSetCheckState = requireSym(hWidgets, "_ZN9QCheckBox13setCheckStateEN2Qt10CheckStateE").reinterpret()
        fnQRadioButtonCtor = requireSym(hWidgets, "_ZN12QRadioButtonC1EP7QWidget").reinterpret()

        fnQComboBoxCtor = requireSym(hWidgets, "_ZN9QComboBoxC1EP7QWidget").reinterpret()
        fnQComboBoxClear = requireSym(hWidgets, "_ZN9QComboBox5clearEv").reinterpret()
        fnQComboBoxSetEditable = requireSym(hWidgets, "_ZN9QComboBox11setEditableEb").reinterpret()
        fnQComboBoxSetCurrentIndex = requireSym(hWidgets, "_ZN9QComboBox15setCurrentIndexEi").reinterpret()
        fnQComboBoxCurrentIndex = requireSym(hWidgets, "_ZNK9QComboBox12currentIndexEv").reinterpret()
        fnQComboBoxInsertItem = requireSym(hWidgets, "_ZN9QComboBox10insertItemEiRK5QIconRK7QStringRK8QVariant").reinterpret()
        fnQComboBoxSetEditText = requireSym(hWidgets, "_ZN9QComboBox11setEditTextERK7QString").reinterpret()
        fnQComboBoxSetPlaceholder = optSym(hWidgets, "_ZN9QComboBox18setPlaceholderTextERK7QString")?.reinterpret()

        fnQLineEditCtor = requireSym(hWidgets, "_ZN9QLineEditC1EP7QWidget").reinterpret()
        fnQLineEditSetText = requireSym(hWidgets, "_ZN9QLineEdit7setTextERK7QString").reinterpret()
        fnQLineEditSetPlaceholder = requireSym(hWidgets, "_ZN9QLineEdit18setPlaceholderTextERK7QString").reinterpret()
        fnQLineEditSetReadOnly = optSym(hWidgets, "_ZN9QLineEdit11setReadOnlyEb")?.reinterpret()
        fnQLineEditSetEchoMode = requireSym(hWidgets, "_ZN9QLineEdit11setEchoModeENS_8EchoModeE").reinterpret()
        fnQLineEditSetFrame = optSym(hWidgets, "_ZN9QLineEdit8setFrameEb")?.reinterpret()
        fnQPlainTextCtor = requireSym(hWidgets, "_ZN14QPlainTextEditC1EP7QWidget").reinterpret()
        fnQPlainTextSetText = requireSym(hWidgets, "_ZN14QPlainTextEdit12setPlainTextERK7QString").reinterpret()
        fnQPlainTextSetPlaceholder = requireSym(hWidgets, "_ZN14QPlainTextEdit18setPlaceholderTextERK7QString").reinterpret()
        fnQPlainTextSetReadOnly = requireSym(hWidgets, "_ZN14QPlainTextEdit11setReadOnlyEb").reinterpret()
        fnQPlainTextDocument = requireSym(hWidgets, "_ZNK14QPlainTextEdit8documentEv").reinterpret()
        fnQTextDocCharCount = requireSym(hGui, "_ZNK13QTextDocument14characterCountEv").reinterpret()
        fnQTextDocCharAt = requireSym(hGui, "_ZNK13QTextDocument11characterAtEi").reinterpret()

        fnQSpinBoxCtor = requireSym(hWidgets, "_ZN8QSpinBoxC1EP7QWidget").reinterpret()
        fnQSpinBoxSetRange = requireSym(hWidgets, "_ZN8QSpinBox8setRangeEii").reinterpret()
        fnQSpinBoxSetSingleStep = requireSym(hWidgets, "_ZN8QSpinBox13setSingleStepEi").reinterpret()
        fnQSpinBoxSetValue = requireSym(hWidgets, "_ZN8QSpinBox8setValueEi").reinterpret()
        fnQSpinBoxValue = requireSym(hWidgets, "_ZNK8QSpinBox5valueEv").reinterpret()
        fnQDoubleSpinBoxCtor = requireSym(hWidgets, "_ZN14QDoubleSpinBoxC1EP7QWidget").reinterpret()
        fnQDoubleSpinBoxSetRange = requireSym(hWidgets, "_ZN14QDoubleSpinBox8setRangeEdd").reinterpret()
        fnQDoubleSpinBoxSetSingleStep = requireSym(hWidgets, "_ZN14QDoubleSpinBox13setSingleStepEd").reinterpret()
        fnQDoubleSpinBoxSetValue = requireSym(hWidgets, "_ZN14QDoubleSpinBox8setValueEd").reinterpret()
        fnQDoubleSpinBoxValue = requireSym(hWidgets, "_ZNK14QDoubleSpinBox5valueEv").reinterpret()

        fnQDateEditCtor = requireSym(hWidgets, "_ZN9QDateEditC1EP7QWidget").reinterpret()
        fnQTimeEditCtor = requireSym(hWidgets, "_ZN9QTimeEditC1EP7QWidget").reinterpret()
        fnQDateTimeEditSetDate = requireSym(hWidgets, "_ZN13QDateTimeEdit7setDateE5QDate").reinterpret()
        fnQDateTimeEditSetTime = requireSym(hWidgets, "_ZN13QDateTimeEdit7setTimeE5QTime").reinterpret()
        fnQDateTimeEditSetDisplayFmt = optSym(hWidgets, "_ZN13QDateTimeEdit16setDisplayFormatERK7QString")?.reinterpret()
        fnQDateYear = optSym(hCore, "_ZNK5QDate4yearEv")?.reinterpret()
        fnQDateMonth = optSym(hCore, "_ZNK5QDate5monthEv")?.reinterpret()
        fnQDateDay = optSym(hCore, "_ZNK5QDate3dayEv")?.reinterpret()
        fnQTimeHour = optSym(hCore, "_ZNK5QTime4hourEv")?.reinterpret()
        fnQTimeMinute = optSym(hCore, "_ZNK5QTime6minuteEv")?.reinterpret()
        fnQTimeSecond = optSym(hCore, "_ZNK5QTime6secondEv")?.reinterpret()

        fnQSliderCtorOrient = requireSym(hWidgets, "_ZN7QSliderC1EN2Qt11OrientationEP7QWidget").reinterpret()
        fnQAbstractSliderSetRange = requireSym(hWidgets, "_ZN15QAbstractSlider8setRangeEii").reinterpret()
        fnQAbstractSliderSetValue = requireSym(hWidgets, "_ZN15QAbstractSlider8setValueEi").reinterpret()
        fnQAbstractSliderSetSingleStep = requireSym(hWidgets, "_ZN15QAbstractSlider13setSingleStepEi").reinterpret()
        fnQAbstractSliderValue = requireSym(hWidgets, "_ZNK15QAbstractSlider5valueEv").reinterpret()

        fnQProgressBarCtor = requireSym(hWidgets, "_ZN12QProgressBarC1EP7QWidget").reinterpret()
        fnQProgressBarSetRange = requireSym(hWidgets, "_ZN12QProgressBar8setRangeEii").reinterpret()
        fnQProgressBarSetValue = requireSym(hWidgets, "_ZN12QProgressBar8setValueEi").reinterpret()
        fnQProgressBarSetTextVisible = requireSym(hWidgets, "_ZN12QProgressBar14setTextVisibleEb").reinterpret()

        fnQLabelCtor = requireSym(hWidgets, "_ZN6QLabelC1EP7QWidget6QFlagsIN2Qt10WindowTypeEE").reinterpret()
        fnQLabelSetText = requireSym(hWidgets, "_ZN6QLabel7setTextERK7QString").reinterpret()
        fnQLabelSetWordWrap = requireSym(hWidgets, "_ZN6QLabel11setWordWrapEb").reinterpret()
        fnQLabelSetAlignment = requireSym(hWidgets, "_ZN6QLabel12setAlignmentE6QFlagsIN2Qt13AlignmentFlagEE").reinterpret()
        fnQTextBrowserCtor = requireSym(hWidgets, "_ZN12QTextBrowserC1EP7QWidget").reinterpret()
        fnQTextEditSetHtml = requireSym(hWidgets, "_ZN9QTextEdit7setHtmlERK7QString").reinterpret()
        fnQTextEditSetMarkdown = requireSym(hWidgets, "_ZN9QTextEdit11setMarkdownERK7QString").reinterpret()
        fnQTextEditSetReadOnly = requireSym(hWidgets, "_ZN9QTextEdit11setReadOnlyEb").reinterpret()
        fnQTextBrowserSetOpenExtLinks = requireSym(hWidgets, "_ZN12QTextBrowser20setOpenExternalLinksEb").reinterpret()

        fnQFrameCtor = optSym(hWidgets, "_ZN6QFrameC1EP7QWidget6QFlagsIN2Qt10WindowTypeEE")?.reinterpret()
        fnQFrameSetShape = requireSym(hWidgets, "_ZN6QFrame13setFrameShapeENS_5ShapeE").reinterpret()
        fnQFrameSetShadow = requireSym(hWidgets, "_ZN6QFrame14setFrameShadowENS_6ShadowE").reinterpret()
        fnQFrameSetLineWidth = requireSym(hWidgets, "_ZN6QFrame12setLineWidthEi").reinterpret()

        fnQScrollAreaCtor = requireSym(hWidgets, "_ZN11QScrollAreaC1EP7QWidget").reinterpret()
        fnQScrollAreaSetWidget = requireSym(hWidgets, "_ZN11QScrollArea9setWidgetEP7QWidget").reinterpret()
        fnQScrollAreaSetWidgetResizable = requireSym(hWidgets, "_ZN11QScrollArea18setWidgetResizableEb").reinterpret()
        fnQScrollAreaSetHBarPolicy = optSym(hWidgets, "_ZN19QAbstractScrollArea24setHorizontalScrollBarPolicyEN2Qt15ScrollBarPolicyE")?.reinterpret()
        fnQScrollAreaSetVBarPolicy = optSym(hWidgets, "_ZN19QAbstractScrollArea22setVerticalScrollBarPolicyEN2Qt15ScrollBarPolicyE")?.reinterpret()
        fnQScrollAreaVBar = requireSym(hWidgets, "_ZNK19QAbstractScrollArea17verticalScrollBarEv").reinterpret()
        fnQScrollAreaHBar = requireSym(hWidgets, "_ZNK19QAbstractScrollArea19horizontalScrollBarEv").reinterpret()

        fnQSplitterCtor = requireSym(hWidgets, "_ZN9QSplitterC1EN2Qt11OrientationEP7QWidget").reinterpret()
        fnQSplitterAddWidget = requireSym(hWidgets, "_ZN9QSplitter9addWidgetEP7QWidget").reinterpret()
        fnQSplitterSetOrientation = requireSym(hWidgets, "_ZN9QSplitter14setOrientationEN2Qt11OrientationE").reinterpret()
        fnQSplitterSetHandleWidth = requireSym(hWidgets, "_ZN9QSplitter14setHandleWidthEi").reinterpret()
        fnQSplitterSetChildrenCollapsible = optSym(hWidgets, "_ZN9QSplitter22setChildrenCollapsibleEb")?.reinterpret()

        fnQTabBarCtor = requireSym(hWidgets, "_ZN7QTabBarC1EP7QWidget").reinterpret()
        fnQTabBarAddTab = requireSym(hWidgets, "_ZN7QTabBar6addTabERK7QString").reinterpret()
        fnQTabBarInsertTab = requireSym(hWidgets, "_ZN7QTabBar9insertTabEiRK7QString").reinterpret()
        fnQTabBarRemoveTab = requireSym(hWidgets, "_ZN7QTabBar9removeTabEi").reinterpret()
        fnQTabBarSetCurrentIndex = requireSym(hWidgets, "_ZN7QTabBar15setCurrentIndexEi").reinterpret()
        fnQTabBarSetTabText = requireSym(hWidgets, "_ZN7QTabBar10setTabTextEiRK7QString").reinterpret()
        fnQTabBarSetTabsClosable = requireSym(hWidgets, "_ZN7QTabBar15setTabsClosableEb").reinterpret()
        fnQTabBarSetShape = requireSym(hWidgets, "_ZN7QTabBar8setShapeENS_5ShapeE").reinterpret()
        fnQTabBarSetExpanding = requireSym(hWidgets, "_ZN7QTabBar12setExpandingEb").reinterpret()
        fnQTabBarSetTabIcon = optSym(hWidgets, "_ZN7QTabBar10setTabIconEiRK5QIcon")?.reinterpret()
        fnQTabBarSetTabButton = optSym(hWidgets, "_ZN7QTabBar12setTabButtonEiNS_14ButtonPositionEP7QWidget")?.reinterpret()
        fnQTabBarSetIconSize = optSym(hWidgets, "_ZN7QTabBar11setIconSizeERK5QSize")?.reinterpret()
        fnQStackedWidgetCtor = requireSym(hWidgets, "_ZN14QStackedWidgetC1EP7QWidget").reinterpret()
        fnQStackedWidgetAddWidget = requireSym(hWidgets, "_ZN14QStackedWidget9addWidgetEP7QWidget").reinterpret()
        fnQStackedWidgetRemoveWidget = requireSym(hWidgets, "_ZN14QStackedWidget12removeWidgetEP7QWidget").reinterpret()
        fnQStackedWidgetSetCurrentIndex = requireSym(hWidgets, "_ZN14QStackedWidget15setCurrentIndexEi").reinterpret()

        fnQTableWidgetCtor = requireSym(hWidgets, "_ZN12QTableWidgetC1EP7QWidget").reinterpret()
        fnQTableWidgetSetRowCount = requireSym(hWidgets, "_ZN12QTableWidget11setRowCountEi").reinterpret()
        fnQTableWidgetSetColCount = requireSym(hWidgets, "_ZN12QTableWidget14setColumnCountEi").reinterpret()
        fnQTableWidgetSetItem = requireSym(hWidgets, "_ZN12QTableWidget7setItemEiiP16QTableWidgetItem").reinterpret()
        fnQTableWidgetSetHHeaderItem = requireSym(hWidgets, "_ZN12QTableWidget23setHorizontalHeaderItemEiP16QTableWidgetItem").reinterpret()
        fnQTableWidgetClear = requireSym(hWidgets, "_ZN12QTableWidget5clearEv").reinterpret()
        fnQTableViewHHeader = requireSym(hWidgets, "_ZNK10QTableView16horizontalHeaderEv").reinterpret()
        fnQTableViewVHeader = requireSym(hWidgets, "_ZNK10QTableView14verticalHeaderEv").reinterpret()
        fnQHeaderViewSetVisible = requireSym(hWidgets, "_ZN11QHeaderView10setVisibleEb").reinterpret()
        fnQHeaderViewSetResizeMode = requireSym(hWidgets, "_ZN11QHeaderView20setSectionResizeModeEiNS_10ResizeModeE").reinterpret()
        fnQTableItemCtorText = optSym(hWidgets, "_ZN16QTableWidgetItemC1ERK7QStringi", "_ZN16QTableWidgetItemC1Ei")?.reinterpret()
        fnQItemViewSetEditTriggers = requireSym(hWidgets, "_ZN17QAbstractItemView15setEditTriggersE6QFlagsINS_11EditTriggerEE").reinterpret()
        fnQItemViewSetSelectionBehavior = requireSym(hWidgets, "_ZN17QAbstractItemView20setSelectionBehaviorENS_17SelectionBehaviorE").reinterpret()
        fnQItemViewSetSelectionMode = requireSym(hWidgets, "_ZN17QAbstractItemView16setSelectionModeENS_13SelectionModeE").reinterpret()
        fnQItemViewSetAltRows = optSym(hWidgets, "_ZN17QAbstractItemView23setAlternatingRowColorsEb")?.reinterpret()

        fnQTreeWidgetCtor = requireSym(hWidgets, "_ZN11QTreeWidgetC1EP7QWidget").reinterpret()
        fnQTreeWidgetSetColCount = requireSym(hWidgets, "_ZN11QTreeWidget14setColumnCountEi").reinterpret()
        fnQTreeWidgetAddTopItem = requireSym(hWidgets, "_ZN11QTreeWidget15addTopLevelItemEP15QTreeWidgetItem").reinterpret()
        fnQTreeWidgetClear = requireSym(hWidgets, "_ZN11QTreeWidget5clearEv").reinterpret()
        fnQTreeWidgetSetCurrentItem = optSym(hWidgets, "_ZN11QTreeWidget14setCurrentItemEP15QTreeWidgetItem")?.reinterpret()
        fnQTreeViewSetHeaderHidden = requireSym(hWidgets, "_ZN9QTreeView15setHeaderHiddenEb").reinterpret()
        fnQTreeViewSetIndentation = requireSym(hWidgets, "_ZN9QTreeView14setIndentationEi").reinterpret()
        fnQTreeItemCtorType = requireSym(hWidgets, "_ZN15QTreeWidgetItemC1Ei").reinterpret()
        fnQTreeItemSetData = requireSym(hWidgets, "_ZN15QTreeWidgetItem7setDataEiiRK8QVariant").reinterpret()
        fnQTreeItemAddChild = requireSym(hWidgets, "_ZN15QTreeWidgetItem8addChildEPS_").reinterpret()
        fnQTreeItemSetExpanded = requireSym(hWidgets, "_ZN15QTreeWidgetItem11setExpandedEb").reinterpret()
        fnQVariantCtorString = requireSym(hCore, "_ZN8QVariantC1ERK7QString").reinterpret()
        fnQVariantDtor = requireSym(hCore, "_ZN8QVariantD1Ev").reinterpret()

        fnQMenuBarCtor = requireSym(hWidgets, "_ZN8QMenuBarC1EP7QWidget").reinterpret()
        fnQMenuBarAddMenu = requireSym(hWidgets, "_ZN8QMenuBar7addMenuEP5QMenu").reinterpret()
        fnQMenuBarClear = requireSym(hWidgets, "_ZN8QMenuBar5clearEv").reinterpret()
        fnQMenuCtor = requireSym(hWidgets, "_ZN5QMenuC1EP7QWidget").reinterpret()
        fnQMenuSetTitle = requireSym(hWidgets, "_ZN5QMenu8setTitleERK7QString").reinterpret()
        fnQMenuAddMenu = requireSym(hWidgets, "_ZN5QMenu7addMenuEPS_").reinterpret()
        fnQMenuAddActionText = requireSym(hWidgets, "_ZN5QMenu9addActionERK7QString").reinterpret()
        fnQMenuAddSeparator = requireSym(hWidgets, "_ZN5QMenu12addSeparatorEv").reinterpret()
        fnQMenuClear = requireSym(hWidgets, "_ZN5QMenu5clearEv").reinterpret()
        fnQMenuPopup = requireSym(hWidgets, "_ZN5QMenu5popupERK6QPointP7QAction").reinterpret()
        fnQActionSetText = requireSym(hGui, "_ZN7QAction7setTextERK7QString").reinterpret()
        fnQActionSetCheckable = requireSym(hGui, "_ZN7QAction12setCheckableEb").reinterpret()
        fnQActionSetChecked = requireSym(hGui, "_ZN7QAction10setCheckedEb").reinterpret()
        fnQActionSetEnabled = requireSym(hGui, "_ZN7QAction10setEnabledEb").reinterpret()
        fnQActionSetVisible = optSym(hGui, "_ZN7QAction10setVisibleEb")?.reinterpret()
        fnQActionSetShortcut = optSym(hGui, "_ZN7QAction11setShortcutERK12QKeySequence")?.reinterpret()
        fnQKeySequenceCtorText = optSym(hGui, "_ZN12QKeySequenceC1ERK7QStringNS_14SequenceFormatE")?.reinterpret()
        fnQKeySequenceDtor = optSym(hGui, "_ZN12QKeySequenceD1Ev")?.reinterpret()

        fnQTrayIconCtor = requireSym(hWidgets, "_ZN15QSystemTrayIconC1EP7QObject").reinterpret()
        fnQTrayIconSetIcon = requireSym(hWidgets, "_ZN15QSystemTrayIcon7setIconERK5QIcon").reinterpret()
        fnQTrayIconSetToolTip = requireSym(hWidgets, "_ZN15QSystemTrayIcon10setToolTipERK7QString").reinterpret()
        fnQTrayIconSetMenu = requireSym(hWidgets, "_ZN15QSystemTrayIcon14setContextMenuEP5QMenu").reinterpret()
        fnQTrayIconSetVisible = requireSym(hWidgets, "_ZN15QSystemTrayIcon10setVisibleEb").reinterpret()
        fnQTrayIconShowMessage = requireSym(hWidgets, "_ZN15QSystemTrayIcon11showMessageERK7QStringS2_NS_11MessageIconEi").reinterpret()

        fnQDialogCtor = optSym(hWidgets, "_ZN7QDialogC1EP7QWidget6QFlagsIN2Qt10WindowTypeEE")?.reinterpret()
        fnQDialogExec = optSym(hWidgets, "_ZN7QDialog4execEv")?.reinterpret()
        fnQDialogSetModal = optSym(hWidgets, "_ZN7QDialog8setModalEb")?.reinterpret()
        fnQMessageBoxInfo = requireSym(hWidgets, "_ZN11QMessageBox11informationEP7QWidgetRK7QStringS4_6QFlagsINS_14StandardButtonEES6_").reinterpret()
        fnQMessageBoxWarn = requireSym(hWidgets, "_ZN11QMessageBox7warningEP7QWidgetRK7QStringS4_6QFlagsINS_14StandardButtonEES6_").reinterpret()
        fnQMessageBoxCrit = requireSym(hWidgets, "_ZN11QMessageBox8criticalEP7QWidgetRK7QStringS4_6QFlagsINS_14StandardButtonEES6_").reinterpret()
        fnQMessageBoxQuestion = requireSym(hWidgets, "_ZN11QMessageBox8questionEP7QWidgetRK7QStringS4_6QFlagsINS_14StandardButtonEES6_").reinterpret()
        fnQFileDialogCtor = requireSym(hWidgets, "_ZN11QFileDialogC1EP7QWidget6QFlagsIN2Qt10WindowTypeEE").reinterpret()
        fnQFileDialogSetFileMode = requireSym(hWidgets, "_ZN11QFileDialog11setFileModeENS_8FileModeE").reinterpret()
        fnQFileDialogSetOptions = requireSym(hWidgets, "_ZN11QFileDialog10setOptionsE6QFlagsINS_6OptionEE").reinterpret()
        fnQFileDialogSetDirectory = requireSym(hWidgets, "_ZN11QFileDialog12setDirectoryERK7QString").reinterpret()
        fnQFileDialogSetFilter = requireSym(hWidgets, "_ZN11QFileDialog9setFilterE6QFlagsIN4QDir6FilterEE").reinterpret()
        fnQFileDialogSelectFile = optSym(hWidgets, "_ZN11QFileDialog10selectFileERK7QString")?.reinterpret()
        fnQColorDialogCtor = requireSym(hWidgets, "_ZN12QColorDialogC1ERK6QColorP7QWidget").reinterpret()
        fnQColorDialogSetColor = requireSym(hWidgets, "_ZN12QColorDialog15setCurrentColorERK6QColor").reinterpret()

        // Optional KF6 symbols
        if (hKfWidgets != null) {
            fnKBusyCtor = optSym(hKfWidgets, "_ZN20KBusyIndicatorWidgetC1EP7QWidget")?.reinterpret()
            fnKBusySetRunning = optSym(hKfWidgets, "_ZN20KBusyIndicatorWidget8setRunningEb")?.reinterpret()
            fnKPassCtor = optSym(hKfWidgets, "_ZN17KPasswordLineEditC1EP7QWidget")?.reinterpret()
            fnKPassLineEdit = optSym(hKfWidgets, "_ZNK17KPasswordLineEdit8lineEditEv")?.reinterpret()
            fnKPassSetRevealAvail = optSym(hKfWidgets, "_ZN17KPasswordLineEdit26setRevealPasswordAvailableEb")?.reinterpret()
            fnKDateCtor = optSym(hKfWidgets, "_ZN13KDateComboBoxC1EP7QWidget")?.reinterpret()
            fnKDateSetDateRef = optSym(hKfWidgets, "_ZN13KDateComboBox7setDateERK5QDate")?.reinterpret()
            fnKTimeCtor = optSym(hKfWidgets, "_ZN13KTimeComboBoxC1EP7QWidget")?.reinterpret()
            fnKTimeSetTimeRef = optSym(hKfWidgets, "_ZN13KTimeComboBox7setTimeERK5QTime")?.reinterpret()
            fnKColorBtnCtor = optSym(hKfWidgets, "_ZN12KColorButtonC1EP7QWidget")?.reinterpret()
            fnKColorBtnSetColor = optSym(hKfWidgets, "_ZN12KColorButton8setColorERK6QColor")?.reinterpret()
            fnKSepCtorOrient = optSym(hKfWidgets, "_ZN10KSeparatorC1EN2Qt11OrientationEP7QWidget")?.reinterpret()
            fnKSepSetOrient = optSym(hKfWidgets, "_ZN10KSeparator14setOrientationEN2Qt11OrientationE")?.reinterpret()
            fnKUrlLabelCtor = optSym(hKfWidgets, "_ZN9KUrlLabelC1EP7QWidget")?.reinterpret()
            fnKUrlLabelSetUrl = optSym(hKfWidgets, "_ZN9KUrlLabel6setUrlERK7QString")?.reinterpret()
            fnKMsgCtor = optSym(hKfWidgets, "_ZN14KMessageWidgetC1EP7QWidget")?.reinterpret()
            fnKMsgSetText = optSym(hKfWidgets, "_ZN14KMessageWidget7setTextERK7QString")?.reinterpret()
            fnKMsgSetType = optSym(hKfWidgets, "_ZN14KMessageWidget14setMessageTypeENS_11MessageTypeE")?.reinterpret()
            fnKMsgSetCloseVisible = optSym(hKfWidgets, "_ZN14KMessageWidget21setCloseButtonVisibleEb")?.reinterpret()
            fnKMsgSetWordWrap = optSym(hKfWidgets, "_ZN14KMessageWidget11setWordWrapEb")?.reinterpret()
            fnKCollapsibleCtor = optSym(hKfWidgets, "_ZN20KCollapsibleGroupBoxC1EP7QWidget")?.reinterpret()
            fnKCollapsibleSetTitle = optSym(hKfWidgets, "_ZN20KCollapsibleGroupBox8setTitleERK7QString")?.reinterpret()
            fnKCollapsibleSetExpanded = optSym(hKfWidgets, "_ZN20KCollapsibleGroupBox11setExpandedEb")?.reinterpret()
            fnKActionMenuCtor = optSym(hKfWidgets, "_ZN11KActionMenuC1ERK7QStringP7QObject")?.reinterpret()
            fnKActionMenuMenu = optSym(hKfWidgets, "_ZNK11KActionMenu4menuEv")?.reinterpret()
            kf6WidgetsAvailable = fnKBusyCtor != null
        }

        if (hKfSni != null) {
            fnQSniCtor = optSym(hKfSni, "_ZN19KStatusNotifierItemC1ERK7QStringP7QObject")?.reinterpret()
            fnQSniSetTitle = optSym(hKfSni, "_ZN19KStatusNotifierItem8setTitleERK7QString")?.reinterpret()
            fnQSniSetTipTitle = optSym(hKfSni, "_ZN19KStatusNotifierItem15setToolTipTitleERK7QString")?.reinterpret()
            fnQSniSetTipSub = optSym(hKfSni, "_ZN19KStatusNotifierItem18setToolTipSubTitleERK7QString")?.reinterpret()
            fnQSniSetIconName = optSym(hKfSni, "_ZN19KStatusNotifierItem13setIconByNameERK7QString")?.reinterpret()
            fnQSniSetIconPixmap = optSym(hKfSni, "_ZN19KStatusNotifierItem15setIconByPixmapERK5QIcon")?.reinterpret()
            fnQSniSetCategory = optSym(hKfSni, "_ZN19KStatusNotifierItem11setCategoryENS_12ItemCategoryE")?.reinterpret()
            fnQSniSetStatus = optSym(hKfSni, "_ZN19KStatusNotifierItem9setStatusENS_10ItemStatusE")?.reinterpret()
            fnQSniSetMenu = optSym(hKfSni, "_ZN19KStatusNotifierItem14setContextMenuEP5QMenu")?.reinterpret()
            fnQSniShowMessage = optSym(hKfSni, "_ZN19KStatusNotifierItem11showMessageERK7QStringS2_S2_i")?.reinterpret()
            fnQSniSetStdActions = optSym(hKfSni, "_ZN19KStatusNotifierItem25setStandardActionsEnabledEb")?.reinterpret()
            kf6StatusNotifierAvailable = fnQSniCtor != null
        }

        if (hKfWindowSystem != null) {
            fnKWinEnableBlurBehind = optSym(hKfWindowSystem, "_ZN14KWindowEffects16enableBlurBehindEP7QWindowbRK7QRegion")?.reinterpret()
            kf6WindowSystemAvailable = fnKWinEnableBlurBehind != null
        }

        kf6ColorSchemeAvailable = hKfColorScheme != null
        kf6ConfigCoreAvailable = hKfConfigCore != null

        coreLoaded = true
        true
    }

    fun ensureQApplication(darkTheme: Boolean = false): Boolean = lock.withLock {
        ensureLoaded()
        if (appCreated) return true

        val display = getenv("DISPLAY")?.let { true } ?: false
        val wayland = getenv("WAYLAND_DISPLAY")?.let { true } ?: false
        if (!display && !wayland && getenv("QT_QPA_PLATFORM") == null) {
            memScoped {
                setenv("QT_QPA_PLATFORM".cstr.ptr, "offscreen".cstr.ptr, 0)
            }
        }
        if (getenv("XDG_RUNTIME_DIR") == null) {
            val dir = "/tmp/genericui-xdg-runtime-${getuid()}"
            memScoped {
                mkdir(dir.cstr.ptr, 448u) // 0700
                setenv("XDG_RUNTIME_DIR".cstr.ptr, dir.cstr.ptr, 0)
            }
        }

        qAppArgcPtr = nativeHeap.alloc<IntVar>().apply { value = 1 }.ptr
        val appName = "genericui-kde"
        val nameBytes = appName.encodeToByteArray()
        qAppArg0Ptr = nativeHeap.allocArray<ByteVar>(nameBytes.size + 1).apply {
            for (i in nameBytes.indices) this[i] = nameBytes[i]
            this[nameBytes.size] = 0
        }
        qAppArgvPtr = nativeHeap.allocArray<COpaquePointerVar>(2).apply {
            this[0] = qAppArg0Ptr
            this[1] = null
        }
        qAppStorage = allocRawObject(NATIVE_QOBJECT_ALLOC_BYTES)
        fnQAppCtor!!.invoke(qAppStorage!!, qAppArgcPtr!!, qAppArgvPtr!!, QT6_APPLICATION_FLAGS)
        appCreated = true

        applyBreezeStyleAndPalette(darkTheme)
        true
    }

    fun applyBreezeStyleAndPalette(darkTheme: Boolean) {
        if (!ensureQApplication(darkTheme)) return
        val breezeStyle = createStyleByName("Breeze")
        if (breezeStyle != null) {
            fnQAppSetStylePtr?.invoke(breezeStyle)
            breezeStyleAvailable = true
            activeStyleName = "Breeze"
        } else {
            logOnce("breeze_style", "Optional Breeze QStyle plugin unavailable; falling back to Fusion QStyle.")
            val fusionStyle = createStyleByName("Fusion")
            if (fusionStyle != null) {
                fnQAppSetStylePtr?.invoke(fusionStyle)
                activeStyleName = "Fusion"
            }
        }
        applyBreezePalette(darkTheme)
    }

    fun activeStyleLabel(): String = activeStyleName

    private fun createStyleByName(name: String): COpaquePointer? {
        return withTempQString(name) { qStr ->
            fnQStyleFactoryCreate?.invoke(qStr)
        }
    }

    fun applyBreezePalette(darkTheme: Boolean) {
        if (!appCreated) return
        val iniPalette = readKdeGlobalsColors(darkTheme)
        memScoped {
            val pal = allocArray<LongVar>(4)
            fnQPaletteCtor?.invoke(pal)
            try {
                fun setRole(group: Int, role: Int, rgba: RgbaColor) {
                    val col = allocArray<LongVar>(4)
                    val br = allocArray<LongVar>(2)
                    setQColorRgba(col, rgba)
                    fnQBrushCtorColor?.invoke(br, col, BRUSH_SOLID_PATTERN)
                    fnQPaletteSetBrush?.invoke(pal, group, role, br)
                    fnQBrushDtor?.invoke(br)
                }

                val winBg = iniPalette.windowBg
                val winFg = iniPalette.windowFg
                val baseBg = iniPalette.viewBg
                val altBg = iniPalette.viewAltBg
                val btnBg = iniPalette.btnBg
                val btnFg = iniPalette.btnFg
                val accent = iniPalette.accentBg
                val accentFg = iniPalette.accentFg
                val disabledFg = iniPalette.disabledFg

                for (group in intArrayOf(0 /*Active*/, 2 /*Inactive*/)) {
                    setRole(group, 10 /*Window*/, winBg)
                    setRole(group, 0 /*WindowText*/, winFg)
                    setRole(group, 9 /*Base*/, baseBg)
                    setRole(group, 16 /*AlternateBase*/, altBg)
                    setRole(group, 6 /*Text*/, winFg)
                    setRole(group, 1 /*Button*/, btnBg)
                    setRole(group, 8 /*ButtonText*/, btnFg)
                    setRole(group, 12 /*Highlight*/, accent)
                    setRole(group, 13 /*HighlightedText*/, accentFg)
                    setRole(group, 14 /*Link*/, accent)
                }
                setRole(1 /*Disabled*/, 0, disabledFg)
                setRole(1, 6, disabledFg)
                setRole(1, 8, disabledFg)
                fnQAppSetPalette?.invoke(pal, null)
            } finally {
                fnQPaletteDtor?.invoke(pal)
            }
        }
    }

    private data class KdePaletteColors(
        val windowBg: RgbaColor,
        val windowFg: RgbaColor,
        val viewBg: RgbaColor,
        val viewAltBg: RgbaColor,
        val btnBg: RgbaColor,
        val btnFg: RgbaColor,
        val accentBg: RgbaColor,
        val accentFg: RgbaColor,
        val disabledFg: RgbaColor
    )

    internal fun readUtf8FileOrNull(path: String): String? =
        LinuxPosixPaths.readAllBytes(path)?.decodeToString()

    internal fun resolveBreezeAccentColor(darkTheme: Boolean): RgbaColor =
        readKdeGlobalsColors(darkTheme).accentBg

    private fun readKdeGlobalsColors(darkTheme: Boolean): KdePaletteColors {
        val defaultColors = if (darkTheme) {
            KdePaletteColors(
                windowBg = RgbaColor(0x23, 0x26, 0x29),
                windowFg = RgbaColor(0xFC, 0xFC, 0xFC),
                viewBg = RgbaColor(0x1B, 0x1E, 0x20),
                viewAltBg = RgbaColor(0x23, 0x26, 0x29),
                btnBg = RgbaColor(0x31, 0x36, 0x3B),
                btnFg = RgbaColor(0xFC, 0xFC, 0xFC),
                accentBg = RgbaColor(0x3D, 0xAE, 0xE9),
                accentFg = RgbaColor(0xFC, 0xFC, 0xFC),
                disabledFg = RgbaColor(0x7F, 0x8C, 0x8D)
            )
        } else {
            KdePaletteColors(
                windowBg = RgbaColor(0xEF, 0xF0, 0xF1),
                windowFg = RgbaColor(0x23, 0x26, 0x29),
                viewBg = RgbaColor(0xFC, 0xFC, 0xFC),
                viewAltBg = RgbaColor(0xF7, 0xF7, 0xF7),
                btnBg = RgbaColor(0xEF, 0xF0, 0xF1),
                btnFg = RgbaColor(0x23, 0x26, 0x29),
                accentBg = RgbaColor(0x3D, 0xAE, 0xE9),
                accentFg = RgbaColor(0xFC, 0xFC, 0xFC),
                disabledFg = RgbaColor(0x7F, 0x8C, 0x8D)
            )
        }
        val home = readUtf8FileOrNull(
            (getenv("HOME")?.toKString() ?: return defaultColors) + "/.config/kdeglobals"
        ) ?: return defaultColors

        val parsed = LinuxIniAndXmlConfigParser.parseIni(home)
        fun parseRgb(section: String, key: String, fallback: RgbaColor): RgbaColor {
            val raw = parsed[section]?.get(key) ?: return fallback
            val parts = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (parts.size < 3) return fallback
            val a = if (parts.size >= 4) (parts[3] / 255f).coerceIn(0f, 1f) else 1f
            return RgbaColor(parts[0].coerceIn(0, 255), parts[1].coerceIn(0, 255), parts[2].coerceIn(0, 255), a)
        }
        val iniScheme = parsed["General"]?.get("ColorScheme").orEmpty()
        val schemeLooksDark = iniScheme.contains("Dark", ignoreCase = true)
        if (darkTheme != schemeLooksDark) return defaultColors

        return KdePaletteColors(
            windowBg = parseRgb("Colors:Window", "BackgroundNormal", defaultColors.windowBg),
            windowFg = parseRgb("Colors:Window", "ForegroundNormal", defaultColors.windowFg),
            viewBg = parseRgb("Colors:View", "BackgroundNormal", defaultColors.viewBg),
            viewAltBg = parseRgb("Colors:View", "BackgroundAlternate", defaultColors.viewAltBg),
            btnBg = parseRgb("Colors:Button", "BackgroundNormal", defaultColors.btnBg),
            btnFg = parseRgb("Colors:Button", "ForegroundNormal", defaultColors.btnFg),
            accentBg = parseRgb("Colors:Selection", "BackgroundNormal", defaultColors.accentBg),
            accentFg = parseRgb("Colors:Selection", "ForegroundNormal", defaultColors.accentFg),
            disabledFg = parseRgb("Colors:Window", "ForegroundInactive", defaultColors.disabledFg)
        )
    }

    // --- Memory & QString ABI helpers ---
    fun allocRawObject(byteCount: Int = NATIVE_QOBJECT_ALLOC_BYTES): COpaquePointer {
        val sz = byteCount.coerceAtLeast(NATIVE_QOBJECT_ALLOC_BYTES)
        val ptr = calloc(1uL, sz.convert())
            ?: throw IllegalStateException("Out of native memory allocating $sz bytes for Qt/KF6 object")
        KdeVTableBridge.markObjectAllocated(ptr)
        return ptr
    }

    fun freeRawObject(ptr: COpaquePointer?) {
        if (ptr != null) free(ptr)
    }

    inline fun <T> withTempQString(text: String, block: (COpaquePointer) -> T): T = memScoped {
        val qStr = allocArray<LongVar>(3)
        qStr[0] = 0L
        qStr[1] = 0L
        qStr[2] = 0L
        if (text.isNotEmpty()) {
            val utf16 = UShortArray(text.length) { i -> text[i].code.toUShort() }
            utf16.usePinned { pinned ->
                fnQStringCtorUtf16!!.invoke(qStr, pinned.addressOf(0), text.length.toLong())
            }
        }
        try {
            block(qStr)
        } finally {
            releaseOwnedQString(qStr)
        }
    }

    fun releaseOwnedQString(qStrPtr: COpaquePointer) {
        val slots = qStrPtr.reinterpret<LongVar>()
        val dPtrLong = slots[0]
        if (dPtrLong == 0L) return
        val dPtr = dPtrLong.toCPointer<IntVar>() ?: return
        val refCount = dPtr.pointed.value
        // ref == -1 indicates QTypedArrayData static literal/empty guard; never free!
        if (refCount == -1) return
        if (refCount > 1) {
            dPtr.pointed.value = refCount - 1
        } else {
            fnQArrayDataDealloc?.invoke(dPtr, 2L, 8L)
        }
        slots[0] = 0L
        slots[1] = 0L
        slots[2] = 0L
    }

    fun readBorrowedQString(qStrPtr: COpaquePointer?): String {
        if (qStrPtr == null) return ""
        val slots = qStrPtr.reinterpret<LongVar>()
        val utf16PtrLong = slots[1]
        val size = slots[2].toInt()
        if (utf16PtrLong == 0L || size <= 0) return ""
        val chars = utf16PtrLong.toCPointer<UShortVar>() ?: return ""
        val sb = StringBuilder(size)
        for (i in 0 until size) {
            sb.append(chars[i].toInt().toChar())
        }
        return sb.toString()
    }

    fun readQDateFields(qDatePtr: COpaquePointer?): Triple<Int, Int, Int>? {
        if (qDatePtr == null) return null
        val y = fnQDateYear?.invoke(qDatePtr) ?: return null
        val m = fnQDateMonth?.invoke(qDatePtr) ?: return null
        val d = fnQDateDay?.invoke(qDatePtr) ?: return null
        if (y == 0 && m == 0 && d == 0) return null
        return Triple(y, m, d)
    }

    fun readQTimeFields(qTimePtr: COpaquePointer?): Triple<Int, Int, Int>? {
        if (qTimePtr == null) return null
        val h = fnQTimeHour?.invoke(qTimePtr) ?: return null
        val m = fnQTimeMinute?.invoke(qTimePtr) ?: return null
        val s = fnQTimeSecond?.invoke(qTimePtr) ?: 0
        if (h < 0 || m < 0) return null
        return Triple(h, m, s)
    }

    fun readQColorRgba(qColorPtr: COpaquePointer?): RgbaColor? {
        if (qColorPtr == null) return null
        val r = fnQColorRed?.invoke(qColorPtr) ?: return null
        val g = fnQColorGreen?.invoke(qColorPtr) ?: return null
        val b = fnQColorBlue?.invoke(qColorPtr) ?: return null
        val a = fnQColorAlpha?.invoke(qColorPtr) ?: 255
        return RgbaColor(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255), a.coerceIn(0, 255))
    }

    fun setQColorRgba(qColorPtr: COpaquePointer, color: RgbaColor) {
        fnQColorCtorRgba?.invoke(qColorPtr, 0u)
        val aInt = color.a.coerceIn(0, 255)
        fnQColorSetRgb?.invoke(
            qColorPtr,
            color.r.coerceIn(0, 255),
            color.g.coerceIn(0, 255),
            color.b.coerceIn(0, 255),
            aInt
        )
    }

    // Julian Day conversion matching QDate::jd for Gregorian dates
    fun encodeQDateJulianDay(year: Int, month: Int, day: Int): Long {
        val a = (14 - month) / 12
        val y = year + 4800 - a
        val m = month + 12 * a - 3
        return (day + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045).toLong()
    }

    fun encodeQTimeMs(hour: Int, minute: Int, second: Int = 0): Int =
        ((hour.coerceIn(0, 23) * 3600) + (minute.coerceIn(0, 59) * 60) + second.coerceIn(0, 59)) * 1000

    // --- Event Loop ---
    fun execEventLoop(): Int {
        ensureQApplication()
        return fnQAppExec?.invoke() ?: 0
    }

    fun quitEventLoop() {
        fnQAppQuit?.invoke()
    }

    fun postUserEvent(receiver: COpaquePointer) {
        if (!appCreated) return
        val fnPost = fnQCorePostEvent ?: return
        val fnEv = fnQEventCtor ?: return
        val ev = allocRawObject(64)
        fnEv.invoke(ev, 1000 /* QEvent::User */)
        fnPost.invoke(receiver, ev, 0)
    }

    fun primaryRefreshRateHz(): Double {
        if (!appCreated) return 0.0
        val screen = fnQGuiPrimaryScreen?.invoke() ?: return 0.0
        val hz = fnQScreenRefreshRate?.invoke(screen) ?: 0.0
        return if (hz > 1.0 && hz.isFinite()) hz else 0.0
    }

    fun processEvents(maxTimeMs: Int? = null) {
        if (!appCreated) return
        if (maxTimeMs != null && fnQAppProcessEventsMax != null) {
            fnQAppProcessEventsMax!!.invoke(0 /*AllEvents*/, maxTimeMs.coerceAtLeast(1))
        } else {
            fnQAppProcessEvents?.invoke(0)
        }
    }

    fun setOverrideCursorShape(shape: Int) {
        if (!appCreated) return
        memScoped {
            val cur = allocArray<LongVar>(2)
            fnQCursorCtorShape?.invoke(cur, shape)
            fnQGuiSetOverrideCursor?.invoke(cur)
            fnQCursorDtor?.invoke(cur)
        }
    }

    fun restoreOverrideCursor() {
        if (!appCreated) return
        fnQGuiRestoreOverrideCursor?.invoke()
    }

    fun globalCursorPos(): Pair<Int, Int>? {
        val fn = fnQCursorPos ?: return null
        val packed = fn.invoke()
        val x = (packed and 0xFFFFFFFFL).toInt()
        val y = (packed ushr 32).toInt()
        return x to y
    }

    // --- QObject / QMetaObject / QTimer ---
    fun createQObject(parent: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQObjectCtor!!.invoke(ptr, parent)
        return ptr
    }

    fun createQTimer(parent: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQTimerCtor!!.invoke(ptr, parent)
        return ptr
    }

    fun startQTimer(timer: COpaquePointer, intervalMs: Int) {
        fnQTimerSetInterval?.invoke(timer, intervalMs.coerceAtLeast(1))
        fnQTimerStart?.invoke(timer, intervalMs.coerceAtLeast(1))
    }

    fun stopQTimer(timer: COpaquePointer) {
        fnQTimerStop?.invoke(timer)
    }

    fun indexOfSignal(sender: COpaquePointer, signalSignature: String): Int {
        val vptrLong = sender.reinterpret<LongVar>()[0]
        val vptr = vptrLong.toCPointer<COpaquePointerVar>() ?: return -1
        // slot 0 in Qt QObject vtable is metaObject() const
        val metaObjFn = vptr[0]?.reinterpret<CFunction<(COpaquePointer) -> COpaquePointer?>>() ?: return -1
        val metaObj = metaObjFn.invoke(sender) ?: return -1
        return memScoped {
            val sigBytes = signalSignature.cstr.ptr
            var idx = fnQMetaObjectIndexOfSignal!!.invoke(metaObj, sigBytes)
            if (idx < 0 && fnQMetaObjectNormalizeSig != null) {
                val qba = allocArray<ULongVar>(3)
                qba[0] = 0uL; qba[1] = 0uL; qba[2] = 0uL
                kde_invoke_sret24_1(fnQMetaObjectNormalizeSig, sigBytes, qba)
                val normPtrLong = qba[1].toLong()
                if (normPtrLong != 0L) {
                    val normPtr = normPtrLong.toCPointer<ByteVar>()!!
                    idx = fnQMetaObjectIndexOfSignal!!.invoke(metaObj, normPtr)
                }
                val dPtrLong = qba[0].toLong()
                if (dPtrLong != 0L) {
                    val dPtr = dPtrLong.toCPointer<IntVar>()
                    val ref = dPtr?.pointed?.value ?: -1
                    if (dPtr != null && ref > 0) {
                        if (ref > 1) dPtr.pointed.value = ref - 1
                        else fnQArrayDataDealloc?.invoke(dPtr, 1L, 8L)
                    }
                }
            }
            idx
        }
    }

    fun connectSignal(sender: COpaquePointer, signalIndex: Int, receiver: COpaquePointer, methodIndex: Int): Boolean {
        if (signalIndex < 0) return false
        val fn = fnQMetaObjectConnect ?: return false
        return kde_invoke_qmetaobject_connect(
            fn,
            fnQMetaObjectConnDtor,
            sender,
            signalIndex,
            receiver,
            methodIndex,
            0 /*DirectConnection*/,
            null
        ) != 0
    }

    // --- Widget Creators ---
    fun createQWidget(parent: COpaquePointer? = null, windowFlags: Int = 0): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQWidgetCtor!!.invoke(ptr, parent, windowFlags)
        return ptr
    }

    fun createQPushButton(parent: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQPushButtonCtor!!.invoke(ptr, parent)
        return ptr
    }

    fun createQToolButton(parent: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQToolButtonCtor!!.invoke(ptr, parent)
        return ptr
    }

    fun createQCheckBox(parent: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQCheckBoxCtor!!.invoke(ptr, parent)
        return ptr
    }

    fun createQRadioButton(parent: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQRadioButtonCtor!!.invoke(ptr, parent)
        return ptr
    }

    fun createQComboBox(parent: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQComboBoxCtor!!.invoke(ptr, parent)
        return ptr
    }

    fun createQLineEdit(parent: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQLineEditCtor!!.invoke(ptr, parent)
        return ptr
    }

    fun createQPlainTextEdit(parent: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQPlainTextCtor!!.invoke(ptr, parent)
        return ptr
    }

    fun createQSpinBox(parent: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQSpinBoxCtor!!.invoke(ptr, parent)
        return ptr
    }

    fun createQDoubleSpinBox(parent: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQDoubleSpinBoxCtor!!.invoke(ptr, parent)
        return ptr
    }

    fun createQDateEdit(parent: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQDateEditCtor!!.invoke(ptr, parent)
        return ptr
    }

    fun createQTimeEdit(parent: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQTimeEditCtor!!.invoke(ptr, parent)
        return ptr
    }

    fun createQSlider(orientation: Int = HORIZONTAL, parent: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQSliderCtorOrient!!.invoke(ptr, orientation, parent)
        return ptr
    }

    fun createQProgressBar(parent: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQProgressBarCtor!!.invoke(ptr, parent)
        return ptr
    }

    fun createQLabel(parent: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQLabelCtor!!.invoke(ptr, parent, 0)
        return ptr
    }

    fun createQTextBrowser(parent: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQTextBrowserCtor!!.invoke(ptr, parent)
        return ptr
    }

    fun createQFrame(parent: COpaquePointer? = null, windowFlags: Int = 0): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        val ctor = fnQFrameCtor
        if (ctor != null) ctor.invoke(ptr, parent, windowFlags)
        else fnQWidgetCtor!!.invoke(ptr, parent, windowFlags)
        return ptr
    }

    fun createQScrollArea(parent: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQScrollAreaCtor!!.invoke(ptr, parent)
        return ptr
    }

    fun createQSplitter(orientation: Int = HORIZONTAL, parent: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQSplitterCtor!!.invoke(ptr, orientation, parent)
        fnQSplitterSetChildrenCollapsible?.invoke(ptr, 0)
        return ptr
    }

    fun splitterAddWidget(splitter: COpaquePointer, widget: COpaquePointer) {
        fnQSplitterAddWidget?.invoke(splitter, widget)
    }

    fun splitterSetOrientation(splitter: COpaquePointer, orientation: Int) {
        fnQSplitterSetOrientation?.invoke(splitter, orientation)
    }

    fun splitterSetHandleWidth(splitter: COpaquePointer, widthPx: Int) {
        fnQSplitterSetHandleWidth?.invoke(splitter, widthPx)
    }

    fun createQTabBar(parent: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQTabBarCtor!!.invoke(ptr, parent)
        return ptr
    }

    fun createQStackedWidget(parent: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQStackedWidgetCtor!!.invoke(ptr, parent)
        return ptr
    }

    fun createQTableWidget(parent: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQTableWidgetCtor!!.invoke(ptr, parent)
        return ptr
    }

    fun createQTreeWidget(parent: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQTreeWidgetCtor!!.invoke(ptr, parent)
        return ptr
    }

    fun createQMenuBar(parent: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQMenuBarCtor!!.invoke(ptr, parent)
        return ptr
    }

    fun createQMenu(parent: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQMenuCtor!!.invoke(ptr, parent)
        return ptr
    }

    fun createQDialog(parent: COpaquePointer? = null, flags: Int = WINDOW_DIALOG): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        val ctor = fnQDialogCtor
        if (ctor != null) ctor.invoke(ptr, parent, flags)
        else fnQWidgetCtor!!.invoke(ptr, parent, flags)
        return ptr
    }

    // --- KF6 Creators (returning null if KF6 unavailable so caller uses Qt6 fallback) ---
    fun createKBusyIndicatorWidget(parent: COpaquePointer? = null): COpaquePointer? {
        ensureQApplication()
        val ctor = fnKBusyCtor ?: return null
        val ptr = allocRawObject()
        ctor.invoke(ptr, parent)
        return ptr
    }

    fun setKBusyIndicatorRunning(widget: COpaquePointer, running: Boolean) {
        fnKBusySetRunning?.invoke(widget, if (running) 1 else 0)
    }

    fun createKPasswordLineEdit(parent: COpaquePointer? = null): COpaquePointer? {
        ensureQApplication()
        val ctor = fnKPassCtor ?: return null
        val ptr = allocRawObject()
        ctor.invoke(ptr, parent)
        fnKPassSetRevealAvail?.invoke(ptr, 1)
        return ptr
    }

    fun kPasswordInnerLineEdit(widget: COpaquePointer): COpaquePointer? =
        fnKPassLineEdit?.invoke(widget)

    fun createKDateComboBox(parent: COpaquePointer? = null): COpaquePointer? {
        ensureQApplication()
        val ctor = fnKDateCtor ?: return null
        val ptr = allocRawObject()
        ctor.invoke(ptr, parent)
        return ptr
    }

    fun setKDateComboDate(widget: COpaquePointer, year: Int, month: Int, day: Int) {
        val fn = fnKDateSetDateRef ?: return
        memScoped {
            val d = alloc<LongVar>()
            d.value = encodeQDateJulianDay(year, month, day)
            fn.invoke(widget, d.ptr)
        }
    }

    fun createKTimeComboBox(parent: COpaquePointer? = null): COpaquePointer? {
        ensureQApplication()
        val ctor = fnKTimeCtor ?: return null
        val ptr = allocRawObject()
        ctor.invoke(ptr, parent)
        return ptr
    }

    fun setKTimeComboTime(widget: COpaquePointer, hour: Int, minute: Int, second: Int = 0) {
        val fn = fnKTimeSetTimeRef ?: return
        memScoped {
            val t = alloc<IntVar>()
            t.value = encodeQTimeMs(hour, minute, second)
            fn.invoke(widget, t.ptr)
        }
    }

    fun createKColorButton(parent: COpaquePointer? = null): COpaquePointer? {
        ensureQApplication()
        val ctor = fnKColorBtnCtor ?: return null
        val ptr = allocRawObject()
        ctor.invoke(ptr, parent)
        return ptr
    }

    fun setKColorButtonColor(widget: COpaquePointer, color: RgbaColor) {
        val fn = fnKColorBtnSetColor ?: return
        memScoped {
            val col = allocArray<LongVar>(4)
            setQColorRgba(col, color)
            fn.invoke(widget, col)
        }
    }

    fun createKSeparator(orientation: Int, parent: COpaquePointer? = null): COpaquePointer? {
        ensureQApplication()
        val ctor = fnKSepCtorOrient ?: return null
        val ptr = allocRawObject()
        ctor.invoke(ptr, orientation, parent)
        return ptr
    }

    fun setKSeparatorOrientation(widget: COpaquePointer, orientation: Int) {
        fnKSepSetOrient?.invoke(widget, orientation)
    }

    fun createKUrlLabel(parent: COpaquePointer? = null): COpaquePointer? {
        ensureQApplication()
        val ctor = fnKUrlLabelCtor ?: return null
        val ptr = allocRawObject()
        ctor.invoke(ptr, parent)
        return ptr
    }

    fun setKUrlLabelUrl(widget: COpaquePointer, url: String) {
        withTempQString(url) { q -> fnKUrlLabelSetUrl?.invoke(widget, q) }
    }

    fun createKMessageWidget(parent: COpaquePointer? = null): COpaquePointer? {
        ensureQApplication()
        val ctor = fnKMsgCtor ?: return null
        val ptr = allocRawObject()
        ctor.invoke(ptr, parent)
        fnKMsgSetCloseVisible?.invoke(ptr, 0)
        fnKMsgSetWordWrap?.invoke(ptr, 1)
        return ptr
    }

    fun updateKMessageWidget(widget: COpaquePointer, text: String, messageType: Int) {
        withTempQString(text) { q -> fnKMsgSetText?.invoke(widget, q) }
        fnKMsgSetType?.invoke(widget, messageType)
    }

    fun createKCollapsibleGroupBox(parent: COpaquePointer? = null): COpaquePointer? {
        ensureQApplication()
        val ctor = fnKCollapsibleCtor ?: return null
        val ptr = allocRawObject()
        ctor.invoke(ptr, parent)
        return ptr
    }

    fun setKCollapsibleGroupBox(widget: COpaquePointer, title: String, expanded: Boolean) {
        withTempQString(title) { q -> fnKCollapsibleSetTitle?.invoke(widget, q) }
        fnKCollapsibleSetExpanded?.invoke(widget, if (expanded) 1 else 0)
    }

    fun createKActionMenu(label: String, parent: COpaquePointer? = null): COpaquePointer? {
        ensureQApplication()
        val ctor = fnKActionMenuCtor ?: return null
        val ptr = allocRawObject()
        withTempQString(label) { q -> ctor.invoke(ptr, q, parent) }
        return ptr
    }

    fun kActionMenuMenu(actionMenu: COpaquePointer): COpaquePointer? =
        fnKActionMenuMenu?.invoke(actionMenu)

    fun createKStatusNotifierItem(id: String, parent: COpaquePointer? = null): COpaquePointer? {
        ensureQApplication()
        val ctor = fnQSniCtor ?: return null
        val ptr = allocRawObject()
        withTempQString(id) { q -> ctor.invoke(ptr, q, parent) }
        fnQSniSetCategory?.invoke(ptr, KSNI_CAT_APP_STATUS)
        fnQSniSetStatus?.invoke(ptr, KSNI_STATUS_ACTIVE)
        fnQSniSetStdActions?.invoke(ptr, 0)
        return ptr
    }

    fun updateKStatusNotifierItem(
        sni: COpaquePointer,
        title: String? = null,
        tooltip: String? = null,
        iconName: String? = null,
        menu: COpaquePointer? = null,
        visible: Boolean? = null
    ) {
        if (title != null) withTempQString(title) { q -> fnQSniSetTitle?.invoke(sni, q) }
        if (tooltip != null) {
            withTempQString(tooltip) { q ->
                fnQSniSetTipTitle?.invoke(sni, q)
                fnQSniSetTipSub?.invoke(sni, q)
            }
        }
        if (!iconName.isNullOrBlank()) withTempQString(iconName) { q -> fnQSniSetIconName?.invoke(sni, q) }
        if (menu != null) fnQSniSetMenu?.invoke(sni, menu)
        if (visible != null) fnQSniSetStatus?.invoke(sni, if (visible) KSNI_STATUS_ACTIVE else KSNI_STATUS_PASSIVE)
    }

    fun setKStatusNotifierIconBytes(sni: COpaquePointer, pngOrRawBytes: ByteArray): Boolean {
        val fn = fnQSniSetIconPixmap ?: return false
        return memScoped {
            val img = allocArray<LongVar>(4)
            fnQImageCtorEmpty?.invoke(img, 1, 1, IMAGE_FORMAT_ARGB32_PREMULTIPLIED)
            val loaded = pngOrRawBytes.usePinned { p ->
                fnQImageLoadFromData?.invoke(img, p.addressOf(0), pngOrRawBytes.size, null) ?: 0.toByte()
            }
            val result = if (loaded != 0.toByte()) {
                val pm = allocArray<LongVar>(4)
                fnQPixmapCtorEmpty?.invoke(pm)
                fnQPixmapConvertFromImage?.invoke(pm, img, 0)
                val icon = allocArray<LongVar>(2)
                fnQIconCtorPixmap?.invoke(icon, pm)
                fn.invoke(sni, icon)
                fnQIconDtor?.invoke(icon)
                fnQPixmapDtor?.invoke(pm)
                true
            } else false
            fnQImageDtor?.invoke(img)
            result
        }
    }

    fun showKStatusNotifierMessage(sni: COpaquePointer, title: String, message: String, iconName: String = "dialog-information", timeoutMs: Int = 5000) {
        val fn = fnQSniShowMessage ?: return
        withTempQString(title) { t ->
            withTempQString(message) { m ->
                withTempQString(iconName) { i ->
                    fn.invoke(sni, t, m, i, timeoutMs)
                }
            }
        }
    }

    fun enableKWindowBlurBehind(widget: COpaquePointer, enabled: Boolean, widthPx: Int = 0, heightPx: Int = 0): Boolean {
        val fn = fnKWinEnableBlurBehind ?: return false
        val qWindow = fnQWidgetWindowHandle?.invoke(widget) ?: return false
        memScoped {
            val reg = allocArray<LongVar>(4)
            fnQRegionCtorRect?.invoke(reg, 0, 0, widthPx.coerceAtLeast(0), heightPx.coerceAtLeast(0), 0)
            fn.invoke(qWindow, if (enabled) 1 else 0, reg)
            fnQRegionDtor?.invoke(reg)
        }
        return true
    }

    // --- QSystemTrayIcon (Fallback) ---
    fun createQSystemTrayIcon(parent: COpaquePointer? = null): COpaquePointer {
        ensureQApplication()
        val ptr = allocRawObject()
        fnQTrayIconCtor!!.invoke(ptr, parent)
        return ptr
    }

    fun setQSystemTrayIconVisible(tray: COpaquePointer, visible: Boolean) {
        fnQTrayIconSetVisible?.invoke(tray, if (visible) 1 else 0)
    }

    fun setQSystemTrayIconTooltip(tray: COpaquePointer, tooltip: String) {
        withTempQString(tooltip) { q -> fnQTrayIconSetToolTip?.invoke(tray, q) }
    }

    fun setQSystemTrayIconMenu(tray: COpaquePointer, menu: COpaquePointer?) {
        fnQTrayIconSetMenu?.invoke(tray, menu)
    }

    fun setQSystemTrayIconFileOrBytes(tray: COpaquePointer, filePath: String? = null, encodedBytes: ByteArray? = null): Boolean {
        return memScoped {
            val icon = allocArray<LongVar>(2)
            var ok = false
            if (encodedBytes != null && encodedBytes.isNotEmpty()) {
                val img = allocArray<LongVar>(4)
                fnQImageCtorEmpty?.invoke(img, 1, 1, IMAGE_FORMAT_ARGB32_PREMULTIPLIED)
                val loaded = encodedBytes.usePinned { p ->
                    fnQImageLoadFromData?.invoke(img, p.addressOf(0), encodedBytes.size, null) ?: 0.toByte()
                }
                if (loaded != 0.toByte()) {
                    val pm = allocArray<LongVar>(4)
                    fnQPixmapCtorEmpty?.invoke(pm)
                    fnQPixmapConvertFromImage?.invoke(pm, img, 0)
                    fnQIconCtorPixmap?.invoke(icon, pm)
                    fnQTrayIconSetIcon?.invoke(tray, icon)
                    fnQIconDtor?.invoke(icon)
                    fnQPixmapDtor?.invoke(pm)
                    ok = true
                }
                fnQImageDtor?.invoke(img)
            }
            if (!ok && !filePath.isNullOrBlank()) {
                fnQIconCtorEmpty?.invoke(icon)
                val sz = alloc<LongVar>()
                sz.value = (32L) or (32L shl 32)
                withTempQString(filePath) { q ->
                    fnQIconAddFile?.invoke(icon, q, sz.ptr, 0, 0)
                }
                fnQTrayIconSetIcon?.invoke(tray, icon)
                fnQIconDtor?.invoke(icon)
                ok = true
            }
            ok
        }
    }

    fun showQSystemTrayMessage(tray: COpaquePointer, title: String, message: String, iconType: Int = 1, timeoutMs: Int = 5000) {
        withTempQString(title) { t ->
            withTempQString(message) { m ->
                fnQTrayIconShowMessage?.invoke(tray, t, m, iconType, timeoutMs)
            }
        }
    }

    // --- QWidget Operations ---
    fun setWidgetParent(widget: COpaquePointer, parent: COpaquePointer?, preserveFlags: Int? = null) {
        if (preserveFlags != null) {
            fnQWidgetSetParentFlags?.invoke(widget, parent, preserveFlags)
        } else {
            fnQWidgetSetParent?.invoke(widget, parent)
        }
    }

    fun setWidgetGeometry(widget: COpaquePointer, x: Int, y: Int, width: Int, height: Int) {
        val w = width.coerceAtLeast(0)
        val h = height.coerceAtLeast(0)
        memScoped {
            val rect = allocArray<IntVar>(4)
            // Qt QRect internal representation: x1, y1, x2, y2 where x2 = x + w - 1
            rect[0] = x
            rect[1] = y
            rect[2] = x + w - 1
            rect[3] = y + h - 1
            fnQWidgetSetGeometryRect?.invoke(widget, rect)
        }
    }

    fun moveWidget(widget: COpaquePointer, x: Int, y: Int) {
        memScoped {
            val pt = alloc<LongVar>()
            pt.value = (x.toLong() and 0xFFFFFFFFL) or (y.toLong() shl 32)
            fnQWidgetMovePt?.invoke(widget, pt.ptr)
        }
    }

    fun resizeWidget(widget: COpaquePointer, width: Int, height: Int) {
        memScoped {
            val sz = alloc<LongVar>()
            sz.value = (width.coerceAtLeast(0).toLong() and 0xFFFFFFFFL) or (height.coerceAtLeast(0).toLong() shl 32)
            fnQWidgetResizeSz?.invoke(widget, sz.ptr)
        }
    }

    fun setWidgetVisible(widget: COpaquePointer, visible: Boolean) {
        fnQWidgetSetVisible?.invoke(widget, if (visible) 1 else 0)
    }

    fun isWidgetVisible(widget: COpaquePointer): Boolean =
        (fnQWidgetIsVisible?.invoke(widget) ?: 0.toByte()) != 0.toByte()

    fun setWidgetEnabled(widget: COpaquePointer, enabled: Boolean) {
        fnQWidgetSetEnabled?.invoke(widget, if (enabled) 1 else 0)
    }

    fun isWidgetEnabled(widget: COpaquePointer): Boolean =
        (fnQWidgetIsEnabled?.invoke(widget) ?: 1.toByte()) != 0.toByte()

    fun setWidgetAttribute(widget: COpaquePointer, attr: Int, on: Boolean) {
        fnQWidgetSetAttribute?.invoke(widget, attr, if (on) 1 else 0)
    }

    fun setWindowFlags(widget: COpaquePointer, flags: Int) {
        fnQWidgetSetWindowFlags?.invoke(widget, flags)
    }

    fun setWindowState(widget: COpaquePointer, stateFlags: Int) {
        fnQWidgetSetWindowState?.invoke(widget, stateFlags)
    }

    fun getWindowState(widget: COpaquePointer): Int =
        fnQWidgetWindowState?.invoke(widget) ?: WINDOW_NO_STATE

    fun setWindowTitle(widget: COpaquePointer, title: String) {
        withTempQString(title) { q -> fnQWidgetSetWindowTitle?.invoke(widget, q) }
    }

    fun setWindowOpacity(widget: COpaquePointer, opacity: Float) {
        fnQWidgetSetWindowOpacity?.invoke(widget, opacity.coerceIn(0f, 1f).toDouble())
    }

    fun setWindowIconFromPath(widget: COpaquePointer, path: String) {
        val fn = fnQWidgetSetWindowIcon ?: return
        memScoped {
            val icon = allocArray<LongVar>(2)
            fnQIconCtorEmpty?.invoke(icon)
            val sz = alloc<LongVar>()
            sz.value = (48L) or (48L shl 32)
            withTempQString(path) { q -> fnQIconAddFile?.invoke(icon, q, sz.ptr, 0, 0) }
            fn.invoke(widget, icon)
            fnQIconDtor?.invoke(icon)
        }
    }

    fun setWidgetTooltip(widget: COpaquePointer, tooltip: String) {
        withTempQString(tooltip) { q -> fnQWidgetSetToolTip?.invoke(widget, q) }
    }

    fun setWidgetCursorShape(widget: COpaquePointer, shape: Int?) {
        if (shape == null) {
            fnQWidgetUnsetCursor?.invoke(widget)
            return
        }
        memScoped {
            val cur = allocArray<LongVar>(2)
            fnQCursorCtorShape?.invoke(cur, shape)
            fnQWidgetSetCursor?.invoke(widget, cur)
            fnQCursorDtor?.invoke(cur)
        }
    }

    fun setWidgetFocus(widget: COpaquePointer, focused: Boolean) {
        if (focused) fnQWidgetSetFocusReason?.invoke(widget, 7 /*OtherFocusReason*/)
        else fnQWidgetClearFocus?.invoke(widget)
    }

    fun setWidgetFocusPolicy(widget: COpaquePointer, policy: Int) {
        fnQWidgetSetFocusPolicy?.invoke(widget, policy)
    }

    fun getWidgetFocusPolicy(widget: COpaquePointer): Int =
        fnQWidgetFocusPolicy?.invoke(widget) ?: FOCUS_NO_FOCUS

    fun hasWidgetFocus(widget: COpaquePointer): Boolean =
        (fnQWidgetHasFocus?.invoke(widget) ?: 0.toByte()).toInt() != 0

    fun setWidgetMinSize(widget: COpaquePointer, w: Int, h: Int) {
        fnQWidgetSetMinSize?.invoke(widget, w.coerceAtLeast(0), h.coerceAtLeast(0))
    }

    fun setWidgetMaxSize(widget: COpaquePointer, w: Int, h: Int) {
        fnQWidgetSetMaxSize?.invoke(widget, w.coerceAtLeast(0), h.coerceAtLeast(0))
    }

    fun setWidgetFixedWidth(widget: COpaquePointer, w: Int) {
        fnQWidgetSetFixedWidth?.invoke(widget, w.coerceAtLeast(0))
    }

    fun setWidgetFixedHeight(widget: COpaquePointer, h: Int) {
        fnQWidgetSetFixedHeight?.invoke(widget, h.coerceAtLeast(0))
    }

    fun raiseWidget(widget: COpaquePointer) { fnQWidgetRaise?.invoke(widget) }
    fun lowerWidget(widget: COpaquePointer) { fnQWidgetLower?.invoke(widget) }
    fun stackWidgetUnder(widget: COpaquePointer, sibling: COpaquePointer) { fnQWidgetStackUnder?.invoke(widget, sibling) }
    fun updateWidget(widget: COpaquePointer) { fnQWidgetUpdate?.invoke(widget) }
    fun repaintWidget(widget: COpaquePointer) { fnQWidgetRepaint?.invoke(widget) }
    fun showMinimized(widget: COpaquePointer) { fnQWidgetShowMinimized?.invoke(widget) }
    fun showMaximized(widget: COpaquePointer) { fnQWidgetShowMaximized?.invoke(widget) }
    fun showFullScreen(widget: COpaquePointer) { fnQWidgetShowFullScreen?.invoke(widget) }
    fun showNormal(widget: COpaquePointer) { fnQWidgetShowNormal?.invoke(widget) }
    fun activateWindow(widget: COpaquePointer) { fnQWidgetActivateWindow?.invoke(widget) }
    fun isWindowActive(widget: COpaquePointer): Boolean =
        (fnQWidgetIsActiveWindow?.invoke(widget) ?: 1.toByte()) != 0.toByte()
    fun hasIsWindowActiveSymbol(): Boolean = fnQWidgetIsActiveWindow != null
    fun startWindowSystemMove(widget: COpaquePointer): Boolean {
        val qWindow = fnQWidgetWindowHandle?.invoke(widget) ?: return false
        return (fnQWindowStartSystemMove?.invoke(qWindow) ?: 0.toByte()) != 0.toByte()
    }
    fun closeWidget(widget: COpaquePointer) { fnQWidgetClose?.invoke(widget) }
    fun getWidgetWinId(widget: COpaquePointer): ULong = fnQWidgetEffectiveWinId?.invoke(widget) ?: fnQWidgetWinId?.invoke(widget) ?: 0uL
    fun getWidgetWidth(widget: COpaquePointer): Int = fnQPaintDeviceMetric?.invoke(paintDevicePtr(widget), PDM_WIDTH) ?: 0
    fun getWidgetHeight(widget: COpaquePointer): Int = fnQPaintDeviceMetric?.invoke(paintDevicePtr(widget), PDM_HEIGHT) ?: 0
    fun getWidgetX(widget: COpaquePointer): Int = fnQWidgetX?.invoke(widget) ?: 0
    fun getWidgetY(widget: COpaquePointer): Int = fnQWidgetY?.invoke(widget) ?: 0
    fun getWidgetPos(widget: COpaquePointer): Pair<Int, Int> {
        val fn = fnQWidgetPos ?: return getWidgetX(widget) to getWidgetY(widget)
        val packed = fn.invoke(widget)
        return (packed and 0xFFFFFFFFL).toInt() to (packed ushr 32).toInt()
    }
    fun mapToGlobal(widget: COpaquePointer, x: Int, y: Int): Pair<Int, Int> {
        val fn = fnQWidgetMapToGlobalPt ?: return x to y
        return memScoped {
            val pt = alloc<LongVar>()
            pt.value = (x.toLong() and 0xFFFFFFFFL) or (y.toLong() shl 32)
            val packed = fn.invoke(widget, pt.ptr)
            (packed and 0xFFFFFFFFL).toInt() to (packed ushr 32).toInt()
        }
    }
    fun mapFromGlobal(widget: COpaquePointer, gx: Int, gy: Int): Pair<Int, Int> {
        val fn = fnQWidgetMapFromGlobalPt ?: return gx to gy
        return memScoped {
            val pt = alloc<LongVar>()
            pt.value = (gx.toLong() and 0xFFFFFFFFL) or (gy.toLong() shl 32)
            val packed = fn.invoke(widget, pt.ptr)
            (packed and 0xFFFFFFFFL).toInt() to (packed ushr 32).toInt()
        }
    }

    /** QPaintDevice subobject base inside QWidget multiple inheritance (`self + 16` verified on 64-bit). */
    fun paintDevicePtr(qWidgetPtr: COpaquePointer): COpaquePointer =
        (qWidgetPtr.rawValue.toLong() + 16L).toCPointer<ByteVar>()!!

    fun paintDeviceDevType(paintDevicePtr: COpaquePointer): Int {
        val vptrLong = paintDevicePtr.reinterpret<LongVar>()[0]
        val vptr = vptrLong.toCPointer<COpaquePointerVar>() ?: return -1
        // slot 2 in Qt6 QWidget's QPaintDevice secondary vtable ([0]=~D1, [1]=~D0, [2]=devType)
        val devTypeFn = vptr[2]?.reinterpret<CFunction<(COpaquePointer) -> Int>>() ?: return -1
        return devTypeFn.invoke(paintDevicePtr)
    }

    fun renderWidgetIntoPainter(
        widget: COpaquePointer,
        painter: COpaquePointer,
        targetOffsetX: Int,
        targetOffsetY: Int,
        srcX: Int,
        srcY: Int,
        srcW: Int,
        srcH: Int,
        renderFlags: Int
    ) {
        if (srcW <= 0 || srcH <= 0) return
        memScoped {
            val offset = alloc<LongVar>()
            offset.value = (targetOffsetX.toLong() and 0xFFFFFFFFL) or (targetOffsetY.toLong() shl 32)
            val region = allocArray<LongVar>(4)
            fnQRegionCtorRect?.invoke(region, srcX, srcY, srcW, srcH, 0)
            fnQWidgetRender?.invoke(widget, painter, offset.ptr, region, renderFlags)
            fnQRegionDtor?.invoke(region)
        }
    }

    fun setWidgetGraphicsBlur(widget: COpaquePointer, blurPx: Float) {
        val fnSet = fnQWidgetSetGraphicsEffect ?: return
        if (blurPx <= 0.1f) {
            fnSet.invoke(widget, null)
            return
        }
        val ctor = fnQGraphicsBlurCtor ?: return
        val eff = allocRawObject()
        ctor.invoke(eff, widget)
        fnQGraphicsBlurSetRadius?.invoke(eff, blurPx.toDouble())
        fnSet.invoke(widget, eff)
    }

    // --- QWidget direct QObject child tree traversal for backdrop capture ---
    /**
     * Returns direct child QObject pointers of [parentObj] matching QObjectData::children ABI (`d_ptr + 32`),
     * safely verifying each pointer is a widget (`isWidgetType`: QWidget vptr check via `d_ptr` or registered handle).
     */
    fun getQObjectChildPointers(parentObj: COpaquePointer): List<COpaquePointer> {
        // QObject layout: [0]=vptr, [1]=QObjectData* d_ptr
        val objSlots = parentObj.reinterpret<LongVar>()
        val dPtrLong = objSlots[1]
        if (dPtrLong == 0L) return emptyList()
        // Qt 6 QObjectData: [0]=vptr, [1]=q_ptr, [2]=parent, [3..5]=QObjectList children (d, ptr, size)
        val dSlots = dPtrLong.toCPointer<LongVar>() ?: return emptyList()
        val listPtrLong = dSlots[4] // QObjectData::children.ptr
        val listSize = dSlots[5].toInt() // QObjectData::children.size
        if (listPtrLong == 0L || listSize <= 0 || listSize > 16384) return emptyList()
        val arr = listPtrLong.toCPointer<LongVar>() ?: return emptyList()
        val result = ArrayList<COpaquePointer>(listSize)
        for (i in 0 until listSize) {
            val childLong = arr[i]
            if (childLong != 0L) {
                val cPtr = childLong.toCPointer<ByteVar>()
                if (cPtr != null) result.add(cPtr)
            }
        }
        return result
    }

    fun getQObjectParentPtr(qObj: COpaquePointer): COpaquePointer? {
        val objSlots = qObj.reinterpret<LongVar>()
        val dPtrLong = objSlots[1]
        if (dPtrLong == 0L) return null
        val dSlots = dPtrLong.toCPointer<LongVar>() ?: return null
        val pLong = dSlots[2]
        if (pLong == 0L) return null
        return pLong.toCPointer<ByteVar>()
    }

    // --- QImage / QPainter wrappers ---
    fun createQImage(width: Int, height: Int, format: Int = IMAGE_FORMAT_ARGB32_PREMULTIPLIED): COpaquePointer {
        ensureLoaded()
        val img = nativeHeap.allocArray<LongVar>(4)
        fnQImageCtorEmpty!!.invoke(img, width.coerceAtLeast(1), height.coerceAtLeast(1), format)
        return img
    }

    fun createZeroCopyBgraQImage(
        dataPtr: COpaquePointer,
        width: Int,
        height: Int,
        strideBytes: Int,
        ignoreAlpha: Boolean = true
    ): COpaquePointer? {
        val ctor = fnQImageCtorDataConst ?: return null
        val img = nativeHeap.allocArray<LongVar>(4)
        val format = if (ignoreAlpha) 4 else IMAGE_FORMAT_ARGB32_PREMULTIPLIED
        ctor.invoke(img, dataPtr, width.coerceAtLeast(1), height.coerceAtLeast(1), strideBytes.toLong(), format, null, null)
        return img
    }

    fun destroyQImage(img: COpaquePointer?) {
        if (img == null) return
        fnQImageDtor?.invoke(img)
        nativeHeap.free(img.rawValue)
    }

    fun fillQImage(img: COpaquePointer, argb: UInt) {
        fnQImageFillRgba?.invoke(img, argb)
    }

    fun qImageBits(img: COpaquePointer): COpaquePointer? = fnQImageBits?.invoke(img)
    fun qImageBytesPerLine(img: COpaquePointer): Int = (fnQImageBytesPerLine?.invoke(img) ?: 0L).toInt()

    fun loadQImageFromBytes(encodedBytes: ByteArray): COpaquePointer? {
        val fn = fnQImageLoadFromData ?: return null
        val img = createQImage(1, 1, IMAGE_FORMAT_ARGB32_PREMULTIPLIED)
        val ok = encodedBytes.usePinned { p ->
            fn.invoke(img, p.addressOf(0), encodedBytes.size, null) != 0.toByte()
        }
        return if (ok) img else {
            destroyQImage(img)
            null
        }
    }

    inline fun <T> withQPainter(paintDevicePtr: COpaquePointer, block: (COpaquePointer) -> T): T = memScoped {
        val painter = allocArray<LongVar>(2)
        fnQPainterCtor!!.invoke(painter)
        fnQPainterBegin!!.invoke(painter, paintDevicePtr)
        fnQPainterSetRenderHint?.invoke(painter, RENDER_ANTIALIASING, 1)
        fnQPainterSetRenderHint?.invoke(painter, RENDER_TEXT_ANTIALIASING, 1)
        fnQPainterSetRenderHint?.invoke(painter, RENDER_SMOOTH_PIXMAP_TRANSFORM, 1)
        try {
            block(painter)
        } finally {
            fnQPainterEnd?.invoke(painter)
            fnQPainterDtor?.invoke(painter)
        }
    }

    fun painterSave(p: COpaquePointer) { fnQPainterSave?.invoke(p) }
    fun painterRestore(p: COpaquePointer) { fnQPainterRestore?.invoke(p) }
    fun painterSetOpacity(p: COpaquePointer, opacity: Float) { fnQPainterSetOpacity?.invoke(p, opacity.coerceIn(0f, 1f).toDouble()) }
    fun painterTranslate(p: COpaquePointer, dx: Double, dy: Double) {
        val fn = fnQPainterTranslate ?: return
        memScoped {
            val pt = allocArray<DoubleVar>(2)
            pt[0] = dx
            pt[1] = dy
            fn.invoke(p, pt)
        }
    }
    fun painterScale(p: COpaquePointer, sx: Double, sy: Double) { fnQPainterScale?.invoke(p, sx, sy) }
    fun painterRotate(p: COpaquePointer, deg: Double) { fnQPainterRotate?.invoke(p, deg) }

    fun painterSetTransformMatrix(p: COpaquePointer, a: Double, b: Double, c: Double, d: Double, tx: Double, ty: Double, combine: Boolean = true) {
        val fn = fnQPainterSetTransform ?: return
        val setM = fnQTransformSetMatrix ?: return
        memScoped {
            val xf = allocArray<DoubleVar>(11)
            setM.invoke(xf, a, b, 0.0, c, d, 0.0, tx, ty, 1.0)
            fn.invoke(p, xf, if (combine) 1 else 0)
        }
    }

    fun painterSetClipRect(p: COpaquePointer, x: Double, y: Double, w: Double, h: Double, op: Int = CLIP_INTERSECT) {
        memScoped {
            val r = allocArray<DoubleVar>(4)
            r[0] = x; r[1] = y; r[2] = w; r[3] = h
            fnQPainterSetClipRect?.invoke(p, r, op)
        }
    }

    fun painterSetClipPath(p: COpaquePointer, path: COpaquePointer, op: Int = CLIP_INTERSECT) {
        fnQPainterSetClipPath?.invoke(p, path, op)
    }

    fun painterFillRect(p: COpaquePointer, x: Double, y: Double, w: Double, h: Double, color: RgbaColor) {
        memScoped {
            val r = allocArray<DoubleVar>(4)
            r[0] = x; r[1] = y; r[2] = w; r[3] = h
            val col = allocArray<LongVar>(4)
            setQColorRgba(col, color)
            fnQPainterFillRectColor?.invoke(p, r, col)
        }
    }

    fun painterFillPath(p: COpaquePointer, path: COpaquePointer, color: RgbaColor) {
        memScoped {
            val col = allocArray<LongVar>(4)
            val br = allocArray<LongVar>(2)
            setQColorRgba(col, color)
            fnQBrushCtorColor?.invoke(br, col, BRUSH_SOLID_PATTERN)
            fnQPainterFillPath?.invoke(p, path, br)
            fnQBrushDtor?.invoke(br)
        }
    }

    fun painterFillLinearGradientPath(
        p: COpaquePointer,
        path: COpaquePointer,
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        stops: List<Pair<Double, RgbaColor>>
    ) {
        if (stops.isEmpty()) return
        memScoped {
            val grad = allocArray<LongVar>(12)
            fnQLinearGradCtor?.invoke(grad, x1, y1, x2, y2)
            for ((pos, c) in stops) {
                val col = allocArray<LongVar>(4)
                setQColorRgba(col, c)
                fnQGradientSetColorAt?.invoke(grad, pos.coerceIn(0.0, 1.0), col)
            }
            val br = allocArray<LongVar>(2)
            fnQBrushCtorGradient?.invoke(br, grad)
            fnQPainterFillPath?.invoke(p, path, br)
            fnQBrushDtor?.invoke(br)
            fnQLinearGradDtor?.invoke(grad)
        }
    }

    fun painterFillRadialGradientPath(
        p: COpaquePointer,
        path: COpaquePointer,
        cx: Double,
        cy: Double,
        radius: Double,
        stops: List<Pair<Double, RgbaColor>>
    ) {
        if (stops.isEmpty()) return
        memScoped {
            val grad = allocArray<LongVar>(12)
            fnQRadialGradCtor?.invoke(grad, cx, cy, radius.coerceAtLeast(1.0), cx, cy)
            for ((pos, c) in stops) {
                val col = allocArray<LongVar>(4)
                setQColorRgba(col, c)
                fnQGradientSetColorAt?.invoke(grad, pos.coerceIn(0.0, 1.0), col)
            }
            val br = allocArray<LongVar>(2)
            fnQBrushCtorGradient?.invoke(br, grad)
            fnQPainterFillPath?.invoke(p, path, br)
            fnQBrushDtor?.invoke(br)
            fnQRadialGradDtor?.invoke(grad)
        }
    }

    fun painterStrokePath(
        p: COpaquePointer,
        path: COpaquePointer,
        color: RgbaColor,
        strokeWidthPx: Double,
        penStyle: Int = PEN_SOLID_LINE
    ) {
        memScoped {
            val col = allocArray<LongVar>(4)
            val pen = allocArray<LongVar>(2)
            setQColorRgba(col, color)
            fnQPenCtorColor?.invoke(pen, col)
            fnQPenSetStyle?.invoke(pen, penStyle)
            fnQPenSetWidthF?.invoke(pen, strokeWidthPx)
            fnQPainterStrokePath?.invoke(p, path, pen)
            fnQPenDtor?.invoke(pen)
        }
    }

    fun painterFillRoundedRect(
        p: COpaquePointer,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        radiusPx: Double,
        color: RgbaColor
    ) {
        if (w <= 0.0 || h <= 0.0) return
        val r = radiusPx.coerceAtLeast(0.0)
        withQPainterPath { path ->
            pathAddRoundedRect(path, x, y, w, h, r, r, r, r)
            painterFillPath(p, path, color)
        }
    }

    fun painterStrokeRoundedRect(
        p: COpaquePointer,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        radiusPx: Double,
        color: RgbaColor,
        strokeWidthPx: Double = 1.0,
        penStyle: Int = PEN_SOLID_LINE
    ) {
        if (w <= 0.0 || h <= 0.0) return
        val r = radiusPx.coerceAtLeast(0.0)
        withQPainterPath { path ->
            pathAddRoundedRect(path, x, y, w, h, r, r, r, r)
            painterStrokePath(p, path, color, strokeWidthPx, penStyle)
        }
    }

    fun painterDrawLine(p: COpaquePointer, x1: Double, y1: Double, x2: Double, y2: Double, color: RgbaColor, widthPx: Double = 1.0, penStyle: Int = PEN_SOLID_LINE) {
        memScoped {
            val col = allocArray<LongVar>(4)
            val pen = allocArray<LongVar>(2)
            val line = allocArray<DoubleVar>(4)
            setQColorRgba(col, color)
            fnQPenCtorColor?.invoke(pen, col)
            fnQPenSetStyle?.invoke(pen, penStyle)
            fnQPenSetWidthF?.invoke(pen, widthPx)
            fnQPainterSetPenObj?.invoke(p, pen)
            line[0] = x1; line[1] = y1; line[2] = x2; line[3] = y2
            fnQPainterDrawLines?.invoke(p, line, 1)
            fnQPenDtor?.invoke(pen)
        }
    }

    fun painterDrawEllipse(p: COpaquePointer, x: Double, y: Double, w: Double, h: Double, fill: RgbaColor?, stroke: RgbaColor? = null, strokeWidthPx: Double = 1.0) {
        memScoped {
            val r = allocArray<DoubleVar>(4)
            r[0] = x; r[1] = y; r[2] = w; r[3] = h
            val colF = allocArray<LongVar>(4)
            val br = allocArray<LongVar>(2)
            if (fill != null) {
                setQColorRgba(colF, fill)
                fnQBrushCtorColor?.invoke(br, colF, BRUSH_SOLID_PATTERN)
                fnQPainterSetBrushObj?.invoke(p, br)
            } else {
                fnQPainterSetBrushStyle?.invoke(p, 0)
            }
            val colS = allocArray<LongVar>(4)
            val pen = allocArray<LongVar>(2)
            if (stroke != null) {
                setQColorRgba(colS, stroke)
                fnQPenCtorColor?.invoke(pen, colS)
                fnQPenSetWidthF?.invoke(pen, strokeWidthPx)
                fnQPainterSetPenObj?.invoke(p, pen)
            } else {
                fnQPainterSetPenStyle?.invoke(p, PEN_NO_PEN)
            }
            fnQPainterDrawEllipse?.invoke(p, r)
            if (stroke != null) fnQPenDtor?.invoke(pen)
            if (fill != null) fnQBrushDtor?.invoke(br)
        }
    }

    fun painterDrawImageRect(
        p: COpaquePointer,
        dstX: Double,
        dstY: Double,
        dstW: Double,
        dstH: Double,
        qImage: COpaquePointer,
        srcX: Double,
        srcY: Double,
        srcW: Double,
        srcH: Double
    ) {
        memScoped {
            val dst = allocArray<DoubleVar>(4)
            val src = allocArray<DoubleVar>(4)
            dst[0] = dstX; dst[1] = dstY; dst[2] = dstW; dst[3] = dstH
            src[0] = srcX; src[1] = srcY; src[2] = srcW; src[3] = srcH
            fnQPainterDrawImageRects?.invoke(p, dst, qImage, src, 0)
        }
    }

    fun painterDrawText(
        p: COpaquePointer,
        text: String,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        color: RgbaColor,
        fontSizePx: Float = 14f,
        fontWeight: Int = 400,
        italic: Boolean = false,
        fontFamily: String? = null,
        alignmentFlags: Int = ALIGN_CENTER
    ) {
        memScoped {
            val font = allocArray<LongVar>(4)
            fnQFontCtor?.invoke(font)
            if (!fontFamily.isNullOrBlank()) {
                withTempQString(fontFamily) { fam -> fnQFontSetFamily?.invoke(font, fam) }
            }
            fnQFontSetPixelSize?.invoke(font, fontSizePx.toInt().coerceAtLeast(1))
            fnQFontSetWeight?.invoke(font, fontWeight)
            if (italic) fnQFontSetStyle?.invoke(font, 1)
            fnQPainterSetFont?.invoke(p, font)

            val col = allocArray<LongVar>(4)
            val pen = allocArray<LongVar>(2)
            setQColorRgba(col, color)
            fnQPenCtorColor?.invoke(pen, col)
            fnQPainterSetPenObj?.invoke(p, pen)

            val r = allocArray<DoubleVar>(4)
            r[0] = x; r[1] = y; r[2] = w; r[3] = h
            withTempQString(text) { qText ->
                fnQPainterDrawTextRect?.invoke(p, r, alignmentFlags, qText, null)
            }
            fnQPenDtor?.invoke(pen)
            fnQFontDtor?.invoke(font)
        }
    }

    fun measureTextSize(
        text: String,
        fontSizePx: Float,
        fontWeight: Int = 400,
        italic: Boolean = false,
        fontFamily: String? = null
    ): Pair<Double, Double> {
        ensureLoaded()
        return memScoped {
            val font = allocArray<LongVar>(4)
            fnQFontCtor?.invoke(font)
            if (!fontFamily.isNullOrBlank()) {
                withTempQString(fontFamily) { fam -> fnQFontSetFamily?.invoke(font, fam) }
            }
            fnQFontSetPixelSize?.invoke(font, fontSizePx.toInt().coerceAtLeast(1))
            fnQFontSetWeight?.invoke(font, fontWeight)
            if (italic) fnQFontSetStyle?.invoke(font, 1)

            val fm = allocArray<LongVar>(2)
            fnQFontMetricsFCtor?.invoke(fm, font)
            val width = withTempQString(text) { qText ->
                fnQFontMetricsFHorizAdvance?.invoke(fm, qText, text.length) ?: (text.length * fontSizePx * 0.55)
            }
            val height = fnQFontMetricsFHeight?.invoke(fm) ?: (fontSizePx * 1.25)
            fnQFontMetricsFDtor?.invoke(fm)
            fnQFontDtor?.invoke(font)
            width to height
        }
    }

    // --- QPainterPath helper ---
    inline fun <T> withQPainterPath(block: (COpaquePointer) -> T): T = memScoped {
        val path = allocArray<LongVar>(2)
        fnQPathCtor!!.invoke(path)
        fnQPathSetFillRule?.invoke(path, FILL_WINDING)
        try {
            block(path)
        } finally {
            fnQPathDtor?.invoke(path)
        }
    }

    fun pathAddRoundedRect(
        path: COpaquePointer,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        tl: Double,
        tr: Double,
        br: Double,
        bl: Double
    ) {
        if (w <= 0.0 || h <= 0.0) return
        val maxR = minOf(w, h) / 2.0
        val rtl = tl.coerceIn(0.0, maxR)
        val rtr = tr.coerceIn(0.0, maxR)
        val rbr = br.coerceIn(0.0, maxR)
        val rbl = bl.coerceIn(0.0, maxR)
        memScoped {
            val r = allocArray<DoubleVar>(4)
            if (rtl == rtr && rtr == rbr && rbr == rbl) {
                r[0] = x; r[1] = y; r[2] = w; r[3] = h
                if (rtl <= 0.01) fnQPathAddRect?.invoke(path, r)
                else fnQPathAddRoundedRect?.invoke(path, r, rtl, rtl, 0 /*AbsoluteSize*/)
                return
            }
            val pt = allocArray<DoubleVar>(2)
            pt[0] = x + rtl; pt[1] = y
            fnQPathMoveTo?.invoke(path, pt)
            pt[0] = x + w - rtr; pt[1] = y
            fnQPathLineTo?.invoke(path, pt)
            if (rtr > 0.01) {
                r[0] = x + w - 2.0 * rtr; r[1] = y; r[2] = 2.0 * rtr; r[3] = 2.0 * rtr
                fnQPathArcTo?.invoke(path, r, 90.0, -90.0)
            }
            pt[0] = x + w; pt[1] = y + h - rbr
            fnQPathLineTo?.invoke(path, pt)
            if (rbr > 0.01) {
                r[0] = x + w - 2.0 * rbr; r[1] = y + h - 2.0 * rbr; r[2] = 2.0 * rbr; r[3] = 2.0 * rbr
                fnQPathArcTo?.invoke(path, r, 0.0, -90.0)
            }
            pt[0] = x + rbl; pt[1] = y + h
            fnQPathLineTo?.invoke(path, pt)
            if (rbl > 0.01) {
                r[0] = x; r[1] = y + h - 2.0 * rbl; r[2] = 2.0 * rbl; r[3] = 2.0 * rbl
                fnQPathArcTo?.invoke(path, r, 270.0, -90.0)
            }
            pt[0] = x; pt[1] = y + rtl
            fnQPathLineTo?.invoke(path, pt)
            if (rtl > 0.01) {
                r[0] = x; r[1] = y; r[2] = 2.0 * rtl; r[3] = 2.0 * rtl
                fnQPathArcTo?.invoke(path, r, 180.0, -90.0)
            }
            fnQPathCloseSubpath?.invoke(path)
        }
    }

    // --- Widget-specific Mutators ---
    fun setWidgetFontStyle(widget: COpaquePointer, fontFamily: String?, fontSizePx: Float?, bold: Boolean, italic: Boolean) {
        memScoped {
            val font = allocArray<LongVar>(4)
            fnQFontCtor?.invoke(font)
            if (!fontFamily.isNullOrBlank()) {
                withTempQString(fontFamily) { q -> fnQFontSetFamily?.invoke(font, q) }
            }
            if (fontSizePx != null && fontSizePx > 0f) {
                fnQFontSetPixelSize?.invoke(font, fontSizePx.toInt().coerceAtLeast(1))
            }
            fnQFontSetWeight?.invoke(font, if (bold) 700 else 400)
            if (italic) fnQFontSetStyle?.invoke(font, 1)
            fnQWidgetSetFont?.invoke(widget, font)
            fnQFontDtor?.invoke(font)
        }
    }

    fun setAbstractButtonText(btn: COpaquePointer, text: String) {
        withTempQString(text) { q -> fnQAbstractBtnSetText?.invoke(btn, q) }
    }

    fun setAbstractButtonChecked(btn: COpaquePointer, checked: Boolean) {
        fnQAbstractBtnSetCheckable?.invoke(btn, 1)
        fnQAbstractBtnSetChecked?.invoke(btn, if (checked) 1 else 0)
    }

    fun isAbstractButtonChecked(btn: COpaquePointer): Boolean =
        (fnQAbstractBtnIsChecked?.invoke(btn) ?: 0.toByte()) != 0.toByte()

    fun setCheckBoxState(cb: COpaquePointer, checked: Boolean) {
        fnQCheckBoxSetCheckState?.invoke(cb, if (checked) 2 /*Checked*/ else 0 /*Unchecked*/)
    }

    fun setPushButtonDefault(btn: COpaquePointer, isDefault: Boolean) {
        fnQPushButtonSetDefault?.invoke(btn, if (isDefault) 1 else 0)
    }

    fun setPushButtonFlat(btn: COpaquePointer, flat: Boolean) {
        fnQPushButtonSetFlat?.invoke(btn, if (flat) 1 else 0)
    }

    fun setPushButtonMenu(btn: COpaquePointer, menu: COpaquePointer?) {
        fnQPushButtonSetMenu?.invoke(btn, menu)
    }

    fun applyWidgetButtonPalette(
        widget: COpaquePointer,
        buttonBg: RgbaColor?,
        buttonFg: RgbaColor?,
        disabledFg: RgbaColor? = null
    ) {
        if (!ensureLoaded()) return
        memScoped {
            val pal = allocArray<LongVar>(4)
            fnQPaletteCtor?.invoke(pal)
            try {
                fun setRole(group: Int, role: Int, rgba: RgbaColor) {
                    val col = allocArray<LongVar>(4)
                    val br = allocArray<LongVar>(2)
                    setQColorRgba(col, rgba)
                    fnQBrushCtorColor?.invoke(br, col, BRUSH_SOLID_PATTERN)
                    fnQPaletteSetBrush?.invoke(pal, group, role, br)
                    fnQBrushDtor?.invoke(br)
                }
                for (group in intArrayOf(0 /*Active*/, 2 /*Inactive*/)) {
                    if (buttonBg != null) setRole(group, 1 /*Button*/, buttonBg)
                    if (buttonFg != null) {
                        setRole(group, 8 /*ButtonText*/, buttonFg)
                        setRole(group, 0 /*WindowText*/, buttonFg)
                    }
                }
                if (disabledFg != null) {
                    setRole(1 /*Disabled*/, 8 /*ButtonText*/, disabledFg)
                }
                fnQWidgetSetPalette?.invoke(widget, pal)
            } finally {
                fnQPaletteDtor?.invoke(pal)
            }
        }
    }

    fun setToolButtonPopupMode(btn: COpaquePointer, mode: Int) {
        fnQToolButtonSetPopupMode?.invoke(btn, mode)
    }

    fun setToolButtonMenu(btn: COpaquePointer, menu: COpaquePointer?) {
        fnQToolButtonSetMenu?.invoke(btn, menu)
    }

    fun setToolButtonDefaultAction(btn: COpaquePointer, action: COpaquePointer?) {
        fnQToolButtonSetDefaultAction?.invoke(btn, action)
    }

    fun setComboBoxOptions(combo: COpaquePointer, options: List<Pair<String, String>>, editable: Boolean, selectedIndex: Int) {
        fnQComboBoxSetEditable?.invoke(combo, if (editable) 1 else 0)
        fnQComboBoxClear?.invoke(combo)
        memScoped {
            val icon = allocArray<LongVar>(2)
            fnQIconCtorEmpty?.invoke(icon)
            val variant = allocArray<LongVar>(4)
            for (i in 0 until 4) variant[i] = 0L
            for ((idx, pair) in options.withIndex()) {
                withTempQString(pair.second) { qLabel ->
                    fnQComboBoxInsertItem?.invoke(combo, idx, icon, qLabel, variant)
                }
            }
            fnQIconDtor?.invoke(icon)
        }
        if (selectedIndex in options.indices) {
            fnQComboBoxSetCurrentIndex?.invoke(combo, selectedIndex)
        }
    }

    fun getComboBoxCurrentIndex(combo: COpaquePointer): Int = fnQComboBoxCurrentIndex?.invoke(combo) ?: -1
    fun setComboBoxCurrentIndex(combo: COpaquePointer, idx: Int) { fnQComboBoxSetCurrentIndex?.invoke(combo, idx) }

    fun setWidgetStyleSheet(widget: COpaquePointer, css: String) {
        withTempQString(css) { q -> fnQWidgetSetStyleSheet?.invoke(widget, q) }
    }

    fun setWidgetAutoFillBackground(widget: COpaquePointer, enabled: Boolean) {
        fnQWidgetSetAutoFillBackground?.invoke(widget, if (enabled) 1 else 0)
    }

    fun setLineEditFrame(le: COpaquePointer, hasFrame: Boolean) {
        fnQLineEditSetFrame?.invoke(le, if (hasFrame) 1 else 0)
    }

    fun setFrameShape(frame: COpaquePointer, shape: Int) {
        fnQFrameSetShape?.invoke(frame, shape)
    }

    fun applyStyledInputPalette(widget: COpaquePointer, textFg: RgbaColor, placeholderFg: RgbaColor) {
        if (!ensureLoaded()) return
        memScoped {
            val pal = allocArray<LongVar>(4)
            fnQPaletteCtor?.invoke(pal)
            try {
                fun setRole(group: Int, role: Int, rgba: RgbaColor) {
                    val col = allocArray<LongVar>(4)
                    val br = allocArray<LongVar>(2)
                    setQColorRgba(col, rgba)
                    fnQBrushCtorColor?.invoke(br, col, BRUSH_SOLID_PATTERN)
                    fnQPaletteSetBrush?.invoke(pal, group, role, br)
                    fnQBrushDtor?.invoke(br)
                }
                val transparent = RgbaColor(0, 0, 0, 0)
                for (group in intArrayOf(0 /*Active*/, 1 /*Disabled*/, 2 /*Inactive*/)) {
                    setRole(group, 9 /*Base*/, transparent)
                    setRole(group, 10 /*Window*/, transparent)
                    setRole(group, 6 /*Text*/, textFg)
                    setRole(group, 0 /*WindowText*/, textFg)
                    setRole(group, 20 /*PlaceholderText*/, placeholderFg)
                }
                fnQWidgetSetPalette?.invoke(widget, pal)
            } finally {
                fnQPaletteDtor?.invoke(pal)
            }
        }
    }

    fun setLineEditState(le: COpaquePointer, text: String, placeholder: String, readOnly: Boolean, password: Boolean) {
        withTempQString(text) { q -> fnQLineEditSetText?.invoke(le, q) }
        withTempQString(placeholder) { q -> fnQLineEditSetPlaceholder?.invoke(le, q) }
        fnQLineEditSetReadOnly?.invoke(le, if (readOnly) 1 else 0)
        fnQLineEditSetEchoMode?.invoke(le, if (password) ECHO_PASSWORD else ECHO_NORMAL)
    }

    fun setPlainTextEditState(te: COpaquePointer, text: String, placeholder: String, readOnly: Boolean) {
        withTempQString(text) { q -> fnQPlainTextSetText?.invoke(te, q) }
        withTempQString(placeholder) { q -> fnQPlainTextSetPlaceholder?.invoke(te, q) }
        fnQPlainTextSetReadOnly?.invoke(te, if (readOnly) 1 else 0)
    }

    fun readPlainTextEditText(te: COpaquePointer): String {
        val doc = fnQPlainTextDocument?.invoke(te) ?: return ""
        val count = (fnQTextDocCharCount?.invoke(doc) ?: 0)
        if (count <= 0) return ""
        val fnAt = fnQTextDocCharAt ?: return ""
        val sb = StringBuilder(count)
        for (i in 0 until count) {
            val ch = fnAt.invoke(doc, i).toInt().toChar()
            if (i == count - 1 && (ch == '\u2029' || ch == '\u0000')) continue
            sb.append(if (ch == '\u2029') '\n' else ch)
        }
        return sb.toString()
    }

    fun setDoubleSpinBoxState(sb: COpaquePointer, minVal: Double, maxVal: Double, step: Double, value: Double) {
        fnQDoubleSpinBoxSetRange?.invoke(sb, minVal, maxVal)
        fnQDoubleSpinBoxSetSingleStep?.invoke(sb, step.coerceAtLeast(0.0001))
        fnQDoubleSpinBoxSetValue?.invoke(sb, value.coerceIn(minVal, maxVal))
    }

    fun getDoubleSpinBoxValue(sb: COpaquePointer): Double = fnQDoubleSpinBoxValue?.invoke(sb) ?: 0.0

    fun setDateEditDate(de: COpaquePointer, year: Int, month: Int, day: Int) {
        withTempQString("yyyy-MM-dd") { fmt -> fnQDateTimeEditSetDisplayFmt?.invoke(de, fmt) }
        fnQDateTimeEditSetDate?.invoke(de, encodeQDateJulianDay(year, month, day))
    }

    fun setTimeEditTime(te: COpaquePointer, hour: Int, minute: Int, second: Int = 0) {
        withTempQString("HH:mm") { fmt -> fnQDateTimeEditSetDisplayFmt?.invoke(te, fmt) }
        fnQDateTimeEditSetTime?.invoke(te, encodeQTimeMs(hour, minute, second))
    }

    fun setSliderState(slider: COpaquePointer, min: Int, max: Int, step: Int, value: Int) {
        fnQAbstractSliderSetRange?.invoke(slider, min, max.coerceAtLeast(min + 1))
        fnQAbstractSliderSetSingleStep?.invoke(slider, step.coerceAtLeast(1))
        fnQAbstractSliderSetValue?.invoke(slider, value.coerceIn(min, max.coerceAtLeast(min + 1)))
    }

    fun getSliderValue(slider: COpaquePointer): Int = fnQAbstractSliderValue?.invoke(slider) ?: 0

    fun setProgressBarState(pb: COpaquePointer, min: Int, max: Int, value: Int, showText: Boolean = false) {
        fnQProgressBarSetRange?.invoke(pb, min, max)
        fnQProgressBarSetValue?.invoke(pb, value)
        fnQProgressBarSetTextVisible?.invoke(pb, if (showText) 1 else 0)
    }

    fun setLabelState(label: COpaquePointer, text: String, wrap: Boolean = true, alignFlags: Int = ALIGN_LEFT or ALIGN_VCENTER) {
        withTempQString(text) { q -> fnQLabelSetText?.invoke(label, q) }
        fnQLabelSetWordWrap?.invoke(label, if (wrap) 1 else 0)
        fnQLabelSetAlignment?.invoke(label, alignFlags)
    }

    fun setTextBrowserContent(tb: COpaquePointer, content: String, markdown: Boolean) {
        fnQTextEditSetReadOnly?.invoke(tb, 1)
        fnQTextBrowserSetOpenExtLinks?.invoke(tb, 1)
        withTempQString(content) { q ->
            if (markdown) fnQTextEditSetMarkdown?.invoke(tb, q)
            else fnQTextEditSetHtml?.invoke(tb, q)
        }
    }

    fun configureFrameSeparator(frame: COpaquePointer, vertical: Boolean) {
        fnQFrameSetShape?.invoke(frame, if (vertical) FRAME_VLINE else FRAME_HLINE)
        fnQFrameSetShadow?.invoke(frame, 0x0030 /*Sunken*/)
        fnQFrameSetLineWidth?.invoke(frame, 1)
    }

    fun configureScrollArea(sa: COpaquePointer, viewportChild: COpaquePointer, hPolicy: Int = SCROLLBAR_AS_NEEDED, vPolicy: Int = SCROLLBAR_AS_NEEDED) {
        fnQScrollAreaSetWidgetResizable?.invoke(sa, 0)
        fnQScrollAreaSetWidget?.invoke(sa, viewportChild)
        fnQScrollAreaSetHBarPolicy?.invoke(sa, hPolicy)
        fnQScrollAreaSetVBarPolicy?.invoke(sa, vPolicy)
    }

    fun scrollAreaSetWidget(sa: COpaquePointer, viewportChild: COpaquePointer, resizable: Boolean = false) {
        fnQScrollAreaSetWidgetResizable?.invoke(sa, if (resizable) 1 else 0)
        fnQScrollAreaSetWidget?.invoke(sa, viewportChild)
    }

    fun scrollAreaSetScrollBarPolicies(sa: COpaquePointer, hPolicy: Int = SCROLLBAR_AS_NEEDED, vPolicy: Int = SCROLLBAR_AS_NEEDED) {
        fnQScrollAreaSetHBarPolicy?.invoke(sa, hPolicy)
        fnQScrollAreaSetVBarPolicy?.invoke(sa, vPolicy)
    }

    fun scrollAreaVBar(sa: COpaquePointer): COpaquePointer? = fnQScrollAreaVBar?.invoke(sa)
    fun scrollAreaHBar(sa: COpaquePointer): COpaquePointer? = fnQScrollAreaHBar?.invoke(sa)
    fun sliderSetValue(slider: COpaquePointer, value: Int) { fnQAbstractSliderSetValue?.invoke(slider, value) }

    fun setScrollAreaOffsets(sa: COpaquePointer, x: Int, y: Int) {
        fnQScrollAreaHBar?.invoke(sa)?.let { hb -> fnQAbstractSliderSetValue?.invoke(hb, x) }
        fnQScrollAreaVBar?.invoke(sa)?.let { vb -> fnQAbstractSliderSetValue?.invoke(vb, y) }
    }

    fun scrollAreaOffsets(sa: COpaquePointer): Pair<Int, Int> {
        val x = fnQScrollAreaHBar?.invoke(sa)?.let { fnQAbstractSliderValue?.invoke(it) } ?: 0
        val y = fnQScrollAreaVBar?.invoke(sa)?.let { fnQAbstractSliderValue?.invoke(it) } ?: 0
        return x to y
    }

    fun splitterSetOrientation(sp: COpaquePointer, vertical: Boolean) { fnQSplitterSetOrientation?.invoke(sp, if (vertical) VERTICAL else HORIZONTAL) }

    const val TAB_BUTTON_LEFT = 0
    const val TAB_BUTTON_RIGHT = 1

    fun tabBarAddTab(tb: COpaquePointer, title: String): Int = withTempQString(title) { q -> fnQTabBarAddTab?.invoke(tb, q) ?: -1 }
    fun tabBarInsertTab(tb: COpaquePointer, idx: Int, title: String): Int = withTempQString(title) { q -> fnQTabBarInsertTab?.invoke(tb, idx, q) ?: -1 }
    fun tabBarRemoveTab(tb: COpaquePointer, idx: Int) { fnQTabBarRemoveTab?.invoke(tb, idx) }
    fun tabBarSetCurrentIndex(tb: COpaquePointer, idx: Int) { fnQTabBarSetCurrentIndex?.invoke(tb, idx) }
    fun tabBarSetTabText(tb: COpaquePointer, idx: Int, title: String) { withTempQString(title) { q -> fnQTabBarSetTabText?.invoke(tb, idx, q) } }
    fun tabBarSetClosable(tb: COpaquePointer, closable: Boolean) { fnQTabBarSetTabsClosable?.invoke(tb, if (closable) 1 else 0) }
    fun tabBarSetShape(tb: COpaquePointer, shape: Int) { fnQTabBarSetShape?.invoke(tb, shape) }
    fun tabBarSetExpanding(tb: COpaquePointer, exp: Boolean) { fnQTabBarSetExpanding?.invoke(tb, if (exp) 1 else 0) }

    fun tabBarSetIconSize(tb: COpaquePointer, widthPx: Int, heightPx: Int) {
        val fn = fnQTabBarSetIconSize ?: return
        memScoped {
            val sz = alloc<LongVar>()
            sz.value = widthPx.coerceAtLeast(1).toLong() or (heightPx.coerceAtLeast(1).toLong() shl 32)
            fn.invoke(tb, sz.ptr)
        }
    }

    fun tabBarClearTabIcon(tb: COpaquePointer, idx: Int) {
        val fn = fnQTabBarSetTabIcon ?: return
        memScoped {
            val icon = allocArray<LongVar>(2)
            fnQIconCtorEmpty?.invoke(icon)
            fn.invoke(tb, idx, icon)
            fnQIconDtor?.invoke(icon)
        }
    }

    fun tabBarSetTabIconFromBuffer(tb: COpaquePointer, idx: Int, buffer: com.nexustools.genericui.image.ImageBuffer): Boolean {
        val fn = fnQTabBarSetTabIcon ?: return false
        val w = buffer.width
        val h = buffer.height
        if (w <= 0 || h <= 0) return false
        val rgba = buffer.toRgba8888()
        val img = createQImage(w, h, IMAGE_FORMAT_ARGB32_PREMULTIPLIED)
        val bitsPtr = qImageBits(img)
        if (bitsPtr == null) {
            destroyQImage(img)
            return false
        }
        val bpl = qImageBytesPerLine(img).takeIf { it >= w * 4 } ?: (w * 4)
        val src = rgba.bytes
        val rowBuf = IntArray(w)
        for (y in 0 until h) {
            val srcRowBase = y * w * 4
            for (x in 0 until w) {
                val o = srcRowBase + x * 4
                val r = src[o].toInt() and 0xFF
                val g = src[o + 1].toInt() and 0xFF
                val b = src[o + 2].toInt() and 0xFF
                val a = src[o + 3].toInt() and 0xFF
                val pr = if (a == 255) r else (r * a + 127) / 255
                val pg = if (a == 255) g else (g * a + 127) / 255
                val pb = if (a == 255) b else (b * a + 127) / 255
                rowBuf[x] = (a shl 24) or (pr shl 16) or (pg shl 8) or pb
            }
            val dstRow = (bitsPtr.rawValue.toLong() + (y.toLong() * bpl.toLong())).toCPointer<ByteVar>()!!
            rowBuf.usePinned { pinned ->
                memcpy(dstRow, pinned.addressOf(0), (w * 4).convert())
            }
        }
        memScoped {
            val pm = allocArray<LongVar>(4)
            fnQPixmapCtorEmpty?.invoke(pm)
            fnQPixmapConvertFromImage?.invoke(pm, img, 0)
            val icon = allocArray<LongVar>(2)
            fnQIconCtorPixmap?.invoke(icon, pm)
            fn.invoke(tb, idx, icon)
            fnQIconDtor?.invoke(icon)
            fnQPixmapDtor?.invoke(pm)
        }
        destroyQImage(img)
        return true
    }

    fun tabBarHideTabCloseButton(tb: COpaquePointer, idx: Int) {
        fnQTabBarSetTabButton?.invoke(tb, idx, TAB_BUTTON_RIGHT, null)
    }

    fun stackedAddWidget(st: COpaquePointer, w: COpaquePointer): Int = fnQStackedWidgetAddWidget?.invoke(st, w) ?: -1
    fun stackedRemoveWidget(st: COpaquePointer, w: COpaquePointer) { fnQStackedWidgetRemoveWidget?.invoke(st, w) }
    fun stackedSetCurrentIndex(st: COpaquePointer, idx: Int) { fnQStackedWidgetSetCurrentIndex?.invoke(st, idx) }

    fun populateQTableWidget(tw: COpaquePointer, headers: List<String>, rows: List<List<String>>) {
        fnQTableWidgetClear?.invoke(tw)
        val colCount = maxOf(headers.size, rows.maxOfOrNull { it.size } ?: 0)
        fnQTableWidgetSetColCount?.invoke(tw, colCount)
        fnQTableWidgetSetRowCount?.invoke(tw, rows.size)
        fnQItemViewSetEditTriggers?.invoke(tw, 0 /*NoEditTriggers*/)
        fnQItemViewSetSelectionBehavior?.invoke(tw, 1 /*SelectRows*/)
        fnQItemViewSetSelectionMode?.invoke(tw, 1 /*SingleSelection*/)

        fun makeItem(text: String): COpaquePointer {
            val item = allocRawObject(128)
            val ctor = fnQTableItemCtorText
            withTempQString(text) { q ->
                ctor?.invoke(item, q, 0)
            }
            return item
        }
        val hHeader = fnQTableViewHHeader?.invoke(tw)
        if (hHeader != null) {
            fnQHeaderViewSetVisible?.invoke(hHeader, if (headers.isNotEmpty()) 1 else 0)
            for (c in headers.indices) {
                fnQTableWidgetSetHHeaderItem?.invoke(tw, c, makeItem(headers[c]))
                fnQHeaderViewSetResizeMode?.invoke(hHeader, c, HEADER_STRETCH)
            }
        }
        fnQTableViewVHeader?.invoke(tw)?.let { fnQHeaderViewSetVisible?.invoke(it, 0) }
        for (r in rows.indices) {
            val row = rows[r]
            for (c in 0 until colCount) {
                fnQTableWidgetSetItem?.invoke(tw, r, c, makeItem(row.getOrElse(c) { "" }))
            }
        }
    }

    fun tableSetAlternatingRowColors(tw: COpaquePointer, enabled: Boolean) {
        fnQItemViewSetAltRows?.invoke(tw, if (enabled) 1 else 0)
    }

    fun clearQTreeWidget(tree: COpaquePointer) {
        fnQTreeWidgetClear?.invoke(tree)
        fnQTreeWidgetSetColCount?.invoke(tree, 1)
        fnQTreeViewSetHeaderHidden?.invoke(tree, 1)
        fnQTreeViewSetIndentation?.invoke(tree, 18)
    }

    fun createQTreeItem(text: String): COpaquePointer {
        val item = allocRawObject(128)
        fnQTreeItemCtorType?.invoke(item, 0)
        withTempQString(text) { q ->
            memScoped {
                val variantBuf = allocArray<ByteVar>(64)
                fnQVariantCtorString?.invoke(variantBuf, q)
                fnQTreeItemSetData?.invoke(item, 0, 0, variantBuf)
                fnQVariantDtor?.invoke(variantBuf)
            }
        }
        return item
    }

    fun addQTreeTopItem(tree: COpaquePointer, item: COpaquePointer) { fnQTreeWidgetAddTopItem?.invoke(tree, item) }
    fun addQTreeChildItem(parent: COpaquePointer, child: COpaquePointer) { fnQTreeItemAddChild?.invoke(parent, child) }
    fun setQTreeItemExpanded(item: COpaquePointer, expanded: Boolean) { fnQTreeItemSetExpanded?.invoke(item, if (expanded) 1 else 0) }
    fun setQTreeCurrentItem(tree: COpaquePointer, item: COpaquePointer?) { fnQTreeWidgetSetCurrentItem?.invoke(tree, item) }

    fun clearMenuBar(mb: COpaquePointer) { fnQMenuBarClear?.invoke(mb) }
    fun addMenuToMenuBar(mb: COpaquePointer, menu: COpaquePointer): COpaquePointer? = fnQMenuBarAddMenu?.invoke(mb, menu)
    fun clearMenu(menu: COpaquePointer) { fnQMenuClear?.invoke(menu) }
    fun setMenuTitle(menu: COpaquePointer, title: String) { withTempQString(title) { q -> fnQMenuSetTitle?.invoke(menu, q) } }
    fun addSubmenu(parent: COpaquePointer, child: COpaquePointer): COpaquePointer? = fnQMenuAddMenu?.invoke(parent, child)
    fun addMenuAction(menu: COpaquePointer, text: String, checkable: Boolean = false, checked: Boolean = false, enabled: Boolean = true, shortcutText: String? = null): COpaquePointer? {
        val action = withTempQString(text) { q -> fnQMenuAddActionText?.invoke(menu, q) } ?: return null
        setActionState(action, text, checkable, checked, enabled, visible = true, shortcutText = shortcutText)
        return action
    }
    fun setActionState(
        action: COpaquePointer,
        text: String,
        checkable: Boolean,
        checked: Boolean,
        enabled: Boolean,
        visible: Boolean = true,
        shortcutText: String? = null
    ) {
        withTempQString(text) { q -> fnQActionSetText?.invoke(action, q) }
        fnQActionSetCheckable?.invoke(action, if (checkable) 1 else 0)
        fnQActionSetChecked?.invoke(action, if (checked) 1 else 0)
        fnQActionSetEnabled?.invoke(action, if (enabled) 1 else 0)
        fnQActionSetVisible?.invoke(action, if (visible) 1 else 0)
        if (!shortcutText.isNullOrBlank() && fnQKeySequenceCtorText != null && fnQActionSetShortcut != null) {
            withTempQString(shortcutText) { q ->
                memScoped {
                    val seqBuf = allocArray<ByteVar>(32)
                    fnQKeySequenceCtorText?.invoke(seqBuf, q, 0)
                    fnQActionSetShortcut?.invoke(action, seqBuf)
                    fnQKeySequenceDtor?.invoke(seqBuf)
                }
            }
        }
    }
    fun addMenuSeparator(menu: COpaquePointer) { fnQMenuAddSeparator?.invoke(menu) }
    fun popupMenuAt(menu: COpaquePointer, globalX: Int, globalY: Int) {
        memScoped {
            val pt = alloc<LongVar>()
            pt.value = (globalX.toLong() and 0xFFFFFFFFL) or (globalY.toLong() shl 32)
            fnQMenuPopup?.invoke(menu, pt.ptr, null)
        }
    }

    fun showMessageBox(kind: String, owner: COpaquePointer?, title: String, message: String, buttonsMask: Int, defaultBtn: Int): Int {
        ensureQApplication()
        val fn = when (kind) {
            "warning" -> fnQMessageBoxWarn
            "error" -> fnQMessageBoxCrit
            "question" -> fnQMessageBoxQuestion
            else -> fnQMessageBoxInfo
        } ?: return 0
        return withTempQString(title) { t ->
            withTempQString(message) { m ->
                fn.invoke(owner, t, m, buttonsMask, defaultBtn)
            }
        }
    }

    fun execDialog(dlg: COpaquePointer): Int = fnQDialogExec?.invoke(dlg) ?: 0
    fun setDialogModal(dlg: COpaquePointer, modal: Boolean) { fnQDialogSetModal?.invoke(dlg, if (modal) 1 else 0) }
}
