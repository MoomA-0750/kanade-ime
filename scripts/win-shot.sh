#!/bin/bash
# to-fu の窓を撮って手元に置く（開発用）。scripts/win-shot.sh <プロセス名> <出力.png>
# 引数はプロセス名（例: Kanade.Settings）。画面のセッションで動かすため、タスクスケジューラ（KanadeShotWin）から撮る。PrintWindow なので隠れていても撮れる
set -euo pipefail
cd "$(dirname "$0")/.."
PROC=$1
OUT=$2
scp -q -o BatchMode=yes windows/tools/winshot.ps1 tofu:kanade/winshot.ps1
ssh -o BatchMode=yes tofu "Remove-Item C:\\Users\\MoomA\\kanade\\win.png* -ErrorAction SilentlyContinue; \$a = New-ScheduledTaskAction -Execute powershell.exe -Argument '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File C:\\Users\\MoomA\\kanade\\winshot.ps1 -Process $PROC'; \$p = New-ScheduledTaskPrincipal -UserId \$env:USERNAME -LogonType Interactive -RunLevel Limited; Register-ScheduledTask -TaskName KanadeShotWin -Action \$a -Principal \$p -Force | Out-Null; Start-ScheduledTask KanadeShotWin; for (\$i = 0; \$i -lt 30 -and -not (Test-Path C:\\Users\\MoomA\\kanade\\win.png.txt); \$i++) { Start-Sleep -Milliseconds 300 }; Get-Content C:\\Users\\MoomA\\kanade\\win.png.txt" | tr -d '\r'
scp -q -o BatchMode=yes tofu:kanade/win.png "$OUT" 2>/dev/null || true
