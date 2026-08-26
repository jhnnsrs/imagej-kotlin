
plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("kapt") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("com.apollographql.apollo") version "4.0.0"
    id("maven-publish") // Add this line
    application
}

application {
    // Entry point: top-level main() in ArkitektCommand.kt -> compiled to ...ArkitektCommandKt
    mainClass.set("com.mycompany.arkitekt.ArkitektCommandKt")
}

// Put the IJ1 legacy layer on the classpath for `./gradlew run` so the "Run Image-To-Image Macro"
// action works standalone. This deliberately targets only the `run` task, not runtimeClasspath,
// so the plugin bundle stays free of a second IJ1 copy (which Fiji already provides).
//
// imagej-legacy needs ij1-patcher to inject ij.IJ's `_hooks` field before ImageJ2 boots
// LegacyService (else: "No _hooks field found in ij.IJ"). main() calls LegacyInjector.preinit()
// first (see ArkitektCommand.kt) to do this in-process. But on JDK 9+ the patcher reflects into
// java.lang.ClassLoader — findLoadedClass (to see if ij.IJ is already loaded) AND defineClass (to
// install the patched ij.IJ) — both via setAccessible(true). Without opening java.base/java.lang,
// those InaccessibleObjectException-fail silently, the patcher falls back to loading ij.IJ
// UNPATCHED, and boot dies. This add-opens is what makes standalone preinit work.
tasks.named<JavaExec>("run") {
    classpath += configurations["imagejRuntime"] + configurations["ij1Runtime"]
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}

// Headless verification of the standalone macro path (see MacroSmokeTest.kt). Same classpath and
// add-opens as `run`, but runs to completion (no UI) and exits non-zero on failure.
tasks.register<JavaExec>("macroSmokeTest") {
    group = "verification"
    description = "Headless smoke test of the IJ1-legacy Dataset<->ImagePlus + runMacro path"
    classpath = sourceSets["main"].runtimeClasspath +
        configurations["imagejRuntime"] + configurations["ij1Runtime"]
    mainClass.set("com.mycompany.arkitekt.MacroSmokeTestKt")
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED", "-Djava.awt.headless=true")
}

group = "com.mycompany"
// Release version comes from the git tag via `-PpluginVersion=` (see
// .github/workflows/release.yml); plain local builds keep the SNAPSHOT. Must stay ABOVE the
// buildPlugin registration below, whose archiveFileName interpolates $version at
// configuration time.
version = (findProperty("pluginVersion") as String?) ?: "0.1.0-SNAPSHOT"

description = "Arkitekt Command"



repositories {
    mavenLocal()
    mavenCentral()
    maven("https://maven.imagej.net/content/groups/public")
    maven("https://repo.maven.apache.org/maven2")
    maven("https://maven.scijava.org/content/groups/public")
}

// The AWS SDK ships two HTTP implementations: apache-client (sync) and netty-nio-client (async).
// Only the sync S3Client is ever built (Arkitekt.kt's Datalayer, and zarr-java's S3Store), so the
// Netty stack is ~4 MB of dead weight in the plugin bundle. Excluded globally rather than per
// dependency because it arrives both directly and transitively via the :zarr-java subproject.
// If an S3AsyncClient is ever introduced, drop this exclude — it fails at runtime, not compile.
configurations.all {
    exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
}

// IJ1 legacy layer for `./gradlew run` only — kept off runtimeClasspath so it is not bundled
// into the Fiji plugin (see the ij1Runtime dependency note below).
val ij1Runtime by configurations.creating

// The ImageJ2/SciJava stack, likewise for `./gradlew run` and macroSmokeTest only. Fiji provides
// all of it at runtime, so keeping it off runtimeClasspath keeps it out of the plugin bundle.
val imagejRuntime by configurations.creating

