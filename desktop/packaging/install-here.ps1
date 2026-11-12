# Installs a freshly built Filet Desktop MSI over the copy installed on this machine.
# Run by the Gradle task installHere after packageMsi / packageExe; see desktop/build.gradle.kts.
#
#  - Per user only. A copy installed for all users (Program Files) needs an administrator to
#    change, so it is reported and left alone, and the build still succeeds.
#  - The open app is closed through its window, so it says goodbye to the network the normal way,
#    and opened again afterwards - sharing again if it was sharing, which is read from whether it
#    had a listening port.
#  - The installed copy is removed first, then the new one installed. A rebuild keeps its version
#    number, and an MSI of an already installed version changes nothing.
param([Parameter(Mandatory = $true)][string]$Msi)
$ErrorActionPreference = 'Stop'

$Upgrade = '{8E3C4C2A-6A5E-4D13-9B0F-7A2D1C4E5F60}'   # windows.upgradeUuid in build.gradle.kts
$installer = New-Object -ComObject WindowsInstaller.Installer

function Installed {
    $list = $installer.RelatedProducts($Upgrade)
    for ($k = 0; $k -lt $list.Count; $k++) { $list.Item($k) }
}
function Info($code, $name) { try { $installer.ProductInfo($code, $name) } catch { '' } }

foreach ($p in @(Installed)) {
    if ((Info $p 'AssignmentType') -eq '1') {
        Write-Host "install-here: Filet Desktop is installed for all users ($p). Remove that copy once from Settings > Apps; it needs an administrator. Builds then install per user on their own. Nothing installed."
        exit 0
    }
}

$running = @(Get-Process -Name 'Filet Desktop' -ErrorAction SilentlyContinue)
$sharing = $false
foreach ($r in $running) {
    if (Get-NetTCPConnection -State Listen -OwningProcess $r.Id -ErrorAction SilentlyContinue) { $sharing = $true }
    [void]$r.CloseMainWindow()
}
foreach ($r in $running) {
    # The window closes at once; the network goodbyes after it are capped at a few seconds.
    if (-not $r.WaitForExit(20000)) { Stop-Process -Id $r.Id -Force }
}

foreach ($p in @(Installed)) {
    $x = Start-Process msiexec.exe -ArgumentList "/x $p /qn /norestart" -Wait -PassThru
    if ($x.ExitCode -notin 0, 3010, 1605) { throw "install-here: removing $p failed with $($x.ExitCode)" }
}

$log = Join-Path $env:TEMP 'filet-desktop-install-here.log'
$i = Start-Process msiexec.exe -ArgumentList "/i `"$Msi`" /qn /norestart /l*v `"$log`"" -Wait -PassThru
if ($i.ExitCode -notin 0, 3010) { throw "install-here: installing failed with $($i.ExitCode). Log: $log" }

$now = @(Installed) | Select-Object -First 1
$version = Info $now 'VersionString'
$dir = Info $now 'InstallLocation'
if (-not $dir) { $dir = Join-Path $env:LOCALAPPDATA 'Filet Desktop' }
$exe = Join-Path $dir 'Filet Desktop.exe'
Write-Host "install-here: Filet Desktop $version installed at $dir"

if ($running.Count -gt 0 -and (Test-Path -LiteralPath $exe)) {
    if ($sharing) { Start-Process -FilePath $exe -ArgumentList '--share' } else { Start-Process -FilePath $exe }
    Write-Host ("install-here: reopened" + $(if ($sharing) { ', sharing again' } else { '' }))
}
