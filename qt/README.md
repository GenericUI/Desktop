# `:desktop:qt` — Native Linux Qt6 Backend (`linuxX64`)

## Overview
`:desktop:qt` is the native Linux Qt6 backend for GenericUI (`linuxX64`).

### Key Highlights
- **Genuine Qt6 System Widgets (`BootstrapType.NONE` Default)**: When `getBootstrap().type == BootstrapType.NONE` (the default), widgets render as untinted native Qt6 `QStyle` / `QPalette` controls with zero Bootstrap CSS styling.
- **Semantic `BootstrapType` Tinting**: Setting a semantic `BootstrapType` (`DANGER`, `PRIMARY`, `SUCCESS`, `WARNING`, `INFO`, `SECONDARY`, `LIGHT`, `DARK`, `LINK`) tints the native `QWidget` palette with the semantic color (`outline = true/false`). Unsupported CSS properties and transitions are silently ignored.
- **Strict User CSS Class Isolation**: `getClasses()`, `getClass()`, and `hasClass()` expose only user-added `customClasses`.
- **Custom Cursor Translation (`QCursor`)**: Converts any PNG, SVG, or `ImageBuffer` `CssCustomCursor` into a `QPixmap` + `QCursor(pixmap, hotspotX, hotspotY)`.
- **Live System Theme Detection (`ThemeMode.AUTO`)**: Queries `QGuiApplication::styleHints()->colorScheme()` and listens for `colorSchemeChanged`.

---

## Bare-Metal Native Async I/O, `IFileSystem`, `Database`, & Networking

### 1. Async `Readable`, `Writable`, `ByteBuffer`, `IFileSystem`, & `FileSystemAvailability`
- **`FileSystemAvailability.Always`**: Both `FileSystem.isAvailable()` and `UserDirectory.isAvailable()` (`ConfigDirectory` / `CacheDirectory`) return `FileSystemAvailability.Always` on `:desktop:qt` with `FilePermissionStatus.GRANTED`.
- **Non-Blocking Qt6 / POSIX File Streams (`FileSystem` & `Readable` / `Writable` / `ByteBuffer`)**:
  - `FileSystem` (`QtFileSystemNativeImpl` / POSIX & `QFile` / `QDir` / `QFileInfo`) executes all file reads (`read`, `openReadable`), writes (`write`, `openWritable`), directory listings (`readdir`), `stat`, `mkdir`, `unlink`, `rmdir`, `rename`, and `copyFile` 100% asynchronously off the Qt GUI thread using `ByteBuffer`, `Readable`, and `Writable` streams.
  - Native file open/save dialogs (`QtFileBrowserDialogNativeImpl` via `QFileDialog`) integrate directly with `FileSystem.openReadableFromFileDialog(...)`, `openReadablesFromFileDialog(...)`, and `openWritableFromFileDialog(...)`.
- **`UserDirectory` (`ConfigDirectory` & `CacheDirectory`)**:
  - `QtConfigDirectoryImpl` resolves to `$XDG_CONFIG_HOME/<appName>[/<subdirectory>]` (defaulting to `~/.config/<appName>`) via `QStandardPaths::AppConfigLocation`.
  - `QtCacheDirectoryImpl` resolves to `$XDG_CACHE_HOME/<appName>[/<subdirectory>]` (defaulting to `~/.cache/<appName>`) via `QStandardPaths::CacheLocation`.
  - Both implement `IFileSystem` (`read`, `write`, `openReadable`, `openWritable`, `openDatabase`) with `usesFileSystemBasePath = true`, delegating async `Readable` / `Writable` / `ByteBuffer` streaming directly to `FileSystem`.

### 2. Native `Database` (`QtSql` `QSqlDatabase` `"QSQLITE"`)
- Backed by **`QtSql`** (`QSqlDatabase::addDatabase("QSQLITE")`, `QSqlQuery`, and `QVariant` parameter binding) in `QtDatabaseNativeImpl`.
- Translates structured `DatabaseTableSchema`, `DatabaseRecord`, `DatabaseQuery`, and `DatabaseFilter` ASTs via `DatabaseQuerySqlTranslator` into parameterized SQLite statements with `?` positional bind values — requiring **zero string query parsing**.
- Defaults to storing the database file inside `ConfigDirectory` (`~/.config/<appName>/<databaseName>.db`), or at any custom path via `Database.setFilePath(path)` or `fileSystem.openDatabase(name, version)`.
- Supports async `defineTable`, `putRecord`, `getRecord`, `query`, `count`, `deleteWhere`, and binary/streamed `ByteBuffer`, `Readable`, and `Writable` values (`read`, `write`, `openReadable`, `openWritable`).

### 3. Async `Network` (`TcpClient`, `UdpClient`), `HttpClient`, `WebSocket` / `SocketIoClient`, & `IApplication.isOffline`
- **Raw TCP & UDP Sockets (`Network.supportsTcp() == true`, `Network.supportsUdp() == true`)**:
  - **`TcpClient`** (`QtTcpClientNativeImpl`): Backed by non-blocking `QTcpSocket` and `QSslSocket` (when `tls = true`), exposing async `Readable` and `Writable` streams.
  - **`UdpClient`** (`QtUdpSocketNativeImpl`): Backed by non-blocking `QUdpSocket` with datagram `bind`, `send`, `receive`, broadcast, and multicast group join/leave (`joinMulticastGroup` / `leaveMulticastGroup`).
- **Async `HttpClient` (`QtHttpClientNativeImpl`)**:
  - Backed by `QNetworkAccessManager`, `QNetworkRequest`, and `QNetworkReply`, supporting async `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, custom headers, `ByteBuffer` payloads, and streaming `Readable` response bodies (`openStream`).
- **Async `WebSocket` & `SocketIoClient` (`QtWebSocketNativeImpl`)**:
  - Backed by `QtWebSockets` (`QWebSocket`), powering both raw `WebSocket` connections (text & binary frames) and the Engine.IO v4 / Socket.IO v4 `SocketIoClient` (`connect`, `emit`, `emitWithAck`, `on`, `once`, custom namespaces).
- **`IApplication.isOffline` Connectivity Monitoring (`QtNetworkMonitorNativeImpl`)**:
  - Uses `QNetworkInformation::instance()->reachability()` and the `reachabilityChanged` signal to update `app.isOffline` / `app.isOnline` in real time and notify listeners registered via `app.addOfflineListener(...)` and `app.setOnOfflineChanged(...)`.

See [`../docs/qt.md`](../docs/qt.md) for full documentation.
