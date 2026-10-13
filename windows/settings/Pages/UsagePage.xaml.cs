using System.Reflection;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Kanade.Settings.Pages;

/// <summary>使い方（docs/WINDOWS-USAGE.md を埋め込んで、見出し・箇条書きだけ見た目を付けて出す）</summary>
public sealed partial class UsagePage : Page
{
    public UsagePage()
    {
        InitializeComponent();
        using var stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("WINDOWS-USAGE.md");
        if (stream == null) return;
        using var reader = new StreamReader(stream);
        foreach (var raw in reader.ReadToEnd().Replace("\r\n", "\n").Split('\n'))
        {
            var line = raw.TrimEnd();
            if (line.Length == 0) continue;
            var text = line.Replace("`", "").Replace("**", "");
            TextBlock block;
            if (line.StartsWith("# ")) block = new TextBlock { Text = text[2..], Style = (Style)Application.Current.Resources["TitleTextBlockStyle"], Margin = new Thickness(0, 0, 0, 8) };
            else if (line.StartsWith("## ")) block = new TextBlock { Text = text[3..], Style = (Style)Application.Current.Resources["SubtitleTextBlockStyle"], Margin = new Thickness(0, 16, 0, 4) };
            else
            {
                var indent = line.Length - line.TrimStart().Length;
                var item = text.TrimStart();
                var bullet = item.StartsWith("- ");
                block = new TextBlock
                {
                    Text = bullet ? "・" + item[2..] : item,
                    TextWrapping = TextWrapping.Wrap,
                    Margin = new Thickness(indent * 8, 0, 0, 0),
                    Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources[bullet ? "TextFillColorPrimaryBrush" : "TextFillColorSecondaryBrush"],
                };
            }
            block.IsTextSelectionEnabled = true;
            Body.Children.Add(block);
        }
    }
}
