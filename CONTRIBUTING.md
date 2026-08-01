# Contributing

## Prerequisites

- JDK 21.
- An Android SDK, with `local.properties`' `sdk.dir` pointing at it (gitignored; only `cmp-bridge`
  declares an Android target, currently an empty placeholder — see
  [`ARCHITECTURE.md`](ARCHITECTURE.md)).

## Building and testing

```
./gradlew build
```

This runs the full multiplatform build across jvm/wasmJs/Android targets, both test suites
(`cmp-bridge`'s unit tests and `cmp-bridge-sample`'s `DemoScenarioTest` E2E suite), packaging tasks,
and `detekt`. It's the same command CI (`.github/workflows/build.yml`) runs, and is expected to be
green before a PR is opened.

To run a single module's tests, e.g.:

```
./gradlew :cmp-bridge:test
./gradlew :cmp-bridge-sample:jvmTest
```

## Static analysis / formatting

Static analysis runs on [`dev.detekt`](https://detekt.dev/) 2.0.0-alpha.5, not the stable 1.x line —
stable 1.23.x bundles an older embedded Kotlin compiler that can't parse this repo's Kotlin 2.3.21
metadata format ([detekt/detekt#8865](https://github.com/detekt/detekt/issues/8865), closed as
won't-fix for 1.x). Don't downgrade it. It's wired into `check`/`build`, so a plain `./gradlew build`
already covers it, but during iteration:

```
./gradlew detekt                 # read-only check
./gradlew detekt --auto-correct  # fixes formatting-rule violations in place
```

Config lives in the one shared `config/detekt/detekt.yml`, referenced from all 5 modules'
`build.gradle.kts` with `buildUponDefaultConfig = true`.

If you add a new source set to `cmp-bridge` or `cmp-bridge-sample` (both Kotlin Multiplatform),
check that it's picked up by the `tasks.named("detekt") { dependsOn(tasks.matching { ... }) }`
wiring in that module's `build.gradle.kts` — detekt 2.0-alpha's Gradle plugin doesn't wire
per-source-set KMP tasks into the umbrella `detekt` task on its own (see the comment above that
wiring for the full explanation). If a `detekt-baseline-*.xml` file ever appears uncommitted at a
module root, delete it rather than committing it — it means the task filter matched a
baseline-generating task by accident.

## Adding or updating a dependency

There's no version catalog. Add the dependency as a raw Maven coordinate with a
[refreshVersions](https://github.com/Splitties/refreshVersions) `:_` placeholder:

```kotlin
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:_")
```

then add (or let `./gradlew refreshVersions` add) a matching entry to `versions.properties`:

```
version.org.jetbrains.kotlinx..kotlinx-coroutines-core=1.11.0
```

Two exceptions — see [`ARCHITECTURE.md`](ARCHITECTURE.md#build-system) for why:

- **Plugins** (`plugins {}` blocks) take no version at all in `build.gradle.kts` — instead add a
  `plugin.<pluginId>=<version>` entry to `versions.properties` (see the comment there; this is a
  different mechanism from the `:_` placeholder, which doesn't work for plugins here).
- **Ktor libraries** (`io.ktor:*`) take no version notation of any kind — no `:_`, no
  `versions.properties` entry.

`./gradlew refreshVersions` writes `## # available=X.Y.Z` comment suggestions directly into
`versions.properties`; review and delete the comment line(s) you want to accept, then re-sync. Do
not run `./gradlew refreshVersionsMigrate` — this repo already went through a one-time
catalog-to-properties migration with it, and running it again from the current state won't do what
you expect (it only migrates forward, isn't additive, and provides no help going the other way).

## Pull requests

- Keep `./gradlew build` green.
- Avoid unrelated formatting churn — `detekt --auto-correct` should only touch lines your change
  actually affects.
