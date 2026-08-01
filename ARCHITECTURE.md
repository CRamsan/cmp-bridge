# Architecture

This document explains what `cmp-bridge` is built out of, why it's shaped the way it is, and the
values behind the design decisions.

## Core idea

`cmp-bridge` drives and inspects a **live** Compose Multiplatform UI over a typed wire protocol. It
does not simulate the UI and does not maintain a shadow model of it — every read comes from the
real, current state of a running app:

- **Desktop** reads `ComposeWindow.semanticsOwners` (the real semantics tree,
  `@OptIn(ExperimentalComposeUiApi::class)`) fresh on every request. No caching.
- **Web** reads Compose Multiplatform's own hidden accessibility DOM, which is enabled by default
  (`ComposeViewportConfiguration.isA11YEnabled = true`) — no custom JS export is needed on the app
  side.

This "always the real thing, never a model of it" rule is the one constraint every design decision
below traces back to. A shadow model can drift from the real UI; reading the platform's own
accessibility tree can't.

The other deliberate property: **JUnit E2E test suites and AI clients (via the HTTP/MCP servers)
share the exact same `BridgeDriver` API.** That symmetry isn't incidental — a test suite and an AI
agent exploring a running app are doing the same fundamental thing (read the tree, act on a tag,
read again), so they get the same five operations instead of two parallel APIs that could drift
apart.

## Module map

| Module | Role |
| --- | --- |
| [`cmp-bridge`](cmp-bridge) | In-app server (`DesktopBridgeServer`, `cmp-bridge/src/jvmMain/kotlin/com/cramsan/cmpbridge/`). Builds `HierarchyNode` trees from the real semantics tree and serves `BridgeCommand`/`BridgeResponse` (kotlinx-serialization sealed classes, `BridgeProtocol.kt`) over a plain socket. |
| [`cmp-bridge-driver`](cmp-bridge-driver) | Client side (`cmp-bridge-driver/src/main/kotlin/com/cramsan/cmpbridge/driver/`). `BridgeDriver` — the interface (`getHierarchy`/`click`/`setText`/`scroll`/`screenshot`, plus default `getBounds`/`waitForTag` built purely on `getHierarchy`). `DesktopBridgeDriver`/`WebBridgeDriver` are **connect-only**. `DesktopAppProcess`/`WasmDevServerProcess` each own *only* a launched subprocess. `ManagedBridgeDriver` is a generic `BridgeDriver by driver` wrapper pairing any `AutoCloseable` resource with any driver for single-call teardown. |
| [`cmp-bridge-http-server`](cmp-bridge-http-server) | Thin Ktor REST wrapper around `BridgeDriver` (`Routes.kt`: `GET /hierarchy`, `POST /click`, `POST /setText`, `POST /scroll`, `GET /screenshot`). Connect-only — never launches an app. |
| [`cmp-bridge-mcp-server`](cmp-bridge-mcp-server) | Same shape as the HTTP server, but as MCP tools (`Tools.kt`) over stdio (`io.modelcontextprotocol:kotlin-sdk`). |
| [`cmp-bridge-sample`](cmp-bridge-sample) | Standalone demo app (desktop + wasmJs) plus `DemoScenarioTest`, a JUnit E2E suite driving it through `BridgeDriver` on both platforms. |

## Design history

**The original design was a hand-rolled tag registry** — `TestBridgeRegistry` +
`Modifier.testTagBridge` + a custom `window.testBridge` JS export. It was fully replaced, not kept
alongside the newer model, once it became clear Compose Multiplatform already exposes a real
semantics tree on every platform — verified directly against decompiled Compose Multiplatform
1.10.3 jars, not just docs. Screens now just use plain `Modifier.testTag(tag)`; there's nothing
bridge-specific for an app to opt into beyond arming the server.

**`launch()`/`connect()` used to be one fork inside `DesktopBridgeDriver`/`WebBridgeDriver`** —
nullable process/logFile fields, a conditional `close()` depending on which factory built the
instance. That was deliberately split into today's shape (connect-only drivers +
`DesktopAppProcess`/`WasmDevServerProcess` + `ManagedBridgeDriver`) on the principle that a class
with an internal "how was I built" branch should be several single-purpose classes composed
together instead. That instinct applies to any future class here that starts growing a similar
mode-fork.

