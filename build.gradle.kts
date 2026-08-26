import java.util.zip.ZipFile
plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("kapt") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("com.apollographql.apollo") version "4.0.0"
    id("maven-publish") // Add this line
    // Bundles the plugin + its dependencies into ONE jar with the third-party packages relocated,
    // so nothing we ship can collide with the copies Fiji already has in its jars/ dir. See the
    // relocation block further down. `com.gradleup.shadow` is the maintained fork -- the old
    // `com.github.johnrengelman.shadow` id is unmaintained and breaks on Gradle 8.10.
    id("com.gradleup.shadow") version "8.3.5"
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
    // SciJava/ImageJ artifacts (net.imagej, net.imglib2, org.scijava, sc.fiji). Required, not
    // optional: the whole net.imagej group is absent from Maven Central (imagej, imagej-common and
    // imagej-legacy all 404 on repo1; only org.scijava:* is synced there).
    // The retired `maven.imagej.net` mirror used to be listed ahead of this one. It bought
    // nothing — both names resolve to the SAME host, 144.92.48.196 (UW-Madison) — and it cost a
    // CI run: a network *timeout* appears to abort the whole resolution rather than falling
    // through to the next repo the way a 404 does, so one stalled request to a repo that was
    // never going to serve the artifact failed the build. Don't add it back. Timeouts/retries for this host are
    // tuned in gradle.properties.
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

// Fiji's OWN copies of the libraries we also bundle, at the versions pom-scijava 36.0.0 pins
// (= what Fiji.app/jars actually contains). Used by `shadedSmokeTest` only, where it goes FIRST
// on the classpath to reproduce the situation this whole shading exercise exists for: Fiji's
// older jar winning over ours. Without it the smoke test would only prove the shaded jar is
// internally consistent, never that it survives contact with Fiji.
val fijiVintage by configurations.creating

