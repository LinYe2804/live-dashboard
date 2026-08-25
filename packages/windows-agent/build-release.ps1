param(
    [string]$Version = "dev",
    [string]$Runtime = "win-x64",
    [string]$PackageName = "live-dashboard-windows-agent",
    [string]$DisplayName = "Live Dashboard Windows Agent",
    [string]$Tagline = "Windows foreground reporter for Live Dashboard.",
    [string]$PostInstallNote = 'If you see "OK ..." in console, reporting works.',
    [string]$DotnetPath = "",
    [string]$SigningCertificatePath = "",
    [string]$SigningCertificatePassword = "",
    [string]$TimestampUrl = "http://timestamp.digicert.com"
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $scriptDir

function Normalize-FileName {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Value
    )

    $normalized = $Value.Trim()
    foreach ($char in [System.IO.Path]::GetInvalidFileNameChars()) {
        $normalized = $normalized.Replace([string]$char, "-")
    }

    $normalized = ($normalized -replace "\s+", "-").Trim(".")

    if ([string]::IsNullOrWhiteSpace($normalized)) {
        throw "PackageName is empty or invalid after normalization."
    }

    return $normalized
}

function Resolve-Dotnet {
    if (-not [string]::IsNullOrWhiteSpace($DotnetPath)) {
        if (Test-Path $DotnetPath) {
            return $DotnetPath
        }

        throw "Specified DotnetPath does not exist: $DotnetPath"
    }

    $cmd = Get-Command dotnet -ErrorAction SilentlyContinue
    if ($cmd) {
        return $cmd.Source
    }

    $fallback = "C:\Program Files\dotnet\dotnet.exe"
    if (Test-Path $fallback) {
        return $fallback
    }

    throw "dotnet not found. Please install .NET SDK 10+ or add dotnet to PATH."
}

function Resolve-AssemblyVersion {
    param([string]$Value)

    if ($Value -match "(?<major>\d+)\.(?<minor>\d+)\.(?<patch>\d+)") {
        return "$($Matches.major).$($Matches.minor).$($Matches.patch)"
    }

    return "1.0.0"
}

function Resolve-SignTool {
    $command = Get-Command "signtool.exe" -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $kitsRoot = Join-Path ${env:ProgramFiles(x86)} "Windows Kits\10\bin"
    if (Test-Path $kitsRoot) {
        $candidate = Get-ChildItem -Path $kitsRoot -Filter "signtool.exe" -File -Recurse |
            Where-Object { $_.FullName -match "\\x64\\signtool\.exe$" } |
            Sort-Object FullName -Descending |
            Select-Object -First 1
        if ($candidate) {
            return $candidate.FullName
        }
    }

    throw "Signing certificate was supplied, but signtool.exe was not found."
}

$dotnet = Resolve-Dotnet
$safePackageName = Normalize-FileName -Value $PackageName
$safeVersion = Normalize-FileName -Value $Version
$packageExeName = "$safePackageName.exe"
$assemblyVersion = Resolve-AssemblyVersion -Value $Version
$publishDir = Join-Path $scriptDir "dist\.publish\$runtime"
$stageDir = Join-Path $scriptDir "dist\$safePackageName"
$zipName = "$safePackageName-$safeVersion-$runtime.zip"
$zipPath = Join-Path $scriptDir "dist\$zipName"

$publishMode = "self-contained"

if (Test-Path $publishDir) { Remove-Item -Recurse -Force $publishDir }
if (Test-Path $stageDir) { Remove-Item -Recurse -Force $stageDir }
if (Test-Path $zipPath) { Remove-Item -Force $zipPath }

