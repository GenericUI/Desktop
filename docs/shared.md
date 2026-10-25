# `:desktop:shared` — Shared Native Desktop Runtime

> **Parent Submodule**: [`:desktop` (`../README.md`)](../README.md) | **Root Repository**: [**GenericUI (`../../README.md`)**](../../README.md)  
> **Root Docs**: [SourceSet Hierarchy](../../docs/sourcesets.md) · [Backends Guide](../../docs/backends.md) · [Extensions Guide](../../docs/extensions.md) · [Gradle Plugin](../../docs/gradle-plugin.md) · [UI Testing](../../docs/testing.md)  
> **Sibling Desktop Backends**: [`:desktop:windows`](./windows.md) · [`:desktop:macos`](./macos.md) · [`:desktop:gtk`](./gtk.md) · [`:desktop:qt`](./qt.md) · [`:desktop:swing`](./swing.md)

The **`:desktop:shared`** module (`com.nexustools.genericui.desktop.*`) is the shared Kotlin Multiplatform native desktop foundation automatically included by **`:desktop:windows`**, **`:desktop:macos`**, **`:desktop:gtk`**, and **`:desktop:qt`**. It backs the **`desktopNativeCommon`** sourceSet tier (`systemCommon` $\rightarrow$ `desktopCommon` $\rightarrow$ `desktopNativeCommon`).

> [!IMPORTANT]
> **Zero VLC or Media Code in `:desktop:shared`**
> `:desktop:shared` contains **NO** VLC (`libvlc`) or media playback code whatsoever. All native desktop VLC bindings (`LibVlcBindings`, `DesktopLibVlcVideoPlayerImpl`, `DesktopLibVlcAudioPlayerImpl`, `DesktopLibVlcAudioAnalyzerImpl`), HDR tone-mapping shaders, and media clocks reside strictly in **`:extensions:media:desktop:shared`** ([`../../extensions/docs/media.md`](../../extensions/docs/media.md)).

---

## Core Architecture & Responsibilities

`:desktop:shared` eliminates boilerplate across the four native OS desktop backends by providing unified implementations for:

1. **Dual-Mode Styling & Input Dispatch (`DesktopBaseNativeImpl`, `DesktopBaseContainerNativeImpl`, `DesktopNativeWidget`)**:
   - Maintains bidirectional synchronization between Bootstrap 5 enums (`BootstrapType`, `BootstrapSize`, `SpacingScale`, `BorderRadiusStyle`, `ShadowElevation`, Flexbox enums) and CSS class strings via `BootstrapClassMapper`.
   - Resolves state-aware `ComputedStyle` rules (`:hover`, `:focus`, `:active`, `:disabled`) from compiled SASS/CSS stylesheets.
   - Dispatches pointer (`PointerEvent`), wheel (`WheelEvent`), keyboard (`KeyEvent`), context-menu, and click events to common widgets.
2. **True Lazy Viewport Virtualization (`DesktopLazyViewportEngine`)**:
   - Powers `DesktopLazyListNativeImpl`, `DesktopLazyGridNativeImpl`, and `DesktopTreeViewNativeImpl` across `:windows`, `:macos`, `:gtk`, and `:qt`.
   - Computes the active visible index range `[startIdx .. endIdx]` from `scrollOffsetPx`, `viewportExtentPx`, `estimatedItemExtentPx`, `columns`, and `overscanItems`.
   - Dynamically mounts (`onMountItem`) only items entering the visible window and immediately unmounts + disposes (`onUnmountItem` + `dispose()`) items scrolling out of view.
3. **Single-Window vs. Multi-Window Studio Docking (`DesktopMultiWindowDockCoordinator`, `DesktopDockWorkspaceNativeImpl`)**:
   - Coordinates GIMP / Photoshop style runtime switching between **`StudioWindowMode.SINGLE_WINDOW_MODE`** (all panels docked inside the primary `DockWorkspace` split areas) and **`StudioWindowMode.MULTI_WINDOW_MODE`** (panels detached into independent native OS utility windows via `DockWindowNativeImpl`).
