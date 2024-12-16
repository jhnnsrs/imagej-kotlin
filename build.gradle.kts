
plugins {
    kotlin("jvm") version "2.0.0"
    kotlin("kapt") version "2.0.0"
    id("com.apollographql.apollo") version "4.0.0"
}

group = "com.mycompany"
version = "0.1.0-SNAPSHOT"

description = "Gauss Filtering"

repositories {
    mavenCentral()
    maven("https://maven.imagej.net/content/groups/public")
    maven("https://repo.maven.apache.org/maven2")
}

dependencies {
    kapt("net.imagej:imagej:2.16.0")
    implementation("net.imagej:imagej:2.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("com.apollographql.apollo:apollo-runtime:4.0.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing")

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
  service("graphql") {
    packageName.set("com.mycompany.imagej")
    introspection {
      endpointUrl.set("http://127.0.0.1/lok/graphql")
      schemaFile.set(file("src/main/graphql/schema.graphqls"))
    }
  }
}