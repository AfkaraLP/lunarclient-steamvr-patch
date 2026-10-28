#!/usr/bin/env bash
# Builds dist/lcvr.jar (Java agent, written in Kotlin) and dist/liblcvr.so (OpenVR bridge).
# Needs kotlinc (2.x), a JDK 17+ (java + JNI headers) and g++.
# On NixOS:  nix shell nixpkgs#kotlin nixpkgs#jdk17 nixpkgs#gcc -c ./build.sh  (or: nix build)
set -euo pipefail
cd "$(dirname "$0")"

ASM_VER=9.7.1
OPENVR_VER=2.15.6
TP=third_party
mkdir -p "$TP" build dist

# Dependencies are downloaded into third_party/ unless provided. The Nix flake passes them
# in, since Nix builds have no network: ASM_JAR, ASM_COMMONS_JAR, OPENVR_SDK.
fetch() { [ -f "$2" ] || curl -sSfL -o "$2" "$1"; }
maven=https://repo1.maven.org/maven2/org/ow2/asm
if [ -z "${ASM_JAR:-}" ]; then
  ASM_JAR="$TP/asm-$ASM_VER.jar"
  fetch "$maven/asm/$ASM_VER/asm-$ASM_VER.jar" "$ASM_JAR"
fi
if [ -z "${ASM_COMMONS_JAR:-}" ]; then
  ASM_COMMONS_JAR="$TP/asm-commons-$ASM_VER.jar"
  fetch "$maven/asm-commons/$ASM_VER/asm-commons-$ASM_VER.jar" "$ASM_COMMONS_JAR"
fi
if [ -z "${OPENVR_SDK:-}" ]; then
  OPENVR_SDK="$TP/openvr-$OPENVR_VER"
  [ -d "$OPENVR_SDK" ] || curl -sSfL "https://github.com/ValveSoftware/openvr/archive/refs/tags/v$OPENVR_VER.tar.gz" | tar xz -C "$TP"
fi

JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
# the stdlib that ships with kotlinc, so the bundled copy matches what the code was compiled against
KOTLIN_STDLIB="${KOTLIN_STDLIB:-$(dirname "$(readlink -f "$(command -v kotlinc)")")/../lib/kotlin-stdlib.jar}"
[ -f "$KOTLIN_STDLIB" ] || { echo "kotlin-stdlib.jar not found; set KOTLIN_STDLIB" >&2; exit 1; }
ASM_CP="$ASM_JAR:$ASM_COMMONS_JAR"

echo "== agent (kotlin)"
rm -rf build/classes build/tools
# Lunar runs 1.8.9 on Java 17: target its bytecode and API
kotlinc -jvm-target 17 -Xjdk-release=17 -no-reflect -cp "$ASM_JAR" -d build/classes src/lcvr/*.kt
kotlinc -jvm-target 17 -no-reflect -cp "$ASM_CP" -d build/tools tools/Shade.kt
java -cp "build/tools:$ASM_CP:$KOTLIN_STDLIB" ShadeKt dist/lcvr.jar build/classes "$ASM_JAR" "$KOTLIN_STDLIB"

echo "== native bridge"
# install, not cp: the SDK may come read-only from the Nix store
install -m 0755 "$OPENVR_SDK/bin/linux64/libopenvr_api.so" dist/libopenvr_api.so
g++ -std=c++17 -O2 -shared -fPIC -fvisibility=hidden \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" -I"$OPENVR_SDK/headers" \
  native/lcvr.cpp -o dist/liblcvr.so \
  -Ldist -lopenvr_api -ldl -Wl,-rpath,'$ORIGIN' -static-libstdc++ -static-libgcc
# the JNI symbols stay visible despite -fvisibility=hidden (JNIEXPORT sets default visibility)

echo "built: $(ls dist)"