dependencies {
    kapt("net.imagej:imagej:2.16.0")
    // compileOnly, for the same reason as imagej-legacy below: Fiji already ships the entire
    // ImageJ2/SciJava stack in its jars/ dir. As an `implementation` dep it put ~210 duplicate
    // jars into the plugin bundle — including ~70 MB of scripting-language engines (scala3,
    // jython, jruby, clojure, renjin) that arrive transitively via imagej-scripting and that this
    // plugin never touches. Compile against it; let Fiji provide it.
    compileOnly("net.imagej:imagej:2.16.0")
    // Same artifact on separate configurations so `./gradlew run` / macroSmokeTest still work
    // standalone, and so the tests can boot a real ImageJ context, without any of it reaching
    // runtimeClasspath (which is what installToImageJ/buildPlugin bundle).
    imagejRuntime("net.imagej:imagej:2.16.0")
    testImplementation("net.imagej:imagej:2.16.0")
    // IJ1 legacy layer — provides `ij.IJ.runMacro` (the ImageJ macro engine) and the
    // Dataset<->ImagePlus converters used by the "Run Image-To-Image Macro" action.
    // compileOnly on purpose: Fiji already ships ij + imagej-legacy in its jars/ dir, and
    // bundling a second copy would double-load IJ1's singletons (ij.IJ / WindowManager) and
    // break the plugin. So we compile against it but let Fiji provide it at runtime. (Version
    // matches Fiji's imagej-legacy-1.2.0.jar; the transitive `net.imagej:ij` comes with it.)
    compileOnly("net.imagej:imagej-legacy:1.2.0")
    // Same artifact, runtime-only, but on a SEPARATE configuration (see `ij1Runtime` below) so it
    // is on the classpath for `./gradlew run` (lets the macro action work standalone) WITHOUT
    // leaking into runtimeClasspath — which installToImageJ/buildPlugin bundle into Fiji, where a
    // second IJ1 copy would double-load ij.IJ/WindowManager singletons and break the plugin.
    ij1Runtime("net.imagej:imagej-legacy:1.2.0")
    // Vendored in-repo Gradle subproject (see settings.gradle.kts + zarr-java/build.gradle.kts).
    // Replaces the old mavenLocal coordinate "dev.zarr:zarr-java:0.0.5-SNAPSHOT" (`mvn install`).
    implementation(project(":zarr-java"))
    implementation("com.apollographql.apollo:apollo-runtime:4.0.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing")
    implementation(platform("software.amazon.awssdk:bom:2.32.8"))
    implementation("software.amazon.awssdk:s3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0-RC")
    implementation("io.ktor:ktor-client-core:3.0.2")
    implementation("io.ktor:ktor-client-cio:3.0.2")
    implementation("io.ktor:ktor-client-websockets:3.0.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Tests cover only the pure, I/O-free parts of the fakts layer: the token-rotation rule,
    // the expiry math, and splitting the flat grant response. The protocol itself needs a live
    // coordination server and is verified by hand.
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // A local fake of the rekuest agent gateway, so the connection lifecycle (INIT, SESSION_INIT,
    // inquiry reconciliation, heartbeat, KICK/BOUNCE, close codes) can be exercised for real. The
    // live server currently rejects our registration for an unrelated server-side reason, which
    // would otherwise leave all of that untested.
    testImplementation("io.ktor:ktor-server-core:3.0.2")
    testImplementation("io.ktor:ktor-server-netty:3.0.2")
    testImplementation("io.ktor:ktor-server-websockets:3.0.2")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    // Run/compile on a full JDK 17 (has AWT, so the ImageJ GUI shows; Gradle-8.10 compatible).
    // Bytecode target stays 1.8 (see jvmTarget / sourceCompatibility below) for Fiji distribution.
    jvmToolchain(17)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "1.8"

    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.encoding = "UTF-8"
}


apollo {
    // The backend serves each service's schema as plain-text SDL at
    // `<host>/<service>/schema` (NOT a GraphQL introspection endpoint), so the
    // committed schema.graphqls files in each srcDir are auto-detected for offline
    // codegen. To refresh them, run `./gradlew downloadSchemas` (see below) —
    // Apollo's `introspection {}` block can't be used because it POSTs an
    // introspection query and expects JSON, while these endpoints return SDL.
    service("lok") {
        packageName.set("com.mycompany.lok.graphql")
        srcDir("src/main/graphql/lok")
    }
    service("mikro") {
        packageName.set("com.mycompany.mikro.graphql")
        srcDir("src/main/graphql/mikro")
    }
    service("rekuest") {
        packageName.set("com.mycompany.rekuest.graphql")
        srcDir("src/main/graphql/rekuest")
    }
}

// --- SDL schema refresh ---------------------------------------------------
// Host of the Arkitekt backend that serves the SDL. Resolved from (in order):
//   1. -PschemaHost=... gradle property
//   2. ARKITEKT_SCHEMA_HOST env var (e.g. set in a local .env you `source`)
//   3. default: http://jhnnsrs-lab
val schemaHost: String = (findProperty("schemaHost") as String?)
    ?: System.getenv("ARKITEKT_SCHEMA_HOST")
    ?: "http://jhnnsrs-lab"

val schemaServices = mapOf(
    "Lok" to "src/main/graphql/lok/schema.graphqls",
    "Mikro" to "src/main/graphql/mikro/schema.graphqls",
    "Rekuest" to "src/main/graphql/rekuest/schema.graphqls",
)

val downloadSchemaTasks = schemaServices.map { (name, path) ->
    tasks.register("download${name}Schema") {
        group = "apollo"
        description = "Download $name SDL from $schemaHost into $path"
        doLast {
            val service = name.lowercase()
            val url = "$schemaHost/$service/schema"
            val target = file(path)
            logger.lifecycle("Downloading $service schema from $url")
            val sdl = uri(url).toURL().readText()
            require(sdl.isNotBlank() && !sdl.trimStart().startsWith("{")) {
                "Expected SDL from $url but got something else (empty or JSON):\n${sdl.take(200)}"
            }
            target.writeText(sdl)
            logger.lifecycle("Wrote ${sdl.length} chars to $path")
        }
    }
}

tasks.register("downloadSchemas") {
    group = "apollo"
    description = "Download all service SDL schemas from $schemaHost"
    dependsOn(downloadSchemaTasks)
}


// --- Plugin bundling ------------------------------------------------------
// One staging point, two consumers: `installToImageJ` drops it into a local Fiji, `buildPlugin`
// zips it for a GitHub Release. Sync (not Copy) on purpose - a dependency that leaves
// runtimeClasspath must also leave the bundle, or a stale jar ships forever.
//
// This bundles the whole `runtimeClasspath`, so whatever lands there ships. The ImageJ2/SciJava
// stack and imagej-legacy are deliberately kept off it (compileOnly + the imagejRuntime /
// ij1Runtime configurations) because Fiji provides them - that is what keeps this ~44 MB rather
// than ~180 MB. Adding an `implementation` dep that Fiji already ships undoes it.
val stagePlugin by tasks.registering(Sync::class) {
    description = "Stage the plugin jar + its runtime dependencies into build/plugins"

    from(configurations.runtimeClasspath) {
        include("**/*.jar")
        exclude("**/groovy*.jar")
    }
    from(tasks.named("jar"))

    into(layout.buildDirectory.dir("plugins"))
}

tasks.register<Zip>("buildPlugin") {
    group = "distribution"
    description = "Bundle the plugin + dependencies into a distributable zip"

    // Nest under `arkitekt/` so the zip unpacks straight into Fiji.app/plugins/ with no
    // intermediate mkdir - matching the layout installToImageJ has always produced.
    from(stagePlugin) { into("arkitekt") }

    archiveFileName.set("arkitekt-plugin-$version.zip")
    destinationDirectory.set(layout.buildDirectory)
}

// Local install. The Fiji path is the author's by default; override with
//   ./gradlew installToImageJ -PfijiDir=/path/to/Fiji.app/plugins/arkitekt
//
// Deliberately a Copy, not a Sync: this writes into a user-supplied directory, and a Sync would
// delete everything already there - a mistyped -PfijiDir pointing at Fiji.app or Fiji.app/plugins
// would wipe the install. Stale jars can accumulate here across dependency changes; `rm -rf` the
// target dir if that ever matters. The staging dir gets the Sync treatment instead.
tasks.register<Copy>("installToImageJ") {
    group = "distribution"
    description = "Copy the plugin + dependencies into a local Fiji plugins directory"

    from(stagePlugin)
    into(findProperty("fijiDir") ?: "/home/jhnnsrs/Programs/fiji-linux64/Fiji.app/plugins/arkitekt")
}
