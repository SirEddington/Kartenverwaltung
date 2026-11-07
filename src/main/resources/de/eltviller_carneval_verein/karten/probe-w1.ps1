# Spike KARV-131, wird nicht gemergt: Weg W1. Wird von der App gestartet und protokolliert jeden Schritt.
# Nur ASCII, damit Windows PowerShell 5.1 die Datei ohne BOM richtig liest.
param([string]$Pattern, [string]$Log)
function L($m) { Add-Content -Path $Log -Value ("{0:HH:mm:ss} {1}" -f (Get-Date), $m) }
try {
    L "Helfer gestartet, PID $PID, Muster $Pattern"
    $pkg = Get-AppxPackage -Name $Pattern | Select-Object -First 1
    if (-not $pkg) { L "FEHLER Paket nicht gefunden"; exit 1 }
    L "Paket $($pkg.PackageFullName), Version $($pkg.Version)"
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    $null = [Windows.Management.Deployment.PackageManager, Windows.Management.Deployment, ContentType = WindowsRuntime]
    $null = [Windows.ApplicationModel.PackageUpdateAvailabilityResult, Windows.ApplicationModel, ContentType = WindowsRuntime]
    $pm = New-Object Windows.Management.Deployment.PackageManager
    $p = $pm.FindPackageForUser('', $pkg.PackageFullName)
    $info = $p.GetAppInstallerInfo()
    L "AppInstaller-Quelle: $(if ($info) { $info.Uri } else { 'keine' })"
    $asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } |
        Select-Object -First 1
    $task = $asTask.MakeGenericMethod([Windows.ApplicationModel.PackageUpdateAvailabilityResult]).Invoke($null, @($p.CheckUpdateAvailabilityAsync()))
    $null = $task.Wait(30000)
    L "CheckUpdateAvailability: $($task.Result.Availability) (Fehler: $($task.Result.ExtendedError))"
    $xml = [xml](Invoke-WebRequest -UseBasicParsing -Uri $info.Uri).Content
    $msix = $xml.AppInstaller.MainPackage.Uri
    L "Neueste Version laut .appinstaller: $($xml.AppInstaller.MainPackage.Version), Paket $msix"
    $aumid = (Get-StartApps | Where-Object { $_.AppID -like "$($pkg.PackageFamilyName)!*" } | Select-Object -First 1).AppID
    L "AUMID $aumid, starte Add-AppxPackage -ForceApplicationShutdown"
    Add-AppxPackage -Path $msix -ForceApplicationShutdown -ErrorAction Stop
    L "installiert: $((Get-AppxPackage -Name $Pattern | Select-Object -First 1).Version)"
    Start-Process "shell:AppsFolder\$aumid"
    L "App neu gestartet"
}
catch { L "FEHLER $($_.Exception.Message)" }
