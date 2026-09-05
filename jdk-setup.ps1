# YunX-Desktop 共享构建工具脚本（被 run.ps1 和 portable-package.ps1 引用）
# 提供：JDK 17 定位/自动安装（Ensure-Jdk）、Gradle 调用（Invoke-Gradle，直调 wrapper jar，不再依赖 gradlew.bat）
# 注意: 本文件必须保持 UTF-8 带 BOM 编码（PS 5.1 会把无 BOM 的 UTF-8 当 ANSI 解析，中文注释变乱码）

function Find-JdkHome {
    param([switch]$RequireJPackage)
    $marker = if ($RequireJPackage) { "bin\jpackage.exe" } else { "bin\java.exe" }

    $candidates = @(
        "C:\Program Files\Microsoft\jdk-17*",
        "C:\Program Files\Java\jdk-17*",
        "C:\Program Files\Eclipse Adoptium\jdk-17*",
        "C:\Program Files\Eclipse Foundation\jdk-17*",
        "C:\Program Files\Zulu\zulu-17*",
        "C:\Program Files\Amazon Corretto\jdk-17*",
        (Join-Path $env:LOCALAPPDATA "jdk-portable\jdk-17*"),
        (Join-Path $PSScriptRoot ".jdk\jdk-17*")
    )
    foreach ($c in $candidates) {
        try {
            $resolved = Resolve-Path $c -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($resolved -and (Test-Path (Join-Path $resolved.Path $marker))) {
                return $resolved.Path
            }
        } catch {}
    }

    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME $marker))) {
        return $env:JAVA_HOME
    }

    $cmdJava = Get-Command java -ErrorAction SilentlyContinue
    if ($cmdJava) {
        try {
            $javaHome = (& $cmdJava.Source -XshowSettings:properties -version 2>&1 |
                Select-String "java.home" |
                ForEach-Object { $_.Line -replace ".*= ", "" } |
                Select-Object -First 1)
            if ($javaHome -and (Test-Path (Join-Path $javaHome $marker))) {
                return $javaHome
            }
        } catch {}
    }
    return $null
}

function Install-PortableJDK17 {
    Write-Host "[!] No JDK 17 found. Downloading portable Microsoft OpenJDK 17..." -ForegroundColor Yellow
    $installDir = Join-Path $env:LOCALAPPDATA "jdk-portable"
    if (-not (Test-Path $installDir)) { New-Item -ItemType Directory -Path $installDir -Force | Out-Null }

    $jdkZipUrl = "https://aka.ms/download-jdk/microsoft-jdk-17.0.14-windows-x64.zip"
    $jdkZipPath = Join-Path $installDir "jdk-17.zip"

    Write-Host "    Downloading from: $jdkZipUrl" -ForegroundColor Gray
    Write-Host "    Saving to: $jdkZipPath" -ForegroundColor Gray
    Write-Host "    (This may take a few minutes - JDK is ~200MB)" -ForegroundColor Gray

    try {
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -Uri $jdkZipUrl -OutFile $jdkZipPath -UseBasicParsing -ErrorAction Stop
    } catch {
        throw "Failed to download JDK: $_"
    }

    Write-Host "    Extracting..." -ForegroundColor Gray
    Expand-Archive -Path $jdkZipPath -DestinationPath $installDir -Force
    Remove-Item $jdkZipPath -Force -ErrorAction SilentlyContinue

    $extracted = Get-ChildItem $installDir -Directory -Filter "jdk-17*" | Select-Object -First 1
    if (-not $extracted) {
        throw "JDK extraction failed - no jdk-17 folder found in $installDir"
    }
    if (-not (Test-Path (Join-Path $extracted.FullName "bin\java.exe"))) {
        throw "JDK extraction failed - no java.exe found"
    }

    Write-Host "[OK] Portable JDK installed: $($extracted.FullName)" -ForegroundColor Green
    return $extracted.FullName
}

function Ensure-Jdk {
    param([switch]$RequireJPackage)
    $jdkHome = Find-JdkHome -RequireJPackage:$RequireJPackage
    if (-not $jdkHome) {
        $jdkHome = Install-PortableJDK17
    }
    $env:JAVA_HOME = $jdkHome
    $env:PATH = "$jdkHome\bin;$env:PATH"
    return $jdkHome
}

function Invoke-Gradle {
    param(
        [Parameter(Position=0, ValueFromRemainingArguments=$true)]
        [string[]]$GradleArgs
    )
    if (-not $env:JAVA_HOME) { throw "JAVA_HOME not set - call Ensure-Jdk first" }
    $wrapperJar = Join-Path $PSScriptRoot "gradle\wrapper\gradle-wrapper.jar"
    if (-not (Test-Path $wrapperJar)) { throw "gradle-wrapper.jar not found: $wrapperJar" }
    $javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
    & $javaExe "-Xmx64m" "-Xms64m" "-Dorg.gradle.appname=gradlew" -jar $wrapperJar @GradleArgs
}
