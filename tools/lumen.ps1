<#
  Instala / ativa / remove o launcher Lumen no Fire TV.
    instalar : instala (ou atualiza) o APK, compila-o (AOT) e ativa o servico do botao Home
    ativar   : so (re)ativa o servico do botao Home (ex.: depois de um force-stop, que o desliga)
    desativar: desliga so o servico do botao Home (o ecra da Amazon volta a ser o inicial)
    protetor : usa o ecra ambiente do Lumen como protecao de ecra (guarda a original em backup\)
    protetor-amazon: repoe a protecao de ecra original da Amazon
    remover  : desliga o servico, repoe a protecao de ecra e desinstala o Lumen (estado original)
  Uso: powershell -ExecutionPolicy Bypass -File lumen.ps1 -Acao <acao> [-Ip 192.168.1.122]
#>
param(
  [Parameter(Mandatory)][ValidateSet('instalar', 'ativar', 'desativar', 'protetor', 'protetor-amazon', 'remover')][string]$Acao,
  [string]$Ip = '192.168.1.122'
)
. (Join-Path $PSScriptRoot 'adb-comum.ps1')
$target = Connect-FireTv $Ip
$pkg = 'pt.tomas.lumen'
$svc = "$pkg/$pkg.HomeGuardService"
$apk = Join-Path $PSScriptRoot '..\android\app\build\outputs\apk\release\app-release.apk'

function Get-Services { (Adb-Shell $target 'settings get secure enabled_accessibility_services').Trim() }

function Set-Services([string[]]$list) {
  if ($list.Count -eq 0) {
    Adb-Shell $target 'settings delete secure enabled_accessibility_services' | Out-Null
    Adb-Shell $target 'settings put secure accessibility_enabled 0' | Out-Null
  } else {
    Adb-Shell $target "settings put secure enabled_accessibility_services $($list -join ':')" | Out-Null
    Adb-Shell $target 'settings put secure accessibility_enabled 1' | Out-Null
  }
}

$dream = "$pkg/$pkg.ambient.AmbientDream"
$dreamBackup = Join-Path $PSScriptRoot '..\backup\protetor-original.txt'

function Restore-Screensaver {
  if (Test-Path $dreamBackup) {
    $orig = (Get-Content $dreamBackup -Raw).Trim()
    Adb-Shell $target "settings put secure screensaver_components $orig" | Out-Null
    Write-Host "Protecao de ecra reposta: $orig"
  }
}

# Mantem os servicos de acessibilidade que ja existiam (ex.: VoiceView) e so mexe no nosso.
$current = @((Get-Services) -split ':' | Where-Object { $_ -and $_ -ne 'null' -and $_ -ne $svc })

switch ($Acao) {
  'instalar' {
    & $script:adb -s $target install -r $apk
    # Loja Lumen: autoriza o Lumen a entregar APKs ao instalador (o sistema pede sempre confirmacao).
    Adb-Shell $target "appops set $pkg REQUEST_INSTALL_PACKAGES allow" | Out-Null
    # Estatisticas de uso: o servico sabe se o ecra da Amazon ficou a vista quando (re)arranca.
    Adb-Shell $target "appops set $pkg GET_USAGE_STATS allow" | Out-Null
    # Compilacao antecipada: sem ela o Compose corre em JIT nos primeiros minutos (mais engasgos).
    Adb-Shell $target "cmd package compile -m speed -f $pkg" | Out-Null
    Set-Services ($current + $svc)
    Write-Host "Servicos ativos: $(Get-Services)"
    Adb-Shell $target "am start -n $pkg/.MainActivity" | Out-Null
  }
  'ativar' {
    Set-Services ($current + $svc)
    Write-Host "Servicos ativos: $(Get-Services)"
  }
  'desativar' {
    Set-Services $current
    Write-Host "Servicos ativos: $(Get-Services)"
  }
  'protetor' {
    $orig = (Adb-Shell $target 'settings get secure screensaver_components').Trim()
    if ($orig -ne $dream -and -not (Test-Path $dreamBackup)) {
      New-Item -ItemType Directory -Force (Split-Path $dreamBackup) | Out-Null
      Set-Content -Encoding utf8 $dreamBackup $orig
    }
    Adb-Shell $target "settings put secure screensaver_components $dream" | Out-Null
    Write-Host "Protecao de ecra: $((Adb-Shell $target 'settings get secure screensaver_components').Trim())"
  }
  'protetor-amazon' { Restore-Screensaver }
  'remover' {
    Restore-Screensaver
    Adb-Shell $target "appops set $pkg REQUEST_INSTALL_PACKAGES default" | Out-Null
    Adb-Shell $target "appops set $pkg GET_USAGE_STATS default" | Out-Null
    Set-Services $current
    & $script:adb -s $target uninstall $pkg
    Write-Host 'Lumen removido; o ecra da Amazon volta a ser o inicial.'
  }
}
