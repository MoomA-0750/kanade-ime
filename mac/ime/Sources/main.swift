// Kanade の macOS の入力方式（InputMethodKit のアプリ）。~/Library/Input Methods/Kanade.app に置く。
//   ほかの場所（ダウンロードしたディスクイメージなど）で開くと、自分を入れて終わる（Setup.swift）
//   Kanade --setup      同じことを、画面を出さずに行う（install.sh が呼ぶ）
//   Kanade --install    入力ソースに登録して有効にする
//   Kanade --uninstall  入力ソースを無効にする
import AppKit
import Carbon
import InputMethodKit

let bundleId = Bundle.main.bundleIdentifier ?? "dev.mooma.inputmethod.Kanade"
let args = CommandLine.arguments

func inputSources() -> [TISInputSource] {
    let filter = [kTISPropertyBundleID as String: bundleId] as CFDictionary
    return (TISCreateInputSourceList(filter, true)?.takeRetainedValue() as? [TISInputSource]) ?? []
}

if args.contains("--install") {
    let status = TISRegisterInputSource(Bundle.main.bundleURL as CFURL)
    print("register: \(status)")
    for s in inputSources() {
        let id = Unmanaged<CFString>.fromOpaque(TISGetInputSourceProperty(s, kTISPropertyInputSourceID)).takeUnretainedValue()
        print("enable \(id): \(TISEnableInputSource(s))")
    }
    exit(0)
}
if args.contains("--setup") {
    let error = Setup.install()
    print(error ?? "installed")
    exit(error == nil ? 0 : 1)
}
if args.contains("--uninstall") {
    for s in inputSources() {
        print("disable: \(TISDisableInputSource(s))")
    }
    exit(0)
}

if !Setup.isInstalledCopy && !args.contains("--serve-here") {
    Setup.runInteractive()
}
// .app を入れ替えた（新しい版の中のエンジンが置いてあるものと違う）ときは、エンジンを写し直す
do { try Setup.syncEngine() } catch { klog("engine を写せない: \(error)") }

let connection = Bundle.main.infoDictionary?["InputMethodConnectionName"] as? String ?? "dev.mooma.inputmethod.Kanade_Connection"
guard let server = IMKServer(name: connection, bundleIdentifier: bundleId) else {
    klog("IMKServer を作れない: \(connection)")
    exit(1)
}
klog("started (\(connection))")
let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let voice = VoiceInput()
EngineLink.shared.fallback = voice
EngineLink.shared.onVoiceKeys = { hold, toggle in voice.setKeys(hold: hold, toggle: toggle) }
voice.start()
// 録音キーの設定をもらうため、起動のあとでつなぐ（エンジンが動いていなければ起こし、少し待ってつなぎ直す）
var connectTries = 0
Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { t in
    connectTries += 1
    if EngineLink.shared.ensureConnected() || connectTries >= 20 { t.invalidate() }
}
_ = server
app.run()
