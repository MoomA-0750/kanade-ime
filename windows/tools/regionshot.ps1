# to-fu の画面の一部を撮る（開発用。窓の外に出る候補の窓なども写る）
param([int]$X = 0, [int]$Y = 0, [int]$W = 1200, [int]$H = 800, [string]$Out = "$env:USERPROFILE\kanade\region.png")
Add-Type -AssemblyName System.Drawing
Add-Type -TypeDefinition 'using System.Runtime.InteropServices; public class D2 { [DllImport("user32.dll")] public static extern bool SetProcessDPIAware(); }'
[D2]::SetProcessDPIAware() | Out-Null
$bmp = New-Object System.Drawing.Bitmap $W, $H
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.CopyFromScreen($X, $Y, 0, 0, $bmp.Size)
$bmp.Save($Out)
'ok' | Out-File "$Out.txt"
