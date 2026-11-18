// 「確かめてほしいこと」: 足りない許可・落としていない音声のモデル・入力ソースに無い Kanade を、システム設定の「ソフトウェア・アップデートがあります」のように
// サイドバーの上に出して知らせる。許可の様子は入力方式（Kanade.app）が permissions.properties に書く（許可は Kanade.app に付き、この画面からは調べられないため）
import AppKit
import Carbon
import SwiftUI

struct Issue: Identifiable, Equatable {
    enum Fix: Equatable { case privacy(String), voicePage, keyboardSettings }
    let id: String
    let title: String
    let detail: String
    let action: String
    let fix: Fix
    let symbol: String
}

@MainActor @Observable
final class Attention {
    static let shared = Attention()
    private(set) var issues: [Issue] = []
    /** 入力方式が書いた許可の様子（accessibility・microphone） */
    private(set) var perms: [String: String] = [:]
    private var timer: Timer?

    private init() {
        refresh()
        // 許可を入にした・モデルを落としたなどをすぐ映すため、開いているあいだは 2 秒ごとに見直す
        timer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { _ in Task { @MainActor in Attention.shared.refresh() } }
    }

    func refresh(settings: [String: String]? = nil) {
        var list: [Issue] = []
        let perms = Self.permissions()
        if perms != self.perms { self.perms = perms }
        if !Self.kanadeEnabled() {
            list.append(Issue(id: "input-source", title: T("Kanade が入力ソースにありません", "Kanade is not an input source"),
                              detail: T("システム設定 → キーボード → 入力ソースの「編集…」で「+」を押し、日本語の「Kanade」を足します",
                                        "In System Settings → Keyboard → Input Sources, click Edit…, then + and add Kanade under Japanese"),
                              action: T("キーボードの設定を開く", "Open Keyboard settings"), fix: .keyboardSettings, symbol: "keyboard"))
        }
        if perms["accessibility"] == "false" {
            list.append(Issue(id: "accessibility", title: T("アクセシビリティの許可が必要です", "Accessibility permission is needed"),
                              detail: T("録音キーを使うのに要ります。プライバシーとセキュリティ → アクセシビリティで「Kanade」を入にします",
                                        "Needed for the record keys. Turn on Kanade in Privacy & Security → Accessibility"),
                              action: T("許可の設定を開く", "Open privacy settings"), fix: .privacy("Privacy_Accessibility"), symbol: "accessibility"))
        }
        if perms["microphone"] == "denied" || perms["microphone"] == "restricted" {
            list.append(Issue(id: "microphone", title: T("マイクの許可が必要です", "Microphone permission is needed"),
                              detail: T("音声入力に要ります。プライバシーとセキュリティ → マイクで「Kanade」を入にします",
                                        "Needed for voice input. Turn on Kanade in Privacy & Security → Microphone"),
                              action: T("許可の設定を開く", "Open privacy settings"), fix: .privacy("Privacy_Microphone"), symbol: "mic.slash"))
        }
        let values = settings ?? SettingsFile.read()
        let japanese = voiceModels.filter(\.japanese)
        if !japanese.contains(where: \.ready) {
            list.append(Issue(id: "model", title: T("音声認識のモデルがありません", "No speech recognition model"),
                              detail: T("音声入力を使うには、日本語の文を作るモデルを1つ落とします（parakeet-ja がおすすめです）",
                                        "To use voice input, download a model for Japanese (parakeet-ja is recommended)"),
                              action: T("モデルを選ぶ", "Choose a model"), fix: .voicePage, symbol: "waveform.slash"))
        } else if let id = values["voice_final"], let m = japanese.first(where: { $0.id == id }), !m.ready {
            list.append(Issue(id: "model-selected", title: T("選んだモデルを落としていません", "The chosen model is not downloaded"),
                              detail: T("「\(m.name)」が選ばれていますが、まだ落としていません。落とすか、落としたモデルを選びます",
                                        "\(m.name) is chosen but not downloaded. Download it or choose a downloaded model"),
                              action: T("モデルを選ぶ", "Choose a model"), fix: .voicePage, symbol: "waveform.slash"))
        }
        if list != issues { issues = list }
    }

    func perform(_ fix: Issue.Fix) {
        switch fix {
        case .privacy(let anchor):
            NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?\(anchor)")!)
        case .keyboardSettings:
            NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.Keyboard-Settings.extension")!)
        case .voicePage:
            Navigation.shared.page = .voice
        }
    }

    /** 入力方式が書いた許可の様子。ファイルが無ければ（入力方式がまだ動いていない）何も言わない */
    nonisolated static func permissions() -> [String: String] {
        let url = support.appendingPathComponent("permissions.properties")
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return [:] }
        var v: [String: String] = [:]
        for l in text.components(separatedBy: "\n") where !l.hasPrefix("#") {
            if let eq = l.firstIndex(of: "=") { v[String(l[..<eq])] = String(l[l.index(after: eq)...]).trimmingCharacters(in: .whitespaces) }
        }
        return v
    }

    /** Kanade の入力ソースが有効か（入力メニューに出ているか） */
    nonisolated static func kanadeEnabled() -> Bool {
        let filter = [kTISPropertyBundleID as String: "dev.mooma.inputmethod.Kanade"] as CFDictionary
        guard let list = TISCreateInputSourceList(filter, false)?.takeRetainedValue() as? [TISInputSource] else { return false }
        return !list.isEmpty
    }
}

/** サイドバーの上の行（システム設定の「ソフトウェア・アップデートがあります」と同じ形） */
struct AttentionRow: View {
    let issues: [Issue]

    var body: some View {
        Label(issues.count == 1 ? issues[0].title : T("確かめてほしいことがあります", "Needs your attention"),
              systemImage: "exclamationmark.triangle.fill")
            .badge(issues.count)
            .badgeProminence(.increased)
    }
}

struct AttentionPage: View {
    @Bindable private var attention = Attention.shared

    var body: some View {
        Form {
            if attention.issues.isEmpty {
                Section {
                    Label(T("確かめてほしいことはありません", "Nothing needs your attention"), systemImage: "checkmark.circle")
                }
            }
            ForEach(attention.issues) { issue in
                Section {
                    LabeledContent {
                        Button(issue.action) { attention.perform(issue.fix) }
                    } label: {
                        Label {
                            Text(issue.title)
                            Text(issue.detail)
                        } icon: {
                            Image(systemName: issue.symbol).foregroundStyle(.red)
                        }
                    }
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle(T("確かめてほしいこと", "Needs your attention"))
    }
}
