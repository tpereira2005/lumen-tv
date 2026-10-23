<#
  Fase 1 - ajustes reversiveis do Fire TV.
    aplicar : guarda os valores atuais em backup\fase1-original.json (so na 1.a vez) e aplica os novos.
    reverter: repoe exatamente os valores guardados.
  Uso: powershell -ExecutionPolicy Bypass -File fase1.ps1 -Acao aplicar|reverter [-Ip 192.168.1.122]
#>
param(
  [Parameter(Mandatory)][ValidateSet('aplicar', 'reverter')][string]$Acao,
  [string]$Ip = '192.168.1.122'
)
. (Join-Path $PSScriptRoot 'adb-comum.ps1')
$target = Connect-FireTv $Ip

# namespace, chave, valor novo
$ajustes = @(
  @('global', 'hdmi_control_auto_device_off_enabled', '1'),   # TV desliga quando o Fire adormece
  @('global', 'window_animation_scale', '0.5'),
  @('global', 'transition_animation_scale', '0.5'),
  @('global', 'animator_duration_scale', '0.5')
)

$backupDir = Join-Path $PSScriptRoot '..\backup'
$backup = Join-Path $backupDir 'fase1-original.json'

if ($Acao -eq 'aplicar') {
  if (-not (Test-Path $backup)) {
    New-Item -ItemType Directory -Force $backupDir | Out-Null
    $orig = foreach ($a in $ajustes) {
      [pscustomobject]@{ ns = $a[0]; key = $a[1]; value = (Adb-Shell $target "settings get $($a[0]) $($a[1])").Trim() }
    }
    $orig | ConvertTo-Json | Out-File -Encoding utf8 $backup
    Write-Host "Valores originais guardados em $((Resolve-Path $backup).Path)"
  }
  foreach ($a in $ajustes) {
    Adb-Shell $target "settings put $($a[0]) $($a[1]) $($a[2])" | Out-Null
    Write-Host ("{0}/{1} = {2}" -f $a[0], $a[1], (Adb-Shell $target "settings get $($a[0]) $($a[1])").Trim())
  }
} else {
  if (-not (Test-Path $backup)) { throw "Sem backup em $backup" }
  foreach ($o in (Get-Content $backup -Raw | ConvertFrom-Json)) {
    # 'null' significa que a chave nao existia: apaga-a em vez de gravar o texto 'null'
    if ($o.value -eq 'null') { Adb-Shell $target "settings delete $($o.ns) $($o.key)" | Out-Null }
    else { Adb-Shell $target "settings put $($o.ns) $($o.key) $($o.value)" | Out-Null }
    Write-Host ("{0}/{1} reposto -> {2}" -f $o.ns, $o.key, (Adb-Shell $target "settings get $($o.ns) $($o.key)").Trim())
  }
}
