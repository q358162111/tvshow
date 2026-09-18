#!/usr/bin/env bash
# TvDy jar spider builder (macOS / bash 版)
# 用法: ./build.sh
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(pwd)"

JAVAC=javac
JAR=jar
D8="/System/Volumes/Data/Users/caihongjie/Library/Android/sdk/build-tools/35.0.0/d8"

BUILD="$ROOT/build"
LIB="$BUILD/lib"
STUBCLS="$BUILD/stub-classes"
APPCLS="$BUILD/classes"
ALIASCLS="$BUILD/alias-classes"
DEXOUT="$BUILD/dex"
DIST="$ROOT/dist"

mkdir -p "$LIB" "$STUBCLS" "$APPCLS" "$ALIASCLS" "$DEXOUT" "$DIST"
rm -rf "$STUBCLS" "$APPCLS" "$ALIASCLS" "$DEXOUT"/*
mkdir -p "$STUBCLS" "$APPCLS" "$ALIASCLS" "$DEXOUT"

# deps
JSON="$LIB/json-20231013.jar"
if [[ ! -f "$JSON" ]]; then
    echo "[deps] downloading org.json ..."
    curl -sSf -o "$JSON" https://repo1.maven.org/maven2/org/json/json/20231013/json-20231013.jar
fi

# 1) compile stubs
echo "[1/4] compiling stubs ..."
STUBSRC=( $(find stubs -name "*.java" -type f) )
"$JAVAC" -nowarn -encoding UTF-8 -source 8 -target 8 -d "$STUBCLS" "${STUBSRC[@]}"
"$JAR" cf "$LIB/stubs.jar" -C "$STUBCLS" .

# 2) compile spider
echo "[2/4] compiling spider ..."
APPSRC=( $(find src -name "*.java" -type f) )
"$JAVAC" -nowarn -encoding UTF-8 -source 8 -target 8 -cp "$STUBCLS:$JSON" -d "$APPCLS" "${APPSRC[@]}"

ALIASSRC=( $(find src-alias -name "*.java" -type f) )
if [[ ${#ALIASSRC[@]} -gt 0 ]]; then
    echo "      compiling alias ..."
    "$JAVAC" -nowarn -encoding UTF-8 -source 8 -target 8 -cp "$STUBCLS:$JSON:$APPCLS" -d "$ALIASCLS" "${ALIASSRC[@]}"
fi

# 3) dex
echo "[3/4] d8 ..."
"$JAR" cf "$LIB/app.jar" -C "$APPCLS" . -C "$ALIASCLS" .
"$D8" --min-api 19 --lib "$LIB/stubs.jar" --lib "$JSON" --output "$DEXOUT" "$LIB/app.jar"

# 4) package jar
echo "[4/4] packaging jar ..."
OUT="$DIST/TvDy.jar"
"$JAR" cf "$OUT" -C "$DEXOUT" classes.dex

echo
echo "DONE => $OUT"
SIZE=$(stat -f%z "$OUT" 2>/dev/null || stat -c%s "$OUT")
echo "size : $SIZE bytes"
MD5=$(md5 -q "$OUT" 2>/dev/null || md5sum "$OUT" | awk '{print $1}')
echo "md5  : $MD5"
echo
echo "Config sample:"
echo '  {"key":"tvdy","name":"tvdy","type":3,"api":"csp_TvDy","searchable":1,"quickSearch":1,"filterable":1,"jar":"./TvDy.jar;md5;'$MD5'"}'