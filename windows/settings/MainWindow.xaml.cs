using Kanade.Settings.Pages;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Animation;

namespace Kanade.Settings;

public sealed partial class MainWindow : Window
{
    private static readonly Dictionary<string, Type> Pages = new()
    {
        ["input"] = typeof(InputPage),
        ["voice"] = typeof(VoicePage),
        ["dictionary"] = typeof(PairPage),
        ["snippets"] = typeof(PairPage),
        ["backup"] = typeof(BackupPage),
        ["usage"] = typeof(UsagePage),
    };

    public MainWindow(string page)
    {
        InitializeComponent();
        ExtendsContentIntoTitleBar = true;
        SetTitleBar(TitleBar);
        AppWindow.TitleBar.PreferredHeightOption = TitleBarHeightOption.Tall;
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Kanade.ico"));
        AppWindow.Resize(new Windows.Graphics.SizeInt32(Scale(1000), Scale(720)));
        var tag = Pages.ContainsKey(page) ? page : "input";
        Nav.SelectedItem = Nav.MenuItems.OfType<NavigationViewItem>().First(i => (string)i.Tag == tag);
    }

    private int Scale(int px) => (int)(px * (Content?.XamlRoot?.RasterizationScale ?? GetDpiScale()));

    private static double GetDpiScale() => NativeMethods.GetDpiForSystem() / 96.0;

    private void OnSelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (args.SelectedItem is not NavigationViewItem item) return;
        var tag = (string)item.Tag;
        ContentFrame.Navigate(Pages[tag], tag, new DrillInNavigationTransitionInfo());
    }

    /// <summary>ファイルの選択の窓などに渡す、この窓のハンドル</summary>
    public static IntPtr Handle { get; private set; }

    public void RememberHandle() => Handle = WinRT.Interop.WindowNative.GetWindowHandle(this);
}

internal static class NativeMethods
{
    [System.Runtime.InteropServices.DllImport("user32.dll")]
    public static extern uint GetDpiForSystem();
}
