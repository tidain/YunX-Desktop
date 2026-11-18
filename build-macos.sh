#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"

[[ "$(uname -s)" == Darwin && "$(uname -m)" == arm64 ]] || {
  echo "Run this script on an Apple Silicon Mac." >&2
  exit 1
}

# Keep downloaded build tools and Gradle caches inside this local checkout.
mkdir -p .tools
export GRADLE_USER_HOME="$PWD/.gradle-home"
task_jdk="${JAVA_HOME:-}"
if [[ -z "$task_jdk" ]]; then
  task_jdk="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
fi
if [[ ! -x "$task_jdk/bin/jpackage" || ! -x "$task_jdk/bin/javac" ]] ||
   ! "$task_jdk/bin/java" -XshowSettings:properties -version 2>&1 |
     awk '/java.version = 17[.]/ { version = 1 } /os.arch = aarch64/ { arch = 1 } END { exit !(version && arch) }'; then
  task_jdk="$PWD/.tools/jdk-17.0.21+9/Contents/Home"
fi
if [[ ! -x "$task_jdk/bin/java" ]]; then
  task_jdk_url='https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21%2B9/OpenJDK17U-jdk_aarch64_mac_hotspot_17.0.21_9.tar.gz'
  curl -fL --retry 3 "$task_jdk_url" -o .tools/jdk17.tar.gz
  curl -fL --retry 3 "$task_jdk_url.sha256.txt" -o .tools/jdk17.sha256.txt
  task_sha="$(awk '{print $1}' .tools/jdk17.sha256.txt)"
  printf '%s  %s\n' "$task_sha" '.tools/jdk17.tar.gz' | shasum -a 256 -c -
  tar -xzf .tools/jdk17.tar.gz -C .tools
fi
export JAVA_HOME="$task_jdk"

if [[ -x .tools/gradle-8.13/bin/gradle ]]; then
  task_gradle=(.tools/gradle-8.13/bin/gradle)
else
  task_gradle=("$JAVA_HOME/bin/java" -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain)
fi

if [[ "$#" -gt 0 ]]; then
  "${task_gradle[@]}" "$@"
else
  "${task_gradle[@]}" :desktop:test :desktop:createDistributable :desktop:packageDmg
  task_version="$(head -n 1 version.txt | tr -d '\r')"
  mkdir -p release
  # ditto merges existing directories; remove the generated App to avoid stale JARs.
  rm -rf -- release/YunX-Desktop.app
  ditto desktop/build/compose/binaries/main/app/YunX-Desktop.app release/YunX-Desktop.app
  cp "desktop/build/compose/binaries/main/dmg/YunX-Desktop-${task_version}.dmg" \
    "release/YunX-Desktop-${task_version}-macOS-arm64.dmg"
  cp docs/BUILD-MACOS.md release/README-macOS.md
  echo "App: $PWD/release/YunX-Desktop.app"
  echo "DMG: $PWD/release/YunX-Desktop-${task_version}-macOS-arm64.dmg"
fi
