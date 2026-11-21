# `:desktop` Common Layer (`commonMain` / `desktopCommon`)

> **Module Guide**: [**`:desktop` (`../README.md`)**](../README.md) | **Related**: [`shared.md`](shared.md) · [`nativeCommon.md`](nativeCommon.md) · [`linuxCommon.md`](linuxCommon.md)

## Overview
The `commonMain` source set of `:desktop` (`desktopCommon` variant scope) holds platform-agnostic pure-Kotlin desktop infrastructure shared across all five desktop targets (`Windows`, `MacOS`, `Gtk`, `Qt`, and `Swing`). It targets `jvm()`, `mingwX64()`, `macosArm64()`, `linuxX64()`, and `linuxArm64()`, sitting directly above `:common` and `:system`.

---

## Core Responsibilities

| Class | Package | Description |
|-------|---------|-------------|
| `DesktopLazyViewportEngine` | `com.nexustools.genericui.desktop.lazy` | Viewport range math, item-height indexing, and widget recycling engine for virtualized lists and grids |
| `DesktopFileBrowserHelper` | `com.nexustools.genericui.desktop.dialog` | Common path navigation, wildcard/MIME filter matching, and directory sorting for file browser dialogs |
| `DesktopFileBrowserDialogNativeImpl` | `com.nexustools.genericui.desktop.dialog` | Base file/directory picker dialog implementation across desktop targets |
| `DesktopStandardDialogsNativeImpl` | `com.nexustools.genericui.desktop.dialog` | Common alert, confirmation, prompt, and message box dialog routing |
| `DesktopUiTimerNativeImpl` | `com.nexustools.genericui.desktop.timer` | Repeating and single-shot UI timer scheduling for desktop event loops |
| `DesktopNetworkMonitorNativeImpl` | `com.nexustools.genericui.desktop.system` | Desktop network connectivity state tracking and change notifications |
| `DesktopNotificationManagerNativeImpl` | `com.nexustools.genericui.desktop.system` | Desktop OS notification dispatch abstraction |
| `DesktopPermissionManagerNativeImpl` | `com.nexustools.genericui.desktop.system` | Desktop capability and permission status reporting |
| `DesktopSystemTrayNativeImpl` | `com.nexustools.genericui.desktop.system` | System tray icon, tooltip, click/activation, and context menu management (backing `ServiceWorker` tray presence on desktop targets) |

---

## Dependency Graph
```text
:common
  └── :system
        └── :desktop (commonMain / desktopCommon)
              ├── jvmMain (JVM Swing, + :jvm)
              └── nativeDesktopMain (Native cinterop: Win32, macOS + :apple, Linux GTK4 & Qt6)
```
