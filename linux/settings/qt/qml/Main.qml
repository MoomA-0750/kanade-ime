// 設定の窓。左に一覧、右にページ（幅が狭いときは1列にして、戻るボタンで一覧へ）
import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Layouts
import Kanade.Backend

ApplicationWindow {
    id: win
    property string startPage: "input"
    property string current: startPage
    readonly property bool narrow: width < 600
    property bool showContent: true
    readonly property var pages: [
        { name: "input", title: Pal.t("入力", "Input"), icon: "keyboard" },
        { name: "voice", title: Pal.t("音声入力", "Voice input"), icon: "mic" },
        { name: "dictionary", title: Pal.t("ユーザー辞書", "User dictionary"), icon: "book" },
        { name: "snippets", title: Pal.t("スニペット", "Snippets"), icon: "paste" },
        { name: "backup", title: Pal.t("設定の保存と読み込み", "Backup and restore"), icon: "save" },
        { name: "usage", title: Pal.t("使い方", "How to use"), icon: "help" },
    ]
    readonly property string currentTitle: pages.find(p => p.name === current)?.title ?? ""

    width: 920
    height: 680
    visible: true
    title: Pal.t("Kanade の設定", "Kanade Settings")
    color: Pal.window
    Material.theme: Pal.dark ? Material.Dark : Material.Light
    Material.accent: Pal.accent
    Material.primary: Pal.accent
    Material.background: Pal.window
    Material.foreground: Pal.fg

    function showPage(name) {
        current = ["input", "voice", "dictionary", "snippets", "backup", "usage"].includes(name) ? name : "input"
        showContent = true
    }

    function toast(text) {
        if (!text) return
        toastLabel.text = text
        toastBox.opacity = 1
        toastTimer.restart()
    }

    // 撮るとき（--screenshot）: 「ページ」か「ページ:見せるもの」
    function prepareShot(what) {
        const parts = String(what).split(":")
        // ページを作り直して、前に開いたダイアログを消す
        loader.active = false
        loader.active = true
        if (parts[0] === "list") { showContent = false; return }
        showPage(parts[0])
        if (parts.length > 1) Qt.callLater(() => loader.item.demo(parts[1]))
    }

    Connections {
        target: Engine
        function onFinished(ok, message) { win.toast(message) }
    }
    Connections {
        target: Models
        function onMessage(text) { win.toast(text) }
    }

    RowLayout {
        anchors.fill: parent
        spacing: 0

        // 一覧
        Rectangle {
            Layout.fillHeight: true
            Layout.preferredWidth: win.narrow ? win.width : 240
            visible: !win.narrow || !win.showContent
            color: Pal.view
            ColumnLayout {
                anchors.fill: parent
                spacing: 0
                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 52
                    color: Pal.raised
                    Label {
                        anchors.centerIn: parent
                        text: "Kanade"
                        font.pixelSize: 15
                        font.bold: true
                    }
                    Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Pal.border }
                }
                ListView {
                    id: list
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    Layout.topMargin: 8
                    clip: true
                    model: win.pages
                    spacing: 2
                    delegate: ItemDelegate {
                        id: item
                        required property var modelData
                        width: ListView.view.width - 16
                        x: 8
                        height: 46
                        Material.roundedScale: Pal.smallRadius
                        onClicked: win.showPage(modelData.name)
                        background: Rectangle {
                            radius: Pal.smallRadius
                            color: win.current === item.modelData.name && !(win.narrow && !win.showContent) ? Pal.selected
                                 : item.hovered ? Qt.rgba(Pal.fg.r, Pal.fg.g, Pal.fg.b, 0.06) : "transparent"
                        }
                        contentItem: RowLayout {
                            spacing: 12
                            Icon { name: item.modelData.icon; color: Pal.fg; Layout.leftMargin: 4 }
                            Label {
                                Layout.fillWidth: true
                                text: item.modelData.title
                                font.pixelSize: 14
                                elide: Text.ElideRight
                            }
                        }
                    }
                }
            }
        }
        Rectangle {
            Layout.fillHeight: true
            Layout.preferredWidth: 1
            visible: !win.narrow
            color: Pal.border
        }

        // ページ
        ColumnLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: !win.narrow || win.showContent
            spacing: 0
            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: 52
                color: Pal.raised
                IconButton {
                    anchors.left: parent.left
                    anchors.leftMargin: 6
                    anchors.verticalCenter: parent.verticalCenter
                    visible: win.narrow
                    iconName: "back"
                    tip: Pal.t("戻る", "Back")
                    onClicked: win.showContent = false
                }
                Label {
                    anchors.centerIn: parent
                    width: Math.min(implicitWidth, parent.width - 120)
                    text: win.currentTitle
                    font.pixelSize: 15
                    font.bold: true
                    elide: Text.ElideRight
                }
                Rectangle { anchors.bottom: parent.bottom; width: parent.width; height: 1; color: Pal.border }
            }
            Loader {
                id: loader
                Layout.fillWidth: true
                Layout.fillHeight: true
                sourceComponent: ({ input: inputPage, voice: voicePage, dictionary: dictionaryPage, snippets: snippetsPage,
                                    backup: backupPage, usage: usagePage })[win.current]
            }
        }
    }

    Component { id: inputPage; InputPage {} }
    Component { id: voicePage; VoicePage {} }
    Component { id: dictionaryPage; PairPage { snippets: false } }
    Component { id: snippetsPage; PairPage { snippets: true } }
    Component { id: backupPage; BackupPage {} }
    Component { id: usagePage; UsagePage {} }

    // 下に出る短い知らせ
    Rectangle {
        id: toastBox
        anchors.horizontalCenter: parent.horizontalCenter
        anchors.bottom: parent.bottom
        anchors.bottomMargin: 24
        width: Math.min(toastLabel.implicitWidth + 40, parent.width - 48)
        height: toastLabel.implicitHeight + 24
        radius: Pal.rounding >= 0 ? Pal.rounding : height / 2
        color: Pal.fg
        opacity: 0
        visible: opacity > 0
        Behavior on opacity { NumberAnimation { duration: 180 } }
        Label {
            id: toastLabel
            anchors.centerIn: parent
            width: parent.width - 40
            color: Pal.window
            font.pixelSize: 14
            wrapMode: Text.Wrap
            horizontalAlignment: Text.AlignHCenter
        }
        Timer { id: toastTimer; interval: 4000; onTriggered: toastBox.opacity = 0 }
    }
}
