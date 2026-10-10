[CmdletBinding()]
param(
    [switch]$Demo,
    [ValidateRange(840, 4096)][int]$Width = 1280,
    [ValidateRange(600, 4096)][int]$Height = 800
)

$ErrorActionPreference = 'Stop'
$focusCandidates = @($env:FOCUSDUO_JAVA_HOME, $env:JAVA_HOME)
$focusJdksDirectory = Join-Path $env:USERPROFILE '.jdks'
if (Test-Path -LiteralPath $focusJdksDirectory) {
    $focusCandidates += @(Get-ChildItem -Directory -LiteralPath $focusJdksDirectory | Select-Object -ExpandProperty FullName)
}
$focusJavaCommand = Get-Command java -ErrorAction SilentlyContinue
if ($focusJavaCommand) {
    $focusCandidates += Split-Path (Split-Path $focusJavaCommand.Source -Parent) -Parent
}

$focusJdk = $null
foreach ($focusCandidate in $focusCandidates) {
    if ([string]::IsNullOrWhiteSpace($focusCandidate)) { continue }
    $focusRelease = Join-Path $focusCandidate 'release'
    $focusCompiler = Join-Path $focusCandidate 'bin/javac.exe'
    if ((Test-Path -LiteralPath $focusRelease) -and (Test-Path -LiteralPath $focusCompiler)) {
        if (Select-String -LiteralPath $focusRelease -Pattern '^JAVA_VERSION="21(?:\.|\+|")' -Quiet) {
            $focusJdk = $focusCandidate
            break
        }
    }
}
if (-not $focusJdk) {
    throw 'JDK 21 not found. Set FOCUSDUO_JAVA_HOME or JAVA_HOME to your JDK 21 directory.'
}

$focusPreviousJava = $env:JAVA_HOME
$focusPreviousPath = $env:PATH
$focusExitCode = 1
Push-Location $PSScriptRoot
try {
    $env:JAVA_HOME = $focusJdk
    $env:PATH = (Join-Path $focusJdk 'bin') + [IO.Path]::PathSeparator + $focusPreviousPath
    $focusRunArguments = @("--width=$Width", "--height=$Height")
    if ($Demo) { $focusRunArguments += '--demo' }
    Write-Host "FocusDuo: using JDK 21 at $focusJdk"
    & ./gradlew.bat run "--args=$($focusRunArguments -join ' ')" --console=plain
    $focusExitCode = $LASTEXITCODE
} finally {
    $env:JAVA_HOME = $focusPreviousJava
    $env:PATH = $focusPreviousPath
    Pop-Location
}
exit $focusExitCode
