# `:desktop:windows` — Native Windows Backend (`mingwX64`)

## Overview
`:desktop:windows` is the native Windows (`mingwX64`) backend for GenericUI, built on Win32 (`HWND`), **`UxTheme`** (`uxtheme.dll`), Desktop Window Manager (`dwmapi.dll`), and Direct2D.

### Key Highlights
- **Genuine Win32 `UxTheme` Controls (`BootstrapType.NONE` Default)**: When `getBootstrap().type == BootstrapType.NONE` (the default) and no inline `ComponentStyle` background/border is set, controls render via `Win32DwmUxThemeBridge` using `SetWindowTheme` (`"Explorer"` in `LIGHT` mode, `"DarkMode_Explorer"` in `DARK` mode), `OpenThemeData(hwnd, "Explorer::BUTTON" | "DarkMode_Explorer::BUTTON")`, and `DrawThemeBackground`.
- **Semantic `BootstrapType` Tinting**: Setting `DANGER`, `PRIMARY`, `SUCCESS`, `WARNING`, `INFO`, `SECONDARY`, `LIGHT`, `DARK`, or `LINK` emulates `BootstrapType` by tinting the native Win32 control with the semantic color (`outline = true/false`). Unsupported CSS properties and transitions are silently ignored.
- **Strict User CSS Class Isolation**: `getClasses()`, `getClass()`, `hasClass()`, `addClass()`, and `removeClass()` expose only user-added custom classes (`customClasses`).
- **Windows `.cur` Custom Cursor Synthesis (`WindowsCurCodec`)**: Converts any PNG `ResourceID`, SVG `ResourceID`/markup, or `ImageBuffer` `CssCustomCursor` into a valid Windows `.cur` binary (`ICONDIR` `idType = 2`, `ICONDIRENTRY` `wXHotSpot`/`wYHotSpot`, 32-bit BGRA DIB + 1-bpp AND mask) and Win32 `HCURSOR` for `WM_SETCURSOR`.
- **Live System Theme Detection (`ThemeMode.AUTO`)**: Reads `AppsUseLightTheme` from the Windows Registry, handles `WM_SETTINGCHANGE`, and updates `DWMWA_USE_IMMERSIVE_DARK_MODE`.

---

## Bare-Metal Native Async I/O, `IFileSystem`, `Database`, & Networking

### 1. Async `Readable`, `Writable`, `ByteBuffer`, `IFileSystem`, & `FileSystemAvailability`
- **`FileSystemAvailability.Always`**: Both `FileSystem.isAvailable()` and `UserDirectory.isAvailable()` (`ConfigDirectory` / `CacheDirectory`) return `FileSystemAvailability.Always` on `:desktop:windows` with `FilePermissionStatus.GRANTED`.
- **Non-Blocking Win32 File Streams (`FileSystem` & `Readable` / `Writable` / `ByteBuffer`)**:
  - `FileSystem` (`WindowsFileSystemNativeImpl` / Win32 `CreateFileW`, `ReadFile`, `WriteFile`, `FindFirstFileW` / `FindNextFileW`, `GetFileAttributesExW`, `CreateDirectoryW`, `DeleteFileW`, `RemoveDirectoryW`, `MoveFileExW`, `CopyFileW`) executes all file reads (`read`, `openReadable`), writes (`write`, `openWritable`), directory listings (`readdir`), `stat`, `mkdir`, `unlink`, `rmdir`, `rename`, and `copyFile` 100% asynchronously off the Win32 UI message thread using `ByteBuffer`, `Readable`, and `Writable` streams.
  - Native file open/save dialogs (`WindowsFileBrowserDialogNativeImpl` via COM `IFileOpenDialog` / `IFileSaveDialog`) integrate directly with `FileSystem.openReadableFromFileDialog(...)`, `openReadablesFromFileDialog(...)`, and `openWritableFromFileDialog(...)`.
