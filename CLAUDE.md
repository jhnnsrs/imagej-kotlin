# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Kotlin **ImageJ2/Fiji plugin** (`Plugins > Arkitekt`) that bridges ImageJ to the
[Arkitekt](https://arkitekt.live) platform. It logs the user in, registers the running
ImageJ instance as a remote **agent**, and exposes ImageJ actions (upload the active
image, load an image back into the viewer) that the Arkitekt server can invoke remotely
over a WebSocket. Images are moved as Zarr arrays stored in S3.

> Note: `pom.xml` is a **stale leftover** from the `example-imagej2-command-kotlin` template —
> the project migrated to Gradle, and the root POM is not part of any build (it even declares
> `<ciManagement><system>None</system></ciManagement>`). The build is Gradle (`build.gradle.kts`).
> `README.md` was rewritten and is current: it documents installing from a GitHub Release.

## Build & run

```bash
./gradlew build                 # compile + generate Apollo GraphQL clients
./gradlew run                   # launches ImageJ with the plugin via ArkitektCommand.main()
                                #   (provided by the `application` plugin; mainClass = ...ArkitektCommandKt)
./gradlew installToImageJ       # syncs plugin + runtime deps into a local Fiji.app
                                #   (default target /home/jhnnsrs/Programs/.../Fiji.app/plugins/arkitekt;
                                #    override with -PfijiDir=/path/to/Fiji.app/plugins/arkitekt)
./gradlew buildPlugin           # bundles plugin + deps into build/arkitekt-plugin-<version>.zip
```

**JDK requirement (important):** build/run needs a **full (non-headless) JDK 17**.
`gradle.properties` pins this via `org.gradle.java.home=/usr/lib/jvm/java-17-openjdk-amd64`,
because on this machine:
- the default `java` is OpenJDK **25** — too new for Gradle 8.10 (fails with a cryptic `25.0.3`);
- JDK **8** and **21** are *headless* builds (no `libawt_xawt.so`) — ImageJ launches with no
  window and `main()` returns immediately, so `run` exits in ~4s with no GUI.

JDK 11 and 17 are the only full JDKs installed; the build is pinned to 17. `./gradlew run` should
print a benign `Cannot create plugin: ...JavaScriptScriptLanguage` line and then keep running
(window stays up). On a different machine, point `org.gradle.java.home` at any full JDK ≤ 17.

Tests (`./gradlew test`, JUnit 5, 90 of them) cover the fakts token-rotation rule, expiry math and
flat-grant split (`FaktsTest.kt`, `TokenManagerTest.kt`); the agent wire format and close-code
policy (`AgentProtocolTest.kt`, `CloseCodeTest.kt`); the lens read path — render-axis derivation,
slice resolution, the permutation and the stride (`LensViewTest.kt`, which needs no network); the
ROI write path — collection-axis-order vectors, the inclusive far corner, the per-kind vertex
minimums and IJ1's 1-based/0-means-unset slice positions (`RoiAnnotationTest.kt`); and the agent's
whole connection lifecycle
(`AgentLifecycleTest.kt`) driven against `FakeGateway.kt`, a local Ktor stand-in for the `/agi`
gateway. The fake exists because the live gateway currently refuses our registration for a
server-side reason (see the org-claim note below) — without it, everything past REGISTER would be
verified only by reading, and that is exactly where a mishandled close hangs the agent or starts an
eviction war. The tests boot a headless ImageJ context (~250ms) to build a real `App`.
The fakts device-code flow still needs a live coordination server and is verified by hand. Bytecode target is pinned to **Java 8**
(`jvmTarget = "1.8"`, `sourceCompatibility/targetCompatibility = "1.8"`) for Fiji distribution,
even though the toolchain/run JVM is 17 — don't introduce APIs above Java 8 in source.

Both bundling tasks share one `stagePlugin` **`Sync`** task (so a dependency dropped from
`runtimeClasspath` also leaves the bundle instead of lingering forever; it now stages the single
`shadowJar` output plus the `.arkitekt-plugin` marker). `installToImageJ`
defaults to the author's Fiji path but takes `-PfijiDir=`; it stays a plain `Copy` on purpose,
since a `Sync` into a user-supplied directory would wipe whatever a mistyped path points at. `buildPlugin` is a real `Zip` task whose name carries `$version`
(`-PpluginVersion=` overrides it; that is how the release workflow stamps the git tag).

**Bundle size is a maintained property, not an accident** (177 MB -> 44 MB, 289 -> 102 jars,
now one shaded jar — see the shading section below). Both bundling tasks ship whatever reaches
`runtimeClasspath`, since that is what `shadowJar` reads:
- `net.imagej:imagej` is **`compileOnly`**, like `imagej-legacy`. As an `implementation` dep it
  dragged the whole ImageJ2/SciJava stack into the bundle — ~210 jars duplicating Fiji's own
  `jars/` (`scijava-common`, `imagej-common`, `imglib2`, and alarmingly `imagej-updater` and
  `imagej-launcher`), including ~70 MB of scripting-language engines (scala3-compiler 19 MB,
  jython 15 MB, jruby 18 MB, clojure, renjin) arriving via `imagej-scripting`. Fiji provides all
  of it at runtime. It stays available to `run`/`macroSmokeTest` through the **`imagejRuntime`**
  configuration and to the tests through `testImplementation`.
- `software.amazon.awssdk:netty-nio-client` is **excluded globally**: only the *sync* `S3Client`
  is ever built (`Datalayer` in `Arkitekt.kt`, zarr-java's `S3Store`), so the Netty async stack
  was ~4 MB of dead weight. This fails at **runtime, not compile time** — introducing an
  `S3AsyncClient` means dropping the exclude.

So: adding an `implementation` dependency that Fiji already ships is the easy way to re-bloat
this. Prefer `compileOnly` + a dedicated runtime configuration for anything in Fiji's `jars/`.

### Running on *any* Fiji: the baseline and the shading
Two separate hazards, both silent on the author's machine and loud on someone else's.

**1. Compile LOW, run HIGH.** `Fiji.app/jars` is exactly **`pom-scijava` 36.0.0** — imagej
**2.14.0**, ij 1.54f, imglib2 6.1.0, scijava-common 2.94.2, imagej-legacy 1.2.0, gson 2.10.1,
guava 31.1-jre, jackson 2.14.2, okhttp 4.11.0, slf4j 1.7.36, protobuf 3.23.0. `net.imagej:imagej`
is pinned to **2.14.0** for that reason: a newer Fiji still has these methods, an older one does
not have 2.16.0's — compiling against the newer aggregator was the wrong direction. Every ImageJ2
class this plugin imports lives in `imagej-common-2.0.4` / `imagej-ops-2.0.0` /
`scijava-common-2.94.2` / `imglib2-6.1.0`; the overlays are **not** in the `imagej-deprecated` jar
Fiji also ships, so no extra coordinate is needed. Bump the pin only to a version some target Fiji
actually ships. ⚠️ Do **not** replace it with `platform("org.scijava:pom-scijava:36.0.0")`: 662 of
its managed entries include `kotlin-stdlib` → `${kotlin.version}` and `kotlinx-coroutines` →
**1.6.4**, which drags the build back below Kotlin 2.1 / ktor 3. It is the source of truth for
version *numbers*, not a platform to import.

**2. One shaded jar, not ~102 loose ones.** Fiji loads `jars/**` and `plugins/**` into a single
flat classloader, so every library we ship that Fiji also ships was a coin flip decided by the
launcher — kotlin-stdlib 2.1.0 vs its 1.8.22, guava 33.4.8 vs 31.1, jackson 2.20 vs 2.14.2, okio
3.9 vs 3.3, protobuf 4.31 vs 3.23, cdm-core 5.9.1 vs 5.3.3 — and whichever copy won, something
broke. `shadowJar` (`com.gradleup.shadow`; the `johnrengelman` id is dead on Gradle 8.10) relocates
those under `com.mycompany.arkitekt.shaded.`. The rule for that list is **relocate what Fiji
ships** — adding relocations for packages Fiji lacks (ktor, apollo, awssdk) buys nothing and costs
risk, since some resolve resources by package path. Two deliberate exceptions:
- **JNI is never relocated** — `com.github.luben.zstd` and blosc-java encode the Java package in
  their native symbol names (`Java_com_github_luben_zstd_…`), so relocating breaks the binding.
  zstd-jni is instead version-matched to Fiji's 1.5.5-10 in `zarr-java/build.gradle.kts`.
- Relocating `org.slf4j` ships an slf4j API with **no provider**, so slf4j output goes to NOP.
  Harmless here (logging goes through `org.scijava.log.LogService` and `println`).

Shadow rewrites **string constants**, not just bytecode — a literal beginning with a relocated
package token comes out prefixed. That is what makes `Class.forName("ucar.ma2.Array")` keep
working, but it also mangles log messages: `"kotlinx-serialization round-trip OK"` printed as
`"com.mycompany.arkitekt.shaded.kotlinx-serialization round-trip OK"` until it was rephrased.

Derive the relocation list by comparing **packages**, not artifact names: one jar can carry
several packages, which is how `thredds` and `uk.ac.rdg.resc.edal` were missed at first — they
ship *inside* cdm-core next to `ucar`, so relocating only `ucar` left our 5.9.1 classes binding to
Fiji's 5.3.3 ones. Diff the shaded jar's package set against every jar in `Fiji.app/jars` after
any dependency change. Doing that leaves exactly **5** collisions, both groups deliberate:
`com.github.luben.zstd(.util)` (JNI, version-matched) and `javax.annotation*` (jsr305, annotation
-only and identical 3.0.2 on both sides).

Both `./gradlew run` and the JUnit tests exercise the **unshaded** classpath, so a broken
relocation would otherwise first surface inside someone's Fiji. Two tasks, wired into `check`,
catch it in CI instead: **`verifyShadedJar`** (nothing that should have moved is still top-level,
the relocated copies exist, and `META-INF/json/org.scijava.plugin.Plugin` — the only reason the
menu entry appears — survived the merge) and **`shadedSmokeTest`**, which runs `MacroSmokeTest.kt`
out of the shaded jar with the **`fijiVintage`** configuration (Fiji's own kotlin-stdlib 1.8.22 /
guava 31.1 / jackson 2.14.2 / okio 3.3.0) **ahead of it** on the classpath. That ordering is the
whole point: it reproduces Fiji's older jar winning, so it fails unshaded and passes shaded.

`installToImageJ` now clears stale jars before copying — but **only** from a directory holding the
`.arkitekt-plugin` marker it writes, so a mistyped `-PfijiDir` still cannot delete anything. This
matters because the pre-shading `Copy` accumulated: the author's install had grown to 329 files
carrying cdm-core 5.5.3 *and* 5.9.1, guava 30.1 *and* 33.4.8, okhttp 2.7.5 + 4.11 + 4.12 and
netty/jnr jars from dependency sets that no longer exist — all of it still on Fiji's classpath.
An install predating the marker must be `rm -rf`'d once by hand; the task says so and refuses.

### CI / releases
`.github/workflows/ci.yml` builds, tests and bundles on push/PR (temurin 17, `gradle/actions/setup-gradle`),
uploading the zip as a run artifact. `.github/workflows/release.yml` fires on a `v*` tag and
attaches `arkitekt-plugin-<version>.zip` to a GitHub Release. Both pass
`-Dorg.gradle.java.home="$JAVA_HOME"` to override the machine-specific `org.gradle.java.home`
pinned in `gradle.properties` — a command-line `-D` outranks the project properties file.
CI must never run `./gradlew run` (it launches a GUI); tests set
`java.awt.headless` themselves, so no xvfb is needed. The workflow files under
`zarr-java/.github/` are vendored from upstream and inert — GitHub only reads the repo root.

**The one fragile dependency in CI is `maven.scijava.org`.** It is the sole non-Central
repository and it is *required* — the whole `net.imagej` group is absent from Maven Central
(`imagej`, `imagej-common`, `imagej-legacy` all 404 on repo1; only `org.scijava:*` is synced) — but the
host (144.92.48.196, UW-Madison) intermittently stalls: the first CI run died with `Read timed out`
fetching `net.imagej:imagej:2.16.0`. The log shows only that one attempt, so a network **timeout
appears to abort the whole resolution** rather than falling through to the next repo the way a 404
does. Two consequences: the retired alias `maven.imagej.net` was removed from
`repositories` — it resolves to the *same* IP, so it only doubled the exposure — **don't add it
back**; and `gradle.properties` carries `systemProp.org.gradle.internal.http.{connection,socket}Timeout`
plus `…repository.max.tentatives`/`initial.backoff` to give that one host more room than Gradle's
defaults. A dev machine hides all of this behind `~/.gradle/caches`; only a cold CI resolve sees it.

### zarr-java (vendored in-repo Gradle subproject)
- `zarr-java/` is **vendored into this repo** — a fork of upstream `zarr-developers/zarr-java`
  (code is upstream 0.1.3), tracked in this repo's history. It is a **Gradle subproject**
  (`include(":zarr-java")` in `settings.gradle.kts`; its `zarr-java/build.gradle.kts` translates the
  Maven `pom.xml`), consumed by the root via `implementation(project(":zarr-java"))`. So plain
  `./gradlew build` compiles it — **no more `mvn install` into mavenLocal** and no `libs/…jar` shuffle.
  The subproject's Maven-`compile`-scope deps are declared `api` so they stay on the plugin's
  classpath and in the `installToImageJ`/`buildPlugin` bundle (both come from `runtimeClasspath`).
- The `pom.xml` is retained **only** for independent Maven Central publishing (shade/gpg/central
  plugins); it is *not* used by `./gradlew build`. Its version stays pinned to `0.0.5-SNAPSHOT`.
  zarr-java's own tests are disabled in the Gradle build (they need Docker/S3/testcontainers) — run
  them via Maven if needed.
- This zarr-java is on **AWS SDK v2** (`software.amazon.awssdk:s3`); its `S3Store` takes a v2
  `S3Client`, so `Datalayer` builds the client with `S3Client.builder()…forcePathStyle(true)`.
  The subproject api-exports s3 `2.34.6` and the root also imports the awssdk BOM `2.32.8` — Gradle
  resolves the classpath to the higher `2.34.6` (both v2, API-compatible).
- Local patch in `zarr-java/`: v3 `BytesCodec` accepts a `bytes` codec with **no `configuration`**
  (legitimate for single-byte dtypes) — the `@JsonCreator` arg is nullable and `getByteOrder()`
  defaults to little-endian. Without it, reading server-written `uint8` arrays fails at parse time.

## GraphQL / Apollo codegen

Three Apollo services are configured in `build.gradle.kts`, each generating a typed client
under a distinct package. **Generated code is what you import** (e.g. `com.mycompany.mikro.graphql.GetLensQuery`).

| Service   | Package                        | `.graphql` ops + schema location | Backend role |
|-----------|--------------------------------|----------------------------------|--------------|
| `lok`     | `com.mycompany.lok.graphql`    | `src/main/graphql/lok/`          | auth / "who am I" (`MeQuery`) |
| `mikro`   | `com.mycompany.mikro.graphql`  | `src/main/graphql/mikro/`        | array datasets, lenses, coordinate systems + S3 upload/access grants |
| `rekuest` | `com.mycompany.rekuest.graphql`| `src/main/graphql/rekuest/`      | agent + action/implementation registration |

To add a query/mutation: drop a `.graphql` file in the right service dir and rebuild.

The backend serves each service's schema as **plain-text SDL** at `<host>/<service>/schema`, which
is not a GraphQL introspection endpoint — so there is no Apollo `introspection {}` block. Refresh a
committed schema with `./gradlew downloadMikroSchema` (or `downloadSchemas` for all three), which
does a plain GET against `$schemaHost` (`-PschemaHost=`, `$ARKITEKT_SCHEMA_HOST`, default
`http://jhnnsrs-lab`). It rewrites only `schema.graphqls`; `src/main/graphql/mikro/federation.graphqls`,
which hand-declares the `@key`/`@link` federation directives Apollo does not auto-import, is
committed separately and survives a refresh. The schema files are committed, so codegen works
offline.

⚠️ **mikro is on API v2.** `Image`, the whole `View` family (`RGBView`, `ChannelView`, …) and
`fromArrayLike` were deleted server-side; the identifier `@mikro/image` no longer exists. See the
Lens section below.

## Architecture

The whole plugin lives in `src/main/kotlin/com/mycompany/arkitekt/`:
`Fakts.kt` (protocol + models + cache), `TokenManager.kt`, `Auth.kt` (Apollo interceptors),
`Arkitekt.kt` (orchestration, image/Zarr, action handlers), `Actions.kt` (port definitions),
`LensView.kt` (the axis algebra of reading a lens), `RoiAnnotation.kt` + `RoiSources.kt`
(the axis algebra of *writing* a drawn ROI back), `Agent.kt` + `AgentProtocol.kt`,
`ArkitektCommand.kt`, `ArkitektTool.kt`, `ArkitektState.kt`, `NodeId.kt`, `MacroSmokeTest.kt`.

**Entry point** — `ArkitektCommand.kt`
- `@Plugin(menuPath = "Plugins > Arkitekt")` SciJava `Command`. SciJava injects services
  (`UIService`, `DatasetService`, `ImageDisplayService`, `Context`) via `@Parameter`.
- `run()` opens a Swing `Dialog` with an editable **Server** field (defaults to
  `DEFAULT_SERVER` = `https://go.arkitekt.live`) and a Login button → `Arkitekt.login(url, cb)`.
- `main()` boots a standalone ImageJ for IDE debugging.

**Orchestration** — `Arkitekt.kt` (the bulk of the code)
- **Auth: fakts protocol 2** (`Fakts.kt`; sequenced by `negotiate`/`getSession` → `alogin`).
  Two OAuth2 round-trips, where protocol 1 had four proprietary ones:
  `discover` (`GET {url}/.well-known/fakts` → `FaktsEndpoint`; **`protocol_version` must be
  `"2"`** — a protocol-1 deployment fails here, loudly, and there is no compatibility mode) →
  `deviceAuthorize` (`POST {device_authorization_endpoint}`, JSON `{manifest,
  requested_client_kind}` → RFC 8628 device authorization **plus dynamic client registration**:
  the `client_id` is minted here; gated on the non-RFC literal `status == "granted"`) → open the
  browser at the server-supplied `verification_uri_complete` → `pollToken` (`POST
  {token_endpoint}`, **form-encoded**, `grant_type=urn:ietf:params:oauth:grant-type:device_code`).
  There is no `claim` step: **the successful token response IS the claim** — the OAuth2 members
  and the fakts envelope (`self`/`instances`/`statuses`) are top-level siblings in one flat JSON
  object, split by `splitGrantResponse`.
  The poll **branches on the `error` member, never on HTTP success**: "still waiting" is an HTTP
  400 carrying `{"error": "authorization_pending"}`. It honours the server's `interval`
  (sleeping *before* the first poll), backs off `+5s` on `slow_down`, and is bounded by
  wall-clock `expires_in` on a monotonic clock. The device code is single-use.
- **Tokens rotate — this is the load-bearing constraint.** There is no `client_secret` any more,
  so nothing can independently re-mint a token, and the **refresh token rotates on every use**:
  two concurrent refreshes kill the session permanently (the user must re-approve in a browser).
  So every consumer — the three Apollo clients and the agent WebSocket — pulls its token from the
  single **`TokenManager`** (`TokenManager.kt`), which serializes refreshes under a `Mutex`,
  persists the rotated refresh token *before* handing out the new access token, and refreshes
  proactively 60s ahead of expiry. Its one subtle rule: a **forced** refresh (the server just
  rejected our token) must never settle for an in-flight **non-forced** one, which would hand
  back that very token — it waits the raced one out, then refreshes for real. Forced-into-forced
  coalesces. `AuthRetryInterceptor` (`Auth.kt`) forces a refresh and replays an operation once on
  `extensions.code == "UNAUTHENTICATED"` (and only that code — `PERMISSION_DENIED` and
  `INTERNAL_ERROR` are never retried).
  A refresh response may legitimately carry **no** envelope (the server renders it best-effort);
  that must refresh the session, not destroy it, so the current config is kept. Conversely a
  refresh that *does* carry one is the **config-push channel**: aliases are re-rendered against
  the requesting host, so config changes arrive without a human re-approving.
- **The session** (`FaktsSession` = endpoint + envelope + token) is cached to
  `~/.arkitekt/fakts_cache.json`, keyed by `sha256("v2:" + manifest + "|" + url)` (`FaktsCache`).
  Written on **every refresh**, not just at login — the rotated refresh token is the only thing
  that survives a restart. A protocol-1 record fails to decode, which is exactly the migration
  we want. `hasCachedConfig(url)` means "do I hold a usable refresh token", i.e. can this login
  be silent. A stale cache (aliases stop answering, or the refresh is rejected) triggers exactly
  one re-negotiation (self-heal). `logout()` clears it.
- **ActiveFakts shape**: `self` (deployment, whose `self.alias` **is** the lok endpoint — wired
  into `Unlok`, not a requirement), `instances` keyed by requirement key
  (`rekuest`/`mikro`/`datalayer`), and `statuses` (`GrantStatus`; the server only ever emits
  granted/denied/unavailable, `UNKNOWN` is our coercion). **There is no `auth` block** — the
  `client_id` the refresh grant needs lives on the token instead.
  `getFirstReachableAlias` GET-probes each alias's challenge URL
  (`alias.to_http_path(alias.challenge)`; `challenge` is a path fragment, not a nonce) to pick a
  live endpoint. Required services must be `GRANTED` and resolvable or `alogin` throws
  `CompositionError`. `Instance.challenge_key` (Ed25519) is modelled but **not verified** — the
  Python client does verify it, which is the obvious follow-up.
  After composing, `alogin` POSTs an alias report to `{base_url}report/` (best-effort, never
  throws) — the only use `base_url` has; every other endpoint arrives fully qualified.
- **Service clients**: `Unlok` (lok), `Mikro`, `Rekuest` each wrap an `ApolloClient` with an
  `AuthorizationInterceptor` (Bearer token pulled from the `TokenManager` **per request**, not
  baked in at construction) + `AuthRetryInterceptor` + logging interceptors. `Datalayer` takes
  `(alias, mikro)` and is *not* a token consumer — it authenticates transitively through Mikro's
  client, issuing fresh S3 session credentials per request via mikro mutations
  (`RequestZarrUpload`/`FinishZarrUpload`/`RequestZarrAccess`) and building an `S3Store` for
  Zarr. All bundled into the `App` god-object passed to every action.
- **Upload (ImageJ -> Zarr)**: `imgPlusToCTZYXUcarArray` flattens an ImageJ `ImgPlus` into a
  fixed **c,t,z,y,x** `ucar.ma2.Array` (preserving the source dtype); `uploadArray` writes it as a
  Blosc-compressed Zarr array to S3, then registers it with `CreateArrayDatasetMutation`, whose
  `axes` argument *states* that c,t,z,y,x order with a semantic type per axis. The writer is the one
  place a fixed order survives, and `generateChunkShape` hard-requires rank 5 — generalising the
  writer to arbitrary axes is a separate change.
- **Download (Lens -> ImageJ)**: see the Lens section below.
- **Action handlers**: `runX` (upload active image), `showLens` (display a lens), `showDataset`
  (display a whole dataset), `annotateLens` (display a lens and save drawn ROIs — see below) and
  `runImageToImageMacro`. Each has signature
  `suspend (App, Map<String, JsonElement?>) -> Map<String, JsonElement?>`; their typed port
  definitions live in `Actions.kt` (`buildFunctionRegistry`).

**Reading a Lens (mikro v2)** — `LensView.kt` + `loadLensView`/`buildDataset` in `Arkitekt.kt`

A **`Lens`** is mikro's *"selection over a dataset, nothing else"*: a list of per-axis
`Slice {axis, start, stop, step}` over an `ArrayDataset`. It is what a `Layer` renders, and it is
what `showLens` displays. `showDataset` is the unsliced sibling and goes down the same path.

**The axis rules, which is what makes this more than a rename:**
- **There is no canonical axis order.** `Axis.order` *is* the store's dimension order; `(z,c,y,x)`
  is as legal as `(c,t,z,y,x)` (the server deleted `assert_axis_type_order`). An axis is
  `(order, name, type, unit)` with `type` one of
  `SPACE/TIME/CHANNEL/COORDINATE/DISPLACEMENT/MICROTIME/SPECTRUM/INDEX`.
- **Which axis is screen x/y/z/time/channel is inferred from the axis names AND types together**,
  by `resolveRenderAxes` in `LensView.kt`. **`Lens.renderAxes` is deprecated and deliberately not
  queried** (its SDL description is stale too — it claims a purely positional derivation).
  The rule, and both halves are load-bearing:
  - **Type decides candidacy**: which axes are spatial at all, and which one is time/channel. An
    axis typed SPACE but named `t` is a spatial axis, and is not also claimed as the time axis.
  - **Name decides which spatial axis is which**: when the spatial set is exactly `{x,y}` or
    `{x,y,z}` (aliases `width`/`height`/`depth` allowed), each binds to the axis it is called.
    Position alone cannot do this — `(z,y,x)` and `(x,y,z)` are both well-formed and only one is
    meant, so reading positionally transposes the second silently.
  - Otherwise **wholly positional** (last spatial = x, second-to-last = y, third-to-last = z).
    All-or-nothing: binding the recognised names in a set like `(x,y,q)` and leaving `q`
    positional would let `q` and `x` both claim x.
  - Time and channel are found **by type first, by name (`t`/`time`/`frame`, `c`/`channel`/`ch`)
    only as a fallback**, so a properly typed store is never second-guessed and a sloppy one still
    works.
- **A selection never drops or reorders an axis**, so a lens' axis list is its dataset's; only the
  extents differ. `Lens.shape` is what the slices cut out, and `buildLensView` **cross-checks its
  derived shape against it and throws on mismatch** — the alternative is reading the wrong pixels
  and displaying them as if they were right.
- Slice bounds follow Python's `slice(...).indices(size)` (negatives, clamping) because the server
  does. A **negative step is refused**: zarr can only read forward.
- **The pixels are only in `dataset.dataArrays[level == 0].store`** — pick level 0 explicitly, the
  list order is not guaranteed. `CoordinateAnchor` has no store in v2 (it is a metadata hub).

**The read**: `read(offset, extent)` pulls the `[start, stop)` box (zarr-java has no stride
overload), then `applyStride` subsamples with **`sectionNoReduce`** — *not* `section`, which drops
length-1 dimensions, and a lens pinning a single channel or z-plane is the most ordinary lens there
is; losing its rank would make the permutation address the wrong axes.

**The display**: `buildDataset` permutes into ImageJ's x,y,z,c,t (`imageJDimOrder`) before zipping
the ucar `IndexIterator` against the ImgLib2 `Cursor` — ImgLib2 iterates dim 0 fastest, ucar its
*last* dim fastest, so the ucar array is permuted to the reverse of the ImageJ order. Skipping this
is what silently transposed the two slowest axes whenever an image had both channels > 1 and
time > 1. Every dimension is then labelled by name (`imageJAxisTypeFor`); an axis the renderer does
not name — MICROTIME, SPECTRUM, INDEX — becomes a custom `Axes.get(name)` rather than an error.

**Writing ROIs back (mikro v2)** — `RoiAnnotation.kt` + `RoiSources.kt` + `annotateLens`

`annotate_lens` opens a lens and saves every ROI the user draws into a fresh `AnnotationCollection`
as it is drawn. Four things decide the design, and each is a way to be silently wrong:

- **"Live" is a side effect, not a stream.** This agent client emits exactly **one** `YIELD` per
  `ASSIGN` (`Agent.kt`: `func(app, args)` -> one `Yield` -> `Completed`, and `outbound` is local to
  `connectOnce`), so `ActionKind.GENERATOR` is unusable here even though it exists in the rekuest
  schema. And mikro has **no annotation subscription** (`core/subscriptions/` is just `files.py`), so
  a viewer sees the shapes on its next query rather than pushed. Each shape is saved the moment it
  appears; the handler runs long and returns the collection id at the end.
- **The collection is placed by an `IDENTITY` edge onto the LENS.** `createAnnotationCollection`
  requires `axes` (it owns its coordinate system), and `derivedFrom` is what places it --
  WARNING: **omit the `transform` and the edge is `UNMAPPABLE`**: lineage only, no geometry, every
  mutation still succeeds. Naming the *lens* rather than the dataset means the lens' own `toParent`
  edge carries any crop for free. The alternative sugar is `createAnnotation(scene:)`, which mints
  collection + system + registration + **layer**; this path deliberately does not, so the shapes are
  placed and queryable but not composited into a scene until someone calls `createAnnotationLayer`.
- **`vectors` are positional in the COLLECTION's declared axis order -- every axis, including
  non-spatial ones.** So the collection's axes are declared as the lens' axes, and each vertex is
  emitted full-width: the `renderAxes` x/y axes take the drawn coordinates, every other axis takes
  its current slice index. A bare `[x, y]` on a `(c,t,z,y,x)` lens would be stored against the
  channel and time axes. (`ThreeDVector` is a pass-through `list` scalar that enforces nothing;
  `assert_shape_vectors` only checks equal widths and a per-kind minimum.)
- **No half-voxel shift and no array->vertex permutation.** The server applies +/-0.5 itself in
  `vectors_bbox`, purely to derive `intrinsicBbox` -- stored vectors are untouched. And
  `array_to_vertex_order`, despite being documented as "THE permutation", has no caller on the
  annotation path. The one real adjustment is an **inclusive far corner**: IJ1's width/height are
  counts, mikro's two-corner kinds hold voxel indices, so `x=10,w=5` -> corners `10` and `14`.

**Reading the drawing surface is polled, not evented** (`RoiSources.kt`), because there is no single
place drawn shapes live: under `./gradlew run` the ImageJ2 Swing UI makes a `net.imagej.overlay.Overlay`
and `OverlayCreatedEvent` fires, while inside Fiji the IJ1 legacy UI makes an `ij.gui.Roi` and no such
event fires. Both are read every 500 ms and keyed by **object identity** (ImageJ returns the same
instance each poll, so an edit is `updateAnnotation` and a new drawing is `createAnnotation`; a
geometry key would make every edit a duplicate). The IJ1 side reads the **ROI Manager only**, not the
live `imp.roi` -- IJ1 replaces that object mid-drag, so polling it would write an annotation per drag
state, and `t` is the standard Fiji gesture for "this one is finished". Events were rejected for a
third reason too: SciJava holds subscribers **weakly**, so a collected handler stops firing silently.

Three things the poll has to defend against, none of which errors when got wrong:
- **The ROI Manager is a global singleton that outlives a run**, so the session snapshots what is
  already drawn (`drawnBaseline`) and excludes it — otherwise a second `annotate_lens` re-saves the
  first session's shapes into its new collection. It also holds ROIs for *other* open images, so a
  ROI naming an image other than the current one is skipped.
- **A banked IJ1 ROI remembers the slice it was drawn on** (`roi.cPosition`/`zPosition`/`tPosition`,
  **1-based, 0 = unset**), and the viewer has usually moved on since — so the ROI's own answer wins
  over the display's, via `ij1Pins`. Reading them naively pins every shape one slice too high.
- **A failed mutation must not end the session.** Each shape's write is wrapped, since this action is
  meant to run for as long as someone is drawing and one transient error would otherwise propagate
  out of the handler and be reported `CRITICAL`. Likewise the close check only applies *after* a
  first successful poll, so a display that has not registered yet cannot end the session instantly
  and report success having saved nothing.

**Agent / remote-invocation runtime** — `Agent.kt` + `AgentProtocol.kt`
- `FunctionRegistry` maps an `interface` name → (handler fn, `DefinitionInput`). Handlers are
  registered in `alogin` with their typed Arkitekt port definitions (args/returns).
- `Agent.createAgent(name)` calls `ensureAgent(AgentInput{name})`, then `registerFunctions()`
  advertises all implementations in one `implementAgent(ImplementAgentInput{implementations})` call
  (the rekuest-next agent API — there is no `instanceId`/`setExtensionImplementations` anymore).
- `Agent.provideForever()` is a **reconnect loop** around `connectOnce()`, which opens a Ktor
  **WebSocket** to `…/agi` and sends `REGISTER{token, force, session_id}` as its first frame (the
  token rides *in* the frame, never in the URL, and is pulled fresh per attempt). The per-process
  `sessionId` is minted **once, outside the loop** — this is load-bearing, not cosmetic: the
  backend reads `session_id` as "is this the same process?", so presenting the same one lets a
  reconnect reclaim its in-flight tasks, while a fresh one makes the server fail-and-cascade them.
  The connection then loops (tasks are tracked in a `ConcurrentHashMap<task, Job>`):
  - `INIT` — the server's acknowledgement, and the only proof the connection was accepted. On the
    first one we send `SESSION_INIT{session_id, states:{}}`, which opens the server's `Session`
    row; it announces the *process*, so it is never repeated on a reconnect. We then answer
    `INIT.inquiries` — the tasks the server still has open for us — with a `CRITICAL` each
    (`reportsForInquiries`). There is no inquiry-reply message type; silence would leave those
    tasks in flight until a server-side sweep. Every job is cancelled when a connection drops, so
    "it died" is always the honest answer here.
  - `HEARTBEAT` → `HEARTBEAT_ANSWER`, sent **on the session directly, never through the outbound
    channel**. The server pings every 10s and closes 3001 if the answer takes >5s, and that answer
    also renews our write-lease — so liveness must not queue behind a large `YIELD` (or behind a
    full channel, which would block the receive loop itself).
  - `ASSIGN` → look up the handler by `interface` name, run it on `Dispatchers.IO`, emit `STARTED`
    → `YIELD` (the return map) → `COMPLETED`, or `CRITICAL` on exception. **A repeat of a `task`
    already running is dropped**: delivery is at-least-once (the backend queue does
    pop → send → ack), and the server dedups the resulting *report* but not the side effect.
  - `CANCEL`/`INTERRUPT` → cancel the running job, emit `CANCELLED`/`INTERRUPTED`; `PAUSE`/`RESUME`
    → ack-only `PAUSED`/`RESUMED`; `KICK` → stop for good; `BOUNCE` → reconnect (honouring its
    `duration` hint); `PROTOCOL_ERROR` → kept as the connection's failure reason.
- **What we do on a close is decided by the server's close code**, not by guesswork
  (`classifyClose` / `AgentCloseCodes`): 3001 (heartbeat timeout) reconnects; 3002/3003/3004 and
  4003 (blocked) stop; 4004 (a live incumbent holds the agent) waits ~35s for its lease to go
  stale and retries once; **4005 (displaced) stops** — reconnecting would displace the incumbent
  right back and turn two instances into a mutual-eviction loop. A transport drop that carried no
  code at all always reconnects (bounded by the retry budget); "did `INIT` arrive" only colours the
  log message there, it does not change the decision.
  ⚠️ The numbers come from the **server** (`facade/codes.py` in the rekuest deployment), *not*
  from rekuest-next: that client's table predates 4004/4005 and still reads 3001 as "kicked",
  which would stop an agent for good over a mere heartbeat timeout. Take its *structure*
  (fatal vs. correctable), not its constants.
  Backoff mirrors rekuest-next's `ConnectionPolicy`: 1s doubling to a 60s cap with ±10% jitter,
  5 retries, and the budget is refunded by connection **duration** (30s of uptime), not by merely
  connecting — otherwise the cap means nothing against a link that connects and instantly drops.
- Wire messages are `kotlinx.serialization` sealed classes in `AgentProtocol.kt` (`AgentMessage`
  inbound, `AgentEvent` outbound), discriminated by `type` (`agentJson`, `classDiscriminator="type"`).
  **`task` is a UUID string** (v2 renamed `assignation` → `task`, `DONE` → `COMPLETED` and
  `ERROR` → `FAILED`) and every message carries an `id`; outbound events are serialized to
  `Frame.Text` explicitly (no Ktor content-converter). **The handler return map is serialized
  straight back as the YIELD payload**, so handler return keys must match the registered `returns`
  keys.
  Two traps encoded there: `REGISTER` is the **only** message the server declares `extra="forbid"`
  on, so a stray field closes the socket instead of being ignored (this is how the retired `mode`
  field broke the agent) — hence `AgentProtocolTest` asserts its *exact* key set. And
  `APP_CANCELLED` appears in the server's enum but has no message class and is not in the union:
  sending it is a protocol error, so app-side cancellation is reported as `CANCELLED`.
  `seq`/`EVENT_ACK` (the at-least-once report contract) are deliberately not implemented — the
  backend dedups terminal reports by task id anyway, and answering `INIT.inquiries` achieves what
  rekuest-next's unacked-report replay is for.

### Adding a new remote action
1. Write a `suspend (App, Map<String, JsonElement?>) -> Map<String, JsonElement?>` handler
   (usually in `Arkitekt.kt`, alongside `runX`/`showLens`).
2. In `Actions.kt`'s `buildFunctionRegistry`,
   `registry.register_function(<interface name>, DefinitionInput(...), arkitekt::yourFn)`.
   `DefinitionInput` requires `key`, `version`, `name`, `kind`; args are `ArgPortInput`, returns are
   `ReturnPortInput` (separate types).
3. Rebuild — the implementation is auto-advertised on next login; the server can then `ASSIGN` it.

## Conventions & gotchas
- Coroutines are used throughout; long-running work is launched on `Dispatchers.Default`/`IO`,
  UI callbacks marshalled back to `Dispatchers.Main` (Swing) via `kotlinx-coroutines-swing`.
- Two HTTP stacks coexist: **OkHttp** (raw Fakts/OAuth calls) and **Apollo/Ktor** (GraphQL + WS).
- **Axis order is hard-coded c,t,z,y,x on the WRITE path only.** The read path is axis-driven
  (see the Lens section) — mikro v2 has no canonical axis order, so anything that indexes axes by
  position reads the wrong pixels and shows them as if they were right. Both paths preserve the
  source dtype; they do not cast to UINT32.
- Errors are mostly surfaced via `println` and a Logger, not structured logging.
- The agent name is hard-coded to `"my_agent"` in `alogin`.
- `requested_client_kind` is sent as `"desktop"`. The server also accepts
  `requested_client_role` (`interface`|`agent`, default `interface`) — we send nothing, so we
  register as an `interface` even though this plugin *is* an agent. Worth confirming with the
  backend.
