// 見出し・説明・行の並んだカード（libadwaita の PreferencesGroup と同じ形）。header に置いたものはカードの外（上）に出る
import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Column {
    id: group
    property string title
    property string description
    property bool card: true
    default property alias rows: rowsColumn.data
    property alias header: headerItem.data
    width: parent ? parent.width : 0
    spacing: 10

    Column {
        width: parent.width
        spacing: 4
        visible: group.title.length > 0 || group.description.length > 0
        Label {
            width: parent.width
            visible: text.length > 0
            text: group.title
            font.pixelSize: 15
            font.bold: true
            wrapMode: Text.Wrap
        }
        Label {
            width: parent.width
            visible: text.length > 0
            text: group.description
            font.pixelSize: 13
            opacity: 0.75
            wrapMode: Text.Wrap
        }
    }
    Item {
        id: headerItem
        width: parent.width
        height: childrenRect.height
        visible: children.length > 0
    }
    Item {
        width: parent.width
        height: rowsColumn.height
        visible: rowsColumn.children.length > 0
        Rectangle {
            anchors.fill: parent
            visible: rowsColumn.height > 0
            color: group.card ? Pal.raised : "transparent"
            radius: Pal.panelRadius
            border.color: group.card ? Pal.border : "transparent"
            border.width: group.card ? 1 : 0
        }
        Column {
            id: rowsColumn
            width: parent.width
        }
    }
}
