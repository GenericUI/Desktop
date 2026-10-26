# `:desktop:common` — Pure-Kotlin Desktop Common Runtime

> **Parent Submodule**: [`:desktop` (`../README.md`)](../README.md) | **Root Repository**: [**GenericUI (`../../README.md`)**](../../README.md)  
> **Sibling Desktop Modules**: [`:desktop:nativeCommon`](../nativeCommon/README.md) · [`:desktop:linuxCommon`](../linuxCommon/README.md) · [`:desktop:windows`](../docs/windows.md) · [`:desktop:macos`](../docs/macos.md) · [`:desktop:gtk`](../docs/gtk.md) · [`:desktop:qt`](../docs/qt.md) · [`:desktop:swing`](../docs/swing.md)

The **`:desktop:common`** module (`com.nexustools.genericui.desktop.*`) is the pure-Kotlin desktop foundation inherited by **all 5 desktop backends** (`:desktop:windows`, `:desktop:macos`, `:desktop:gtk`, `:desktop:qt`, and `:desktop:swing`). It backs the **`desktopCommon`** variant scope (`AppTarget.DESKTOP_COMMON`, specificity rank `2`) in the sourceSet hierarchy (`common` $\rightarrow$ `systemCommon` $\rightarrow$ `desktopCommon`).

---

## Core Responsibilities & Classes

Because `:desktop:common` targets both Kotlin/Native (`mingwX64`, `macosX64`, `macosArm64`, `linuxX64`, `linuxArm64`) and the JVM (`:desktop:swing`), it contains pure-Kotlin desktop models and engines that work identically across native OS and JVM desktop environments:

| Package | Class / Object | Responsibility |
| :--- | :--- | :--- |
| `com.nexustools.genericui.desktop.lazy` | `DesktopLazyViewportEngine` | Calculates the visible item window `[startIdx .. endIdx]` from `scrollOffsetPx`, `viewportExtentPx`, `estimatedItemExtentPx`, `columns`, and `overscanItems`, mounting only visible items and immediately unmounting/disposing offscreen items. |
| `com.nexustools.genericui.desktop.dialog` | `DesktopFileBrowserHelper` | Converts `FileBrowserRequest` and `FileTypeFilter` lists into Win32 `COMDLG_FILTERSPEC` pairs, macOS `UTType`/extension lists, GTK4 `GtkFileFilter` glob patterns, and Qt6 `QFileDialog` filter strings. |
| `com.nexustools.genericui.desktop.dialog` | `DesktopFileBrowserDialogNativeImpl` | Base desktop `FileBrowserDialogNativeImpl` managing active dialog requests, filter validation, and `SelectedFile` result dispatch. |
| `com.nexustools.genericui.desktop.dialog` | `DesktopStandardDialogsNativeImpl` | Base desktop `StandardDialogsNativeImpl` for alert, confirm, and prompt modal dialogs. |
| `com.nexustools.genericui.desktop.system` | `DesktopUiTimerNativeImpl` | Lifecycle-bound one-shot and repeating desktop UI timer implementation used by `:common`, `Fragment`, and `:extensions:overlay`. |
| `com.nexustools.genericui.desktop.system` | `DesktopSystemTrayNativeImpl` | Base desktop system tray icon and context menu peer. |
| `com.nexustools.genericui.desktop.system` | `DesktopNotificationManagerNativeImpl` | Base desktop OS notification manager peer. |
| `com.nexustools.genericui.desktop.system` | `DesktopNetworkMonitorNativeImpl` | Base desktop network reachability observer peer. |
| `com.nexustools.genericui.desktop.system` | `DesktopPermissionManagerNativeImpl` | Desktop permission manager (desktop OSes grant standard hardware/storage permissions without mobile runtime prompts). |

---

## Detailed Documentation

See [**`../docs/common.md`**](../docs/common.md) for architecture details and code examples.
