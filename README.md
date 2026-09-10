# Arkitekt ImageJ plugin

An ImageJ2/Fiji plugin (**Plugins › Arkitekt**) that bridges ImageJ to the
[Arkitekt](https://arkitekt.live) platform. It logs you in, registers the running ImageJ
instance as a remote *agent*, and exposes ImageJ actions — upload the active image, load a
lens or dataset back into the viewer, run an image-to-image macro — that the Arkitekt server
can invoke remotely over a WebSocket. Images move as Zarr arrays stored in S3.

## Installing

Grab the plugin from the [latest release](https://github.com/jhnnsrs/imagej-kotlin/releases/latest).
Each release carries the same plugin in two forms, plus a `SHA256SUMS.txt`:

- **Simplest:** download `arkitekt-plugin-<version>.jar` and drop it into your Fiji
  installation's `plugins/` folder. Delete any older `arkitekt-plugin-*.jar` first (two versions
  on the classpath means Fiji picks one at random).
- **Or** the zip, which unpacks to a single `arkitekt/` folder — the layout
  `./gradlew installToImageJ` manages:

  ```bash
  rm -rf /path/to/Fiji.app/plugins/arkitekt        # remove a previous install first
  unzip arkitekt-plugin-*.zip -d /path/to/Fiji.app/plugins/
  ```

Restart Fiji. The plugin appears under **Plugins › Arkitekt**.

Open it, enter your Arkitekt server (default `https://go.arkitekt.live`) and click **Login** —
this opens a browser for device-code approval. The login is cached in
`~/.arkitekt/fakts_cache.json`, so subsequent starts are silent. Delete that file to log out.

Fiji's bundled JRE is what this is tested against.

## Building from source

The build is **Gradle**, not Maven — a full, non-headless **JDK 17** is required. See
[`CLAUDE.md`](CLAUDE.md) for the full architecture notes and the JDK rationale.

```bash
./gradlew build            # compile + Apollo GraphQL codegen + tests
./gradlew buildPlugin      # -> build/arkitekt-plugin-<version>.zip
./gradlew installToImageJ -PfijiDir=/path/to/Fiji.app/plugins/arkitekt
./gradlew run              # launch ImageJ with the plugin, for debugging
```

A [`justfile`](justfile) wraps the common ones (`just build`, `just plugin`, `just install`).

## Releasing

CI (`.github/workflows/ci.yml`) builds, tests and bundles on every push and pull request. The
per-test results are published as a **Test results** check, and the bundle is attached to each
run as an artifact, so a change can be test-installed before merge.

Releases are automatic. After the build passes on `main`, a `release` job runs
[semantic-release](https://semantic-release.gitbook.io/) (config: `.releaserc.yml`): it reads the
[Conventional Commits](https://www.conventionalcommits.org/) since the last `v*` tag, derives the
next version, rebuilds the plugin with that version stamped in (`scripts/release-assets.sh`),
pushes the tag and publishes a GitHub Release with the jar, the zip, a `SHA256SUMS.txt` and
generated notes. So the commit message is the release decision:

| Commit message                                   | Release |
|--------------------------------------------------|---------|
| `fix: ...`                                       | patch   |
| `feat: ...`                                      | minor   |
| `feat!: ...` or a `BREAKING CHANGE:` footer      | major   |
| `docs:`, `chore:`, `ci:`, `refactor:`, `test:`   | none    |

Don't push `v*` tags by hand — semantic-release owns them. The first release will be `1.0.0`
unless an earlier `v0.x.y` tag exists for it to count from. Dependabot keeps the workflow's
actions and the semantic-release packages current (`.github/dependabot.yml`).

## License

Simplified BSD — see [`LICENSE.txt`](LICENSE.txt).
