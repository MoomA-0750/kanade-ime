using CommunityToolkit.WinUI.Controls;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Kanade.Settings.Pages;

public sealed partial class VoicePage : Page
{
    private readonly Store store = Store.Load();
    private bool loading = true;
    // 落としているモデル（画面を離れても続ける。もう一度押すと止める）
    private static readonly Dictionary<string, CancellationTokenSource> Downloads = new();

    public VoicePage()
    {
        InitializeComponent();
        Spoken.IsOn = store.SpokenPunctuation;
        BuildModels();
        loading = false;
    }

    private void OnChanged(object sender, RoutedEventArgs e)
    {
        if (loading) return;
        store.SpokenPunctuation = Spoken.IsOn;
        store.Save();
    }

    /// <summary>モデルごとに1枚のカード（使う・落とす/止める・消す・落としている進み具合）</summary>
    private void BuildModels()
    {
        Models.Children.Clear();
        foreach (var model in VoiceModel.All)
        {
            var use = new RadioButton { GroupName = "final", IsChecked = store.VoiceFinal == model.Id, IsEnabled = model.IsReady, Content = Lang.T("使う", "Use"), MinWidth = 0 };
            use.Checked += (_, _) =>
            {
                if (loading) return;
                store.VoiceFinal = model.Id;
                store.Save();
            };
            var progress = new ProgressBar { Width = 160, Minimum = 0, Maximum = 1, Visibility = Visibility.Collapsed, VerticalAlignment = VerticalAlignment.Center };
            var action = new Button();
            var actions = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12, Children = { progress, action, use } };
            var card = new SettingsCard
            {
                Header = model.Name,
                Description = Lang.T($"{model.Description}（{model.Size}）", $"{model.DescriptionEn} ({model.Size})"),
                HeaderIcon = new FontIcon { Glyph = "" },
                Content = actions,
            };
            void Refresh()
            {
                var downloading = Downloads.ContainsKey(model.Id);
                progress.Visibility = downloading ? Visibility.Visible : Visibility.Collapsed;
                use.IsEnabled = model.IsReady && !downloading;
                action.Content = downloading ? Lang.T("止める", "Stop") : model.IsReady ? Lang.T("消す", "Delete") : Lang.T("落とす", "Download");
            }
            action.Click += async (_, _) =>
            {
                if (Downloads.TryGetValue(model.Id, out var running))
                {
                    running.Cancel();
                    return;
                }
                if (model.IsReady)
                {
                    if (store.VoiceFinal == model.Id)
                    {
                        await Show(Lang.T("使っているモデルは消せません", "This model is in use"), Lang.T("ほかのモデルを「使う」にしてから消してください。", "Choose Use on another model first, then delete this one."));
                        return;
                    }
                    model.Delete();
                    Refresh();
                    return;
                }
                var cancel = new CancellationTokenSource();
                Downloads[model.Id] = cancel;
                Refresh();
                try
                {
                    await model.DownloadAsync(new Progress<double>(p => progress.Value = p), cancel.Token);
                }
                catch (OperationCanceledException)
                {
                }
                catch (Exception ex)
                {
                    await Show(Lang.T("落とせませんでした", "Download failed"), ex.Message);
                }
                finally
                {
                    Downloads.Remove(model.Id);
                    Refresh();
                }
            };
            Refresh();
            Models.Children.Add(card);
        }
    }

    private async Task Show(string title, string message)
    {
        await new ContentDialog { XamlRoot = XamlRoot, Title = title, Content = message, CloseButtonText = Lang.T("閉じる", "Close") }.ShowAsync();
    }
}