dependencies {
    kapt("net.imagej:imagej:2.14.0")
    // compileOnly, for the same reason as imagej-legacy below: Fiji already ships the entire
    // ImageJ2/SciJava stack in its jars/ dir. As an `implementation` dep it put ~210 duplicate
    // jars into the plugin bundle — including ~70 MB of scripting-language engines (scala3,
    // jython, jruby, clojure, renjin) that arrive transitively via imagej-scripting and that this
    // plugin never touches. Compile against it; let Fiji provide it.
    // The version is the FIJI BASELINE, not "latest": Fiji.app/jars is exactly pom-scijava
    // 36.0.0 -- imagej 2.14.0, ij 1.54f, imglib2 6.1.0, scijava-common 2.94.2, imagej-legacy
    // 1.2.0. Compiling LOW and running HIGH is safe (a newer Fiji still has these methods);
    // the reverse is what throws NoSuchMethodError on someone else's install, which is what
    // compiling against 2.16.0 was doing. Bump this only to a version some target Fiji ships.
    //
    // Note: do NOT be tempted to `platform("org.scijava:pom-scijava:36.0.0")` instead. Its 662
    // managed entries include kotlin-stdlib -> ${kotlin.version} and kotlinx-coroutines -> 1.6.4,
    // which would drag this build back below Kotlin 2.1 / ktor 3. It is the source of truth for
    // version NUMBERS, not a platform to import.
    compileOnly("net.imagej:imagej:2.14.0")
    // Same artifact on separate configurations so `./gradlew run` / macroSmokeTest still work
    // standalone, and so the tests can boot a real ImageJ context, without any of it reaching
    // runtimeClasspath (which is what installToImageJ/buildPlugin bundle).
    imagejRuntime("net.imagej:imagej:2.14.0")
    testImplementation("net.imagej:imagej:2.14.0")
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

    // See the `fijiVintage` configuration above: these are Fiji's versions, not ours. Every one
    // of them is a package the shadowJar block relocates, so if a relocation is ever dropped the
    // shadedSmokeTest starts running against these instead of ours -- which is the point.
    fijiVintage("org.jetbrains.kotlin:kotlin-stdlib:1.8.22")
    fijiVintage("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.22")
    fijiVintage("com.google.guava:guava:31.1-jre")
    fijiVintage("com.google.code.gson:gson:2.10.1")
    fijiVintage("com.google.protobuf:protobuf-java:3.23.0")
    fijiVintage("com.fasterxml.jackson.core:jackson-databind:2.14.2")
    fijiVintage("com.squareup.okhttp3:okhttp:4.11.0")
    fijiVintage("com.squareup.okio:okio:3.3.0")
    fijiVintage("commons-io:commons-io:2.11.0")
    fijiVintage("org.apache.commons:commons-lang3:3.12.0")
    fijiVintage("org.apache.commons:commons-compress:1.23.0")
    fijiVintage("commons-codec:commons-codec:1.15")
    fijiVintage("org.slf4j:slf4j-api:1.7.36")
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


// --- Shading --------------------------------------------------------------
// Fiji loads jars/** and plugins/** into ONE flat classloader, so every library we ship that Fiji
// also ships is a coin flip decided by the launcher: kotlin-stdlib 2.1.0 vs its 1.8.22, guava
// 33.4.8 vs 31.1, jackson 2.20 vs 2.14.2, okio 3.9 vs 3.3, protobuf 4.31 vs 3.23, cdm-core 5.9.1
// vs 5.3.3. Whichever copy wins, someone breaks - us if Fiji's older class is loaded, Fiji if
// ours is. Relocating removes the question: our copies live under a package nothing else knows.
//
// The rule for this list is "relocate what Fiji ships" - see the table in Fiji.app/jars. Adding
// relocations for packages Fiji does NOT have (ktor, kotlinx, apollo, awssdk) buys nothing and
// costs risk, since some of them resolve resources by package path.
val shadedPrefix = "com.mycompany.arkitekt.shaded"

// The list is derived by comparing the PACKAGES in the shaded jar against the packages in every
// Fiji.app/jars jar - not the artifact names, which miss the case where one jar carries several
// packages. That is how `thredds` / `uk.ac.rdg.resc.edal` got here: they ship inside cdm-core
// next to `ucar`, so relocating only `ucar` left our 5.9.1 classes binding to Fiji's 5.3.3 ones.
// Re-run that comparison after any dependency change (see the note under `verifyShadedJar`).
val shadedPackages = listOf(
    // kotlin-stdlib 2.1.0 vs Fiji's 1.8.22 -- the single most dangerous collision here, since
    // coroutines/serialization/ktor are all compiled against the 2.x stdlib and would break
    // against 1.8.22. `kotlinx` is listed explicitly, but note the prefix match on `kotlin`
    // would catch it anyway and map it to the same target.
    "kotlin", "kotlinx",
    "com.google.common", "com.google.thirdparty",   // guava 33.4.8 vs 31.1
    "com.google.gson",                              // 2.11.0 vs 2.10.1
    "com.google.protobuf",                          // 4.31.1 vs 3.23.0
    "com.google.re2j",                              // 1.3 vs 1.7
    "com.fasterxml.jackson",                        // 2.20 vs 2.14.2
    "okhttp3", "okio",                              // 4.12.0/3.9.0 vs 4.11.0/3.3.0
    "org.apache.commons",                           // codec/compress/io/lang3/logging/math3
    "org.apache.http",                              // 4.5.13 vs 4.5.14
    "org.slf4j",                                    // 2.0.17 vs 1.7.36
    "ucar", "thredds", "uk.ac.rdg.resc.edal",       // cdm-core 5.9.1 vs 5.3.3, udunits vs 4.3.18
    "org.jdom2", "org.joda.time", "com.beust.jcommander", "picocli",
    // Annotation-only, so nothing would ever call a method on them - but they do collide
    // (jetbrains 23/26 vs 13.0, errorprone 2.36 vs 2.19), and relocating is free.
    "com.google.errorprone", "com.google.j2objc",
    "org.jetbrains.annotations", "org.intellij.lang.annotations",
)

tasks.shadowJar {
    // Distinct base name from the plain `jar` task (ArkitektCommand-<version>.jar), and no
    // classifier, so the bundled artifact reads arkitekt-plugin-<version>.jar.
    archiveBaseName.set("arkitekt-plugin")
    archiveClassifier.set("")

    // ktor engine containers, coroutines MainDispatcherFactory, aws execution interceptors: all
    // ServiceLoader-based. The transformer relocates the service file NAMES and CONTENTS too,
    // which is why it must stay paired with the relocations below.
    mergeServiceFiles()
    // A signed dependency's signature covers its original class names; after relocation the
    // signature no longer matches and the JVM rejects the whole jar.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")

    shadedPackages.forEach { relocate(it, "$shadedPrefix.$it") }

    // NOT relocated, deliberately -- these are the only packages left that Fiji also has:
    //  - com.github.luben.zstd (+ .util) and blosc-java: JNI. Native symbol names encode the Java
    //    package (Java_com_github_luben_zstd_...), so relocating the class breaks the native
    //    binding. zstd-jni is instead version-matched to Fiji's 1.5.5-10 in zarr-java.
    //  - javax.annotation (jsr305): annotation-only AND identical version (3.0.2) on both sides,
    //    so there is nothing to get wrong; left alone rather than rewriting a javax.* namespace.
    //  - io.ktor / com.apollographql / software.amazon.awssdk: Fiji ships none of them, so there
    //    is nothing to collide with. Their references INTO the relocated packages are still
    //    rewritten, which is all they need.
    //
    // One consequence worth knowing: relocating org.slf4j ships an slf4j API with no provider
    // bound, so slf4j output goes to NOP. Harmless here (this code logs via
    // org.scijava.log.LogService and println), but it is a real behaviour change.
}

// --- Plugin bundling ------------------------------------------------------
// One staging point, two consumers: `installToImageJ` drops it into a local Fiji, `buildPlugin`
// zips it for a GitHub Release. Since shadowJar collapsed ~102 loose jars into one, staging is
// now just that jar plus a marker file - and `installToImageJ` can finally clean up after itself
// (see its guard below).
//
// The ImageJ2/SciJava stack and imagej-legacy stay off `runtimeClasspath` (compileOnly + the
// imagejRuntime / ij1Runtime configurations) because Fiji provides them. shadowJar reads
// runtimeClasspath, so that split is what still keeps the ImageJ stack out of the bundle.
val pluginMarker by tasks.registering {
    description = "Generate the .arkitekt-plugin marker that identifies an install dir as ours"
    val marker = layout.buildDirectory.file("tmp/plugin-marker/.arkitekt-plugin")
    outputs.file(marker)
    doLast {
        val f = marker.get().asFile
        f.parentFile.mkdirs()
        f.writeText(
            "Managed by the arkitekt Gradle build (installToImageJ / buildPlugin).\n" +
            "Its presence marks this directory as ours: installToImageJ clears stale jars here,\n" +
            "and refuses to touch a directory that holds jars but no marker.\n"
        )
    }
}

val stagePlugin by tasks.registering(Sync::class) {
    description = "Stage the shaded plugin jar into build/plugins"

    from(tasks.shadowJar)
    from(pluginMarker)

    into(layout.buildDirectory.dir("plugins"))
}

// --- Shading verification -------------------------------------------------
// `./gradlew run` and the JUnit tests both exercise the UNSHADED classpath, so without these two
// tasks a broken relocation would first surface inside someone's Fiji. Both are headless and
// wired into `check`, so CI catches it instead.

// Structural check: nothing that should have moved is still at the top level, the relocated
// copies are actually there, and - easy to lose and silent when lost - the SciJava annotation
// index survived the merge. That index is the only reason "Plugins > Arkitekt" appears at all.
val verifyShadedJar by tasks.registering {
    group = "verification"
    // This checks the packages we KNOW about. To re-derive the list after a dependency change,
    // diff the shaded jar's packages against every jar in Fiji.app/jars and look for overlap -
    // that comparison is what found `thredds`, which artifact-name matching had missed.
    description = "Assert the shaded jar relocated what Fiji ships and kept the SciJava index"
    dependsOn(tasks.shadowJar)

    doLast {
        val jar = tasks.shadowJar.get().archiveFile.get().asFile
        ZipFile(jar).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()

            // Derived from the relocation list itself, so dropping a relocate() entry fails here
            // rather than silently shipping that package unrelocated.
            val leaked = shadedPackages.map { it.replace('.', '/') + "/" }.filter { pkg ->
                names.any { it.startsWith(pkg) && it.endsWith(".class") }
            }
            check(leaked.isEmpty()) {
                "${jar.name} still ships unrelocated $leaked - those collide with Fiji.app/jars"
            }

            val prefix = shadedPrefix.replace('.', '/') + "/"
            listOf("kotlin/", "com/google/common/", "com/fasterxml/jackson/", "ucar/").forEach { pkg ->
                check(names.any { it.startsWith(prefix + pkg) && it.endsWith(".class") }) {
                    "${jar.name} has no relocated $pkg under $prefix - is the relocation still declared?"
                }
            }

            check(names.contains("com/mycompany/arkitekt/ArkitektCommand.class")) {
                "${jar.name} does not contain the plugin class itself"
            }
            val indexPath = "META-INF/json/org.scijava.plugin.Plugin"
            val index = zip.getEntry(indexPath)
                ?: error("${jar.name} lost $indexPath - Fiji would show no menu entry")
            val text = zip.getInputStream(index).readBytes().toString(Charsets.UTF_8)
            check("com.mycompany.arkitekt.ArkitektCommand" in text) {
                "$indexPath does not name ArkitektCommand:\n$text"
            }
        }
        logger.lifecycle("verifyShadedJar: ${jar.name} OK")
    }
}

