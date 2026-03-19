#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST_DIR="$ROOT_DIR/tools/android_python_runtime_p4a/dist"
DEFAULT_P4A_STORAGE_BASE="${XDG_CACHE_HOME:-$HOME/.cache}"
STORAGE_DIR="${P4A_STORAGE_DIR:-$DEFAULT_P4A_STORAGE_BASE/opencray/p4a-storage}"
P4A_BIN="${P4A_BIN:-}"
P4A_PYTHON_BIN="${P4A_PYTHON_BIN:-}"
P4A_AUTO_BOOTSTRAP="${P4A_AUTO_BOOTSTRAP:-1}"
P4A_AUTO_UPGRADE="${P4A_AUTO_UPGRADE:-1}"
P4A_PACKAGE_SPEC="${P4A_PACKAGE_SPEC:-python-for-android}"
P4A_BUILD_VENV_DIR="${P4A_BUILD_VENV_DIR:-$ROOT_DIR/.p4a-build-venv}"
P4A_USE_BUILD_VENV="${P4A_USE_BUILD_VENV:-1}"
P4A_AUTO_SYSTEM_BOOTSTRAP="${P4A_AUTO_SYSTEM_BOOTSTRAP:-1}"
P4A_BUILD_PYTHON_PACKAGES="${P4A_BUILD_PYTHON_PACKAGES:-Cython<3}"
P4A_ANDROID_SDK_ROOT="${P4A_ANDROID_SDK_ROOT:-$ROOT_DIR/.android-sdk-linux}"
P4A_PRIVATE_DIR="${P4A_PRIVATE_DIR:-$ROOT_DIR/.p4a-private}"
P4A_ANDROID_SDK_SEED="${P4A_ANDROID_SDK_SEED:-}"
P4A_GRADLE_USER_HOME_BASE="${P4A_GRADLE_USER_HOME_BASE:-$HOME/.gradle-opencray-p4a}"
P4A_GRADLE_USER_HOME="${P4A_GRADLE_USER_HOME:-}"
P4A_HOOK_PATH="${P4A_HOOK_PATH:-$ROOT_DIR/.p4a-generated/p4a_build_hook.py}"
P4A_GRADLE_DIST_ZIP="${P4A_GRADLE_DIST_ZIP:-$ROOT_DIR/tools/android_python_runtime_p4a/gradle/gradle-8.0.2-all.zip}"
P4A_REQUIREMENTS_LOCK_FILE="${P4A_REQUIREMENTS_LOCK_FILE:-$ROOT_DIR/tools/android_python_runtime_p4a/requirements.lock}"
P4A_ANDROID_API="${P4A_ANDROID_API:-33}"
P4A_BUILD_TOOLS_VERSION="${P4A_BUILD_TOOLS_VERSION:-}"
P4A_NDK_VERSION="${P4A_NDK_VERSION:-}"
DIST_NAME="${P4A_DIST_NAME:-opencray-python-runtime}"
PACKAGE_NAME="${P4A_PACKAGE:-org.opencray.app}"
APP_NAME="${P4A_NAME:-OpenCray Python Runtime}"
APP_VERSION="${P4A_VERSION:-0.1.0}"
SERVICE_ID="${P4A_SERVICE_ID:-opencraypython}"
ARCH="${P4A_ARCH:-arm64-v8a}"
REQUIREMENTS=""
SERVICE_ENTRY="${P4A_SERVICE_ENTRY:-python_runner/p4a_service_main.py}"

log_step() {
  echo
  echo "==> $1"
}

trim_string() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  echo "$value"
}

resolve_requirements() {
  if [[ -n "${P4A_REQUIREMENTS:-}" ]]; then
    echo "$P4A_REQUIREMENTS"
    return
  fi

  local requirements=("python3")
  local line=""
  local requirement=""
  declare -A seen=()
  seen["python3"]=1

  if [[ -f "$P4A_REQUIREMENTS_LOCK_FILE" ]]; then
    while IFS= read -r line || [[ -n "$line" ]]; do
      line="${line%%#*}"
      requirement="$(trim_string "$line")"
      if [[ -z "$requirement" ]]; then
        continue
      fi
      if [[ -n "${seen[$requirement]:-}" ]]; then
        continue
      fi
      requirements+=("$requirement")
      seen["$requirement"]=1
    done < "$P4A_REQUIREMENTS_LOCK_FILE"
  fi

  local joined=""
  local item=""
  for item in "${requirements[@]}"; do
    if [[ -n "$joined" ]]; then
      joined+=","
    fi
    joined+="$item"
  done

  echo "$joined"
}

