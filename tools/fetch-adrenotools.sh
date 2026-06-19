# fetch-adrenotools.sh — downloads and bundles libadrenotools.so
# from Pipetto-crypto's libadrenotools repository.
#
# Adrenotools is a runtime driver loader that lets the app swap Adreno
# Vulkan drivers without root. It's what WinNative/Winlator use for
# their driver-slot system.
#
# Output: app/src/main/jniLibs/arm64-v8a/libadrenotools.so
#
# Run from the project root before building the APK.

set -e

JNILIBS_DIR="app/src/main/jniLibs/arm64-v8a"
mkdir -p "$JNILIBS_DIR"

OUTPUT="$JNILIBS_DIR/libadrenotools.so"

echo "=== Fetching libadrenotools.so ==="

if [ -f "$OUTPUT" ] && [ "$1" != "--force" ]; then
    echo "  ✓ Already present (use --force to re-download)"
    exit 0
fi

# Clone the libadrenotools repo and build it with NDK
WORK_DIR="/tmp/libadrenotools-build"
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR"

echo "  Cloning Pipetto-crypto/libadrenotools…"
git clone --depth 1 https://github.com/Pipetto-crypto/libadrenotools.git "$WORK_DIR/libadrenotools"

# The repo ships prebuilt .so files in some branches, or we build from source.
# Try prebuilt first.
PREBUILT=$(find "$WORK_DIR/libadrenotools" -name "libadrenotools.so" -type f | head -1)
if [ -n "$PREBUILT" ]; then
    echo "  Found prebuilt: $PREBUILT"
    cp "$PREBUILT" "$OUTPUT"
    chmod +x "$OUTPUT"
    echo "  ✓ Copied prebuilt libadrenotools.so"
else
    echo "  No prebuilt found — building from source with NDK…"
    
    # Find NDK
    NDK_ROOT="${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/26.1.10909125}"
    if [ ! -d "$NDK_ROOT" ]; then
        # Try common locations
        for path in /opt/android-sdk/ndk/26.1.10909125 \
                    $HOME/Android/Sdk/ndk/26.1.10909125 \
                    /usr/lib/android-sdk/ndk/26.1.10909125; do
            if [ -d "$path" ]; then
                NDK_ROOT="$path"
                break
            fi
        done
    fi
    
    if [ ! -d "$NDK_ROOT" ]; then
        echo "ERROR: NDK not found. Set ANDROID_NDK_HOME or install NDK r26." >&2
        echo "  Tried: $NDK_ROOT" >&2
        exit 1
    fi
    
    CLANG="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android33-clang"
    if [ ! -x "$CLANG" ]; then
        echo "ERROR: NDK clang not found at $CLANG" >&2
        exit 1
    fi
    
    # Find the adrenotools source
    SRC=$(find "$WORK_DIR/libadrenotools" -name "adrenotools.c" -o -name "adrenotools.cpp" | head -1)
    if [ -z "$SRC" ]; then
        echo "ERROR: adrenotools source not found in repo" >&2
        ls -la "$WORK_DIR/libadrenotools"
        exit 1
    fi
    
    SRC_DIR=$(dirname "$SRC")
    echo "  Building from: $SRC_DIR"
    
    # Compile as shared lib
    cd "$SRC_DIR"
    "$CLANG" -shared -fPIC -O2 -Wall \
        -I"$SRC_DIR" \
        -o "$OUTPUT" \
        $(find . -name "*.c" -o -name "*.cpp" | head -10) \
        -landroid -llog -ldl
    
    if [ ! -f "$OUTPUT" ]; then
        echo "ERROR: build failed" >&2
        exit 1
    fi
    
    chmod +x "$OUTPUT"
    echo "  ✓ Built libadrenotools.so ($(stat -c%s "$OUTPUT") bytes)"
fi

# Verify ELF
FILE_TYPE=$(file "$OUTPUT")
if ! echo "$FILE_TYPE" | grep -q "ELF"; then
    echo "ERROR: $OUTPUT is not an ELF binary" >&2
    echo "  $FILE_TYPE" >&2
    exit 1
fi
echo "  ✓ Verified: $FILE_TYPE"
