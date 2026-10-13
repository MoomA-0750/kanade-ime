# to-fu の窓を撮る（開発用。scripts/win-shot.sh から呼ぶ）
param([string]$Process = 'Kanade.Settings', [string]$Out = "$env:USERPROFILE\kanade\win.png")
Add-Type -AssemblyName System.Drawing
Add-Type -TypeDefinition @'
using System; using System.Runtime.InteropServices;
public class W {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern IntPtr FindWindow(string c, string t);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr dc, uint f);
}
'@
[W]::SetProcessDPIAware() | Out-Null
$h = (Get-Process $Process -ErrorAction SilentlyContinue | Where-Object MainWindowHandle -ne 0 | Select-Object -First 1).MainWindowHandle
if (-not $h) { $h = [IntPtr]::Zero }
if ($h -eq [IntPtr]::Zero) { 'notfound' | Out-File "$Out.txt"; exit }
$r = New-Object W+RECT; [W]::GetWindowRect($h, [ref]$r) | Out-Null
$bmp = New-Object System.Drawing.Bitmap ($r.R - $r.L), ($r.B - $r.T)
$g = [System.Drawing.Graphics]::FromImage($bmp); $dc = $g.GetHdc()
[W]::PrintWindow($h, $dc, 2) | Out-Null
$g.ReleaseHdc($dc); $bmp.Save($Out); 'ok' | Out-File "$Out.txt"