resolve_python_bin() {
  local explicit_bin="${1:-}"
  if [[ -n "$explicit_bin" ]]; then
    echo "$explicit_bin"
    return
  fi

  if command -v python3 >/dev/null 2>&1; then
    echo "python3"
    return
  fi

  if command -v python >/dev/null 2>&1; then
    echo "python"
    return
  fi

  echo "Python command not found. Install python3 in WSL or set P4A_PYTHON_BIN." >&2
  exit 1
}

can_run_privileged() {
  if [[ "$(id -u)" -eq 0 ]]; then
    return 0
  fi

  command -v sudo >/dev/null 2>&1
}

run_privileged() {
  if [[ "$(id -u)" -eq 0 ]]; then
    "$@"
    return
  fi

  sudo "$@"
}

install_system_packages() {
  if ! command -v apt-get >/dev/null 2>&1; then
    return 1
  fi

  if ! can_run_privileged; then
    return 1
  fi

  local packages=("$@")
  log_step "Installing missing WSL packages: ${packages[*]}"
  run_privileged apt-get update
  run_privileged env DEBIAN_FRONTEND=noninteractive apt-get install -y "${packages[@]}"
}

is_debian_package_installed() {
  local package_name="$1"
  if ! command -v dpkg-query >/dev/null 2>&1; then
    return 1
  fi

  dpkg-query -W -f='${Status}' "$package_name" 2>/dev/null | grep -q '^install ok installed$'
}

