// 候補の窓と、カーソルの近くの小さな表示（「あ」「A」・音声入力の「🎤」と話している途中の文字）。
// IMKCandidates は使わず、自分で描く（頁の表示・説明・クリックをエンジンの形のまま出すため）
import AppKit

/** 前に出るが、入力欄からフォーカスを取らない窓 */
class FloatingPanel: NSPanel {
    init() {
        super.init(contentRect: .zero, styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: true)
        level = .popUpMenu
        isOpaque = false
        backgroundColor = .clear
        hasShadow = true
        hidesOnDeactivate = false
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .transient, .ignoresCycle]
        isReleasedWhenClosed = false
    }

    /**
     * 窓の地。macOS 26 からは Liquid Glass（NSGlassEffectView）、それより前はぼかし。
     * 中身は [content] に入れ、窓の大きさが変わったら一緒に変わる
     */
    static func background(radius: CGFloat, content: NSView) -> NSView {
        content.autoresizingMask = [.width, .height]
        if #available(macOS 26.0, *) {
            let glass = NSGlassEffectView()
            glass.cornerRadius = radius
            glass.contentView = content
            return glass
        }
        let effect = NSVisualEffectView() // apple-ui-allow: macOS 25 以前のための代わり
        effect.material = .popover
        effect.state = .active
        effect.wantsLayer = true
        effect.layer?.cornerRadius = radius // apple-ui-allow: macOS 25 以前のための代わり
        effect.layer?.masksToBounds = true
        content.frame = effect.bounds
        effect.addSubview(content)
        return effect
    }

    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }

    /** [caret]（画面の座標。入力中の文字の行）の下に置く。下がはみ出すなら上に置く */
    func place(size: NSSize, below caret: NSRect) {
        var origin = NSPoint(x: caret.minX, y: caret.minY - size.height - 4)
        let screen = NSScreen.screens.first { $0.frame.intersects(caret) } ?? NSScreen.main
        if let vf = screen?.visibleFrame {
            if origin.y < vf.minY { origin.y = caret.maxY + 4 }
            origin.x = min(max(origin.x, vf.minX), vf.maxX - size.width)
        }
        setFrame(NSRect(origin: origin, size: size), display: true)
    }
}

/** 候補の窓。エンジンは 9 件ずつの頁で数字を受けるので、いまの頁だけを出す */
final class CandidateWindow: FloatingPanel {
    static let page = 9
    private let view = CandidateView()
    var onSelect: ((Int) -> Void)?

    override init() {
        super.init()
        contentView = Self.background(radius: 10, content: view)
        view.onClick = { [weak self] i in self?.onSelect?(i) }
    }

    /** cands <選んでいる位置> <数> <候補> <説明>… */
    func show(_ c: [String], caret: NSRect) {
        let selected = Int(c[1]) ?? -1
        let count = Int(c[2]) ?? 0
        let page = selected >= 0 ? selected / Self.page : 0
        var rows: [(Int, String, String)] = []
        var i = page * Self.page
        while i < count && i < (page + 1) * Self.page {
            let at = 3 + 2 * i
            if at >= c.count { break }
            rows.append((i, c[at], at + 1 < c.count ? c[at + 1] : ""))
            i += 1
        }
        if rows.isEmpty {
            orderOut(nil)
            return
        }
        let pages = (count + Self.page - 1) / Self.page
        view.set(rows: rows, selected: selected, pageLabel: pages > 1 ? "\(page + 1)/\(pages)" : nil)
        let size = view.fittingSize
        place(size: size, below: caret)
        orderFront(nil)
    }
}

final class CandidateView: NSView {
    private var rows: [(Int, String, String)] = []
    private var selected = -1
    private var pageLabel: String?
    var onClick: ((Int) -> Void)?

    private let font = NSFont.systemFont(ofSize: 16) // apple-ui-allow: 候補の窓は、アプリの文字の大きさに関わらず同じ大きさにする
    private let labelFont = NSFont.monospacedDigitSystemFont(ofSize: 12, weight: .regular)
    private let commentFont = NSFont.systemFont(ofSize: 12) // apple-ui-allow: 同上
    private let rowHeight: CGFloat = 26
    private let pad: CGFloat = 6

    override var isFlipped: Bool { true }