4. **Cross-Desktop File & Folder Browser Formatting (`DesktopFileBrowserHelper`)**:
   - Translates `FileBrowserRequest` and `FileTypeFilter` lists into platform-native filter descriptors for Win32 `COMDLG_FILTERSPEC`, macOS `NSOpenPanel` `UTType`/extensions, GTK4 `GtkFileFilter` glob lists, and Qt6 `QFileDialog` filter strings, and normalizes selected paths into `SelectedFile` objects.
5. **Base Native Image Codecs (`DesktopLibJpegImageCodecImpl`, `DesktopImageCodecHooks`)**:
   - Provides shared `libjpeg` / `libpng` / `libwebp` native codec hooks that pair with platform image loaders (`WIC` on Windows, `ImageIO` on macOS, `GdkPixbuf` on GTK, `QImage` on Qt).

---

## Key Classes Reference

| Package | Class / Object | Responsibility |
| :--- | :--- | :--- |
| `com.nexustools.genericui.desktop.core` | `DesktopNativeWidget` | Lightweight native widget descriptor tracking platform family, widget type, native pointer/handle attributes, bounds, padding/margin, visibility, enabled state, and child widget hierarchy. |
| `com.nexustools.genericui.desktop.core` | `DesktopBaseNativeImpl<W>` | Base `NativeImpl<W>` peer for all native desktop widgets; manages `BootstrapStyleSnapshot`, `ComputedStyle`, `CssTransitionSpec`, geometry, and input event listeners. |
| `com.nexustools.genericui.desktop.core` | `DesktopBaseContainerNativeImpl<W>` | Extends `DesktopBaseNativeImpl<W>` with child `NativeImpl` attachment (`addChildNative`, `insertChildNative`, `removeChildNative`, `clearChildrenNative`) and layout invalidation. |
| `com.nexustools.genericui.desktop.lazy` | `DesktopLazyViewportEngine` | Calculates visible row/column windows (`computeVisibleRange()`) and manages mounted `NativeImpl` lifecycle (`configureItems`, `updateScrollAndViewport`, `scrollToIndex`, `notifyRangeChanged`, `unmountAll`). |
| `com.nexustools.genericui.desktop.lazy` | `DesktopLazyListNativeImpl` | Shared native peer for `LazyList` (`LazyColumn` / `LazyRow`) backed by `DesktopLazyViewportEngine`. |
| `com.nexustools.genericui.desktop.lazy` | `DesktopLazyGridNativeImpl` | Shared native peer for multi-column `LazyGrid` backed by `DesktopLazyViewportEngine`. |
| `com.nexustools.genericui.desktop.lazy` | `DesktopTreeViewNativeImpl` | Shared native peer for hierarchical `TreeView` with flattened visible node virtualization. |
| `com.nexustools.genericui.desktop.dock` | `DesktopMultiWindowDockCoordinator` | Orchestrates `detachPanelToFloatingWindow`, `reattachPanelToWorkspace`, and `setStudioWindowMode` (`SINGLE_WINDOW_MODE` vs. `MULTI_WINDOW_MODE`). |
| `com.nexustools.genericui.desktop.dock` | `DesktopDockWorkspaceNativeImpl` | Native peer for `DockWorkspace` integrating `DesktopMultiWindowDockCoordinator` with platform `DockWindowNativeImpl` factories. |
| `com.nexustools.genericui.desktop.dialog` | `DesktopFileBrowserHelper` | Converts `FileBrowserRequest` filters into Win32, macOS, GTK4, and Qt6 native dialog formats and dispatches `SelectedFile` results. |
| `com.nexustools.genericui.desktop.shell` | `DesktopWindowNativeImpl` | Shared base class for top-level desktop windows (`TitleBar` instruction application, menu bar synchronization, fullscreen/maximized state, VBlank scheduling). |

---

## SourceSet Hierarchy & Dependency Wiring

`:desktop:shared` compiles for `mingwX64`, `macosX64`, `macosArm64`, `linuxX64`, and `linuxArm64`. When you enable any native desktop backend (`windows()`, `macos()`, `gtk()`, or `qt()`) in `genericui { backends { ... } }`, the Gradle plugin automatically wires `:desktop:shared` into the **`desktopNativeCommonMain`** sourceSet:

