# YunX-Desktop 安装程序构建脚本
# 流程：先复用 portable-package.ps1 生成免安装版（含自研启动器），再用 Inno Setup 压成单文件安装包
# 输出：release\YunX-Desktop-setup-<版本>.exe（中文向导、可选安装目录、开始菜单/桌面快捷方式、可卸载）
# 依赖：Inno Setup 6 缺失时自动下载并静默安装到 %LOCALAPPDATA%\Programs\Inno Setup 6
# 注意: 本文件必须保持 UTF-8 带 BOM 编码（PS 5.1 会把无 BOM 的 UTF-8 当 ANSI 解析，中文注释变乱码）

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
Write-Host "Installer build working directory: $root" -ForegroundColor Cyan

# 版本号需与 portable-package.ps1 中 jpackage 的 --app-version 保持一致
$AppVersion = "1.1.2"

# ---- [1/3] 生成免安装版（jpackage app-image + 自研启动器 + skiko 原生库）----
Write-Host ""
Write-Host "[1/3] Building portable distribution first..." -ForegroundColor Cyan
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root "portable-package.ps1")
if ($LASTEXITCODE -ne 0) { throw "portable package failed with exit $LASTEXITCODE" }

# ---- [2/3] 定位 Inno Setup 6（缺失时自动下载静默安装）----
Write-Host ""
Write-Host "[2/3] Locating Inno Setup 6 compiler..." -ForegroundColor Cyan
$isccCandidates = @(
    (Join-Path $env:LOCALAPPDATA "Programs\Inno Setup 6\ISCC.exe"),
    (Join-Path ${env:ProgramFiles(x86)} "Inno Setup 6\ISCC.exe"),
    (Join-Path $env:ProgramFiles "Inno Setup 6\ISCC.exe")
)
$iscc = $isccCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $iscc) {
    Write-Host "[!] Inno Setup 6 not found. Downloading and installing silently..." -ForegroundColor Yellow
    $innoSetupExe = Join-Path $env:TEMP "innosetup-installer.exe"
    # 直链来自 GitHub Releases（官网 download.php 返回的是 HTML 中转页，脚本拿不到真实文件）
    $urls = @(
        "https://github.com/jrsoftware/issrc/releases/download/is-6_7_3/innosetup-6.7.3.exe",
        "https://github.com/jrsoftware/issrc/releases/download/is-7_1_0/innosetup-7.1.0-x64.exe"
    )
    $downloaded = $false
    foreach ($u in $urls) {
        try {
            Write-Host "    Downloading: $u" -ForegroundColor Gray
            $ProgressPreference = 'SilentlyContinue'
            Invoke-WebRequest -Uri $u -OutFile $innoSetupExe -UseBasicParsing -ErrorAction Stop
            # 校验是 Windows 可执行文件（MZ 头）且体积正常，防止 HTML 错误页冒充安装器
            $fs = [IO.File]::OpenRead($innoSetupExe)
            $mz = @($fs.ReadByte(), $fs.ReadByte()); $fs.Close()
            if ($mz[0] -eq 0x4D -and $mz[1] -eq 0x5A -and (Get-Item $innoSetupExe).Length -gt 5MB) {
                $downloaded = $true
                break
            }
            Write-Host "    Not a valid PE file, trying next mirror..." -ForegroundColor Yellow
        } catch {
            Write-Host "    Download failed: $_" -ForegroundColor Yellow
        }
    }
    if (-not $downloaded) { throw "Cannot download Inno Setup - install it manually from https://jrsoftware.org/isdl.php then retry." }
    $innoDir = Join-Path $env:LOCALAPPDATA "Programs\Inno Setup 6"
    $p = Start-Process -FilePath $innoSetupExe -ArgumentList "/VERYSILENT","/SUPPRESSMSGBOXES","/NORESTART","/CURRENTUSER","/DIR=`"$innoDir`"" -Wait -PassThru
    if ($p.ExitCode -ne 0) { throw "Inno Setup silent install failed with exit $($p.ExitCode)" }
    Remove-Item $innoSetupExe -Force -ErrorAction SilentlyContinue
    $iscc = Join-Path $innoDir "ISCC.exe"
    if (-not (Test-Path $iscc)) { throw "ISCC.exe not found after install: $iscc" }
}
Write-Host "[OK] ISCC: $iscc" -ForegroundColor Green

# 中文语言包随仓库分发（installer\ChineseSimplified.isl），缺失说明仓库不完整
$islRepo = Join-Path $root "installer\ChineseSimplified.isl"
if (-not (Test-Path $islRepo)) { throw "Missing language pack: $islRepo" }

# ---- [3/3] 编译单文件安装包 ----
Write-Host ""
Write-Host "[3/3] Compiling installer (this compresses ~200MB, may take a while)..." -ForegroundColor Cyan
& $iscc "/DAppVersion=$AppVersion" (Join-Path $root "installer\YunX-Desktop.iss")
if ($LASTEXITCODE -ne 0) { throw "ISCC failed with exit $LASTEXITCODE" }

$outFile = Join-Path $root "release\YunX-Desktop-setup-$AppVersion.exe"
if (-not (Test-Path $outFile)) { throw "Installer output not found: $outFile" }
$sizeMb = [math]::Round((Get-Item $outFile).Length / 1MB, 1)
Write-Host ""
Write-Host "Installer ready: $outFile ($sizeMb MB)" -ForegroundColor Green
