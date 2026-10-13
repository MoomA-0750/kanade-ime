using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Kanade.Settings.Pages;

public sealed partial class InputPage : Page
{
    private readonly Store store = Store.Load();
    private bool loading = true;

    public InputPage()
    {
        InitializeComponent();
        Live.IsOn = store.LiveConversion;
        FullWidth.IsOn = store.FullWidthSymbols;
        Punctuation.SelectedItem = Punctuation.Items.Contains(store.Punctuation) ? store.Punctuation : "、。";
        Brackets.IsOn = store.PairBrackets;
        RightAlt.IsOn = store.Toggles.Contains("RIGHT_ALT_TAP");
        CtrlSpace.IsOn = store.Toggles.Contains("CTRL_SPACE");
        F13.IsOn = store.Toggles.Contains("F13");
        Learning.IsOn = store.Learning;
        loading = false;
    }

    /// <summary>変えたらすぐ保存する（エンジンが2秒以内に読み直す）</summary>
    private void OnChanged(object sender, RoutedEventArgs e)
    {
        if (loading) return;
        store.LiveConversion = Live.IsOn;
        store.FullWidthSymbols = FullWidth.IsOn;
        store.Punctuation = Punctuation.SelectedItem as string ?? "、。";
        store.PairBrackets = Brackets.IsOn;
        store.Toggles = new HashSet<string>();
        if (RightAlt.IsOn) store.Toggles.Add("RIGHT_ALT_TAP");
        if (CtrlSpace.IsOn) store.Toggles.Add("CTRL_SPACE");
        if (F13.IsOn) store.Toggles.Add("F13");
        store.Learning = Learning.IsOn;
        store.Save();
    }

    private async void OnClearLearning(object sender, RoutedEventArgs e)
    {
        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = "覚えたものを消しますか？",
            Content = "学習した変換をすべて消します。元に戻せません。",
            PrimaryButtonText = "消す",
            CloseButtonText = "やめる",
            DefaultButton = ContentDialogButton.Close,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        var (ok, message) = await Engine.RequestAsync("clear-learning");
        Message.Severity = ok ? InfoBarSeverity.Success : InfoBarSeverity.Error;
        Message.Message = message;
        Message.IsOpen = true;
    }
}
