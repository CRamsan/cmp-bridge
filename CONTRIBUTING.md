# Contributing

Thanks for taking a look at cmp-bridge. This document covers how to build the project,
what CI expects from a PR, and the conventions the codebase follows so your change fits
in without a round of style fixups. For how the modules fit together, read
[ARCHITECTURE.md](ARCHITECTURE.md) first — it explains the desktop/web split and the
`BridgeDriver` interface everything is written against, which is useful context before
touching any of the code below.

## Prerequisites

- JDK 21 (matches `jdkVersion` in `gradle.properties`, which drives every module's
  toolchain).
- No Android SDK is required to build day-to-day: `cmp-bridge`'s Android target only
  needs `compileSdkVersion`/`minSdkVersion` set in `gradle.properties`; CI installs the
  SDK itself via `android-actions/setup-android`.
- Nothing else to install by hand — Gradle, Kotlin, and Compose Multiplatform all come
  through the wrapper (`./gradlew`) and Gradle plugin resolution.

## Building and testing

```bash
./gradlew build          # compiles every module, runs tests and detekt, same as CI
./gradlew test jvmTest    # unit + JVM tests only (module-dependent target name)
./gradlew detekt          # static analysis / style, no autocorrect
./gradlew detekt --auto-correct   # apply the formatting rules detekt can fix automatically
```

Run a single module's tests with `./gradlew :module-name:test` (or `:jvmTest` for the
KMP modules, `cmp-bridge` and `cmp-bridge-sample`).

`cmp-bridge-sample`'s `DemoScenarioTest` is the project's real end-to-end test: it
launches an actual desktop app subprocess and an actual wasmJs dev server subprocess
and drives both through the real bridge. It's slower than the rest of the suite (the
web case brings up a webpack dev server) and needs network access, but it's the
strongest signal that a change to the protocol, the desktop server, or either driver
still works end to end — run it explicitly (`./gradlew :cmp-bridge-sample:jvmTest`)
after touching anything in `cmp-bridge` or `cmp-bridge-driver`.

CI (`.github/workflows/build.yml`) runs `./gradlew build` on every push to `main` and
every PR — that single command is the bar a PR needs to clear.

## Dependency versions

Versions are managed by [refreshVersions](https://github.com/jmfayard/refreshVersions),
not a version catalog. Don't hand-edit `versions.properties` — it's generated, and its
own header says so. Two ways it gets updated:

- **Automatically**: `.github/workflows/refreshVersions.yml` runs weekly (and can be
  triggered manually), opens a `dependency-update` PR with whatever
  `./gradlew refreshVersions` finds.
- **Manually, when a PR needs a specific bump now**: run `./gradlew refreshVersions`
  locally and commit the resulting diff to `versions.properties`.

When adding a *new* dependency, declare it in the module's `build.gradle.kts` with the
`:_` placeholder version (e.g. `implementation("some.group:artifact:_")`), then run
`./gradlew refreshVersions` to populate its resolved version in `versions.properties`.
The one documented exception is Ktor libraries in `cmp-bridge-http-server`: they're
version-aligned by the `io.ktor.plugin` Gradle plugin itself, so they're declared
*without* a trailing `:_` — see the comment above the Ktor dependencies in that
module's `build.gradle.kts` before changing that.

## Code style

Formatting and static analysis run through detekt (`config/detekt/detekt.yml`,
`buildUponDefaultConfig = true` — anything not explicitly overridden there falls back
to detekt's own shipped defaults plus the ktlint-wrapper ruleset). Keep that file's
philosophy: only add an override when there's a real, repo-specific reason, and leave a
comment explaining it, the same way the existing `FunctionNaming` override for
`@Composable` functions does.

Run `./gradlew detekt --auto-correct` before pushing to catch formatting issues
automatically; re-run plain `./gradlew detekt` to confirm nothing's left that needs a
manual fix.

Beyond what detekt enforces, match the conventions already visible in the codebase:

- **KDoc explains *why*, not *what*.** Look at any file in `cmp-bridge-driver` or
  `cmp-bridge` — comments call out non-obvious constraints (why `Robot` isn't used, why
  a socket is opened per-command, why a workaround exists) rather than restating the
  signature. If you catch yourself writing a comment that just repeats the function
  name in prose, delete it.
- **`@Suppress` needs a reason on the line above it**, the same way
  `TooGenericExceptionCaught` and `TooManyFunctions` are justified at their call sites
  in this codebase — a bare `@Suppress` with no comment reads as suppressing a real
  problem rather than a deliberate design choice.
- **Constants are named, not magic.** Timeouts, ports, poll intervals, and byte offsets
  all live in companion-object `const val`s with names that explain the number (see
  `DesktopBridgeServer.RELEASE_OFFSET_MS`, `BridgeDriver.WAIT_FOR_TAG_POLL_INTERVAL_MS`).
- **Platform gaps are documented where they're hit, not hidden.** Where web's
  accessibility-DOM path can't do something desktop's semantics tree can (e.g. the
  known scroll and password-masking gaps referenced in `WebBridgeDriver`), the
  limitation is called out in a doc comment with a link to the tracking issue rather
  than silently producing a partial result.

## Adding to the protocol or a driver

If your change adds a new bridge operation (a new `BridgeCommand`/`BridgeResponse`
variant, or a new `BridgeDriver` method), it touches more than one module — treat it as
a checklist:

1. `cmp-bridge`: extend `BridgeCommand`/`BridgeResponse` (`BridgeProtocol.kt`), and
   implement the handling in `DesktopBridgeServer`.
2. `cmp-bridge-driver`: add the method to the `BridgeDriver` interface, and implement it
   in both `DesktopBridgeDriver` and `WebBridgeDriver`. If web genuinely can't support
   it, say so in a doc comment on the interface method and in `WebBridgeDriver`'s
   implementation, the way `scroll`'s known-issue gap is documented today — don't leave
   it silently unsupported.
3. `cmp-bridge-http-server`: add the REST route in `Routes.kt`.
4. `cmp-bridge-mcp-server`: add the MCP tool in `Tools.kt`.
5. `cmp-bridge-sample`: exercise the new operation from `DemoScenarioTest` on both
   platforms (or document why one platform is skipped, as the web test already does for
   the operations it can't cover) — this is what actually proves the change works
   end to end, not just that it compiles.

## Opening a PR

- Keep PRs scoped to one change; the checklist above is a lot of surface area for a
  single bridge operation, but it shouldn't also carry unrelated refactors.
- Make sure `./gradlew build` passes locally before opening the PR — it's exactly what
  CI runs.
- Don't add prose "why we built this" or historical documentation to the codebase
  itself. This repo has deliberately moved doc comments toward "why", not narrative —
  put the *what changed and why* in the PR description, not in a new markdown file.

## License

cmp-bridge is licensed under the Apache License, Version 2.0 (see `LICENSE`).
Contributions are accepted under the same license.
