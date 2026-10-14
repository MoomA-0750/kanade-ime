using System.Net.Sockets;
using System.Text;

namespace Kanade.Settings;

/// <summary>
/// エンジン（常駐の Kotlin のプロセス）に頼みごとをする（docs/WINDOWS-PROTOCOL.md の admin）。
/// ソケットは %USERPROFILE%\.kanade\engine.sock。返事は1行で「admin 番号 成功(1/0) 文」
/// </summary>
public static class Engine
{
    private static readonly string SocketPath = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".kanade", "engine.sock");

    public static async Task<(bool Ok, string Message)> RequestAsync(string command, string argument = "")
    {
        try
        {
            using var socket = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
            await socket.ConnectAsync(new UnixDomainSocketEndPoint(SocketPath));
            using var stream = new NetworkStream(socket);
            var line = $"admin\t1\t{Escape(command)}\t{Escape(argument)}\n";
            await stream.WriteAsync(Encoding.UTF8.GetBytes(line));
            using var reader = new StreamReader(stream, Encoding.UTF8);
            var timeout = Task.Delay(TimeSpan.FromSeconds(20));
            while (true)
            {
                var read = reader.ReadLineAsync();
                if (await Task.WhenAny(read, timeout) == timeout) return (false, Lang.T("エンジンから返事がありません", "The engine did not respond"));
                var reply = await read;
                if (reply == null) return (false, Lang.T("エンジンとの接続が切れました", "Lost the connection to the engine"));
                var f = reply.Split('\t');
                if (f.Length >= 3 && f[0] == "admin") return (f[2] == "1", Unescape(f.Length > 3 ? f[3] : ""));
            }
        }
        catch (SocketException)
        {
            return (false, Lang.T("Kanade のエンジンが動いていません（文字を1つ打つと起動します）", "The Kanade engine is not running (typing one character starts it)"));
        }
    }

    private static string Escape(string s) => s.Replace("\\", "\\\\").Replace("\t", "\\t").Replace("\n", "\\n").Replace("\r", "\\r");

    private static string Unescape(string s)
    {
        var sb = new StringBuilder();
        for (var i = 0; i < s.Length; i++)
        {
            if (s[i] == '\\' && i + 1 < s.Length)
            {
                i++;
                sb.Append(s[i] switch { 't' => '\t', 'n' => '\n', 'r' => '\r', _ => s[i] });
            }
            else sb.Append(s[i]);
        }
        return sb.ToString();
    }
}
