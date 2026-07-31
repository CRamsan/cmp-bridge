# CLAUDE.md

Context for Claude Code when working in this repository. This project was extracted from
`CodeHavenX/MonoRepo` (`framework/cmp-bridge*`) on 2026-07-31 as a standalone open-source library.
This file exists to carry that history and the hard-won debugging context forward — most of it is
not derivable from the code alone.

## What this is

`cmp-bridge` drives and inspects a **live** Compose Multiplatform UI over a typed wire protocol.
It does not simulate the UI or maintain a shadow model — every read comes from the real, current
state of a running app:

- **Desktop**: reads `ComposeWindow.semanticsOwners` (the real semantics tree, `@OptIn(ExperimentalComposeUiApi::class)`) fresh on every request, no caching.
- **Web**: reads Compose Multiplatform's own hidden accessibility DOM, which is enabled by default (`ComposeViewportConfiguration.isA11YEnabled = true`) — no custom JS export needed on the app side at all.

Two consumers share the exact same `BridgeDriver` API: JUnit E2E test suites, and AI clients via
the HTTP/MCP servers. That symmetry is a deliberate design goal, not a coincidence.

## Module map

| Module | Role |
| --- | --- |
| `cmp-bridge` | In-app server (`DesktopBridgeServer`). Builds `HierarchyNode` trees from the real semantics tree, serves `BridgeCommand`/`BridgeResponse` (kotlinx-serialization sealed classes) over a plain socket. |
| `cmp-bridge-driver` | Client side. `BridgeDriver` interface (`getHierarchy`/`click`/`setText`/`scroll`/`screenshot`, plus default `getBounds`/`waitForTag` built purely on `getHierarchy`). `DesktopBridgeDriver`/`WebBridgeDriver` are **connect-only**. `DesktopAppProcess`/`WasmDevServerProcess` each own *only* a launched subprocess. `ManagedBridgeDriver` is a generic `BridgeDriver by driver` wrapper pairing any `AutoCloseable` resource with any driver for single-call teardown. |
| `cmp-bridge-http-server` | Thin Ktor REST wrapper around `BridgeDriver` (`GET /hierarchy`, `POST /click|/setText|/scroll`, `GET /screenshot`). Connect-only — never launches an app. |
| `cmp-bridge-mcp-server` | Same shape as the HTTP server, but as MCP tools over stdio (`io.modelcontextprotocol:kotlin-sdk`). |
| `cmp-bridge-sample` | Standalone demo app (desktop + wasmJs) plus `DemoScenarioTest`, a JUnit E2E suite driving it through `BridgeDriver` on both platforms — see gotcha #10, discovered while building it. This is the fixture the "No E2E tests were brought over" note below used to say didn't exist. |

## Design history worth knowing

- **The original design was a hand-rolled tag registry** (`TestBridgeRegistry` + `Modifier.testTagBridge` + a custom `window.testBridge` JS export). It was **fully replaced**, not kept alongside the new model, once it became clear Compose already exposes a real semantics tree on every platform — verified directly against decompiled Compose Multiplatform 1.10.3 jars, not docs. Screens now just use plain `Modifier.testTag(tag)`.
- **`launch()`/`connect()` used to be one fork inside `DesktopBridgeDriver`/`WebBridgeDriver`** (nullable process/logFile fields, conditional `close()` depending on which factory built the instance). That was deliberately split into today's shape — connect-only drivers + `DesktopAppProcess`/`WasmDevServerProcess` + `ManagedBridgeDriver` — because a class with an internal mode-fork should be several single-purpose classes composed together instead. Apply that same instinct to any future class here that grows a "how was I built" branch.
- **Renamed from `ui-test-bridge*` to `cmp-bridge*`** once the library's scope grew past pure test tooling (AI-client exploration via HTTP/MCP isn't a "test" activity). "CMP" = Compose Multiplatform, matching JetBrains' own abbreviation and naming the actual dependency: this only works because CMP exposes a live semantics tree on both desktop and web.
- **Extracted from the monorepo** with the package renamed again, `com.cramsan.framework.cmpbridge` → `com.cramsan.cmpbridge` — "framework" was the monorepo's internal grouping directory, meaningless standalone.

