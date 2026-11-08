// ユーザー辞書とスニペット。一覧で足す・直す・消す、テキストでまとめて編集
import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Layouts
import Kanade.Backend

PrefPage {
    id: page
    property bool snippets: false
    readonly property string kind: snippets ? "snippets" : "dictionary"
    readonly property string keyLabel: snippets ? Pal.t("打つ文字", "Typed text") : Pal.t("読み", "Reading")
    readonly property string valueLabel: snippets ? Pal.t("置き換える文", "Replacement") : Pal.t("単語", "Word")
    property bool textMode: false
    readonly property var items: Store.revision >= 0 ? Store.entries(kind, search.text) : []

    function demo(what) {
        if (what === "edit" && items.length > 0) editor.edit(items[0])
        else if (what === "add") editor.edit(null)
        else if (what === "text") textButton.click()
    }

    PrefGroup {
        description: page.snippets
            ? Pal.t("Kanade で打った文字がキーと一致した瞬間に、文に置き換えます。例: 「->」→「→」、「@@」→ メールアドレス。改行は \\n と書きます",
                    "As soon as what you type with Kanade matches a key, it is replaced with the text. "
                    + "Examples: -> becomes →, @@ becomes your email address. Write \\n for a line break")
            : Pal.t("読みと単語を登録すると、変換の候補の先頭に出ます", "Words you add here are shown first among the conversion candidates for their reading")
        header: RowLayout {
            width: parent ? parent.width : 0
            spacing: 8
            TextField {
                id: search
                Layout.fillWidth: true
                enabled: !page.textMode
                placeholderText: Pal.t("探す", "Search")
                Material.containerStyle: Material.Outlined
                Material.roundedScale: Pal.smallRadius
                Material.background: Pal.view
            }
            KButton {
                id: textButton
                text: Pal.t("テキストで編集", "Edit as text")
                checkable: true
                checked: page.textMode
                onClicked: {
                    page.textMode = !page.textMode
                    if (page.textMode) area.text = Store.text(page.kind)
                }
            }
            KButton {
                kind: "suggested"
                enabled: !page.textMode
                text: Pal.t("足す", "Add")
                onClicked: editor.edit(null)
            }
        }
    }

    PrefGroup {
        visible: !page.textMode
        PrefRow {
            visible: page.items.length === 0
            dim: true
            title: search.text.trim().length === 0 ? Pal.t("まだありません", "Nothing yet") : Pal.t("見つかりません", "No matches")
        }
        Repeater {
            model: page.items
            PrefRow {
                required property var modelData
                clickable: true
                title: modelData.value
                subtitle: modelData.key
                onClicked: editor.edit(modelData)
                Icon { name: "next"; color: Pal.fg; opacity: 0.6 }
            }
        }
    }

    Column {
        visible: page.textMode
        width: parent ? parent.width : 0
        spacing: 12
        Rectangle {
            width: parent.width
            height: 380
            color: Pal.view
            radius: Pal.panelRadius
            border.color: Pal.border
            ScrollView {
                anchors.fill: parent
                anchors.margins: 1
                clip: true
                TextArea {
                    id: area
                    font.family: "monospace"
                    font.pixelSize: 14
                    wrapMode: TextEdit.NoWrap
                    leftPadding: 10
                    rightPadding: 10
                    topPadding: 8
                    bottomPadding: 8
                    background: null
                    selectByMouse: true
                }
            }
        }
        KButton {
            anchors.right: parent.right
            kind: "suggested"
            text: Pal.t("保存", "Save")
            onClicked: {
                Store.setText(page.kind, area.text)
                ApplicationWindow.window.toast(Pal.t("保存しました", "Saved"))
            }
        }
    }

    // 足す・直す
    KDialog {
        id: editor
        parent: Overlay.overlay
        property var entry: null
        readonly property bool valid: keyField.text.trim().length > 0 && !/[ \t]/.test(keyField.text.trim()) && valueField.text.trim().length > 0
        function edit(e) {
            entry = e
            keyField.text = e ? e.key : ""
            valueField.text = e ? e.value : ""
            open()
            keyField.forceActiveFocus()
        }
        function save() {
            if (!valid) return
            Store.writeEntry(page.kind, entry ? entry.line : -1, keyField.text, valueField.text)
            close()
        }
        heading: entry === null ? Pal.t("足す", "Add") : Pal.t("直す", "Edit")
        TextField {
            id: keyField
            Layout.fillWidth: true
            placeholderText: page.keyLabel
            Material.roundedScale: Pal.smallRadius
            onAccepted: editor.save()
        }
        TextField {
            id: valueField
            Layout.fillWidth: true
            placeholderText: page.valueLabel
            Material.roundedScale: Pal.smallRadius
            onAccepted: editor.save()
        }
        buttons: [
            KButton { text: Pal.t("やめる", "Cancel"); onClicked: editor.close() },
            KButton {
                visible: editor.entry !== null
                kind: "destructive"
                text: Pal.t("消す", "Delete")
                onClicked: { Store.writeEntry(page.kind, editor.entry.line, "", "", true); editor.close() }
            },
            KButton { kind: "suggested"; enabled: editor.valid; text: Pal.t("保存", "Save"); onClicked: editor.save() }
        ]
    }
}
