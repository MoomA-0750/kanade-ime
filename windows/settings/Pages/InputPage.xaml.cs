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
        ZenzMode.SelectedItem = ZenzMode.Items.OfType<ComboBoxItem>().FirstOrDefault(i => (string)i.Tag == store.ZenzMode) ?? ZenzMode.Items[0];
        FullWidth.IsOn = store.FullWidthSymbols;
        Punctuation.SelectedItem = Punctuation.Items.OfType<ComboBoxItem>().FirstOrDefault(i => (string)i.Tag == store.Punctuation) ?? Punctuation.Items[0];
        Brackets.IsOn = store.PairBrackets;
        EmojiNames.IsOn = store.EmojiShortcodes;
        EmojiPrefix.SelectedItem = EmojiPrefix.Items.Contains(store.EmojiPrefix) ? store.EmojiPrefix : ":";
        EmojiTwice.IsOn = store.EmojiPrefixTwice;
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
        store.ZenzMode = (ZenzMode.SelectedItem as ComboBoxItem)?.Tag as string ?? "CORRECT";
        store.FullWidthSymbols = FullWidth.IsOn;
        store.Punctuation = (Punctuation.SelectedItem as ComboBoxItem)?.Tag as string ?? "、。";
        store.PairBrackets = Brackets.IsOn;
        store.EmojiShortcodes = EmojiNames.IsOn;
        store.EmojiPrefix = EmojiPrefix.SelectedItem as string ?? ":";
        store.EmojiPrefixTwice = EmojiTwice.IsOn;
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
            Title = Lang.T("覚えたものを消しますか？", "Clear what Kanade has learned?"),
            Content = Lang.T("元に戻せません。ユーザー辞書は残ります", "This cannot be undone. Your user dictionary is kept"),
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
