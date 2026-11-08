// 色・角の丸み・言葉。Omarchy のテーマ（Theme）があればその色、無ければ Material 3 の基本の色
pragma Singleton
import QtQuick
import Kanade.Backend

QtObject {
    readonly property bool omarchy: Theme.omarchy
    readonly property bool dark: Theme.dark
    readonly property color window: omarchy ? Theme.background : dark ? "#141218" : "#fef7ff"
    readonly property color view: omarchy ? Theme.view : dark ? "#1d1b20" : "#f7f2fa"
    readonly property color raised: omarchy ? Theme.raised : dark ? "#2b2930" : "#ece6f0"
    readonly property color fg: omarchy ? Theme.foreground : dark ? "#e6e0e9" : "#1d1b20"
    readonly property color border: omarchy ? Theme.border : dark ? "#49454f" : "#cac4d0"
    readonly property color accent: omarchy ? Theme.accent : dark ? "#d0bcff" : "#6750a4"
    readonly property color textOnAccent: omarchy ? Theme.textOnAccent : dark ? "#381e72" : "#ffffff"
    readonly property color red: omarchy ? Theme.red : dark ? "#f2b8b5" : "#b3261e"
    readonly property color textOnRed: omarchy ? Theme.textOnRed : dark ? "#601410" : "#ffffff"
    readonly property color selected: Qt.rgba(accent.r, accent.g, accent.b, 0.2)

    // 角の丸み: hyprctl の decoration:rounding。分からなければ Material の既定
    readonly property int rounding: Theme.rounding
    readonly property int panelRadius: rounding >= 0 ? rounding : 12
    readonly property int dialogRadius: rounding >= 0 ? rounding : 28
    readonly property int buttonRadius: rounding >= 0 ? rounding : 100   // 100 は Material.FullScale（丸い端）
    readonly property int smallRadius: rounding >= 0 ? Math.min(4, rounding) : 4

    function t(ja, en) { return Store.english ? en : ja }
}
