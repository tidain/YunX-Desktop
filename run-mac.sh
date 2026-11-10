#!/usr/bin/env bash
# macOS 构建入口（对应 Windows 的 run.ps1）
# 需要 JDK 17+。打包（app / dmg）请用非 Homebrew 发行版的 JDK（如 Amazon Corretto、Temurin），
# Compose 插件会拒绝 Homebrew JDK（见 JetBrains/compose-multiplatform#3107）。
# 可通过 JAVA_HOME 指定 JDK。
set -euo pipefail

cd "$(dirname "$0")"

CMD="${1:-run}"

if [ -n "${JAVA_HOME:-}" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi

run_gradle() {
    java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain "$@"
}

case "$CMD" in
    run)
        echo "==> 编译并启动 YunX-Desktop (macOS)"
        run_gradle :desktop:run
        ;;
    build)
        echo "==> 编译 Kotlin (macOS)"
        run_gradle :desktop:compileKotlin
        ;;
    app)
        echo "==> 打包 .app"
        run_gradle :desktop:createDistributable
        echo "==> 产物：desktop/build/compose/binaries/main/app/YunX-Desktop.app"
        ;;
    dmg)
        echo "==> 打包 .dmg"
        run_gradle :desktop:packageDmg
        echo "==> 产物：desktop/build/compose/binaries/main/dmg/"
        ;;
    *)
        echo "用法: ./run-mac.sh [run|build|app|dmg]"
        exit 1
        ;;
esac
