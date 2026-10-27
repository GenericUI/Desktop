# Desktop Common Modules (`:desktop:common`, `:desktop:nativeCommon`, `:desktop:linuxCommon`)

## Overview
Desktop cross-backend code in GenericUI is organized into three layered modules above `:systemCommon`:

1. **[`:desktop:common`](common.md)** (`commonMain` — JVM, MinGW, macOS, Linux):
   - `DesktopLazyViewportEngine` (`com.nexustools.genericui.desktop`)
   - `DesktopFileBrowserHelper` (`com.nexustools.genericui.desktop`)
   - `DesktopFileBrowserDialogNativeImpl` & `DesktopStandardDialogsNativeImpl` (`com.nexustools.genericui.desktop.dialog`)
   - `DesktopUiTimerNativeImpl` (`com.nexustools.genericui.desktop.timer`)
   - `DesktopNetworkMonitorNativeImpl`, `DesktopNotificationManagerNativeImpl`, `DesktopPermissionManagerNativeImpl`, `DesktopSystemTrayNativeImpl` (`com.nexustools.genericui.desktop.system`)

2. **[`:desktop:nativeCommon`](nativeCommon.md)** (`mingwX64`, `macosX64`, `macosArm64`, `linuxX64`):
   - `DesktopBaseNativeImpl` (`BootstrapType.NONE` default, semantic `BootstrapType` tinting via `resolveSemanticBootstrapColor`, strict user `customClasses` isolation, `ComponentStyle` & `accentColor`, `CssCustomCursor`), `DesktopBaseContainerNativeImpl`, `DesktopNativeWidget`, `DesktopWindowNativeImpl`, `DesktopApplicationNativeImpl` (`com.nexustools.genericui.desktop`)
   - `DesktopMultiWindowDockCoordinator`, `DesktopDockWorkspaceNativeImpl`, `DesktopLazyListNativeImpl`, `DesktopLazyGridNativeImpl`, `DesktopTreeViewNativeImpl` (`com.nexustools.genericui.desktop.container`)
   - `DesktopConfigDirectoryHelper`, `DesktopUserDirectoryNativeImpl` (`com.nexustools.genericui.desktop.system`)
   - `DesktopLibJpegImageCodecImpl` (`com.nexustools.genericui.desktop.image`)

3. **[`:desktop:linuxCommon`](linuxCommon.md)** (`linuxX64` — used by `:desktop:gtk` and `:desktop:qt`):
   - `LinuxXdgConfigDirectoryImpl`, `LinuxXdgCacheDirectoryImpl`, `LinuxXdgPreferenceStoreImpl` (`com.nexustools.genericui.linux`)