- **`UserDirectory` (`ConfigDirectory` & `CacheDirectory`)**:
  - `WindowsConfigDirectoryImpl` resolves to `%APPDATA%\<appName>[\<subdirectory>]` (`FOLDERID_RoamingAppData`).
  - `WindowsCacheDirectoryImpl` resolves to `%LOCALAPPDATA%\<appName>\Cache[\<subdirectory>]` (`FOLDERID_LocalAppData`).
  - Both implement `IFileSystem` (`read`, `write`, `openReadable`, `openWritable`, `openDatabase`) with `usesFileSystemBasePath = true` and `fileSeparator = '\\'`, delegating async `Readable` / `Writable` / `ByteBuffer` streaming directly to `FileSystem`.

### 2. Native `Database` (`libsqlite3`)
- Backed directly by **`libsqlite3`** (`sqlite3_open_v2`, `sqlite3_prepare_v2`, `sqlite3_bind_*`, `sqlite3_step`, `sqlite3_finalize`, `sqlite3_close_v2`) in `WindowsDatabaseNativeImpl`.
- Translates structured `DatabaseTableSchema`, `DatabaseRecord`, `DatabaseQuery`, and `DatabaseFilter` ASTs via `DatabaseQuerySqlTranslator` into parameterized SQLite statements with `?` positional bind values — requiring **zero string query parsing**.
- Defaults to storing the database file inside `ConfigDirectory` (`%APPDATA%\<appName>\<databaseName>.db`), or at any custom path via `Database.setFilePath(path)` or `fileSystem.openDatabase(name, version)`.
- Supports async `defineTable`, `putRecord`, `getRecord`, `query`, `count`, `deleteWhere`, and binary/streamed `ByteBuffer`, `Readable`, and `Writable` values (`read`, `write`, `openReadable`, `openWritable`).

### 3. Async `Network` (`TcpClient`, `UdpClient`), `HttpClient`, `WebSocket` / `SocketIoClient`, & `IApplication.isOffline`
- **Raw TCP & UDP Sockets (`Network.supportsTcp() == true`, `Network.supportsUdp() == true`)**:
  - **`TcpClient`** (`WindowsTcpClientNativeImpl`): Backed by non-blocking WinSock2 (`ws2_32.dll` `WSAStartup`, `socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)`, `connect`, `recv`, `send`, and Schannel / TLS when `tls = true`), exposing async `Readable` and `Writable` streams.
  - **`UdpClient`** (`WindowsUdpSocketNativeImpl`): Backed by non-blocking WinSock2 `SOCK_DGRAM` sockets with datagram `bind`, `sendto`, `recvfrom`, `SO_BROADCAST`, and `IP_ADD_MEMBERSHIP` / `IP_DROP_MEMBERSHIP` multicast support.
- **Async `HttpClient` (`WindowsHttpClientNativeImpl`)**:
  - Backed by Windows `WinHTTP` (`winhttp.dll` `WinHttpOpen`, `WinHttpConnect`, `WinHttpOpenRequest`, `WinHttpSendRequest`, `WinHttpReceiveResponse`, `WinHttpReadData`) / `WinINet`, supporting async `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, custom headers, `ByteBuffer` payloads, and streaming `Readable` response bodies (`openStream`).
- **Async `WebSocket` & `SocketIoClient` (`WindowsWebSocketNativeImpl`)**:
  - Backed by `WinHTTP` WebSocket (`WinHttpWebSocketCompleteUpgrade`, `WinHttpWebSocketSend`, `WinHttpWebSocketReceive`, `WinHttpWebSocketClose`), powering both raw `WebSocket` connections (text & binary frames) and the Engine.IO v4 / Socket.IO v4 `SocketIoClient` (`connect`, `emit`, `emitWithAck`, `on`, `once`, custom namespaces).
- **`IApplication.isOffline` Connectivity Monitoring (`WindowsNetworkMonitorNativeImpl`)**:
  - Uses COM `INetworkListManager` (`NLM_CONNECTIVITY`) and `InternetGetConnectedState` to update `app.isOffline` / `app.isOnline` in real time and notify listeners registered via `app.addOfflineListener(...)` and `app.setOnOfflineChanged(...)`.

See [`../docs/windows.md`](../docs/windows.md) for full documentation.
