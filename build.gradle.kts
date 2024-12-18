
plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("kapt") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("com.apollographql.apollo") version "4.0.0"
    id("maven-publish") // Add this line
}

group = "com.mycompany"
version = "0.1.0-SNAPSHOT"

description = "Arkitekt Command"

repositories {
    mavenCentral()
    maven("https://maven.imagej.net/content/groups/public")
    maven("https://repo.maven.apache.org/maven2")
    maven("https://maven.scijava.org/content/groups/public")
}

dependencies {
    kapt("net.imagej:imagej:2.16.0")
    implementation("net.imagej:imagej:2.16.0")
    implementation("com.apollographql.apollo:apollo-runtime:4.0.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing")
    implementation("dev.zarr:zarr-java:0.0.4")
    implementation("com.amazonaws:aws-java-sdk-s3:1.12.780")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0-RC")




    implementation("io.ktor:ktor-client-core:3.0.2")
    implementation("io.ktor:ktor-client-cio:3.0.2")
    implementation("io.ktor:ktor-client-websockets:3.0.2")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

}

kotlin {
    jvmToolchain(8)
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
    service("lok") {
        packageName.set("com.mycompany.lok.graphql")
        srcDir("src/main/graphql/lok")
        introspection {
          endpointUrl.set("http://127.0.0.1/lok/graphql")
          schemaFile.set(file("src/main/graphql/lok/schema.graphqls"))

        }
    }
    service("mikro") {
        packageName.set("com.mycompany.mikro.graphql")
        srcDir("src/main/graphql/mikro")
        introspection {
            endpointUrl.set("http://127.0.0.1/mikro/graphql")
            schemaFile.set(file("src/main/graphql/mikro/schema.graphqls"))
        }
    }
    service("rekuest") {
        packageName.set("com.mycompany.rekuest.graphql")
        srcDir("src/main/graphql/rekuest")
        introspection {
            endpointUrl.set("http://127.0.0.1/rekuest/graphql")
            schemaFile.set(file("src/main/graphql/rekuest/schema.graphqls"))
        }
    }
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