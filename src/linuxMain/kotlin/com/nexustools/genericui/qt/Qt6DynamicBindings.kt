@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.qt

import com.nexustools.genericui.linux.LinuxDynamicLibraryLoader
import com.nexustools.genericui.linux.LinuxReentrantLock
import com.nexustools.genericui.style.color.RgbaColor
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.calloc
import platform.posix.free as posixFree
import platform.posix.getenv
import platform.posix.setenv
import kotlin.math.roundToInt

/**
 * Direct `dlopen`/`dlsym` bindings to Qt 6.10.x (`libQt6Core.so.6`, `libQt6Gui.so.6`, `libQt6Widgets.so.6`)
 * using Qt 6's non-vtable, non-`sret` C++ Itanium ABI surface:
 * - Zero-copy raw 24-byte `QString` views (`d = 0L`) and `_ZN10QArrayData10deallocateEPS_xx` cleanup
 * - `QMetaObject::metacall(ReadProperty)` and `QObjectData::children` traversal for non-`sret` property reads
 * - Caller-owned `Format_ARGB32` `QImage` + `QPainter` + `QPixmap::convertFromImage` + `QLabel::setPixmap`
 * - `QDialog::exec()` for native modal dialogs without `QSlotObjectBase` vtables
 */
internal object Qt6DynamicBindings {
    const val QT_VERSION = 0x060000

    const val CALL_READ_PROPERTY = 1

    const val QIMAGE_FORMAT_ARGB32 = 5

    const val QDIALOG_ACCEPTED = 1

    const val QMSG_ICON_NO_ICON = 0
    const val QMSG_ICON_INFO = 1
    const val QMSG_ICON_WARNING = 2
    const val QMSG_ICON_CRITICAL = 3
    const val QMSG_BTN_OK = 0x00000400
    const val QMSG_BTN_CANCEL = 0x00400000
    const val QMSG_BTN_YES = 0x00004000
    const val QMSG_BTN_NO = 0x00010000

    const val QFILE_MODE_ANY_FILE = 0
    const val QFILE_MODE_EXISTING_FILE = 1
    const val QFILE_MODE_DIRECTORY = 2
    const val QFILE_MODE_EXISTING_FILES = 3
    const val QFILE_ACCEPT_OPEN = 0
    const val QFILE_ACCEPT_SAVE = 1
    const val QFILE_OPT_SHOW_DIRS_ONLY = 0x00000001
    const val QFILE_OPT_DONT_USE_NATIVE = 0x00000008

    const val QCOLOR_OPT_SHOW_ALPHA = 0x00000001
    const val QCOLOR_OPT_DONT_USE_NATIVE = 0x00000004

    const val QPAINTER_HINT_ALL = 0x1 or 0x2 or 0x4 // Antialiasing | TextAntialiasing | SmoothPixmapTransform

    const val QT_ALIGN_LEFT = 0x0001
    const val QT_ALIGN_RIGHT = 0x0002
    const val QT_ALIGN_HCENTER = 0x0004
    const val QT_ALIGN_VCENTER = 0x0080
    const val QT_TEXT_WORD_WRAP = 0x1000

    const val QT_CURSOR_ARROW = 0
    const val QT_CURSOR_CROSS = 2
    const val QT_CURSOR_WAIT = 3
    const val QT_CURSOR_IBEAM = 4
    const val QT_CURSOR_POINTING_HAND = 13

    const val QT_COLOR_SCHEME_DARK = 2

    private val lock = LinuxReentrantLock()
    private var librariesLoaded = false
    private var qtLibrariesAvailable = false

    private var qAppPtr: COpaquePointer? = null
    private var cachedIsXcbPlatform: Boolean? = null
    private var qAppArgcPtr: CPointer<IntVar>? = null
    private var qAppArgvPtr: CPointer<CPointerVar<ByteVar>>? = null
    private var qAppArg0Ptr: CPointer<ByteVar>? = null

    private var fnArrayDataDeallocate: CPointer<CFunction<(COpaquePointer?, Long, Long) -> Unit>>? = null
    private var fnMetaObjectMetacall: CPointer<CFunction<(COpaquePointer?, Int, Int, COpaquePointer?) -> Int>>? = null
    private var fnMetaObjectIndexOfProperty: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> Int>>? = null
    private var fnMetaObjectClassName: CPointer<CFunction<(COpaquePointer?) -> CPointer<ByteVar>?>>? = null
    private var fnCoreAppProcessEvents: CPointer<CFunction<(Int, Int) -> Unit>>? = null
    private var fnCoreAppExit: CPointer<CFunction<(Int) -> Unit>>? = null

