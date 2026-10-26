# `:desktop:nativeCommon` — Native Desktop Common Runtime

> **Parent Submodule**: [`:desktop` (`../README.md`)](../README.md) | **Root Repository**: [**GenericUI (`../../README.md`)**](../../README.md)  
> **Sibling Desktop Modules**: [`:desktop:common`](../common/README.md) · [`:desktop:linuxCommon`](../linuxCommon/README.md) · [`:desktop:windows`](../docs/windows.md) · [`:desktop:macos`](../docs/macos.md) · [`:desktop:gtk`](../docs/gtk.md) · [`:desktop:qt`](../docs/qt.md) · [`:desktop:swing`](../docs/swing.md)

The **`:desktop:nativeCommon`** module (`com.nexustools.genericui.desktop.*`) is the Kotlin/Native desktop runtime included by the four native OS desktop backends: **`:desktop:windows`** (`mingwX64`), **`:desktop:macos`** (`macosArm64`, `macosX64`), **`:desktop:gtk`** (`linuxX64`, `linuxArm64`), and **`:desktop:qt`** (`linuxX64`, `linuxArm64`). It backs the canonical **`nativeDesktopCommon`** variant scope (`AppTarget.NATIVE_DESKTOP_COMMON`, specificity rank `3`) in the sourceSet hierarchy (`common` $\rightarrow$ `systemCommon` $\rightarrow$ `desktopCommon` $\rightarrow$ `nativeDesktopCommon`).

---

## Core Responsibilities & Classes

| Package | Class / Object | Responsibility |
| :--- | :--- | :--- |
| `com.nexustools.genericui.desktop.core` | `DesktopNativeWidget` | Native widget descriptor tracking platform family, widget type, native pointer/handle attributes, bounds, padding/margin, visibility, enabled state, and child hierarchy. |
| `com.nexustools.genericui.desktop.core` | `DesktopBaseNativeImpl<W>` | Base `NativeImpl<W>` peer for native desktop widgets; manages `BootstrapStyleSnapshot`, `ComputedStyle`, `CssTransitionSpec`, pointer enter/leave (`onPointerEnter` / `onPointerLeave`), and input event listeners. |
| `com.nexustools.genericui.desktop.core` | `DesktopBaseContainerNativeImpl<W>` | Extends `DesktopBaseNativeImpl<W>` with child `NativeImpl` attachment (`addChildNative`, `removeChildNative`, `clearChildrenNative`) and layout invalidation. |
| `com.nexustools.genericui.desktop.shell` | `DesktopWindowNativeImpl` | Base class for top-level native desktop windows (`TitleCapabilities`, `TitleBar` instruction application, single-placement `mountedInTitleBar` component marking, menu bar sync, fullscreen/maximized state, VBlank scheduling). |
| `com.nexustools.genericui.desktop.shell` | `DesktopApplicationNativeImpl` | Base class for native desktop `Application` lifecycle, theme color/mode, and window management. |
| `com.nexustools.genericui.desktop.dock` | `DesktopMultiWindowDockCoordinator` | Orchestrates GIMP-style runtime switching between `StudioWindowMode.SINGLE_WINDOW_MODE` and `StudioWindowMode.MULTI_WINDOW_MODE` (detaching/reattaching `DockPanel`s to native floating utility windows). |
| `com.nexustools.genericui.desktop.dock` | `DesktopDockWorkspaceNativeImpl` | Native peer for `DockWorkspace` integrating `DesktopMultiWindowDockCoordinator` with platform `DockWindowNativeImpl` factories. |
| `com.nexustools.genericui.desktop.lazy` | `DesktopLazyListNativeImpl`, `DesktopLazyGridNativeImpl`, `DesktopTreeViewNativeImpl` | Native peers for `LazyList`, `LazyGrid`, and `TreeView` powered by `:desktop:common`'s `DesktopLazyViewportEngine`. |
| `com.nexustools.genericui.desktop.system` | `DesktopConfigDirectoryHelper`, `DesktopUserDirectoryNativeImpl` | Resolves OS preference and cache directories (`%APPDATA%` on Windows, `~/Library/Preferences` on macOS, `$XDG_CONFIG_HOME` on Linux) and provides POSIX binary file I/O for `PreferenceStore`. |
| `com.nexustools.genericui.desktop.image` | `DesktopLibJpegImageCodecImpl`, `DesktopImageCodecHooks` | Native `libjpeg` / `libpng` / `libwebp` image decoding/encoding hooks paired with platform codecs. |
| `com.nexustools.genericui.desktop.widget` | `DesktopButtonNativeImpl`, `DesktopTextInputNativeImpl`, `DesktopTableNativeImpl`, etc. | Complete suite of native desktop widget peers inherited by `:windows`, `:macos`, `:gtk`, and `:qt`. |

---

## Detailed Documentation

See [**`../docs/nativeCommon.md`**](../docs/nativeCommon.md) for architecture details and code examples.