    func set(rows: [(Int, String, String)], selected: Int, pageLabel: String?) {
        self.rows = rows
        self.selected = selected
        self.pageLabel = pageLabel
        needsDisplay = true
    }

    override var fittingSize: NSSize {
        var width: CGFloat = 120
        for (_, text, comment) in rows {
            var w = 28 + (text as NSString).size(withAttributes: [.font: font]).width
            if !comment.isEmpty { w += 12 + (comment as NSString).size(withAttributes: [.font: commentFont]).width }
            width = max(width, w + 16)
        }
        let footer: CGFloat = pageLabel == nil ? 0 : 20
        return NSSize(width: ceil(width), height: CGFloat(rows.count) * rowHeight + pad * 2 + footer)
    }

    override func draw(_ dirtyRect: NSRect) {
        for (n, row) in rows.enumerated() {
            let (index, text, comment) = row
            let r = NSRect(x: pad, y: pad + CGFloat(n) * rowHeight, width: bounds.width - pad * 2, height: rowHeight)
            let isSelected = index == selected
            if isSelected {
                NSColor.selectedContentBackgroundColor.setFill()
                NSBezierPath(roundedRect: r, xRadius: 6, yRadius: 6).fill()
            }
            let fg: NSColor = isSelected ? .alternateSelectedControlTextColor : .labelColor
            let sub: NSColor = isSelected ? .alternateSelectedControlTextColor.withAlphaComponent(0.8) : .secondaryLabelColor
            let label = "\(n + 1)" as NSString
            label.draw(at: NSPoint(x: r.minX + 6, y: r.minY + 6), withAttributes: [.font: labelFont, .foregroundColor: sub])
            let t = text as NSString
            t.draw(at: NSPoint(x: r.minX + 22, y: r.minY + 3), withAttributes: [.font: font, .foregroundColor: fg])
            if !comment.isEmpty {
                let x = r.minX + 22 + t.size(withAttributes: [.font: font]).width + 12
                (comment as NSString).draw(at: NSPoint(x: x, y: r.minY + 6), withAttributes: [.font: commentFont, .foregroundColor: sub])
            }
        }
        if let p = pageLabel {
            let s = p as NSString
            let size = s.size(withAttributes: [.font: commentFont])
            s.draw(at: NSPoint(x: bounds.width - pad - 6 - size.width, y: bounds.height - 18),
                   withAttributes: [.font: commentFont, .foregroundColor: NSColor.secondaryLabelColor])
        }
    }

    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override func mouseDown(with event: NSEvent) {
        let p = convert(event.locationInWindow, from: nil)
        let n = Int((p.y - pad) / rowHeight)
        if n >= 0 && n < rows.count { onClick?(rows[n].0) }
    }
}

/** カーソルの近くの小さな表示 */
final class IndicatorWindow: FloatingPanel {
    private let label = NSTextField(labelWithString: "")
    private var timer: Timer?

    override init() {
        super.init()
        label.font = .systemFont(ofSize: 15) // apple-ui-allow: カーソルの近くの小さな表示は、アプリの文字の大きさに関わらず同じ大きさにする
        label.lineBreakMode = .byTruncatingHead
        let holder = NSView()
        holder.addSubview(label)
        contentView = Self.background(radius: 8, content: holder)
    }

    /** [ms] が 0 なら消すまで出す */
    func show(_ text: String, ms: Int, caret: NSRect) {
        label.stringValue = text
        let size = label.fittingSize
        let width = min(max(size.width + 20, 36), 560)
        let frame = NSSize(width: width, height: size.height + 12)
        place(size: frame, below: caret)
        label.frame = NSRect(x: 10, y: 6, width: width - 20, height: size.height)
        orderFront(nil)
        timer?.invalidate()
        timer = nil
        if ms > 0 {
            timer = Timer.scheduledTimer(withTimeInterval: Double(ms) / 1000, repeats: false) { [weak self] _ in self?.orderOut(nil) }
        }
    }

    func hide() {
        timer?.invalidate()
        timer = nil
        orderOut(nil)
    }
}

/** 窓は全部の入力欄で1つずつ使い回す */
enum Panels {
    static let candidates = CandidateWindow()
    static let indicator = IndicatorWindow()

    static func hideAll() {
        candidates.orderOut(nil)
        indicator.hide()
    }
}
