// whisper.cpp を Kotlin の WhisperNative から呼ぶための JNI（音声入力）。
// 文字列は UTF-8 の byte[] で受け渡す。1つのハンドルを複数スレッドから同時に呼ばない（cancel だけは別スレッドから呼べる）。

#include <jni.h>
#include <whisper.h>

#include <atomic>
#include <cstdlib>
#include <string>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "KanadeWhisper", __VA_ARGS__)
#else
#include <cstdio>
#define LOGE(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif

namespace {

struct Whisper {
    whisper_context *ctx = nullptr;
    int threads = 4;
    std::atomic<bool> abort{false};
};

std::string to_string(JNIEnv *env, jbyteArray bytes) {
    if (!bytes) return {};
    jsize n = env->GetArrayLength(bytes);
    std::string s(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(s.data()));
    return s;
}

void quiet_log(ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) LOGE("%s", text);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_dev_mooma_kanade_whisper_WhisperNative_init(JNIEnv *env, jobject, jbyteArray modelPath, jint threads) {
    whisper_log_set(quiet_log, nullptr);
    std::string path = to_string(env, modelPath);
    auto cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (!ctx) {
        LOGE("model load failed: %s", path.c_str());
        return 0;
    }
    auto *w = new Whisper();
    w->ctx = ctx;
    w->threads = threads;
    return reinterpret_cast<jlong>(w);
}

JNIEXPORT void JNICALL Java_dev_mooma_kanade_whisper_WhisperNative_free(JNIEnv *, jobject, jlong handle) {
    auto *w = reinterpret_cast<Whisper *>(handle);
    if (!w) return;
    whisper_free(w->ctx);
    delete w;
}

JNIEXPORT void JNICALL Java_dev_mooma_kanade_whisper_WhisperNative_cancel(JNIEnv *, jobject, jlong handle) {
    auto *w = reinterpret_cast<Whisper *>(handle);
    if (w) w->abort = true;
}

// 16kHz・モノラルの音声（-1〜1 の float）を文字にする。[prompt] は書き方の手本（句読点を付けるなど）。中断・失敗は null
JNIEXPORT jbyteArray JNICALL Java_dev_mooma_kanade_whisper_WhisperNative_transcribe(JNIEnv *env, jobject, jlong handle,
                                                                                   jfloatArray pcm, jbyteArray language,
                                                                                   jbyteArray prompt) {
    auto *w = reinterpret_cast<Whisper *>(handle);
    w->abort = false;
    jsize n = env->GetArrayLength(pcm);
    std::vector<float> samples(static_cast<size_t>(n));
    env->GetFloatArrayRegion(pcm, 0, n, samples.data());
    std::string lang = to_string(env, language);
    std::string initial = to_string(env, prompt);

    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = w->threads;
    params.language = lang.empty() ? "ja" : lang.c_str();
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.suppress_nst = true;
    // エンコーダは既定で 30 秒の窓を丸ごと計算する。短い音声なら窓を音声の長さまで縮めると何倍も速い
    // （1 秒 = 50 フレーム。少し余裕を足す）。環境変数 KANADE_FULL_CTX があれば縮めない（比べるため）
    if (!std::getenv("KANADE_FULL_CTX")) {
        int frames = static_cast<int>(samples.size() / 320) + 64;
        if (frames < 1500) params.audio_ctx = frames;
    }
    params.initial_prompt = initial.empty() ? nullptr : initial.c_str();
    params.abort_callback = [](void *data) { return static_cast<Whisper *>(data)->abort.load(); };
    params.abort_callback_user_data = w;

    if (whisper_full(w->ctx, params, samples.data(), static_cast<int>(samples.size())) != 0) return nullptr;
    std::string out;
    int segments = whisper_full_n_segments(w->ctx);
    for (int i = 0; i < segments; i++) {
        // 話していない区間（無音・雑音）から出た文は捨てる（whisper は無音から「ご視聴ありがとうございました」などを作りやすい）
        if (whisper_full_get_segment_no_speech_prob(w->ctx, i) > 0.6f) continue;
        out += whisper_full_get_segment_text(w->ctx, i);
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

}  // extern "C"
