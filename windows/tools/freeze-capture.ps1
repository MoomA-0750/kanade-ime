# Kanade: 固まったとき（キー入力が効かない・トレイのメニューが出ない）に、その場の情報を集める。
#   使い方は docs/WINDOWS-FREEZE.md。固まったら（マウスは動く前提）、このファイルのショートカット（freeze-capture.cmd）をダブルクリック。
#   -Delay 秒: 実行を始めてから集めるまで待つ（その間に、固まっているウィンドウをクリックして手前に出す。既定 8 秒）
#   -Arm     : 次に備えて、DLL の記録（%LOCALAPPDATA%\Kanade\tsf-log）を有効にして終わる（アプリを開き直すと効く）
#   -Dump    : 手前のアプリの全メモリのダンプ（.dmp、大きい）も取る
# このファイルの文字は、文字化けを避けるため英語（Windows PowerShell 5.1 と 7 の両方で動く）。結果は日本語の説明なしで読めるよう key=value で出す。
param(
    [int]$Delay = 8,
    [switch]$Arm,
    [switch]$Dump,
    [string]$OutRoot = ''
)
$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

if ($Arm) {
    $dir = Join-Path $env:LOCALAPPDATA 'Kanade\tsf-log'
    New-Item -ItemType Directory -Force $dir | Out-Null
    Write-Host "armed: $dir  (restart the apps; each process writes <exe>-<pid>.log there, delete the folder to stop)"
    return
}

Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;
public static class KFreeze {
    [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll")] public static extern bool IsHungAppWindow(IntPtr h);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetWindowText(IntPtr h, StringBuilder s, int n);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern int GetClassName(IntPtr h, StringBuilder s, int n);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] public static extern IntPtr FindWindow(string c, string t);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc p, IntPtr l);
    [DllImport("user32.dll", SetLastError = true)] static extern IntPtr SendMessageTimeout(IntPtr h, uint msg, IntPtr w, IntPtr l, uint flags, uint timeout, out IntPtr result);
    delegate bool EnumProc(IntPtr h, IntPtr l);
    // WM_NULL を送って、窓のスレッドがメッセージを処理するか（ミリ秒。応答なしは -1）
    public static long Ping(IntPtr h, uint timeoutMs) {
        IntPtr r; var sw = System.Diagnostics.Stopwatch.StartNew();
        IntPtr ok = SendMessageTimeout(h, 0, IntPtr.Zero, IntPtr.Zero, 2 /*SMTO_ABORTIFHUNG*/, timeoutMs, out r);
        return ok == IntPtr.Zero ? -1 : sw.ElapsedMilliseconds;
    }
    public static string Text(IntPtr h) { var s = new StringBuilder(256); GetWindowText(h, s, 256); return s.ToString(); }
    public static string Class(IntPtr h) { var s = new StringBuilder(256); GetClassName(h, s, 256); return s.ToString(); }
    public static List<IntPtr> TopLevel() {
        var l = new List<IntPtr>();
        EnumWindows((h, p) => { if (IsWindowVisible(h)) l.Add(h); return true; }, IntPtr.Zero);
        return l;
    }

    // ---- エンジンのソケット（AF_UNIX）。Windows PowerShell 5.1 の .NET には無いので Winsock を直接呼ぶ ----
    [StructLayout(LayoutKind.Sequential)] struct WSADATA { public ushort v, hv; [MarshalAs(UnmanagedType.ByValArray, SizeConst = 257)] public byte[] d; [MarshalAs(UnmanagedType.ByValArray, SizeConst = 129)] public byte[] s; public ushort ms, md; public IntPtr vi; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Ansi)] struct SOCKADDR_UN { public ushort family; [MarshalAs(UnmanagedType.ByValArray, SizeConst = 108)] public byte[] path; }
    [DllImport("ws2_32.dll")] static extern int WSAStartup(ushort v, out WSADATA d);
    [DllImport("ws2_32.dll")] static extern IntPtr socket(int af, int type, int proto);
    [DllImport("ws2_32.dll")] static extern int connect(IntPtr s, ref SOCKADDR_UN a, int len);
    [DllImport("ws2_32.dll")] static extern int send(IntPtr s, byte[] b, int len, int flags);
    [DllImport("ws2_32.dll")] static extern int recv(IntPtr s, byte[] b, int len, int flags);
    [DllImport("ws2_32.dll")] static extern int setsockopt(IntPtr s, int level, int name, ref int val, int len);
    [DllImport("ws2_32.dll")] static extern int closesocket(IntPtr s);
    [DllImport("ws2_32.dll")] static extern int WSAGetLastError();
    // エンジンへつないで hello を送り、返事の最初の行が届くまでの時間を測る。lock が取れない（エンジンの中で止まっている）と返事が来ない
    public static string ProbeEngine(string path, int timeoutMs) {
        WSADATA d; WSAStartup(0x0202, out d);
        var sw = System.Diagnostics.Stopwatch.StartNew();
        IntPtr s = socket(1, 1, 0);
        if (s == (IntPtr)(-1)) return "socket_failed err=" + WSAGetLastError();
        try {
            var a = new SOCKADDR_UN { family = 1, path = new byte[108] };
            var pb = Encoding.Default.GetBytes(path); Array.Copy(pb, a.path, Math.Min(pb.Length, 107));
            if (connect(s, ref a, Marshal.SizeOf(typeof(SOCKADDR_UN))) != 0) return "connect_failed err=" + WSAGetLastError() + " after_ms=" + sw.ElapsedMilliseconds;
            long connectMs = sw.ElapsedMilliseconds;
            int to = timeoutMs; setsockopt(s, 0xffff, 0x1006 /*SO_RCVTIMEO*/, ref to, 4);
            var msg = Encoding.UTF8.GetBytes("hello\t1\t0\tfreeze-capture.exe\n");
            send(s, msg, msg.Length, 0);
            var buf = new byte[4096]; sw.Restart();
            int n = recv(s, buf, buf.Length, 0);
            long replyMs = sw.ElapsedMilliseconds;
            send(s, Encoding.UTF8.GetBytes("bye\n"), 4, 0);
            if (n <= 0) return "connected connect_ms=" + connectMs + " NO_REPLY_within_ms=" + timeoutMs + " err=" + WSAGetLastError();
            return "ok connect_ms=" + connectMs + " hello_reply_ms=" + replyMs;
        } finally { closesocket(s); }
    }
}
'@

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
if (-not $OutRoot) { $OutRoot = [Environment]::GetFolderPath('Desktop') }
$out = Join-Path $OutRoot "kanade-freeze-$stamp"
New-Item -ItemType Directory -Force $out | Out-Null
$report = Join-Path $out 'report.txt'
function W([string]$s) { Add-Content -Path $report -Value $s -Encoding UTF8; Write-Host $s }
function Section([string]$s) { W ''; W "== $s ==" }

