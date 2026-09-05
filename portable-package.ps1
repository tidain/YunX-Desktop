# YunX-Desktop 免安装打包脚本
# 输出: release\YunX-Desktop\ （整个文件夹可拷贝到任意位置，双击 YunX-Desktop.exe 运行）
# 注意: 本文件必须保持 UTF-8 带 BOM 编码（PS 5.1 会把无 BOM 的 UTF-8 当 ANSI 解析，中文注释变乱码）
# 注意: Gradle 会向 stderr 输出无害警告，不要把原生 stderr 当作致命错误处理
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
Write-Host "Portable package working directory: $root" -ForegroundColor Cyan

# ---- 定位 JDK（缺失时自动下载，打包要求带 jpackage.exe）----
. (Join-Path $root "jdk-setup.ps1")
$JDK = Ensure-Jdk -RequireJPackage
Write-Host "[OK] Using JDK: $JDK" -ForegroundColor Green

Write-Host ""
Write-Host "[1/5] Gradle: build jar + export runtime libs..." -ForegroundColor Cyan
$buildOut = Invoke-Gradle ":desktop:jar" ":desktop:exportRuntimeLibs" "--no-build-cache" "--console=plain" 2>&1 | Out-String
Write-Host $buildOut
if ($buildOut -match "BUILD FAILED|FAILURE:") { throw "gradle export failed" }

Write-Host ""
Write-Host "[2/5] Assembling portable-libs folder..." -ForegroundColor Cyan
$libs = Join-Path $root "portable-libs"
if (Test-Path $libs) { Remove-Item $libs -Recurse -Force }
New-Item -ItemType Directory -Force -Path $libs | Out-Null
Copy-Item (Join-Path $root "desktop\build\libs\desktop.jar") $libs -Force
$exportDir = Join-Path $root "desktop\build\exportLibs"
if (-not (Test-Path $exportDir)) { throw "exportLibs dir not found at $exportDir" }
Get-ChildItem $exportDir -File | ForEach-Object { Copy-Item $_.FullName $libs -Force }
$jarCount = (Get-ChildItem $libs -File).Count
Write-Host "libs assembled: $jarCount jars" -ForegroundColor Green

Write-Host ""
Write-Host "[3/5] Building JRE runtime image (jlink)..." -ForegroundColor Cyan
$outDir = Join-Path $root "release\YunX-Desktop"
$runtimeTmp = Join-Path $root "release-runtime-tmp"
# jlink 要求输出目录不存在，先清掉
if (Test-Path $runtimeTmp) { Remove-Item $runtimeTmp -Recurse -Force }
$jlinkExe = Join-Path $JDK "bin\jlink.exe"
& $jlinkExe --module-path (Join-Path $JDK "jmods") `
  --add-modules java.base,java.datatransfer,java.xml,java.prefs,java.desktop,java.logging,jdk.crypto.ec,java.sql,java.naming `
  --strip-debug --no-header-files --no-man-pages --output $runtimeTmp
if ($LASTEXITCODE -ne 0) { throw "jlink failed with exit $LASTEXITCODE" }

