<#
  Mede a fluidez do Lumen com um percurso de navegacao fixo (sempre o mesmo, para comparar versoes).
  Uso: powershell -ExecutionPolicy Bypass -File perf.ps1 [-Amazon] [-Rondas 3] [-Ip 192.168.1.122]
    -Amazon: mede o ecra inicial original da Amazon (desativa o Lumen durante a medicao e reativa-o no fim)
#>
param([switch]$Amazon, [int]$Rondas = 3, [string]$Ip = '192.168.1.122')
. (Join-Path $PSScriptRoot 'adb-comum.ps1')
$t = Connect-FireTv $Ip
$pkg = if ($Amazon) { 'com.amazon.tv.launcher' } else { 'pt.tomas.lumen' }
$lumenScript = Join-Path $PSScriptRoot 'lumen.ps1'

Adb-Shell $t 'input keyevent KEYCODE_WAKEUP' | Out-Null
if ($Amazon) { & powershell -ExecutionPolicy Bypass -File $lumenScript -Acao desativar -Ip $Ip | Out-Null }
Adb-Shell $t 'input keyevent KEYCODE_HOME' | Out-Null
Start-Sleep 3
Adb-Shell $t "dumpsys gfxinfo $pkg reset" | Out-Null

# Percurso: favoritos para a direita e de volta, descer pelas apps, voltar acima.
$percurso = @('RIGHT') * 6 + @('LEFT') * 6 + @('DOWN') * 4 + @('RIGHT') * 3 + @('UP') * 4
for ($r = 0; $r -lt $Rondas; $r++) {
  # Um so comando de shell por ronda: os intervalos entre teclas ficam regulares (~250 ms).
  $cmds = ($percurso | ForEach-Object { "input keyevent KEYCODE_DPAD_$_; sleep 0.25" }) -join '; '
  Adb-Shell $t $cmds | Out-Null
}

$out = Adb-Shell $t "dumpsys gfxinfo $pkg"
if ($Amazon) { & powershell -ExecutionPolicy Bypass -File $lumenScript -Acao ativar -Ip $Ip | Out-Null; Adb-Shell $t 'input keyevent KEYCODE_HOME' | Out-Null }
$get = { param($re) if ($out -match $re) { $Matches[1] } else { '?' } }
[pscustomobject]@{
  Ecra        = $pkg
  Frames      = & $get 'Total frames rendered: (\d+)'
  Lentos      = & $get 'Janky frames: \d+ \(([\d.]+%)\)'
  p50         = & $get '50th percentile: (\d+ms)'
  p90         = & $get '90th percentile: (\d+ms)'
  p99         = & $get '99th percentile: (\d+ms)'
  'GPU p50'   = & $get '50th gpu percentile: (\d+ms)'
} | Format-List