& $dotnet publish ".\WindowsAgent.csproj" `
    -c Release `
    -r $runtime `
    --self-contained true `
    -p:AssemblyName=$safePackageName `
    -p:Version=$assemblyVersion `
    -p:FileVersion="$assemblyVersion.0" `
    -p:InformationalVersion=$Version `
    -p:PublishSingleFile=false `
    -p:PublishTrimmed=false `
    -p:ContinuousIntegrationBuild=true `
    -p:DebugType=None `
    -p:DebugSymbols=false `
    -o $publishDir

if ($LASTEXITCODE -ne 0) {
    Write-Warning "Self-contained publish failed. Falling back to framework-dependent package."
    $publishMode = "framework-dependent"

    if (Test-Path $publishDir) { Remove-Item -Recurse -Force $publishDir }

    & $dotnet publish ".\WindowsAgent.csproj" `
        -c Release `
        -r $runtime `
        --self-contained false `
        -p:AssemblyName=$safePackageName `
        -p:Version=$assemblyVersion `
        -p:FileVersion="$assemblyVersion.0" `
        -p:InformationalVersion=$Version `
        -p:PublishSingleFile=false `
        -p:PublishTrimmed=false `
        -p:ContinuousIntegrationBuild=true `
        -p:DebugType=None `
        -p:DebugSymbols=false `
        -o $publishDir

    if ($LASTEXITCODE -ne 0) {
        throw "dotnet publish failed in both self-contained and framework-dependent modes."
    }
}

$publishedExe = Join-Path $publishDir $packageExeName
if (-not (Test-Path $publishedExe)) {
    throw "Published executable not found: $publishedExe"
}

$signatureStatus = "unsigned"
if (-not [string]::IsNullOrWhiteSpace($SigningCertificatePath)) {
    if (-not (Test-Path $SigningCertificatePath)) {
        throw "Signing certificate does not exist: $SigningCertificatePath"
    }
    if ([string]::IsNullOrWhiteSpace($SigningCertificatePassword)) {
        throw "SigningCertificatePassword is required when a signing certificate is supplied."
    }

    $signTool = Resolve-SignTool
    & $signTool sign `
        /fd SHA256 `
        /td SHA256 `
        /tr $TimestampUrl `
        /f $SigningCertificatePath `
        /p $SigningCertificatePassword `
        $publishedExe
    if ($LASTEXITCODE -ne 0) {
        throw "Authenticode signing failed."
    }

    & $signTool verify /pa /v $publishedExe
    if ($LASTEXITCODE -ne 0) {
        throw "Authenticode signature verification failed."
    }
    $signatureStatus = "authenticode"
}

New-Item -ItemType Directory -Path $stageDir -Force | Out-Null

Copy-Item (Join-Path $publishDir "*") $stageDir -Recurse -Force

Copy-Item ".\appsettings.example.json" (Join-Path $stageDir "appsettings.example.json") -Force
Copy-Item ".\appsettings.example.json" (Join-Path $stageDir "appsettings.json") -Force

$startScriptTemplate = Get-Content ".\start-agent.bat" -Raw
$startScriptContent = $startScriptTemplate.Replace("live-dashboard-windows-agent.exe", $packageExeName)
Set-Content -Path (Join-Path $stageDir "start-agent.bat") -Value $startScriptContent -Encoding ASCII

$readmeTxt = @"
$DisplayName
Version: $Version
Package Name: $safePackageName
Package Mode: $publishMode
Packaging: native .NET multi-file (not PyInstaller or a self-extracting single file)
Signature: $signatureStatus

$Tagline

How to use:
1. Edit appsettings.json
2. Fill serverUrl and token
3. Double-click start-agent.bat

$PostInstallNote

If Package Mode is framework-dependent, install .NET Runtime 10 x64 first.
Verify downloaded files against SHA256SUMS.txt before running them.
"@

Set-Content -Path (Join-Path $stageDir "README.txt") -Value $readmeTxt -Encoding UTF8

$packageMeta = [ordered]@{
    version = $Version
    runtime = $runtime
    packageName = $safePackageName
    displayName = $DisplayName
    packageMode = $publishMode
    packaging = "dotnet-multifile"
    signature = $signatureStatus
    executableName = $packageExeName
}

$packageMeta | ConvertTo-Json | Set-Content -Path (Join-Path $stageDir "package-meta.json") -Encoding UTF8

$checksumLines = Get-ChildItem -Path $stageDir -File -Recurse |
    Where-Object { $_.Name -ne "SHA256SUMS.txt" } |
    Sort-Object FullName |
    ForEach-Object {
        $relativePath = [System.IO.Path]::GetRelativePath($stageDir, $_.FullName).Replace("\", "/")
        $hash = (Get-FileHash -Path $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $relativePath"
    }
$checksumLines | Set-Content -Path (Join-Path $stageDir "SHA256SUMS.txt") -Encoding ASCII

Compress-Archive -Path (Join-Path $stageDir "*") -DestinationPath $zipPath -CompressionLevel Optimal

Write-Host "Done: $zipPath"