ensure_debian_packages() {
  if ! command -v apt-get >/dev/null 2>&1; then
    return 1
  fi

  local requested_packages=("$@")
  local missing_packages=()
  local package_name=""
  for package_name in "${requested_packages[@]}"; do
    if ! is_debian_package_installed "$package_name"; then
      missing_packages+=("$package_name")
    fi
  done

  if [[ ${#missing_packages[@]} -eq 0 ]]; then
    return
  fi

  install_system_packages "${missing_packages[@]}"
}

ensure_system_prerequisites() {
  if [[ "$P4A_AUTO_SYSTEM_BOOTSTRAP" != "1" ]]; then
    return
  fi

  ensure_debian_packages \
    python3 \
    python3-pip \
    python3-venv \
    openjdk-17-jdk \
    zip \
    unzip \
    build-essential \
    pkg-config \
    libffi-dev \
    libssl-dev \
    zlib1g-dev \
    libbz2-dev \
    libsqlite3-dev \
    liblzma-dev \
    libreadline-dev \
    libgdbm-dev \
    libexpat1-dev \
    uuid-dev \
    lld || true
}

ensure_pip_available() {
  local python_bin="$1"
  if "$python_bin" -m pip --version >/dev/null 2>&1; then
    return
  fi

  if "$python_bin" -m ensurepip --upgrade >/dev/null 2>&1; then
    if "$python_bin" -m pip --version >/dev/null 2>&1; then
      return
    fi
  fi

  if [[ "$P4A_AUTO_SYSTEM_BOOTSTRAP" == "1" ]]; then
    install_system_packages python3-pip python3-venv || true
    if "$python_bin" -m pip --version >/dev/null 2>&1; then
      return
    fi
  fi

  echo "pip is not available for $python_bin. Install python3-pip in WSL first." >&2
  exit 1
}

resolve_build_python_bin() {
  local host_python_bin="$1"
  if [[ "$P4A_USE_BUILD_VENV" != "1" ]]; then
    echo "$host_python_bin"
    return
  fi

  local venv_python_bin="$P4A_BUILD_VENV_DIR/bin/python"
  if [[ ! -x "$venv_python_bin" ]]; then
    log_step "Creating WSL build venv at $P4A_BUILD_VENV_DIR"
    if ! "$host_python_bin" -m venv "$P4A_BUILD_VENV_DIR"; then
      if [[ "$P4A_AUTO_SYSTEM_BOOTSTRAP" == "1" ]]; then
        install_system_packages python3-venv || true
      fi
      if ! "$host_python_bin" -m venv "$P4A_BUILD_VENV_DIR"; then
        echo "Failed to create the WSL build venv. Install python3-venv or set P4A_USE_BUILD_VENV=0." >&2
        exit 1
      fi
    fi
  fi

  echo "$venv_python_bin"
}

ensure_python_for_android() {
  local python_bin="$1"
  if [[ "$P4A_AUTO_BOOTSTRAP" != "1" ]]; then
    return
  fi

  if ! "$python_bin" -m pip show python-for-android >/dev/null 2>&1; then
    log_step "Installing $P4A_PACKAGE_SPEC in the WSL Python environment"
    "$python_bin" -m pip install --upgrade "$P4A_PACKAGE_SPEC"
    return
  fi

  if [[ "$P4A_AUTO_UPGRADE" == "1" ]]; then
    log_step "Upgrading $P4A_PACKAGE_SPEC in the WSL Python environment"
    "$python_bin" -m pip install --upgrade "$P4A_PACKAGE_SPEC"
  fi
}

ensure_build_python_packages() {
  local python_bin="$1"
  local package_specs=()
  local package_spec=""
  local package_name=""

  if [[ -z "$P4A_BUILD_PYTHON_PACKAGES" ]]; then
    return
  fi

  read -r -a package_specs <<< "$P4A_BUILD_PYTHON_PACKAGES"
  for package_spec in "${package_specs[@]}"; do
    package_name="${package_spec%%[<>=!~]*}"
    if "$python_bin" -m pip show "$package_name" >/dev/null 2>&1; then
      continue
    fi

    log_step "Installing build Python package $package_spec"
    "$python_bin" -m pip install --upgrade "$package_spec"
  done
}

resolve_java_home() {
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
    echo "$JAVA_HOME"
    return
  fi

  if ! command -v java >/dev/null 2>&1; then
    echo "java was not found. Install openjdk-17-jdk in WSL first." >&2
    exit 1
  fi

  local java_bin
  java_bin="$(readlink -f "$(command -v java)")"
  echo "$(cd "$(dirname "$java_bin")/.." && pwd)"
}

windows_path_to_wsl() {
  local input_path="$1"
  if [[ "$input_path" =~ ^([A-Za-z]):\\(.*)$ ]]; then
    local drive_letter
    local rest
    drive_letter="$(printf '%s' "${BASH_REMATCH[1]}" | tr '[:upper:]' '[:lower:]')"
    rest="${BASH_REMATCH[2]//\\//}"
    echo "/mnt/${drive_letter}/${rest}"
    return
  fi

  echo "$input_path"
}

resolve_android_sdk_seed() {
  if [[ -n "$P4A_ANDROID_SDK_SEED" ]]; then
    if [[ -d "$P4A_ANDROID_SDK_SEED" ]]; then
      echo "$P4A_ANDROID_SDK_SEED"
      return
    fi
    echo "P4A_ANDROID_SDK_SEED does not exist: $P4A_ANDROID_SDK_SEED" >&2
    exit 1
  fi

  if [[ -n "${ANDROID_SDK_ROOT:-}" && -d "${ANDROID_SDK_ROOT}" ]]; then
    echo "${ANDROID_SDK_ROOT}"
    return
  fi

  if [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}" ]]; then
    echo "${ANDROID_HOME}"
    return
  fi

  local local_properties_path="$ROOT_DIR/local.properties"
  if [[ -f "$local_properties_path" ]]; then
    local raw_sdk_dir=""
    raw_sdk_dir="$(grep -m1 '^sdk\.dir=' "$local_properties_path" | sed 's/^sdk\.dir=//')"
    if [[ -n "$raw_sdk_dir" ]]; then
      local unescaped_sdk_dir=""
      unescaped_sdk_dir="$(printf '%s' "$raw_sdk_dir" | sed 's#\\:#:#g; s#\\\\#\\#g')"
      local resolved_sdk_dir=""
      resolved_sdk_dir="$(windows_path_to_wsl "$unescaped_sdk_dir")"
      if [[ -d "$resolved_sdk_dir" ]]; then
        echo "$resolved_sdk_dir"
        return
      fi
    fi
  fi

  echo "Android SDK seed was not found. Set P4A_ANDROID_SDK_SEED or configure local.properties sdk.dir." >&2
  exit 1
}

resolve_build_tools_version() {
  local sdk_seed="$1"
  if [[ -n "$P4A_BUILD_TOOLS_VERSION" ]]; then
    echo "$P4A_BUILD_TOOLS_VERSION"
    return
  fi

  local build_tools_root="$sdk_seed/build-tools"
  if [[ -d "$build_tools_root/34.0.0" ]]; then
    echo "34.0.0"
    return
  fi

  local resolved_version=""
  resolved_version="$(
    find "$build_tools_root" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null \
      | sort -V \
      | tail -n 1
  )"
  if [[ -n "$resolved_version" ]]; then
    echo "$resolved_version"
    return
  fi

  echo "34.0.0"
}

resolve_ndk_version() {
  local sdk_seed="$1"
  if [[ -n "$P4A_NDK_VERSION" ]]; then
    echo "$P4A_NDK_VERSION"
    return
  fi

  local ndk_root="$sdk_seed/ndk"
  if [[ ! -d "$ndk_root" ]]; then
    echo "Android NDK was not found under the SDK seed: $sdk_seed" >&2
    exit 1
  fi

  local preferred_version=""
  preferred_version="$(
    find "$ndk_root" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null \
      | grep '^25\.' \
      | sort -V \
      | tail -n 1 || true
  )"
  if [[ -n "$preferred_version" ]]; then
    echo "$preferred_version"
    return
  fi

  local fallback_version=""
  fallback_version="$(
    find "$ndk_root" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' 2>/dev/null \
      | sort -V \
      | tail -n 1
  )"
  if [[ -n "$fallback_version" ]]; then
    echo "$fallback_version"
    return
  fi

  echo "Android NDK was not found under the SDK seed: $sdk_seed" >&2
  exit 1
}

