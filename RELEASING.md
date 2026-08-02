# Releasing

Maintainer-only notes for releasing cmp-bridge. A single `vX.Y.Z` tag push drives
`.github/workflows/release.yml`'s single `release` job, which runs in strict order — build and
test once, then reuse that build for both distribution channels:

1. **Build and test** every module once (`./gradlew build`) — the same bar as `build.yml`.
2. **Build CLI fat jars** for `cmp-bridge-http-server`/`cmp-bridge-mcp-server` from that same
   build (`shadowJar`, no recompilation).
3. **Create the GitHub Release**, attaching those fat jars. These two are CLI applications, not
   libraries — nobody adds a CLI tool as a Gradle dependency — so they're distributed as standalone
   downloads instead of Maven artifacts. See "GitHub Release: the CLI tools" below.
4. **Publish to Maven Central**: `cmp-bridge` and `cmp-bridge-driver`, the only two modules meant
   to be depended on as libraries (see the `mavenPublishing { ... }` block duplicated across each
   one's `build.gradle.kts`).

Doing this as one sequential job (rather than parallel jobs) avoids compiling the shared modules
twice on separate runners. The trade-off: the whole job — including the build/test/GitHub-Release
steps, not just the Maven publish step — sits behind `environment: release`'s protection rules
(see step 8 below), since environment gating applies at the job level. If a step fails partway
through, re-running the job re-runs everything from the start, including the build.

`cmp-bridge-sample` is never released.

If you're looking for how to *consume* cmp-bridge, see [README.md](README.md).

Maven Central publishing targets Sonatype's **Central Publisher Portal** (`central.sonatype.com`)
— the legacy OSSRH host is gone — via the `com.vanniktech.maven.publish` Gradle plugin.

## One-time setup (do these once, in order)

1. Create a Sonatype Central Portal account at `central.sonatype.com`.
2. Register the namespace `com.cramsan` (Portal → Namespaces → Add Namespace).
3. Add the DNS TXT record Central Portal provides to `cramsan.com`'s DNS zone, then verify in the
   Portal UI. This checks the exact `cramsan.com` domain, not a subdomain — allow 5–30 minutes for
   DNS propagation before retrying verification.
4. Generate a User Token in Central Portal (Account → Generate User Token) — a username/password
   pair distinct from your login credentials.
5. Generate a dedicated GPG key pair for release signing: `gpg --full-generate-key` (RSA 4096, with
   a passphrase). Don't reuse a personal git-commit-signing key for this.
6. Publish the public key to a keyserver Central Portal checks, e.g.:
   ```bash
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>
   ```
7. Export the private key ASCII-armored, for pasting into a GitHub secret:
   ```bash
   gpg --export-secret-keys --armor <KEY_ID> > private-key.asc
   ```
   Delete `private-key.asc` once it's pasted into the secret below — don't leave it on disk.
8. In `CRamsan/cmp-bridge` → Settings → Environments, create (or reuse) an environment named
   `release`, then add these as *environment* secrets on it (not repo-level Actions secrets — the
   `release` job in `release.yml` targets `environment: release`, so secrets added anywhere else
   won't be visible to it):
   - `MAVEN_CENTRAL_USERNAME` — the token username from step 4
   - `MAVEN_CENTRAL_PASSWORD` — the token password from step 4
   - `GPG_SIGNING_KEY` — the full contents of `private-key.asc` from step 7
   - `GPG_SIGNING_PASSWORD` — the passphrase from step 5
9. Confirm the `developer { }` block's name/email in every module's `mavenPublishing { }` config
   (currently "Cesar Ramirez" / `contact@cramsan.com`) is what you want publicly visible on Maven
   Central — this is effectively permanent once a version is released, so fix it now if it's wrong
   rather than after the first tag.

## Cutting a release

1. In `gradle.properties`, bump `VERSION_NAME` to the next release version (drop the `-SNAPSHOT`
   suffix, e.g. `0.1.0-SNAPSHOT` → `0.1.0`). Commit this.
2. Tag the commit and push the tag: `git tag v0.1.0 && git push origin v0.1.0`. This triggers
   `release.yml`'s `release` job, which runs build → test → GitHub Release → Maven Central publish
   in order, in a single run.
3. The job builds and tests every module once, builds the CLI fat jars from that build, and
   attaches them to a GitHub Release for the pushed tag — no manual step needed for this part.
4. It then runs `publishToMavenCentral` for `cmp-bridge`/`cmp-bridge-driver` — an upload per
   published module, reusing the same build (no recompilation). Once it finishes, log into Central
   Portal and manually click "Publish" on each of the 2 pending deployments to actually release
   them. Every `mavenPublishing { }` block is deliberately left at `publishToMavenCentral()`'s
   default (leave deployments "pending" rather than `automaticRelease = true`), so this manual step
   is required for every release — that's intentional, not a bug, so the first upload of a new
   version can be sanity-checked in the Portal UI before it becomes permanent. If you'd rather
   releases auto-publish, that's a one-line change (`publishToMavenCentral(automaticRelease =
   true)`) in each module's `mavenPublishing { }` block, once you trust the pipeline enough to skip
   the manual check.
5. Bump `VERSION_NAME` again to the next `-SNAPSHOT` (e.g. `0.1.0` → `0.1.1-SNAPSHOT`) in a
   follow-up commit, so local `publishToMavenLocal` builds and any in-progress work don't
   accidentally resolve as the just-released version.

## GitHub Release: the CLI tools

`cmp-bridge-http-server` builds its fat jar via `io.ktor.plugin`'s `fatJar { }` (a Ktor-flavored
wrapper around the Shadow plugin); `cmp-bridge-mcp-server` applies `com.gradleup.shadow` directly
for the same effect, since it has no other reason to depend on Ktor. Both wire up `Main-Class`
automatically from their `application { mainClass.set(...) }` block, so the resulting jars are
directly runnable:

```bash
java -jar cmp-bridge-http-server-all.jar --platform desktop
java -jar cmp-bridge-mcp-server-all.jar --platform desktop
```

**Known size caveat**: both fat jars are ~250 MB, because `cmp-bridge-driver` depends on
`com.microsoft.playwright:playwright` (for `WebBridgeDriver`), and Playwright's Java bindings
bundle a full Node.js runtime for every platform (linux x64/arm64, mac x64/arm64, windows x64)
inside its own jar — that dwarfs everything else in either fat jar. This predates
`cmp-bridge-mcp-server` getting a fat jar at all; `cmp-bridge-http-server`'s was already this size.
It's well within GitHub's 2 GB per-file release-asset limit, so it's not a blocker, just worth
knowing before you go looking for why the download is so large. Shrinking it would mean splitting
`cmp-bridge-driver`'s desktop and web drivers into separate modules so a CLI that only needs
`DesktopBridgeDriver` isn't forced to carry Playwright too — a real change, not something to do
as a side effect of a release-process update.

## Local verification before a release

`./gradlew publishToMavenLocal` works with no secrets configured — signing/upload tasks no-op
locally. Useful for a dry run before pushing a tag:

```bash
./gradlew publishToMavenLocal
find ~/.m2/repository/com/cramsan/cmpbridge -type f
```

Things worth checking in that output, given how much of this setup was non-obvious to get right
the first time (see the comments in each module's `mavenPublishing { }` block for the specifics):

- `cmp-bridge` produces 4 target-publications: root/metadata, `-android`, `-jvm`, `-wasm-js`.
- `cmp-bridge-driver` produces the plain jar + sources + javadoc, nothing else.
- POM contents render correctly for a sampled module or two: license, SCM URLs, and the
  `developer` block.
- Neither `cmp-bridge-http-server` nor `cmp-bridge-mcp-server` produces anything under
  `~/.m2/repository` at all — they don't apply the publishing plugin.

For the CLI jars, `./gradlew :cmp-bridge-http-server:shadowJar :cmp-bridge-mcp-server:shadowJar`
builds both locally (output in each module's `build/libs/*-all.jar`) without needing a tag or any
secrets — useful for testing `release.yml`'s build step before relying on CI to catch a problem.