if ($Delay -gt 0) {
    Write-Host "Click the frozen window now. Capturing in $Delay seconds..."
    Start-Sleep -Seconds $Delay
}
W "kanade-freeze-capture $stamp  (PowerShell $($PSVersionTable.PSVersion))"
W "computer=$env:COMPUTERNAME user=$env:USERNAME"

# ---- 手前のウィンドウと、応答するか ----
Section 'foreground window'
$fg = [KFreeze]::GetForegroundWindow()
$fgPid = 0
[void][KFreeze]::GetWindowThreadProcessId($fg, [ref]$fgPid)
$fgProc = if ($fgPid) { Get-Process -Id $fgPid -ErrorAction SilentlyContinue } else { $null }
W "hwnd=$fg pid=$fgPid process=$($fgProc.ProcessName) class=$([KFreeze]::Class($fg)) title=$([KFreeze]::Text($fg))"
W "is_hung_app_window=$([KFreeze]::IsHungAppWindow($fg))  wm_null_ping_ms=$([KFreeze]::Ping($fg, 3000))  (-1 = no answer in 3s)"
W "process_responding=$($fgProc.Responding)"

Section 'taskbar (explorer) responsiveness'
$tray = [KFreeze]::FindWindow('Shell_TrayWnd', $null)
W "Shell_TrayWnd hwnd=$tray wm_null_ping_ms=$([KFreeze]::Ping($tray, 3000)) is_hung=$([KFreeze]::IsHungAppWindow($tray))"

Section 'visible top-level windows that do not answer (WM_NULL 1s)'
foreach ($h in [KFreeze]::TopLevel()) {
    $t = [KFreeze]::Text($h)
    if (-not $t) { continue }
    $p = 0; [void][KFreeze]::GetWindowThreadProcessId($h, [ref]$p)
    $ping = [KFreeze]::Ping($h, 1000)
    if ($ping -lt 0 -or [KFreeze]::IsHungAppWindow($h)) { W "NOT_RESPONDING hwnd=$h pid=$p process=$((Get-Process -Id $p -ErrorAction SilentlyContinue).ProcessName) title=$t" }
}

