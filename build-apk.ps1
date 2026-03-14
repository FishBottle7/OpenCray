param(
  [ValidateSet("debug", "release")]
  [string]$Variant = "release",

  [switch]$Clean
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectRoot

$flutterAppDir = Join-Path $projectRoot "flutter_app"
$flutterBat = $null
if ($env:FLUTTER_ROOT) {
  $flutterBat = Join-Path $env:FLUTTER_ROOT "bin\\flutter.bat"
}
$fallbackFlutterBat = "D:\\Program Files\\flutter\\bin\\flutter.bat"

function Write-Step {
  param([string]$Message)
  Write-Host ""
  Write-Host "==> $Message" -ForegroundColor Cyan
}

function Ensure-Directory {
  param([string]$Path)
  if (-not (Test-Path $Path)) {
    New-Item -ItemType Directory -Path $Path | Out-Null
  }
}

function Get-FlutterCommand {
  $pathFlutter = Get-Command flutter -ErrorAction SilentlyContinue
  if ($pathFlutter) {
    return $pathFlutter.Source
  }

  if ($env:FLUTTER_ROOT -and (Test-Path $flutterBat)) {
    return $flutterBat
  }

  if (Test-Path $fallbackFlutterBat) {
    return $fallbackFlutterBat
  }

  throw "Flutter command not found. Please install Flutter or set FLUTTER_ROOT."
}

if (-not (Test-Path $flutterAppDir)) {
  throw "flutter_app directory not found: $flutterAppDir"
}

$buildType = switch ($Variant) {
  "debug" { "Debug" }
  "release" { "Release" }
  default { throw "Unsupported variant: $Variant" }
}

$flutterCommand = Get-FlutterCommand
Push-Location $flutterAppDir
try {
  if ($Clean) {
    Write-Step "Running Flutter clean"
    & $flutterCommand "clean" "--suppress-analytics"
    if ($LASTEXITCODE -ne 0) {
      throw "Flutter clean failed with exit code $LASTEXITCODE"
    }
  }

  Write-Step "Fetching Flutter dependencies"
  & $flutterCommand "pub" "get" "--suppress-analytics"
  if ($LASTEXITCODE -ne 0) {
    throw "flutter pub get failed with exit code $LASTEXITCODE"
  }
  
  Write-Step "Building Flutter APK ($Variant)"
  & $flutterCommand "build" "apk" "--$Variant" "--target-platform" "android-arm64" "--suppress-analytics"
  if ($LASTEXITCODE -ne 0) {
    throw "Flutter build failed with exit code $LASTEXITCODE"
  }

  $apkDir = Join-Path $flutterAppDir "build\\app\\outputs\\flutter-apk"
  if (-not (Test-Path $apkDir)) {
    throw "Build completed, but no Flutter APK directory was found: $apkDir"
  }

  $apkFiles = Get-ChildItem -Path $apkDir -Filter "*.apk" -File -ErrorAction Stop |
    Sort-Object LastWriteTime -Descending
  if (-not $apkFiles) {
    throw "Build completed, but no Flutter APK was found in $apkDir."
  }

  $primaryApk = $apkFiles[0]
}
finally {
  Pop-Location
}

$artifactDir = Join-Path $projectRoot "build\\apk"
Ensure-Directory -Path $artifactDir

$artifactName = "OpenCray-$Variant.apk"
$artifactPath = Join-Path $artifactDir $artifactName
Copy-Item -Path $primaryApk.FullName -Destination $artifactPath -Force

Write-Step "Build complete"
Write-Host "Source APK: $($primaryApk.FullName)" -ForegroundColor Green
Write-Host "Copied APK: $artifactPath" -ForegroundColor Green
