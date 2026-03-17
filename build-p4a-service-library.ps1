param(
  [string]$WslDistro,
  [string]$P4aRequirements = "python3",
  [string]$P4aArch = "arm64-v8a",
  [switch]$SkipP4aUpgrade
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectRoot

function Write-Step {
  param([string]$Message)
  Write-Host ""
  Write-Host "==> $Message" -ForegroundColor Cyan
}

function Convert-ToWslPath {
  param(
    [Parameter(Mandatory = $true)]
    [string]$Path,

    [switch]$AllowMissing
  )

  $resolvedPath = if ($AllowMissing) {
    [System.IO.Path]::GetFullPath($Path)
  } else {
    (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
  }

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

function Convert-ToBashLiteral {
  param([string]$Value)
  $singleQuote = [string][char]39
  $doubleQuote = [string][char]34
  $escapedValue = $Value.Replace(
    $singleQuote,
    $singleQuote + $doubleQuote + $singleQuote + $doubleQuote + $singleQuote
  )
  return $singleQuote + $escapedValue + $singleQuote
}

function Invoke-WslBash {
  param(
    [Parameter(Mandatory = $true)]
    [string]$Command,

    [string]$Distro,

    [string]$User
  )

  $wslCommand = Get-Command wsl.exe -ErrorAction SilentlyContinue
  if (-not $wslCommand) {
    throw "wsl.exe was not found. Install WSL first or run the bash script from Linux directly."
  }

  $arguments = @()
  if (-not [string]::IsNullOrWhiteSpace($Distro)) {
    $arguments += "-d"
    $arguments += $Distro
  }
  if (-not [string]::IsNullOrWhiteSpace($User)) {
    $arguments += "-u"
    $arguments += $User
  }
  $arguments += "--"
  $arguments += "bash"
  $arguments += "-lc"
  $arguments += $Command

  & $wslCommand.Source @arguments
  if ($LASTEXITCODE -ne 0) {
    throw "WSL command failed with exit code $LASTEXITCODE."
  }
}

function Resolve-AndroidSdkPath {
  $sdkDir = $null

  if ($env:ANDROID_SDK_ROOT) {
    $sdkDir = $env:ANDROID_SDK_ROOT
  } elseif ($env:ANDROID_HOME) {
    $sdkDir = $env:ANDROID_HOME
  } else {
    $localPropertiesPath = Join-Path $projectRoot "local.properties"
    if (Test-Path $localPropertiesPath) {
      $sdkLine = Get-Content $localPropertiesPath |
        Where-Object { $_ -like "sdk.dir=*" } |
        Select-Object -First 1
      if ($sdkLine) {
        $sdkDir = $sdkLine.Substring("sdk.dir=".Length)
        $sdkDir = $sdkDir.Replace('\:', ':').Replace('\\', '\')
      }
    }
  }

  if (-not $sdkDir) {
    throw "Android SDK path was not found. Set ANDROID_SDK_ROOT/ANDROID_HOME or local.properties sdk.dir."
  }

  if (-not (Test-Path $sdkDir)) {
    throw "Android SDK path does not exist: $sdkDir"
  }

  return (Resolve-Path -LiteralPath $sdkDir).Path
}

function Resolve-JavaHomePath {
  if ($env:JAVA_HOME -and (Test-Path $env:JAVA_HOME)) {
    return (Resolve-Path -LiteralPath $env:JAVA_HOME).Path
  }

  throw "JAVA_HOME is not configured or does not exist."
}

function Resolve-AndroidNdkPath {
  param(
    [Parameter(Mandatory = $true)]
    [string]$AndroidSdkPath
  )

  $ndkRoot = Join-Path $AndroidSdkPath "ndk"
  if (-not (Test-Path $ndkRoot)) {
    return $null
  }

  $ndkDirs = Get-ChildItem -Path $ndkRoot -Directory -ErrorAction SilentlyContinue
  if (-not $ndkDirs) {
    return $null
  }

  $preferred = $ndkDirs |
    Where-Object { $_.Name -like "25.*" } |
    Sort-Object Name -Descending |
    Select-Object -First 1
  if (-not $preferred) {
    $preferred = $ndkDirs |
      Sort-Object Name -Descending |
      Select-Object -First 1
  }

  return $preferred.FullName
}

function Resolve-AndroidBuildToolsVersion {
  param(
    [Parameter(Mandatory = $true)]
    [string]$AndroidSdkPath
  )

  $buildToolsRoot = Join-Path $AndroidSdkPath "build-tools"
  if (-not (Test-Path $buildToolsRoot)) {
    return "34.0.0"
  }

  $buildToolsDirs = Get-ChildItem -Path $buildToolsRoot -Directory -ErrorAction SilentlyContinue
  if (-not $buildToolsDirs) {
    return "34.0.0"
  }

  $preferred = $buildToolsDirs |
    Where-Object { $_.Name -eq "34.0.0" } |
    Select-Object -First 1
  if ($preferred) {
    return $preferred.Name
  }

  return ($buildToolsDirs | Sort-Object Name -Descending | Select-Object -First 1).Name
}

function Write-Utf8NoBomFile {
  param(
    [Parameter(Mandatory = $true)]
    [string]$Path,

    [Parameter(Mandatory = $true)]
    [string]$Content
  )

  $parentDir = Split-Path -Parent $Path
  if ($parentDir -and -not (Test-Path $parentDir)) {
    New-Item -ItemType Directory -Path $parentDir -Force | Out-Null
  }

  $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
  [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
}

function Ensure-WslShimScripts {
  param(
    [Parameter(Mandatory = $true)]
    [string]$SdkCmdlineToolsBinWindows,

    [Parameter(Mandatory = $true)]
    [string]$SdkCmdlineToolsRootWsl
  )

  $sdkManagerShim = @'
#!/usr/bin/env bash
set -euo pipefail
TOOLS_DIR='__TOOLS_DIR__'
CLASSPATH="${TOOLS_DIR}/lib/sdkmanager-classpath.jar"
exec java "-Dcom.android.sdklib.toolsdir=${TOOLS_DIR}" -classpath "$CLASSPATH" com.android.sdklib.tool.sdkmanager.SdkManagerCli "$@"
'@.Replace('__TOOLS_DIR__', $SdkCmdlineToolsRootWsl)

  $avdManagerShim = @'
#!/usr/bin/env bash
set -euo pipefail
TOOLS_DIR='__TOOLS_DIR__'
'@.Replace('__TOOLS_DIR__', $SdkCmdlineToolsRootWsl)

  Write-Utf8NoBomFile -Path (Join-Path $SdkCmdlineToolsBinWindows "sdkmanager") -Content $sdkManagerShim
  Write-Utf8NoBomFile -Path (Join-Path $SdkCmdlineToolsBinWindows "avdmanager") -Content $avdManagerShim
}

function Ensure-WslAndroidSdkScaffold {
  param(
    [Parameter(Mandatory = $true)]
    [string]$AndroidSdkSeedWindows,

    [Parameter(Mandatory = $true)]
    [string]$AndroidSdkRootWindows
  )

  $seedCmdlineToolsLatest = Join-Path $AndroidSdkSeedWindows "cmdline-tools\latest"
  if (-not (Test-Path $seedCmdlineToolsLatest)) {
    throw "Android SDK seed cmdline-tools were not found: $seedCmdlineToolsLatest"
  }

  $targetCmdlineToolsRoot = Join-Path $AndroidSdkRootWindows "cmdline-tools"
  New-Item -ItemType Directory -Path $targetCmdlineToolsRoot -Force | Out-Null
  Copy-Item -Path $seedCmdlineToolsLatest -Destination $targetCmdlineToolsRoot -Recurse -Force
}

function Ensure-WslAndroidSdkPackages {
  param(
    [string]$Distro,

    [Parameter(Mandatory = $true)]
    [string]$AndroidSdkRootWsl,

    [Parameter(Mandatory = $true)]
    [string]$BuildToolsVersion,

    [Parameter(Mandatory = $true)]
    [string]$NdkVersion
  )

  $sdkManagerPathWsl = "$AndroidSdkRootWsl/cmdline-tools/latest/bin/sdkmanager"
  $sdkManagerCmd = "$(Convert-ToBashLiteral $sdkManagerPathWsl) --sdk_root=$(Convert-ToBashLiteral $AndroidSdkRootWsl)"
  $installScript = @(
    "set -euo pipefail",
    "export ANDROID_HOME=$(Convert-ToBashLiteral $AndroidSdkRootWsl)",
    "export ANDROID_SDK_ROOT=$(Convert-ToBashLiteral $AndroidSdkRootWsl)",
    "chmod +x $(Convert-ToBashLiteral $sdkManagerPathWsl)",
    "if [[ ! -d $(Convert-ToBashLiteral "$AndroidSdkRootWsl/platforms/android-33") ]] || [[ ! -d $(Convert-ToBashLiteral "$AndroidSdkRootWsl/build-tools/$BuildToolsVersion") ]] || [[ ! -d $(Convert-ToBashLiteral "$AndroidSdkRootWsl/ndk/$NdkVersion") ]]; then",
    "  yes | $sdkManagerCmd --licenses >/dev/null",
    "  $sdkManagerCmd $(Convert-ToBashLiteral 'platform-tools') $(Convert-ToBashLiteral 'platforms;android-33') $(Convert-ToBashLiteral "build-tools;$BuildToolsVersion") $(Convert-ToBashLiteral "ndk;$NdkVersion")",
    "fi"
  ) -join "`n"

  Invoke-WslBash -Command $installScript -Distro $Distro
}

function Test-WslPythonBootstrap {
  param([string]$Distro)

  $checkScript = @(
    "set -euo pipefail",
    "python3 -m pip --version >/dev/null 2>&1",
    "python3 -m venv --help >/dev/null 2>&1",
    "java -version >/dev/null 2>&1"
  ) -join "`n"

  try {
    Invoke-WslBash -Command $checkScript -Distro $Distro
    return $true
  } catch {
    return $false
  }
}

function Ensure-WslPythonBootstrap {
  param([string]$Distro)

  if (Test-WslPythonBootstrap -Distro $Distro) {
    return
  }

  Write-Step "Installing missing WSL system packages for Python build"
  $installScript = @(
    "set -euo pipefail",
    "apt-get update",
    "DEBIAN_FRONTEND=noninteractive apt-get install -y python3-pip python3-venv openjdk-17-jdk"
  ) -join "`n"

  Invoke-WslBash -Command $installScript -Distro $Distro -User "root"
}

$androidSdkSeedWindows = Resolve-AndroidSdkPath
$androidBuildToolsVersion = Resolve-AndroidBuildToolsVersion -AndroidSdkPath $androidSdkSeedWindows
$androidNdkSeedWindows = Resolve-AndroidNdkPath -AndroidSdkPath $androidSdkSeedWindows
if (-not $androidNdkSeedWindows) {
  throw "No Android NDK installation was found under the seed SDK: $androidSdkSeedWindows"
}
$androidNdkVersion = Split-Path -Leaf $androidNdkSeedWindows
$androidSdkWindows = Join-Path $projectRoot ".android-sdk-linux"
$androidSdkWsl = Convert-ToWslPath -Path $androidSdkWindows -AllowMissing
$androidNdkWsl = "$androidSdkWsl/ndk/$androidNdkVersion"
$sdkCmdlineToolsRootWsl = "$androidSdkWsl/cmdline-tools/latest"
$sdkCmdlineToolsBinWsl = "$sdkCmdlineToolsRootWsl/bin"
$repoRootWsl = Convert-ToWslPath -Path $projectRoot
$sdkCmdlineToolsBinWindows = Join-Path $androidSdkWindows "cmdline-tools\latest\bin"
$autoUpgrade = if ($SkipP4aUpgrade) { "0" } else { "1" }
$bashLines = @(
  "set -euo pipefail",
  "cd $(Convert-ToBashLiteral $repoRootWsl)",
  "mkdir -p $(Convert-ToBashLiteral $sdkCmdlineToolsBinWsl)",
  "export ANDROID_HOME=$(Convert-ToBashLiteral $androidSdkWsl)",
  "export ANDROID_SDK_ROOT=$(Convert-ToBashLiteral $androidSdkWsl)",
  "export ANDROIDSDK=$(Convert-ToBashLiteral $androidSdkWsl)",
  "export ANDROID_NDK_HOME=$(Convert-ToBashLiteral $androidNdkWsl)",
  "export ANDROID_NDK_ROOT=$(Convert-ToBashLiteral $androidNdkWsl)",
  "export ANDROIDNDK=$(Convert-ToBashLiteral $androidNdkWsl)",
  "chmod +x $(Convert-ToBashLiteral "$sdkCmdlineToolsBinWsl/sdkmanager")",
  "chmod +x $(Convert-ToBashLiteral "$sdkCmdlineToolsBinWsl/avdmanager")",
  "export P4A_AUTO_BOOTSTRAP=1",
  "export P4A_AUTO_UPGRADE=$autoUpgrade",
  "export P4A_REQUIREMENTS=$(Convert-ToBashLiteral $P4aRequirements)",
  "export P4A_ARCH=$(Convert-ToBashLiteral $P4aArch)",
  "./build-p4a-service-library.sh"
)

Ensure-WslPythonBootstrap -Distro $WslDistro
Ensure-WslAndroidSdkScaffold `
  -AndroidSdkSeedWindows $androidSdkSeedWindows `
  -AndroidSdkRootWindows $androidSdkWindows
Ensure-WslShimScripts `
  -SdkCmdlineToolsBinWindows $sdkCmdlineToolsBinWindows `
  -SdkCmdlineToolsRootWsl $sdkCmdlineToolsRootWsl
Ensure-WslAndroidSdkPackages `
  -Distro $WslDistro `
  -AndroidSdkRootWsl $androidSdkWsl `
  -BuildToolsVersion $androidBuildToolsVersion `
  -NdkVersion $androidNdkVersion

Write-Step "Building embedded Python runtime in WSL"
Invoke-WslBash -Command ($bashLines -join "`n") -Distro $WslDistro

$distDir = Join-Path $projectRoot "tools/android_python_runtime_p4a/dist"
$builtAars = Get-ChildItem -Path $distDir -Filter "*.aar" -File -ErrorAction SilentlyContinue |
  Sort-Object LastWriteTime -Descending
if (-not $builtAars) {
  throw "WSL build completed, but no runtime AAR was copied into $distDir."
}

Write-Step "Embedded Python runtime build complete"
Write-Host "Runtime AAR: $($builtAars[0].FullName)" -ForegroundColor Green
