# Copies the phone's Documents/FileManagerTraining folder into this repo under training-data/<device>/.
# Needs adb (default: E:\Sdk30\Sdk\platform-tools) and a phone with USB debugging.
# Review the folder before committing: samples/ contains small thumbnails of personal photos and videos.
param(
  [string]$Adb = "E:\Sdk30\Sdk\platform-tools\adb.exe",
  [string]$Serial = ""
)
$sel = if ($Serial) { @("-s", $Serial) } else { @() }
$model = (& $Adb @sel shell getprop ro.product.model).Trim()
$dest = Join-Path (Split-Path $PSScriptRoot -Parent) "training-data\$model"
New-Item -ItemType Directory -Force $dest | Out-Null
& $Adb @sel pull /sdcard/Documents/FileManagerTraining/. $dest
Write-Host "Pulled to $dest"