# ---- エンジン（JVM） ----
Section 'engine process'
$engine = Get-CimInstance Win32_Process | Where-Object { $_.Name -match '^javaw?\.exe$' -and $_.CommandLine -match 'desktop\.MainKt' -and $_.CommandLine -match 'serve' } | Select-Object -First 1
if (-not $engine) { W 'engine_process=NOT_RUNNING (no javaw with desktop.MainKt serve)' }
else {
    $ep = Get-Process -Id $engine.ProcessId
    $cpu1 = $ep.TotalProcessorTime.TotalSeconds; Start-Sleep -Seconds 3
    $ep.Refresh(); $cpu2 = $ep.TotalProcessorTime.TotalSeconds
    W "engine_pid=$($ep.Id) started=$($ep.StartTime) responding=$($ep.Responding) cpu_seconds_in_3s=$([math]::Round($cpu2 - $cpu1, 2)) working_set_mb=$([int]($ep.WorkingSet64/1MB)) private_mb=$([int]($ep.PrivateMemorySize64/1MB)) threads=$($ep.Threads.Count) handles=$($ep.HandleCount)"
    W "engine_cmd=$($engine.CommandLine)"
    $ep.Threads | Group-Object { "$($_.ThreadState)/$($_.WaitReason)" } | ForEach-Object { W "  threads $($_.Name) x$($_.Count)" }
}

Section 'engine socket probe (hello -> first reply; needs the engine global lock)'
$sock = Join-Path $env:USERPROFILE '.kanade\engine.sock'
W "socket_file_exists=$(Test-Path $sock) path=$sock"
W ("probe=" + [KFreeze]::ProbeEngine($sock, 3000))

Section 'engine thread dump'
if ($engine) {
    $javaBin = Split-Path -Parent $ep.Path
    $jcmd = @("$javaBin\jcmd.exe", "$javaBin\jstack.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $jcmd) {
        $cands = @("$env:JAVA_HOME\bin\jcmd.exe", 'C:\Program Files\Android\Android Studio\jbr\bin\jcmd.exe') + (Get-Command jcmd -ErrorAction SilentlyContinue | ForEach-Object Source)
        $jcmd = $cands | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
    }
    if ($jcmd) {
        $dumpFile = Join-Path $out 'engine-threads.txt'
        $args2 = if ($jcmd -match 'jcmd') { @($ep.Id, 'Thread.print', '-l') } else { @('-l', $ep.Id) }
        $job = Start-Job { param($e, $a) & $e @a 2>&1 } -ArgumentList $jcmd, $args2
        if (Wait-Job $job -Timeout 20) { Receive-Job $job | Out-File $dumpFile -Encoding UTF8; W "thread_dump=$dumpFile via $jcmd" }
        else { Stop-Job $job; W "thread_dump=TIMEOUT (the JVM did not answer the attach in 20s: that itself means it is hung)" }
        Remove-Job $job -Force -ErrorAction SilentlyContinue
    } else { W 'thread_dump=NOT_AVAILABLE (no jcmd/jstack found; the installed runtime is a jlink image without them)' }
}

# ---- 手前のアプリの中 ----
if ($fgProc) {
    Section 'foreground process'
    try {
        $mods = $fgProc.Modules | Where-Object { $_.ModuleName -like 'kanade*' } | ForEach-Object { "$($_.ModuleName) $($_.FileName)" }
        W "kanade_modules=$($mods -join '; ')"
    } catch { W "kanade_modules=UNREADABLE ($($_.Exception.Message))" }
    W "threads=$($fgProc.Threads.Count) handles=$($fgProc.HandleCount) working_set_mb=$([int]($fgProc.WorkingSet64/1MB))"
    $fgProc.Threads | Group-Object { "$($_.ThreadState)/$($_.WaitReason)" } | ForEach-Object { W "  threads $($_.Name) x$($_.Count)" }
    if ($Dump) {
        $dmp = Join-Path $out "$($fgProc.ProcessName)-$($fgProc.Id).dmp"
        try { rundll32.exe C:\Windows\System32\comsvcs.dll, MiniDump $fgProc.Id $dmp full; Start-Sleep -Seconds 2; W "dump=$dmp size_mb=$([int]((Get-Item $dmp -ErrorAction SilentlyContinue).Length/1MB))" }
        catch { W "dump=FAILED $($_.Exception.Message)" }
    }
}

