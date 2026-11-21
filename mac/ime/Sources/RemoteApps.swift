// キーを Kanade が取らずにそのまま通すアプリ（リモートデスクトップ・仮想マシンなど）。Control+Space や英字を向こう側のホストへ届けるため。
// 標準の一覧に、設定の remote_apps（bundle id のカンマ区切り）を足す
import AppKit

enum RemoteApps {
    static let standard: Set<String> = [
        "com.moonlight-stream.Moonlight", "com.parsecgaming.parsec", "com.p5sys.jump.mac.viewer", "com.microsoft.rdc.macos",
        "com.microsoft.rdc.mac", "com.apple.ScreenSharing", "com.teamviewer.TeamViewer", "com.anydesk.anydesk",
        "com.parallels.desktop.console", "com.utmapp.UTM", "com.vmware.fusion", "com.realvnc.vncviewer", "com.edovia.screens.5",
        "com.edovia.screens.4", "com.splashtop.sos.business", "com.nomachine.nxplayer", "org.virtualbox.app.VirtualBoxVM",
    ]
    /** 設定で足した分 */
    static var extra = Set<String>()

    /** いちばん手前のアプリが、キーを通すアプリか */
    static var isFrontmost: Bool {
        guard let id = NSWorkspace.shared.frontmostApplication?.bundleIdentifier else { return false }
        return standard.contains(id) || extra.contains(id)
    }
}
