#!/usr/bin/env bash
set -euo pipefail

# ─── Android SDK + NDK r16b Setup Script ────────────────────────────────────
# Run once before building: bash setup-android.sh
# After setup: cd VirtualApp && ./gradlew assembleDebug

ANDROID_HOME="${ANDROID_HOME:-/home/runner/android-sdk}"
ANDROID_SDK_ROOT="$ANDROID_HOME"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-9477386_latest.zip"
NDK_URL="https://dl.google.com/android/repository/android-ndk-r16b-linux-x86_64.zip"
# NDK is stored in the workspace to avoid /home/runner quota limits
NDK_INSTALL_DIR="/home/runner/workspace/android-ndk/android-ndk-r16b"
TMP_NDK="/home/runner/workspace/android-ndk/ndk-r16b.zip"

# ─── Use JDK 11 ──────────────────────────────────────────────────────────────
JDK11_PATH="/nix/store/r9w6q359r1608viyc13bm7a3h65nhxp0-openjdk-11.0.26+4"
if [ -d "$JDK11_PATH" ]; then
    export JAVA_HOME="$JDK11_PATH"
    export PATH="$JAVA_HOME/bin:$PATH"
fi

export ANDROID_HOME ANDROID_SDK_ROOT
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

echo ""
echo "========================================"
echo "  Android Build Environment Setup"
echo "========================================"
echo "  ANDROID_HOME : $ANDROID_HOME"
echo "  JAVA_HOME    : $JAVA_HOME"
echo "  Java         : $(java -version 2>&1 | head -1)"
echo "========================================"
echo ""

# ─── 1. Create SDK directory structure ───────────────────────────────────────
mkdir -p "$ANDROID_HOME/cmdline-tools"
mkdir -p "$ANDROID_HOME/licenses"
mkdir -p "$ANDROID_HOME/ndk"

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

# ─── 2. Download command-line tools if not already present ───────────────────
if [ ! -f "$SDKMANAGER" ]; then
    echo ">>> [1/4] Downloading Android command-line tools..."
    TMP_ZIP="/tmp/cmdline-tools.zip"
    wget -q --show-progress -c -O "$TMP_ZIP" "$CMDLINE_TOOLS_URL"
    echo ">>> Extracting command-line tools..."
    unzip -q "$TMP_ZIP" -d "$ANDROID_HOME/cmdline-tools"
    mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    rm -f "$TMP_ZIP"
    echo "    Done."
else
    echo ">>> [1/4] Android command-line tools already installed. Skipping."
fi

# ─── 3. Pre-write license acceptances (non-interactive) ──────────────────────
echo ">>> [2/4] Accepting Android SDK licenses..."
mkdir -p "$ANDROID_HOME/licenses"

write_license() {
    local file="$1"
    local content="$2"
    printf '%s' "$content" > "$file" 2>/dev/null || true
}

write_license "$ANDROID_HOME/licenses/android-sdk-license" \
    "\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n8933bad161af4408b1c4ae2a4e14da1cf3c36f55"
write_license "$ANDROID_HOME/licenses/android-sdk-preview-license" \
    "\n84831b9409646a918e30573bab4c9c91346d8abd"
write_license "$ANDROID_HOME/licenses/android-googletv-license" \
    "\n601085b94cd77f0b54ff86406957099ebe79c4d7"
write_license "$ANDROID_HOME/licenses/android-sdk-arm-dbt-license" \
    "\n859f317696f67ef3d7f30a50a5560e7834b43903"
write_license "$ANDROID_HOME/licenses/mips-android-sysimage-license" \
    "\ne9acab5b5fbb560a72cfaecce8946896ff6aab9d"
write_license "$ANDROID_HOME/licenses/intel-android-extra-license" \
    "\nd975f751698a77b662f1254ddbeed3901e976f5a"

# Also accept interactively as a fallback
yes | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses > /dev/null 2>&1 || true
echo "    Done."

# ─── 4. Install SDK platforms and build tools ─────────────────────────────────
echo ">>> [3/4] Installing SDK Platform 28 and Build-Tools 28.0.3..."
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" \
    "platforms;android-28" \
    "build-tools;28.0.3" \
    "platform-tools"
echo "    Done."

# ─── 5. Download and install NDK r16b ────────────────────────────────────────
# Check for complete installation: the build/ subdirectory must exist
NDK_COMPLETE=false
if [ -d "$NDK_INSTALL_DIR/build" ]; then
    NDK_COMPLETE=true
fi

if [ "$NDK_COMPLETE" = false ]; then
    # Remove any incomplete extraction
    rm -rf "$NDK_INSTALL_DIR"

    # NDK r16b full size is ~822MB; use -c to resume partial downloads
    NDK_FULL_SIZE=861630238  # bytes
    EXISTING_SIZE=0
    if [ -f "$TMP_NDK" ]; then
        EXISTING_SIZE=$(wc -c < "$TMP_NDK" 2>/dev/null || echo 0)
    fi

    if [ "$EXISTING_SIZE" -lt "$NDK_FULL_SIZE" ]; then
        echo ">>> [4/4] Downloading Android NDK r16b (resuming if partial)..."
        wget -q --show-progress -c -O "$TMP_NDK" "$NDK_URL"
    else
        echo ">>> [4/4] NDK r16b zip already downloaded. Extracting..."
    fi

    echo ">>> Extracting NDK r16b (this takes ~2 minutes)..."
    EXTRACT_DIR="$(dirname "$NDK_INSTALL_DIR")/ndk-extract-tmp"
    rm -rf "$EXTRACT_DIR"
    mkdir -p "$EXTRACT_DIR"
    unzip -q "$TMP_NDK" -d "$EXTRACT_DIR"
    mv "$EXTRACT_DIR/android-ndk-r16b" "$NDK_INSTALL_DIR"
    rm -rf "$EXTRACT_DIR"
    rm -f "$TMP_NDK"
    echo "    Done."
else
    echo ">>> [4/4] NDK r16b already installed at $NDK_INSTALL_DIR. Skipping."
fi

# ─── 6. Write local.properties for Gradle ────────────────────────────────────
echo ">>> Writing VirtualApp/local.properties..."
cat > "VirtualApp/local.properties" <<EOF
sdk.dir=$ANDROID_HOME
ndk.dir=$NDK_INSTALL_DIR
EOF
echo "    Done."

# ─── 7. Ensure gradlew is executable ─────────────────────────────────────────
chmod +x VirtualApp/gradlew

echo ""
echo "========================================"
echo "  Setup Complete!"
echo "========================================"
echo "  SDK  : $ANDROID_HOME"
echo "  NDK  : $NDK_INSTALL_DIR"
echo ""
echo "  To build the APK:"
echo "    cd VirtualApp && ./gradlew assembleDebug"
echo ""
