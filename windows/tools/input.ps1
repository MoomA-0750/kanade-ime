# to-fu の画面に、実際の画面の座標でクリックやキーを送る（解像度は変わることがある。-info で確かめる）。MCP の computer_click は縦の換算がずれるので、こちらを使う。
# 例: -x 2336 -y 1563 -button right（タスクバーの「あ」を右クリック）、-vk 123 -hold 2500（F12 を2.5秒押す）。MCP の computer_run_command から動かす（ssh やタスクスケジューラからではカーソルが動かなかった）
param([int]$x = -1, [int]$y = -1, [string]$button = "", [switch]$info, [int]$vk = 0, [int]$hold = 100)
Add-Type @"
using System;using System.Runtime.InteropServices;
public static class M{
[DllImport("user32.dll")]public static extern bool SetProcessDPIAware();
[DllImport("user32.dll")]public static extern bool SetCursorPos(int x,int y);
[DllImport("user32.dll")]public static extern void mouse_event(uint f,int x,int y,uint d,IntPtr e);
[DllImport("user32.dll")]public static extern int GetSystemMetrics(int i);
[DllImport("user32.dll")]public static extern void keybd_event(byte v,byte s,uint f,IntPtr e);}
"@
[M]::SetProcessDPIAware() | Out-Null
if ($info) { "{0}x{1}" -f [M]::GetSystemMetrics(0), [M]::GetSystemMetrics(1) }
if ($x -ge 0) { [M]::SetCursorPos($x, $y) | Out-Null; Start-Sleep -Milliseconds 150 }
switch ($button) {
  "left"  { [M]::mouse_event(2,0,0,0,[IntPtr]::Zero); [M]::mouse_event(4,0,0,0,[IntPtr]::Zero) }
  "right" { [M]::mouse_event(8,0,0,0,[IntPtr]::Zero); [M]::mouse_event(16,0,0,0,[IntPtr]::Zero) }
}
if ($vk) { [M]::keybd_event([byte]$vk,0,0,[IntPtr]::Zero); Start-Sleep -Milliseconds $hold; [M]::keybd_event([byte]$vk,0,2,[IntPtr]::Zero) }
