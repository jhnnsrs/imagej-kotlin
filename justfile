# Justfile for the Arkitekt ImageJ plugin.
# Run `just` (or `just start`) to launch ImageJ with the plugin.

# Full, non-headless JDK 17: Gradle 8.10 can't run on the system default (JDK 25), and the
# JDK 8/21 installs here are headless (no AWT, so no GUI). Override on the CLI if needed:
#   just java_home=/path/to/jdk17 start
java_home := "/usr/lib/jvm/java-17-openjdk-amd64"

export JAVA_HOME := java_home

# Show available recipes.
default:
    @just --list

# Start the software: launch ImageJ with the Arkitekt plugin (GUI).
start:
    ./gradlew run

# Alias for `start`.
run: start

# Compile + assemble (Apollo codegen + Kotlin compile + jar).
build:
    ./gradlew build

# Copy the plugin + runtime deps into the local Fiji.app plugins dir.
install:
    ./gradlew installToImageJ

# Bundle the plugin + deps into build/arkitekt-plugin.zip for distribution.
plugin:
    ./gradlew buildPlugin

# Clear the cached Arkitekt login (forces a fresh fakts negotiation on next start).
logout:
    rm -f ~/.arkitekt/fakts_cache.json

# Remove Gradle build outputs.
clean:
    ./gradlew clean
