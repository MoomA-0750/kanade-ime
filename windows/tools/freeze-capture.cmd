@echo off
rem Kanade: freeze-capture.ps1 を、同じフォルダーから実行する（ダブルクリック用）。引数はそのまま渡す（例: -Dump / -Arm）
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0freeze-capture.ps1" %*
pause
