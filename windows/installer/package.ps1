# 配布物を to-fu で組み立てる（scripts/win.sh package から呼ぶ。開発用）
# 入力: <root>\dist\Kanade（手元から送ったエンジン・辞書・インストーラー）、<root>\jdk（Temurin 21）、<root>\native、<root>\tsf-build-release・tsf-build-x86、<root>\settings
param([string]$Root = "$env:USERPROFILE\kanade")
$ErrorActionPreference = 'Stop'
$d = Join-Path $Root 'dist\Kanade'
& (Join-Path $Root 'jdk\bin\jlink.exe') --add-modules java.base,java.desktop --strip-debug --no-header-files --no-man-pages `
    --compress zip-6 --output (Join-Path $d 'runtime')
New-Item -ItemType Directory -Force (Join-Path $d 'native'), (Join-Path $d 'tsf') | Out-Null
Copy-Item (Join-Path $Root 'native\kanade_zenz.dll'), (Join-Path $Root 'native\kanade_whisper.dll') (Join-Path $d 'native')
Copy-Item (Join-Path $Root 'tsf-build-release\kanade_tsf.dll') (Join-Path $d 'tsf')
# 32bit のアプリ用の DLL
New-Item -ItemType Directory -Force (Join-Path $d 'tsf\x86') | Out-Null
Copy-Item (Join-Path $Root 'tsf-build-x86\kanade_tsf.dll') (Join-Path $d 'tsf\x86')
# 設定の画面（WinUI 3。scripts/win.sh settings で作ったもの）
Copy-Item -Recurse (Join-Path $Root 'settings') (Join-Path $d 'settings')
# 音声のモデルは、この PC に置いてあるもの（whisper small・ReazonSpeech）を写す
$models = Join-Path $env:LOCALAPPDATA 'Kanade\data'
if (Test-Path "$models\ggml-small-q5_1.bin") { Copy-Item "$models\ggml-small-q5_1.bin" (Join-Path $d 'data') }
if (Test-Path "$models\reazonspeech") { Copy-Item -Recurse "$models\reazonspeech" (Join-Path $d 'data') }
$zip = Join-Path $Root 'dist\Kanade-windows-x64.zip'
if (Test-Path $zip) { Remove-Item $zip }
Compress-Archive -Path $d -DestinationPath $zip
'{0:N0} MB' -f ((Get-ChildItem -Recurse $d | Measure-Object Length -Sum).Sum / 1MB)
'{0:N0} MB (zip) {1}' -f ((Get-Item $zip).Length / 1MB), $zip
