# Funcoes partilhadas pelos scripts (carregar com: . .\adb-comum.ps1)
$script:adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $script:adb) {
  $script:adb = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Recurse -Filter adb.exe -ErrorAction SilentlyContinue |
                Select-Object -First 1 -ExpandProperty FullName
}
if (-not $script:adb) { throw 'adb.exe nao encontrado.' }

function Connect-FireTv([string]$Ip) {
  $t = if ($Ip -match ':') { $Ip } else { "${Ip}:5555" }
  & $script:adb connect $t | Out-Null
  $s = (& $script:adb -s $t get-state 2>&1) -join ''
  if ($s -ne 'device') { throw "Fire TV nao ligado ($t): $s" }
  return $t
}

function Adb-Shell([string]$Target, [string]$Cmd) {
  # O PowerShell 5.1 remove aspas duplas ao passar argumentos a .exe; o sh remoto aceita aspas simples.
  ((& $script:adb -s $Target shell ($Cmd -replace '"', "'")) 2>&1) -join "`n"
}
