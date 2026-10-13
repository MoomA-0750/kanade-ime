using System.Net.Http.Headers;

namespace Kanade.Settings;

/// <summary>
/// 確定の文を作る音声認識のモデル（Android 版の VoiceCatalog と同じ id・同じファイル・同じ版）。
/// エンジン側の一覧は desktop/…/VoiceModels.kt。一覧を変えるときは両方を直す
/// </summary>
public sealed record VoiceModel(string Id, string Name, string Description, string Size, string Path, IReadOnlyList<(string Name, string Url, long Bytes)> Files, bool Single)
{
    /// <summary>おすすめの順（Android 版と同じ）</summary>
    public static readonly IReadOnlyList<VoiceModel> All = new[]
    {
        Dir("parakeet", "parakeet-ja", "日本語・高い精度・速い・句読点あり", "656MB", "parakeet-ja",
            "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8/resolve/bef18eb066808c90bd0f5df5be685767b0732de8/",
            ("model.int8.onnx", 655_542_604), ("tokens.txt", 28_557)),
        Dir("reazon", "ReazonSpeech", "日本語・高い精度・とても速い・句読点なし。話している途中の文字にも使います", "73MB", "reazonspeech",
            "https://huggingface.co/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/main/",
            ("encoder-epoch-35-avg-1.int8.onnx", 70_876_409), ("decoder-epoch-35-avg-1.int8.onnx", 1_308_690),
            ("joiner-epoch-35-avg-1.int8.onnx", 1_033_417), ("tokens.txt", 26_631)),
        Dir("sensevoice", "SenseVoice", "中国語・英語・日本語・韓国語・精度はふつう・速い・句読点あり", "239MB", "sensevoice-2024-07-17",
            "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07/",
            ("model.int8.onnx", 239_233_841), ("tokens.txt", 315_894)),
        File("whisper-small", "whisper small", "多言語・精度は低め・遅い・句読点あり", "190MB",
            "ggml-small-q5_1.bin", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin", 190_085_487),
        File("whisper-kotoba", "kotoba-whisper v2.0", "日本語・高い精度・とても遅い・句読点は少なめ", "538MB",
            "ggml-kotoba-whisper-v2.0-q5_0.bin", "https://huggingface.co/kotoba-tech/kotoba-whisper-v2.0-ggml/resolve/main/ggml-kotoba-whisper-v2.0-q5_0.bin", 537_819_875),
        File("whisper-turbo", "whisper large-v3-turbo", "多言語・高い精度・とても遅い・句読点あり", "574MB",
            "ggml-large-v3-turbo-q5_0.bin", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin", 574_041_195),
    };

    /// <summary>Windows 版の既定（配布物に入っている）</summary>
    public const string DefaultId = "whisper-small";

    private static VoiceModel Dir(string id, string name, string description, string size, string dir, string baseUrl, params (string Name, long Bytes)[] files) =>
        new(id, name, description, size, dir, files.Select(f => (f.Name, baseUrl + f.Name, f.Bytes)).ToList(), false);

    private static VoiceModel File(string id, string name, string description, string size, string file, string url, long bytes) =>
        new(id, name, description, size, file, new[] { (file, url, bytes) }, true);

    private string Target(string name) => Single ? System.IO.Path.Combine(Store.DataDir, Path) : System.IO.Path.Combine(Store.DataDir, Path, name);

    /// <summary>落とし終わっている（大きさが合っている）</summary>
    public bool IsReady => Files.All(f => new FileInfo(Target(f.Name)) is { Exists: true } info && info.Length == f.Bytes);

    public void Delete()
    {
        if (Single) System.IO.File.Delete(Target(Files[0].Name));
        else if (Directory.Exists(System.IO.Path.Combine(Store.DataDir, Path))) Directory.Delete(System.IO.Path.Combine(Store.DataDir, Path), true);
    }

    private static readonly HttpClient Http = new() { Timeout = Timeout.InfiniteTimeSpan };

    /// <summary>落とす。途中まで落としたもの（.part）があれば続きから。[progress] は 0〜1</summary>
    public async Task DownloadAsync(IProgress<double> progress, CancellationToken cancel)
    {
        long total = Files.Sum(f => f.Bytes);
        long done = 0;
        foreach (var (name, url, bytes) in Files)
        {
            var target = Target(name);
            Directory.CreateDirectory(System.IO.Path.GetDirectoryName(target)!);
            if (new FileInfo(target) is { Exists: true } existing && existing.Length == bytes)
            {
                done += bytes;
                continue;
            }
            var part = target + ".part";
            long have = System.IO.File.Exists(part) ? new FileInfo(part).Length : 0;
            using var request = new HttpRequestMessage(HttpMethod.Get, url);
            if (have > 0) request.Headers.Range = new RangeHeaderValue(have, null);
            using var response = await Http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancel);
            response.EnsureSuccessStatusCode();
            // 続きを頼んでも最初から返すサーバーなら、最初から書き直す
            if (have > 0 && response.StatusCode != System.Net.HttpStatusCode.PartialContent) have = 0;
            await using (var input = await response.Content.ReadAsStreamAsync(cancel))
            await using (var output = new FileStream(part, have > 0 ? FileMode.Append : FileMode.Create, FileAccess.Write))
            {
                var buffer = new byte[1 << 20];
                long written = have;
                int n;
                while ((n = await input.ReadAsync(buffer, cancel)) > 0)
                {
                    await output.WriteAsync(buffer.AsMemory(0, n), cancel);
                    written += n;
                    progress.Report((double)(done + written) / total);
                }
            }
            if (new FileInfo(part).Length != bytes) throw new IOException($"{name} の大きさが合いません");
            System.IO.File.Move(part, target, overwrite: true);
            done += bytes;
        }
        progress.Report(1);
    }
}