# ---- 機械の様子 ----
Section 'system'
$os = Get-CimInstance Win32_OperatingSystem
W "boot=$($os.LastBootUpTime) free_mem_mb=$([int]($os.FreePhysicalMemory/1024)) total_mem_mb=$([int]($os.TotalVisibleMemorySize/1024)) free_virtual_mb=$([int]($os.FreeVirtualMemory/1024))"
try { $c = Get-Counter '\Processor(_Total)\% Processor Time', '\PhysicalDisk(_Total)\Current Disk Queue Length' -ErrorAction Stop; $c.CounterSamples | ForEach-Object { W "counter $($_.Path)=$([math]::Round($_.CookedValue, 1))" } } catch { W "counters=UNAVAILABLE" }
W 'top_by_working_set:'
Get-Process | Sort-Object WorkingSet64 -Descending | Select-Object -First 8 | ForEach-Object { W "  $($_.ProcessName) pid=$($_.Id) ws_mb=$([int]($_.WorkingSet64/1MB))" }
W 'processes_not_responding:'
Get-Process | Where-Object { $_.MainWindowHandle -ne 0 -and -not $_.Responding } | ForEach-Object { W "  $($_.ProcessName) pid=$($_.Id) title=$($_.MainWindowTitle)" }

Section 'recent events (6h): sleep/resume, session lock/RDP, app hang/crash'
$since = (Get-Date).AddHours(-6)
$queries = @(
    @{ LogName = 'System'; ProviderName = 'Microsoft-Windows-Kernel-Power'; Id = 42, 107, 506, 507 },
    @{ LogName = 'System'; ProviderName = 'Microsoft-Windows-Power-Troubleshooter' },
    @{ LogName = 'Microsoft-Windows-TerminalServices-LocalSessionManager/Operational'; Id = 21, 22, 23, 24, 25, 39, 40 },
    @{ LogName = 'Application'; ProviderName = 'Application Error' },
    @{ LogName = 'Application'; ProviderName = 'Application Hang' }
)
foreach ($q in $queries) {
    $q.StartTime = $since
    Get-WinEvent -FilterHashtable $q -MaxEvents 30 -ErrorAction SilentlyContinue | Where-Object { $_.TimeCreated -ge $since } | Sort-Object TimeCreated | ForEach-Object {
        $m = ($_.Message -replace '\s+', ' '); W "$($_.TimeCreated) [$($_.ProviderName) $($_.Id)] $($m.Substring(0, [Math]::Min(200, $m.Length)))" }
}

# ---- Kanade の記録 ----
Section 'kanade logs'
$kdir = Join-Path $env:LOCALAPPDATA 'Kanade'
$elog = Join-Path $kdir 'engine.log'
if (Test-Path $elog) { Copy-Item $elog (Join-Path $out 'engine.log'); W "engine.log size=$((Get-Item $elog).Length) last_write=$((Get-Item $elog).LastWriteTime) (copied)" } else { W 'engine.log=MISSING' }
$tdir = Join-Path $kdir 'tsf-log'
if (Test-Path $tdir) {
    $recent = Get-ChildItem $tdir -File | Where-Object { $_.LastWriteTime -gt (Get-Date).AddHours(-3) }
    New-Item -ItemType Directory -Force (Join-Path $out 'tsf-log') | Out-Null
    foreach ($f in $recent) { Get-Content $f.FullName -Tail 400 | Set-Content (Join-Path $out "tsf-log\$($f.Name)") -Encoding UTF8 }
    W "tsf-log files (last 3h)=$(@($recent).Count) (tails copied)"
} else { W 'tsf-log=NOT_ENABLED (run with -Arm once, then restart the apps)' }
$ver = Get-ItemProperty 'HKCU:\Software\Kanade' -ErrorAction SilentlyContinue
W "EngineCommand=$($ver.EngineCommand)"
Get-ChildItem 'Registry::HKEY_CLASSES_ROOT\CLSID\{92CD23E6-D514-4099-84D0-81969040EE41}\InprocServer32' -ErrorAction SilentlyContinue | Out-Null
W "tsf_dll=$((Get-ItemProperty 'Registry::HKEY_CLASSES_ROOT\CLSID\{92CD23E6-D514-4099-84D0-81969040EE41}\InprocServer32' -ErrorAction SilentlyContinue).'(default)')"

$zip = "$out.zip"
try { Compress-Archive -Path "$out\*" -DestinationPath $zip -Force; W "zip=$zip" } catch { W "zip=FAILED $($_.Exception.Message)" }
Write-Host ''
Write-Host "Done: $out"
Write-Host "Send the zip (or report.txt) to the developer."
try { Start-Process explorer.exe $out } catch {}