ensure_android_sdk_scaffold() {
  local sdk_seed="$1"
  local sdk_root="$2"

  local seed_cmdline_tools="$sdk_seed/cmdline-tools/latest"
  if [[ ! -d "$seed_cmdline_tools" ]]; then
    echo "Android SDK cmdline-tools were not found in the seed SDK: $seed_cmdline_tools" >&2
    exit 1
  fi

  log_step "Syncing Android cmdline-tools into $sdk_root"
  mkdir -p "$sdk_root/cmdline-tools/latest"
  cp -R "$seed_cmdline_tools/." "$sdk_root/cmdline-tools/latest/"
}

write_cmdline_tools_shims() {
  local sdk_root="$1"
  local tools_dir="$sdk_root/cmdline-tools/latest"
  local bin_dir="$tools_dir/bin"

  mkdir -p "$bin_dir"

  cat > "$bin_dir/sdkmanager" <<EOF
#!/usr/bin/env bash
set -euo pipefail
TOOLS_DIR='$tools_dir'
CLASSPATH="\${TOOLS_DIR}/lib/sdkmanager-classpath.jar"
exec java "-Dcom.android.sdklib.toolsdir=\${TOOLS_DIR}" \${JAVA_OPTS:-} \${SDKMANAGER_OPTS:-} -classpath "\${CLASSPATH}" com.android.sdklib.tool.sdkmanager.SdkManagerCli "\$@"
EOF

  cat > "$bin_dir/avdmanager" <<EOF
#!/usr/bin/env bash
set -euo pipefail
TOOLS_DIR='$tools_dir'
CLASSPATH="\${TOOLS_DIR}/lib/avdmanager-classpath.jar"
exec java "-Dcom.android.sdkmanager.toolsdir=\${TOOLS_DIR}" \${JAVA_OPTS:-} \${AVDMANAGER_OPTS:-} -classpath "\${CLASSPATH}" com.android.sdklib.tool.AvdManagerCli "\$@"
EOF

  chmod +x "$bin_dir/sdkmanager" "$bin_dir/avdmanager"
}

