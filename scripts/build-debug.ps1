. "$PSScriptRoot\android-env.ps1"
Set-Location (Resolve-Path (Join-Path $PSScriptRoot '..'))
.\gradlew.bat assembleDebug --no-daemon
