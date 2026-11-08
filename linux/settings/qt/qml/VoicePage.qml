import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Layouts
import Kanade.Backend

PrefPage {
    id: page
    readonly property var holds: Store.revision >= 0 ? Store.get("voice_hold_keys").split(",").filter(k => k.length > 0) : []
    readonly property string toggle: Store.revision >= 0 ? Store.get("voice_toggle_key") : ""

    function demo(what) {
        if (what === "key") capture.ask(false, k => {})
        else if (what === "download" || what === "resume") Models.download("reazon", "voice_final")
        else if (what === "stop") Models.stop("reazon")
    }
    function setHolds(keys) { Store.set("voice_hold_keys", keys.join(",")) }

    PrefGroup {
        title: Pal.t("録音キー", "Record keys")
        description: Pal.t("どの入力方式でも使えます。Kanade のときは日本語、ほかの入力方式（英字など）のときは英語で書きます。録音中に Esc で取り消します",
                           "Work with any input method. With Kanade you get Japanese, with other input methods (such as English) you get English. "
                           + "Press Esc while recording to cancel")
        Repeater {
            model: page.holds
            PrefRow {
                required property string modelData
                title: KeyGrab.label(modelData, Store.english)
                subtitle: Pal.t("押している間だけ録音し、離すと文字にします", "Records while held and writes the text when released")
                prefix: Icon { name: "mic"; color: Pal.fg }
                IconButton {
                    iconName: "remove"
                    tip: Pal.t("外す", "Remove")
                    onClicked: page.setHolds(page.holds.filter(h => h !== modelData))
                }
            }
        }
        PrefRow {
            clickable: true
            centered: true
            title: Pal.t("押している間だけ録音するキーを足す", "Add a hold-to-record key")
            prefix: Icon { name: "add"; color: Pal.fg }
            onClicked: capture.ask(false, k => page.setHolds(page.holds.includes(k) ? page.holds : page.holds.concat([k])))
        }
        PrefRow {
            title: Pal.t("押すたびに始める・終える", "Press to start and stop")
            subtitle: page.toggle ? KeyGrab.label(page.toggle, Store.english) : Pal.t("使わない", "Not used")
            prefix: Icon { name: "record"; color: Pal.fg }
            KButton {
                text: Pal.t("変える", "Change")
                onClicked: capture.ask(true, k => Store.set("voice_toggle_key", k))
            }
            KButton {
                visible: page.toggle.length > 0
                kind: "flat"
                text: Pal.t("使わない", "Turn off")
                onClicked: Store.set("voice_toggle_key", "")
            }
        }
    }

    PrefGroup {
        title: Pal.t("書き方", "Writing")
        SwitchRow {
            key: "spoken_punctuation"
            title: Pal.t("話した句読点", "Spoken punctuation")
            subtitle: Pal.t("日本語で「まる」「てん」「かいぎょう」などを、句読点や改行にします",
                            "In Japanese, turns spoken words such as maru, ten and kaigyou into punctuation and line breaks")
        }
    }

    ModelGroup {
        title: Pal.t("日本語の文を作るモデル", "Model for Japanese")
        description: Pal.t("録音を終えたときに、句読点付きの文を作るモデルです。使うには落としておきます（途中で止めても続きから落とせます）。"
                           + "ReazonSpeech は話している途中の文字にも使います",
                           "Writes the sentence with punctuation when recording ends. Download it to use it (a stopped download resumes). "
                           + "ReazonSpeech is also used for the text shown while you speak")
        key: "voice_final"
        language: "ja"
    }
    ModelGroup {
        title: Pal.t("英語の文を作るモデル", "Model for English")
        description: Pal.t("Kanade 以外の入力方式のときに使います", "Used when another input method is active")
        key: "voice_final_en"
        language: "en"
    }

    // 次に押したキーの名前を取る
    KDialog {
        id: capture
        parent: Overlay.overlay
        property bool combo: false
        property var done: null
        function ask(c, callback) { combo = c; done = callback; open() }
        heading: Pal.t("キーを押してください", "Press a key")
        body: combo ? Pal.t("Ctrl・Alt などと組み合わせたキーを押します（Esc でやめる）", "Press a key together with Ctrl, Alt or similar (Esc to cancel)")
                    : Pal.t("F9・右 Alt など、ふだん使わないキーを押します（Esc でやめる）", "Press a key you do not normally use, such as F9 or Right Alt (Esc to cancel)")
        closePolicy: Popup.NoAutoClose
        onOpened: KeyGrab.start(combo)
        onClosed: KeyGrab.stop()
        buttons: [KButton { text: Pal.t("やめる", "Cancel"); onClicked: capture.close() }]
        Connections {
            target: KeyGrab
            function onCaptured(name) {
                capture.close()
                if (capture.done) capture.done(name)
            }
            function onCancelled() { capture.close() }
        }
    }

    component ModelGroup: PrefGroup {
        id: group
        property string key
        property string language
        readonly property string current: Store.revision >= 0 ? Store.get(key) : ""
        ButtonGroup { id: radios }
        Repeater {
            model: Models.revision >= 0 && Store.english !== undefined ? Models.list(group.language) : []
            PrefRow {
                id: row
                required property var modelData
                readonly property string id_: modelData.id
                title: modelData.name + "（" + modelData.size + "）"
                subtitle: modelData.description
                clickable: modelData.ready
                onClicked: Store.set(group.key, id_)
                prefix: RadioButton {
                    ButtonGroup.group: radios
                    checked: row.id_ === group.current
                    enabled: row.modelData.ready
                    onClicked: Store.set(group.key, row.id_)
                }
                // 落としている途中
                ProgressBar {
                    id: bar
                    visible: row.modelData.downloading
                    Layout.preferredWidth: 140
                    value: 0
                    Connections {
                        target: Models
                        function onProgressChanged(id) { if (id === row.id_) bar.value = Models.progress(id) }
                    }
                    Component.onCompleted: value = Models.progress(row.id_)
                }
                Label {
                    visible: row.modelData.downloading
                    text: Math.floor(bar.value * 100) + "%"
                    font.pixelSize: 13
                    Layout.preferredWidth: 40
                }
                IconButton {
                    visible: row.modelData.downloading
                    iconName: "stop"
                    tip: Pal.t("止める", "Stop")
                    onClicked: Models.stop(row.id_)
                }
                // 落としてある
                Label {
                    visible: !row.modelData.downloading && row.modelData.ready && row.id_ === group.current
                    text: Pal.t("使用中", "In use")
                    opacity: 0.6
                    font.pixelSize: 13
                }
                IconButton {
                    visible: !row.modelData.downloading && row.modelData.ready
                    iconName: "delete"
                    tip: Pal.t("消す（ゴミ箱へ）", "Delete (to trash)")
                    onClicked: Models.remove(row.id_)
                }
                // まだない
                KButton {
                    visible: !row.modelData.downloading && !row.modelData.ready
                    text: Pal.t("落とす", "Download")
                    onClicked: Models.download(row.id_, group.key)
                }
            }
        }
    }
}
