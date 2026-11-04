# `:desktop:swing` — JVM Desktop Swing / AWT Backend (`jvmMain`)

## Overview
`:desktop:swing` is the cross-platform JVM Swing / AWT backend for GenericUI (`jvmMain`).

### Key Highlights
- **Genuine Swing Look-and-Feel Controls (`BootstrapType.NONE` Default)**: When `getBootstrap().type == BootstrapType.NONE` (the default), components render as untinted Look-and-Feel `JComponent` peers with zero Bootstrap CSS styling.
- **Semantic `BootstrapType` Tinting**: Setting `DANGER`, `PRIMARY`, `SUCCESS`, `WARNING`, `INFO`, `SECONDARY`, `LIGHT`, `DARK`, or `LINK` tints the `JComponent` with the semantic color (`outline = true/false`). Unsupported CSS properties and transitions are silently ignored.
- **Strict User CSS Class Isolation**: `getClasses()`, `getClass()`, and `hasClass()` return only user-added `customClasses`.
- **Custom Cursor Translation (`Toolkit.createCustomCursor`)**: Converts any PNG, SVG, or `ImageBuffer` `CssCustomCursor` into a `BufferedImage` and `java.awt.Cursor` with `(hotspotX, hotspotY)`.
- **Live System Theme Detection (`ThemeMode.AUTO`)**: Detects host OS dark/light theme across Windows, macOS, and Linux.

---

## Bare-Metal Native Async I/O, `IFileSystem`, `Database`, & Networking

### 1. Async `Readable`, `Writable`, `ByteBuffer`, `IFileSystem`, & `FileSystemAvailability`
- **`FileSystemAvailability.Always`**: Both `FileSystem.isAvailable()` and `UserDirectory.isAvailable()` (`ConfigDirectory` / `CacheDirectory`) return `FileSystemAvailability.Always` on `:desktop:swing` with `FilePermissionStatus.GRANTED`.
- **Non-Blocking JVM NIO.2 File Streams (`FileSystem` & `Readable` / `Writable` / `ByteBuffer`)**:
  - `FileSystem` (`SwingFileSystemNativeImpl` / `java.nio.channels.AsynchronousFileChannel`, `java.nio.file.Files`, and `java.nio.file.Path` on `Dispatchers.IO`) executes all file reads (`read`, `openReadable`), writes (`write`, `openWritable`), directory listings (`readdir`), `stat`, `mkdir`, `unlink`, `rmdir`, `rename`, and `copyFile` 100% asynchronously off the AWT Event Dispatch Thread (EDT) using `ByteBuffer`, `Readable`, and `Writable` streams.
  - Native file open/save dialogs (`SwingFileBrowserDialogImpl` via `JFileChooser`) integrate directly with `FileSystem.openReadableFromFileDialog(...)`, `openReadablesFromFileDialog(...)`, and `openWritableFromFileDialog(...)`.
- **`UserDirectory` (`ConfigDirectory` & `CacheDirectory`)**:
  - `SwingUserDirectoryNativeImpl` (`isCache = false` for `ConfigDirectory`, `isCache = true` for `CacheDirectory`) resolves to the host OS application config/cache directory (`%APPDATA%\<appName>` / `%LOCALAPPDATA%\<appName>\Cache` on Windows, `~/Library/Caches/<appName>` on macOS, `$XDG_CONFIG_HOME/<appName>` / `$XDG_CACHE_HOME/<appName>` on Linux).
  - Implements `IFileSystem` (`read`, `write`, `openReadable`, `openWritable`, `openDatabase`) with `usesFileSystemBasePath = true`, delegating async `Readable` / `Writable` / `ByteBuffer` streaming directly to `FileSystem`.

### 2. Native `Database` (`org.xerial:sqlite-jdbc`)
- Backed by **`org.xerial:sqlite-jdbc`** (`org.sqlite.JDBC` / `java.sql.DriverManager.getConnection("jdbc:sqlite:...")` and `PreparedStatement` on `Dispatchers.IO`) in `SwingDatabaseNativeImpl`.
- Translates structured `DatabaseTableSchema`, `DatabaseRecord`, `DatabaseQuery`, and `DatabaseFilter` ASTs via `DatabaseQuerySqlTranslator` into parameterized SQLite statements with `?` positional bind values — requiring **zero string query parsing**.
- Defaults to storing the database file inside `ConfigDirectory` (`<ConfigDirectory>/<databaseName>.db`), or at any custom path via `Database.setFilePath(path)` or `fileSystem.openDatabase(name, version)`.
- Supports async `defineTable`, `putRecord`, `getRecord`, `query`, `count`, `deleteWhere`, and binary/streamed `ByteBuffer`, `Readable`, and `Writable` values (`read`, `write`, `openReadable`, `openWritable`).

### 3. Async `Network` (`TcpClient`, `UdpClient`), `HttpClient`, `WebSocket` / `SocketIoClient`, & `IApplication.isOffline`
- **Raw TCP & UDP Sockets (`Network.supportsTcp() == true`, `Network.supportsUdp() == true`)**:
  - **`TcpClient`** (`SwingTcpClientNativeImpl`): Backed by non-blocking JVM NIO `AsynchronousSocketChannel` / `SocketChannel` (and `javax.net.ssl.SSLSocket` / `SSLEngine` when `tls = true`), exposing async `Readable` and `Writable` streams.
  - **`UdpClient`** (`SwingUdpSocketNativeImpl`): Backed by JVM NIO `DatagramChannel` (`StandardProtocolFamily.INET`) with datagram `bind`, `send`, `receive`, `SO_BROADCAST`, and `join` / `drop` multicast group membership.
- **Async `HttpClient` (`SwingHttpClientNativeImpl`)**:
  - Backed by `java.net.http.HttpClient` (`sendAsync`), supporting async `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, custom headers, `ByteBuffer` payloads, and streaming `Readable` response bodies (`openStream`).
- **Async `WebSocket` & `SocketIoClient` (`SwingWebSocketNativeImpl`)**:
  - Backed by `java.net.http.WebSocket` (`HttpClient.newWebSocketBuilder()`), powering both raw `WebSocket` connections (text & binary frames) and the Engine.IO v4 / Socket.IO v4 `SocketIoClient` (`connect`, `emit`, `emitWithAck`, `on`, `once`, custom namespaces).
- **`IApplication.isOffline` Connectivity Monitoring (`SwingNetworkMonitorImpl`)**:
  - Monitors active non-loopback `java.net.NetworkInterface` instances (`isUp && !isLoopback`) to update `app.isOffline` / `app.isOnline` and notify listeners registered via `app.addOfflineListener(...)` and `app.setOnOfflineChanged(...)`.

See [`../docs/swing.md`](../docs/swing.md) for full documentation.
