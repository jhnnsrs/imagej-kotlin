// Gradle build for the vendored zarr-java sources. This translates the Maven `pom.xml` (which is
// kept only for independent Maven Central publishing) into a `java-library` subproject so that
// `./gradlew build` at the repo root compiles zarr-java directly — no more `mvn install` into
// mavenLocal. See the root settings.gradle.kts (`include(":zarr-java")`) and build.gradle.kts
// (`implementation(project(":zarr-java"))`).

plugins { `java-library` }

group = "dev.zarr"
version = "0.0.5-SNAPSHOT" // keep coordinate identity; the project() dependency is what's actually used

repositories {
    mavenCentral()
    // cdm-core (edu.ucar) — canonical source is Unidata (the pom declares this repo).
    maven("https://artifacts.unidata.ucar.edu/repository/unidata-all/")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8 // pom: maven.compiler.release = 8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

dependencies {
    // Maven compile-scope deps -> `api` (transitive; the parent plugin imports ucar.ma2.* and
    // software.amazon.awssdk...S3Client from these, so they must stay on its classpath + in the bundle).
    api("com.fasterxml.jackson.core:jackson-databind:2.20.0")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.20.0")
    api("com.google.code.findbugs:jsr305:3.0.2")
    api("edu.ucar:cdm-core:5.9.1")
    api("software.amazon.awssdk:s3:2.34.6")
    api("com.scalableminds:blosc-java:0.3-1.21.6")
    api("com.github.luben:zstd-jni:1.5.5-7")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.apache.commons:commons-compress:1.28.0")
    api("info.picocli:picocli:4.7.6")

    // Maven test-scope deps.
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.14.0")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.14.0")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.14.0")
    testImplementation("junit:junit:4.13.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// zarr-java's tests hit Docker/S3/testcontainers + external testdata; don't run them as part of
// `./gradlew build`. Compilation is what the plugin needs. (Re-enable manually to run them.)
tasks.named<Test>("test") { enabled = false }