install_android_sdk_packages() {
  local sdk_root="$1"
  local sdkmanager_bin="$sdk_root/cmdline-tools/latest/bin/sdkmanager"
  local build_tools_version="$2"
  local ndk_version="$3"
  local android_api="$4"

  local missing_packages=0
  if [[ ! -d "$sdk_root/platform-tools" ]]; then
    missing_packages=1
  fi
  if [[ ! -d "$sdk_root/platforms/android-$android_api" ]]; then
    missing_packages=1
  fi
  if [[ ! -d "$sdk_root/build-tools/$build_tools_version" ]]; then
    missing_packages=1
  fi
  if [[ ! -d "$sdk_root/ndk/$ndk_version" ]]; then
    missing_packages=1
  fi

  if [[ "$missing_packages" == "0" ]]; then
    return
  fi

  log_step "Installing Android SDK packages into $sdk_root"
  set +o pipefail
  yes | "$sdkmanager_bin" --sdk_root="$sdk_root" --licenses >/dev/null
  set -o pipefail
  "$sdkmanager_bin" --sdk_root="$sdk_root" \
    "platform-tools" \
    "platforms;android-$android_api" \
    "build-tools;$build_tools_version" \
    "ndk;$ndk_version"
}

export_android_toolchain() {
  local sdk_root="$1"
  local ndk_version="$2"
  local java_home="$3"

  export JAVA_HOME="$java_home"
  export ANDROID_HOME="$sdk_root"
  export ANDROID_SDK_ROOT="$sdk_root"
  export ANDROIDSDK="$sdk_root"
  export ANDROID_NDK_HOME="$sdk_root/ndk/$ndk_version"
  export ANDROID_NDK_ROOT="$sdk_root/ndk/$ndk_version"
  export ANDROIDNDK="$sdk_root/ndk/$ndk_version"
}

export_gradle_env() {
  if [[ -z "$P4A_GRADLE_USER_HOME" ]]; then
    local run_tag=""
    run_tag="$(date +%Y%m%d-%H%M%S)-$$"
    P4A_GRADLE_USER_HOME="$P4A_GRADLE_USER_HOME_BASE/$run_tag"
  fi

  mkdir -p "$P4A_GRADLE_USER_HOME"
  export CI="${CI:-true}"
  export TERM="dumb"
  export GRADLE_USER_HOME="$P4A_GRADLE_USER_HOME"
  export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.console=plain -Dorg.gradle.daemon=false -Dorg.gradle.vfs.watch=false -Dorg.gradle.parallel=false -Dorg.gradle.workers.max=2"
  if [[ -n "${P4A_GRADLE_DIST_URI:-}" ]]; then
    export P4A_GRADLE_DIST_URI
    log_step "Using local Gradle distribution $P4A_GRADLE_DIST_URI"
  fi
  log_step "Using Gradle user home $GRADLE_USER_HOME"
}

resolve_gradle_dist_uri() {
  if [[ -z "$P4A_GRADLE_DIST_ZIP" ]]; then
    return
  fi

  if [[ ! -f "$P4A_GRADLE_DIST_ZIP" ]]; then
    if [[ "$P4A_GRADLE_DIST_ZIP" == "$ROOT_DIR/tools/android_python_runtime_p4a/gradle/gradle-8.0.2-all.zip" ]]; then
      return
    fi
    echo "Configured P4A_GRADLE_DIST_ZIP does not exist: $P4A_GRADLE_DIST_ZIP" >&2
    exit 1
  fi

  local resolved_zip=""
  resolved_zip="$(cd "$(dirname "$P4A_GRADLE_DIST_ZIP")" && pwd)/$(basename "$P4A_GRADLE_DIST_ZIP")"
  export P4A_GRADLE_DIST_URI="file://$resolved_zip"
}

