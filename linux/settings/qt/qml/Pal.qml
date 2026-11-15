// 色・角の丸み・余白・言葉。Omarchy のテーマ（Theme）があればその色、無ければ kasane の控えの色（Kasane.qml。アイコンの色が種）
pragma Singleton
import QtQuick
import Kanade.Backend

QtObject {
    readonly property bool omarchy: Theme.omarchy
    readonly property bool dark: Theme.dark
    // kasane の値（角丸・余白・控えの色）。kasane-sync で写したもの。手で直さない
    readonly property Kasane kasane: Kasane {}
    readonly property var k: kasane.colors(dark)
    readonly property color window: omarchy ? Theme.background : k.bg
    readonly property color view: omarchy ? Theme.view : k.surface
    readonly property color raised: omarchy ? Theme.raised : k.surface2
    readonly property color fg: omarchy ? Theme.foreground : k.fg
    readonly property color border: omarchy ? Theme.border : k.lineStrong
    readonly property color accent: omarchy ? Theme.accent : k.accent
    readonly property color textOnAccent: omarchy ? Theme.textOnAccent : k.onAccent
    readonly property color red: omarchy ? Theme.red : k.danger
    readonly property color textOnRed: omarchy ? Theme.textOnRed : k.onDanger
    readonly property color selected: Qt.rgba(accent.r, accent.g, accent.b, 0.2)

    // 余白の段（kasane。s4 = 16）
    readonly property var space: kasane.space

    // 角の丸み: hyprctl の decoration:rounding。分からなければ kasane の角丸
    readonly property int rounding: Theme.rounding
    readonly property int panelRadius: rounding >= 0 ? rounding : kasane.radius.card
    readonly property int dialogRadius: rounding >= 0 ? rounding : kasane.radius.card
    readonly property int buttonRadius: rounding >= 0 ? rounding : kasane.radius.control
    readonly property int smallRadius: rounding >= 0 ? Math.min(4, rounding) : kasane.radius.sm
    // 同心の角丸: 外の丸みと余白から中の丸み。Hyprland の丸みが 0（角張る）なら中も 0 のまま
    function innerRadius(outer: real, padding: real): real {
        return rounding >= 0 ? Math.max(0, outer - padding) : kasane.innerRadius(outer, padding)
    }

    function t(ja, en) { return Store.english ? en : ja }
}