**Renamed from `ui-test-bridge*` to `cmp-bridge*`** once the library's scope grew past pure test
tooling — AI-client exploration via HTTP/MCP isn't a "test" activity, so a name that implied it was
had become misleading. "CMP" = Compose Multiplatform, matching JetBrains' own abbreviation and
naming the actual dependency this only works because of: CMP exposing a live semantics tree on both
desktop and web.

## Build system

No version catalog. Every dependency is a raw Maven coordinate string with a
[refreshVersions](https://github.com/Splitties/refreshVersions) `:_` placeholder (e.g.
`"org.jetbrains.kotlinx:kotlinx-coroutines-core:_"`), resolved against a
`version.<group>..<artifact>=<version>` entry in `versions.properties`. Two exceptions:

- **Plugins** take no version in the root `build.gradle.kts`'s `apply false` list (e.g.
  `id("dev.detekt") apply false`); submodules apply the same plugin with a bare `id("...")`,
  inheriting the resolved version via ordinary Gradle multi-project plugin inheritance.
  refreshVersions resolves the actual version via `plugin.<pluginId>=<version>` entries in
  `versions.properties` (Kotlin plugins ride its built-in `version.kotlin` shorthand instead of
  needing their own entry). Note this is a different mechanism from the `:_` placeholder used for
  library dependencies below — `id("...") version "_"` does **not** work in a `plugins {}` block
  here (Gradle tries to resolve a literal artifact version `"_"` and errors); omitting the version
  entirely is what makes refreshVersions' plugin-resolution hook apply.
- **Ktor libraries** take no version notation at all (no `:_`, nothing in `versions.properties`).
  The Ktor Gradle plugin auto-aligns every `io.ktor:*` artifact to its own configured version; a
  `:_` suffix here would be actively wrong.

Current pins: Kotlin 2.3.21 · Compose Multiplatform 1.10.3 · AGP 9.2.1 · ktor-plugin 3.5.0 ·
kotlinx-coroutines 1.11.0 · kotlinx-serialization 1.11.0 · dev.detekt 2.0.0-alpha.5 ·
de.fayard.refreshVersions 0.60.6.

detekt runs on **`dev.detekt` 2.0.0-alpha.5**, not the stable 1.x line — 1.23.x bundles an older
embedded Kotlin compiler that can't parse Kotlin 2.3's metadata format
([detekt/detekt#8865](https://github.com/detekt/detekt/issues/8865), closed as won't-fix for the
1.x branch). See the comment on `versions.properties`' `plugin.dev.detekt` entry before downgrading
it, and `cmp-bridge/build.gradle.kts`'s `detekt` task wiring for the KMP per-source-set workaround
this version needs.

`cmp-bridge`'s production code deliberately depends only on `androidx.compose.ui:ui` (semantics
classes) + `compose.desktop.currentOs` (for `ComposeWindow`) — no `material3`/`foundation`/
`animation`/icon-font dependencies, since the library never draws UI itself. `cmp-bridge-sample` is
exempt from this rule: it's a demo app, not the library, so pulling in Compose UI widgets there is
appropriate.

## Where this stands

- **No sync mechanism exists between this repo and its origin**, and none is planned. This
  repository (originally extracted from a private monorepo, with the package renamed to
  `com.cramsan.cmpbridge`) is the canonical public copy going forward.
- **Publishing to Maven Central / a package registry is out of scope for now.** Nothing about the
  design blocks it later; it just hasn't been prioritized.
- **No Android driver exists yet.** The real equivalent would be Android's native
  `AccessibilityNodeProvider`/UI Automator tree (Compose already wires into it via
  `AndroidComposeViewAccessibilityDelegateCompat`), matching the same "use the platform's real
  tree" philosophy used for desktop and web. `cmp-bridge`'s Android target currently exists only as
  a placeholder.
- **Web scroll is implemented but unverified** against a real (non-sandboxed-headless) browser —
  see `WebBridgeDriver.scroll()`'s doc comment and the tracking issue for this.