## Confirmed-live gotchas (don't rediscover these)

1. **Transient degenerate `(0,0,0,0)` bounds.** A freshly-composed-but-not-yet-laid-out element (or one whose text-bearing semantics haven't settled after a screen transition) can report all-zero bounds on the very first read after it appears — seen on both desktop (semantics tree) and web (debounced 100–1000ms accessibility-DOM sync). Caused real, intermittent test flakiness (`click()` computing coordinates from a zero-sized rect, silently missing the target). Fixed generally in `BridgeDriver.getBounds()`, which filters `width > 0 && height > 0`. Code needing *text* specifically (not just bounds) should still poll rather than trust the very first snapshot.
2. **JS falsy-string coercion.** `el.innerText || null` (and the equivalent for `getAttribute`) silently turns a legitimate empty string into `null` — broke reading a freshly-focused, still-empty text field on web. Fixed by reading `el.innerText` directly with no `||` coercion; web's `text` is therefore never actually `null` in practice (always at least `""`), an accepted asymmetry vs. desktop's real `null` for "no text-bearing semantics at all."
3. **Compose Web attaches its whole render, including the accessibility DOM, to a real shadow root on `<body>`** (`document.body.attachShadow(...)`). Plain `document.getElementById()` can never see into it — always go through `document.body.shadowRoot?.getElementById('cmp_a11y_root')`.
4. **Web scroll is broken in at least one sandboxed headless Chromium fallback build**: `page.mouse().wheel()` corrupts Compose Web's reported layout state (elements collapse to degenerate bounds and never recover, no console/page error). A manual drag-gesture substitute was also tried and produced no scroll effect. Left implemented as `wheel()` (semantically correct, may work in a real browser) — **treat as unverified, not proven-broken-everywhere**, until confirmed against a real non-fallback browser.
5. **Desktop screenshot**: `Robot.createScreenCapture` fails in headless/sandboxed environments ("Screen Capture in the selected area was not allowed"). Use `Component.paint()`/`window.paint(graphics)` into an off-screen `BufferedImage` instead — that's what `DesktopBridgeServer` does.
6. **Desktop input must use direct AWT event-queue injection** (`Toolkit.getDefaultToolkit().systemEventQueue`), not `java.awt.Robot` — `Robot`-based synthetic input hangs in at least one sandboxed X server.
7. **Password fields**: an app must explicitly mark them `Modifier.semantics { password() }` — `PasswordVisualTransformation` alone only masks drawn glyphs, not what the semantics tree reports. `DesktopBridgeServer` suppresses `text`/`contentDescription` when `SemanticsProperties.Password` is present. **Known unfixable gap**: Compose Web's own accessibility-DOM sync doesn't check for `Password` before setting `innerText`, so a password field's real text can still leak into web's accessibility DOM regardless of the `password()` marker or anything this library does — flag this to anyone relying on it, don't try to route around it client-side.
8. **Role normalization** deliberately mirrors Compose Web's own `ComposeWebSemanticsListener.getRoleId()` ordering, including its acknowledged imprecision: an element with both an explicit `Role` and `OnClick` (e.g. a checkbox) normalizes to `"button"` on both platforms, matching a `// TODO: Not everything with OnClick is a button!!!` in Compose's own source. This is intentional platform-parity, not a bug to "fix" on just one side.
9. **MCP stdio transport**: nothing may write to stdout except the JSON-RPC stream itself. The `kotlin-sdk`'s own `kotlin-logging` dependency prints an init banner straight to stdout before any protocol traffic starts, corrupting parsing. Fixed by capturing the real `System.out` first thing in `main()` and redirecting `System.out` to stderr before constructing anything else — if this class ever gets refactored, that ordering must be preserved.
10. **Web text-input elements permanently report `(0,0,0,0)` bounds.** On Compose Multiplatform 1.10.3's wasmJs target, a real text-input composable — confirmed for both material3 `TextField` and the simpler `BasicTextField`, so it isn't about decoration-box complexity — and whatever Column sibling immediately follows it never get a real bounding box in the accessibility DOM. Confirmed live in `cmp-bridge-sample`'s `DemoScenarioTest`: stable across 12+ seconds of polling (ruling out gotcha #1's transient settling gap) and a real screenshot showing the canvas itself never renders anything past that point — so this isn't just an accessibility-DOM sync lag, the layout pass itself stalls. `getHierarchy()` still reports the element's correct *text* even with zero bounds; only a `getBounds()`-based `click()`/`setText()` can't locate it. No known app-side fix — `cmp-bridge-sample`'s web test documents this rather than working around it with sibling-ordering tricks (tried first; every ordering just moves which tag breaks).
11. **detekt 1.23.x (stable) is broken on this repo's Kotlin 2.3.21** — it bundles its own older embedded Kotlin compiler (~2.0.0) that can't parse 2.3's metadata format ([detekt/detekt#8865](https://github.com/detekt/detekt/issues/8865), closed as won't-fix for the 1.x branch). This repo uses **`dev.detekt` 2.0.0-alpha.5** instead (new group/plugin id, pre-1.0 API) — confirmed working. Don't "helpfully" downgrade to `io.gitlab.arturbosch.detekt` 1.x thinking it's the safer/stable choice; it will misbehave here. See `config/detekt/detekt.yml` for the version-specific config-key renames this surfaced (`FunctionNaming` now lives under `naming:`, not `style:`).
12. **detekt 2.0-alpha's Gradle plugin doesn't wire per-source-set KMP analysis tasks into the plain `detekt` task.** On `cmp-bridge`/`cmp-bridge-sample` (both Kotlin Multiplatform), the plugin generates one task per source set/compilation (`detektJvmMainSourceSet`, `detektCommonMainSourceSet`, ...) instead of a single task covering the whole module — the umbrella `detekt` task (the one `check`/`build` actually depend on) reports `NO-SOURCE` and silently skips all of them unless told about them explicitly. Both KMP modules' `build.gradle.kts` add `tasks.named("detekt") { dependsOn(tasks.matching { ... }) }` to fix this — **don't remove that wiring**, and don't assume a plain `ktlint`/`detekt` task run alone covers a KMP module's real source. The task filter must also exclude anything matching `*Baseline*SourceSet` (a too-broad `it.name.endsWith("SourceSet")` predicate will trigger `detektBaseline*` tasks too, which silently write a baseline file that grandfathers in every current violation — confirmed live, caught only because findings mysteriously vanished after wiring this up. If a `detekt-baseline-*.xml` file ever turns up uncommitted at a module root, that's this happening again — delete it, don't commit it.

## Build system notes

Standalone Gradle build (`gradle/libs.versions.toml` version catalog) — **not** a copy of the
monorepo's `build-logic` convention-plugin setup or refreshVersions. Pinned versions were resolved
live from the monorepo's actual dependency graph at extraction time, not guessed:

kotlin 2.3.21 · compose-multiplatform 1.10.3 · AGP 9.2.1 · ktor-plugin 3.5.0 ·
kotlinx-coroutines 1.11.0 · kotlinx-serialization 1.11.0 · kotlinx-io 0.9.1 · clikt 5.1.0 ·
mcp kotlin-sdk 0.15.0 · playwright 1.61.0 · mockk 1.14.11 · junit-jupiter 5.12.2 ·
dev.detekt 2.0.0-alpha.5

- `cmp-bridge`'s Android target is a **deliberately empty placeholder** (kept on request despite zero real Android source) — building it standalone needs a real Android SDK; `local.properties` (gitignored) points `sdk.dir` at `/home/cramsan/Android/Sdk`.
- `cmp-bridge`'s production code only ever needs `androidx.compose.ui:ui` (semantics classes) + `compose.desktop.currentOs` (for `ComposeWindow`) — deliberately did **not** carry over the monorepo's `kotlin-mpp-common-compose` convention plugin, which would have dragged in `material3`/`foundation`/`animation`/icon-font deps and an internal-only `:framework:ui-preview` module dependency that this library never uses.
- **detekt is set up** (`dev.detekt` 2.0.0-alpha.5, not the stable 1.x line — see gotchas 11–12 for why). Applied individually in all 5 modules' `build.gradle.kts` (no shared convention-plugin module exists to hook into instead), each pointing at the one shared `config/detekt/detekt.yml` with `buildUponDefaultConfig = true`. `./gradlew detekt` runs read-only (wired into `check`/`build`); `./gradlew detekt --auto-correct` fixes formatting-rule violations in place. The `detekt-rules-ktlint-wrapper` dependency (`libs.detekt.formatting` in the version catalog — note the *artifact* renamed from 1.x's `detekt-formatting`, kept the old catalog alias name since it's an internal reference) is what actually provides the formatting/ktlint-style rules; without it `formatting`-category rules don't run at all.
- No E2E tests were brought over at extraction time — the monorepo's `BridgeDriverContractTest`/`SignInFlowTest` suites depend on `framework-samples`/`edifikana` app fixtures that don't exist standalone. Only the 2 self-contained unit test files (`BridgeProtocolTest`, `RoutesTest`) came along. **This has since been addressed**: `cmp-bridge-sample` is a from-scratch fixture (not a port of the old suites) with its own `DemoScenarioTest` E2E suite — see the module map and gotcha #10.
- `cmp-bridge-sample` is exempt from the `material3`/`foundation`-avoidance rule below — it's a demo app, not the library, so pulling in Compose UI widgets is appropriate there. It depends on them via direct Maven coordinates (e.g. `"org.jetbrains.compose.material3:material3:1.9.0"`), not the deprecated `compose.material3`-style accessors, which this compose-multiplatform version's Gradle script compiler now errors on.
- Build/verify: `./gradlew build` (full multiplatform build + both test suites + packaging tasks). Confirmed green across jvm/wasmJs/Android targets before the initial push.

## Repo facts

- **`https://github.com/CRamsan/cmp-bridge`** — public, Apache 2.0. Personal account (`CRamsan`), not the `CodeHavenX` org that owns the monorepo — an explicit choice, not a default.
- Initial commit `83785f4` on `main`.
- The monorepo's own copy (`CodeHavenX/MonoRepo`, `framework/cmp-bridge*`) **still exists** — it was not deleted after extraction. The two copies are already diverged as of the extraction date; there is no sync mechanism between them yet, and none has been decided on.
- An architecture-overview Artifact was published earlier in the monorepo's history (diagrams for module map, desktop/web transport, connection lifecycle) — it predates both the `cmp-bridge` rename and this extraction, so treat its naming as stale if it's ever pulled up as a reference; the ideas in it are still broadly accurate.

## Open / not yet decided

- No CONTRIBUTING.md, CHANGELOG, or Maven Central / package publishing setup.
- No sync strategy between this repo and the monorepo's copy.
- Web scroll unverified against a real (non-fallback-build) browser.
- Password-field leak into Compose Web's accessibility DOM (see gotcha 7) is an upstream Compose gap, not reported upstream yet.
- No Android driver exists — the real equivalent would be Android's native `AccessibilityNodeProvider`/UI Automator tree (Compose already wires into it via `AndroidComposeViewAccessibilityDelegateCompat`), matching the same "use the platform's real tree" philosophy used for desktop/web.
- Web text-input zero-bounds gap (gotcha 10) is an upstream Compose Multiplatform issue, not reported upstream yet.
