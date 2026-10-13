# Kanade（Windows 版）を外す。管理者の PowerShell で実行する
#   powershell -ExecutionPolicy Bypass -File "C:\Program Files\Kanade\uninstall.ps1" [-Purge]
# -Purge を付けると、設定・ユーザー辞書・学習（%APPDATA%\Kanade）と辞書・モデル（%LOCALAPPDATA%\Kanade）も消す
#requires -RunAsAdministrator
param([switch]$Purge)
$ErrorActionPreference = 'Continue'
$dest = Join-Path $env:ProgramFiles 'Kanade'
$tip = '0411:{92CD23E6-D514-4099-84D0-81969040EE41}{92A70E2C-B06A-4AD4-B6CB-7B6DF9CED95E}'

$list = Get-WinUserLanguageList
$ja = $list | Where-Object LanguageTag -eq 'ja'
if ($ja -and $ja.InputMethodTips -contains $tip) {
    [void]$ja.InputMethodTips.Remove($tip)
    Set-WinUserLanguageList $list -Force
}
Get-ChildItem (Join-Path $dest 'tsf\x86') -Filter 'kanade_tsf-*.dll' -ErrorAction SilentlyContinue | ForEach-Object {
    Start-Process (Join-Path $env:windir 'SysWOW64\regsvr32.exe') -ArgumentList '/s', '/u', "`"$($_.FullName)`"" -Wait
}
Get-ChildItem (Join-Path $dest 'tsf') -Filter 'kanade_tsf-*.dll' -ErrorAction SilentlyContinue | ForEach-Object {
    Start-Process regsvr32 -ArgumentList '/s', '/u', "`"$($_.FullName)`"" -Wait
}
Get-Process java, javaw -ErrorAction SilentlyContinue | Where-Object { $_.CommandLine -like '*dev.mooma.kanade.desktop.MainKt*' } | Stop-Process -Force
Remove-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' Kanade -ErrorAction SilentlyContinue
Get-Process Kanade.Settings -ErrorAction SilentlyContinue | Stop-Process -Force
Remove-Item (Join-Path ([Environment]::GetFolderPath('CommonPrograms')) 'Kanade の設定.lnk') -ErrorAction SilentlyContinue
Remove-Item -Recurse 'HKCU:\Software\Kanade' -ErrorAction SilentlyContinue

# 使っているアプリが DLL を読み込んだままだと消せない。消せなかったものは再起動のあとに消す
Remove-Item -Recurse -Force $dest -ErrorAction SilentlyContinue
if (Test-Path $dest) {
    Write-Host "一部のファイルは使用中で消せませんでした。サインインし直したあとに $dest を消してください"
}
if ($Purge) {
    Remove-Item -Recurse -Force (Join-Path $env:APPDATA 'Kanade') -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force (Join-Path $env:LOCALAPPDATA 'Kanade') -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force (Join-Path $env:USERPROFILE '.kanade') -ErrorAction SilentlyContinue
}
Write-Host '外しました'
