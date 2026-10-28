#!/bin/bash
# Builds org.fossify:commons:7.1.3-dragon1 in the local Maven repo by
# neutralizing showModdedAppWarning in the official 7.1.3 AAR.
# Usage: patch-commons.sh [path-to-official-commons-7.1.3.aar]
# If no path is given, the Gradle cache is searched, then Maven Central.
set -euo pipefail

OFFICIAL_AAR="${1:-}"
if [ -z "$OFFICIAL_AAR" ]; then
    OFFICIAL_AAR="$(find ~/.gradle/caches/modules-2/files-2.1/org.fossify/commons/7.1.3 -name 'commons-7.1.3.aar' 2>/dev/null | head -1 || true)"
fi
if [ -z "$OFFICIAL_AAR" ]; then
    echo "Official AAR not cached; downloading from Maven Central..."
    mkdir -p /tmp/commons-patch-dl
    OFFICIAL_AAR="/tmp/commons-patch-dl/commons-7.1.3.aar"
    curl -fsSL -o "$OFFICIAL_AAR" \
        "https://repo1.maven.org/maven2/org/fossify/commons/7.1.3/commons-7.1.3.aar"
fi
echo "Official AAR: $OFFICIAL_AAR"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ASM_JAR="$(find ~/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm -name 'asm-9.*.jar' 2>/dev/null | head -1)"
if [ -z "$ASM_JAR" ]; then
    echo "ERROR: ASM jar not found in Gradle cache" >&2
    exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
unzip -o -q "$OFFICIAL_AAR" -d "$WORK/aar"

javac -cp "$ASM_JAR" -d "$WORK/classes" "$SCRIPT_DIR/PatchCommons.java"
java -cp "$WORK/classes:$ASM_JAR" PatchCommons \
    "$WORK/aar/classes.jar" "$WORK/classes-patched.jar"

# Verify: the patched method must be a bare return, no dialog construction.
javap -c -p -cp "$WORK/classes-patched.jar" \
    org.fossify.commons.extensions.ActivityKt > "$WORK/javap.txt" || true
if grep -A8 "showModdedAppWarning" "$WORK/javap.txt" | grep -q "ConfirmationDialog"; then
    echo "ERROR: patched method still references the dialog" >&2
    exit 1
fi
echo "Verified: showModdedAppWarning neutralized in ActivityKt"

# Rebuild the AAR with the patched classes.jar swapped in.
mkdir -p "$WORK/repack" && cp -r "$WORK/aar/"* "$WORK/repack/" 2>/dev/null || true
cp "$WORK/classes-patched.jar" "$WORK/repack/classes.jar"
cd "$WORK/repack" && zip -q -r "$WORK/commons-7.1.3-dragon1.aar" . && cd - >/dev/null

DEST="$HOME/.m2/repository/org/fossify/commons/7.1.3-dragon1"
mkdir -p "$DEST"
cp "$WORK/commons-7.1.3-dragon1.aar" "$DEST/"
# POM MUST carry the official artifact's full dependency list: a minimal
# POM drops Commons' transitive deps (material, appcompat, ...) and the
# app build then fails resource linking. So copy the official POM and
# change only its own version.
OFFICIAL_POM="$(find ~/.gradle/caches/modules-2/files-2.1/org.fossify/commons/7.1.3 -name 'commons-7.1.3.pom' 2>/dev/null | head -1 || true)"
if [ -z "$OFFICIAL_POM" ]; then
    curl -fsSL -o "$WORK/commons-7.1.3.pom" \
        "https://repo1.maven.org/maven2/org/fossify/commons/7.1.3/commons-7.1.3.pom"
    OFFICIAL_POM="$WORK/commons-7.1.3.pom"
fi
sed 's|<version>7.1.3</version>|<version>7.1.3-dragon1</version>|' \
    "$OFFICIAL_POM" > "$DEST/commons-7.1.3-dragon1.pom"
echo "Published: $DEST/commons-7.1.3-dragon1.aar (POM with official dependencies)"
