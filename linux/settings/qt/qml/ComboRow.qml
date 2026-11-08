// 選ぶ設定。choices: [[値, 表示], …]
import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material
import Kanade.Backend

PrefRow {
    id: row
    property string key
    property var choices: []
    signal changed(string value)
    function openPopup() { combo.popup.open() }
    ComboBox {
        id: combo
        model: row.choices.map(c => c[1])
        implicitContentWidthPolicy: ComboBox.WidestText
        currentIndex: Store.revision >= 0 ? row.choices.findIndex(c => c[0] === Store.get(row.key)) : -1
        Material.roundedScale: Pal.smallRadius
        Material.background: Pal.view
        Material.elevation: 0
        popup.background: Rectangle {
            radius: Pal.smallRadius
            color: Pal.raised
            border.color: Pal.border
        }
        onActivated: index => {
            const value = row.choices[index][0]
            Store.set(row.key, value)
            row.changed(value)
        }
    }
}
