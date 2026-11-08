// ボタン。kind: "" ふつう、"suggested" アクセント色、"destructive" 赤、"flat" 地なし
import QtQuick
import QtQuick.Controls
import QtQuick.Controls.Material

Button {
    id: control
    property string kind: ""
    readonly property bool filled: kind === "suggested" || kind === "destructive"
    readonly property color fill: kind === "destructive" ? Pal.red : Pal.accent
    flat: kind === "flat"
    highlighted: filled
    Material.accent: fill
    Material.elevation: 0
    Material.roundedScale: Pal.rounding >= 0 ? Pal.rounding : Material.FullScale
    font.pixelSize: 14

    // 地: アクセント・赤・なし、ふつうは一覧の地の色に線（カードの上で見分けられるように）
    background: Rectangle {
        implicitWidth: 64
        implicitHeight: 36
        radius: Pal.rounding >= 0 ? Pal.rounding : height / 2
        color: control.filled ? control.fill : control.flat ? "transparent" : control.checked ? Pal.selected : Pal.view
        border.width: control.filled || control.flat ? 0 : 1
        border.color: control.checked ? Pal.accent : Pal.border
        opacity: control.enabled ? 1 : 0.5
        Rectangle {
            anchors.fill: parent
            radius: parent.radius
            color: control.filled ? (control.kind === "destructive" ? Pal.textOnRed : Pal.textOnAccent) : Pal.fg
            opacity: control.down ? 0.16 : control.hovered ? 0.08 : 0
        }
    }

    contentItem: Label {
        text: control.text
        font: control.font
        horizontalAlignment: Text.AlignHCenter
        verticalAlignment: Text.AlignVCenter
        elide: Text.ElideRight
        // アクセントの上の文字は、テーマの背景色と文字色のうち差が大きい方
        color: !control.enabled ? Qt.rgba(Pal.fg.r, Pal.fg.g, Pal.fg.b, 0.4)
             : control.filled ? (control.kind === "destructive" ? Pal.textOnRed : Pal.textOnAccent)
             : control.checked ? Pal.accent : Pal.fg
    }
}
