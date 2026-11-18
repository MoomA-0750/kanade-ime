// 音声のモデル（desktop/…/VoiceModels.kt・linux/settings・windows/settings/VoiceModels.cs と同じ版）を落とす・消す
import Foundation
import SwiftUI

let dataDir = support.appendingPathComponent("engine/data")

struct VoiceModel: Identifiable {
    let id: String
    let name: String
    let ja: String
    let en: String
    let size: String
    let path: String
    /** (名前, URL, バイト数) */
    let files: [(String, String, Int64)]
    var single = false
    var japanese = true
    var english = false

    func target(_ name: String) -> URL { single ? dataDir.appendingPathComponent(path) : dataDir.appendingPathComponent(path).appendingPathComponent(name) }

    var ready: Bool {
        files.allSatisfy { n, _, b in ((try? FileManager.default.attributesOfItem(atPath: target(n).path))?[.size] as? Int64) == b }
    }

    var total: Int64 { files.reduce(0) { $0 + $1.2 } }
}

private let HF = "https://huggingface.co/"

let voiceModels: [VoiceModel] = {
    func multi(_ base: String, _ files: [(String, Int64)]) -> [(String, String, Int64)] { files.map { ($0.0, base + $0.0, $0.1) } }
    return [
        VoiceModel(id: "parakeet", name: "parakeet-ja", ja: "日本語・高い精度・速い・句読点あり", en: "Japanese, high accuracy, fast, punctuation", size: "656MB",
                   path: "parakeet-ja", files: multi(HF + "csukuangfj/sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8/resolve/bef18eb066808c90bd0f5df5be685767b0732de8/",
                                                     [("model.int8.onnx", 655_542_604), ("tokens.txt", 28_557)])),
        VoiceModel(id: "reazon", name: "ReazonSpeech", ja: "日本語・高い精度・とても速い・句読点なし。話している途中の文字にも使います",
                   en: "Japanese, high accuracy, very fast, no punctuation. Also used for the text shown while you speak", size: "73MB",
                   path: "reazonspeech", files: multi(HF + "csukuangfj/reazonspeech-k2-v2-ja-en/resolve/main/",
                                                      [("encoder-epoch-35-avg-1.int8.onnx", 70_876_409), ("decoder-epoch-35-avg-1.int8.onnx", 1_308_690),
                                                       ("joiner-epoch-35-avg-1.int8.onnx", 1_033_417), ("tokens.txt", 26_631)])),
        VoiceModel(id: "sensevoice", name: "SenseVoice", ja: "中国語・英語・日本語・韓国語・精度はふつう・速い・句読点あり",
                   en: "Chinese, English, Japanese, Korean, medium accuracy, fast, punctuation", size: "239MB", path: "sensevoice-2024-07-17",
                   files: multi(HF + "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07/",
                                [("model.int8.onnx", 239_233_841), ("tokens.txt", 315_894)])),
        VoiceModel(id: "whisper-small", name: "whisper small", ja: "多言語・精度は低め・遅い・句読点あり", en: "Multilingual, lower accuracy, slow, punctuation",
                   size: "190MB", path: "ggml-small-q5_1.bin",
                   files: [("ggml-small-q5_1.bin", HF + "ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin", 190_085_487)], single: true, english: true),
        VoiceModel(id: "whisper-kotoba", name: "kotoba-whisper v2.0", ja: "日本語・高い精度・とても遅い・句読点は少なめ", en: "Japanese, high accuracy, very slow, little punctuation",
                   size: "538MB", path: "ggml-kotoba-whisper-v2.0-q5_0.bin",
                   files: [("ggml-kotoba-whisper-v2.0-q5_0.bin", HF + "kotoba-tech/kotoba-whisper-v2.0-ggml/resolve/main/ggml-kotoba-whisper-v2.0-q5_0.bin", 537_819_875)],
                   single: true),
        VoiceModel(id: "whisper-turbo", name: "whisper large-v3-turbo", ja: "多言語・高い精度・とても遅い・句読点あり", en: "Multilingual, high accuracy, very slow, punctuation",
                   size: "574MB", path: "ggml-large-v3-turbo-q5_0.bin",
                   files: [("ggml-large-v3-turbo-q5_0.bin", HF + "ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin", 574_041_195)],
                   single: true, english: true),
        VoiceModel(id: "parakeet-en", name: "parakeet-tdt v3", ja: "英語とヨーロッパの 25 の言語・高い精度・速い・句読点と大文字あり",
                   en: "English and 24 other European languages, high accuracy, fast, punctuation and capitals", size: "670MB", path: "parakeet-v3",
                   files: multi(HF + "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/ed5d745623472f97cfc2e6c2916ee248798fc5a8/",
                                [("encoder.int8.onnx", 652_282_294), ("decoder.int8.onnx", 11_845_274), ("joiner.int8.onnx", 6_355_277), ("tokens.txt", 93_939)]),
                   japanese: false, english: true),
    ]
}()

/** 落としている途中のもの（ページを移っても続ける）。進み具合は 0〜1 */
@MainActor @Observable
final class Downloads {
    static let shared = Downloads()
    var progress: [String: Double] = [:]
    /** 落とせなかったときの理由（モデルの行の下に出す。落とし終えた・消したは行の見た目が変わるので、知らせは出さない） */
    var errors: [String: String] = [:]
    /** 状態が変わった（落とし終えた・消した）ら増える。画面はこれを見て「落としたか」を見直す */
    var revision = 0
    private var tasks: [String: Task<Void, Never>] = [:]

