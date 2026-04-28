$ProjectRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = Join-Path $ProjectRoot '.gradle'
$env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + (Join-Path ([Environment]::GetEnvironmentVariable('GRADLE_HOME', 'User')) 'bin') + ';' + (Join-Path $env:ANDROID_HOME 'platform-tools') + ';' + (Join-Path $env:ANDROID_HOME 'cmdline-tools\latest\bin') + ';' + $env:Path

Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "ANDROID_HOME=$env:ANDROID_HOME"
Write-Host "GRADLE_USER_HOME=$env:GRADLE_USER_HOME"