```
commonMain
 └── systemCommonMain
      └── desktopCommonMain
           ├── swingMain (:desktop:swing)
           └── desktopNativeCommonMain (:desktop:shared)
                ├── windowsMain (:desktop:windows)
                ├── macMain (:desktop:macos)
                └── linuxMain
                     ├── gtkMain (:desktop:gtk)
                     └── qtMain (:desktop:qt)
```

---

## Code Examples

### 1. Extending `DesktopBaseNativeImpl` and `DesktopBaseContainerNativeImpl`

Platform backends (`:windows`, `:macos`, `:gtk`, `:qt`) subclass `DesktopBaseNativeImpl` or `DesktopBaseContainerNativeImpl` to bridge OS widget handles (`HWND`, `NSView*`, `GtkWidget*`, `QWidget*`) with GenericUI's styling and event pipeline:

```kotlin
package com.example.desktop.custom

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.style.css.ComputedStyle

class CustomStatusBadgeNativeImpl : DesktopBaseNativeImpl<DesktopNativeWidget>(
    nativeWidget = DesktopNativeWidget(platform = "Desktop", widgetType = "StatusBadge"),
    componentClassPrefix = "badge"
) {
    init {
        setType(BootstrapType.INFO)
        addClass("rounded-pill px-3 py-1")
    }

    override fun onStyleChanged() {
        super.onStyleChanged()
        val effectiveBg = getEffectiveBackgroundColor()
        val effectiveTint = getEffectiveTintColor()
        // Synchronize resolved colors and Bootstrap classes with the OS widget handle
        nativeWidget.attributes["resolvedBg"] = effectiveBg
        nativeWidget.attributes["resolvedTint"] = effectiveTint
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        requestRepaintNative()
    }
}
```

### 2. True Lazy Viewport Windowing with `DesktopLazyViewportEngine`

`DesktopLazyViewportEngine` guarantees that a 100,000-row table or asset grid only instantiates `NativeImpl` peers for the rows currently visible on screen plus `overscanItems`:

```kotlin
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.lazy.DesktopLazyViewportEngine

val viewportEngine = DesktopLazyViewportEngine(
    estimatedItemExtentPx = 48f,
    overscanItems = 3,
    columns = 4 // 4-column grid layout
)

// Configure 50,000 items; only the initial visible window (e.g., indices 0..75) is mounted:
viewportEngine.configureItems(
    count = 50_000,
    factory = { index ->
        DesktopBaseNativeImpl(
            DesktopNativeWidget("Desktop", "GridCell").apply {
                text = "Asset #$index"
            }
        )
    },
    onMountItem = { index, nativeImpl ->
        // Attach native widget handle to the scrollable viewport container
        println("Mounted visible cell #$index -> ${nativeImpl.nativeWidget.text}")
    },
    onUnmountItem = { index, _ ->
        // Detach native widget handle before dispose() is called automatically
        println("Unmounted offscreen cell #$index")
    }
)

// When the user scrolls to offset 2,400px in an 800px-tall viewport:
viewportEngine.updateScrollAndViewport(
    newScrollOffsetPx = 2400f,
    newViewportExtentPx = 800f
)
println("Active visible range: ${viewportEngine.visibleRange}")
println("Currently mounted count: ${viewportEngine.mountedIndices.size}")
```

### 3. Single-Window vs. Multi-Window Docking with `DesktopMultiWindowDockCoordinator`

`DesktopMultiWindowDockCoordinator` powers `DockWorkspaceFragment` (used by **HAEditor**) so users can toggle seamlessly between GIMP Single-Window Mode and Multi-Window Mode at runtime:

