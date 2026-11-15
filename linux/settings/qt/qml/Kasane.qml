// 書き出したもの。直すのは kasane の tokens.css と themes/personal.css（scripts/build.mjs）
// kasane 1.1.0  source abfa0d9adc4d4ff1  seed #4C5C92 #4C5C92
import QtQuick

// kasane の値。登録はアプリの仕組みのまま（singleton にするか、1つ作って渡す）。明暗はアプリの Theme.dark で選ぶ
// 角丸は控え: Omarchy（Hyprland の decoration:rounding）の丸さが分かるときは、アプリがそちらを使う
QtObject {
    component Radii: QtObject {
        readonly property real sm: 6
        readonly property real base: 10
        readonly property real lg: 16
        readonly property real min: 2
        readonly property real control: 10
        readonly property real field: 10
        readonly property real card: 26
        readonly property real row: 26
    }
    component Spaces: QtObject {
        readonly property real s1: 4
        readonly property real s2: 8
        readonly property real s3: 12
        readonly property real s4: 16
        readonly property real s5: 20
        readonly property real s6: 24
        readonly property real s8: 32
        readonly property real s10: 40
    }
    component Palette: QtObject {
        required property color bg
        required property color surface
        required property color surface2
        required property color raised
        required property color input
        required property color fg
        required property color muted
        required property color faint
        required property color line
        required property color lineStrong
        required property color hover
        required property color active
        required property color accent
        required property color accentStrong
        required property color accentSoft
        required property color onAccent
        required property color fill
        required property color onFill
        required property color fillHover
        required property color link
        required property color focus
        required property color danger
        required property color dangerSoft
        required property color dangerFill
        required property color dangerFillHover
        required property color onDanger
        required property color warn
        required property color warnSoft
        required property color success
        required property color successSoft
        required property color codeBg
        required property color codeFg
        required property color backdrop
        required property color scrim
        required property color onMedia
        required property color mediaControl
    }

    readonly property Radii radius: Radii {}
    readonly property Spaces space: Spaces {}
    // 控えの色（Omarchy のテーマが無いとき）。色は #AARRGGBB
    readonly property Palette light: Palette {
        bg: "#FFF2F4FA"
        surface: "#FFFDFDFF"
        surface2: "#FFE9EDF6"
        raised: "#FFFDFEFF"
        input: "#FFFDFEFF"
        fg: "#FF272B35"
        muted: "#FF555A6A"
        faint: "#FF868C9D"
        line: "#21313952"
        lineStrong: "#8F252D44"
        hover: "#0F313952"
        active: "#1A313952"
        accent: "#FF4C5C92"
        accentStrong: "#FF3A487C"
        accentSoft: "#244C5C92"
        onAccent: "#FFFFFFFF"
        fill: "#FF4C5C92"
        onFill: "#FFFFFFFF"
        fillHover: "#FF485687"
        link: "#FF4C5C92"
        focus: "#FF4C5C92"
        danger: "#FFC5293E"
        dangerSoft: "#1AC5293E"
        dangerFill: "#FFC5293E"
        dangerFillHover: "#FFB2293D"
        onDanger: "#FFFFFFFF"
        warn: "#FFA14F05"
        warnSoft: "#1FA14F05"
        success: "#FF007571"
        successSoft: "#1F007571"
        codeBg: "#247A8091"
        codeFg: "#FFB6303A"
        backdrop: "#66080B13"
        scrim: "#99000000"
        onMedia: "#FFFFFFFF"
        mediaControl: "#26FFFFFF"
    }
    readonly property Palette dark: Palette {
        bg: "#FF16171B"
        surface: "#FF1E1F24"
        surface2: "#FF24262D"
        raised: "#FF2A2B30"
        input: "#FF191A1F"
        fg: "#FFE5E8EE"
        muted: "#FFADB1BB"
        faint: "#FF777A84"
        line: "#1AE9EEFE"
        lineStrong: "#5CE9EEFE"
        hover: "#0FE9EEFE"
        active: "#1AE9EEFE"
        accent: "#FF899CD7"
        accentStrong: "#FF9EB2EE"
        accentSoft: "#24899CD7"
        onAccent: "#FF121620"
        fill: "#FF899CD7"
        onFill: "#FF121620"
        fillHover: "#FF94A5D9"
        link: "#FF899CD7"
        focus: "#FF899CD7"
        danger: "#FFFF757B"
        dangerSoft: "#1AFF757B"
        dangerFill: "#FFFF757B"
        dangerFillHover: "#FFFD8389"
        onDanger: "#FF2B0B09"
        warn: "#FFFFA255"
        warnSoft: "#1FFFA255"
        success: "#FF27AEA5"
        successSoft: "#1F27AEA5"
        codeBg: "#337A8090"
        codeFg: "#FFFF757B"
        backdrop: "#99000000"
        scrim: "#99000000"
        onMedia: "#FFFFFFFF"
        mediaControl: "#26FFFFFF"
    }

    function colors(isDark: bool): Palette { return isDark ? dark : light }
    // 同心の角丸: 中の角丸 = max(radius-min, 外 − 余白)。外がピル・Hyprland の丸さのときも同じ
    function innerRadius(outer: real, padding: real): real { return Math.max(radius.min, outer - padding) }
}
