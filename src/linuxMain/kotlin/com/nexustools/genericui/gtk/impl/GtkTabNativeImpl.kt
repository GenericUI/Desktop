@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTabNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.component.tabs.TabIconPlacement
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkSurfaceRenderHelper
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.linux.LinuxTabIconHelper
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.Job
import kotlin.math.ceil
import kotlin.math.max

/**
 * Retained GTK4 tab chip widget backing `AdwTabPage` / custom `Tab` headers inside [GtkTabContainerNativeImpl].
 */
open class GtkTabNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("AdwTabPage"),
    closable: Boolean = false
) : DesktopTabNativeImpl<GtkWidgetHandle>(nativeWidget, closable) {

    var onHeaderChanged: (() -> Unit)? = null
    var onSelectRequested: (() -> Unit)? = null
    var onCloseActionRequested: (() -> Unit)? = null

    private var iconPicPtr: COpaquePointer? = null
    private var iconTextLblPtr: COpaquePointer? = null
    private var labelPtr: COpaquePointer? = null
    private var badgePtr: COpaquePointer? = null
    private var closeBtnPtr: COpaquePointer? = null
    private var clickRef: StableRef<GtkTabNativeImpl>? = null
    private var localCloseListener: (() -> Unit)? = null
    private var decodedIconBuffer: ImageBuffer? = null
    private var activeIconLoadJob: Job? = null
    private var iconLoadGeneration: Int = 0
    private var lastLoadedIconKey: String? = null

    init {
        nativeWidget.ownerImpl = this
        nativeWidget.widgetMaterializer = { materializeTabChip() }
    }

    private fun materializeTabChip(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null

        val row = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_HORIZONTAL, 6) ?: return null
        Gtk4DynamicBindings.gtkWidgetAddCssClass(row, "tab-chip")
        Gtk4DynamicBindings.gtkWidgetSetMargins(row, 6, 6, 10, 10)

        clickRef?.dispose()
        val ref = StableRef.create(this)
        clickRef = ref

        val customPtr = (customHeaderImpl?.nativeWidget as? GtkWidgetHandle)?.ensureLiveWidgetPtr()
        if (customPtr != null) {
            Gtk4DynamicBindings.gtkBoxAppend(row, customPtr)
            iconPicPtr = null
            iconTextLblPtr = null
            labelPtr = null
            badgePtr = null
        } else {
            val iconSz = iconSizePxVal.coerceIn(12, 32)
            val iconPic = Gtk4DynamicBindings.gtkPictureNewForPaintable(null)
            if (iconPic != null) {
                Gtk4DynamicBindings.gtkPictureSetCanShrink(iconPic, true)
                Gtk4DynamicBindings.gtkPictureSetContentFit(iconPic, Gtk4DynamicBindings.GTK_CONTENT_FIT_CONTAIN)
                Gtk4DynamicBindings.gtkWidgetSetSizeRequest(iconPic, iconSz, iconSz)
                Gtk4DynamicBindings.gtkWidgetSetVisible(iconPic, false)
            }
            iconPicPtr = iconPic

            val iconTextLbl = Gtk4DynamicBindings.gtkLabelNew("")
            if (iconTextLbl != null) {
                Gtk4DynamicBindings.gtkWidgetSetVisible(iconTextLbl, false)
            }
            iconTextLblPtr = iconTextLbl

            val btn = Gtk4DynamicBindings.gtkButtonNew()
            if (btn != null) {
                Gtk4DynamicBindings.gtkWidgetAddCssClass(btn, "flat")
                Gtk4DynamicBindings.gtkButtonSetLabel(btn, formattedTitle())
                LinuxGlibGioBindings.signalConnect(btn, "clicked", tabBtnClickedCb.reinterpret(), ref.asCPointer())
            }
            labelPtr = btn

            val verticalIconLayout = iconPlacementVal == TabIconPlacement.TOP
            val contentTarget: COpaquePointer = if (verticalIconLayout) {
                Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL, 2)?.also { vBox ->
                    Gtk4DynamicBindings.gtkBoxAppend(row, vBox)
                } ?: row
            } else {
                row
            }
            iconPic?.let { Gtk4DynamicBindings.gtkBoxAppend(contentTarget, it) }
            iconTextLbl?.let { Gtk4DynamicBindings.gtkBoxAppend(contentTarget, it) }
            btn?.let { Gtk4DynamicBindings.gtkBoxAppend(contentTarget, it) }

            val badgeText = nativeWidget.attributes["badgeText"] as? String
            if (!badgeText.isNullOrBlank()) {
                val bLbl = Gtk4DynamicBindings.gtkLabelNew(badgeText)
                if (bLbl != null) {
                    Gtk4DynamicBindings.gtkWidgetAddCssClass(bLbl, "badge")
                    Gtk4DynamicBindings.gtkBoxAppend(row, bLbl)
                    badgePtr = bLbl
                }
            }
        }

        val isClosable = (nativeWidget.attributes["closable"] as? Boolean ?: false) &&
            (nativeWidget.attributes["pinned"] as? Boolean != true)
        val closeBtn = Gtk4DynamicBindings.gtkButtonNewWithLabel("×")
        if (closeBtn != null) {
            Gtk4DynamicBindings.gtkWidgetAddCssClass(closeBtn, "flat")
            Gtk4DynamicBindings.gtkWidgetAddCssClass(closeBtn, "circular")
            LinuxGlibGioBindings.signalConnect(closeBtn, "clicked", tabCloseClickedCb.reinterpret(), ref.asCPointer())
            Gtk4DynamicBindings.gtkBoxAppend(row, closeBtn)
            Gtk4DynamicBindings.gtkWidgetSetVisible(closeBtn, isClosable)
            closeBtnPtr = closeBtn
        }

        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = row,
            focusTargetPtr = labelPtr ?: row,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = true,
            attachClickGesture = true
        )
        syncSelectedAppearance(row)
        syncGtkTabIcon()
        return row
    }

    private fun formattedTitle(): String {
        val rawTitle = (nativeWidget.attributes["title"] as? String).orEmpty()
        val dirty = nativeWidget.attributes["dirty"] as? Boolean == true
        val pinned = nativeWidget.attributes["pinned"] as? Boolean == true
        val prefix = if (pinned) "📌 " else ""
        val suffix = if (dirty) " •" else ""
        return "$prefix$rawTitle$suffix"
    }

    private fun syncGtkTabIcon() {
        val rawIcon = iconVal?.trim().orEmpty()
        val pic = iconPicPtr
        val txtLbl = iconTextLblPtr
        if (rawIcon.isEmpty()) {
            activeIconLoadJob?.cancel()
            activeIconLoadJob = null
            decodedIconBuffer = null
            lastLoadedIconKey = null
            pic?.let {
                Gtk4DynamicBindings.gtkPictureSetPaintable(it, null)
                Gtk4DynamicBindings.gtkWidgetSetVisible(it, false)
            }
            txtLbl?.let {
                Gtk4DynamicBindings.gtkLabelSetText(it, "")
                Gtk4DynamicBindings.gtkWidgetSetVisible(it, false)
            }
            return
        }

        if (!LinuxTabIconHelper.looksLikeImageOrVectorSpec(rawIcon)) {
            activeIconLoadJob?.cancel()
            activeIconLoadJob = null
            decodedIconBuffer = null
            lastLoadedIconKey = null
            pic?.let {
                Gtk4DynamicBindings.gtkPictureSetPaintable(it, null)
                Gtk4DynamicBindings.gtkWidgetSetVisible(it, false)
            }
            txtLbl?.let {
                Gtk4DynamicBindings.gtkLabelSetText(it, rawIcon)
                Gtk4DynamicBindings.gtkWidgetSetVisible(it, true)
            }
            return
        }

        txtLbl?.let {
            Gtk4DynamicBindings.gtkLabelSetText(it, "")
            Gtk4DynamicBindings.gtkWidgetSetVisible(it, false)
        }
        val iconSz = iconSizePxVal.coerceIn(12, 32)
        pic?.let {
            Gtk4DynamicBindings.gtkWidgetSetSizeRequest(it, iconSz, iconSz)
            Gtk4DynamicBindings.gtkWidgetSetVisible(it, true)
        }

        val loadKey = "$rawIcon@$iconSz"
        if (lastLoadedIconKey == loadKey && decodedIconBuffer != null) {
            pushIconBufferToPicture()
            return
        }

        activeIconLoadJob?.cancel()
        activeIconLoadJob = null
        val gen = ++iconLoadGeneration
        decodedIconBuffer = null
        lastLoadedIconKey = loadKey
        activeIconLoadJob = LinuxTabIconHelper.launchLoadTabIcon(
            iconSpec = rawIcon,
            targetSizePx = iconSz,
            onLoaded = { buf ->
                if (gen != iconLoadGeneration) return@launchLoadTabIcon
                decodedIconBuffer = buf
                GenericUiThreading.runOnUi {
                    if (gen != iconLoadGeneration) return@runOnUi
                    pushIconBufferToPicture()
                    onHeaderChanged?.invoke()
                }
            },
            onError = {
                if (gen != iconLoadGeneration) return@launchLoadTabIcon
                GenericUiThreading.runOnUi {
                    if (gen != iconLoadGeneration) return@runOnUi
                    iconPicPtr?.let {
                        Gtk4DynamicBindings.gtkPictureSetPaintable(it, null)
                        Gtk4DynamicBindings.gtkWidgetSetVisible(it, false)
                    }
                }
            }
        )
    }

    private fun pushIconBufferToPicture() {
        val pic = iconPicPtr ?: return
        val rawBuf = decodedIconBuffer ?: return
        val prepared = LinuxTabIconHelper.prepareTabIconBuffer(
            raw = rawBuf,
            sizePx = iconSizePxVal.coerceIn(12, 32),
            tintToTheme = tintImageToThemeVal,
            isDark = LinuxTabIconHelper.isDarkTheme()
        )
        val premulBgra = LinuxTabIconHelper.toPremulBgraBytes(prepared)
        val texture = Gtk4DynamicBindings.gdkMemoryTextureNewBgraPremul(prepared.width, prepared.height, premulBgra)
        if (texture != null) {
            Gtk4DynamicBindings.gtkPictureSetPaintable(pic, texture)
            Gtk4DynamicBindings.gtkWidgetSetVisible(pic, true)
            LinuxGlibGioBindings.objectUnref(texture)
        }
    }

    private fun syncSelectedAppearance(ptr: COpaquePointer? = nativeWidget.realGtkWidgetPtr) {
        ptr ?: return
        val selected = nativeWidget.attributes["selected"] as? Boolean == true
        if (selected) {
            Gtk4DynamicBindings.gtkWidgetAddCssClass(ptr, "active")
            Gtk4DynamicBindings.gtkWidgetAddCssClass(ptr, "suggested-action")
        } else {
            Gtk4DynamicBindings.gtkWidgetRemoveCssClass(ptr, "active")
            Gtk4DynamicBindings.gtkWidgetRemoveCssClass(ptr, "suggested-action")
        }
        labelPtr?.let { btn ->
            Gtk4DynamicBindings.gtkButtonSetLabel(btn, formattedTitle())
        }
        Gtk4DynamicBindings.gtkWidgetQueueDraw(ptr)
    }

    private fun rebuildChipIfLive() {
        val oldPtr = nativeWidget.realGtkWidgetPtr ?: run {
            onHeaderChanged?.invoke()
            return
        }
        nativeWidget.realGtkWidgetPtr = null
        val newPtr = materializeTabChip()
        if (newPtr != null) {
            GtkWidgetLifecycleBridge.swapLiveWidgetInParent(this, oldPtr, newPtr)
        }
        onHeaderChanged?.invoke()
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val customBase = customHeaderImpl as? DesktopBaseNativeImpl<*>
        if (customBase != null) {
            val cs = customBase.measureIntrinsicSize(maxWidth, maxHeight)
            val closeW = if (nativeWidget.attributes["closable"] as? Boolean == true) 26 else 0
            return Size(max(44, cs.width + 20 + closeW), max(34, cs.height + 10))
        }
        val (tw, th) = GtkSurfaceRenderHelper.measureTextSize(formattedTitle().ifEmpty { "Tab" }, 13.5f)
        val iconW = if (!iconVal.isNullOrBlank()) (iconSizePxVal.coerceIn(12, 32) + 6) else 0
        val badgeW = (nativeWidget.attributes["badgeText"] as? String)?.let { it.length * 7 + 16 } ?: 0
        val closeW = if (closableVal && !pinnedVal) 26 else 0
        return Size(max(64, tw + 28 + iconW + badgeW + closeW), max(34, max(th + 14, iconSizePxVal + 10)))
    }

    override fun setTitleNative(title: String) {
        super.setTitleNative(title)
        syncSelectedAppearance()
        onHeaderChanged?.invoke()
    }

    override fun setIconNative(icon: String?) {
        super.setIconNative(icon)
        syncGtkTabIcon()
        syncSelectedAppearance()
        onHeaderChanged?.invoke()
    }

    override fun setIconSizePxNative(sizePx: Int) {
        super.setIconSizePxNative(sizePx)
        syncGtkTabIcon()
        onHeaderChanged?.invoke()
    }

    override fun setIconPlacementNative(placement: TabIconPlacement) {
        super.setIconPlacementNative(placement)
        rebuildChipIfLive()
    }

    override fun setTintImageToThemeNative(tintToTheme: Boolean) {
        super.setTintImageToThemeNative(tintToTheme)
        pushIconBufferToPicture()
        onHeaderChanged?.invoke()
    }

    override fun setClosableNative(closable: Boolean) {
        super.setClosableNative(closable)
        val effectiveClosable = closable && !pinnedVal
        val cb = closeBtnPtr
        if (cb != null) {
            Gtk4DynamicBindings.gtkWidgetSetVisible(cb, effectiveClosable)
            onHeaderChanged?.invoke()
        } else {
            rebuildChipIfLive()
        }
    }

    override fun setDirtyNative(dirty: Boolean) {
        super.setDirtyNative(dirty)
        syncSelectedAppearance()
        onHeaderChanged?.invoke()
    }

    override fun setPinnedNative(pinned: Boolean) {
        super.setPinnedNative(pinned)
        closeBtnPtr?.let { Gtk4DynamicBindings.gtkWidgetSetVisible(it, closableVal && !pinned) }
        syncSelectedAppearance()
        onHeaderChanged?.invoke()
    }

    override fun setOnCloseClicked(listener: (() -> Unit)?) {
        localCloseListener = listener
        super.setOnCloseClicked(listener)
    }

    override fun setBadgeNative(badgeText: String?, variantCss: String?) {
        super.setBadgeNative(badgeText, variantCss)
        rebuildChipIfLive()
    }

    override fun setSelectedNative(selected: Boolean) {
        super.setSelectedNative(selected)
        syncSelectedAppearance()
    }

    override fun setCustomHeaderComponentNative(customImpl: NativeImpl<*>?) {
        super.setCustomHeaderComponentNative(customImpl)
        rebuildChipIfLive()
    }

    override fun setType(type: BootstrapType?) {
        super.setType(type)
        onHeaderChanged?.invoke()
    }

    override fun setBackgroundType(type: BootstrapType?) {
        super.setBackgroundType(type)
        onHeaderChanged?.invoke()
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super.applyComponentBootstrap(bootstrap)
        onHeaderChanged?.invoke()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super.applyComponentStyle(style)
        onHeaderChanged?.invoke()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        onHeaderChanged?.invoke()
    }

    override fun onNativeBoundsChanged(rect: Rect) = GtkWidgetLifecycleBridge.onBoundsChanged(this, rect)
    override fun onNativeVisibilityChanged(visible: Boolean) = GtkWidgetLifecycleBridge.onVisibilityChanged(this, visible)
    override fun onNativeEnabledChanged(enabled: Boolean) = GtkWidgetLifecycleBridge.onEnabledChanged(this, enabled)
    override fun onNativeOpacityChanged(opacity: Float) = GtkWidgetLifecycleBridge.onOpacityChanged(this, opacity)
    override fun onNativeTooltipChanged(tooltip: String?) = GtkWidgetLifecycleBridge.onTooltipChanged(this, tooltip)
    override fun onNativeCursorChanged(cursor: CssCursorValue?) = GtkWidgetLifecycleBridge.onCursorChanged(this, cursor)
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) = GtkWidgetLifecycleBridge.onPointerEventsChanged(this, pointerEventsNone)
    override fun onNativeZIndexChanged(zIndex: Int) = GtkWidgetLifecycleBridge.onZIndexChanged(this, zIndex)
    override fun onNativeTransformChanged(transform: CssTransform?) = GtkWidgetLifecycleBridge.onTransformChanged(this, transform)
    override fun onNativeStyleSynced() = GtkWidgetLifecycleBridge.onStyleSynced(this)

    override fun dispatchClick() {
        onSelectRequested?.invoke()
        super.dispatchClick()
    }

    override fun dispose() {
        activeIconLoadJob?.cancel()
        activeIconLoadJob = null
        decodedIconBuffer = null
        clickRef?.dispose()
        clickRef = null
        super.dispose()
    }

    companion object {
        private val tabBtnClickedCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { _, userData ->
            val impl = userData?.asStableRef<GtkTabNativeImpl>()?.get() ?: return@staticCFunction
            impl.dispatchClick()
        }

        private val tabCloseClickedCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { _, userData ->
            val impl = userData?.asStableRef<GtkTabNativeImpl>()?.get() ?: return@staticCFunction
            (impl.onCloseActionRequested ?: impl.localCloseListener)?.invoke()
        }
    }
}
