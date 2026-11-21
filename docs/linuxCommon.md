# `:desktop` Linux Shared Layer (`linuxMain` / `linuxCommon`)

> **Module Guide**: [**`:desktop` (`../README.md`)**](../README.md) | **Backends**: [`gtk.md`](gtk.md) · [`qt.md`](qt.md)

## Overview
The `linuxMain` source set of `:desktop` (`linuxCommon` variant scope for `linuxX64` and `linuxArm64`) hosts all shared Linux OS implementations reused identically by both **`Gtk`** (`GtkNativeFactory`) and **`Qt`** (`QtNativeFactory`).

---

## Shared Linux Subsystems (`com.nexustools.genericui.linux`)

| Domain | Classes | Implementation & Responsibility |
|-------|---------|--------------------------------|
| **Embedded ELF Resources** | `LinuxResourceResolverImpl` | Looks up prefix-free `ResourceID.nativeResourceKey` entries (`"gui/"` / `"gui/ext/<cat>/<ext>/"` / `"app/"` stripped) from ELF `.rodata` resource tables (`genericui_res_lookup_<group>` / `genericui_res_lookup_app`) via `dlsym` inside the running executable (`dlopen(null, RTLD_LAZY)`) or `lib<group>.so` (cached in `lookupCache`), falling back to `$exeDir` / XDG data roots and `Resources.activeTable`. |
| **XDG Config, Cache, & Preferences** | `LinuxXdgConfigDirectoryImpl`<br/>`LinuxXdgCacheDirectoryImpl`<br/>`LinuxXdgPreferenceStoreImpl` | Resolves `$XDG_CONFIG_HOME/<app>` (`~/.config`) and `$XDG_CACHE_HOME/<app>` (`~/.cache`), persisting key-value preferences via `SystemStorageKeyHelper`. |
| **Filesystem, SQLite, & Images** | `LinuxPosixFileSystemNativeImpl` (`Readable`/`Writable`)<br/>`LinuxSqliteDatabaseNativeImpl` (`DatabaseReadable`/`Writable`)<br/>`LinuxGdkPixbufImageCodecNativeImpl` | Asynchronous POSIX file I/O (`open`/`read`/`write`), `libsqlite3` structured AST query execution (`LinuxSqliteBindings`), and `gdk-pixbuf` decoding/encoding (`LinuxGdkPixbufBindings`). |
| **Networking, WebSockets, & Connectivity** | `LinuxCurlHttpClientNativeImpl` (`HttpConnection`/`Request`/`Response`)<br/>`LinuxPosixTcpSocketNativeImpl`<br/>`LinuxPosixUdpSocketNativeImpl`<br/>`LinuxRfc6455WebSocketNativeImpl`<br/>`LinuxGioNetworkMonitorNativeImpl` | `libcurl` HTTPS client (`LinuxCurlBindings`), POSIX TCP (`OpenSSL` TLS SNI via `LinuxOpenSslBindings`) and UDP sockets, RFC 6455 WebSocket (`Socket.IO` v4 capable), and GIO `GNetworkMonitor` offline tracking (`LinuxGlibGioBindings`). |
| **Notifications & X11 Window Hints** | `LinuxDbusNotificationManagerNativeImpl`<br/>`LinuxX11WindowHintsBridge` | FreeDesktop D-Bus notifications (`org.freedesktop.Notifications`) and shared X11 window hints alongside XDG autostart (`.desktop`) and `fcntl`/`flock` single-instance `ServiceWorker` process coordination. |

---

## SourceSet Dependency Chain
```text
:common
  └── :system
        └── :desktop (commonMain -> nativeDesktopMain -> linuxMain [linuxCommon -> Gtk + Qt])
```
