// 1つのページ。中身は真ん中に寄せ、幅を抑える（libadwaita の PreferencesPage と同じ）
import QtQuick
import QtQuick.Controls

ScrollView {
    id: page
    default property alias content: column.data
    contentWidth: availableWidth
    clip: true
    // 撮るときなどに中身を整える（ページごとに上書き）
    function demo(what) {}

    Item {
        width: page.availableWidth
        implicitHeight: column.implicitHeight + 48
        Column {
            id: column
            y: 24
            width: Math.min(parent.width - 48, 680)
            x: (parent.width - width) / 2
            spacing: 28
        }
    }
}
