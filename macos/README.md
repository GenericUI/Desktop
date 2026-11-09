# `:desktop:macos` — Native macOS AppKit Backend (`macosX64`, `macosArm64`)

## Overview
`:desktop:macos` is the native macOS AppKit / Cocoa backend for GenericUI (`macosX64` and `macosArm64`).

### Key Highlights
- **Genuine AppKit System Controls (`BootstrapType.NONE` Default)**: When `getBootstrap().type == BootstrapType.NONE` (the default), controls render as 100% unmodified native macOS Aqua / Dark Aqua `NSControl` widgets with zero Bootstrap CSS styling.
- **Empty-Text Offscreen Sprite + `ThemeConfig` Tint + Text Overlay (`BootstrapType != NONE` via `MacosControlRenderUtil`)**: When `BootstrapType != BootstrapType.NONE`, `MacosControlRenderUtil` renders the native AppKit `NSButtonCell` / `NSControl` with **no text** into an offscreen `CGBitmapContext` ARGB buffer, color-tints the native control pixels via `DesktopControlTintRenderUtil` and `ThemeConfig` while preserving native 3D bevels, borders, and hover/pressed/disabled states, blits the tinted control, and overlays contrasting text and icons on top.
- **System Backend Typography vs. Aesthetic Style Restraints**: Typography styles (`fontFamily`, `fontSize`, `fontWeight`, `fontStyle`, `color`, `lineHeight`, `letterSpacing`, `textAlign`, etc.) are honored on text elements (`Label`, `Text`, `Heading`, `Badge`, `Blockquote`, `CodeBlock`, `MarkdownView`). Aesthetic skinning and font overrides on `ComponentStyle` are **silently ignored** on standard controls/containers (which can only be tinted via `BootstrapType`), while `StyledContainer` and `StyledButton` honor full `ComponentStyle` aesthetics.
- **Strict User CSS Class Isolation**: `getClasses()`, `getClass()`, and `hasClass()` return only user-added `customClasses`.
- **Retina `NSCursor` Synthesis (`MacosCursorTranslator`)**: Translates any PNG `ResourceID`, SVG `ResourceID`/markup, or `ImageBuffer` `CssCustomCursor` into an `NSImage` + `NSCursor(image: nsImage, hotSpot: NSMakePoint(hotspotX, hotspotY))`.
- **Live System Theme Detection (`ThemeMode.AUTO`)**: Tracks `NSApp.effectiveAppearance` (`NSAppearanceNameDarkAqua` vs. `NSAppearanceNameAqua`).

---

## Bare-Metal Native Async I/O, `IFileSystem`, `Database`, & Networking

### 1. Async `Readable`, `Writable`, `ByteBuffer`, `IFileSystem`, & `FileSystemAvailability`
- **`FileSystemAvailability.Always`**: Both `FileSystem.isAvailable()` and `UserDirectory.isAvailable()` (`ConfigDirectory` / `CacheDirectory`) return `FileSystemAvailability.Always` on `:desktop:macos` with `FilePermissionStatus.GRANTED`.
- **Non-Blocking Foundation / POSIX File Streams (`FileSystem` & `Readable` / `Writable` / `ByteBuffer`)**:
  - `FileSystem` (`MacosFileSystemNativeImpl` / Foundation `NSFileManager`, `NSFileHandle`, Grand Central Dispatch `dispatch_io_t`, and POSIX async I/O) executes all file reads (`read`, `openReadable`), writes (`write`, `openWritable`), directory listings (`readdir`), `stat`, `mkdir`, `unlink`, `rmdir`, `rename`, and `copyFile` 100% asynchronously off the AppKit main thread using `ByteBuffer`, `Readable`, and `Writable` streams.
  - Native file open/save dialogs (`MacosFileBrowserDialogNativeImpl` via AppKit `NSOpenPanel` / `NSSavePanel`) integrate directly with `FileSystem.openReadableFromFileDialog(...)`, `openReadablesFromFileDialog(...)`, and `openWritableFromFileDialog(...)`.
