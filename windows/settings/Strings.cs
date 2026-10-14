using System.Globalization;
using Microsoft.UI.Xaml.Markup;

namespace Kanade.Settings;

/// <summary>
/// 画面の文字の言語（設定の ui_language: auto・ja・en）。auto は Windows の表示言語が日本語なら日本語、ほかは英語。
/// エンジン（desktop/…/Ui.kt）と DLL（LangBarButton.cpp）も同じ決め方をする。変えたら窓を作り直す（MainWindow.Reload）
/// </summary>
public static class Lang
{
    public static bool English { get; private set; }

    public static void Apply(string setting) => English = setting switch
    {
        "ja" => false,
        "en" => true,
        _ => CultureInfo.CurrentUICulture.TwoLetterISOLanguageName != "ja",
    };

    public static string T(string ja, string en) => English ? en : ja;
}

/// <summary>XAML で両方の言語を並べて書く: Text="{local:L Ja='入力', En='Input'}"</summary>
[MarkupExtensionReturnType(ReturnType = typeof(string))]
public sealed partial class L : MarkupExtension
{
    public string Ja { get; set; } = "";
    public string En { get; set; } = "";

    protected override object ProvideValue() => Lang.T(Ja, En);
}
