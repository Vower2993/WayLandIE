#!/bin/bash
# install-turnip-manual.sh — direct, no-python Turnip installer.
#
# This is a fallback for when the in-app Settings → Install Turnip
# flow doesn't work. Run this script INSIDE Debian proot.
#
# Usage:
#   1. Make sure you have a turnip .zip or .tar.xz file somewhere
#      (e.g. in /sdcard/Download/)
#   2. From Termux:
#      proot-distro login debian --shared-tmp -- bash -c '
#        curl -sL https://tmpfiles.org/dl/wQwBHfIDyG5t/waylandie-diagnose.txt -o /tmp/diag.sh
#        # Or for this script:
#        # bash install-turnip-manual.sh /path/to/turnip.zip
#      '
#
# Or pass the archive path as arg 1.

set -e
ARCHIVE="${1:-}"
if [ -z "$ARCHIVE" ]; then
    echo "Usage: $0 <path-to-turnip-archive>"
    echo "  Supported formats: .zip .tar.gz .tar.xz .tar.bz2 .tar"
    echo
    echo "Download a Turnip build from:"
    echo "  https://github.com/K11MCH1/AdrenoToolsDrivers/releases/latest"
    echo
    echo "Looking for archive in common locations…"
    for c in \
        /sdcard/Download/turnip*.zip \
        /sdcard/Download/turnip*.tar.xz \
        /sdcard/Download/turnip*.tar.gz \
        /sdcard/Download/AdrenoToolsDrivers*.zip \
        /root/turnip*.zip \
        /root/turnip*.tar.xz \
        /tmp/turnip*.zip; do
        if ls $c 2>/dev/null | head -1 > /dev/null; then
            ARCHIVE=$(ls $c 2>/dev/null | head -1)
            echo "  Found: $ARCHIVE"
            break
        fi
    done
    if [ -z "$ARCHIVE" ]; then
        echo "  No archive found. Pass the path explicitly:"
        echo "    $0 /path/to/turnip.zip"
        exit 1
    fi
fi

if [ ! -f "$ARCHIVE" ]; then
    echo "ERROR: file not found: $ARCHIVE"
    exit 1
fi

echo "=== Manual Turnip installer ==="
echo "  archive: $ARCHIVE"
echo "  size:    $(stat -c%s "$ARCHIVE" 2>/dev/null || wc -c < "$ARCHIVE") bytes"
echo

# Make sure we're root in the proot (most proot-distro setups auto-root).
if [ "$(id -u)" != "0" ]; then
    echo "WARNING: not running as root. Some installs may fail."
    echo "  Re-run with: proot-distro login debian --shared-tmp -- bash -c '...'"
    echo
fi

# 1. Extract to /tmp/turnip-extract
EXTRACT_DIR=/tmp/turnip-extract-$$
rm -rf "$EXTRACT_DIR"
mkdir -p "$EXTRACT_DIR"
echo "Extracting to $EXTRACT_DIR …"
case "$ARCHIVE" in
    *.zip)         unzip -q "$ARCHIVE" -d "$EXTRACT_DIR" ;;
    *.tar.gz|*.tgz) tar -xzf "$ARCHIVE" -C "$EXTRACT_DIR" ;;
    *.tar.xz|*.txz) tar -xJf "$ARCHIVE" -C "$EXTRACT_DIR" ;;
    *.tar.bz2|*.tbz2) tar -xjf "$ARCHIVE" -C "$EXTRACT_DIR" ;;
    *.tar)         tar -xf "$ARCHIVE" -C "$EXTRACT_DIR" ;;
    *)
        # Try zip then tar as fallbacks.
        unzip -q "$ARCHIVE" -d "$EXTRACT_DIR" 2>/dev/null || \
        tar -xf "$ARCHIVE" -C "$EXTRACT_DIR" 2>/dev/null || {
            echo "ERROR: unrecognized archive format."
            exit 1
        }
        ;;
esac
echo

# 2. Find the ICD JSON
echo "Searching for ICD JSON…"
ICD=$(find "$EXTRACT_DIR" -name "*icd*.json" -type f 2>/dev/null | head -1)
if [ -z "$ICD" ]; then
    echo "ERROR: no *icd*.json file found in archive."
    echo "Contents of $EXTRACT_DIR:"
    find "$EXTRACT_DIR" -type f | head -20
    echo
    echo "The Turnip archive should contain a JSON file like"
    echo "  freedreno_icd.aarch64.json"
    echo "or"
    echo "  lvp_icd.x86_64.json"
    echo
    echo "The archive you provided doesn't have one. Check the source."
    exit 1
