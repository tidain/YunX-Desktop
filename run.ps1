# YunX-Desktop 构建/运行脚本
# 用法: .\run.ps1 [run|build|package|installer]
#   run       编译并启动桌面应用
#   build     仅编译
#   package   调用 portable-package.ps1 生成免安装包 (release\YunX-Desktop\)
#   installer 调用 installer-package.ps1 生成安装程序 (release\YunX-Desktop-setup-*.exe)

param(
    [Parameter(Position=0)]
    [ValidateSet("run","build","package","installer")]
    [string]$Command = "run"
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
Write-Host "Working directory: $root" -ForegroundColor Cyan

# 共享 JDK 逻辑：找到已装的 JDK 17，没有则自动下载便携版，新机器无需配环境变量
. (Join-Path $root "jdk-setup.ps1")
$javaHome = Ensure-Jdk

Write-Host "[OK] Using JDK: $javaHome" -ForegroundColor Green
try {
    & "$javaHome\bin\java.exe" -version 2>&1 | Select-Object -First 2 | ForEach-Object { Write-Host "  $_" }
} catch {}

Write-Host ""
switch ($Command) {
    "run" {
        Write-Host ">>> Compiling and launching YunX-Desktop app..." -ForegroundColor Cyan
        Invoke-Gradle ":desktop:run" "--console=plain"
    }
    "build" {
        Write-Host ">>> Building project (compile only, no launch)..." -ForegroundColor Cyan
        # 用 jar 而非 build：build 会连带 :desktop:test，其测试源集配置是坏的（ClassNotFoundException）
        Invoke-Gradle ":desktop:jar" "--console=plain"
    }
    "package" {
        Write-Host ">>> Packaging Windows portable distribution..." -ForegroundColor Cyan
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root "portable-package.ps1")
    }
    "installer" {
        Write-Host ">>> Building Windows installer (setup.exe)..." -ForegroundColor Cyan
        & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root "installer-package.ps1")
    }
}

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "[FAIL] Gradle exited with code $LASTEXITCODE" -ForegroundColor Red
    exit $LASTEXITCODE
}
