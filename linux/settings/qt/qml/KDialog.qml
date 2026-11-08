// 確かめる・入れるためのダイアログ。buttons に KButton を並べる（右寄せ）
import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import QtQuick.Layouts

Dialog {
    id: dialog
    property string heading
    property string body
    default property alias extra: extraColumn.data
    property alias buttons: buttonRow.data
    modal: true
    anchors.centerIn: Overlay.overlay
    width: Math.min(440, (parent ? parent.width : 440) - 48)
    padding: 24
    Material.roundedScale: Pal.dialogRadius
    Material.background: Pal.raised
    Material.elevation: 6
    // 地と影はここで描く（Material の影は層の効果で描くため、描き先によっては地ごと消える）
    background: Rectangle {
        radius: Pal.dialogRadius
        color: Pal.raised
        border.color: Pal.border
        border.width: 1
    }
    Overlay.modal: Rectangle { color: Qt.rgba(0, 0, 0, 0.5) }

    contentItem: ColumnLayout {
        spacing: 16
        Label {
            Layout.fillWidth: true
            text: dialog.heading
            font.pixelSize: 19
            font.bold: true
            wrapMode: Text.Wrap
            horizontalAlignment: Text.AlignHCenter
        }
        Label {
            Layout.fillWidth: true
            visible: text.length > 0
            text: dialog.body
            font.pixelSize: 14
            opacity: 0.8
            wrapMode: Text.Wrap
            horizontalAlignment: Text.AlignHCenter
        }
        ColumnLayout {
            id: extraColumn
            Layout.fillWidth: true
            spacing: 8
            visible: children.length > 0
        }
        Row {
            id: buttonRow
            Layout.alignment: Qt.AlignRight
            Layout.topMargin: 4
            spacing: 8
        }
    }
}
