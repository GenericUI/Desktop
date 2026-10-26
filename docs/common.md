# `:desktop:common` — Cross-Desktop Infrastructure (`commonMain`)

## Overview
`:desktop:common` is the platform-agnostic KMP `commonMain` module for all five desktop backends (`:desktop:windows`, `:desktop:macos`, `:desktop:gtk`, `:desktop:qt`, and `:desktop:swing`). It targets `jvm()`, `mingwX64()`, `macosX64()`, `macosArm64()`, and `linuxX64()`, and depends on `:common` and `:systemCommon`.

---

## Core Responsibilities

| Class | Package | Description |
|-------|---------|-------------|
| `DesktopLazyViewportEngine` | `com.nexustools.genericui.desktop` | Viewport range math, item-height indexing, and widget recycling engine for virtualized lists and grids |
| `DesktopFileBrowserHelper` | `com.nexustools.genericui.desktop` | Common path navigation, wildcard/MIME filter matching, and directory sorting for file browser dialogs |
| `DesktopFileBrowserDialogNativeImpl` | `com.nexustools.genericui.desktop.dialog` | Base file/directory picker dialog implementation across desktop targets |
| `DesktopStandardDialogsNativeImpl` | `com.nexustools.genericui.desktop.dialog` | Common alert, confirmation, prompt, and message box dialog routing |
| `DesktopUiTimerNativeImpl` | `com.nexustools.genericui.desktop.timer` | Repeating and single-shot UI timer scheduling for desktop event loops |
| `DesktopNetworkMonitorNativeImpl` | `com.nexustools.genericui.desktop.system` | Desktop network connectivity state tracking and change notifications |
| `DesktopNotificationManagerNativeImpl` | `com.nexustools.genericui.desktop.system` | Desktop OS notification dispatch abstraction |
| `DesktopPermissionManagerNativeImpl` | `com.nexustools.genericui.desktop.system` | Desktop capability and permission status reporting |
| `DesktopSystemTrayNativeImpl` | `com.nexustools.genericui.desktop.system` | System tray icon, tooltip, and context menu management |

---

## Dependency Graph
```text
:common
  └── :systemCommon
        └── :desktop:common
              ├── :desktop:swing (JVM)
              └── :desktop:nativeCommon (cinterop: Win32, Cocoa, GTK, Qt)
```
