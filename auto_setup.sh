#!/usr/bin/env bash
# Fast autonomous WinNative setup script for WayLandIE testing
# Usage: ./auto_setup.sh <apk_path_or_url>
set -eu

ADB="adb"
if [ -n "${ANDROID_HOME:-}" ] && [ -f "$ANDROID_HOME/platform-tools/adb" ]; then
  ADB="$ANDROID_HOME/platform-tools/adb"
fi

echo "=== Installing APK ==="
APK="$1"
if echo "$APK" | grep -q '^http'; then
  # Download APK directly to phone
  $ADB shell "curl -sL -o /data/local/tmp/waylandie.apk '$APK' && pm install -r -g /data/local/tmp/waylandie.apk" 2>&1
elif [ -f "$APK" ]; then
  $ADB install -r -g "$APK"
else
  # Assume it's a phone path
  $ADB shell "pm install -r -g '$APK'"
fi

echo "=== Launching app ==="
$ADB shell am start -n com.tencent.ig/com.winlator.cmod.app.shell.UnifiedActivity
sleep 3

# Check if setup wizard appears - check for "SETUP WIZARD" text
SETUP=$($ADB shell uiautomator dump /dev/stdout 2>/dev/null | grep -o 'SETUP WIZARD' | head -1)

if [ -n "$SETUP" ]; then
  echo "=== Setup Wizard detected ==="
  # Step 1: Install system files (all 3 buttons)
  $ADB shell input tap 1625 830    # Notifications - Allow
  sleep 1
  $ADB shell input tap 660 830     # File access - Grant
  sleep 2
  # Handle "All files access" system dialog
  # The button might vary by Android version - tap common "Allow" position
  $ADB shell input tap 1873 1040   # System permission Allow button
  sleep 2
  $ADB shell input tap 2589 830    # Install System Files
  sleep 5
  # Tap Next to go to step 2
  $ADB shell input tap 2910 1249
  sleep 2
  # Tap Next again to skip component selection (we'll install from main app)
  $ADB shell input tap 2910 1249
  sleep 2
  # Tap Finish
  $ADB shell input tap 2894 1249
  sleep 3
fi

echo "=== Opening menu drawer ==="
$ADB shell input tap 115 120       # Hamburger menu
sleep 2

echo "=== Opening Debug settings ==="
# The drawer shows SETTINGS with scrollable items
# Tap on "Debug" under TOOLS section
$ADB shell input tap 400 650       # Debug (approximate)
sleep 2

echo "=== Enabling debug options ==="
# Toggle: Application Log, WineDebug, last option
# These are toggle switches at specific positions
$ADB shell input tap 2800 400      # Toggle 1: Application Log
sleep 1
$ADB shell input tap 2800 550      # Toggle 2: WineDebug
sleep 1
$ADB shell input tap 2800 700      # Toggle 3: Last option
sleep 1

echo "=== Installing Turnip driver ==="
# Navigate back to main, then open Drivers
$ADB shell input tap 115 120       # Open menu
sleep 2
# Scroll down the drawer to find Drivers
$ADB shell input swipe 400 1000 400 200 200  # Swipe down
sleep 1
$ADB shell input tap 500 750       # Tap "Drivers"
sleep 2
# Tap "Install" or select Turnip from list
$ADB shell input tap 2700 500      # Install driver
sleep 1
# In file picker, navigate to Downloads/1DMP/Compressed/
$ADB shell input tap 400 400       # Navigate to Downloads
sleep 1
$ADB shell input tap 400 600       # Open 1DMP folder
sleep 1
$ADB shell input tap 400 600       # Open Compressed folder
sleep 1
# Select Turnip driver file
$ADB shell input tap 400 800       # Select Turnip file
sleep 2
# Confirm install
$ADB shell input tap 2800 1200     # Confirm/Install button
sleep 5

echo "=== Installing Components ==="
# Go back, open Components
$ADB shell input tap 115 120       # Menu
sleep 2
$ADB shell input tap 500 600       # Tap "Components"
sleep 2

# Install FEX 2.6.5
$ADB shell input tap 2800 400      # Install FEX button
sleep 1
# Navigate to Downloads in file picker
$ADB shell input tap 400 400       # Downloads path
sleep 1
$ADB shell input tap 400 800       # Select FEX file
sleep 2
$ADB shell input tap 2800 1200     # Confirm
sleep 5

# Install DXVK 2.71  
$ADB shell input tap 2800 600      # Install DXVK button
sleep 1
# Navigate to Downloads
$ADB shell input tap 400 400       # Downloads path
sleep 1
$ADB shell input tap 400 900       # Select DXVK file (adjust if needed)
sleep 2
$ADB shell input tap 2800 1200     # Confirm
sleep 5

# Install wn.proton-11
$ADB shell input tap 2800 800      # Install Proton button
sleep 1
$ADB shell input tap 400 400       # Downloads path
sleep 1
$ADB shell input tap 400 1000      # Select Proton file (adjust if needed)
sleep 2
$ADB shell input tap 2800 1200     # Confirm
sleep 5

echo "=== Creating Container ==="
$ADB shell input tap 115 120       # Menu
sleep 2
$ADB shell input tap 500 400       # Tap "Containers"
sleep 2
$ADB shell input tap 2800 400      # "New Container" button
sleep 2
# Select Proton version
$ADB shell input tap 500 800       # Select Proton from list
sleep 2
$ADB shell input tap 2800 1200     # Create container
sleep 10                           # Wait for extraction

echo "=== Adding ROTTR shortcut ==="
# Navigate to shortcuts/games
$ADB shell input tap 115 120       # Menu
sleep 2
$ADB shell input tap 500 300       # Tap "Library" or "Games"
sleep 2
$ADB shell input tap 2800 400      # "Add Custom Game" or similar
sleep 2
# Navigate to ROTTR exe in file picker
$ADB shell input tap 400 400       # Navigate to Downloads
sleep 1
# Navigate to ROTTR directory
$ADB shell input tap 400 800       # Select ROTTR folder (adjust based on listing)
sleep 1
$ADB shell input tap 400 900       # Select rottr.exe
sleep 2
$ADB shell input tap 2800 1200     # Confirm game shortcut
sleep 2

echo "=== Launching ROTTR ==="
$ADB shell input tap 500 600       # Tap on ROTTR in game list
sleep 2
$ADB shell input tap 2800 400      # "Play" or "Run" button
sleep 5

echo "=== Game launched. Monitoring for 120s ==="
# Monitor for key log messages
sleep 120

echo "=== Collecting logs ==="
$ADB shell "find /storage/emulated/0/WinNative -name '*log*' -type f -newer /data/local/tmp/waylandie.apk 2>/dev/null | head -5"
$ADB pull "/storage/emulated/0/WinNative/logs/" /tmp/win_logs_auto/ 2>/dev/null || true
echo "=== Done ==="