    func start(_ model: VoiceModel, then: @escaping @MainActor () -> Void) {
        guard tasks[model.id] == nil else { return }
        progress[model.id] = 0
        errors[model.id] = nil
        tasks[model.id] = Task.detached {
            let result = await Self.download(model) { f in await MainActor.run { Downloads.shared.progress[model.id] = f } }
            await MainActor.run {
                let d = Downloads.shared
                d.tasks[model.id] = nil
                d.progress[model.id] = nil
                d.revision += 1
                switch result {
                case .success(true): then()
                case .success(false): break
                case .failure(let e): d.errors[model.id] = T("落とせませんでした: \(e.localizedDescription)", "Download failed: \(e.localizedDescription)")
                }
                Attention.shared.refresh()
            }
        }
    }

    func stop(_ id: String) { tasks[id]?.cancel() }

    func delete(_ model: VoiceModel) {
        let url = dataDir.appendingPathComponent(model.path)
        try? FileManager.default.trashItem(at: url, resultingItemURL: nil)
        revision += 1
        Attention.shared.refresh()
    }

    /** 途中まで落としたもの（.part）があれば続きから。止めたら false */
    nonisolated static func download(_ model: VoiceModel, progress: @escaping @Sendable (Double) async -> Void) async -> Result<Bool, Error> {
        let total = Double(model.total)
        var done: Int64 = 0
        do {
            for (name, url, size) in model.files {
                let target = model.target(name)
                try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
                if ((try? FileManager.default.attributesOfItem(atPath: target.path))?[.size] as? Int64) == size {
                    done += size
                    continue
                }
                let part = URL(fileURLWithPath: target.path + ".part")
                let have = ((try? FileManager.default.attributesOfItem(atPath: part.path))?[.size] as? Int64) ?? 0
                var request = URLRequest(url: URL(string: url)!)
                request.setValue("kanade-settings", forHTTPHeaderField: "User-Agent")
                if have > 0 { request.setValue("bytes=\(have)-", forHTTPHeaderField: "Range") }
                let before = done
                let written = try await ChunkDownload(request: request, part: part, have: have) { n in
                    await progress(Double(before + n) / total)
                }.run()
                guard let written else { return .success(false) }
                if written != size { throw NSError(domain: "kanade", code: 1, userInfo: [NSLocalizedDescriptionKey: T("\(name) の大きさが合いません", "\(name) has the wrong size")]) }
                if FileManager.default.fileExists(atPath: target.path) { try FileManager.default.removeItem(at: target) }
                try FileManager.default.moveItem(at: part, to: target)
                done += size
            }
            await progress(1)
            return .success(true)
        } catch is CancellationError {
            return .success(false)
        } catch {
            return Task.isCancelled ? .success(false) : .failure(error)
        }
    }
}

/**
 * 1つのファイルを .part へ書き足す（URLSession のデータの届いた分ごとに書く。続きを頼んで 206 でなければ最初から）。
 * 止めたら nil、書けたら .part の大きさ
 */
final class ChunkDownload: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let request: URLRequest
    private let part: URL
    private var have: Int64
    private let progress: @Sendable (Int64) async -> Void
    private var out: FileHandle?
    private var written: Int64 = 0
    private var continuation: CheckedContinuation<Int64?, Error>?
    private var lastReport = Date.distantPast
    private var task: URLSessionDataTask?

    init(request: URLRequest, part: URL, have: Int64, progress: @escaping @Sendable (Int64) async -> Void) {
        self.request = request
        self.part = part
        self.have = have
        self.progress = progress
    }

    func run() async throws -> Int64? {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (c: CheckedContinuation<Int64?, Error>) in
                continuation = c
                let session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
                let t = session.dataTask(with: request)
                task = t
                t.resume()
            }
        } onCancel: {
            task?.cancel()
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 || status == 206 else {
            completionHandler(.cancel)
            finish(.failure(NSError(domain: "kanade", code: status, userInfo: [NSLocalizedDescriptionKey: "HTTP \(status)"])))
            return
        }
        if have > 0 && status != 206 { have = 0 }
        if have == 0 { FileManager.default.createFile(atPath: part.path, contents: nil) }
        out = try? FileHandle(forWritingTo: part)
        if have > 0 { _ = try? out?.seekToEnd() }
        written = have
        completionHandler(out == nil ? .cancel : .allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        do { try out?.write(contentsOf: data) } catch {
            dataTask.cancel()
            finish(.failure(error))
            return
        }
        written += Int64(data.count)
        if Date().timeIntervalSince(lastReport) > 0.2 {
            lastReport = Date()
            let n = written
            Task { await progress(n) }
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        try? out?.close()
        session.finishTasksAndInvalidate()
        if let e = error as? URLError, e.code == .cancelled {
            finish(.success(nil))
        } else if let error {
            finish(.failure(error))
        } else {
            finish(.success(written))
        }
    }

    private func finish(_ result: Result<Int64?, Error>) {
        guard let c = continuation else { return }
        continuation = nil
        c.resume(with: result)
    }
}
