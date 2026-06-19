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

OUTPUT="$JNILIBS_DIR/libproot.so"

echo "=== Fetching static proot binary ==="

if [ -f "$OUTPUT" ] && [ "$1" != "--force" ]; then
    echo "  ✓ Already present (use --force to re-download)"
    exit 0
fi

# Try multiple proot static binary sources in order of preference.
# v5.3.0 is the latest release with static aarch64 binaries.
# We also try some forks that Winlator/WinNative use.
PROOT_URLS=(
    "https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-aarch64-static"
    "https://github.com/proot-me/proot/releases/download/v5.4.0/proot-v5.4.0-aarch64-static"
    "https://github.com/proot-me/proot/releases/download/v5.1.0/proot-v5.1.0-aarch64-static"
    # Fall back to a docker image that bundles proot if all else fails
)

for url in "${PROOT_URLS[@]}"; do
    echo "  Trying: $url"
    if curl -L --fail --max-time 60 -o "$OUTPUT" "$url" 2>/dev/null; then
        # Verify it's actually a binary, not a 404 HTML page
        FILE_TYPE=$(file "$OUTPUT")
        if echo "$FILE_TYPE" | grep -q "ELF"; then
            chmod +x "$OUTPUT"
            SIZE=$(stat -c%s "$OUTPUT")
            echo "  ✓ Downloaded $SIZE bytes"
            echo "  ✓ Verified ELF: $FILE_TYPE"
            exit 0
        else
            echo "  ⚠ Downloaded but not ELF: $FILE_TYPE"
            rm -f "$OUTPUT"
            continue
        fi
    fi
    echo "  ⚠ Download failed or HTTP error"
    rm -f "$OUTPUT"
done

# Last resort: try downloading from a Termux package mirror
# Termux ships proot as a .deb — extract the binary from it
echo "  Trying Termux package mirror as last resort…"
if curl -L --fail --max-time 60 -o /tmp/proot.deb \
    "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.3.0-1_aarch64.deb" 2>/dev/null; then
    mkdir -p /tmp/proot-extract
    if dpkg-deb -x /tmp/proot.deb /tmp/proot-extract 2>/dev/null; then
        PROOT_BIN=$(find /tmp/proot-extract -name "proot" -type f | head -1)
        if [ -n "$PROOT_BIN" ]; then
            cp "$PROOT_BIN" "$OUTPUT"
            chmod +x "$OUTPUT"
            FILE_TYPE=$(file "$OUTPUT")
            if echo "$FILE_TYPE" | grep -q "ELF"; then
                echo "  ✓ Extracted proot from Termux .deb"
                echo "  ✓ Verified ELF: $FILE_TYPE"
                rm -rf /tmp/proot-extract /tmp/proot.deb
                exit 0
            fi
        fi
    fi
    rm -rf /tmp/proot-extract /tmp/proot.deb
fi

echo "ERROR: Failed to download proot from any source" >&2
echo "  Tried:" >&2
for url in "${PROOT_URLS[@]}"; do
    echo "    - $url" >&2
done
echo "    - Termux packages.termux.dev" >&2
echo ""
echo "  Manual fix: download proot-v5.3.0-aarch64-static from" >&2
echo "  https://github.com/proot-me/proot/releases/tag/v5.3.0" >&2
echo "  and place it at app/src/main/jniLibs/arm64-v8a/libproot.so" >&2
exit 1
