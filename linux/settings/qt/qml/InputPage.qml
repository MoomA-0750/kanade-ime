import QtQuick
import QtQuick.Controls
import Kanade.Backend

PrefPage {
    id: page
    function demo(what) {
        if (what === "clear") confirm.open()
        else if (what === "combo") punctuationRow.openPopup()
    }

    PrefGroup {
        title: Pal.t("変換", "Conversion")
        SwitchRow {
            key: "live_conversion"
            title: Pal.t("ライブ変換", "Live conversion")
            subtitle: Pal.t("打つたびに変換した結果を出します。切ると Space を押すまでかなのままです",
                            "Shows the converted text as you type. When off, text stays in kana until you press Space")
        }
        SwitchRow {
            key: "full_width_symbols"
            title: Pal.t("数字・記号を全角にする", "Full-width numbers and symbols")
            subtitle: Pal.t("日本語入力中の数字・記号・大文字を全角で入れます（Space は半角のまま）",
                            "Types numbers, symbols and capital letters in full width while typing Japanese (Space stays half width)")
        }
        ComboRow {
            id: punctuationRow
            key: "punctuation"
            title: Pal.t("句読点", "Punctuation")
            subtitle: Pal.t("「,」と「.」で入る句読点です", "The marks typed with the comma and period keys")
            choices: ["、。", "，．", "、．", "，。"].map(p => [p, p])
        }
        SwitchRow {
            key: "pair_brackets"
            title: Pal.t("括弧を対で入れる", "Insert brackets in pairs")
            subtitle: Pal.t("開き括弧を打つと閉じ括弧も入れ、カーソルを中に置きます。空の括弧で Space を押すと括弧の種類が替わります",
                            "Typing an opening bracket also inserts the closing one and puts the cursor between them. "
                            + "Press Space in empty brackets to change their kind")
        }
    }

    PrefGroup {
        title: Pal.t("日本語と英字の切り替え", "Switching between Japanese and English")
        description: Pal.t("fcitx5 が受け持ちます。Kanade に切り替えると、いつも日本語から始まります。英字は fcitx5 の keyboard-us などで打ちます",
                           "fcitx5 handles this. Switching to Kanade always starts in Japanese; type English with fcitx5's keyboard-us or similar")
        PrefRow {
            visible: Desktop.hasProgram("fcitx5-configtool")
            title: Pal.t("切り替えのキーを変える", "Change the switching keys")
            subtitle: Pal.t("fcitx5 の設定の「全体の設定」で決めます", "Set them under Global Options in the fcitx5 settings")
            KButton {
                text: Pal.t("fcitx5 の設定を開く", "Open fcitx5 settings")
                onClicked: Desktop.launch("fcitx5-configtool")
            }
        }
    }

    PrefGroup {
        title: Pal.t("学習", "Learning")
        SwitchRow {
            key: "learning"
            title: Pal.t("選んだ変換を覚える", "Remember chosen conversions")
            subtitle: Pal.t("候補から選んで確定したものを、次から先に出します", "Candidates you pick are shown first next time")
        }
        PrefRow {
            title: Pal.t("覚えたものを消す", "Clear learned conversions")
            subtitle: Pal.t("学習した変換をすべて消します（ユーザー辞書は消えません）", "Deletes everything learned (your user dictionary is kept)")
            KButton {
                kind: "destructive"
                text: Pal.t("消す", "Clear")
                onClicked: confirm.open()
            }
        }
    }

    PrefGroup {
        title: Pal.t("表示", "Display")
        ComboRow {
            id: languageRow
            key: "ui_language"
            title: Pal.t("表示の言語（Language）", "Language（表示の言語）")
            subtitle: Pal.t("この画面・メニュー・カーソルの近くの表示の言語です", "The language of this window, the menu and the messages near the cursor")
            choices: [["auto", Pal.t("システムに合わせる", "Same as the system")], ["ja", "日本語"], ["en", "English"]]
        }
    }

    KDialog {
        id: confirm
        parent: Overlay.overlay
        heading: Pal.t("覚えたものを消しますか？", "Clear what Kanade has learned?")
        body: Pal.t("元に戻せません。ユーザー辞書は残ります", "This cannot be undone. Your user dictionary is kept")
        buttons: [
            KButton { text: Pal.t("やめる", "Cancel"); onClicked: confirm.close() },
            KButton {
                kind: "destructive"
                text: Pal.t("消す", "Clear")
                onClicked: { confirm.close(); Engine.request("clear-learning") }
            }
        ]
    }
}
