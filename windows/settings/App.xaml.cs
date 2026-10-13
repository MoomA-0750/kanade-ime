using Microsoft.UI.Xaml;

namespace Kanade.Settings;

public partial class App : Application
{
    private MainWindow? window;

    public App()
    {
        InitializeComponent();
        // 落ちたときの理由を残す（%LOCALAPPDATA%\Kanade\settings-error.log）
        UnhandledException += (_, e) => WriteError(e.Exception);
    }

    public static void WriteError(Exception e)
    {
        try
        {
            var dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Kanade");
            Directory.CreateDirectory(dir);
            File.AppendAllText(Path.Combine(dir, "settings-error.log"), $"{DateTime.Now:s} {e}\n");
        }
        catch
        {
        }
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        // 起動の引数で最初の画面を選ぶ（エンジンが、タスクバーのメニューで選んだものを渡す）。input・voice・dictionary・snippets・backup・usage
        var page = System.Environment.GetCommandLineArgs().Skip(1).FirstOrDefault() ?? "input";
        try
        {
            window = new MainWindow(page);
        }
        catch (Exception e)
        {
            WriteError(e);
            throw;
        }
        window.RememberHandle();
        window.Activate();
    }
}
