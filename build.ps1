[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArguments
)

$ErrorActionPreference = 'Stop'
$previousJavaHome = $env:JAVA_HOME
$previousPath = $env:PATH
$taskJdk = $env:GAUNTLET_JAVA_HOME
if ([string]::IsNullOrWhiteSpace($taskJdk)) {
    $taskJdk = 'C:\Program Files\Java\jdk-25.0.4.1'
}
if (-not (Test-Path -LiteralPath (Join-Path $taskJdk 'bin\javac.exe'))) {
    throw 'JDK 25 not found. Set GAUNTLET_JAVA_HOME to your JDK 25 directory.'
}
if (-not $GradleArguments) {
    $GradleArguments = @('build')
}

Push-Location $PSScriptRoot
try {
    $env:JAVA_HOME = $taskJdk
    $env:PATH = (Join-Path $taskJdk 'bin') + ';' + $previousPath
    & (Join-Path $PSScriptRoot 'gradlew.bat') @GradleArguments
    $buildExitCode = $LASTEXITCODE
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:PATH = $previousPath
    Pop-Location
}
if ($buildExitCode -ne 0) {
    throw "Gradle failed with exit code $buildExitCode."
}
