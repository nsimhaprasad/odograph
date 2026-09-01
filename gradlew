#!/bin/sh
# Self-bootstrapping Gradle launcher.
#
# The stock wrapper is not used here: Gradle's wrapper generator validates the distribution URL
# with a request that services.gradle.org answers with a 307 to GitHub releases, which the
# validator rejects. Downloading the same URL works fine, so we do that ourselves and keep no
# binary jar in the repository.
set -e
GRADLE_VERSION=8.7
DIST_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/odograph-dists"
GRADLE_BIN="$DIST_DIR/gradle-$GRADLE_VERSION/bin/gradle"

if [ ! -x "$GRADLE_BIN" ]; then
    echo "Gradle $GRADLE_VERSION not found. Downloading once into $DIST_DIR ..."
    mkdir -p "$DIST_DIR"
    curl -fsSL -o "$DIST_DIR/gradle.zip" \
        "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    unzip -q -o "$DIST_DIR/gradle.zip" -d "$DIST_DIR"
    rm -f "$DIST_DIR/gradle.zip"
fi

exec "$GRADLE_BIN" "$@"
