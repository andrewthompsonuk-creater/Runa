$ErrorActionPreference = 'Stop'

$env:ANDROID_HOME = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$emulator = Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$avdName = 'DogRunPixel'

Start-Process -FilePath $emulator -ArgumentList @(
    '-avd', $avdName,
    '-scale', '0.60',
    '-camera-back', 'webcam0',
    '-camera-front', 'webcam0',
    '-no-snapshot-load'
) -WindowStyle Normal

$signature = @'
using System;
using System.Runtime.InteropServices;
using System.Text;
public static class EmulatorWindowMover {
  public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
  [DllImport("user32.dll", CharSet=CharSet.Auto)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);
  [DllImport("user32.dll")] public static extern bool MoveWindow(IntPtr hWnd, int X, int Y, int nWidth, int nHeight, bool bRepaint);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
  [DllImport("user32.dll")] public static extern int GetSystemMetrics(int nIndex);
  public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
}
'@

Add-Type -TypeDefinition $signature -ErrorAction SilentlyContinue

$deadline = (Get-Date).AddSeconds(45)
$target = [IntPtr]::Zero

while ((Get-Date) -lt $deadline -and $target -eq [IntPtr]::Zero) {
    Start-Sleep -Milliseconds 500
    $script:target = [IntPtr]::Zero
    $callback = [EmulatorWindowMover+EnumWindowsProc] {
        param([IntPtr]$hWnd, [IntPtr]$lParam)
        if ([EmulatorWindowMover]::IsWindowVisible($hWnd)) {
            $titleBuilder = New-Object System.Text.StringBuilder 512
            [void][EmulatorWindowMover]::GetWindowText($hWnd, $titleBuilder, $titleBuilder.Capacity)
            if ($titleBuilder.ToString() -like 'Android Emulator - DogRunPixel*') {
                $script:target = $hWnd
                return $false
            }
        }
        return $true
    }
    [void][EmulatorWindowMover]::EnumWindows($callback, [IntPtr]::Zero)
    $target = $script:target
}

if ($target -ne [IntPtr]::Zero) {
    [EmulatorWindowMover]::ShowWindow($target, 9) | Out-Null
    [EmulatorWindowMover+RECT]$rect = New-Object EmulatorWindowMover+RECT
    [void][EmulatorWindowMover]::GetWindowRect($target, [ref]$rect)
    $width = $rect.Right - $rect.Left
    $height = $rect.Bottom - $rect.Top
    $screenWidth = [EmulatorWindowMover]::GetSystemMetrics(0)
    $x = [Math]::Max(20, [int](($screenWidth - $width) / 2))
    $y = 20
    [void][EmulatorWindowMover]::MoveWindow($target, $x, $y, $width, $height, $true)
    [void][EmulatorWindowMover]::SetForegroundWindow($target)
    Write-Host "Moved $avdName emulator window to $x,$y."
} else {
    Write-Warning "Started emulator, but could not find its main window to move."
}

if (Test-Path $adb) {
    & $adb wait-for-device
}