fi
echo "  Found ICD JSON: $ICD"
echo

# 3. Find the .so
echo "Searching for Vulkan .so…"
SO=$(find "$EXTRACT_DIR" -name "libvulkan_freedreno.so" -type f 2>/dev/null | head -1)
if [ -z "$SO" ]; then
    SO=$(find "$EXTRACT_DIR" -name "libvulkan_*.so" -type f 2>/dev/null | head -1)
fi
if [ -z "$SO" ]; then
    echo "ERROR: no libvulkan_*.so file found in archive."
    echo "Contents of $EXTRACT_DIR:"
    find "$EXTRACT_DIR" -type f | head -20
    exit 1
fi
echo "  Found .so: $SO"
echo

# 4. Install the .so to /usr/local/lib
DEST_SO=/usr/local/lib/$(basename "$SO")
mkdir -p /usr/local/lib
echo "Copying .so to $DEST_SO …"
cp -f "$SO" "$DEST_SO"
chmod 644 "$DEST_SO"
ldconfig 2>/dev/null || true
echo

# 5. Write a fresh ICD JSON that points at the absolute .so path.
# This avoids relying on the archive's JSON which may have relative paths.
DEST_ICD_DIR=/usr/local/etc/vulkan/icd.d
DEST_ICD=$DEST_ICD_DIR/$(basename "$ICD")
mkdir -p "$DEST_ICD_DIR"
echo "Writing ICD JSON to $DEST_ICD …"
# We don't need python — just emit a minimal valid ICD JSON directly.
cat > "$DEST_ICD" <<EOF
{
    "file_format_version": "1.0.0",
    "ICD": {
        "library_path": "$DEST_SO",
        "api_version": "1.3.0"
    }
}
EOF
chmod 644 "$DEST_ICD"
echo

# 6. Disable llvmpipe ICD so Turnip wins by default
echo "Looking for llvmpipe ICD to disable…"
LP_ICD=$(find /usr/share/vulkan/icd.d /etc/vulkan/icd.d -name "*lvp*.json" -o -name "*llvmpipe*.json" 2>/dev/null | head -1)
if [ -n "$LP_ICD" ]; then
    echo "  Found llvmpipe ICD: $LP_ICD"
    echo "  Renaming to .disabled so Turnip wins:"
    mv "$LP_ICD" "${LP_ICD}.disabled"
    echo "  → ${LP_ICD}.disabled"
else
    echo "  No llvmpipe ICD found in standard paths (OK)."
fi
echo

# 7. Also write the env-turnip.sh file so waylandie-run sources it
ENV_FILE=/usr/local/share/waylandie/env-turnip.sh
mkdir -p /usr/local/share/waylandie
cat > "$ENV_FILE" <<EOF
# Auto-generated by install-turnip-manual.sh
export VK_ICD_FILENAMES="$DEST_ICD"
export VK_DRIVER_FILES="$DEST_ICD"
EOF
chmod 644 "$ENV_FILE"
echo "Wrote env file: $ENV_FILE"
echo

# 8. Cleanup
rm -rf "$EXTRACT_DIR"

# 9. Verify
echo "=== Verifying install ==="
echo "ICD dir contents:"
ls -la "$DEST_ICD_DIR"
echo
echo "ICD JSON content:"
cat "$DEST_ICD"
echo
echo ".so file:"
ls -la "$DEST_SO"
echo

echo "=== Verifying vulkaninfo picks up Turnip ==="
# Source the env file before checking.
. "$ENV_FILE"
echo "VK_ICD_FILENAMES=$VK_ICD_FILENAMES"
echo
echo "Running vulkaninfo --summary:"
vulkaninfo --summary 2>&1 | head -30 || echo "  vulkaninfo not installed"
echo

echo "=== DONE ==="
echo
echo "If vulkaninfo still shows llvmpipe, run:"
echo "  export VK_ICD_FILENAMES=$DEST_ICD"
echo "  vulkaninfo --summary | grep deviceName"
echo
echo "If that works, your shell env wasn't picking up the env file."
echo "Add this to ~/.bashrc:"
echo "  . $ENV_FILE"
