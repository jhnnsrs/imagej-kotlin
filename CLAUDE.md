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

Tests (`./gradlew test`, JUnit 5, 50 of them) cover the fakts token-rotation rule, expiry math and
flat-grant split (`FaktsTest.kt`, `TokenManagerTest.kt`); the agent wire format and close-code
policy (`AgentProtocolTest.kt`, `CloseCodeTest.kt`); and the agent's whole connection lifecycle
(`AgentLifecycleTest.kt`) driven against `FakeGateway.kt`, a local Ktor stand-in for the `/agi`
gateway. The fake exists because the live gateway currently refuses our registration for a
server-side reason (see the org-claim note below) — without it, everything past REGISTER would be
verified only by reading, and that is exactly where a mishandled close hangs the agent or starts an
eviction war. The tests boot a headless ImageJ context (~250ms) to build a real `App`.
The fakts device-code flow still needs a live coordination server and is verified by hand. Bytecode target is pinned to **Java 8**
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

The whole plugin lives in `src/main/kotlin/com/mycompany/arkitekt/`:
`Fakts.kt` (protocol + models + cache), `TokenManager.kt`, `Auth.kt` (Apollo interceptors),
`Arkitekt.kt` (orchestration, image/Zarr, action handlers), `Actions.kt` (port definitions),
`Agent.kt` + `AgentProtocol.kt`, `ArkitektCommand.kt`, `ArkitektTool.kt`, `ArkitektState.kt`,
`NodeId.kt`, `MacroSmokeTest.kt`.

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
- **Image <-> Zarr conversion**: `imgPlusToCTZYXUcarArray` flattens an ImageJ `ImgPlus`
  into a fixed **c,t,z,y,x** `ucar.ma2.Array` (preserving the source dtype);
  `uploadArray` writes it as a Blosc-compressed Zarr array to S3 then registers it via
  `FromArrayLikeMutation`. `loadArrayAsDataset` does the reverse, dispatching on Zarr dtype
  to the right `ArrayImgs` factory and creating a `Dataset`.
- **Action handlers**: `runX` (upload active image), `loadImage` (download + display) and
  `runImageToImageMacro`. Each has signature
  `suspend (App, Map<String, JsonElement?>) -> Map<String, JsonElement?>`; their typed port
  definitions live in `Actions.kt` (`buildFunctionRegistry`).

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
   (usually in `Arkitekt.kt`, alongside `runX`/`loadImage`).
2. In `Actions.kt`'s `buildFunctionRegistry`,
   `registry.register_function(<interface name>, DefinitionInput(...), arkitekt::yourFn)`.
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
- The agent name is hard-coded to `"my_agent"` in `alogin`.
- `requested_client_kind` is sent as `"desktop"`. The server also accepts
  `requested_client_role` (`interface`|`agent`, default `interface`) — we send nothing, so we
  register as an `interface` even though this plugin *is* an agent. Worth confirming with the
  backend.
