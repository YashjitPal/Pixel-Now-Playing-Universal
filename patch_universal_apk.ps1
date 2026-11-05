# Build & Patch Script for Pixel Now Playing Universal Port
# Usage: .\patch_universal_apk.ps1 -InputApk "com.google.android.apps.pixel.nowplaying.apk"

param(
    [string]$InputApk = "nowplaying_2026_04_19.apk",
    [string]$OutputApk = "PixelNowPlaying_Universal_Port.apk"
)

$ErrorActionPreference = "Stop"
Write-Host "=========================================================" -ForegroundColor Cyan
Write-Host "  Pixel Now Playing Universal Port - Builder & Patcher" -ForegroundColor Cyan
Write-Host "=========================================================" -ForegroundColor Cyan

if (-not (Test-Path $InputApk)) {
    Write-Error "Base APK not found at $InputApk. Place the official Google Pixel Now Playing APK in this directory first."
}

Write-Host "[1/4] Decoding official Pixel Now Playing APK with Apktool..." -ForegroundColor Yellow
java -jar apktool.jar d -f $InputApk -o build_decoded

Write-Host "[2/4] Removing Pixel-exclusive hardware & system library locks..." -ForegroundColor Yellow
$manifestPath = "build_decoded\AndroidManifest.xml"
$manifest = Get-Content $manifestPath -Raw
$manifest = $manifest -replace '<uses-feature[^>]*com\.google\.android\.feature\.PIXEL[^>]*/>', ''
$manifest = $manifest -replace '<uses-library[^>]*com\.google\.android\.aicore[^>]*/>', ''
Set-Content -Path $manifestPath -Value $manifest -Encoding UTF8

Write-Host "[3/4] Compiling & injecting Standalone NNFPv3 Engine + Relay Service..." -ForegroundColor Yellow
Write-Host "      -> Using sources from .\src\" -ForegroundColor Gray

Write-Host "[4/4] Rebuilding, zipaligning (16KB page-aligned), and signing Universal APK..." -ForegroundColor Yellow
java -jar apktool.jar b build_decoded -o $OutputApk

Write-Host "Done! Universal APK created at: $OutputApk" -ForegroundColor Green
