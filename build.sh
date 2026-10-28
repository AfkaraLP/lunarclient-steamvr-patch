#!/usr/bin/env bash
# Builds dist/lcvr.jar (Java agent) and dist/liblcvr.so (OpenVR bridge).
# Needs javac (17+) and g++. On NixOS:  nix shell nixpkgs#jdk17 nixpkgs#gcc -c ./build.sh
set -euo pipefail
cd "$(dirname "$0")"

ASM_VER=9.7.1
OPENVR_VER=2.15.6
TP=third_party
mkdir -p "$TP" build dist

for a in asm asm-commons asm-tree; do
  f="$TP/$a-$ASM_VER.jar"
  [ -f "$f" ] || curl -sSfL -o "$f" "https://repo1.maven.org/maven2/org/ow2/asm/$a/$ASM_VER/$a-$ASM_VER.jar"
done
if [ ! -d "$TP/openvr-$OPENVR_VER" ]; then
  curl -sSfL "https://github.com/ValveSoftware/openvr/archive/refs/tags/v$OPENVR_VER.tar.gz" | tar xz -C "$TP"
fi
OVR="$TP/openvr-$OPENVR_VER"

JAVAC="$(command -v javac)"
JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$JAVAC")")")}"
ASM_CP="$TP/asm-$ASM_VER.jar:$TP/asm-commons-$ASM_VER.jar:$TP/asm-tree-$ASM_VER.jar"

echo "== java agent"
rm -rf build/classes build/tools
javac --release 17 -d build/classes -cp "$TP/asm-$ASM_VER.jar" src/lcvr/*.java
javac --release 17 -d build/tools -cp "$ASM_CP" tools/Shade.java
java -cp "build/tools:$ASM_CP" Shade dist/lcvr.jar build/classes "$TP/asm-$ASM_VER.jar"

echo "== native bridge"
cp "$OVR/bin/linux64/libopenvr_api.so" dist/
g++ -std=c++17 -O2 -shared -fPIC -fvisibility=hidden \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" -I"$OVR/headers" \
  native/lcvr.cpp -o dist/liblcvr.so \
  -Ldist -lopenvr_api -ldl -Wl,-rpath,'$ORIGIN' -static-libstdc++ -static-libgcc
# the JNI symbols must stay visible despite -fvisibility=hidden (JNIEXPORT sets default visibility)

echo "built: $(ls dist)"
