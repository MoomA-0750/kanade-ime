// アイコンだけの地なしのボタン（丸い部品なので丸のまま）
import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material

RoundButton {
    id: control
    property string iconName
    property string tip
    flat: true
    implicitWidth: 40
    implicitHeight: 40
    Material.elevation: 0
    ToolTip.visible: hovered && tip.length > 0
    ToolTip.text: tip
    ToolTip.delay: 600
    contentItem: Item {
        Icon {
            anchors.centerIn: parent
            name: control.iconName
            color: Pal.fg
        }
    }
}
