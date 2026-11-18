// Kanade.app を開くだけで入るようにする（自分を ~/Library/Input Methods へ写し、中のエンジンを置き、入力ソースに登録する）。
// 配布物の Kanade.app は、中（Contents/Resources）にエンジン（engine）と設定の画面（Kanade Settings.app）を持つ。
// エンジンは ~/Library/Application Support/Kanade/engine へ写して動かす（落とした音声のモデルをそこに置くので、.app を入れ替えても消えない）。
import AppKit
import Carbon

enum Setup {
    static let fm = FileManager.default
    static let home = fm.homeDirectoryForCurrentUser
    static let inputMethods = home.appendingPathComponent("Library/Input Methods")
    static let installedApp = inputMethods.appendingPathComponent("Kanade.app")
    static let settingsDest = home.appendingPathComponent("Applications/Kanade Settings.app")
    static let engineDest = Paths.support.appendingPathComponent("engine")
    static var bundledEngine: URL { Bundle.main.bundleURL.appendingPathComponent("Contents/Resources/engine") }
    static var bundledSettings: URL { Bundle.main.bundleURL.appendingPathComponent("Contents/Resources/Kanade Settings.app") }

    /** pkg が入れる場所（全員分） */
    static let systemApp = URL(fileURLWithPath: "/Library/Input Methods/Kanade.app")

    /** この Kanade.app が入れた場所（pkg の /Library/Input Methods/Kanade.app か、開発用の ~/Library/Input Methods/Kanade.app）で動いているか */
    static var isInstalledCopy: Bool {
        let me = Bundle.main.bundleURL.resolvingSymlinksInPath().standardizedFileURL.path
        return [installedApp, systemApp].contains { $0.resolvingSymlinksInPath().standardizedFileURL.path == me }
    }

