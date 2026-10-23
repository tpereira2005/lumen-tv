<#
  Fase 0 - Diagnostico do Fire TV Stick (SO LEITURA).
  Nenhum comando aqui altera definicoes, apps ou servicos do dispositivo.
  Uso:  powershell -ExecutionPolicy Bypass -File diagnostico.ps1 -Ip 192.168.1.X
#>
param([Parameter(Mandatory)][string]$Ip)

$ErrorActionPreference = 'Continue'
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
  $adb = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Recurse -Filter adb.exe -ErrorAction SilentlyContinue |
         Select-Object -First 1 -ExpandProperty FullName
}
if (-not $adb) { throw 'adb.exe nao encontrado.' }

$target = if ($Ip -match ':') { $Ip } else { "${Ip}:5555" }
$out = Join-Path $PSScriptRoot ("..\diagnostico\" + (Get-Date -Format 'yyyy-MM-dd_HHmm'))
New-Item -ItemType Directory -Force $out | Out-Null

Write-Host "A ligar a $target ... (aceite o pedido 'Permitir depuracao USB' na TV)"
& $adb connect $target | Write-Host
$state = (& $adb -s $target get-state 2>&1) -join ''
if ($state -ne 'device') { throw "Ligacao nao autorizada ou falhou (estado: $state). Aceite o pedido na TV e volte a correr." }

function Run([string]$name, [string]$cmd) {
  Write-Host "  - $name"
  # O PowerShell 5.1 remove aspas duplas ao passar argumentos a .exe; o sh remoto aceita aspas simples.
  $cmd = $cmd -replace '"', "'"
  & $adb -s $target shell $cmd 2>&1 | Out-File -Encoding utf8 (Join-Path $out "$name.txt")
}

# Sistema e hardware
Run 'props'            'getprop'
Run 'cpuinfo'          'cat /proc/cpuinfo'
Run 'meminfo'          'cat /proc/meminfo'
Run 'kernel'           'uname -a; cat /proc/version'
Run 'storage'          'df -h'
Run 'uptime'           'uptime'
Run 'display'          'wm size; wm density; dumpsys display | grep -iE "mode|hdr|refresh" | head -60'
Run 'features'         'pm list features'

# Carga e desempenho
Run 'top'              'top -b -n 1 -m 30'
Run 'meminfo_sistema'  'dumpsys meminfo -s'
Run 'cpu_dumpsys'      'dumpsys cpuinfo | head -50'
Run 'animacoes'        'settings get global window_animation_scale; settings get global transition_animation_scale; settings get global animator_duration_scale'
Run 'processos_bg'     'dumpsys activity processes | grep -E "^ +\*APP|PERS|Proc #" | head -120'

# Pacotes
Run 'pacotes_todos'    'pm list packages -f -U'
Run 'pacotes_sistema'  'pm list packages -s'
Run 'pacotes_3p'       'pm list packages -3'
Run 'pacotes_desativ'  'pm list packages -d'
Run 'apps_utilizador'  'for p in com.netflix.ninja com.amazon.firetv.youtube com.google.android.youtube.tv com.stremio.one ar.tvplayer.tv com.spotify.tv.android; do echo "== $p"; dumpsys package $p | grep -E "versionName|targetSdk" | head -2; done; echo "== vodafone:"; pm list packages | grep -i vodafone'

# Launcher e restricoes relevantes
Run 'home_resolve'     'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME; echo ---; cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
Run 'launcher_amazon'  'for p in com.amazon.tv.launcher com.amazon.firehomestarter com.amazon.tv.settings.v2; do echo "== $p"; dumpsys package $p | grep -E "versionName|enabled=|pkgFlags|privateFlags" | head -6; done'
Run 'acessibilidade'   'settings get secure enabled_accessibility_services; settings get secure accessibility_enabled; dumpsys accessibility | head -60'
Run 'atividade_topo'   'dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity|mFocusedApp" | head -5; dumpsys window | grep -E "mCurrentFocus" '
Run 'settings_global'  'settings list global'
Run 'settings_secure'  'settings list secure'

# HDMI-CEC, comando e entrada
Run 'hdmi_cec'         'dumpsys hdmi_control'
Run 'hdmi_cec_cmd'     'cmd hdmi_control help 2>&1 | head -40'
Run 'input'            'dumpsys input | grep -E "Device [0-9]+:|Name:|Sources:|KeyLayoutFile" | head -80'
Run 'bluetooth'        'dumpsys bluetooth_manager | grep -iE "name|bonded|connected|address" | head -40'

# DRM (so leitura)
Run 'drm'              'dumpsys media.drm 2>&1 | head -40; getprop | grep -iE "widevine|drm|hdcp"'

# Falhas recentes
Run 'crashes'          'logcat -d -b crash -t 300'

Write-Host "`nRelatorio guardado em: $((Resolve-Path $out).Path)"
