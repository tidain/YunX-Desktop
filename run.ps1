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

# 遮罩选位器：C# 原生逐像素透明遮罩（见 picker\PositionPicker.cs）。
# 开发态编到仓库根 build\ 下，PositionPicker.kt 的候选路径会从这里找到它。
function Build-Picker {
    $csc = "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
    $src = Join-Path $root "picker\PositionPicker.cs"
    $out = Join-Path $root "build\YunX-PositionPicker.exe"
    if (-not (Test-Path $csc)) { Write-Host "[WARN] csc.exe not found, skip picker build" -ForegroundColor Yellow; return }
    if (-not (Test-Path $src)) { Write-Host "[WARN] picker source not found, skip picker build" -ForegroundColor Yellow; return }
    $dir = Split-Path -Parent $out
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    & $csc /nologo /target:winexe /platform:x64 /optimize+ `
        "/r:System.Windows.Forms.dll" "/r:System.Drawing.dll" `
        "/out:$out" "$src"
    if ($LASTEXITCODE -ne 0) { throw "picker compile failed with exit $LASTEXITCODE" }
    Write-Host "[OK] Position picker built: $out" -ForegroundColor Green
}

Write-Host ""
switch ($Command) {
    "run" {
        Build-Picker
        Write-Host ">>> Compiling and launching YunX-Desktop app..." -ForegroundColor Cyan
        Invoke-Gradle ":desktop:run" "--console=plain"
    }
    "build" {
        Build-Picker
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