write_p4a_hook_file() {
  local hook_dir
  hook_dir="$(dirname "$P4A_HOOK_PATH")"
  mkdir -p "$hook_dir"
  cat > "$P4A_HOOK_PATH" <<'EOF'
from __future__ import annotations

from pathlib import Path


GRADLE_WRAPPER_NAME = "gradlew"
GRADLE_WRAPPER_BACKUP_NAME = "gradlew.opencray-original"
GRADLE_PROPERTIES_NAME = "gradle.properties"


def _rewrite_gradlew(dist_dir: Path) -> None:
    wrapper_path = dist_dir / GRADLE_WRAPPER_NAME
    backup_path = dist_dir / GRADLE_WRAPPER_BACKUP_NAME
    if not wrapper_path.exists():
        return

    if not backup_path.exists():
        wrapper_path.rename(backup_path)

    wrapper_content = """#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export CI="${CI:-true}"
export TERM="${TERM:-dumb}"
exec "$SCRIPT_DIR/gradlew.opencray-original" --no-daemon --stacktrace --info --console=plain "$@"
"""
    wrapper_path.write_text(wrapper_content, encoding="utf-8")
    wrapper_path.chmod(0o755)


def _merge_gradle_properties(dist_dir: Path) -> None:
    properties_path = dist_dir / GRADLE_PROPERTIES_NAME
    desired = {
        "org.gradle.console": "plain",
        "org.gradle.daemon": "false",
        "org.gradle.parallel": "false",
        "org.gradle.vfs.watch": "false",
        "org.gradle.workers.max": "2",
    }

    current: dict[str, str] = {}
    if properties_path.exists():
        for raw_line in properties_path.read_text(encoding="utf-8").splitlines():
            line = raw_line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            current[key.strip()] = value.strip()

    current.update(desired)
    serialized = "".join(f"{key}={value}\n" for key, value in sorted(current.items()))
    properties_path.write_text(serialized, encoding="utf-8")


def _override_wrapper_distribution(dist_dir: Path) -> None:
    distribution_uri = __import__("os").environ.get("P4A_GRADLE_DIST_URI", "").strip()
    if not distribution_uri:
        return

    wrapper_properties = dist_dir / "gradle" / "wrapper" / "gradle-wrapper.properties"
    if not wrapper_properties.exists():
        return

    lines = wrapper_properties.read_text(encoding="utf-8").splitlines()
    rewritten = []
    replaced = False
    for line in lines:
        if line.startswith("distributionUrl="):
            rewritten.append(f"distributionUrl={distribution_uri}")
            replaced = True
        else:
            rewritten.append(line)
    if not replaced:
        rewritten.append(f"distributionUrl={distribution_uri}")
    wrapper_properties.write_text("\n".join(rewritten) + "\n", encoding="utf-8")


def before_apk_assemble(toolchain) -> None:
    dist_dir = Path.cwd()
    _rewrite_gradlew(dist_dir)
    _merge_gradle_properties(dist_dir)
    _override_wrapper_distribution(dist_dir)
EOF
}

prepare_private_sources() {
  local private_dir="$1"
  local package_src="$ROOT_DIR/python_runner"
  local package_dst="$private_dir/python_runner"

  if [[ ! -d "$package_src" ]]; then
    echo "python_runner package was not found: $package_src" >&2
    exit 1
  fi

  log_step "Preparing minimal p4a private sources in $private_dir"
  rm -rf "$private_dir"
  mkdir -p "$package_dst"
  cp "$package_src/__init__.py" "$package_dst/"
  cp "$package_src/p4a_bridge.py" "$package_dst/"
  cp "$package_src/p4a_service_main.py" "$package_dst/"
}

clean_broken_hostpython_cache() {
  local hostpython_bin="$STORAGE_DIR/build/other_builds/hostpython3/desktop/hostpython3/native-build/python3"
  local hostpython_root="$STORAGE_DIR/build/other_builds/hostpython3"

  if [[ ! -x "$hostpython_bin" ]]; then
    return
  fi

  if "$hostpython_bin" -c 'import ctypes' >/dev/null 2>&1; then
    return
  fi

  log_step "Removing cached hostpython3 build without _ctypes support"
  rm -rf "$hostpython_root"
}

