#!/bin/bash
# Diagnostic script to test winewayland.drv loading in isolation.
# Runs wine explorer.exe with full debug output and captures the exit signal.
# This helps pinpoint whether the crash is in:
#   - winewayland.drv's DllMain (process_attach)
#   - USER_LoadDriver's GetProcAddress calls
#   - The driver's create_desktop function
#   - A background thread (wayland event loop)
#
# Usage: Run this on the device (via adb shell or termux) after the Wayland
# bridge is running. The script will:
#   1. Start wine explorer.exe with WINEDEBUG=+all
#   2. Capture stderr to a file
#   3. Report the exit code and signal
#   4. Grep the debug log for crash-related lines

set -e

PREFIX="${1:-/data/user/0/com.tencent.ig/files/imagefs/home/xuser-1/.wine}"
WINEPATH="${2:-/data/user/0/com.tencent.ig/files/contents/Proton/11.0-2-arm64ec-0}"
RUNTIME_DIR="${3:-/data/user/0/com.tencent.ig/files/imagefs/usr/tmp/runtime}"
LOGFILE="${4:-/data/user/0/com.tencent.ig/files/logs/test-winewayland.log}"

echo "=== WaylandIE winewayland.drv diagnostic test ==="
echo "PREFIX=$PREFIX"
echo "WINEPATH=$WINEPATH"
echo "RUNTIME_DIR=$RUNTIME_DIR"
echo "LOGFILE=$LOGFILE"
echo ""

# Verify files exist
echo "--- File verification ---"
echo "winewayland.drv in system32: $(ls -la "$PREFIX/drive_c/windows/system32/winewayland.drv" 2>&1)"
echo "winewayland.drv in winePath: $(ls -la "$WINEPATH/lib/wine/aarch64-windows/winewayland.drv" 2>&1)"
echo "winewayland.so in winePath:  $(ls -la "$WINEPATH/lib/wine/aarch64-unix/winewayland.so" 2>&1)"
echo "wayland-0 socket:            $(ls -la "$RUNTIME_DIR/wayland-0" 2>&1)"
echo ""

# Check registry entries
echo "--- Registry check ---"
echo "GraphicsDriver in system.reg:"
grep -i "GraphicsDriver" "$PREFIX/system.reg" 2>/dev/null || echo "  (not found)"
echo ""
echo "Graphics in user.reg [Software\\Wine\\Drivers]:"
grep -A5 'Software\\\\Wine\\\\Drivers' "$PREFIX/user.reg" 2>/dev/null || echo "  (not found)"
echo ""

# Check WINEDLLOVERRIDES
echo "--- Environment check ---"
echo "WINEDLLOVERRIDES should be: winewayland.drv=b;winex11.drv="
echo ""

# Run wine explorer.exe with full debug
echo "--- Starting wine explorer.exe with WINEDEBUG=+all ---"
echo "This will produce VERY verbose output. Waiting 10 seconds then killing..."

export WINEPREFIX="$PREFIX"
export WINEDEBUG="+all"
export WAYLAND_DISPLAY="wayland-0"
export XDG_RUNTIME_DIR="$RUNTIME_DIR"
export DISPLAY=":0"
export WINEDLLOVERRIDES="winewayland.drv=b;winex11.drv="
export LD_LIBRARY_PATH="/data/user/0/com.tencent.ig/files/imagefs/usr/lib:/system/lib64"
export PATH="$WINEPATH/bin:/data/user/0/com.tencent.ig/files/imagefs/usr/bin"

# Run wine and capture exit code
timeout 10 "$WINEPATH/bin/wine" explorer.exe >"$LOGFILE" 2>&1 &
WINE_PID=$!
echo "Wine PID: $WINE_PID"

# Wait for wine to finish or timeout
wait $WINE_PID 2>/dev/null
EXIT_CODE=$?

echo ""
echo "--- Exit code analysis ---"
if [ $EXIT_CODE -eq 0 ]; then
    echo "Wine exited normally (code 0)"
elif [ $EXIT_CODE -gt 128 ]; then
    SIG=$((EXIT_CODE - 128))
    case $SIG in
        1)  SIG_NAME="SIGHUP" ;;
        2)  SIG_NAME="SIGINT" ;;
        3)  SIG_NAME="SIGQUIT" ;;
        4)  SIG_NAME="SIGILL" ;;
        6)  SIG_NAME="SIGABRT" ;;
        7)  SIG_NAME="SIGBUS" ;;
        8)  SIG_NAME="SIGFPE" ;;
        9)  SIG_NAME="SIGKILL" ;;
        11) SIG_NAME="SIGSEGV" ;;
        13) SIG_NAME="SIGPIPE" ;;
        15) SIG_NAME="SIGTERM" ;;
        *)  SIG_NAME="signal_$SIG" ;;
    esac
    echo "Wine was KILLED BY SIGNAL $SIG ($SIG_NAME)"
    echo "Exit code: $EXIT_CODE (128 + $SIG)"
else
    echo "Wine exited with code $EXIT_CODE"
fi

echo ""
echo "--- Crash analysis ---"
echo "Searching log for crash-related lines..."
echo ""

echo "=== SEH exceptions ==="
grep -i "seh:" "$LOGFILE" | tail -20
echo ""

echo "=== nodrv_CreateWindow ==="
grep -c "nodrv_CreateWindow" "$LOGFILE"
echo ""

echo "=== winewayland.drv loading ==="
grep "winewayland.drv" "$LOGFILE" | grep -E "load_dll|build_module|process_attach|MODULE_InitDLL" | head -10
echo ""

echo "=== Last 30 lines of log ==="
tail -30 "$LOGFILE"
echo ""

echo "=== Log file size ==="
wc -c "$LOGFILE"
echo ""
echo "Full log at: $LOGFILE"
