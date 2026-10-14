using System.Text;

namespace Kanade.Settings;

/// <summary>
/// 設定・ユーザー辞書・スニペット（%APPDATA%\Kanade）。書式はエンジン（desktop/…/DesktopStore.kt）と同じで、
/// 書き換えるとエンジンが2秒以内に読み直す。項目を足すときは DesktopStore.kt と両方を直す
/// </summary>
public sealed class Store
{
    public static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Kanade");
    public static string SettingsFile => Path.Combine(Dir, "settings.properties");
    public static string UserDictionaryFile => Path.Combine(Dir, "user_dictionary.txt");
    public static string SnippetsFile => Path.Combine(Dir, "snippets.txt");

    public static readonly string DataDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Kanade", "data");

    public bool FullWidthSymbols { get; set; } = true;
    public bool LiveConversion { get; set; } = true;
    public string Punctuation { get; set; } = "、。";
    public bool PairBrackets { get; set; } = true;
    public HashSet<string> Toggles { get; set; } = new() { "RIGHT_ALT_TAP", "CTRL_SPACE", "F13" };
    public bool SpokenPunctuation { get; set; } = true;
    public bool Learning { get; set; } = true;
    public bool LastJapanese { get; set; }
    public string VoiceFinal { get; set; } = VoiceModel.DefaultId;
    /// <summary>画面の文字の言語（auto・ja・en）</summary>
    public string UiLanguage { get; set; } = "auto";

    public static Store Load()
    {
        var s = new Store();
        if (!File.Exists(SettingsFile)) return s;
        foreach (var raw in File.ReadAllLines(SettingsFile, Encoding.UTF8))
        {
            var line = raw.Trim();
            if (line.Length == 0 || line.StartsWith('#')) continue;
            var eq = line.IndexOf('=');
            if (eq < 0) continue;
            var key = line[..eq].Trim();
            var value = line[(eq + 1)..].Trim();
            bool Bool(bool fallback) => bool.TryParse(value, out var b) ? b : fallback;
            switch (key)
            {
                case "full_width_symbols": s.FullWidthSymbols = Bool(s.FullWidthSymbols); break;
                case "live_conversion": s.LiveConversion = Bool(s.LiveConversion); break;
                case "punctuation": if (value.Length == 2) s.Punctuation = value; break;
                case "pair_brackets": s.PairBrackets = Bool(s.PairBrackets); break;
                case "toggle_triggers": s.Toggles = value.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).ToHashSet(); break;
                case "spoken_punctuation": s.SpokenPunctuation = Bool(s.SpokenPunctuation); break;
                case "learning": s.Learning = Bool(s.Learning); break;
                case "last_japanese": s.LastJapanese = Bool(s.LastJapanese); break;
                case "ui_language": if (value is "auto" or "ja" or "en") s.UiLanguage = value; break;
                case "voice_final": if (VoiceModel.All.Any(m => m.Id == value)) s.VoiceFinal = value; break;
            }
        }
        return s;
    }

    /// <summary>エンジンと同じ書き方で書く（前回の日本語のオン/オフはエンジンが書いたものを読み直して残す）</summary>
    public void Save()
    {
        LastJapanese = Load().LastJapanese;
        string B(bool b) => b ? "true" : "false";
        var order = new[] { "RIGHT_ALT_TAP", "CTRL_SPACE", "F13" };
        var text = new StringBuilder()
            .AppendLine("# Kanade（Windows 版）の設定。保存するとすぐ反映されます")
            .AppendLine("# 日本語入力中の数字・記号・大文字を全角にする")
            .AppendLine($"full_width_symbols={B(FullWidthSymbols)}")
            .AppendLine("# 打鍵ごとに変換結果を出す（ライブ変換）")
            .AppendLine($"live_conversion={B(LiveConversion)}")
            .AppendLine("# 句読点（、。 ，． 、． ，。 のどれか）")
            .AppendLine($"punctuation={Punctuation}")
            .AppendLine("# 開き括弧を打ったら閉じ括弧も入れ、カーソルを中に置く")
            .AppendLine($"pair_brackets={B(PairBrackets)}")
            .AppendLine("# 日本語のオン/オフに使うキー（RIGHT_ALT_TAP・CTRL_SPACE・F13 をカンマ区切りで。変換/無変換・半角/全角はいつでも使える）")
            .AppendLine($"toggle_triggers={string.Join(",", order.Where(Toggles.Contains))}")
            .AppendLine("# 音声入力で「まる」「てん」などを句読点にする")
            .AppendLine($"spoken_punctuation={B(SpokenPunctuation)}")
            .AppendLine("# 音声入力の確定の文を作るモデル（parakeet・reazon・sensevoice・whisper-small・whisper-kotoba・whisper-turbo）")
            .AppendLine($"voice_final={VoiceFinal}")
            .AppendLine("# 選んだ変換を覚えて次から先に出す")
            .AppendLine($"learning={B(Learning)}")
            .AppendLine("# 画面に出す文字の言語（auto は Windows の表示言語に合わせる・ja・en）")
            .AppendLine($"ui_language={UiLanguage}")
            .AppendLine("# 前回の日本語のオン/オフ（エンジンが書く）")
            .AppendLine($"last_japanese={B(LastJapanese)}")
            .ToString();
        WriteText(SettingsFile, text);
    }

    /// <summary>消えないように、一時ファイルに書いてから置き換える</summary>
    public static void WriteText(string path, string text)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var tmp = path + ".tmp";
        File.WriteAllText(tmp, text, new UTF8Encoding(false));
        File.Move(tmp, path, overwrite: true);
    }

    public static string ReadText(string path) => File.Exists(path) ? File.ReadAllText(path, Encoding.UTF8) : "";
}

/// <summary>ユーザー辞書・スニペットの1件（ファイルの [Line] 行目）。書式は1行に「キー 値」（Android 版と同じ）</summary>
public sealed record PairEntry(int Line, string Key, string Value)
{
    public string ValueForList => Value.Replace("\\n", " ⏎ ");

    public static List<PairEntry> Parse(string text)
    {
        var list = new List<PairEntry>();
        var lines = text.Split('\n');
        for (var i = 0; i < lines.Length; i++)
        {
            var line = lines[i].TrimEnd('\r');
            if (line.Length == 0 || line.StartsWith('#')) continue;
            var sep = line.IndexOfAny(new[] { ' ', '\t' });
            if (sep <= 0) continue;
            var value = line[(sep + 1)..].Trim();
            if (value.Length == 0) continue;
            list.Add(new PairEntry(i, line[..sep], value));
        }
        return list;
    }

    /// <summary>[entry] の行を [line] に置き換える（entry が null なら末尾に足す、line が null なら消す）。ほかの行（コメントなど）は残す</summary>
    public static void Write(string path, PairEntry? entry, string? line)
    {
        var lines = Store.ReadText(path).Replace("\r\n", "\n").Split('\n').ToList();
        if (lines.Count > 0 && lines[^1] == "") lines.RemoveAt(lines.Count - 1);
        if (entry == null) { if (line != null) lines.Add(line); }
        else if (line == null) lines.RemoveAt(entry.Line);
        else lines[entry.Line] = line;
        Store.WriteText(path, string.Join("\n", lines) + "\n");
    }
}
