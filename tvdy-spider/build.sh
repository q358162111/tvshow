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
TOOLSCLS="$BUILD/tools-classes"
EXTDIR="$BUILD/external"
DEXOUT="$BUILD/dex"
DIST="$ROOT/dist"

mkdir -p "$LIB" "$DEXOUT" "$DIST"
rm -rf "$STUBCLS" "$APPCLS" "$ALIASCLS" "$TOOLSCLS" "$EXTDIR" "$DEXOUT"/*
mkdir -p "$STUBCLS" "$APPCLS" "$ALIASCLS" "$TOOLSCLS" "$DEXOUT"

# deps
JSON="$LIB/json-20231013.jar"
if [[ ! -f "$JSON" ]]; then
    echo "[deps] downloading org.json ..."
    curl -sSf -o "$JSON" https://repo1.maven.org/maven2/org/json/json/20231013/json-20231013.jar
fi

# ---------------------------------------------------------------------------
# 0) 合并外部通用 spider jar（提供 AppRJ / SaoHuo / Proxy / jsoup 等实现）
#    与本地重名的类（Init / SaoHuo）保留本地版本，从外部 dex 中剔除
#    产物缓存于 build/lib/external-filtered.dex，删除该文件可强制重新生成
# ---------------------------------------------------------------------------
EXT_URL="https://ncstatic-file.clewm.net/rsrc/2026/0406/21/5b2f258907bc956ee3538c9f5cdf4b83.jpg"
EXT_DEX="$LIB/external-filtered.dex"

if [[ ! -f "$EXT_DEX" ]]; then
    echo "[0/5] preparing external spider dex ..."
    DL="$LIB/dexlib2-2.5.2.jar"
    UT="$LIB/util-2.5.2.jar"
    GV="$LIB/guava-31.1-jre.jar"
    [[ -f "$DL" ]] || curl -sSf -o "$DL" https://repo1.maven.org/maven2/org/smali/dexlib2/2.5.2/dexlib2-2.5.2.jar
    [[ -f "$UT" ]] || curl -sSf -o "$UT" https://repo1.maven.org/maven2/org/smali/util/2.5.2/util-2.5.2.jar
    [[ -f "$GV" ]] || curl -sSf -o "$GV" https://repo1.maven.org/maven2/com/google/guava/guava/31.1-jre/guava-31.1-jre.jar
    rm -rf "$EXTDIR"
    mkdir -p "$EXTDIR"
    curl -sSf -o "$EXTDIR/external.jar" "$EXT_URL"
    ( cd "$EXTDIR" && unzip -o -q external.jar classes.dex )
    "$JAVAC" -nowarn -cp "$DL:$UT:$GV" -d "$TOOLSCLS" tools/DexFilter.java
    java -cp "$TOOLSCLS:$DL:$UT:$GV" DexFilter "$EXTDIR/classes.dex" "$EXT_DEX" \
        'Lcom/github/catvod/spider/Init;' \
        'Lcom/github/catvod/spider/Init$1;' \
        'Lcom/github/catvod/spider/Init$2;' \
        'Lcom/github/catvod/spider/Init$3;' \
        'Lcom/github/catvod/spider/Init$Loader;' \
        'Lcom/github/catvod/spider/Init$UP;' \
        'Lcom/github/catvod/spider/SaoHuo;'
fi

# 1) compile stubs
echo "[1/5] compiling stubs ..."
STUBSRC=( $(find stubs -name "*.java" -type f) )
"$JAVAC" -nowarn -encoding UTF-8 -source 8 -target 8 -d "$STUBCLS" "${STUBSRC[@]}"
"$JAR" cf "$LIB/stubs.jar" -C "$STUBCLS" .

# 2) compile spider
echo "[2/5] compiling spider ..."
APPSRC=( $(find src -name "*.java" -type f) )
"$JAVAC" -nowarn -encoding UTF-8 -source 8 -target 8 -cp "$STUBCLS:$JSON" -d "$APPCLS" "${APPSRC[@]}"

ALIASSRC=( $(find src-alias -name "*.java" -type f) )
if [[ ${#ALIASSRC[@]} -gt 0 ]]; then
    echo "      compiling alias ..."
    "$JAVAC" -nowarn -encoding UTF-8 -source 8 -target 8 -cp "$STUBCLS:$JSON:$APPCLS" -d "$ALIASCLS" "${ALIASSRC[@]}"
fi

# 3) dex：本地爬虫 + 已过滤的外部通用 dex，合并为单个 classes.dex
echo "[3/5] d8 merge ..."
"$JAR" cf "$LIB/app.jar" -C "$APPCLS" . -C "$ALIASCLS" .
"$D8" --min-api 19 --lib "$LIB/stubs.jar" --lib "$JSON" --output "$DEXOUT" "$LIB/app.jar" "$EXT_DEX"

# 4) package jar（写入固定时间戳，保证 classes.dex 不变时 jar 的 md5 可复现）
echo "[4/5] packaging jar ..."
OUT="$DIST/TvDy.jar"
rm -f "$OUT"
if command -v python3 >/dev/null 2>&1; then
    python3 - "$OUT" "$DEXOUT/classes.dex" <<'PY'
import sys, zipfile
out, dex = sys.argv[1], sys.argv[2]
ts = (1980, 1, 1, 0, 0, 0)
def entry(name):
    zi = zipfile.ZipInfo(name, ts)
    zi.compress_type = zipfile.ZIP_DEFLATED
    zi.external_attr = 0o644 << 16
    return zi
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr(entry("META-INF/MANIFEST.MF"),
               b"Manifest-Version: 1.0\r\nCreated-By: 1.0 (tvdy-spider)\r\n\r\n")
    with open(dex, "rb") as f:
        z.writestr(entry("classes.dex"), f.read())
PY
else
    "$JAR" cf "$OUT" -C "$DEXOUT" classes.dex
fi

echo
echo "DONE => $OUT"
SIZE=$(stat -f%z "$OUT" 2>/dev/null || stat -c%s "$OUT")
echo "size : $SIZE bytes"
MD5=$(md5 -q "$OUT" 2>/dev/null || md5sum "$OUT" | awk '{print $1}')
echo "md5  : $MD5"
echo
echo "Config sample:"
echo '  {"key":"tvdy","name":"tvdy","type":3,"api":"csp_TvDy","searchable":1,"quickSearch":1,"filterable":1,"jar":"./TvDy.jar;md5;'$MD5'"}'