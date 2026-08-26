# Arkitekt ImageJ plugin

An ImageJ2/Fiji plugin (**Plugins › Arkitekt**) that bridges ImageJ to the
[Arkitekt](https://arkitekt.live) platform. It logs you in, registers the running ImageJ
instance as a remote *agent*, and exposes ImageJ actions — upload the active image, load a
lens or dataset back into the viewer, run an image-to-image macro — that the Arkitekt server
can invoke remotely over a WebSocket. Images move as Zarr arrays stored in S3.

## Installing

1. Download `arkitekt-plugin-<version>.zip` from the
   [latest release](https://github.com/jhnnsrs/imagej-kotlin/releases/latest).
2. Unzip it into your Fiji installation's `plugins/` directory. The zip contains a single
   `arkitekt/` folder, so you should end up with `Fiji.app/plugins/arkitekt/`:

   ```bash
   unzip arkitekt-plugin-*.zip -d /path/to/Fiji.app/plugins/
   ```

3. Restart Fiji. The plugin appears under **Plugins › Arkitekt**.

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

CI (`.github/workflows/ci.yml`) builds, tests and bundles on every push and pull request; the
bundle is attached to each run as an artifact, so a change can be test-installed before merge.

Pushing a `v*` tag triggers `.github/workflows/release.yml`, which builds the bundle with the
version taken from the tag and publishes it as a GitHub Release:

```bash
git tag v0.1.0 && git push origin v0.1.0    # -> arkitekt-plugin-0.1.0.zip
```

## License

Simplified BSD — see [`LICENSE.txt`](LICENSE.txt).
