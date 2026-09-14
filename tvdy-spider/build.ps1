# ============================================================
#  TvDy jar spider builder  (www.tvdy.xyz)
#  Output: dist\TvDy.jar  (contains classes.dex for TVBox)
#  Need  : JDK 8+ (javac/jar/java). org.json & d8(r8) are
#          downloaded automatically into build\lib on first run.
# ============================================================
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $root

# ---------- locate JDK ----------
$javacCmd = Get-Command javac.exe -ErrorAction SilentlyContinue
if (-not $javacCmd) { throw "javac.exe not found. Please install JDK 8+ and add it to PATH." }
$javac = $javacCmd.Source
$javaHome = Split-Path (Split-Path $javac -Parent) -Parent
$java = Join-Path $javaHome "bin\java.exe"
$jarTool = Join-Path $javaHome "bin\jar.exe"
Write-Host "JDK: $javaHome" -ForegroundColor Cyan

# ---------- directories ----------
$build = Join-Path $root "build"
$lib = Join-Path $build "lib"
$stubCls = Join-Path $build "stub-classes"
$appCls = Join-Path $build "classes"
$aliasCls = Join-Path $build "alias-classes"
$dexOut = Join-Path $build "dex"
$dist = Join-Path $root "dist"
New-Item -ItemType Directory -Force -Path $lib | Out-Null
foreach ($d in @($stubCls, $appCls, $aliasCls, $dexOut, $dist)) {
    if (Test-Path $d) { Remove-Item $d -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

# ---------- dependencies ----------
$jsonJar = Join-Path $lib "json-20231013.jar"
if (-not (Test-Path $jsonJar)) {
    Write-Host "Downloading org.json ..." -ForegroundColor Cyan
    Invoke-WebRequest -UseBasicParsing "https://repo1.maven.org/maven2/org/json/json/20231013/json-20231013.jar" -OutFile $jsonJar
}
$R8 = "8.2.42"
$r8Jar = Join-Path $lib "r8-$R8.jar"
if (-not (Test-Path $r8Jar)) {
    Write-Host "Downloading d8 (r8 $R8) ..." -ForegroundColor Cyan
    Invoke-WebRequest -UseBasicParsing "https://dl.google.com/dl/android/maven2/com/android/tools/r8/$R8/r8-$R8.jar" -OutFile $r8Jar
}

# ---------- 1) compile stubs (compile-time only) ----------
Write-Host "[1/4] Compiling stub classes ..." -ForegroundColor Green
$stubSrc = @(Get-ChildItem -Path (Join-Path $root "stubs") -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& $javac -nowarn -encoding UTF-8 -source 8 -target 8 -d $stubCls $stubSrc
if ($LASTEXITCODE -ne 0) { throw "stub compile failed" }
$stubsJar = Join-Path $lib "stubs.jar"
& $jarTool cf $stubsJar -C $stubCls .

# ---------- 2) compile spider ----------
# NOTE: main classes and the "Tvdy" alias are compiled into SEPARATE output dirs,
# because Windows filesystems are case-insensitive and TvDy.class / Tvdy.class
# would otherwise overwrite each other. The classes are merged later in the jar.
Write-Host "[2/4] Compiling spider source ..." -ForegroundColor Green
$appSrc = @(Get-ChildItem -Path (Join-Path $root "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName })
& $javac -nowarn -encoding UTF-8 -source 8 -target 8 -cp "$stubCls;$jsonJar" -d $appCls $appSrc
if ($LASTEXITCODE -ne 0) { throw "spider compile failed" }

$aliasSrc = @(Get-ChildItem -Path (Join-Path $root "src-alias") -Recurse -Filter *.java | ForEach-Object { $_.FullName })
if ($aliasSrc.Count -gt 0) {
    Write-Host "      Compiling case-alias classes ..." -ForegroundColor Green
    & $javac -nowarn -encoding UTF-8 -source 8 -target 8 -cp "$stubCls;$jsonJar;$appCls" -d $aliasCls $aliasSrc
    if ($LASTEXITCODE -ne 0) { throw "alias compile failed" }
}

# ---------- 3) dex ----------
Write-Host "[3/4] Converting to classes.dex ..." -ForegroundColor Green
$appJar = Join-Path $lib "app.jar"
& $jarTool cf $appJar -C $appCls . -C $aliasCls .
& $java -cp $r8Jar com.android.tools.r8.D8 --min-api 19 --lib $stubsJar --lib $jsonJar --output $dexOut $appJar
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

# ---------- 4) package jar ----------
Write-Host "[4/4] Packaging jar ..." -ForegroundColor Green
$outJar = Join-Path $dist "TvDy.jar"
& $jarTool cf $outJar -C $dexOut "classes.dex"
if ($LASTEXITCODE -ne 0) { throw "package failed" }

Write-Host ""
Write-Host "DONE => $outJar" -ForegroundColor Yellow
Write-Host ("size : {0} bytes" -f (Get-Item $outJar).Length) -ForegroundColor Yellow
Write-Host ""
Write-Host "Config sample:" -ForegroundColor Cyan
Write-Host '  {"key":"tvdy","name":"tvdy","type":3,"api":"csp_TvDy","searchable":1,"quickSearch":1,"filterable":1,"jar":"./TvDy.jar"}'
Write-Host '  classes: csp_TvDy (TvDy) / csp_Tvdy (Tvdy alias)'