// Behavioural check, and the one that matters: run the existing headless smoke main out of the
// SHADED jar with Fiji's OWN library versions ahead of it on the classpath. That is the exact
// situation shading exists for - Fiji's kotlin-stdlib 1.8.22 / guava 31.1 / jackson 2.14.2
// winning the lookup. Unrelocated, this fails; relocated, our copies are unreachable to it and
// it passes. (Order matters: fijiVintage FIRST.)
tasks.register<JavaExec>("shadedSmokeTest") {
    group = "verification"
    description = "Run the headless smoke test from the shaded jar, behind Fiji's own library versions"
    dependsOn(tasks.shadowJar)

    classpath = files(
        configurations["fijiVintage"],
        tasks.shadowJar.map { it.archiveFile },
        configurations["imagejRuntime"],
        configurations["ij1Runtime"],
    )
    mainClass.set("com.mycompany.arkitekt.MacroSmokeTestKt")
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED", "-Djava.awt.headless=true")
}

tasks.named("check") {
    dependsOn(verifyShadedJar, tasks.named("shadedSmokeTest"))
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
// Still a Copy, not a Sync: this writes into a user-supplied directory, and a Sync would delete
// everything already there - a mistyped -PfijiDir pointing at Fiji.app or Fiji.app/plugins would
// wipe the install.
//
// But a plain Copy accumulated: because the bundle used to be ~102 loose jars, every dependency
// change left its predecessor behind, and the author's install had grown to 329 files holding
// cdm-core 5.5.3 AND 5.9.1, guava 30.1 AND 33.4.8, okhttp 2.7.5 + 4.11 + 4.12, plus netty/jnr
// jars from dependency sets that no longer exist. All of them still on Fiji's classpath. So the
// jars are cleared first - but ONLY from a directory carrying our marker file, which is what
// keeps a mistyped path safe.
tasks.register<Copy>("installToImageJ") {
    group = "distribution"
    description = "Install the shaded plugin jar into a local Fiji plugins directory"

    val target = file(findProperty("fijiDir") ?: "/home/jhnnsrs/Programs/fiji-linux64/Fiji.app/plugins/arkitekt")

    from(stagePlugin)
    into(target)

    doFirst {
        if (target.isDirectory) {
            val jars = target.listFiles { f: File -> f.isFile && f.name.endsWith(".jar") } ?: emptyArray()
            val marker = File(target, ".arkitekt-plugin")
            if (jars.isNotEmpty() && !marker.exists()) {
                throw GradleException(
                    "$target holds ${jars.size} jar(s) but no .arkitekt-plugin marker, so this build did " +
                    "not create it. Refusing to delete anything. If it IS an old arkitekt install " +
                    "(pre-shading installs left ~329 loose jars), remove it by hand first:\n" +
                    "  rm -rf $target"
                )
            }
            jars.forEach { it.delete() }
        }
    }
}
