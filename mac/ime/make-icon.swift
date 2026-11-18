// メニューバーの入力ソースの印（入力方式は「か」、モードは「あ」「A」。テンプレートの画像なので黒だけで描く）を TIFF で書く: swift make-icon.swift <出力> [文字]
import AppKit
let out = CommandLine.arguments[1]
let glyph = CommandLine.arguments.count > 2 ? CommandLine.arguments[2] : "か"
let reps: [NSBitmapImageRep] = [16, 32].map { px in
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: px, pixelsHigh: px, bitsPerSample: 8, samplesPerPixel: 4,
                               hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    rep.size = NSSize(width: 16, height: 16)
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    let s = glyph as NSString
    let attrs: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: 13, weight: .semibold), .foregroundColor: NSColor.black] // apple-ui-allow: メニューバーの印の絵を作る
    let size = s.size(withAttributes: attrs)
    s.draw(at: NSPoint(x: (16 - size.width) / 2, y: (16 - size.height) / 2), withAttributes: attrs)
    NSGraphicsContext.restoreGraphicsState()
    return rep
}
let data = NSBitmapImageRep.tiffRepresentationOfImageReps(in: reps, using: .none, factor: 0)!
try! data.write(to: URL(fileURLWithPath: out))
