#!/bin/bash
# fetch-proot.sh — downloads a static proot binary and bundles it as
# libproot.so in the app's jniLibs directory.
#
# Android requires native libraries in lib/<abi>/ to have a .so extension.
# We rename proot to libproot.so and chmod +x at runtime.
#
# Run from the project root before building the APK.

set -e

JNILIBS_DIR="app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNILIBS_DIR"

# Try multiple proot static binary sources
PROOT_URLS=(
    "https://github.com/proot-me/proot/releases/download/v5.1.0/proot-v5.1.0-aarch64-static"
    "https://github.com/proot-me/proot/releases/download/v5.1.0/proot-v5.1.0-x86_64-static"
)

OUTPUT="$JNILIBS_DIR/libproot.so"

echo "=== Fetching static proot binary ==="

if [ -f "$OUTPUT" ] && [ "$1" != "--force" ]; then
    echo "  ✓ Already present (use --force to re-download)"
    exit 0
fi

for url in "${PROOT_URLS[@]}"; do
    echo "  Trying: $url"
    if curl -L -o "$OUTPUT" "$url" 2>/dev/null; then
        FILE_TYPE=$(file "$OUTPUT")
        if echo "$FILE_TYPE" | grep -q "ELF"; then
            chmod +x "$OUTPUT"
            echo "  ✓ Downloaded $(stat -c%s "$OUTPUT") bytes"
            echo "  ✓ Verified ELF: $FILE_TYPE"
            exit 0
        fi
    fi
done

echo "ERROR: Failed to download proot from any source" >&2
exit 1
