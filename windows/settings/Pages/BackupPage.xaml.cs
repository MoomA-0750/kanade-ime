using System.Diagnostics;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Storage.Pickers;

namespace Kanade.Settings.Pages;

public sealed partial class BackupPage : Page
{
    public BackupPage()
    {
        InitializeComponent();
    }

    private async void OnImport(object sender, RoutedEventArgs e)
    {
        var picker = new FileOpenPicker { SuggestedStartLocation = PickerLocationId.Downloads };
        picker.FileTypeFilter.Add(".json");
        WinRT.Interop.InitializeWithWindow.Initialize(picker, MainWindow.Handle);
        var file = await picker.PickSingleFileAsync();
        if (file == null) return;
        Show(await Engine.RequestAsync("import-android", file.Path));
    }

    private async void OnExport(object sender, RoutedEventArgs e)
    {
        var picker = new FileSavePicker { SuggestedStartLocation = PickerLocationId.DocumentsLibrary, SuggestedFileName = $"kanade-settings-{DateTime.Now:yyyyMMdd}" };
        picker.FileTypeChoices.Add("Kanade の設定", new List<string> { ".json" });
        WinRT.Interop.InitializeWithWindow.Initialize(picker, MainWindow.Handle);
        var file = await picker.PickSaveFileAsync();
        if (file == null) return;
        Show(await Engine.RequestAsync("export-android", file.Path));
    }

    private void OnOpenFolder(object sender, RoutedEventArgs e) =>
        Process.Start(new ProcessStartInfo("explorer.exe", $"\"{Store.Dir}\"") { UseShellExecute = true });

    private void OnOpenLog(object sender, RoutedEventArgs e)
    {
        var log = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Kanade", "engine.log");
        Process.Start(new ProcessStartInfo("notepad.exe", $"\"{log}\"") { UseShellExecute = true });
    }

    private void Show((bool Ok, string Message) result)
    {
        Message.Severity = result.Ok ? InfoBarSeverity.Success : InfoBarSeverity.Error;
        Message.Message = result.Message;
        Message.IsOpen = true;
    }
}
