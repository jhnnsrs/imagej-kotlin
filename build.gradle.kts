
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
    classpath += configurations["ij1Runtime"]
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}

// Headless verification of the standalone macro path (see MacroSmokeTest.kt). Same classpath and
// add-opens as `run`, but runs to completion (no UI) and exits non-zero on failure.
tasks.register<JavaExec>("macroSmokeTest") {
    group = "verification"
    description = "Headless smoke test of the IJ1-legacy Dataset<->ImagePlus + runMacro path"
    classpath = sourceSets["main"].runtimeClasspath + configurations["ij1Runtime"]
    mainClass.set("com.mycompany.arkitekt.MacroSmokeTestKt")
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED", "-Djava.awt.headless=true")
}

group = "com.mycompany"
version = "0.1.0-SNAPSHOT"

description = "Arkitekt Command"



repositories {
    mavenLocal()
    mavenCentral()
    maven("https://maven.imagej.net/content/groups/public")
    maven("https://repo.maven.apache.org/maven2")
    maven("https://maven.scijava.org/content/groups/public")
}

// IJ1 legacy layer for `./gradlew run` only — kept off runtimeClasspath so it is not bundled
// into the Fiji plugin (see the ij1Runtime dependency note below).
val ij1Runtime by configurations.creating

dependencies {
    kapt("net.imagej:imagej:2.16.0")
    implementation("net.imagej:imagej:2.16.0")
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
    implementation("dev.zarr:zarr-java:0.0.5-SNAPSHOT")
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



tasks.register<Copy>("installToImageJ") {
    description = "Copy plugin and dependencies to ImageJ plugins directory"

    // Path to ImageJ's plugins directory
    val imagejPluginsDir = file("${buildDir}/plugins")

    from(configurations.runtimeClasspath) {
        include("**/*.jar")
        exclude("**/groovy*.jar")
    }

    

    into(imagejPluginsDir)

    // Also copy your own plugin JAR
    from(tasks.named("jar")) {
        include("**/*.jar")

    }

    doLast {
        copy {
            from(imagejPluginsDir)
            into(file("/home/jhnnsrs/Programs/fiji-linux64/Fiji.app/plugins/arkitekt"))
        }
    }

}

tasks.register<Copy>("buildPlugin") {
    description = "Copy plugin and dependencies to ImageJ plugins directory"

    // Path to ImageJ's plugins directory
    val imagejPluginsDir = file("${buildDir}/plugins")

    from(configurations.runtimeClasspath) {
        include("**/*.jar")
        exclude("**/groovy*.jar")
    }

    into(imagejPluginsDir)

    // Also copy your own plugin JAR
    from(tasks.named("jar")) {
        include("**/*.jar")
    }

    // Zip the plugin directory
    val zipFile = file("${buildDir}/arkitekt-plugin.zip")
    doLast {
        ant.withGroovyBuilder {
            "zip"("destfile" to zipFile, "basedir" to imagejPluginsDir)
        }
    }
}