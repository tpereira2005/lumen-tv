<#
  Instala / atualiza o Stremio (Android TV, ARM 32-bit) a partir da fonte oficial.
  1. Le stremio.com/downloads e escolhe a versao "Android TV, Latest" (ignora RC/beta).
  2. Descarrega de dl.strem.io para uma pasta isolada.
  3. So instala se a assinatura for a do Stremio (certificado fixado abaixo) e a versao for mais recente.
  Uso: powershell -ExecutionPolicy Bypass -File stremio-atualizar.ps1 [-Ip 192.168.1.122] [-Forcar]
#>
param([string]$Ip = '192.168.1.122', [switch]$Forcar)
. (Join-Path $PSScriptRoot 'adb-comum.ps1')

# Certificado de assinatura oficial (CN=Ivo Georgiev, O=Stremio, Sofia), verificado em 2026-10-23.
$CertEsperado = '7e6a979c968f771e3fbcf2c2e8718ce61e708d87caf91fc13e2d4c19a8022c6b'
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot'
$bt = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools", "$env:LOCALAPPDATA\Packages\Claude_pzs8sxrjxfjjc\LocalCache\Local\Android\Sdk\build-tools" `
        -Recurse -Filter apksigner.bat -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty DirectoryName
if (-not $bt) { throw 'apksigner nao encontrado (Android SDK build-tools).' }

$html = (Invoke-WebRequest -UseBasicParsing 'https://www.stremio.com/downloads').Content
$m = [regex]::Matches($html, 'https://dl\.strem\.io/android/v(\d+\.\d+\.\d+)-androidTV/com\.stremio\.one-[^"''\s]+-armeabi-v7a\.apk') |
     Sort-Object { [version]$_.Groups[1].Value } -Descending | Select-Object -First 1
if (-not $m) { throw 'Nao encontrei o APK Android TV (ARM) em stremio.com/downloads.' }
$url = $m.Value; $versao = $m.Groups[1].Value
Write-Host "Ultima versao oficial: $versao"

$target = Connect-FireTv $Ip
$instalada = ((Adb-Shell $target 'dumpsys package com.stremio.one | grep versionName') -replace '.*versionName=', '').Trim()
Write-Host "Instalada no Fire TV: $(if ($instalada) { $instalada } else { 'nenhuma' })"
if ($instalada -and -not $Forcar -and [version]($instalada -replace '[^\d.].*$', '') -ge [version]$versao) {
  Write-Host 'Ja esta atualizado.'; return
}

$dir = Join-Path $env:USERPROFILE 'stremio-untrusted'
New-Item -ItemType Directory -Force $dir | Out-Null
$apk = Join-Path $dir "stremio-$versao.apk"
Invoke-WebRequest -UseBasicParsing $url -OutFile $apk

$certs = & "$bt\apksigner.bat" verify --print-certs $apk 2>&1
if ($LASTEXITCODE -ne 0) { throw "Assinatura invalida:`n$certs" }
$digest = ($certs | Select-String 'Signer #1 certificate SHA-256 digest: (\w+)').Matches[0].Groups[1].Value
if ($digest -ne $CertEsperado) { throw "Certificado inesperado ($digest). Instalacao cancelada." }
Write-Host 'Assinatura confirmada: Stremio oficial.'

& $script:adb -s $target install -r $apk
