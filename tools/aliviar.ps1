<#
  Alivia o Fire TV: DESATIVA (nao desinstala) servicos da Amazon que correm em segundo plano e nao sao usados.
  Base: lista testada no Fire OS 8 pelo projeto Glass (github.com/ivantrj/firetv-glass-launcher), em versao conservadora.

    aplicar : desativa a lista e guarda em backup\aliviar-desativados.txt quais estavam ativos (para reverter so esses)
    reverter: volta a ativar exatamente os que este script desativou
    estado  : mostra o estado de cada pacote

  NAO toca em: transmissao do telemovel (Fling/DIAL), espelhamento (Miracast), atualizacoes de sistema,
  ecra inicial e definicoes da Amazon, Appstore, Alexa, nem em nenhuma app que use.
  Uso: powershell -ExecutionPolicy Bypass -File aliviar.ps1 -Acao aplicar|reverter|estado [-Ip 192.168.1.122]
#>
param([Parameter(Mandatory)][ValidateSet('aplicar', 'reverter', 'estado')][string]$Acao, [string]$Ip = '192.168.1.122')
. (Join-Path $PSScriptRoot 'adb-comum.ps1')
$t = Connect-FireTv $Ip

# Grupo -> pacotes. A lista tem de coincidir com UpdateWatch.RELIEVED no Lumen.
$grupos = [ordered]@{
  'Telemetria e analise (envio de dados de uso para a Amazon)' = @(
    'com.amazon.device.telemetry.emitter', 'com.amazon.dp.logger', 'com.amazon.neo.minerva', 'com.amazon.neo.minerva.logcat',
    'com.amazon.perfcollection', 'com.amazon.wirelessmetrics.service', 'com.amazon.connectivitydiag',
    'com.amazon.tv.developer.dataservice', 'com.amazon.tv.acr', 'com.amazon.ceviche', 'com.amazon.logan')
  'Anuncios e montras (publicidade, compras, pre-visualizacoes)' = @(
    'com.amazon.hybridadidservice', 'com.amazon.sneakpeek', 'com.amazon.kso.blackbird', 'com.amazon.shoptv.client',
    'com.amazon.shoptv.firetv.client', 'com.amazon.minitv.android.app', 'com.amazon.tv.ftvambient',
    'com.amazon.tv.earlyaccess', 'com.amazon.tv.releasenotes')
  'Servicos de fundo sem uso (Kindle, impressao, dicas)' = @(
    'com.amazon.sharingservice.android.client.proxy', 'com.amazon.ods.kindleconnect', 'com.amazon.tv.ottssocompanionapp',
    'com.android.printservice.recommendation', 'com.android.protips')
  'Apps e tutoriais pre-instalados que estao ocultos no Lumen' = @(
    'com.amazon.storm.lightning.tutorial', 'com.amazon.tmm.tutorial', 'com.amazon.tv.website_launcher', 'com.amazon.gamehub',
    'com.amazon.tahoe', 'com.amazon.hedwig', 'com.amazon.spiderpork', 'com.amazon.bueller.music', 'com.amazon.bueller.photos')
}
$backup = Join-Path $PSScriptRoot '..\backup\aliviar-desativados.txt'
$instalados = (Adb-Shell $t 'pm list packages') -split "`n" | ForEach-Object { $_.Trim() -replace '^package:', '' }
$desativados = (Adb-Shell $t 'pm list packages -d') -split "`n" | ForEach-Object { $_.Trim() -replace '^package:', '' }

switch ($Acao) {
  'estado' {
    foreach ($g in $grupos.Keys) {
      Write-Host "`n$g"
      foreach ($p in $grupos[$g]) {
        $e = if ($p -notin $instalados) { 'nao existe' } elseif ($p -in $desativados) { 'desativado' } else { 'ATIVO' }
        Write-Host ("  {0,-48} {1}" -f $p, $e)
      }
    }
  }
  'aplicar' {
    $feitos = @()
    foreach ($g in $grupos.Keys) {
      Write-Host "`n$g"
      foreach ($p in $grupos[$g]) {
        if ($p -notin $instalados) { continue }
        if ($p -in $desativados) { Write-Host "  $p  (ja estava desativado)"; continue }
        # Um comando pm por chamada: encadeados, o Fire OS pode parar depois do primeiro.
        $r = Adb-Shell $t "pm disable-user --user 0 $p"
        if ($r -match 'new state: disabled') { $feitos += $p; Write-Host "  $p  desativado" }
        else { Write-Host "  $p  recusado pelo Fire OS ($($r.Trim()))" }
      }
    }
    New-Item -ItemType Directory -Force (Split-Path $backup) | Out-Null
    $anteriores = if (Test-Path $backup) { Get-Content $backup } else { @() }
    ($anteriores + $feitos) | Sort-Object -Unique | Set-Content -Encoding utf8 $backup
    # Marca para o Lumen saber que o Stick foi aliviado (e avisar se uma atualizacao reativar servicos).
    Adb-Shell $t 'settings put global lumen_aliviado 1' | Out-Null
    Write-Host "`n$($feitos.Count) servicos desativados. Lista em $((Resolve-Path $backup).Path)"
  }
  'reverter' {
    if (-not (Test-Path $backup)) { throw 'Nada a reverter (sem backup).' }
    foreach ($p in Get-Content $backup) {
      $r = Adb-Shell $t "pm enable --user 0 $p"
      Write-Host "  $p  $(if ($r -match 'enabled') { 'reativado' } else { $r.Trim() })"
    }
    Adb-Shell $t 'settings delete global lumen_aliviado' | Out-Null
    Remove-Item $backup
    Write-Host 'Tudo reposto.'
  }
}
