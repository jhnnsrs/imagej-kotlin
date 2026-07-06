# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Kotlin **ImageJ2/Fiji plugin** (`Plugins > Arkitekt`) that bridges ImageJ to the
[Arkitekt](https://arkitekt.live) platform. It logs the user in, registers the running
ImageJ instance as a remote **agent**, and exposes ImageJ actions (upload the active
image, load an image back into the viewer) that the Arkitekt server can invoke remotely
over a WebSocket. Images are moved as Zarr arrays stored in S3.

> Note: `README.md` and `pom.xml` are **stale leftovers** from the `example-imagej2-command-kotlin`
> template — the project migrated to Gradle. Ignore Maven/`mvn` instructions in the README.
> The build is Gradle (`build.gradle.kts`).

## Build & run

```bash
./gradlew build                 # compile + generate Apollo GraphQL clients
./gradlew run                   # launches ImageJ with the plugin via ArkitektCommand.main()
                                #   (provided by the `application` plugin; mainClass = ...ArkitektCommandKt)
./gradlew installToImageJ       # copies plugin + runtime deps into a hard-coded local Fiji.app
                                #   (build/plugins -> /home/jhnnsrs/Programs/fiji-linux64/Fiji.app/plugins/arkitekt)
./gradlew buildPlugin           # bundles plugin + deps into build/arkitekt-plugin.zip for distribution
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

There are **no tests** in this repo. Bytecode target is pinned to **Java 8**
(`jvmTarget = "1.8"`, `sourceCompatibility/targetCompatibility = "1.8"`) for Fiji distribution,
even though the toolchain/run JVM is 17 — don't introduce APIs above Java 8 in source.

`installToImageJ` contains a hard-coded path to the author's Fiji install — adjust the
`doLast` block in `build.gradle.kts` before using it on another machine.

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
under a distinct package. **Generated code is what you import** (e.g. `com.mycompany.mikro.graphql.GetImageQuery`).

| Service   | Package                        | `.graphql` ops + schema location | Backend role |
|-----------|--------------------------------|----------------------------------|--------------|
| `lok`     | `com.mycompany.lok.graphql`    | `src/main/graphql/lok/`          | auth / "who am I" (`MeQuery`) |
| `mikro`   | `com.mycompany.mikro.graphql`  | `src/main/graphql/mikro/`        | image metadata + S3 upload/access grants |
| `rekuest` | `com.mycompany.rekuest.graphql`| `src/main/graphql/rekuest/`      | agent + action/implementation registration |

To add a query/mutation: drop a `.graphql` file in the right service dir and rebuild.
The `introspection {}` blocks point at `http://127.0.0.1/<service>/graphql` to refresh the
committed `schema.graphqls` (run `./gradlew downloadServiceApolloSchemaFromIntrospection`
with a live backend) — the schema files are committed, so codegen works offline.

## Architecture

The whole plugin lives in `src/main/kotlin/com/mycompany/arkitekt/` (4 files).

**Entry point** — `ArkitektCommand.kt`
- `@Plugin(menuPath = "Plugins > Arkitekt")` SciJava `Command`. SciJava injects services
  (`UIService`, `DatasetService`, `ImageDisplayService`, `Context`) via `@Parameter`.
- `run()` opens a Swing `Dialog` with an editable **Server** field (defaults to
  `DEFAULT_SERVER` = `https://go.arkitekt.live`) and a Login button → `Arkitekt.login(url, cb)`.
- `main()` boots a standalone ImageJ for IDE debugging.

**Orchestration** — `Arkitekt.kt` (the bulk of the code)
- **Auth: the fakts-next protocol** (`getActiveFakts` → `alogin`): a three-stage negotiation
  against a coordination server — `discover` (`GET {url}/.well-known/fakts` → `FaktsEndpoint`,
  yielding `base_url` + `configure_url`) → `demand` (device code: `POST {base}start/`, open the
  browser at `{configure_url}{code}`, poll `POST {base}challenge/` every 1s for
  granted/denied/error) → `claim` (`POST {base}claim/` with `{token, secure}` → `ActiveFakts`).
  Status envelopes are `{status, …}`. The full `ActiveFakts` is cached to
  `~/.arkitekt/fakts_cache.json` keyed by `sha256(manifest + url)` (`FaktsCache`); a changed
  manifest/url invalidates it, and a stale cache (aliases stop answering) triggers exactly one
  re-negotiation (self-heal). `logout()` clears the cache.
- **ActiveFakts shape**: `self` (deployment, whose `self.alias` **is** the lok endpoint —
  wired into `Unlok`, no longer a requirement), `auth` (OAuth2 client creds + `token_url`),
  `instances` keyed by requirement key (`rekuest`/`mikro`/`datalayer`), and `statuses`
  (`GrantStatus` granted/denied/unavailable/unknown). `getFirstReachableAlias` GET-probes each
  alias's challenge URL (`alias.to_http_path(alias.challenge)`) to pick a live endpoint.
  Required services must be `GRANTED` and resolvable or `alogin` throws `CompositionError`.
- **Service clients**: `Unlok` (lok), `Mikro`, `Rekuest` each wrap an `ApolloClient` with an
  `AuthorizationInterceptor` (Bearer token) + logging interceptors. `Datalayer` issues
  S3 credentials via mikro mutations (`RequestUpload`/`RequestAccess`) and builds an
  `S3Store` for Zarr. All bundled into the `App` god-object passed to every action.
- **Image <-> Zarr conversion**: `imgPlusToCTZXYUInt32UcarArray` flattens an ImageJ `ImgPlus`
  into a fixed **c,t,z,y,x** `ucar.ma2.Array` (currently force-casts everything to UINT32);
  `uploadArray` writes it as a Blosc-compressed Zarr array to S3 then registers it via
  `FromArrayLikeMutation`. `loadArrayAsDataset` does the reverse, dispatching on Zarr dtype
  to the right `ArrayImgs` factory and creating a `Dataset`.
- **Action handlers**: `runX` (upload active image) and `loadImage` (download + display).
  Each has signature `suspend (App, Map<String, JsonElement?>) -> Map<String, JsonElement?>`.

**Agent / remote-invocation runtime** — `Agent.kt` + `AgentProtocol.kt`
- `FunctionRegistry` maps an `interface` name → (handler fn, `DefinitionInput`). Handlers are
  registered in `alogin` with their typed Arkitekt port definitions (args/returns).
- `Agent.createAgent(name)` calls `ensureAgent(AgentInput{name})`, then `registerFunctions()`
  advertises all implementations in one `implementAgent(ImplementAgentInput{implementations})` call
  (the rekuest-next agent API — there is no `instanceId`/`setExtensionImplementations` anymore).
- `Agent.provideForever()` opens a Ktor **WebSocket** to `…/agi`, sends `REGISTER{token, force}`, then
  loops (assignations are tracked in a `ConcurrentHashMap<assignation, Job>` so they can be cancelled):
  - `HEARTBEAT` → `HEARTBEAT_ANSWER`
  - `ASSIGN` → look up the handler by `interface` name, run it on `Dispatchers.IO`, emit `YIELD`
    (the return map) + `DONE`, or `CRITICAL` on exception.
  - `CANCEL`/`INTERRUPT` → cancel the running job, emit `CANCELLED`/`INTERRUPTED`; `PAUSE`/`RESUME`
    → ack-only `PAUSED`/`RESUMED`; `KICK`/`BOUNCE` → close.
- Wire messages are `kotlinx.serialization` sealed classes in `AgentProtocol.kt` (`AgentMessage`
  inbound, `AgentEvent` outbound), discriminated by `type` (`agentJson`, `classDiscriminator="type"`).
  **`assignation` is a UUID string** and every message carries an `id`; outbound events are serialized
  to `Frame.Text` explicitly (no Ktor content-converter). **The handler return map is serialized
  straight back as the YIELD payload**, so handler return keys must match the registered `returns` keys.

### Adding a new remote action
1. Write a `suspend (App, Map<String, JsonElement?>) -> Map<String, JsonElement?>` handler
   (usually in `Arkitekt.kt`, alongside `runX`/`loadImage`).
2. In `alogin`, `registry.register_function(<interface name>, DefinitionInput(...), ::yourFn)`.
   `DefinitionInput` requires `key`, `version`, `name`, `kind`; args are `ArgPortInput`, returns are
   `ReturnPortInput` (separate types).
3. Rebuild — the implementation is auto-advertised on next login; the server can then `ASSIGN` it.

## Conventions & gotchas
- Coroutines are used throughout; long-running work is launched on `Dispatchers.Default`/`IO`,
  UI callbacks marshalled back to `Dispatchers.Main` (Swing) via `kotlinx-coroutines-swing`.
- Two HTTP stacks coexist: **OkHttp** (raw Fakts/OAuth calls) and **Apollo/Ktor** (GraphQL + WS).
- Axis order is hard-coded **c,t,z,y,x**; pixel conversion currently clamps/casts to UINT32
  regardless of source type — a known rough edge if working on image fidelity.
- Errors are mostly surfaced via `println` and a Logger, not structured logging.
- `instanceId` / extension name is hard-coded to `"default"` in several places.
