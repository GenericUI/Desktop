# Desktop Shared SourceSet Layers (`commonMain`, `nativeDesktopMain`, `linuxMain`)

> **Module Guide**: [**`:desktop` (`../README.md`)**](../README.md)

## Overview
Cross-backend desktop code inside the consolidated `:desktop` KMP module is layered cleanly across three shared source sets above `:system`:

1. **[`commonMain` (`desktopCommon`, `common.md`)](common.md)** — all 5 desktop targets (`jvm`, `mingwX64`, `macosArm64`, `linuxX64`, `linuxArm64`):
   - `DesktopLazyViewportEngine` (`com.nexustools.genericui.desktop.lazy`)
   - `DesktopFileBrowserHelper`, `DesktopFileBrowserDialogNativeImpl`, `DesktopStandardDialogsNativeImpl` (`com.nexustools.genericui.desktop.dialog`)
   - `DesktopUiTimerNativeImpl` (`com.nexustools.genericui.desktop.timer`)
   - `DesktopNetworkMonitorNativeImpl`, `DesktopNotificationManagerNativeImpl`, `DesktopPermissionManagerNativeImpl`, `DesktopSystemTrayNativeImpl` (`com.nexustools.genericui.desktop.system`)

2. **[`nativeDesktopMain` (`nativeDesktopCommon`, `nativeCommon.md`)](nativeCommon.md)** — `mingwX64`, `macosArm64`, `linuxX64`, `linuxArm64`:
   - `DesktopBaseNativeImpl`, `DesktopBaseContainerNativeImpl`, `DesktopNativeWidget` (`com.nexustools.genericui.desktop.core`)
   - `DesktopRenderableNativeImpl`, `DesktopControlTintRenderUtil` (`com.nexustools.genericui.desktop.render`)
   - `DesktopStyledContainerNativeImpl`, `DesktopStyledButtonNativeImpl` (`com.nexustools.genericui.desktop.widget`, forwarding CSS `backdropFilter` while `TextBubble` strips it)
   - `DesktopWindowNativeImpl`, `DesktopApplicationNativeImpl` (`com.nexustools.genericui.desktop.shell`)
   - `DesktopMultiWindowDockCoordinator`, `DesktopDockWorkspaceNativeImpl` (`com.nexustools.genericui.desktop.dock`)
   - `DesktopLazyListNativeImpl`, `DesktopLazyGridNativeImpl`, `DesktopTreeViewNativeImpl` (`com.nexustools.genericui.desktop.lazy`)
   - `DesktopConfigDirectoryHelper`, `DesktopUserDirectoryNativeImpl` (`com.nexustools.genericui.desktop.system`)
   - `DesktopLibJpegImageCodecImpl` (`com.nexustools.genericui.desktop.image`)

3. **[`linuxMain` (`linuxCommon`, `linuxCommon.md`)](linuxCommon.md)** — `linuxX64`, `linuxArm64`, shared by **`Gtk`** & **`Qt`**:
   - `LinuxResourceResolverImpl` (ELF `.rodata` `genericui_res_lookup_*` / `genericui_res_lookup_app` symbols via `dlsym`)
   - `LinuxXdgConfigDirectoryImpl`, `LinuxXdgCacheDirectoryImpl`, `LinuxXdgPreferenceStoreImpl`
   - `LinuxPosixFileSystemNativeImpl`, `LinuxSqliteDatabaseNativeImpl`, `LinuxCurlHttpClientNativeImpl`, `LinuxPosixTcpSocketNativeImpl`, `LinuxPosixUdpSocketNativeImpl`, `LinuxRfc6455WebSocketNativeImpl`, `LinuxGioNetworkMonitorNativeImpl`, `LinuxDbusNotificationManagerNativeImpl`, `LinuxGdkPixbufImageCodecNativeImpl`, `LinuxX11WindowHintsBridge`
