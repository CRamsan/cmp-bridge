# cmp-bridge

[![Build](https://github.com/CRamsan/cmp-bridge/actions/workflows/build.yml/badge.svg)](https://github.com/CRamsan/cmp-bridge/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

Drive and inspect a live [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) UI over a
typed wire protocol — for end-to-end tests and for AI clients that want to explore an app's real screen.

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for the module map, design rationale, and build system
notes. Contributing? Start with [`CONTRIBUTING.md`](CONTRIBUTING.md).

`cmp-bridge` doesn't simulate your UI. On desktop it reads Compose's real semantics tree
(`ComposeWindow.semanticsOwners`) and drives it with genuine AWT input events. On web it reads Compose
Multiplatform's own hidden accessibility DOM and drives it with a real headless browser (Playwright). Every
read reflects the actual, current state of a running app — there's no shadow model to fall out of sync.

## Modules

| Module | What it is |
| --- | --- |
| [`cmp-bridge`](cmp-bridge) | The in-app server. Add it to your app, arm it with one call, and it exposes the live semantics tree over a local socket. |
| [`cmp-bridge-driver`](cmp-bridge-driver) | `BridgeDriver` — the client. `DesktopBridgeDriver` and `WebBridgeDriver` connect to an already-running app; `DesktopAppProcess`/`WasmDevServerProcess` + `ManagedBridgeDriver` compose in "launch a disposable instance" for tests that want one. |
| [`cmp-bridge-http-server`](cmp-bridge-http-server) | A small Ktor REST API in front of `BridgeDriver`, for anything that speaks HTTP. |
| [`cmp-bridge-mcp-server`](cmp-bridge-mcp-server) | An [MCP](https://modelcontextprotocol.io/) server in front of `BridgeDriver`, for AI clients like Claude Code/Desktop. |

## The five operations

Every consumer — JUnit tests, curl, an MCP tool call — ends up calling the same five things on `BridgeDriver`:

- `getHierarchy()` — the live semantics tree: tags, roles, text, bounds, enabled state, available actions.
- `click(tag)`
- `setText(tag, text)`
- `scroll(anchorTag, deltaY)`
- `screenshot()` — PNG bytes of the current frame.

`getBounds(tag)`/`waitForTag(tag)` are default methods on `BridgeDriver`, built purely from `getHierarchy()`.

## Using it in your app

Add `cmp-bridge` to your desktop app and arm the bridge behind a flag you control — never on by default:

```kotlin
if (System.getProperty("cmpBridge.enabled") == "true") {
    DesktopBridgeServer.startIfEnabled(window, port = 8901)
}
```

Web apps need nothing extra — Compose Multiplatform's own accessibility DOM (enabled by default) is what
`WebBridgeDriver` reads; `cmp-bridge` doesn't need to run inside a wasmJs app at all.

## Using it from a test

```kotlin
val process = DesktopAppProcess.launch("com.example.MainKt")
val driver = ManagedBridgeDriver(process, DesktopBridgeDriver.connect(process.host, process.port))

driver.click("sign-in-button")
driver.setText("email-field", "user@example.com")
assertEquals("user@example.com", driver.getHierarchy().find("email-field")?.text)

driver.close() // closes the driver, then kills the launched process
```

## Using it from an AI client

Point either server at an app you already started yourself (`cmp-bridge` never launches anything):

```
./gradlew :cmp-bridge-http-server:run --args="--platform=desktop --port=8901 --server-port=8090"
```

```
curl localhost:8090/hierarchy
curl -X POST localhost:8090/click -d '{"tag":"sign-in-button"}'
```

Or register the MCP server (stdio transport) with an MCP-aware client:

```
./gradlew :cmp-bridge-mcp-server:installDist
./cmp-bridge-mcp-server/build/install/cmp-bridge-mcp-server/bin/cmp-bridge-mcp-server \
    --platform=desktop --port=8901
```

Both servers take the same connection flags: `--platform=desktop|web`, then either `--host`/`--port`
(desktop) or `--url` (web).

## Building

```
./gradlew build
```

Requires JDK 21 and an Android SDK on `local.properties`' `sdk.dir` (only `cmp-bridge` declares an Android
target — currently an empty placeholder reserved for future native Android support).

## Known gaps

- Web scroll (`page.mouse().wheel()`) is unverified against a real (non-fallback) browser build — implemented,
  but treat it as best-effort until confirmed.
- Compose Multiplatform's own web accessibility DOM doesn't respect `Modifier.semantics { password() }` —
  a password field's real text can still surface there regardless of what `cmp-bridge` does on its side.
- No Android driver yet — Android's real equivalent would be `AccessibilityNodeProvider`/UI Automator,
  matching the same "read the platform's real tree" approach used for desktop and web.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
