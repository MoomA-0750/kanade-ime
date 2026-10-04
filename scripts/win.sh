#!/bin/bash
# Windows 版を to-fu（Windows 11）で作って試す。手元（Linux）から ssh で投げる（emu.sh と同じやり方）
#   scripts/win.sh engine           エンジン（Kotlin）を手元でビルドして to-fu へ送る
#   scripts/win.sh data             辞書・zenz のモデルを to-fu の %LOCALAPPDATA%\Kanade\data へ送る
#   scripts/win.sh tsf [Release]    TSF の DLL（windows/tsf）を to-fu の MSVC でビルドする
#   scripts/win.sh eng <args...>    to-fu でエンジンを動かす（例: eng type '{Henkan}nihongo'）
#   scripts/win.sh reg | unreg      ビルドした DLL を登録して日本語のキーボードの一覧に足す/外す（管理者。ssh は管理者で入る）
#                                   使用中の DLL は上書きできないので、毎回別の名前（tsf-bin\kanade_tsf-<時刻>.dll）に写して登録し直す
#   scripts/win.sh start | stop     to-fu のログオン中のセッションでエンジンを常駐させる/止める（タスク KanadeEngine）
#   scripts/win.sh type [--rich] '<キー列>'  試験の窓（kanade_typetest）をログオン中のセッションで開いて打ち、入力欄の中身を出す
#   scripts/win.sh log              エンジンの記録を出す
#   scripts/win.sh ps '<command>'   to-fu で PowerShell の命令を実行
# to-fu の置き場所: C:\Users\MoomA\kanade（engine・tsf-src・tsf-build）。JDK は Android Studio 同梱の JBR 21 を使う
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/opt/jdk-21.0.12.1+1}
REMOTE='C:\Users\MoomA\kanade'
JBR='C:\Program Files\Android\Android Studio\jbr'
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

ps() { ssh -o BatchMode=yes tofu "[Console]::OutputEncoding=[Text.Encoding]::UTF8; $1" | tr -d '\r'; }

