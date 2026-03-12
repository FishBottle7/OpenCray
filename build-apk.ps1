param(
  [ValidateSet("debug", "release")]
  [string]$Variant = "debug",

  [switch]$Clean
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectRoot

$gradleUserHome = Join-Path $projectRoot ".gradle-user-home"
$androidUserHome = Join-Path $projectRoot ".android-user-home"
$localGradleRoot = Join-Path $gradleUserHome "local-gradle"
$bundledGradleZip = Join-Path $projectRoot "gradle-8.13-bin.zip"
$gradleLauncher = Join-Path $localGradleRoot "gradle-8.13\\bin\\gradle.bat"

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

function Get-GradleCommand {
  Ensure-Directory -Path $gradleUserHome

  if (Test-Path $bundledGradleZip) {
    if (-not (Test-Path $gradleLauncher)) {
      Write-Step "Extracting bundled Gradle 8.13"
      Ensure-Directory -Path $localGradleRoot
      Expand-Archive -Path $bundledGradleZip -DestinationPath $localGradleRoot -Force
    }

    return $gradleLauncher
  }

  return Join-Path $projectRoot "gradlew.bat"
}

$env:GRADLE_USER_HOME = $gradleUserHome
Ensure-Directory -Path $androidUserHome
$env:ANDROID_USER_HOME = $androidUserHome
$gradleCommand = Get-GradleCommand
$taskName = ":app:assemble$($Variant.Substring(0, 1).ToUpper() + $Variant.Substring(1))"

if ($Clean) {
  Write-Step "Running clean"
  & $gradleCommand "--no-daemon" "clean"
  if ($LASTEXITCODE -ne 0) {
    throw "Gradle clean failed with exit code $LASTEXITCODE"
  }
}

Write-Step "Building APK ($Variant)"
& $gradleCommand "--no-daemon" $taskName
if ($LASTEXITCODE -ne 0) {
  throw "Gradle build failed with exit code $LASTEXITCODE"
}

$apkOutputDir = Join-Path $projectRoot "app\\build\\outputs\\apk\\$Variant"
$apkFiles = Get-ChildItem -Path $apkOutputDir -Filter "*.apk" -File -ErrorAction Stop |
  Sort-Object LastWriteTime -Descending

if (-not $apkFiles) {
  throw "Build completed, but no APK was found in $apkOutputDir"
}

$primaryApk = $apkFiles[0]
$artifactDir = Join-Path $projectRoot "build\\apk"
Ensure-Directory -Path $artifactDir

$artifactName = "OpenCray-$Variant.apk"
$artifactPath = Join-Path $artifactDir $artifactName
Copy-Item -Path $primaryApk.FullName -Destination $artifactPath -Force

Write-Step "Build complete"
Write-Host "Source APK: $($primaryApk.FullName)" -ForegroundColor Green
Write-Host "Copied APK: $artifactPath" -ForegroundColor Green
