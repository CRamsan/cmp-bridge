# cmp-bridge

Drive a running [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/)
app for UI automation and end-to-end testing: read its live semantics tree, click,
type, scroll, and capture screenshots — through the app's *real* input pipeline, not a
simulated one. Think Espresso, XCUITest, or Playwright, but for Compose Multiplatform,
currently across desktop (JVM) and web (wasmJs).

It ships as a small library you embed in your app plus a driver and two standalone
servers (REST and MCP) for driving that app from outside — from a JVM test, a
non-JVM test runner, or an LLM agent.

For how the pieces fit together, see [ARCHITECTURE.md](ARCHITECTURE.md). To contribute,
see [CONTRIBUTING.md](CONTRIBUTING.md).

## How it works, briefly

- **Desktop**: your app opts in to an in-process debug socket server
  (`DesktopBridgeServer`) that reads Compose's real `semanticsOwners` tree and drives
  the app with real AWT input events posted onto its own event queue — never
  `java.awt.Robot`. Off unless explicitly armed.
- **Web**: no code needed in your app at all. Compose Multiplatform's web target
  already renders a hidden accessibility DOM for screen readers, and cmp-bridge drives
  that directly through a real headless browser (Playwright).

Both platforms are exposed through the same interface, `BridgeDriver` — five
operations (`getHierarchy`, `click`, `setText`, `scroll`, `screenshot`) and one shared
tree shape, `HierarchyNode`. See [ARCHITECTURE.md](ARCHITECTURE.md) for the full
breakdown, including where the two platforms' capabilities differ.

## Modules

| Module | What it's for |
|---|---|
| `cmp-bridge` | Add to your app. Defines the wire protocol and runs the in-process bridge server on desktop. |
| `cmp-bridge-driver` | Add to your test source set. `BridgeDriver` plus its desktop/web implementations and helpers to launch a disposable app/dev-server instance. |
| `cmp-bridge-http-server` | Standalone process. Exposes a running app's bridge over a local REST API. |
| `cmp-bridge-mcp-server` | Standalone process. Exposes a running app's bridge over MCP (stdio), for LLM agents. |
| `cmp-bridge-sample` | A minimal demo app plus an end-to-end test (`DemoScenarioTest`) driving it on both platforms — the best reference for wiring the bridge into your own app. |

## Trying it out with the sample app

The fastest way to see the bridge working is `cmp-bridge-sample`, without writing any
code:

**Desktop**

```bash
./gradlew :cmp-bridge-sample:run -DcmpBridge.enabled=true
```

This opens the sample app with the bridge listening on `127.0.0.1:8901`. In another
terminal, point either standalone server at it:

```bash
./gradlew :cmp-bridge-http-server:run --args="--platform desktop"
curl http://127.0.0.1:8090/hierarchy

# or, for an MCP client:
./gradlew :cmp-bridge-mcp-server:run --args="--platform desktop"
```

**Web**

```bash
./gradlew :cmp-bridge-sample:wasmJsBrowserDevelopmentRun
```

Then, once the dev server is up:

```bash
./gradlew :cmp-bridge-http-server:run --args="--platform web --url http://127.0.0.1:8080/"
```

Both standalone servers assume the app (or dev server) is already running — neither one
launches it. Run either with `--help` for the full option list.

## Driving an app over HTTP or MCP

Both standalone servers wrap the exact same five `BridgeDriver` operations — pick
whichever transport fits your tooling.

**HTTP (`cmp-bridge-http-server`)** exposes them all behind a single endpoint,
`POST /bridge` (on `--server-port`, default `8090`). The request body is an envelope —
`{"operation": "...", "payload": {...}}` — where `operation` picks the driver call and
`payload` is that operation's own arguments (omitted for the two that take none):