case "$1" in
  engine)
    "$0" stop >/dev/null
    ./gradlew -q :desktop:installDist
    tar -C desktop/build/install -czf "$TMP/engine.tgz" kanade-engine
    ps "New-Item -ItemType Directory -Force $REMOTE | Out-Null"
    scp -q -o BatchMode=yes "$TMP/engine.tgz" "tofu:kanade/engine.tgz"
    ps "Set-Location $REMOTE; if (Test-Path engine) { Remove-Item -Recurse -Force engine }; tar -xzf engine.tgz; Rename-Item kanade-engine engine; Remove-Item engine.tgz"
    echo "engine -> $REMOTE\\engine" ;;
  data)
    A=app/build/generated/kanadeAssets
    ps "New-Item -ItemType Directory -Force \$env:LOCALAPPDATA\\Kanade\\data | Out-Null"
    scp -q -o BatchMode=yes $A/system.kdic.kgz $A/connection.kcon.kgz $A/pos.kpos $A/symbol.ksym $A/emoji.ksym $A/zenz.gguf "tofu:AppData/Local/Kanade/data/"
    echo "data -> %LOCALAPPDATA%\\Kanade\\data" ;;
  tsf)
    CONFIG=${2:-Debug}
    tar -C windows -czf "$TMP/tsf.tgz" tsf
    ps "New-Item -ItemType Directory -Force $REMOTE | Out-Null"
    scp -q -o BatchMode=yes "$TMP/tsf.tgz" "tofu:kanade/tsf.tgz"
    ps "Set-Location $REMOTE; if (Test-Path tsf-src) { Remove-Item -Recurse -Force tsf-src }; tar -xzf tsf.tgz; Rename-Item tsf tsf-src; Remove-Item tsf.tgz"
    ps "cmd /c $REMOTE\\tsf-src\\build.cmd $CONFIG" ;;
  eng)
    shift
    args=""
    for a in "$@"; do args+=" '${a//\'/\'\'}'"; done
    ps "\$env:JAVA_HOME='$JBR'; [Console]::OutputEncoding=[Text.Encoding]::UTF8; & $REMOTE\\engine\\bin\\kanade-engine.bat$args" ;;
  reg)
    ps "\$bin = '$REMOTE\\tsf-bin'; New-Item -ItemType Directory -Force \$bin | Out-Null; \$dll = Join-Path \$bin ('kanade_tsf-' + (Get-Date -Format 'MMddHHmmss') + '.dll'); Copy-Item $REMOTE\\tsf-build\\kanade_tsf.dll \$dll; icacls \$bin /grant '*S-1-15-2-1:(OI)(CI)(RX)' '*S-1-15-2-2:(OI)(CI)(RX)' /T /Q | Out-Null; \$p = Start-Process regsvr32 -ArgumentList '/s',\$dll -Wait -PassThru; 'regsvr32 ' + \$p.ExitCode + ' ' + \$dll; \$tip = '0411:{92CD23E6-D514-4099-84D0-81969040EE41}{92A70E2C-B06A-4AD4-B6CB-7B6DF9CED95E}'; \$l = Get-WinUserLanguageList; \$ja = \$l | Where-Object LanguageTag -eq 'ja'; if (\$ja.InputMethodTips -notcontains \$tip) { \$ja.InputMethodTips.Add(\$tip); Set-WinUserLanguageList \$l -Force }; (Get-WinUserLanguageList | Where-Object LanguageTag -eq 'ja').InputMethodTips; Get-ChildItem \$bin -Filter *.dll | Sort-Object LastWriteTime -Descending | Select-Object -Skip 5 | Remove-Item -ErrorAction SilentlyContinue" ;;
  unreg)
    ps "\$tip = '0411:{92CD23E6-D514-4099-84D0-81969040EE41}{92A70E2C-B06A-4AD4-B6CB-7B6DF9CED95E}'; \$l = Get-WinUserLanguageList; \$ja = \$l | Where-Object LanguageTag -eq 'ja'; [void]\$ja.InputMethodTips.Remove(\$tip); Set-WinUserLanguageList \$l -Force; \$dll = (Get-ItemProperty 'HKLM:\\Software\\Classes\\CLSID\\{92CD23E6-D514-4099-84D0-81969040EE41}\\InprocServer32' -ErrorAction SilentlyContinue).'(default)'; if (\$dll) { \$p = Start-Process regsvr32 -ArgumentList '/s','/u',\$dll -Wait -PassThru; 'unregistered ' + \$p.ExitCode + ' ' + \$dll }" ;;
  start)
    # エンジンの起動の命令を HKCU\Software\Kanade\EngineCommand に書き（DLL がつながらないときに使う）、タスクで起動する
    ps "\$java = '$JBR\\bin\\javaw.exe'; \$jargs = '-Xmx512m -Dfile.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dsun.java2d.uiScale=1 -cp \"$REMOTE\\engine\\lib\\*\" dev.mooma.kanade.desktop.MainKt serve'; New-Item -Force HKCU:\\Software\\Kanade | Out-Null; Set-ItemProperty HKCU:\\Software\\Kanade EngineCommand ('\"' + \$java + '\" ' + \$jargs); \$a = New-ScheduledTaskAction -Execute \$java -Argument \$jargs; \$p = New-ScheduledTaskPrincipal -UserId \$env:USERNAME -LogonType Interactive -RunLevel Limited; Register-ScheduledTask -TaskName KanadeEngine -Action \$a -Principal \$p -Force | Out-Null; Start-ScheduledTask KanadeEngine; Start-Sleep 3; Get-Content -Encoding UTF8 \$env:LOCALAPPDATA\\Kanade\\engine.log -Tail 3" ;;
  stop)
    ps "Stop-ScheduledTask KanadeEngine -ErrorAction SilentlyContinue; Get-Process java,javaw -ErrorAction SilentlyContinue | Where-Object { \$_.CommandLine -like '*desktop.MainKt*' } | Stop-Process -Force; 'stopped'" ;;
  type)
    RICH=""
    if [ "${2:-}" = "--rich" ]; then RICH="--rich"; shift; fi
    printf '%s' "$2" > "$TMP/in.txt"
    scp -q -o BatchMode=yes "$TMP/in.txt" "tofu:kanade/typetest-in.txt"
    ps "Get-Process kanade_typetest -ErrorAction SilentlyContinue | Stop-Process -Force; Remove-Item $REMOTE\\typetest-out.txt -ErrorAction SilentlyContinue; \$a = New-ScheduledTaskAction -Execute '$REMOTE\\tsf-build\\kanade_typetest.exe' -Argument '$RICH --in $REMOTE\\typetest-in.txt --out $REMOTE\\typetest-out.txt'; \$p = New-ScheduledTaskPrincipal -UserId \$env:USERNAME -LogonType Interactive -RunLevel Limited; Register-ScheduledTask -TaskName KanadeTypeTest -Action \$a -Principal \$p -Force | Out-Null; Start-ScheduledTask KanadeTypeTest; for (\$i = 0; \$i -lt 120 -and -not (Test-Path $REMOTE\\typetest-out.txt); \$i++) { Start-Sleep -Milliseconds 500 }; Start-Sleep -Milliseconds 300; Get-Content -Encoding UTF8 $REMOTE\\typetest-out.txt" ;;
  log)
    ps "Get-Content -Encoding UTF8 \$env:LOCALAPPDATA\\Kanade\\engine.log -Tail 40" ;;
  ps)
    ps "$2" ;;
  *)
    sed -n 2,9p "$0"; exit 2 ;;
esac
