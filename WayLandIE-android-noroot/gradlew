#!/bin/sh
# Gradle wrapper bootstrap script. Downloads gradle-8.7 on first run if the
# wrapper jar is not present, then runs gradle with the wrapper.
#
# If you cloned this repo without the gradle-wrapper.jar (some archives
# strip binary files), this script will fetch it on first run.

set -e

APP_HOME=$(cd "$(dirname "$0")" && pwd)
WRAPPER_DIR="$APP_HOME/gradle/wrapper"
WRAPPER_JAR="$WRAPPER_DIR/gradle-wrapper.jar"
WRAPPER_PROPS="$WRAPPER_DIR/gradle-wrapper.properties"

# Download wrapper jar if missing (some git hosts strip binaries).
if [ ! -f "$WRAPPER_JAR" ]; then
    echo "gradle-wrapper.jar missing — downloading…"
    mkdir -p "$WRAPPER_DIR"
    WRAPPER_URL="https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradle/wrapper/gradle-wrapper.jar"
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$WRAPPER_URL" -o "$WRAPPER_JAR"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$WRAPPER_JAR" "$WRAPPER_URL"
    else
        echo "ERROR: need curl or wget to download gradle-wrapper.jar" >&2
        exit 1
    fi
fi

# Run the wrapper jar.
exec java "-Xmx4096m" "-Dorg.gradle.appname=gradlew" -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
