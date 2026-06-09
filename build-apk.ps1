param(
  [ValidateSet("debug", "release")]
  [string]$Variant = "release",

  [switch]$Clean,

  [switch]$Install,

  [switch]$ClearData,

  [switch]$UninstallFirst,

  [string]$DeviceSerial
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

function Get-WorktreeFallbackRepoRoot {
  $worktreeParent = Split-Path -Parent $projectRoot
  if ((Split-Path -Leaf $worktreeParent) -ne ".codex-worktrees") {
    return $null
  }
  $fallbackRepoRoot = Split-Path -Parent $worktreeParent
  if ([string]::IsNullOrWhiteSpace($fallbackRepoRoot)) {
    return $null
  }
  return $fallbackRepoRoot
}

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

function Get-LocalPropertyValue {
  param([string]$Key)

  $propertyFiles = @(
    (Join-Path $projectRoot "local.properties"),
    (Join-Path $projectRoot "flutter_app\\android\\local.properties")
  )
  $escapedKey = [Regex]::Escape($Key)

  foreach ($propertiesFile in $propertyFiles) {
    if (-not (Test-Path $propertiesFile)) {
      continue
    }
    foreach ($line in Get-Content -Path $propertiesFile -ErrorAction SilentlyContinue) {
      if ($line -match "^\s*$escapedKey=(.*)$") {
        return [Regex]::Unescape($Matches[1].Trim())
      }
    }
  }

  return $null
}

function Get-LocalPropertyValues {
  param([string]$Key)

  $propertyFiles = @(
    (Join-Path $projectRoot "local.properties"),
    (Join-Path $projectRoot "flutter_app\\android\\local.properties")
  )
  $escapedKey = [Regex]::Escape($Key)
  $values = New-Object System.Collections.Generic.List[string]

  foreach ($propertiesFile in $propertyFiles) {
    if (-not (Test-Path $propertiesFile)) {
      continue
    }
    foreach ($line in Get-Content -Path $propertiesFile -ErrorAction SilentlyContinue) {
      if ($line -match "^\s*$escapedKey=(.*)$") {
        $value = [Regex]::Unescape($Matches[1].Trim())
        if (-not [string]::IsNullOrWhiteSpace($value) -and -not $values.Contains($value)) {
          $values.Add($value)
        }
      }
    }
  }

  return $values
}

function Convert-ToLocalPropertiesValue {
  param([string]$Value)

  return $Value -replace "\\", "\\\\"
}

function Set-LocalPropertyValue {
  param(
    [string]$FilePath,
    [string]$Key,
    [string]$Value
  )

  $parentDir = Split-Path -Parent $FilePath
  Ensure-Directory -Path $parentDir

  $escapedKey = [Regex]::Escape($Key)
  $encodedValue = Convert-ToLocalPropertiesValue -Value $Value
  $lines = @()
  if (Test-Path $FilePath) {
    $lines = @(Get-Content -Path $FilePath -ErrorAction SilentlyContinue)
  }

  $updated = $false
  for ($index = 0; $index -lt $lines.Count; $index++) {
    if ($lines[$index] -match "^\s*$escapedKey=") {
      $lines[$index] = "$Key=$encodedValue"
      $updated = $true
    }
  }

  if (-not $updated) {
    $lines += "$Key=$encodedValue"
  }

  Set-Content -Path $FilePath -Value $lines
}

function Test-AndroidSdkRoot {
  param(
    [string]$Path,
    [switch]$RequireNdk
  )

  $resolvedPath = if ($null -eq $Path) { $null } else { $Path.Trim() }
  if ([string]::IsNullOrWhiteSpace($resolvedPath) -or -not (Test-Path $resolvedPath)) {
    return $false
  }

  $hasSdkMarkers =
    (Test-Path (Join-Path $resolvedPath "platform-tools")) -or
    (Test-Path (Join-Path $resolvedPath "platforms"))
  if (-not $hasSdkMarkers) {
    return $false
  }

  if (-not $RequireNdk) {
    return $true
  }

  $ndkRoot = Join-Path $resolvedPath "ndk"
  return (Test-Path $ndkRoot) -and
    $null -ne (Get-ChildItem -Path $ndkRoot -Directory -ErrorAction SilentlyContinue | Select-Object -First 1)
}

function Get-PreferredAndroidSdkRoot {
  $candidates = New-Object System.Collections.Generic.List[string]

  foreach ($candidate in @(
    $env:ANDROID_SDK_ROOT,
    $env:ANDROID_HOME
  )) {
    if (-not [string]::IsNullOrWhiteSpace($candidate) -and -not $candidates.Contains($candidate)) {
      $candidates.Add($candidate)
    }
  }

  foreach ($candidate in Get-LocalPropertyValues -Key "sdk.dir") {
    if (-not $candidates.Contains($candidate)) {
      $candidates.Add($candidate)
    }
  }

  foreach ($candidate in $candidates) {
    if (Test-AndroidSdkRoot -Path $candidate -RequireNdk) {
      return $candidate
    }
  }

  foreach ($candidate in $candidates) {
    if (Test-AndroidSdkRoot -Path $candidate) {
      return $candidate
    }
  }

  return $null
}

function Get-FlutterSdkRootFromCommand {
  param([string]$FlutterCommand)

  $resolvedCommandResult = Resolve-Path -LiteralPath $FlutterCommand -ErrorAction SilentlyContinue
  $resolvedCommand = if ($null -ne $resolvedCommandResult) { $resolvedCommandResult.Path } else { $null }
  if ([string]::IsNullOrWhiteSpace($resolvedCommand)) {
    return $null
  }

  $binDir = Split-Path -Parent $resolvedCommand
  if ([string]::IsNullOrWhiteSpace($binDir)) {
    return $null
  }

  return Split-Path -Parent $binDir
}

function Sync-FlutterAndroidLocalProperties {
  param([string]$FlutterCommand)

  $androidSdkRoot = Get-PreferredAndroidSdkRoot
  if ([string]::IsNullOrWhiteSpace($androidSdkRoot)) {
    throw "Android SDK root not found. Set ANDROID_SDK_ROOT/ANDROID_HOME or fix local.properties."
  }

  $env:ANDROID_SDK_ROOT = $androidSdkRoot
  $env:ANDROID_HOME = $androidSdkRoot

  $flutterLocalProperties = Join-Path $projectRoot "flutter_app\\android\\local.properties"
  Set-LocalPropertyValue -FilePath $flutterLocalProperties -Key "sdk.dir" -Value $androidSdkRoot

  $flutterSdkRoot = Get-FlutterSdkRootFromCommand -FlutterCommand $FlutterCommand
  if (-not [string]::IsNullOrWhiteSpace($flutterSdkRoot)) {
    Set-LocalPropertyValue -FilePath $flutterLocalProperties -Key "flutter.sdk" -Value $flutterSdkRoot
  }
}

function Convert-ToWslPath {
  param([string]$Path)

  $resolvedPath = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
  if ($resolvedPath -notmatch '^(?<drive>[A-Za-z]):\\(?<rest>.*)$') {
    throw "Only Windows drive paths are supported for WSL conversion: $resolvedPath"
  }

  $drive = $Matches["drive"].ToLowerInvariant()
  $rest = $Matches["rest"] -replace "\\", "/"
  if ([string]::IsNullOrWhiteSpace($rest)) {
    return "/mnt/$drive"
  }

  return "/mnt/$drive/$rest"
}

function Get-EmbeddedPythonDistDirectories {
  $candidates = New-Object System.Collections.Generic.List[string]

  $primaryDistDir = Join-Path $projectRoot "tools/android_python_runtime_p4a/dist"
  $candidates.Add($primaryDistDir)

  $fallbackRepoRoot = Get-WorktreeFallbackRepoRoot
  if ($fallbackRepoRoot) {
    $fallbackDistDir = Join-Path $fallbackRepoRoot "tools/android_python_runtime_p4a/dist"
    if (-not $candidates.Contains($fallbackDistDir)) {
      $candidates.Add($fallbackDistDir)
    }
  }

  return $candidates
}

function Ensure-LocalGradleDistributionZip {
  $localGradleZip = Join-Path $projectRoot "gradle-8.13-bin.zip"
  if (Test-Path $localGradleZip) {
    return
  }

  $fallbackRepoRoot = Get-WorktreeFallbackRepoRoot
  if (-not $fallbackRepoRoot) {
    return
  }

  $fallbackGradleZip = Join-Path $fallbackRepoRoot "gradle-8.13-bin.zip"
  if (-not (Test-Path $fallbackGradleZip)) {
    return
  }

  Write-Step "Seeding local Gradle distribution"
  Copy-Item -Path $fallbackGradleZip -Destination $localGradleZip -Force
}

function Get-EmbeddedPythonArtifacts {
  foreach ($distDir in Get-EmbeddedPythonDistDirectories) {
    if (-not (Test-Path $distDir)) {
      continue
    }
    $artifacts = Get-ChildItem -Path $distDir -Filter "*.aar" -File -ErrorAction SilentlyContinue |
      Sort-Object LastWriteTime -Descending
    if ($artifacts) {
      return $artifacts
    }
  }
  return @()
}

function Assert-EmbeddedPythonRuntimeExists {
  $existingArtifacts = Get-EmbeddedPythonArtifacts
  if ($existingArtifacts) {
    Write-Step "Using embedded Python runtime"
    Write-Host "Runtime AAR: $($existingArtifacts[0].FullName)" -ForegroundColor Green
    return
  }

  $distDirs = Get-EmbeddedPythonDistDirectories
  $distDir = $distDirs[0]
  $candidateDirs = ($distDirs | ForEach-Object { " - $_" }) -join [Environment]::NewLine
  $projectRootWsl = Convert-ToWslPath -Path $projectRoot
  $wslCommand = "cd $projectRootWsl && ./build-p4a-service-library.sh"
  throw @"
Embedded Python runtime AAR was not found in:
$candidateDirs

Build the p4a runtime in WSL first, then rerun this script.
Suggested command:
wsl.exe -- bash -lc "$wslCommand"
"@
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

function Get-AdbCommand {
  $pathAdb = Get-Command adb -ErrorAction SilentlyContinue
  if ($pathAdb) {
    return $pathAdb.Source
  }

  $sdkCandidates = @(
    $env:ANDROID_SDK_ROOT,
    $env:ANDROID_HOME,
    (Get-LocalPropertyValue -Key "sdk.dir")
  ) | Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique

  foreach ($sdkRoot in $sdkCandidates) {
    $adbPath = Join-Path $sdkRoot "platform-tools\\adb.exe"
    if (Test-Path $adbPath) {
      return $adbPath
    }
  }

  throw "adb command not found. Please install Android platform-tools or set ANDROID_SDK_ROOT/ANDROID_HOME."
}

function Invoke-AdbCommand {
  param(
    [string]$AdbCommand,
    [string[]]$Arguments,
    [string]$FailureMessage
  )

  & $AdbCommand @Arguments
  if ($LASTEXITCODE -ne 0) {
    throw "$FailureMessage (exit code $LASTEXITCODE)"
  }
}

if (-not (Test-Path $flutterAppDir)) {
  throw "flutter_app directory not found: $flutterAppDir"
}

if ($ClearData -and $UninstallFirst) {
  throw "-ClearData and -UninstallFirst are mutually exclusive. Choose one reset mode."
}

if (($ClearData -or $UninstallFirst) -and -not $Install) {
  throw "-ClearData and -UninstallFirst require -Install."
}

Assert-EmbeddedPythonRuntimeExists
Ensure-LocalGradleDistributionZip
$flutterCommand = Get-FlutterCommand
Sync-FlutterAndroidLocalProperties -FlutterCommand $flutterCommand

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

if ($Install) {
  $adbCommand = Get-AdbCommand
  $adbTargetArgs = @()
  if (-not [string]::IsNullOrWhiteSpace($DeviceSerial)) {
    $adbTargetArgs += @("-s", $DeviceSerial.Trim())
  }

  if ($UninstallFirst) {
    Write-Step "Uninstalling existing app"
    Invoke-AdbCommand `
      -AdbCommand $adbCommand `
      -Arguments ($adbTargetArgs + @("uninstall", "org.opencray.app")) `
      -FailureMessage "adb uninstall failed"
  } elseif ($ClearData) {
    Write-Step "Clearing existing app data"
    Invoke-AdbCommand `
      -AdbCommand $adbCommand `
      -Arguments ($adbTargetArgs + @("shell", "pm", "clear", "org.opencray.app")) `
      -FailureMessage "adb shell pm clear failed"
  }

  Write-Step "Installing APK"
  Invoke-AdbCommand `
    -AdbCommand $adbCommand `
    -Arguments ($adbTargetArgs + @("install", "-r", $artifactPath)) `
    -FailureMessage "adb install failed"
}

Write-Step "Build complete"
Write-Host "Source APK: $($primaryApk.FullName)" -ForegroundColor Green
Write-Host "Copied APK: $artifactPath" -ForegroundColor Green
if ($Install) {
  Write-Host "Installed package: org.opencray.app" -ForegroundColor Green
  if (-not [string]::IsNullOrWhiteSpace($DeviceSerial)) {
    Write-Host "Target device: $($DeviceSerial.Trim())" -ForegroundColor Green
  }
}