Write-Host ""
Write-Host "[4/5] Creating app-image with jpackage (YunX-Desktop.exe)..." -ForegroundColor Cyan
# 打包前先杀运行中的实例：YunX-Desktop.exe / java 子进程 / JCEF 助手会锁住输出目录下的 jar 和运行时文件
Get-Process YunX-Desktop -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Get-Process | Where-Object { $_.Path -like "*\release\YunX-Desktop\*" } | Stop-Process -Force -ErrorAction SilentlyContinue
Get-Process jcef_helper -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
# 资源管理器/杀软可能短暂占用句柄，删除旧输出目录需重试
$removed = $false
for ($i = 1; $i -le 6; $i++) {
    if (-not (Test-Path $outDir)) { $removed = $true; break }
    try {
        Remove-Item $outDir -Recurse -Force -ErrorAction Stop
        $removed = $true
        break
    } catch {
        Write-Host "[i] Output dir locked, retry $i/6 in 2s (close YunX-Desktop / Explorer windows)..." -ForegroundColor Yellow
        Start-Sleep -Seconds 2
    }
}
if (-not $removed) { throw "Cannot remove $outDir - close running YunX-Desktop and Explorer windows, then retry." }
# 参数必须内联传递：@argfile 会在空格处把带空格的参数值拆成多个选项
$jpackageExe = Join-Path $JDK "bin\jpackage.exe"
& $jpackageExe `
  --type app-image `
  --dest release `
  --name YunX-Desktop `
  --app-version 1.1.2 `
  --vendor "YunX-Desktop" `
  --description "YunX-Desktop - netdisk share-link parser and high-speed downloader" `
  --input portable-libs `
  --main-jar desktop.jar `
  --main-class com.yunx.app.MainKt `
  --icon YunX-Desktop.ico `
  --runtime-image release-runtime-tmp `
  --java-options -Xmx4g `
  --java-options "-Dfile.encoding=UTF-8"
if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit $LASTEXITCODE" }
Remove-Item $runtimeTmp -Recurse -Force -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "[4b/5] Building custom launcher (rename-proof / CJK path-proof)..." -ForegroundColor Cyan
# 换用自研启动器：jpackage 的启动器要求 "exe 名 == app\同名.cfg"，改 exe 名就失效；
# 自研启动器固定读 app\YunX-Desktop.cfg，且用宽字符 API，中文路径/重命名都不会坏
$csc = "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
$launcherSrc = Join-Path $root "launcher\Launcher.cs"
$launcherExe = Join-Path $outDir "YunX-Desktop.exe"
$launcherIco = Join-Path $root "YunX-Desktop.ico"
$launcherTmp = Join-Path $root "build\YunX-Desktop-launcher.exe"
if (-not (Test-Path $csc)) { throw "csc.exe not found at $csc" }
# 先编译到 build\ 再覆盖：直接写正在使用的 release exe 可能被杀软/文件锁拒绝
& $csc /nologo /target:winexe /platform:anycpu /optimize+ `
  "/win32icon:$launcherIco" `
  "/r:System.Windows.Forms.dll" `
  "/r:System.Drawing.dll" `
  "/out:$launcherTmp" `
  "$launcherSrc"
if ($LASTEXITCODE -ne 0) { throw "launcher compile failed with exit $LASTEXITCODE" }
Copy-Item $launcherTmp $launcherExe -Force
if ($LASTEXITCODE -ne 0) { throw "launcher compile failed with exit $LASTEXITCODE" }
Write-Host "Custom launcher installed: $launcherExe" -ForegroundColor Green

Write-Host ""
Write-Host "[5/5] Extracting Skiko native resources (DLL/icudtl.dat)..." -ForegroundColor Cyan
Add-Type -AssemblyName System.IO.Compression.FileSystem
$skikoJar = Get-ChildItem $libs -Filter "skiko-awt-runtime-windows-x64*.jar" | Select-Object -First 1 -ExpandProperty FullName
if (-not (Test-Path $skikoJar)) {
    throw "skiko jar not found in $libs"
}
$zip = [System.IO.Compression.ZipFile]::OpenRead($skikoJar)
$appDir = Join-Path $outDir "app"
foreach ($entry in $zip.Entries) {
    if ($entry.FullName -match "\.class$" -or $entry.FullName -match "/$") { continue }
    $leaf = Split-Path $entry.FullName -Leaf
    if ($leaf -eq "") { continue }
    $target = Join-Path $appDir $leaf
    $s = $entry.Open(); $fs = [IO.File]::Create($target); $s.CopyTo($fs); $fs.Close(); $s.Close()
}
$zip.Dispose()
$nativeCount = (Get-ChildItem $appDir -File -Filter "*.dll").Count
Write-Host "Copied native DLLs: $nativeCount" -ForegroundColor Green

Write-Host ""
Write-Host "Portable package ready: $outDir" -ForegroundColor Green
Write-Host "Copy the whole folder anywhere and run YunX-Desktop.exe (no install needed)."
