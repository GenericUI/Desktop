# `:desktop:gtk` — Native Linux GTK4 Backend (`linuxX64`)

## Overview
`:desktop:gtk` is the native Linux GTK4 / Libadwaita backend for GenericUI (`linuxX64`).

### Key Highlights
- **Genuine GTK4 System Widgets (`BootstrapType.NONE` Default)**: When `getBootstrap().type == BootstrapType.NONE` (the default), widgets render as untinted native GTK4 / Adwaita controls with zero Bootstrap CSS styling.
- **Semantic `BootstrapType` Tinting**: Setting a semantic `BootstrapType` (`DANGER`, `PRIMARY`, `SUCCESS`, `WARNING`, `INFO`, `SECONDARY`, `LIGHT`, `DARK`, `LINK`) tints the native `GtkWidget` with the semantic color (`outline = true/false`). Unsupported CSS properties and transitions are silently ignored.
- **Strict User CSS Class Isolation**: `getClasses()`, `getClass()`, and `hasClass()` expose only user-added `customClasses`.
- **Custom Cursor Translation (`GdkCursor`)**: Converts any PNG, SVG, or `ImageBuffer` `CssCustomCursor` into a `GdkTexture` + `gdk_cursor_new_from_texture` with `(hotspotX, hotspotY)`.
- **Live System Theme Detection (`ThemeMode.AUTO`)**: Tracks `GtkSettings` (`gtk-application-prefer-dark-theme`) and the FreeDesktop settings portal.

---

## Bare-Metal Native Async I/O, `IFileSystem`, `Database`, & Networking

### 1. Async `Readable`, `Writable`, `ByteBuffer`, `IFileSystem`, & `FileSystemAvailability`
- **`FileSystemAvailability.Always`**: Both `FileSystem.isAvailable()` and `UserDirectory.isAvailable()` (`ConfigDirectory` / `CacheDirectory`) return `FileSystemAvailability.Always` on `:desktop:gtk` with `FilePermissionStatus.GRANTED`.
- **Non-Blocking GIO / POSIX File Streams (`FileSystem` & `Readable` / `Writable` / `ByteBuffer`)**:
  - `FileSystem` (`GtkFileSystemNativeImpl` / GIO `GFile`, `GFileInputStream`, `GFileOutputStream`, and POSIX async I/O) executes all file reads (`read`, `openReadable`), writes (`write`, `openWritable`), directory listings (`readdir`), `stat`, `mkdir`, `unlink`, `rmdir`, `rename`, and `copyFile` 100% asynchronously off the GTK main loop using `ByteBuffer`, `Readable`, and `Writable` streams.
  - Native file open/save dialogs (`GtkFileBrowserDialogNativeImpl` via `GtkFileDialog`) integrate directly with `FileSystem.openReadableFromFileDialog(...)`, `openReadablesFromFileDialog(...)`, and `openWritableFromFileDialog(...)`.
- **`UserDirectory` (`ConfigDirectory` & `CacheDirectory`)**:
  - `GtkConfigDirectoryImpl` resolves to `$XDG_CONFIG_HOME/<appName>[/<subdirectory>]` (defaulting to `~/.config/<appName>`) via `g_get_user_config_dir()`.
  - `GtkCacheDirectoryImpl` resolves to `$XDG_CACHE_HOME/<appName>[/<subdirectory>]` (defaulting to `~/.cache/<appName>`) via `g_get_user_cache_dir()`.
  - Both implement `IFileSystem` (`read`, `write`, `openReadable`, `openWritable`, `openDatabase`) with `usesFileSystemBasePath = true`, delegating async `Readable` / `Writable` / `ByteBuffer` streaming directly to `FileSystem`.

### 2. Native `Database` (`libsqlite3`)
- Backed directly by **`libsqlite3`** (`sqlite3_open_v2`, `sqlite3_prepare_v2`, `sqlite3_bind_*`, `sqlite3_step`, `sqlite3_finalize`, `sqlite3_close_v2`) in `GtkDatabaseNativeImpl`.
- Translates structured `DatabaseTableSchema`, `DatabaseRecord`, `DatabaseQuery`, and `DatabaseFilter` ASTs via `DatabaseQuerySqlTranslator` into parameterized SQLite statements with `?` positional bind values — requiring **zero string query parsing**.
- Defaults to storing the database file inside `ConfigDirectory` (`~/.config/<appName>/<databaseName>.db`), or at any custom path via `Database.setFilePath(path)` or `fileSystem.openDatabase(name, version)`.
- Supports async `defineTable`, `putRecord`, `getRecord`, `query`, `count`, `deleteWhere`, and binary/streamed `ByteBuffer`, `Readable`, and `Writable` values (`read`, `write`, `openReadable`, `openWritable`).

### 3. Async `Network` (`TcpClient`, `UdpClient`), `HttpClient`, `WebSocket` / `SocketIoClient`, & `IApplication.isOffline`
- **Raw TCP & UDP Sockets (`Network.supportsTcp() == true`, `Network.supportsUdp() == true`)**:
  - **`TcpClient`** (`GtkTcpClientNativeImpl`): Backed by GIO `GSocketClient` / `GSocketConnection` (with `GTlsClientConnection` when `tls = true`) and non-blocking POSIX sockets, exposing async `Readable` and `Writable` streams.
  - **`UdpClient`** (`GtkUdpSocketNativeImpl`): Backed by GIO `GSocket` (`G_SOCKET_TYPE_DATAGRAM`) / non-blocking POSIX `SOCK_DGRAM` sockets with datagram `bind`, `send`, `receive`, broadcast, and multicast group join/leave (`joinMulticastGroup` / `leaveMulticastGroup`).
- **Async `HttpClient` (`GtkHttpClientNativeImpl`)**:
  - Backed by `libsoup-3.0` (`SoupSession` / `SoupMessage`) and `libcurl`, supporting async `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, custom headers, `ByteBuffer` payloads, and streaming `Readable` response bodies (`openStream`).
- **Async `WebSocket` & `SocketIoClient` (`GtkWebSocketNativeImpl`)**:
  - Backed by `libsoup-3.0` (`SoupWebsocketConnection`), powering both raw `WebSocket` connections (text & binary frames) and the Engine.IO v4 / Socket.IO v4 `SocketIoClient` (`connect`, `emit`, `emitWithAck`, `on`, `once`, custom namespaces).
- **`IApplication.isOffline` Connectivity Monitoring (`GtkNetworkMonitorNativeImpl`)**:
  - Uses GIO `GNetworkMonitor` (`g_network_monitor_get_default()` / `g_network_monitor_get_network_available()`) and the `"network-changed"` signal to update `app.isOffline` / `app.isOnline` in real time and notify listeners registered via `app.addOfflineListener(...)` and `app.setOnOfflineChanged(...)`.

See [`../docs/gtk.md`](../docs/gtk.md) for full documentation.
