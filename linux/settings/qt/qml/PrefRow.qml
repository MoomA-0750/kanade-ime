// カードの中の1行。左に prefix、真ん中に見出しと説明、右に置いたもの（既定の子）
import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

Item {
    id: row
    property string title
    property string subtitle
    property bool clickable: false
    property bool dim: false
    property bool centered: false
    property alias prefix: prefixRow.data
    default property alias suffix: suffixRow.data
    signal clicked()
    width: parent ? parent.width : 0
    implicitHeight: Math.max(58, layout.implicitHeight + 22)

    Rectangle {
        anchors.fill: parent
        anchors.margins: 1
        radius: Math.max(0, Pal.panelRadius - 1)
        color: Pal.fg
        opacity: mouse.pressed ? 0.1 : mouse.containsMouse ? 0.05 : 0
    }
    MouseArea {
        id: mouse
        anchors.fill: parent
        enabled: row.clickable
        hoverEnabled: row.clickable
        cursorShape: row.clickable ? Qt.PointingHandCursor : Qt.ArrowCursor
        onClicked: row.clicked()
    }
    RowLayout {
        id: layout
        anchors.fill: parent
        anchors.leftMargin: 16
        anchors.rightMargin: 12
        spacing: 12
        RowLayout {
            id: prefixRow
            Layout.alignment: Qt.AlignVCenter
            spacing: 8
            visible: children.length > 0
        }
        ColumnLayout {
            Layout.fillWidth: true
            Layout.alignment: Qt.AlignVCenter
            spacing: 2
            Label {
                Layout.fillWidth: true
                text: row.title
                font.pixelSize: 15
                wrapMode: Text.Wrap
                opacity: row.dim ? 0.6 : 1
                horizontalAlignment: row.centered ? Text.AlignHCenter : Text.AlignLeft
                textFormat: Text.PlainText
            }
            Label {
                Layout.fillWidth: true
                visible: text.length > 0
                text: row.subtitle
                font.pixelSize: 13
                opacity: 0.7
                wrapMode: Text.Wrap
                textFormat: Text.PlainText
            }
        }
        RowLayout {
            id: suffixRow
            Layout.alignment: Qt.AlignVCenter
            spacing: 6
            visible: children.length > 0
        }
    }
    // 並びの先頭でなければ、上に区切りの線
    Rectangle {
        width: parent.width
        height: 1
        color: Pal.border
        visible: row.y > 0
    }
}
