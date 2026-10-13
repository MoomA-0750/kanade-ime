using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Kanade.Settings.Pages;

public sealed partial class VoicePage : Page
{
    private readonly Store store = Store.Load();
    private bool loading = true;

    public VoicePage()
    {
        InitializeComponent();
        Spoken.IsOn = store.SpokenPunctuation;
        WhisperState.Text = File.Exists(Path.Combine(Store.DataDir, "ggml-small-q5_1.bin")) ? "入っています" : "ありません";
        ReazonState.Text = File.Exists(Path.Combine(Store.DataDir, "reazonspeech", "tokens.txt")) ? "入っています" : "ありません";
        loading = false;
    }

    private void OnChanged(object sender, RoutedEventArgs e)
    {
        if (loading) return;
        store.SpokenPunctuation = Spoken.IsOn;
        store.Save();
    }
}
