import QtQuick
import QtQuick.Controls
import QtQuick.Dialogs
import Kanade.Backend

PrefPage {
    id: page

    PrefGroup {
        title: Pal.t("ほかの Kanade との行き来", "Moving between devices")
        description: Pal.t("設定・ユーザー辞書・スニペットを1つのファイルにします。Android 版の「設定の書き出し」「設定の読み込み」と同じ形です。録音キーや英語のモデルなど PC の版だけの項目は、Windows・Linux・Mac のあいだで引き継ぎ、Android 版は読み飛ばします",
                           "Settings, the user dictionary and snippets in one file, the same format as Export and Import settings in the Android app. Desktop-only items such as record keys and the English model carry over between Windows, Linux and Mac; the Android app skips them")
        PrefRow {
            title: Pal.t("読み込む", "Import")
            subtitle: Pal.t("ほかの Kanade（Android・Windows・Linux・Mac）で書き出したファイル（kanade-settings-….json）を読みます。今のユーザー辞書・スニペットは .bak に残します",
                            "Reads a file exported from another Kanade (Android, Windows, Linux or Mac), kanade-settings-….json. The current dictionary and snippets are kept as .bak")
            KButton { text: Pal.t("ファイルを選ぶ…", "Choose file…"); onClicked: importDialog.open() }
        }
        PrefRow {
            title: Pal.t("書き出す", "Export")
            subtitle: Pal.t("Android・Windows・Linux・Mac の Kanade で読み込める形で書き出します", "Writes a file that Kanade on Android, Windows, Linux and Mac can import")
            KButton { text: Pal.t("保存先を選ぶ…", "Choose where…"); onClicked: exportDialog.open() }
        }
    }

    PrefGroup {
        title: Pal.t("ファイル", "Files")
        Repeater {
            model: [[Pal.t("設定のフォルダーを開く", "Open the settings folder"), Store.configDir],
                    [Pal.t("記録を開く", "Open the log"), Store.logFile]]
            PrefRow {
                required property var modelData
                clickable: true
                title: modelData[0]
                subtitle: modelData[1]
                onClicked: {
                    if (!Desktop.exists(modelData[1])) ApplicationWindow.window.toast(Pal.t("まだありません", "Does not exist yet"))
                    else Desktop.open(modelData[1])
                }
                Icon { name: "open"; color: Pal.fg; opacity: 0.7 }
            }
        }
    }

    FileDialog {
        id: importDialog
        title: Pal.t("読み込むファイル", "File to import")
        fileMode: FileDialog.OpenFile
        nameFilters: [Pal.t("Kanade の設定（JSON）", "Kanade settings (JSON)") + " (*.json)"]
        currentFolder: Desktop.downloadsDir()
        onAccepted: Engine.request("import-android", Desktop.localPath(selectedFile))
    }
    FileDialog {
        id: exportDialog
        title: Pal.t("書き出す先", "Export to")
        fileMode: FileDialog.SaveFile
        nameFilters: [Pal.t("Kanade の設定（JSON）", "Kanade settings (JSON)") + " (*.json)"]
        currentFolder: Desktop.downloadsDir()
        selectedFile: currentFolder + "/kanade-settings-" + Desktop.today() + ".json"
        onAccepted: Engine.request("export-android", Desktop.localPath(selectedFile))
    }
}