| `operation` | `payload` | Description |
|---|---|---|
| `getHierarchy` | — | Returns the app's current `HierarchyNode` tree as JSON. |
| `click` | `{"tag": "..."}` | Real synthetic click on the element with that test tag. |
| `setText` | `{"tag": "...", "text": "..."}` | Clicks the element, then types `text` into it. |
| `scroll` | `{"anchorTag": "...", "deltaY": N}` | Scroll gesture centered on `anchorTag`'s bounds. |
| `screenshot` | — | The app's current frame as a PNG (binary response). |

```bash
curl -X POST http://127.0.0.1:8090/bridge -H 'Content-Type: application/json' -d '{"operation":"getHierarchy"}'
curl -X POST http://127.0.0.1:8090/bridge -H 'Content-Type: application/json' \
  -d '{"operation":"click","payload":{"tag":"increment_button"}}'
curl -X POST http://127.0.0.1:8090/bridge -H 'Content-Type: application/json' \
  -d '{"operation":"setText","payload":{"tag":"name_field","text":"Ada"}}'
curl -X POST http://127.0.0.1:8090/bridge -H 'Content-Type: application/json' \
  -d '{"operation":"scroll","payload":{"anchorTag":"item_list","deltaY":5}}'
curl -X POST http://127.0.0.1:8090/bridge -H 'Content-Type: application/json' \
  -d '{"operation":"screenshot"}' -o screenshot.png
```

A failed operation (unknown tag, timeout, an unrecognized `operation`, ...) comes back
as `400` with `{"error": "..."}` rather than a stack trace.

**MCP (`cmp-bridge-mcp-server`)** exposes the same operations as MCP tools over stdio,
for pointing an LLM agent (Claude, or any other MCP client) at a running app:

| Tool | Arguments |
|---|---|
| `get_hierarchy` | — |
| `click` | `tag` |
| `set_text` | `tag`, `text` |
| `scroll` | `anchorTag`, `deltaY` |
| `screenshot` | — (returns an image, not text) |

Point an MCP client at it with a config like:

```json
{
  "mcpServers": {
    "cmp-bridge": {
      "command": "/path/to/cmp-bridge/gradlew",
      "args": ["-q", "--project-dir", "/path/to/cmp-bridge", ":cmp-bridge-mcp-server:run",
               "--args=--platform desktop"]
    }
  }
}
```

or run the assembled application/fat jar directly once built, passing the same
`--platform`/`--host`/`--port`/`--url` flags shown above.

## Using it in your own app

**1. Embed the bridge (desktop only — web needs nothing).**

```kotlin
// desktop entry point
fun main() = application {
    Window(onCloseRequest = ::exitApplication) {
        val scope = rememberCoroutineScope()
        DesktopBridgeServer.startIfEnabled(window, scope)
        App()
    }
}
```

`startIfEnabled` is a no-op unless the process is launched with
`-DcmpBridge.enabled=true`, so this is safe to leave in a normal build.

**2. Tag the elements you want to drive or read**, the same way you would for any
accessibility-based test tool:

```kotlin
Button(onClick = { ... }, modifier = Modifier.testTag("submit_button")) { ... }
```

**3. Drive it from a test**, via `cmp-bridge-driver`:

```kotlin
val process = DesktopAppProcess.launch("com.example.myapp.desktop.MainKt")
val driver = DesktopBridgeDriver.connect(process.host, process.port)
ManagedBridgeDriver(process, driver).use { d ->
    d.click("submit_button")
    assertEquals("Done", d.waitForTag("status_text").text)
}
```

`WasmDevServerProcess` + `WebBridgeDriver.connect(url)` is the equivalent pair for a
wasmJs app. `cmp-bridge-sample`'s `DemoScenarioTest` is a complete, working example of
both.

This repo doesn't currently publish artifacts to a package registry; consume it as a
Gradle composite build (`includeBuild("path/to/cmp-bridge")` in `settings.gradle.kts`)
or as a git submodule until it does.

## License

Apache License, Version 2.0 — see [LICENSE](LICENSE).
