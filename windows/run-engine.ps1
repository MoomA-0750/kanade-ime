# to-fu で開発中のエンジンを動かす（タスクスケジューラの KanadeEngine から、ログオン中のセッションで呼ぶ）
# 記録は %LOCALAPPDATA%\Kanade\engine.log
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$log = Join-Path $env:LOCALAPPDATA 'Kanade\engine.log'
New-Item -ItemType Directory -Force (Split-Path $log) | Out-Null
cmd /c "`"$env:USERPROFILE\kanade\engine\bin\kanade-engine.bat`" serve > `"$log`" 2>&1"
