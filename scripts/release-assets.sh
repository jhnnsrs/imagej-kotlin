#!/usr/bin/env bash
# Build the plugin with a release version stamped in and collect the assets into dist/.
#
# Called by semantic-release (prepareCmd in .releaserc.yml) with the version it derived from the
# commit history; runnable by hand too:  scripts/release-assets.sh 0.2.0
#
# Only the bundle and the two checks on the *shipped* jar run here. The tests already gated this
# commit in the CI `build` job that the release job depends on.
set -euo pipefail

version="${1:?usage: $(basename "$0") <version>   (e.g. 0.2.0)}"
cd "$(dirname "$0")/.."

# gradle.properties pins org.gradle.java.home to the author's machine; on CI, JAVA_HOME points at
# the JDK setup-java installed and a command-line -D outranks the project file.
java_home_arg=()
if [ -n "${JAVA_HOME:-}" ]; then
  java_home_arg=("-Dorg.gradle.java.home=$JAVA_HOME")
fi

./gradlew buildPlugin verifyShadedJar shadedSmokeTest \
  "${java_home_arg[@]}" \
  -PpluginVersion="$version" \
  --stacktrace

rm -rf dist
mkdir dist
cp "build/arkitekt-plugin-$version.zip" "build/libs/arkitekt-plugin-$version.jar" dist/
(cd dist && sha256sum -- * > SHA256SUMS.txt)

echo "Release assets for $version:"
ls -l dist