- **`UserDirectory` (`ConfigDirectory` & `CacheDirectory`)**:
  - `MacosConfigDirectoryImpl` resolves to `~/Library/Application Support/<appName>[/<subdirectory>]` (`NSApplicationSupportDirectory`).
  - `MacosCacheDirectoryImpl` resolves to `~/Library/Caches/<appName>[/<subdirectory>]` (`NSCachesDirectory`).
  - Both implement `IFileSystem` (`read`, `write`, `openReadable`, `openWritable`, `openDatabase`) with `usesFileSystemBasePath = true`, delegating async `Readable` / `Writable` / `ByteBuffer` streaming directly to `FileSystem`.

### 2. Native `Database` (`libsqlite3`)
- Backed directly by macOS system **`libsqlite3`** (`/usr/lib/libsqlite3.dylib` — `sqlite3_open_v2`, `sqlite3_prepare_v2`, `sqlite3_bind_*`, `sqlite3_step`, `sqlite3_finalize`, `sqlite3_close_v2`) in `MacosDatabaseNativeImpl`.
- Translates structured `DatabaseTableSchema`, `DatabaseRecord`, `DatabaseQuery`, and `DatabaseFilter` ASTs via `DatabaseQuerySqlTranslator` into parameterized SQLite statements with `?` positional bind values — requiring **zero string query parsing**.
- Defaults to storing the database file inside `ConfigDirectory` (`~/Library/Application Support/<appName>/<databaseName>.db`), or at any custom path via `Database.setFilePath(path)` or `fileSystem.openDatabase(name, version)`.
- Supports async `defineTable`, `putRecord`, `getRecord`, `query`, `count`, `deleteWhere`, and binary/streamed `ByteBuffer`, `Readable`, and `Writable` values (`read`, `write`, `openReadable`, `openWritable`).

### 3. Async `Network` (`TcpClient`, `UdpClient`), `HttpClient`, `WebSocket` / `SocketIoClient`, & `IApplication.isOffline`
- **Raw TCP & UDP Sockets (`Network.supportsTcp() == true`, `Network.supportsUdp() == true`)**:
  - **`TcpClient`** (`MacosTcpClientNativeImpl`): Backed by Apple `Network.framework` (`nw_connection_t` / `NWConnection` with TLS parameters when `tls = true`) and non-blocking BSD sockets, exposing async `Readable` and `Writable` streams.
  - **`UdpClient`** (`MacosUdpSocketNativeImpl`): Backed by `Network.framework` / non-blocking Darwin BSD `SOCK_DGRAM` sockets with datagram `bind`, `sendto`, `recvfrom`, broadcast (`SO_BROADCAST`), and multicast group join/leave (`IP_ADD_MEMBERSHIP` / `IP_DROP_MEMBERSHIP`).
- **Async `HttpClient` (`MacosHttpClientNativeImpl`)**:
  - Backed by Foundation `NSURLSession`, `NSMutableURLRequest`, and `NSURLSessionDataTask`, supporting async `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, custom headers, `ByteBuffer` payloads, and streaming `Readable` response bodies (`openStream`).
- **Async `WebSocket` & `SocketIoClient` (`MacosWebSocketNativeImpl`)**:
  - Backed by `NSURLSessionWebSocketTask` (`NSURLSessionWebSocketMessage`), powering both raw `WebSocket` connections (text & binary frames) and the Engine.IO v4 / Socket.IO v4 `SocketIoClient` (`connect`, `emit`, `emitWithAck`, `on`, `once`, custom namespaces).
- **`IApplication.isOffline` Connectivity Monitoring (`MacosNetworkMonitorNativeImpl`)**:
  - Uses Apple `Network.framework` `nw_path_monitor_t` (`NWPathMonitor` / `nw_path_get_status(path) == nw_path_status_satisfied`) to update `app.isOffline` / `app.isOnline` in real time and notify listeners registered via `app.addOfflineListener(...)` and `app.setOnOfflineChanged(...)`.

See [`../docs/macos.md`](../docs/macos.md) for full documentation.
