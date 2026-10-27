# `:desktop:common` — Cross-Desktop Common Foundation (`commonMain`)

## Overview
`:desktop:common` is the cross-desktop KMP module shared by all five desktop backends (`:desktop:windows`, `:desktop:macos`, `:desktop:gtk`, `:desktop:qt`, and `:desktop:swing`). It targets `jvm()`, `mingwX64()`, `macosX64()`, `macosArm64()`, and `linuxX64()`, and depends on `:common` and `:systemCommon`.

---

## Key Packages & Classes

- **`com.nexustools.genericui.desktop`**:
  - `DesktopLazyViewportEngine` — Virtualized scroll viewport calculation and item recycling engine for `LazyList` and `LazyGrid` across all desktop targets.
  - `DesktopFileBrowserHelper` — Cross-desktop path normalization, extension filtering, and breadcrumb sorting for file browser dialogs.
- **`com.nexustools.genericui.desktop.dialog`**:
  - `DesktopFileBrowserDialogNativeImpl` — Base desktop file/folder picker dialog implementation.
  - `DesktopStandardDialogsNativeImpl` — Base desktop alert, confirmation, and prompt dialog implementation.
- **`com.nexustools.genericui.desktop.timer`**:
  - `DesktopUiTimerNativeImpl` — Desktop UI timer scheduling abstraction.
- **`com.nexustools.genericui.desktop.system`**:
  - `DesktopNetworkMonitorNativeImpl` — Desktop network connectivity monitor.
  - `DesktopNotificationManagerNativeImpl` — Desktop system notification dispatcher.
  - `DesktopPermissionManagerNativeImpl` — Desktop permission status manager.
  - `DesktopSystemTrayNativeImpl` — Desktop system tray icon and menu host.

See [`../docs/common.md`](../docs/common.md) for additional details.