copy_built_aar_to_dist() {
  local dist_name="$1"
  local source_candidates=()
  local latest_aar=""
  local source_path=""

  while IFS= read -r source_path; do
    source_candidates+=("$source_path")
  done < <(
    find \
      "$ROOT_DIR" \
      "$STORAGE_DIR/dists/$dist_name" \
      "$STORAGE_DIR/build" \
      -type f -name '*.aar' -print 2>/dev/null
  )

  if [[ ${#source_candidates[@]} -eq 0 ]]; then
    echo "No AAR output was found after p4a build." >&2
    exit 1
  fi

  latest_aar="$(
    printf '%s\n' "${source_candidates[@]}" \
      | while IFS= read -r candidate; do
          printf '%s\t%s\n' "$(stat -c '%Y' "$candidate")" "$candidate"
        done \
      | sort -nr \
      | head -n 1 \
      | cut -f2-
  )"

  if [[ -z "$latest_aar" || ! -f "$latest_aar" ]]; then
    echo "No usable AAR output was found after p4a build." >&2
    exit 1
  fi

  mkdir -p "$DIST_DIR"
  rm -f "$DIST_DIR"/*.aar
  log_step "Copying runtime AAR from $latest_aar"
  cp -f "$latest_aar" "$DIST_DIR/"
  echo "Copied runtime AAR to $DIST_DIR/$(basename "$latest_aar")"
}

run_p4a() {
  if [[ -n "$P4A_BIN" ]]; then
    "$P4A_BIN" "$@"
    return
  fi

  "$P4A_PYTHON_BIN" -m pythonforandroid.toolchain "$@"
}

ensure_system_prerequisites
HOST_PYTHON_BIN="$(resolve_python_bin "$P4A_PYTHON_BIN")"
ensure_pip_available "$HOST_PYTHON_BIN"
P4A_PYTHON_BIN="$(resolve_build_python_bin "$HOST_PYTHON_BIN")"
ensure_pip_available "$P4A_PYTHON_BIN"
REQUIREMENTS="$(resolve_requirements)"
ensure_python_for_android "$P4A_PYTHON_BIN"
ensure_build_python_packages "$P4A_PYTHON_BIN"
export PATH="$(dirname "$P4A_PYTHON_BIN"):$PATH"
hash -r
resolve_gradle_dist_uri

JAVA_HOME="$(resolve_java_home)"
ANDROID_SDK_SEED="$(resolve_android_sdk_seed)"
BUILD_TOOLS_VERSION="$(resolve_build_tools_version "$ANDROID_SDK_SEED")"
NDK_VERSION="$(resolve_ndk_version "$ANDROID_SDK_SEED")"

ensure_android_sdk_scaffold "$ANDROID_SDK_SEED" "$P4A_ANDROID_SDK_ROOT"
write_cmdline_tools_shims "$P4A_ANDROID_SDK_ROOT"
export_android_toolchain "$P4A_ANDROID_SDK_ROOT" "$NDK_VERSION" "$JAVA_HOME"
export_gradle_env
install_android_sdk_packages "$P4A_ANDROID_SDK_ROOT" "$BUILD_TOOLS_VERSION" "$NDK_VERSION" "$P4A_ANDROID_API"
clean_broken_hostpython_cache
write_p4a_hook_file
prepare_private_sources "$P4A_PRIVATE_DIR"

mkdir -p "$DIST_DIR"
rm -f "$DIST_DIR"/*.aar

log_step "Using Python requirements: $REQUIREMENTS"
log_step "Building p4a service library AAR"
run_p4a aar \
  --private "$P4A_PRIVATE_DIR" \
  --storage-dir "$STORAGE_DIR" \
  --hook "$P4A_HOOK_PATH" \
  --dist-name "$DIST_NAME" \
  --bootstrap service_library \
  --package "$PACKAGE_NAME" \
  --name "$APP_NAME" \
  --version "$APP_VERSION" \
  --requirements "$REQUIREMENTS" \
  --service "$SERVICE_ID:$SERVICE_ENTRY" \
  --arch "$ARCH"

copy_built_aar_to_dist "$DIST_NAME"