    private var fnGuiAppStyleHints: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnGuiAppPrimaryScreen: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnStyleHintsColorScheme: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnGuiAppMouseButtons: CPointer<CFunction<() -> UInt>>? = null
    private var fnGuiAppKeyboardModifiers: CPointer<CFunction<() -> UInt>>? = null
    private var fnGuiAppClipboard: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnClipboardSetText: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, Int) -> Unit>>? = null
    private var fnCursorPos: CPointer<CFunction<() -> Long>>? = null
    private var fnCursorCtor: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnCursorDtor: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnColorSetRgb: CPointer<CFunction<(COpaquePointer?, Int, Int, Int, Int) -> Unit>>? = null
    private var fnIconCtorEmpty: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnIconCtorPixmap: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnIconCtorFile: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnIconDtor: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnFontCtor: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnFontDtor: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnFontSetFamily: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnFontSetPixelSize: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnFontSetWeight: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnFontSetStyle: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null

    private var fnImageCtor: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, Int, Int, Int, COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnImageDtor: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnPixmapCtor: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnPixmapDtor: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnPixmapConvertFromImage: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, Int) -> Byte>>? = null
    private var fnPainterCtor: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnPainterDtor: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnPainterEnd: CPointer<CFunction<(COpaquePointer?) -> Byte>>? = null
    private var fnPainterSetRenderHints: CPointer<CFunction<(COpaquePointer?, Int, Byte) -> Unit>>? = null
    private var fnPainterFillRect: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnPainterSetPen: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnPainterSetBrushStyle: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnPainterSetFont: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnPainterDrawText: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, Int, COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnPainterDrawRoundedRect: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, Double, Double, Int) -> Unit>>? = null

    private var fnQAppCtor: CPointer<CFunction<(COpaquePointer?, CPointer<IntVar>?, CPointer<CPointerVar<ByteVar>>?, Int) -> Unit>>? = null
    private var fnWidgetCtor: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, Int) -> Unit>>? = null
    private var fnWidgetDtor: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnWidgetSetWindowTitle: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnWidgetResize: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnWidgetMove: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnWidgetSetMinSize: CPointer<CFunction<(COpaquePointer?, Int, Int) -> Unit>>? = null
    private var fnWidgetSetMaxSize: CPointer<CFunction<(COpaquePointer?, Int, Int) -> Unit>>? = null
    private var fnWidgetSetGeometry: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnWidgetShow: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnWidgetHide: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnWidgetSetVisible: CPointer<CFunction<(COpaquePointer?, Byte) -> Unit>>? = null
    private var fnWidgetClose: CPointer<CFunction<(COpaquePointer?) -> Byte>>? = null
    private var fnWidgetUpdate: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnWidgetShowFullScreen: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnWidgetShowMaximized: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnWidgetShowMinimized: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnWidgetShowNormal: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnWidgetRaise: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnWidgetActivateWindow: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnWidgetSetWindowModality: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnWidgetSetCursor: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnWidgetSetWindowFlags: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnWidgetSetWindowFlag: CPointer<CFunction<(COpaquePointer?, Int, Byte) -> Unit>>? = null
    private var fnWidgetOverrideWindowFlags: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnWidgetWinId: CPointer<CFunction<(COpaquePointer?) -> ULong>>? = null
    private var fnWidgetIsMaximized: CPointer<CFunction<(COpaquePointer?) -> Byte>>? = null
    private var fnWidgetIsFullScreen: CPointer<CFunction<(COpaquePointer?) -> Byte>>? = null

    private var fnLabelCtor: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, Int) -> Unit>>? = null
    private var fnLabelDtor: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnLabelSetPixmap: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnLabelSetText: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null

    private var fnDialogCtor: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, Int) -> Unit>>? = null
    private var fnDialogDtor: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnDialogExec: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null

    private var fnMsgBoxCtor: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnMsgBoxDtor: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnMsgBoxSetText: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnMsgBoxSetInfoText: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnMsgBoxSetIcon: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnMsgBoxSetStdButtons: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnMsgBoxSetDefaultBtn: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null

    private var fnFileDlgCtor: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnFileDlgDtor: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnFileDlgSetFileMode: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnFileDlgSetAcceptMode: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null
    private var fnFileDlgSetDirectory: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnFileDlgSetNameFilter: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnFileDlgSetOption: CPointer<CFunction<(COpaquePointer?, Int, Byte) -> Unit>>? = null

    private var fnColorDlgCtor: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnColorDlgDtor: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnColorDlgSetCurrent: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnColorDlgSetOption: CPointer<CFunction<(COpaquePointer?, Int, Byte) -> Unit>>? = null

    private var fnInputDlgCtor: CPointer<CFunction<(COpaquePointer?, COpaquePointer?, Int) -> Unit>>? = null
    private var fnInputDlgDtor: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnInputDlgSetLabelText: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnInputDlgSetTextValue: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null

    private var fnComboCtor: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnComboDtor: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnComboInsertItem: CPointer<CFunction<(COpaquePointer?, Int, COpaquePointer?, COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnComboSetCurrentIndex: CPointer<CFunction<(COpaquePointer?, Int) -> Unit>>? = null

    private var fnPushBtnCtor: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnPushBtnDtor: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnAbstractBtnSetText: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null

    private var fnTrayCtor: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnTrayDtor: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnTrayIsAvailable: CPointer<CFunction<() -> Byte>>? = null
    private var fnTraySetIcon: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnTraySetToolTip: CPointer<CFunction<(COpaquePointer?, COpaquePointer?) -> Unit>>? = null
    private var fnTraySetVisible: CPointer<CFunction<(COpaquePointer?, Byte) -> Unit>>? = null

    fun ensureLibrariesLoaded(): Boolean = lock.withLock {
        if (librariesLoaded) return qtLibrariesAvailable
        librariesLoaded = true

        val core = LinuxDynamicLibraryLoader.openFirst("libQt6Core.so.6", "libQt6Core.so", global = true)
        val gui = LinuxDynamicLibraryLoader.openFirst("libQt6Gui.so.6", "libQt6Gui.so", global = true)
        val widgets = LinuxDynamicLibraryLoader.openFirst("libQt6Widgets.so.6", "libQt6Widgets.so", global = true)

        if (core != null) {
            fnArrayDataDeallocate = LinuxDynamicLibraryLoader.symbol(core, "_ZN10QArrayData10deallocateEPS_xx")?.reinterpret()
            fnMetaObjectMetacall = LinuxDynamicLibraryLoader.symbol(core, "_ZN11QMetaObject8metacallEP7QObjectNS_4CallEiPPv")?.reinterpret()
            fnMetaObjectIndexOfProperty = LinuxDynamicLibraryLoader.symbol(core, "_ZNK11QMetaObject15indexOfPropertyEPKc")?.reinterpret()
            fnMetaObjectClassName = LinuxDynamicLibraryLoader.symbol(core, "_ZNK11QMetaObject9classNameEv")?.reinterpret()
            fnCoreAppProcessEvents = LinuxDynamicLibraryLoader.symbol(core, "_ZN16QCoreApplication13processEventsE6QFlagsIN10QEventLoop17ProcessEventsFlagEEi")?.reinterpret()
            fnCoreAppExit = LinuxDynamicLibraryLoader.symbol(core, "_ZN16QCoreApplication4exitEi")?.reinterpret()
        }

        if (gui != null) {
            fnGuiAppStyleHints = LinuxDynamicLibraryLoader.symbol(gui, "_ZN15QGuiApplication10styleHintsEv")?.reinterpret()
            fnGuiAppPrimaryScreen = LinuxDynamicLibraryLoader.symbol(gui, "_ZN15QGuiApplication13primaryScreenEv")?.reinterpret()
            fnStyleHintsColorScheme = LinuxDynamicLibraryLoader.symbol(gui, "_ZNK11QStyleHints11colorSchemeEv")?.reinterpret()
            fnGuiAppMouseButtons = LinuxDynamicLibraryLoader.symbol(gui, "_ZN15QGuiApplication12mouseButtonsEv")?.reinterpret()
            fnGuiAppKeyboardModifiers = LinuxDynamicLibraryLoader.symbol(gui, "_ZN15QGuiApplication17keyboardModifiersEv")?.reinterpret()
            fnGuiAppClipboard = LinuxDynamicLibraryLoader.symbol(gui, "_ZN15QGuiApplication9clipboardEv")?.reinterpret()
            fnClipboardSetText = LinuxDynamicLibraryLoader.symbol(gui, "_ZN10QClipboard7setTextERK7QStringNS_4ModeE")?.reinterpret()
            fnCursorPos = LinuxDynamicLibraryLoader.symbol(gui, "_ZN7QCursor3posEv")?.reinterpret()
            fnCursorCtor = LinuxDynamicLibraryLoader.symbol(gui, "_ZN7QCursorC1EN2Qt11CursorShapeE")?.reinterpret()
            fnCursorDtor = LinuxDynamicLibraryLoader.symbol(gui, "_ZN7QCursorD1Ev")?.reinterpret()
            fnColorSetRgb = LinuxDynamicLibraryLoader.symbol(gui, "_ZN6QColor6setRgbEiiii")?.reinterpret()
            fnIconCtorEmpty = LinuxDynamicLibraryLoader.symbol(gui, "_ZN5QIconC1Ev")?.reinterpret()
            fnIconCtorPixmap = LinuxDynamicLibraryLoader.symbol(gui, "_ZN5QIconC1ERK7QPixmap")?.reinterpret()
            fnIconCtorFile = LinuxDynamicLibraryLoader.symbol(gui, "_ZN5QIconC1ERK7QString")?.reinterpret()
            fnIconDtor = LinuxDynamicLibraryLoader.symbol(gui, "_ZN5QIconD1Ev")?.reinterpret()
            fnFontCtor = LinuxDynamicLibraryLoader.symbol(gui, "_ZN5QFontC1Ev")?.reinterpret()
            fnFontDtor = LinuxDynamicLibraryLoader.symbol(gui, "_ZN5QFontD1Ev")?.reinterpret()
            fnFontSetFamily = LinuxDynamicLibraryLoader.symbol(gui, "_ZN5QFont9setFamilyERK7QString")?.reinterpret()
            fnFontSetPixelSize = LinuxDynamicLibraryLoader.symbol(gui, "_ZN5QFont12setPixelSizeEi")?.reinterpret()
            fnFontSetWeight = LinuxDynamicLibraryLoader.symbol(gui, "_ZN5QFont9setWeightENS_6WeightE")?.reinterpret()
            fnFontSetStyle = LinuxDynamicLibraryLoader.symbol(gui, "_ZN5QFont8setStyleENS_5StyleE")?.reinterpret()
            fnImageCtor = LinuxDynamicLibraryLoader.symbol(gui, "_ZN6QImageC1EPhiiNS_6FormatEPFvPvES2_")?.reinterpret()
            fnImageDtor = LinuxDynamicLibraryLoader.symbol(gui, "_ZN6QImageD1Ev")?.reinterpret()
            fnPixmapCtor = LinuxDynamicLibraryLoader.symbol(gui, "_ZN7QPixmapC1Ev")?.reinterpret()
            fnPixmapDtor = LinuxDynamicLibraryLoader.symbol(gui, "_ZN7QPixmapD1Ev")?.reinterpret()
            fnPixmapConvertFromImage = LinuxDynamicLibraryLoader.symbol(gui, "_ZN7QPixmap16convertFromImageERK6QImage6QFlagsIN2Qt19ImageConversionFlagEE")?.reinterpret()
            fnPainterCtor = LinuxDynamicLibraryLoader.symbol(gui, "_ZN8QPainterC1EP12QPaintDevice")?.reinterpret()
            fnPainterDtor = LinuxDynamicLibraryLoader.symbol(gui, "_ZN8QPainterD1Ev")?.reinterpret()
            fnPainterEnd = LinuxDynamicLibraryLoader.symbol(gui, "_ZN8QPainter3endEv")?.reinterpret()
            fnPainterSetRenderHints = LinuxDynamicLibraryLoader.symbol(gui, "_ZN8QPainter14setRenderHintsE6QFlagsINS_10RenderHintEEb")?.reinterpret()
            fnPainterFillRect = LinuxDynamicLibraryLoader.symbol(gui, "_ZN8QPainter8fillRectERK5QRectRK6QColor")?.reinterpret()
            fnPainterSetPen = LinuxDynamicLibraryLoader.symbol(gui, "_ZN8QPainter6setPenERK6QColor")?.reinterpret()
            fnPainterSetBrushStyle = LinuxDynamicLibraryLoader.symbol(gui, "_ZN8QPainter8setBrushEN2Qt10BrushStyleE")?.reinterpret()
            fnPainterSetFont = LinuxDynamicLibraryLoader.symbol(gui, "_ZN8QPainter7setFontERK5QFont")?.reinterpret()
            fnPainterDrawText = LinuxDynamicLibraryLoader.symbol(gui, "_ZN8QPainter8drawTextERK5QRectiRK7QStringPS0_")?.reinterpret()
            fnPainterDrawRoundedRect = LinuxDynamicLibraryLoader.symbol(gui, "_ZN8QPainter15drawRoundedRectERK6QRectFddN2Qt8SizeModeE")?.reinterpret()
        }

        if (widgets != null) {
            fnQAppCtor = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN12QApplicationC1ERiPPci")?.reinterpret()
            fnWidgetCtor = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QWidgetC1EPS_6QFlagsIN2Qt10WindowTypeEE")?.reinterpret()
            fnWidgetDtor = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QWidgetD1Ev")?.reinterpret()
            fnWidgetSetWindowTitle = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QWidget14setWindowTitleERK7QString")?.reinterpret()
            fnWidgetResize = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QWidget6resizeERK5QSize")?.reinterpret()
            fnWidgetMove = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QWidget4moveERK6QPoint")?.reinterpret()
            fnWidgetSetMinSize = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QWidget14setMinimumSizeEii")?.reinterpret()
            fnWidgetSetMaxSize = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QWidget14setMaximumSizeEii")?.reinterpret()
            fnWidgetSetGeometry = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QWidget11setGeometryERK5QRect")?.reinterpret()
            fnWidgetShow = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QWidget4showEv")?.reinterpret()
            fnWidgetHide = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QWidget4hideEv")?.reinterpret()
            fnWidgetSetVisible = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QWidget10setVisibleEb")?.reinterpret()
            fnWidgetClose = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QWidget5closeEv")?.reinterpret()
            fnWidgetUpdate = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QWidget6updateEv")?.reinterpret()
            fnWidgetShowFullScreen = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QWidget14showFullScreenEv")?.reinterpret()
            fnWidgetShowMaximized = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QWidget13showMaximizedEv")?.reinterpret()
            fnWidgetShowMinimized = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QWidget13showMinimizedEv")?.reinterpret()
            fnWidgetShowNormal = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QWidget10showNormalEv")?.reinterpret()
            fnWidgetRaise = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QWidget5raiseEv")?.reinterpret()
            fnWidgetActivateWindow = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QWidget14activateWindowEv")?.reinterpret()
            fnWidgetSetWindowModality = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QWidget17setWindowModalityEN2Qt14WindowModalityE")?.reinterpret()
            fnWidgetSetCursor = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QWidget9setCursorERK7QCursor")?.reinterpret()
            fnWidgetSetWindowFlags = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QWidget14setWindowFlagsE6QFlagsIN2Qt10WindowTypeEE")?.reinterpret()
            fnWidgetSetWindowFlag = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QWidget13setWindowFlagEN2Qt10WindowTypeEb")?.reinterpret()
            fnWidgetOverrideWindowFlags = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QWidget19overrideWindowFlagsE6QFlagsIN2Qt10WindowTypeEE")?.reinterpret()
            fnWidgetWinId = LinuxDynamicLibraryLoader.symbol(widgets, "_ZNK7QWidget5winIdEv")?.reinterpret()
            fnWidgetIsMaximized = LinuxDynamicLibraryLoader.symbol(widgets, "_ZNK7QWidget11isMaximizedEv")?.reinterpret()
            fnWidgetIsFullScreen = LinuxDynamicLibraryLoader.symbol(widgets, "_ZNK7QWidget12isFullScreenEv")?.reinterpret()

            fnLabelCtor = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN6QLabelC1EP7QWidget6QFlagsIN2Qt10WindowTypeEE")?.reinterpret()
            fnLabelDtor = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN6QLabelD1Ev")?.reinterpret()
            fnLabelSetPixmap = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN6QLabel9setPixmapERK7QPixmap")?.reinterpret()
            fnLabelSetText = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN6QLabel7setTextERK7QString")?.reinterpret()

            fnDialogCtor = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QDialogC1EP7QWidget6QFlagsIN2Qt10WindowTypeEE")?.reinterpret()
            fnDialogDtor = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QDialogD1Ev")?.reinterpret()
            fnDialogExec = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN7QDialog4execEv")?.reinterpret()

            fnMsgBoxCtor = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN11QMessageBoxC1EP7QWidget")?.reinterpret()
            fnMsgBoxDtor = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN11QMessageBoxD1Ev")?.reinterpret()
            fnMsgBoxSetText = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN11QMessageBox7setTextERK7QString")?.reinterpret()
            fnMsgBoxSetInfoText = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN11QMessageBox18setInformativeTextERK7QString")?.reinterpret()
            fnMsgBoxSetIcon = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN11QMessageBox7setIconENS_4IconE")?.reinterpret()
            fnMsgBoxSetStdButtons = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN11QMessageBox18setStandardButtonsE6QFlagsINS_14StandardButtonEE")?.reinterpret()
            fnMsgBoxSetDefaultBtn = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN11QMessageBox16setDefaultButtonENS_14StandardButtonE")?.reinterpret()

            fnFileDlgCtor = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN11QFileDialogC1EP7QWidgetRK7QStringS4_S4_")?.reinterpret()
            fnFileDlgDtor = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN11QFileDialogD1Ev")?.reinterpret()
            fnFileDlgSetFileMode = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN11QFileDialog11setFileModeENS_8FileModeE")?.reinterpret()
            fnFileDlgSetAcceptMode = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN11QFileDialog13setAcceptModeENS_10AcceptModeE")?.reinterpret()
            fnFileDlgSetDirectory = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN11QFileDialog12setDirectoryERK7QString")?.reinterpret()
            fnFileDlgSetNameFilter = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN11QFileDialog13setNameFilterERK7QString")?.reinterpret()
            fnFileDlgSetOption = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN11QFileDialog9setOptionENS_6OptionEb")?.reinterpret()

            fnColorDlgCtor = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN12QColorDialogC1EP7QWidget")?.reinterpret()
            fnColorDlgDtor = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN12QColorDialogD1Ev")?.reinterpret()
            fnColorDlgSetCurrent = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN12QColorDialog15setCurrentColorERK6QColor")?.reinterpret()
            fnColorDlgSetOption = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN12QColorDialog9setOptionENS_17ColorDialogOptionEb")?.reinterpret()

            fnInputDlgCtor = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN12QInputDialogC1EP7QWidget6QFlagsIN2Qt10WindowTypeEE")?.reinterpret()
            fnInputDlgDtor = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN12QInputDialogD1Ev")?.reinterpret()
            fnInputDlgSetLabelText = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN12QInputDialog12setLabelTextERK7QString")?.reinterpret()
            fnInputDlgSetTextValue = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN12QInputDialog12setTextValueERK7QString")?.reinterpret()

            fnComboCtor = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN9QComboBoxC1EP7QWidget")?.reinterpret()
            fnComboDtor = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN9QComboBoxD1Ev")?.reinterpret()
            fnComboInsertItem = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN9QComboBox10insertItemEiRK5QIconRK7QStringRK8QVariant")?.reinterpret()
            fnComboSetCurrentIndex = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN9QComboBox15setCurrentIndexEi")?.reinterpret()

            fnPushBtnCtor = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN11QPushButtonC1EP7QWidget")?.reinterpret()
            fnPushBtnDtor = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN11QPushButtonD1Ev")?.reinterpret()
            fnAbstractBtnSetText = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN15QAbstractButton7setTextERK7QString")?.reinterpret()

            fnTrayCtor = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN15QSystemTrayIconC1EP7QObject")?.reinterpret()
            fnTrayDtor = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN15QSystemTrayIconD1Ev")?.reinterpret()
            fnTrayIsAvailable = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN15QSystemTrayIcon21isSystemTrayAvailableEv")?.reinterpret()
            fnTraySetIcon = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN15QSystemTrayIcon7setIconERK5QIcon")?.reinterpret()
            fnTraySetToolTip = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN15QSystemTrayIcon10setToolTipERK7QString")?.reinterpret()
            fnTraySetVisible = LinuxDynamicLibraryLoader.symbol(widgets, "_ZN15QSystemTrayIcon10setVisibleEb")?.reinterpret()
        }

        qtLibrariesAvailable = fnQAppCtor != null &&
            fnWidgetCtor != null &&
            fnLabelCtor != null &&
            fnImageCtor != null &&
            fnPainterCtor != null &&
            fnPixmapConvertFromImage != null &&
            fnCoreAppProcessEvents != null
        qtLibrariesAvailable
    }

    fun hasActiveApplication(): Boolean = lock.withLock { qAppPtr != null }

    fun ensureApplication(): Boolean = lock.withLock {
        if (!ensureLibrariesLoaded()) return@withLock false
        if (qAppPtr != null) return@withLock true

        val hasPlatform = getenv("QT_QPA_PLATFORM")?.toKString()?.isNotBlank() == true
        val hasDisplay = getenv("DISPLAY")?.toKString()?.isNotBlank() == true ||
            getenv("WAYLAND_DISPLAY")?.toKString()?.isNotBlank() == true
        if (!hasPlatform && !hasDisplay) {
            setenv("QT_QPA_PLATFORM", "offscreen", 0)
        }

        val ctor = fnQAppCtor ?: return@withLock false
        val storage = calloc(1uL, 64uL) ?: return@withLock false
        val argcVar = nativeHeap.alloc<IntVar>()
        argcVar.value = 1
        val argBytes = "GenericUI\u0000".encodeToByteArray()
        val arg0 = nativeHeap.allocArray<ByteVar>(argBytes.size)
        for (i in argBytes.indices) {
            arg0[i] = argBytes[i]
        }
        val argvArr = nativeHeap.allocArray<CPointerVar<ByteVar>>(2)
        argvArr[0] = arg0
        argvArr[1] = null
        ctor.invoke(storage, argcVar.ptr, argvArr, QT_VERSION)
        qAppPtr = storage
        qAppArgcPtr = argcVar.ptr
        qAppArg0Ptr = arg0
        qAppArgvPtr = argvArr
        true
    }

    fun processEvents(maxTimeMs: Int = 16) {
        if (!hasActiveApplication()) return
        fnCoreAppProcessEvents?.invoke(0, maxTimeMs)
    }

    fun exitApplication(exitCode: Int = 0) {
        if (!hasActiveApplication()) return
        fnCoreAppExit?.invoke(exitCode)
    }

    fun queryDarkColorScheme(): Boolean? {
        if (!hasActiveApplication()) return null
        val hints = fnGuiAppStyleHints?.invoke() ?: return null
        val scheme = fnStyleHintsColorScheme?.invoke(hints) ?: return null
        return if (scheme == QT_COLOR_SCHEME_DARK) true else if (scheme == 1) false else null
    }

    fun mouseButtons(): UInt = if (hasActiveApplication()) (fnGuiAppMouseButtons?.invoke() ?: 0u) else 0u

    fun keyboardModifiers(): UInt = if (hasActiveApplication()) (fnGuiAppKeyboardModifiers?.invoke() ?: 0u) else 0u

    fun cursorPos(): Pair<Int, Int>? {
        if (!hasActiveApplication()) return null
        val packed = fnCursorPos?.invoke() ?: return null
        val x = (packed and 0xFFFFFFFFL).toInt()
        val y = (packed ushr 32).toInt()
        return x to y
    }

    fun clipboardSetText(text: String) {
        if (!hasActiveApplication()) return
        val cb = fnGuiAppClipboard?.invoke() ?: return
        withRawQString(text) { qs ->
            fnClipboardSetText?.invoke(cb, qs, 0)
        }
    }

    // --- QtStringSupport ---

    inline fun <R> withRawQString(text: String, block: (COpaquePointer) -> R): R = memScoped {
        val qs = allocArray<LongVar>(3)
        if (text.isEmpty()) {
            qs[0] = 0L
            qs[1] = 0L
            qs[2] = 0L
            block(qs)
        } else {
            val units = UShortArray(text.length) { text[it].code.toUShort() }
            units.usePinned { pinned ->
                qs[0] = 0L
                qs[1] = pinned.addressOf(0).rawValue.toLong()
                qs[2] = text.length.toLong()
                block(qs)
            }
        }
    }

    private fun readAndFreeOwnedQString(qs: CPointer<LongVar>): String {
        val d = qs[0]
        val ptrAddr = qs[1]
        val size = qs[2].toInt().coerceAtLeast(0)
        val result = if (ptrAddr != 0L && size > 0) {
            val u16 = ptrAddr.toCPointer<UShortVar>()
            if (u16 != null) {
                CharArray(size) { idx -> Char(u16[idx].toInt()) }.concatToString()
            } else {
                ""
            }
        } else {
            ""
        }
        // Qt 6 QArrayData layout begins with an atomic int refcount (`-1` = static/immortal, `1` = sole owner, `>1` = shared).
        // Calling QArrayData::deallocate unconditionally frees shared/static buffers held by Qt (e.g. `QGuiApplication::platformName`).
        if (d != 0L) {
            val refPtr = d.toCPointer<IntVar>()
            val ref = refPtr?.get(0) ?: -1
            if (ref == 1) {
                refPtr?.set(0, 0)
                fnArrayDataDeallocate?.invoke(d.toCPointer(), 2L, 8L)
            } else if (ref > 1) {
                refPtr?.set(0, ref - 1)
            }
        }
        qs[0] = 0L; qs[1] = 0L; qs[2] = 0L
        return result
    }

    // --- QMetaObject introspection & QObject children (no sret, no vtable subclass) ---

    fun metaObjectOf(qObject: COpaquePointer?): COpaquePointer? {
        if (qObject == null) return null
        val vtablePtr = qObject.reinterpret<CPointerVar<CPointerVar<CPointed>>>()[0] ?: return null
        val fnAddr = vtablePtr.reinterpret<COpaquePointerVar>()[0] ?: return null
        val fn = fnAddr.reinterpret<CFunction<(COpaquePointer?) -> COpaquePointer?>>()
        return fn.invoke(qObject)
    }

    fun classNameOf(qObject: COpaquePointer?): String {
        val mo = metaObjectOf(qObject) ?: return ""
        return fnMetaObjectClassName?.invoke(mo)?.toKString() ?: ""
    }

    fun objectChildrenOf(qObject: COpaquePointer?): List<COpaquePointer> {
        if (qObject == null) return emptyList()
        // QObject::d_ptr is at offset 8 (after vptr); QObjectData::children is QArrayDataPointer at offset 24.
        val dPtrAddr = interpretCPointer<LongVar>(qObject.rawValue + 8L)?.get(0) ?: return emptyList()
        if (dPtrAddr == 0L) return emptyList()
        val dataPtr = (dPtrAddr + 32L).toCPointer<LongVar>()?.get(0) ?: return emptyList()
        val size = ((dPtrAddr + 40L).toCPointer<LongVar>()?.get(0) ?: 0L).toInt()
        if (dataPtr == 0L || size <= 0) return emptyList()
        val array = dataPtr.toCPointer<COpaquePointerVar>() ?: return emptyList()
        val result = ArrayList<COpaquePointer>(size)
        for (i in 0 until size) {
            array[i]?.let { result.add(it) }
        }
        return result
    }

    fun findDescendantByClass(root: COpaquePointer?, targetClassName: String): COpaquePointer? {
        if (root == null) return null
        val queue = ArrayDeque<COpaquePointer>()
        for (child in objectChildrenOf(root)) {
            queue.addLast(child)
        }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (classNameOf(cur) == targetClassName) return cur
            for (next in objectChildrenOf(cur)) {
                queue.addLast(next)
            }
        }
        return null
    }

    fun findDescendantByObjectName(root: COpaquePointer?, targetObjectName: String): COpaquePointer? {
        if (root == null) return null
        val queue = ArrayDeque<COpaquePointer>()
        for (child in objectChildrenOf(root)) {
            queue.addLast(child)
        }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (readPropertyQString(cur, "objectName") == targetObjectName) return cur
            for (next in objectChildrenOf(cur)) {
                queue.addLast(next)
            }
        }
        return null
    }

    private fun readPropertyRaw(qObject: COpaquePointer?, propertyName: String, dstBuffer: COpaquePointer): Boolean {
        val mo = metaObjectOf(qObject) ?: return false
        val idx = memScoped { fnMetaObjectIndexOfProperty?.invoke(mo, propertyName.cstr.ptr) ?: -1 }
        if (idx < 0) return false
        memScoped {
            val args = allocArray<COpaquePointerVar>(1)
            args[0] = dstBuffer
            fnMetaObjectMetacall?.invoke(qObject, CALL_READ_PROPERTY, idx, args)
        }
        return true
    }

    fun readPropertyInt(qObject: COpaquePointer?, propertyName: String): Int? = memScoped {
        val v = alloc<IntVar>()
        v.value = 0
        if (!readPropertyRaw(qObject, propertyName, v.ptr)) return@memScoped null
        v.value
    }

    fun readPropertyBool(qObject: COpaquePointer?, propertyName: String): Boolean? = memScoped {
        val v = alloc<ByteVar>()
        v.value = 0
        if (!readPropertyRaw(qObject, propertyName, v.ptr)) return@memScoped null
        v.value != 0.toByte()
    }

    fun readPropertyQSize(qObject: COpaquePointer?, propertyName: String): Pair<Int, Int>? = memScoped {
        val v = allocArray<IntVar>(2)
        v[0] = 0; v[1] = 0
        if (!readPropertyRaw(qObject, propertyName, v)) return@memScoped null
        v[0] to v[1]
    }

    fun readPropertyQRect(qObject: COpaquePointer?, propertyName: String): IntArray? = memScoped {
        val v = allocArray<IntVar>(4)
        v[0] = 0; v[1] = 0; v[2] = -1; v[3] = -1
        if (!readPropertyRaw(qObject, propertyName, v)) return@memScoped null
        val x = v[0]
        val y = v[1]
        val w = (v[2] - v[0] + 1).coerceAtLeast(0)
        val h = (v[3] - v[1] + 1).coerceAtLeast(0)
        intArrayOf(x, y, w, h)
    }

    fun readPropertyQString(qObject: COpaquePointer?, propertyName: String): String {
        return memScoped {
            val qs = allocArray<LongVar>(3)
            qs[0] = 0L; qs[1] = 0L; qs[2] = 0L
            if (!readPropertyRaw(qObject, propertyName, qs)) return@memScoped ""
            readAndFreeOwnedQString(qs)
        }
    }

    fun readPropertyQColor(qObject: COpaquePointer?, propertyName: String): RgbaColor? = memScoped {
        val buf = allocArray<LongVar>(2)
        buf[0] = 0L; buf[1] = 0L
        if (!readPropertyRaw(qObject, propertyName, buf)) return@memScoped null
        val u16 = buf.reinterpret<UShortVar>()
        val alpha = (u16[2].toInt() ushr 8).coerceIn(0, 255)
        val r = (u16[3].toInt() ushr 8).coerceIn(0, 255)
        val g = (u16[4].toInt() ushr 8).coerceIn(0, 255)
        val b = (u16[5].toInt() ushr 8).coerceIn(0, 255)
        RgbaColor(r = r, g = g, b = b, a = alpha / 255f)
    }

    // --- QWidget & QLabel ---

    fun createWidget(parent: COpaquePointer? = null, flags: Int = 0): COpaquePointer? {
        if (!ensureApplication()) return null
        val ptr = calloc(1uL, 128uL) ?: return null
        fnWidgetCtor?.invoke(ptr, parent, flags) ?: run {
            posixFree(ptr)
            return null
        }
        return ptr
    }

    fun destroyWidget(widget: COpaquePointer?) {
        if (widget == null) return
        fnWidgetDtor?.invoke(widget)
        posixFree(widget)
    }

    fun setWindowTitle(widget: COpaquePointer, title: String) {
        withRawQString(title) { qs -> fnWidgetSetWindowTitle?.invoke(widget, qs) }
    }

    fun resizeWidget(widget: COpaquePointer, width: Int, height: Int) = memScoped {
        val qSize = allocArray<IntVar>(2)
        qSize[0] = width.coerceAtLeast(1)
        qSize[1] = height.coerceAtLeast(1)
        fnWidgetResize?.invoke(widget, qSize)
    }

    fun moveWidget(widget: COpaquePointer, x: Int, y: Int) = memScoped {
        val qPoint = allocArray<IntVar>(2)
        qPoint[0] = x
        qPoint[1] = y
        fnWidgetMove?.invoke(widget, qPoint)
    }

    fun getPrimaryScreenBounds(): com.nexustools.genericui.geometry.Rect? {
        if (!ensureLibrariesLoaded()) return null
        val hasEnv = getenv("DISPLAY")?.toKString()?.isNotBlank() == true ||
            getenv("WAYLAND_DISPLAY")?.toKString()?.isNotBlank() == true
        if (!hasEnv) return null
        if (!hasActiveApplication() && !ensureApplication()) return null
        val screen = fnGuiAppPrimaryScreen?.invoke() ?: return null
        val geom = readPropertyQRect(screen, "geometry") ?: return null
        return if (geom[2] > 0 && geom[3] > 0) {
            com.nexustools.genericui.geometry.Rect(geom[0], geom[1], geom[2], geom[3])
        } else {
            null
        }
    }

    fun setMinimumSize(widget: COpaquePointer, minW: Int, minH: Int) {
        fnWidgetSetMinSize?.invoke(widget, minW.coerceAtLeast(0), minH.coerceAtLeast(0))
    }

    fun setMaximumSize(widget: COpaquePointer, maxW: Int, maxH: Int) {
        fnWidgetSetMaxSize?.invoke(widget, maxW, maxH)
    }

    fun setWidgetGeometry(widget: COpaquePointer, x: Int, y: Int, w: Int, h: Int) = memScoped {
        val qRect = allocArray<IntVar>(4)
        qRect[0] = x
        qRect[1] = y
        qRect[2] = x + w.coerceAtLeast(1) - 1
        qRect[3] = y + h.coerceAtLeast(1) - 1
        fnWidgetSetGeometry?.invoke(widget, qRect)
    }

    fun showWidget(widget: COpaquePointer) { fnWidgetShow?.invoke(widget) }
    fun hideWidget(widget: COpaquePointer) { fnWidgetHide?.invoke(widget) }
    fun setWidgetVisible(widget: COpaquePointer, visible: Boolean) {
        fnWidgetSetVisible?.invoke(widget, if (visible) 1.toByte() else 0.toByte())
    }
    fun closeWidget(widget: COpaquePointer) { fnWidgetClose?.invoke(widget) }
    fun updateWidget(widget: COpaquePointer) { fnWidgetUpdate?.invoke(widget) }
    fun showFullScreen(widget: COpaquePointer) { fnWidgetShowFullScreen?.invoke(widget) }
    fun showMaximized(widget: COpaquePointer) { fnWidgetShowMaximized?.invoke(widget) }
    fun showMinimized(widget: COpaquePointer) { fnWidgetShowMinimized?.invoke(widget) }
    fun showNormal(widget: COpaquePointer) { fnWidgetShowNormal?.invoke(widget) }
    fun raiseWidget(widget: COpaquePointer) { fnWidgetRaise?.invoke(widget) }
    fun activateWindow(widget: COpaquePointer) { fnWidgetActivateWindow?.invoke(widget) }
    fun setWindowModality(widget: COpaquePointer, modality: Int) { fnWidgetSetWindowModality?.invoke(widget, modality) }
    fun setWindowFlags(widget: COpaquePointer, flags: Int) { fnWidgetSetWindowFlags?.invoke(widget, flags) }
    fun setWidgetWindowFlag(widget: COpaquePointer, flag: Int, on: Boolean) {
        fnWidgetSetWindowFlag?.invoke(widget, flag, if (on) 1.toByte() else 0.toByte())
    }
    fun overrideWidgetWindowFlags(widget: COpaquePointer, flags: Int) {
        fnWidgetOverrideWindowFlags?.invoke(widget, flags)
    }
    fun isXcbPlatform(): Boolean {
        if (!hasActiveApplication()) return false
        cachedIsXcbPlatform?.let { return it }
        val resolved = readPropertyQString(qAppPtr, "platformName").equals("xcb", ignoreCase = true)
        cachedIsXcbPlatform = resolved
        return resolved
    }
    fun widgetX11Xid(widget: COpaquePointer): ULong {
        if (!isXcbPlatform()) return 0uL
        return fnWidgetWinId?.invoke(widget) ?: 0uL
    }
    fun isWidgetMaximized(widget: COpaquePointer): Boolean =
        (fnWidgetIsMaximized?.invoke(widget) ?: 0.toByte()) != 0.toByte() ||
            (readPropertyBool(widget, "maximized") == true)
    fun isWidgetFullScreen(widget: COpaquePointer): Boolean =
        (fnWidgetIsFullScreen?.invoke(widget) ?: 0.toByte()) != 0.toByte() ||
            (readPropertyBool(widget, "fullScreen") == true)

    fun setWidgetCursorShape(widget: COpaquePointer, shape: Int) = memScoped {
        val cur = allocArray<LongVar>(2)
        cur[0] = 0L; cur[1] = 0L
        fnCursorCtor?.invoke(cur, shape)
        fnWidgetSetCursor?.invoke(widget, cur)
        fnCursorDtor?.invoke(cur)
    }

    fun createLabel(parent: COpaquePointer? = null, flags: Int = 0): COpaquePointer? {
        if (!ensureApplication()) return null
        val ptr = calloc(1uL, 128uL) ?: return null
        fnLabelCtor?.invoke(ptr, parent, flags) ?: run {
            posixFree(ptr)
            return null
        }
        return ptr
    }

    fun destroyLabel(label: COpaquePointer?) {
        if (label == null) return
        fnLabelDtor?.invoke(label)
        posixFree(label)
    }

    // --- QImage + QPainter + QPixmap -> QLabel pixel presentation ---

    fun presentArgbBufferOnLabel(
        label: COpaquePointer,
        argbPixels: IntArray,
        width: Int,
        height: Int,
        painterOverlay: ((qPainter: COpaquePointer) -> Unit)? = null
    ): Boolean {
        if (!hasActiveApplication() || width <= 0 || height <= 0 || argbPixels.size < width * height) {
            return false
        }
        return memScoped {
            val qimage = allocArray<LongVar>(8)
            val qpixmap = allocArray<LongVar>(8)
            val qpainter = allocArray<LongVar>(4)
            var result = false
            argbPixels.usePinned { pinned ->
                val bytesPtr = pinned.addressOf(0).reinterpret<ByteVar>()
                fnImageCtor?.invoke(qimage, bytesPtr, width, height, QIMAGE_FORMAT_ARGB32, null, null)
                if (painterOverlay != null) {
                    fnPainterCtor?.invoke(qpainter, qimage)
                    fnPainterSetRenderHints?.invoke(qpainter, QPAINTER_HINT_ALL, 1.toByte())
                    painterOverlay.invoke(qpainter)
                    fnPainterEnd?.invoke(qpainter)
                    fnPainterDtor?.invoke(qpainter)
                }
                fnPixmapCtor?.invoke(qpixmap)
                val ok = fnPixmapConvertFromImage?.invoke(qpixmap, qimage, 0) ?: 0.toByte()
                if (ok != 0.toByte()) {
                    fnLabelSetPixmap?.invoke(label, qpixmap)
                    result = true
                }
                fnPixmapDtor?.invoke(qpixmap)
                fnImageDtor?.invoke(qimage)
            }
            result
        }
    }

    fun painterFillRect(painter: COpaquePointer, x: Int, y: Int, w: Int, h: Int, color: RgbaColor) = memScoped {
        if (w <= 0 || h <= 0) return@memScoped
        val qrect = allocArray<IntVar>(4)
        qrect[0] = x; qrect[1] = y; qrect[2] = x + w - 1; qrect[3] = y + h - 1
        val qcol = allocArray<LongVar>(2)
        fnColorSetRgb?.invoke(qcol, color.r.coerceIn(0, 255), color.g.coerceIn(0, 255), color.b.coerceIn(0, 255), (color.a * 255f).roundToInt().coerceIn(0, 255))
        fnPainterFillRect?.invoke(painter, qrect, qcol)
    }

    fun painterDrawRoundedRect(
        painter: COpaquePointer,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        radius: Double,
        penColor: RgbaColor
    ) = memScoped {
        if (w <= 0.0 || h <= 0.0) return@memScoped
        val qrectF = allocArray<DoubleVar>(4)
        qrectF[0] = x; qrectF[1] = y; qrectF[2] = w; qrectF[3] = h
        val qcol = allocArray<LongVar>(2)
        fnColorSetRgb?.invoke(qcol, penColor.r.coerceIn(0, 255), penColor.g.coerceIn(0, 255), penColor.b.coerceIn(0, 255), (penColor.a * 255f).roundToInt().coerceIn(0, 255))
        fnPainterSetPen?.invoke(painter, qcol)
        fnPainterSetBrushStyle?.invoke(painter, 0)
        fnPainterDrawRoundedRect?.invoke(painter, qrectF, radius, radius, 0)
    }

    fun painterDrawText(
        painter: COpaquePointer,
        text: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        color: RgbaColor,
        pixelSize: Int = 14,
        family: String = "Sans",
        weight: Int = 400,
        italic: Boolean = false,
        alignFlags: Int = QT_ALIGN_LEFT or QT_ALIGN_VCENTER or QT_TEXT_WORD_WRAP
    ) = memScoped {
        if (text.isEmpty() || w <= 0 || h <= 0) return@memScoped
        val qrect = allocArray<IntVar>(4)
        qrect[0] = x; qrect[1] = y; qrect[2] = x + w - 1; qrect[3] = y + h - 1
        val qcol = allocArray<LongVar>(2)
        fnColorSetRgb?.invoke(qcol, color.r.coerceIn(0, 255), color.g.coerceIn(0, 255), color.b.coerceIn(0, 255), (color.a * 255f).roundToInt().coerceIn(0, 255))
        val qfont = allocArray<LongVar>(4)
        fnFontCtor?.invoke(qfont)
        withRawQString(family) { famQs -> fnFontSetFamily?.invoke(qfont, famQs) }
        fnFontSetPixelSize?.invoke(qfont, pixelSize.coerceAtLeast(8))
        fnFontSetWeight?.invoke(qfont, weight)
        fnFontSetStyle?.invoke(qfont, if (italic) 1 else 0)
        fnPainterSetPen?.invoke(painter, qcol)
        fnPainterSetFont?.invoke(painter, qfont)
        withRawQString(text) { textQs ->
            fnPainterDrawText?.invoke(painter, qrect, alignFlags, textQs, null)
        }
        fnFontDtor?.invoke(qfont)
    }

    // --- Dialogs & System Tray ---

    fun execMessageBox(
        parent: COpaquePointer?,
        title: String,
        text: String,
        informativeText: String,
        icon: Int,
        buttonsMask: Int,
        defaultButton: Int
    ): Int {
        if (!ensureApplication()) return 0
        val ptr = calloc(1uL, 128uL) ?: return 0
        fnMsgBoxCtor?.invoke(ptr, parent) ?: run {
            posixFree(ptr)
            return 0
        }
        return try {
            withRawQString(title) { qs -> fnWidgetSetWindowTitle?.invoke(ptr, qs) }
            withRawQString(text) { qs -> fnMsgBoxSetText?.invoke(ptr, qs) }
            if (informativeText.isNotEmpty()) {
                withRawQString(informativeText) { qs -> fnMsgBoxSetInfoText?.invoke(ptr, qs) }
            }
            fnMsgBoxSetIcon?.invoke(ptr, icon)
            fnMsgBoxSetStdButtons?.invoke(ptr, buttonsMask)
            fnMsgBoxSetDefaultBtn?.invoke(ptr, defaultButton)
            fnDialogExec?.invoke(ptr) ?: 0
        } finally {
            fnMsgBoxDtor?.invoke(ptr)
            posixFree(ptr)
        }
    }

    fun execInputPrompt(
        parent: COpaquePointer?,
        title: String,
        label: String,
        defaultValue: String
    ): String? {
        if (!ensureApplication()) return null
        val ptr = calloc(1uL, 128uL) ?: return null
        fnInputDlgCtor?.invoke(ptr, parent, 0) ?: run {
            posixFree(ptr)
            return null
        }
        return try {
            withRawQString(title) { qs -> fnWidgetSetWindowTitle?.invoke(ptr, qs) }
            withRawQString(label) { qs -> fnInputDlgSetLabelText?.invoke(ptr, qs) }
            withRawQString(defaultValue) { qs -> fnInputDlgSetTextValue?.invoke(ptr, qs) }
            val accepted = (fnDialogExec?.invoke(ptr) ?: 0) == QDIALOG_ACCEPTED
            if (!accepted) {
                null
            } else {
                val lineEdit = findDescendantByClass(ptr, "QLineEdit")
                if (lineEdit != null) readPropertyQString(lineEdit, "text") else defaultValue
            }
        } finally {
            fnInputDlgDtor?.invoke(ptr)
            posixFree(ptr)
        }
    }

    fun execChoiceDialog(
        parent: COpaquePointer?,
        title: String,
        message: String,
        options: List<String>,
        selectedIndex: Int
    ): Int? {
        if (options.isEmpty() || !ensureApplication()) return null
        val dlg = calloc(1uL, 128uL) ?: return null
        val lbl = calloc(1uL, 128uL)
        val combo = calloc(1uL, 128uL)
        val cancelBtn = calloc(1uL, 128uL)
        val okBtn = calloc(1uL, 128uL)
        if (lbl == null || combo == null || cancelBtn == null || okBtn == null) {
            listOf(lbl, combo, cancelBtn, okBtn, dlg).forEach { if (it != null) posixFree(it) }
            return null
        }
        fnDialogCtor?.invoke(dlg, parent, 0)
        withRawQString(title) { qs -> fnWidgetSetWindowTitle?.invoke(dlg, qs) }
        resizeWidget(dlg, 360, 150)

        fnLabelCtor?.invoke(lbl, dlg, 0)
        withRawQString(message) { qs -> fnLabelSetText?.invoke(lbl, qs) }
        setWidgetGeometry(lbl, 16, 14, 328, 28)

        fnComboCtor?.invoke(combo, dlg)
        memScoped {
            val emptyIcon = allocArray<LongVar>(2)
            fnIconCtorEmpty?.invoke(emptyIcon)
            val emptyVar = allocArray<LongVar>(4)
            for (k in 0..3) emptyVar[k] = 0L
            for ((idx, opt) in options.withIndex()) {
                withRawQString(opt) { qs ->
                    fnComboInsertItem?.invoke(combo, idx, emptyIcon, qs, emptyVar)
                }
            }
            fnIconDtor?.invoke(emptyIcon)
        }
        if (selectedIndex in options.indices) {
            fnComboSetCurrentIndex?.invoke(combo, selectedIndex)
        }
        setWidgetGeometry(combo, 16, 48, 328, 32)

        fnPushBtnCtor?.invoke(cancelBtn, dlg)
        withRawQString("Cancel") { qs -> fnAbstractBtnSetText?.invoke(cancelBtn, qs) }
        setWidgetGeometry(cancelBtn, 156, 98, 88, 32)

        fnPushBtnCtor?.invoke(okBtn, dlg)
        withRawQString("OK") { qs -> fnAbstractBtnSetText?.invoke(okBtn, qs) }
        setWidgetGeometry(okBtn, 256, 98, 88, 32)

        showWidget(dlg)
        var wasOkDown = false
        var wasCancelDown = false
        var accepted: Boolean? = null
        while (accepted == null) {
            processEvents(16)
            val vis = readPropertyBool(dlg, "visible") ?: false
            if (!vis) {
                accepted = false
                break
            }
            val okDown = readPropertyBool(okBtn, "down") ?: false
            if (wasOkDown && !okDown) {
                accepted = true
                break
            }
            wasOkDown = okDown
            val cancelDown = readPropertyBool(cancelBtn, "down") ?: false
            if (wasCancelDown && !cancelDown) {
                accepted = false
                break
            }
            wasCancelDown = cancelDown
        }
        val chosenIdx = if (accepted == true) {
            readPropertyInt(combo, "currentIndex")?.takeIf { it in options.indices }
        } else {
            null
        }
        // Destroy children before parent so ~QDialog skips operator delete on our calloc slots.
        fnPushBtnDtor?.invoke(okBtn); posixFree(okBtn)
        fnPushBtnDtor?.invoke(cancelBtn); posixFree(cancelBtn)
        fnComboDtor?.invoke(combo); posixFree(combo)
        fnLabelDtor?.invoke(lbl); posixFree(lbl)
        fnDialogDtor?.invoke(dlg); posixFree(dlg)
        return chosenIdx
    }

    fun execColorDialog(parent: COpaquePointer?, title: String, initial: RgbaColor): RgbaColor? {
        if (!ensureApplication()) return null
        val ptr = calloc(1uL, 128uL) ?: return null
        fnColorDlgCtor?.invoke(ptr, parent) ?: run {
            posixFree(ptr)
            return null
        }
        return try {
            withRawQString(title) { qs -> fnWidgetSetWindowTitle?.invoke(ptr, qs) }
            fnColorDlgSetOption?.invoke(ptr, QCOLOR_OPT_SHOW_ALPHA, 1.toByte())
            fnColorDlgSetOption?.invoke(ptr, QCOLOR_OPT_DONT_USE_NATIVE, 1.toByte())
            memScoped {
                val qcol = allocArray<LongVar>(2)
                fnColorSetRgb?.invoke(
                    qcol,
                    initial.r.coerceIn(0, 255),
                    initial.g.coerceIn(0, 255),
                    initial.b.coerceIn(0, 255),
                    (initial.a * 255f).roundToInt().coerceIn(0, 255)
                )
                fnColorDlgSetCurrent?.invoke(ptr, qcol)
            }
            val accepted = (fnDialogExec?.invoke(ptr) ?: 0) == QDIALOG_ACCEPTED
            if (accepted) readPropertyQColor(ptr, "currentColor") else null
        } finally {
            fnColorDlgDtor?.invoke(ptr)
            posixFree(ptr)
        }
    }

    fun execFileDialog(
        parent: COpaquePointer?,
        title: String,
        initialDir: String?,
        filterString: String?,
        fileMode: Int,
        acceptMode: Int
    ): List<String> {
        if (!ensureApplication()) return emptyList()
        val ptr = calloc(1uL, 128uL) ?: return emptyList()
        withRawQString(title) { capQs ->
            withRawQString(initialDir.orEmpty()) { dirQs ->
                withRawQString(filterString.orEmpty()) { fltQs ->
                    fnFileDlgCtor?.invoke(ptr, parent, capQs, dirQs, fltQs)
                }
            }
        }
        return try {
            fnFileDlgSetOption?.invoke(ptr, QFILE_OPT_DONT_USE_NATIVE, 1.toByte())
            if (fileMode == QFILE_MODE_DIRECTORY) {
                fnFileDlgSetOption?.invoke(ptr, QFILE_OPT_SHOW_DIRS_ONLY, 1.toByte())
            }
            fnFileDlgSetFileMode?.invoke(ptr, fileMode)
            fnFileDlgSetAcceptMode?.invoke(ptr, acceptMode)
            initialDir?.takeIf { it.isNotBlank() }?.let { dir ->
                withRawQString(dir) { qs -> fnFileDlgSetDirectory?.invoke(ptr, qs) }
            }
            filterString?.takeIf { it.isNotBlank() }?.let { flt ->
                withRawQString(flt) { qs -> fnFileDlgSetNameFilter?.invoke(ptr, qs) }
            }
            val accepted = (fnDialogExec?.invoke(ptr) ?: 0) == QDIALOG_ACCEPTED
            if (!accepted) {
                emptyList()
            } else {
                val dirCombo = findDescendantByObjectName(ptr, "lookInCombo")
                val fileEdit = findDescendantByObjectName(ptr, "fileNameEdit")
                val baseDir = dirCombo?.let { readPropertyQString(it, "currentText") }.orEmpty().ifBlank { initialDir.orEmpty() }
                val rawFile = fileEdit?.let { readPropertyQString(it, "text") }.orEmpty().trim()
                if (fileMode == QFILE_MODE_DIRECTORY) {
                    val chosen = when {
                        rawFile.startsWith("/") -> rawFile
                        rawFile.isNotEmpty() && baseDir.isNotEmpty() -> "${baseDir.trimEnd('/')}/$rawFile"
                        baseDir.isNotEmpty() -> baseDir
                        else -> rawFile
                    }
                    if (chosen.isNotBlank()) listOf(chosen) else emptyList()
                } else {
                    parseQtFileNames(rawFile, baseDir)
                }
            }
        } finally {
            fnFileDlgDtor?.invoke(ptr)
            posixFree(ptr)
        }
    }

    private fun parseQtFileNames(rawText: String, baseDir: String): List<String> {
        if (rawText.isBlank()) return emptyList()
        val tokens = if (rawText.contains('"')) {
            val out = ArrayList<String>()
            var idx = 0
            while (idx < rawText.length) {
                val start = rawText.indexOf('"', idx)
                if (start < 0) break
                val end = rawText.indexOf('"', start + 1)
                if (end < 0) break
                val part = rawText.substring(start + 1, end).trim()
                if (part.isNotEmpty()) out.add(part)
                idx = end + 1
            }
            out
        } else {
            listOf(rawText)
        }
        val cleanBase = baseDir.trimEnd('/')
        return tokens.map { entry ->
            if (entry.startsWith("/") || cleanBase.isEmpty()) entry else "$cleanBase/$entry"
        }
    }

    fun isSystemTrayAvailable(): Boolean =
        ensureApplication() && ((fnTrayIsAvailable?.invoke() ?: 0.toByte()) != 0.toByte())

    fun createSystemTrayIcon(): COpaquePointer? {
        if (!ensureApplication()) return null
        val ptr = calloc(1uL, 64uL) ?: return null
        fnTrayCtor?.invoke(ptr, null) ?: run {
            posixFree(ptr)
            return null
        }
        return ptr
    }

    fun setSystemTrayTooltip(tray: COpaquePointer, tooltip: String) {
        withRawQString(tooltip) { qs -> fnTraySetToolTip?.invoke(tray, qs) }
    }

    fun setSystemTrayIconFromPath(tray: COpaquePointer, path: String) = memScoped {
        val qicon = allocArray<LongVar>(2)
        qicon[0] = 0L; qicon[1] = 0L
        withRawQString(path) { qs -> fnIconCtorFile?.invoke(qicon, qs) }
        fnTraySetIcon?.invoke(tray, qicon)
        fnIconDtor?.invoke(qicon)
    }

    fun setSystemTrayIconFromArgb(tray: COpaquePointer, argbPixels: IntArray, width: Int, height: Int) = memScoped {
        val qimage = allocArray<LongVar>(8)
        val qpixmap = allocArray<LongVar>(8)
        val qicon = allocArray<LongVar>(2)
        argbPixels.usePinned { pinned ->
            fnImageCtor?.invoke(qimage, pinned.addressOf(0).reinterpret(), width, height, QIMAGE_FORMAT_ARGB32, null, null)
            fnPixmapCtor?.invoke(qpixmap)
            fnPixmapConvertFromImage?.invoke(qpixmap, qimage, 0)
            fnIconCtorPixmap?.invoke(qicon, qpixmap)
            fnTraySetIcon?.invoke(tray, qicon)
            fnIconDtor?.invoke(qicon)
            fnPixmapDtor?.invoke(qpixmap)
            fnImageDtor?.invoke(qimage)
        }
    }

    fun setSystemTrayVisible(tray: COpaquePointer, visible: Boolean) {
        fnTraySetVisible?.invoke(tray, if (visible) 1.toByte() else 0.toByte())
    }

    fun destroySystemTrayIcon(tray: COpaquePointer?) {
        if (tray == null) return
        fnTrayDtor?.invoke(tray)
        posixFree(tray)
    }
}
