// 入・切の設定（"true" / "false"）
import QtQuick
import QtQuick.Controls
import Kanade.Backend

PrefRow {
    id: row
    property string key
    clickable: true
    onClicked: { sw.toggle(); sw.toggled() }
    Switch {
        id: sw
        checked: Store.revision >= 0 && Store.get(row.key) === "true"
        onToggled: Store.set(row.key, checked ? "true" : "false")
        // つまみと溝はテーマの色で描く（Material の溝の色はアクセントから作った別の色になるため）
        indicator: Rectangle {
            x: sw.leftPadding
            y: (sw.height - height) / 2
            implicitWidth: 52
            implicitHeight: 32
            radius: height / 2
            color: sw.checked ? Pal.accent : Pal.view
            border.width: sw.checked ? 0 : 2
            border.color: Pal.border
            Behavior on color { ColorAnimation { duration: 120 } }
            Rectangle {
                property int size: sw.checked ? 24 : 16
                width: size
                height: size
                radius: size / 2
                anchors.verticalCenter: parent.verticalCenter
                x: sw.checked ? parent.width - width - 4 : 8
                color: sw.checked ? Pal.textOnAccent : Pal.border
                Behavior on x { NumberAnimation { duration: 120; easing.type: Easing.OutCubic } }
                Behavior on size { NumberAnimation { duration: 120 } }
            }
        }
    }
}
