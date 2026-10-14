using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;

namespace Kanade.Settings.Pages;

/// <summary>ユーザー辞書とスニペット（どちらも1行に「キー 値」のファイル。Android 版の PairEditorScreen と同じ動き）</summary>
public sealed partial class PairPage : Page
{
    private string path = Store.UserDictionaryFile;
    private string keyLabel = Lang.T("読み", "Reading");
    private string valueLabel = Lang.T("単語", "Word");
    private List<PairEntry> all = new();

    public PairPage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        if (e.Parameter as string == "snippets")
        {
            path = Store.SnippetsFile;
            keyLabel = Lang.T("打つ文字", "Typed text");
            valueLabel = Lang.T("置き換える文", "Replacement");
            Title.Text = Lang.T("スニペット", "Snippets");
            Description.Text = Lang.T("打った文字がキーと一致した瞬間に、文に置き換えます（日本語オフでも効きます）。例: 「->」→「→」、「@@」→ メールアドレス。改行は \\n と書きます",
                "As soon as what you type matches a key, it is replaced with the text (also when Japanese is off). Examples: -> becomes →, @@ becomes your email address. Write \\n for a line break");
        }
        else
        {
            Title.Text = Lang.T("ユーザー辞書", "User dictionary");
            Description.Text = Lang.T("読みと単語を登録すると、変換の候補の先頭に出ます", "Words you add here are shown first among the conversion candidates for their reading");
        }
        KeyHeader.Text = keyLabel;
        ValueHeader.Text = valueLabel;
        Reload();
    }

    private void Reload()
    {
        all = PairEntry.Parse(Store.ReadText(path));
        ApplyFilter();
    }

    private void ApplyFilter()
    {
        var q = Search.Text.Trim();
        List.ItemsSource = q.Length == 0 ? all : all.Where(e => e.Key.Contains(q) || e.Value.Contains(q)).ToList();
    }

    private void OnSearch(AutoSuggestBox sender, AutoSuggestBoxTextChangedEventArgs args) => ApplyFilter();

    private async void OnAdd(object sender, RoutedEventArgs e) => await Edit(null);

    private async void OnItemClick(object sender, ItemClickEventArgs e) => await Edit((PairEntry)e.ClickedItem);

    private async Task Edit(PairEntry? entry)
    {
        var key = new TextBox { Header = keyLabel, Text = entry?.Key ?? "" };
        var value = new TextBox { Header = valueLabel, Text = entry?.Value ?? "", AcceptsReturn = false };
        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = entry == null ? Lang.T("足す", "Add") : Lang.T("直す", "Edit"),
            Content = new StackPanel { Spacing = 12, MinWidth = 360, Children = { key, value } },
            PrimaryButtonText = Lang.T("保存", "Save"),
            SecondaryButtonText = entry != null ? Lang.T("消す", "Delete") : "",
            CloseButtonText = Lang.T("やめる", "Cancel"),
            DefaultButton = ContentDialogButton.Primary,
        };
        void Validate() => dialog.IsPrimaryButtonEnabled =
            key.Text.Trim().Length > 0 && !key.Text.Trim().Any(c => c == ' ' || c == '\t') && value.Text.Trim().Length > 0;
        key.TextChanged += (_, _) => Validate();
        value.TextChanged += (_, _) => Validate();
        Validate();
        var result = await dialog.ShowAsync();
        if (result == ContentDialogResult.Primary) PairEntry.Write(path, entry, $"{key.Text.Trim()} {value.Text.Trim()}");
        else if (result == ContentDialogResult.Secondary && entry != null) PairEntry.Write(path, entry, null);
        else return;
        Reload();
    }

    private void OnTextMode(object sender, RoutedEventArgs e)
    {
        var text = TextMode.IsChecked == true;
        ListPane.Visibility = text ? Visibility.Collapsed : Visibility.Visible;
        TextPane.Visibility = text ? Visibility.Visible : Visibility.Collapsed;
        if (text) Editor.Text = Store.ReadText(path).Replace("\r\n", "\n").Replace("\n", "\r");
    }

    private void OnSaveText(object sender, RoutedEventArgs e)
    {
        // TextBox の改行は \r なので、ファイルの書き方（\n）に戻す
        var text = Editor.Text.Replace("\r\n", "\n").Replace("\r", "\n");
        if (!text.EndsWith('\n')) text += "\n";
        Store.WriteText(path, text);
        Reload();
    }
}
