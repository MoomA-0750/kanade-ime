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
        DisplayLanguage.SelectedItem = DisplayLanguage.Items.OfType<ComboBoxItem>().First(i => (string)i.Tag == store.UiLanguage);
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

    private void OnLanguageChanged(object sender, SelectionChangedEventArgs e)
    {
        if (loading || DisplayLanguage.SelectedItem is not ComboBoxItem item) return;
        store.UiLanguage = (string)item.Tag;
        store.Save();
        App.Reopen("input");
    }

    private async void OnClearLearning(object sender, RoutedEventArgs e)
    {
        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = Lang.T("覚えたものを消しますか？", "Clear learned conversions?"),
            Content = Lang.T("学習した変換をすべて消します。元に戻せません。", "Everything learned will be deleted. This cannot be undone."),
            PrimaryButtonText = Lang.T("消す", "Clear"),
            CloseButtonText = Lang.T("やめる", "Cancel"),
            DefaultButton = ContentDialogButton.Close,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        var (ok, message) = await Engine.RequestAsync("clear-learning");
        Message.Severity = ok ? InfoBarSeverity.Success : InfoBarSeverity.Error;
        Message.Message = message;
        Message.IsOpen = true;
    }
}
