import QtQuick
import QtQuick.Controls

PrefPage {
    Repeater {
        model: [
            [Pal.t("打つ", "Typing"), [
                Pal.t("fcitx5 で Kanade に切り替えると日本語。英字は keyboard-us などに切り替えて打ちます",
                      "Switch to Kanade in fcitx5 for Japanese; switch to keyboard-us or similar for English"),
                Pal.t("ローマ字で打つと、打つたびに変換した結果が出ます（ライブ変換）。Enter で確定",
                      "Type in romaji and the converted text appears as you type (live conversion). Enter commits"),
                Pal.t("Space で変換し、候補の窓から Space・↑↓・数字・クリックで選びます。←→ で文節を移り、Shift+←→ で区切りを変えます",
                      "Space converts; choose with Space, ↑↓, numbers or a click. ←→ moves between phrases, Shift+←→ changes the boundaries"),
                Pal.t("F6〜F10（Ctrl+U/I/O/P/T）でひらがな・カタカナ・半角カナ・全角英数・半角英数",
                      "F6–F10 (Ctrl+U/I/O/P/T) convert to hiragana, katakana, half-width kana, full-width and half-width letters"),
            ]],
            [Pal.t("音声入力", "Voice input"), [
                Pal.t("録音キー（既定は F12 と右 Alt）を押している間だけ録音し、離すと句読点付きの文が入ります",
                      "Hold a record key (F12 or Right Alt by default) to record; release it to type the sentence with punctuation"),
                Pal.t("Ctrl+Alt+Space は押すたびに録音を始める・終えます。録音中の Esc で取り消します",
                      "Ctrl+Alt+Space starts and stops recording. Esc cancels while recording"),
                Pal.t("Kanade のときは日本語、ほかの入力方式のときは英語で書きます。話している途中の文字はカーソルの近くに出ます",
                      "With Kanade you get Japanese, with other input methods English. The text appears near the cursor while you speak"),
            ]],
            [Pal.t("設定", "Settings"), [
                Pal.t("この画面で変えたものは、すぐ（2 秒ほどで）効きます", "Changes made here take effect within about two seconds"),
                Pal.t("fcitx5 のメニュー（トレイ）の「Kanade: 設定」からもこの画面を開けます", "You can also open this window from Kanade: Settings in the fcitx5 menu"),
            ]],
        ]
        PrefGroup {
            id: section
            required property var modelData
            title: modelData[0]
            card: false
            Repeater {
                model: section.modelData[1]
                Label {
                    required property string modelData
                    width: parent ? parent.width : 0
                    topPadding: 6
                    bottomPadding: 6
                    text: "・" + modelData
                    font.pixelSize: 14
                    wrapMode: Text.Wrap
                }
            }
        }
    }
}
