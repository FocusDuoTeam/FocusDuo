# Create local configuration without displaying credentials or overwriting an existing file.
# Compatible with Windows PowerShell 5.1 and PowerShell 7.
[CmdletBinding()]
param()

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$temporaryPath = $null
$failureMessage = 'Unable to create .env. Check filesystem permissions and free space.'

try {
    $repositoryPath = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
    $environmentPath = Join-Path $repositoryPath '.env'
    $templatePath = Join-Path $repositoryPath '.env.example'

    if ([System.IO.File]::Exists($environmentPath)) {
        Write-Output 'Existing .env preserved; no values were changed.'
        return
    }
    if ([System.IO.Directory]::Exists($environmentPath)) {
        $failureMessage = 'Cannot create .env: a directory already exists at that path.'
        throw [System.IO.IOException]::new()
    }
    if (-not [System.IO.File]::Exists($templatePath)) {
        $failureMessage = 'Cannot create .env: the repository .env.example template is missing.'
        throw [System.IO.FileNotFoundException]::new()
    }

    $utf8 = New-Object System.Text.UTF8Encoding($false, $true)
    $template = [System.IO.File]::ReadAllText($templatePath, $utf8).Replace("`r`n", "`n").Replace("`r", "`n")
    $passwordEntry = New-Object System.Text.RegularExpressions.Regex('(?m)^DB_PASSWORD=[^\n]*$')
    if ($passwordEntry.Matches($template).Count -ne 1) {
        $failureMessage = 'Cannot create .env: .env.example must contain exactly one DB_PASSWORD= entry.'
        throw [System.IO.InvalidDataException]::new()
    }

    $entropy = New-Object byte[] 32
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($entropy) }
    finally { $random.Dispose() }
    $password = [System.BitConverter]::ToString($entropy).Replace('-', '').ToLowerInvariant()
    $contents = $passwordEntry.Replace($template, ('DB_PASSWORD=' + $password), 1)
    $bytes = $utf8.GetBytes($contents)

    # Both names match the repository's .env/.env.* ignore rules. CreateNew never truncates.
    $temporaryPath = Join-Path $repositoryPath ('.env.' + [System.Guid]::NewGuid().ToString('N') + '.tmp')
    $stream = [System.IO.File]::Open($temporaryPath, [System.IO.FileMode]::CreateNew,
        [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
    try {
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Flush($true)
    }
    finally { $stream.Dispose() }

    try {
        # Moving within one directory publishes the complete file; this overload cannot overwrite.
        [System.IO.File]::Move($temporaryPath, $environmentPath)
    }
    catch [System.IO.IOException] {
        if ([System.IO.File]::Exists($environmentPath)) {
            Write-Output 'Existing .env preserved; no values were changed.'
            return
        }
        throw
    }
    Write-Output 'Created .env with a generated DB_PASSWORD. Credentials are not displayed.'
}
catch {
    # Never emit exception data or template contents: either can contain local configuration.
    [System.Console]::Error.WriteLine($failureMessage)
    exit 1
}
finally {
    if ($null -ne $temporaryPath -and [System.IO.File]::Exists($temporaryPath)) {
        try { [System.IO.File]::Delete($temporaryPath) }
        catch { [System.Console]::Error.WriteLine('Unable to remove the temporary .env file; remove it locally before continuing.') }
    }
}