    static func version(_ engine: URL) -> String? {
        (try? String(contentsOf: engine.appendingPathComponent("VERSION"), encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /** 中のエンジンが、置いてあるものと違えば（はじめて・新しい版）写す。中にエンジンが無い（開発用の組み方）なら何もしない */
    @discardableResult
    static func syncEngine() throws -> Bool {
        guard fm.fileExists(atPath: bundledEngine.path), let v = version(bundledEngine) else { return false }
        if version(engineDest) == v, fm.isExecutableFile(atPath: engineDest.appendingPathComponent("bin/kanade-engine").path) { return false }
        klog("engine を写す（\(version(engineDest) ?? "なし") → \(v)）")
        stopEngine()
        try fm.createDirectory(at: engineDest.appendingPathComponent("data"), withIntermediateDirectories: true)
        for d in ["bin", "lib", "native", "runtime", "VERSION"] {
            let dst = engineDest.appendingPathComponent(d)
            if fm.fileExists(atPath: dst.path) { try fm.removeItem(at: dst) }
            try fm.copyItem(at: bundledEngine.appendingPathComponent(d), to: dst)
        }
        // 辞書などは上書きし、落とした音声のモデル（フォルダ）はそのまま残す
        for f in try fm.contentsOfDirectory(at: bundledEngine.appendingPathComponent("data"), includingPropertiesForKeys: nil) {
            let dst = engineDest.appendingPathComponent("data").appendingPathComponent(f.lastPathComponent)
            if fm.fileExists(atPath: dst.path) { try fm.removeItem(at: dst) }
            try fm.copyItem(at: f, to: dst)
        }
        unquarantine(engineDest)
        return true
    }

    /** ダウンロードした .app から写したものに付く隔離の印を外す（本人が開くと決めたもの。付いたままだとエンジンの java を起こせない） */
    static func unquarantine(_ url: URL) {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/xattr")
        p.arguments = ["-dr", "com.apple.quarantine", url.path]
        p.standardOutput = FileHandle.nullDevice
        p.standardError = FileHandle.nullDevice
        try? p.run()
        p.waitUntilExit()
    }

    static func stopEngine() {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/pkill")
        p.arguments = ["-f", "dev.mooma.kanade.desktop.MainKt serve"]
        try? p.run()
        p.waitUntilExit()
        try? fm.removeItem(at: Paths.support.appendingPathComponent("engine.sock"))
    }

    /** 入力ソースに登録して有効にする */
    static func register(_ app: URL) {
        let status = TISRegisterInputSource(app as CFURL)
        klog("register: \(status)")
        let filter = [kTISPropertyBundleID as String: bundleId] as CFDictionary
        for s in (TISCreateInputSourceList(filter, true)?.takeRetainedValue() as? [TISInputSource]) ?? [] {
            TISEnableInputSource(s)
        }
        setThirdPartyEntries(true)
    }

    // 第三者の入力ソースを有効にした一覧（com.apple.inputsources の AppleEnabledThirdPartyInputSources）。
    // TISEnableInputSource はここへ書かないので、書かないと入力メニューで選べない（TISSelectInputSource が -50）。
    // システム設定で足すと書かれるのと同じ2項目（入力モードと入力方式）。読まれるのはログインのときなので、書いたらログインし直す
    static let thirdPartyDomain = "com.apple.inputsources" as CFString
    static let thirdPartyKey = "AppleEnabledThirdPartyInputSources" as CFString
    static var thirdPartyEntries: [[String: String]] {
        [["Bundle ID": bundleId, "Input Mode": "\(bundleId).Japanese", "InputSourceKind": "Input Mode"],
         ["Bundle ID": bundleId, "InputSourceKind": "Keyboard Input Method"]]
    }

    /** 一覧に Kanade の2項目を足す（on）か外す（off）。変えたら true */
    @discardableResult
    static func setThirdPartyEntries(_ on: Bool) -> Bool {
        let current = (CFPreferencesCopyAppValue(thirdPartyKey, thirdPartyDomain) as? [[String: Any]]) ?? []
        var next = current.filter { ($0["Bundle ID"] as? String) != bundleId }
        if on { next += thirdPartyEntries.map { $0 as [String: Any] } }
        let changed = !(next as NSArray).isEqual(to: current)
        if changed {
            CFPreferencesSetAppValue(thirdPartyKey, next as CFArray, thirdPartyDomain)
            CFPreferencesAppSynchronize(thirdPartyDomain)
        }
        klog("入力ソースの一覧（\(thirdPartyKey)）: \(on ? "足す" : "外す") \(changed ? "変えた" : "そのまま")")
        return changed
    }

    /** 入れる。前の Kanade.app はゴミ箱へ。戻り値は失敗の説明（成功なら nil） */
    static func install() -> String? {
        do {
            try fm.createDirectory(at: inputMethods, withIntermediateDirectories: true)
            try syncEngine()
            // 動いている前の版（入力方式のプロセス）を止める
            for app in NSRunningApplication.runningApplications(withBundleIdentifier: bundleId) where app.processIdentifier != getpid() {
                app.forceTerminate()
            }
            if fm.fileExists(atPath: installedApp.path) {
                try fm.trashItem(at: installedApp, resultingItemURL: nil)
            }
            try fm.copyItem(at: Bundle.main.bundleURL, to: installedApp)
            unquarantine(installedApp)
            if fm.fileExists(atPath: bundledSettings.path) {
                try fm.createDirectory(at: settingsDest.deletingLastPathComponent(), withIntermediateDirectories: true)
                if fm.fileExists(atPath: settingsDest.path) { try fm.trashItem(at: settingsDest, resultingItemURL: nil) }
                try fm.copyItem(at: bundledSettings, to: settingsDest)
                unquarantine(settingsDest)
                LSRegisterURL(settingsDest as CFURL, true)
            }
            register(installedApp)
            klog("installed from \(Bundle.main.bundleURL.path)")
            return nil
        } catch {
            klog("install failed: \(error)")
            return error.localizedDescription
        }
    }

    /** Finder などで開かれたとき: 入れて、結果を知らせて終わる */
    static func runInteractive() -> Never {
        let app = NSApplication.shared
        app.setActivationPolicy(.accessory)
        app.activate(ignoringOtherApps: true)
        let error = install()
        let alert = NSAlert()
        if let error {
            alert.alertStyle = .warning
            alert.messageText = L.t("Kanade を入れられませんでした", "Could not install Kanade")
            alert.informativeText = error
        } else {
            alert.messageText = L.t("Kanade を入れました", "Kanade is installed")
            alert.informativeText = L.t(
                "一度ログアウトしてログインし直すと、メニューバーの入力メニューで「Kanade」を選べます（入力ソースの一覧はログインのときに読まれます）。一覧に無ければ、システム設定 → キーボード → 入力ソースの「編集…」で「日本語」の Kanade を足してください。\n\n音声入力には、はじめて使うときにマイクとアクセシビリティの許可が要ります。音声のモデルは、入力メニューの「設定…」の「音声入力」から落とせます。\n\nこの .app（とディスクイメージ）は、もう消してかまいません。",
                "Log out and log back in, then choose \"Kanade\" in the input menu in the menu bar (the list of input sources is read at login). If it is not listed, add Kanade (Japanese) in System Settings → Keyboard → Input Sources → Edit….\n\nVoice input asks for microphone and Accessibility permission the first time. Download voice models from Settings… → Voice input in the input menu.\n\nYou can delete this app (and the disk image) now.")
        }
        alert.runModal()
        exit(error == nil ? 0 : 1)
    }
}