```kotlin
import com.nexustools.genericui.desktop.dock.DesktopMultiWindowDockCoordinator
import com.nexustools.genericui.enums.dock.DockPosition
import com.nexustools.genericui.enums.dock.StudioWindowMode
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.nativeimpl.dock.DockPanelNativeImpl

fun configureStudioDocking(
    coordinator: DesktopMultiWindowDockCoordinator,
    toolboxPanel: DockPanelNativeImpl<*>,
    layersPanel: DockPanelNativeImpl<*>
) {
    // Detach a single panel into its own native OS utility window:
    val floatingToolbox = coordinator.detachPanelToFloatingWindow(
        panel = toolboxPanel,
        bounds = Rect(x = 40f, y = 80f, width = 280f, height = 640f)
    )

    // Switch the entire workspace to GIMP-style Multi-Window Mode:
    coordinator.setStudioWindowMode(
        mode = StudioWindowMode.MULTI_WINDOW_MODE,
        workspace = null,
        panels = listOf(toolboxPanel, layersPanel)
    )

    // Re-dock all floating utility windows back into the main workspace window:
    coordinator.setStudioWindowMode(
        mode = StudioWindowMode.SINGLE_WINDOW_MODE,
        workspace = null,
        panels = listOf(toolboxPanel, layersPanel)
    )
}
```

### 4. Translating Native File & Folder Filters with `DesktopFileBrowserHelper`

`DesktopFileBrowserHelper` converts a cross-platform `FileBrowserRequest` into exact OS filter descriptors for Win32, macOS, GTK4, and Qt6:

```kotlin
import com.nexustools.genericui.component.dialog.FileBrowserRequest
import com.nexustools.genericui.component.dialog.FileTypeFilter
import com.nexustools.genericui.desktop.dialog.DesktopFileBrowserHelper
import com.nexustools.genericui.enums.dialog.FileBrowserMode

val request = FileBrowserRequest(
    title = "Open Project or Artwork",
    mode = FileBrowserMode.OPEN_MULTIPLE_FILES,
    filters = listOf(
        FileTypeFilter(
            description = "HAEditor & Raster Artwork",
            extensions = listOf("hacanvas", "psd", "xcf", "ora", "png", "webp"),
            macOsUniformTypeIdentifiers = listOf("public.png", "com.adobe.photoshop-image")
        ),
        FileTypeFilter.ALL_FILES
    )
)

// 1. Win32 IFileOpenDialog COMDLG_FILTERSPEC pairs:
//    [("HAEditor & Raster Artwork", "*.hacanvas;*.psd;*.xcf;*.ora;*.png;*.webp"), ("All Files", "*.*")]
val win32Specs = DesktopFileBrowserHelper.toWin32FilterSpecs(request)

// 2. macOS NSOpenPanel allowed content types / extensions:
val macTypes = DesktopFileBrowserHelper.toMacOsAllowedTypes(request)

// 3. GTK4 GtkFileFilter glob patterns:
val gtkPatterns = DesktopFileBrowserHelper.toGtkFilterPatterns(request)

// 4. Qt6 QFileDialog filter string:
//    "HAEditor & Raster Artwork (*.hacanvas *.psd *.xcf *.ora *.png *.webp);;All Files (*)"
val qtFilterString = DesktopFileBrowserHelper.toQtFilterString(request)
```

---

## Related Documentation

- **Parent Desktop Overview**: [`../README.md`](../README.md)
- **Desktop Platform Backends**:
  - [Windows Win32 (`:desktop:windows`)](./windows.md)
  - [macOS AppKit (`:desktop:macos`)](./macos.md)
  - [Linux GTK4 (`:desktop:gtk`)](./gtk.md)
  - [Linux Qt6 (`:desktop:qt`)](./qt.md)
  - [Java/JVM Swing (`:desktop:swing`)](./swing.md)
- **Root GenericUI Documentation**:
  - [Root README (`../../README.md`)](../../README.md)
  - [SourceSet Hierarchy (`../../docs/sourcesets.md`)](../../docs/sourcesets.md)
  - [Backends Architecture (`../../docs/backends.md`)](../../docs/backends.md)
  - [Extensions Guide (`../../docs/extensions.md`)](../../docs/extensions.md)
  - [Media & VLC Extension (`../../extensions/docs/media.md`)](../../extensions/docs/media.md)
  - [Gradle Plugin (`../../docs/gradle-plugin.md`)](../../docs/gradle-plugin.md)
  - [UI Testing (`../../docs/testing.md`)](../../docs/testing.md)
- **Sibling Submodules**: [`:mobile`](../../mobile/README.md) · [`:web`](../../web/README.md) · [`:tests`](../../tests/README.md) · [`:NJS`](../../lib/NJS/README.md)
