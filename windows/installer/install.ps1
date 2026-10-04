# Kanade（Windows 版）を入れる。管理者の PowerShell で、このファイルのあるフォルダ（配布物の Kanade フォルダ）から実行する
#   powershell -ExecutionPolicy Bypass -File install.ps1
# 本体は C:\Program Files\Kanade、辞書・モデルは %LOCALAPPDATA%\Kanade\data、設定は %APPDATA%\Kanade（消さない）
#requires -RunAsAdministrator
$ErrorActionPreference = 'Stop'
$src = $PSScriptRoot
$dest = Join-Path $env:ProgramFiles 'Kanade'
$clsid = '{92CD23E6-D514-4099-84D0-81969040EE41}'
$tip = "0411:$clsid{92A70E2C-B06A-4AD4-B6CB-7B6DF9CED95E}"

Write-Host "Kanade を $dest に入れます"

# 動いているエンジンを止める（ネイティブの DLL を置き換えられるように）
Get-Process java, javaw -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -like '*dev.mooma.kanade.desktop.MainKt*' } | Stop-Process -Force
Start-Sleep -Milliseconds 500

New-Item -ItemType Directory -Force $dest | Out-Null
foreach ($dir in 'runtime', 'lib', 'native') {
    $target = Join-Path $dest $dir
    if (Test-Path $target) { Remove-Item -Recurse -Force $target }
    Copy-Item -Recurse (Join-Path $src $dir) $target
}
Copy-Item (Join-Path $src 'uninstall.ps1') $dest -Force
Copy-Item (Join-Path $src 'THIRD-PARTY.md') $dest -Force -ErrorAction SilentlyContinue

# TSF の DLL は、使っているアプリが読み込んだままだと上書きできないので、毎回別の名前で置いて登録し直す（古いものは使われなくなってから消す）
$tsfDir = Join-Path $dest 'tsf'
New-Item -ItemType Directory -Force $tsfDir | Out-Null
$dll = Join-Path $tsfDir ('kanade_tsf-' + (Get-Date -Format 'yyyyMMddHHmmss') + '.dll')
Copy-Item (Join-Path $src 'tsf\kanade_tsf.dll') $dll
$p = Start-Process regsvr32 -ArgumentList '/s', "`"$dll`"" -Wait -PassThru
if ($p.ExitCode -ne 0) { throw "DLL を登録できませんでした（regsvr32 $($p.ExitCode)）" }
Get-ChildItem $tsfDir -Filter 'kanade_tsf-*.dll' | Where-Object { $_.FullName -ne $dll } | ForEach-Object { Remove-Item $_.FullName -ErrorAction SilentlyContinue }
# 32bit のアプリ用（32bit の regsvr32 で登録する）
$x86Src = Join-Path $src 'tsf\x86\kanade_tsf.dll'
if (Test-Path $x86Src) {
    $x86Dir = Join-Path $tsfDir 'x86'
    New-Item -ItemType Directory -Force $x86Dir | Out-Null
    $x86 = Join-Path $x86Dir ('kanade_tsf-' + (Get-Date -Format 'yyyyMMddHHmmss') + '.dll')
    Copy-Item $x86Src $x86
    $p = Start-Process (Join-Path $env:windir 'SysWOW64\regsvr32.exe') -ArgumentList '/s', "`"$x86`"" -Wait -PassThru
    if ($p.ExitCode -ne 0) { Write-Warning "32bit の DLL を登録できませんでした（regsvr32 $($p.ExitCode)）。64bit のアプリでは使えます" }
    Get-ChildItem $x86Dir -Filter 'kanade_tsf-*.dll' | Where-Object { $_.FullName -ne $x86 } | ForEach-Object { Remove-Item $_.FullName -ErrorAction SilentlyContinue }
}

# 辞書・zenz（ユーザーごと）。音声のモデルは配布物にあれば写す（なければ後から置く）
$data = Join-Path $env:LOCALAPPDATA 'Kanade\data'
New-Item -ItemType Directory -Force $data | Out-Null
Copy-Item -Recurse -Force (Join-Path $src 'data\*') $data

# エンジンの起動の命令（DLL がつながらないときに使う）と、ログオン時の起動
$java = Join-Path $dest 'runtime\bin\javaw.exe'
$command = "`"$java`" -Xmx512m -Dfile.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dsun.java2d.uiScale=1 -Dkanade.native=`"$dest\native`" -cp `"$dest\lib\*`" dev.mooma.kanade.desktop.MainKt serve"
New-Item -Force 'HKCU:\Software\Kanade' | Out-Null
Set-ItemProperty 'HKCU:\Software\Kanade' EngineCommand $command
Set-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' Kanade $command

# 日本語のキーボードの一覧に足す（MS-IME は残す。外すのは設定アプリから）
$list = Get-WinUserLanguageList
$ja = $list | Where-Object LanguageTag -eq 'ja'
if (-not $ja) {
    $list.Add('ja')
    $ja = $list | Where-Object LanguageTag -eq 'ja'
}
if ($ja.InputMethodTips -notcontains $tip) {
    $ja.InputMethodTips.Add($tip)
    Set-WinUserLanguageList $list -Force
}

Write-Host '入れました。Win+Space で「Kanade」を選ぶと使えます（エンジンは最初のキーで立ち上がります）'
