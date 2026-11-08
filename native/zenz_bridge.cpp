// zenz（GPT-2 系のかな漢字変換モデル）を llama.cpp で動かし、Kotlin の ZenzNative から呼ぶための JNI。
// 文字列は Kotlin 側で UTF-8 の byte[] にして渡す（JNI の modified UTF-8 を避ける）。
// 1つのハンドルを複数スレッドから同時に呼ばない前提（Kotlin 側で1本のスレッドに寄せる）。cancel だけは別スレッドから呼べる。

#include <jni.h>
#include <llama.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>

#ifdef __ANDROID__
#include <android/log.h>
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "KanadeZenz", __VA_ARGS__)
#else
#include <cstdio>
#define LOGE(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif

namespace {

struct Zenz {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    llama_batch batch{};
    int n_ctx = 0;
    // KV キャッシュに入っているトークン列。次の入力と先頭が一致する分は計算し直さない
    std::vector<llama_token> cached;
    std::atomic<bool> abort{false};
};

// 系列 0 はプロンプト用、1..kMaxSeq-1 を候補の採点に使う
constexpr int kMaxSeq = 16;

bool abort_callback(void *data) { return static_cast<Zenz *>(data)->abort.load(); }

void quiet_log(ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) LOGE("%s", text);
}

std::string to_string(JNIEnv *env, jbyteArray bytes) {
    jsize n = env->GetArrayLength(bytes);
    std::string s(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(s.data()));
    return s;
}

std::vector<llama_token> tokenize(const Zenz &z, const std::string &text) {
    std::vector<llama_token> tokens(text.size() + 4);
    int n = llama_tokenize(z.vocab, text.data(), static_cast<int32_t>(text.size()), tokens.data(),
                           static_cast<int32_t>(tokens.size()), false, false);
    if (n < 0) {
        tokens.resize(static_cast<size_t>(-n));
        n = llama_tokenize(z.vocab, text.data(), static_cast<int32_t>(text.size()), tokens.data(),
                           static_cast<int32_t>(tokens.size()), false, false);
    }
    tokens.resize(static_cast<size_t>(std::max(n, 0)));
    return tokens;
}

void reset_cache(Zenz &z) {
    llama_memory_clear(llama_get_memory(z.ctx), true);
    z.cached.clear();
}

// tokens を評価し、位置 logits_from 以降の logits を得られる状態にする。
// 戻り値: 位置 i の logits の batch 内の添字が i - offset になる offset。失敗・中断なら -1。
int decode(Zenz &z, const std::vector<llama_token> &tokens, int logits_from) {
    if (tokens.empty() || static_cast<int>(tokens.size()) > z.n_ctx) return -1;
    size_t common = 0;
    while (common < z.cached.size() && common < tokens.size() && z.cached[common] == tokens[common]) common++;
    int keep = std::min<int>(static_cast<int>(common), logits_from);
    keep = std::min<int>(keep, static_cast<int>(tokens.size()) - 1);
    keep = std::max(keep, 0);
    llama_memory_seq_rm(llama_get_memory(z.ctx), 0, keep, -1);

    z.batch.n_tokens = 0;
    for (int i = keep; i < static_cast<int>(tokens.size()); i++) {
        int k = z.batch.n_tokens++;
        z.batch.token[k] = tokens[i];
        z.batch.pos[k] = i;
        z.batch.n_seq_id[k] = 1;
        z.batch.seq_id[k][0] = 0;
        z.batch.logits[k] = i >= logits_from;
    }
    if (z.abort) {
        // KV はもう消したので、覚えている列も捨てる（次の呼び出しで、消した分を使い回そうとしないため）
        reset_cache(z);
        return -1;
    }
    int rc = llama_decode(z.ctx, z.batch);
    if (rc != 0) {
        if (rc != 2) LOGE("llama_decode failed: %d", rc);
        reset_cache(z);
        return -1;
    }
    z.cached = tokens;
    return keep;
}

float log_prob(const float *logits, int n_vocab, llama_token token) {
    float max = logits[0];
    for (int i = 1; i < n_vocab; i++) max = std::max(max, logits[i]);
    double sum = 0;
    for (int i = 0; i < n_vocab; i++) sum += std::exp(static_cast<double>(logits[i] - max));
    return logits[token] - max - static_cast<float>(std::log(sum));
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_dev_mooma_kanade_zenz_ZenzNative_init(JNIEnv *env, jobject, jbyteArray modelPath,
                                                                  jint threads, jint nCtx) {
    llama_log_set(quiet_log, nullptr);
    llama_backend_init();
    std::string path = to_string(env, modelPath);

    auto mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
    if (!model) {
        LOGE("model load failed: %s", path.c_str());
        return 0;
    }
    auto *z = new Zenz();
    z->model = model;
    z->vocab = llama_model_get_vocab(model);
    z->n_ctx = nCtx;

    auto cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(nCtx);
    cparams.n_batch = static_cast<uint32_t>(nCtx);
    cparams.n_ubatch = static_cast<uint32_t>(nCtx);
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;
    cparams.no_perf = true;
    // 候補ごとに別の系列を立て、プロンプトの KV を共有したまま1回の decode でまとめて採点する
    cparams.n_seq_max = kMaxSeq;
    cparams.kv_unified = true;
    cparams.abort_callback = abort_callback;
    cparams.abort_callback_data = z;
    z->ctx = llama_init_from_model(model, cparams);
    if (!z->ctx) {
        LOGE("context init failed");
        llama_model_free(model);
        delete z;
        return 0;
    }
    z->batch = llama_batch_init(nCtx, 0, 1);
    return reinterpret_cast<jlong>(z);
}

JNIEXPORT void JNICALL Java_dev_mooma_kanade_zenz_ZenzNative_free(JNIEnv *, jobject, jlong handle) {
    auto *z = reinterpret_cast<Zenz *>(handle);
    if (!z) return;
    llama_batch_free(z->batch);
    llama_free(z->ctx);
    llama_model_free(z->model);
    delete z;
}

JNIEXPORT void JNICALL Java_dev_mooma_kanade_zenz_ZenzNative_cancel(JNIEnv *, jobject, jlong handle) {
    auto *z = reinterpret_cast<Zenz *>(handle);
    if (z) z->abort = true;
}

// prompt に続けて各候補を出力したときの対数確率の和（文末記号まで含む）。中断されたら null。
// プロンプトは系列 0 に置いて前回と共通の部分を使い回し、候補は系列 1.. にプロンプトの KV を写して並べ、
// まとめて1回の decode で評価する（候補が多い・長いときは数回に分ける）。
JNIEXPORT jfloatArray JNICALL Java_dev_mooma_kanade_zenz_ZenzNative_score(JNIEnv *env, jobject, jlong handle,
                                                                         jbyteArray promptBytes,
                                                                         jobjectArray candidates) {
    auto *z = reinterpret_cast<Zenz *>(handle);
    const int n_vocab = llama_vocab_n_tokens(z->vocab);
    const llama_token eos = llama_vocab_eos(z->vocab);
    z->abort = false;
    std::vector<llama_token> prompt = tokenize(*z, to_string(env, promptBytes));
    if (prompt.empty()) return nullptr;
    const int P = static_cast<int>(prompt.size());

    jsize n = env->GetArrayLength(candidates);
    std::vector<std::vector<llama_token>> cands(static_cast<size_t>(n));
    for (jsize c = 0; c < n; c++) {
        auto bytes = static_cast<jbyteArray>(env->GetObjectArrayElement(candidates, c));
        cands[c] = tokenize(*z, to_string(env, bytes));
        env->DeleteLocalRef(bytes);
        cands[c].push_back(eos);
    }

    // プロンプトの最後の位置の logits（各候補の1トークン目の確率）
    int offset = decode(*z, prompt, P - 1);
    if (offset < 0) return nullptr;
    std::vector<float> first(llama_get_logits_ith(z->ctx, P - 1 - offset),
                             llama_get_logits_ith(z->ctx, P - 1 - offset) + n_vocab);

    std::vector<float> scores(static_cast<size_t>(n), -INFINITY);
    llama_memory_t mem = llama_get_memory(z->ctx);
    int c = 0;
    while (c < n) {
        // この回に載せる候補 [c, end)。系列の数・KV の空き・batch の大きさで区切る
        int end = c;
        int used = 0;
        while (end < n && end - c < kMaxSeq - 1) {
            int extra = static_cast<int>(cands[end].size()) - 1;
            if (P + used + extra > z->n_ctx) break;
            used += extra;
            end++;
        }
        if (end == c) {  // 1件でも収まらない候補は採点しない
            c++;
            continue;
        }
        z->batch.n_tokens = 0;
        for (int i = c; i < end; i++) {
            const int seq = 1 + (i - c);
            llama_memory_seq_rm(mem, seq, -1, -1);
            llama_memory_seq_cp(mem, 0, seq, -1, -1);
            // 最後（文末記号）以外を入れる。位置 P+j の logits が j+1 番目のトークンの確率になる
            for (size_t j = 0; j + 1 < cands[i].size(); j++) {
                int k = z->batch.n_tokens++;
                z->batch.token[k] = cands[i][j];
                z->batch.pos[k] = P + static_cast<int>(j);
                z->batch.n_seq_id[k] = 1;
                z->batch.seq_id[k][0] = seq;
                z->batch.logits[k] = true;
            }
        }
        int rc = z->batch.n_tokens > 0 ? (z->abort ? 2 : llama_decode(z->ctx, z->batch)) : 0;
        if (rc != 0) {
            if (rc != 2) LOGE("llama_decode failed: %d", rc);
            reset_cache(*z);
            return nullptr;
        }
        int k = 0;
        for (int i = c; i < end; i++) {
            float sum = log_prob(first.data(), n_vocab, cands[i][0]);
            for (size_t j = 1; j < cands[i].size(); j++) {
                sum += log_prob(llama_get_logits_ith(z->ctx, k++), n_vocab, cands[i][j]);
            }
            scores[i] = sum;
        }
        for (int seq = 1; seq <= end - c; seq++) llama_memory_seq_rm(mem, seq, -1, -1);
        c = end;
    }
    jfloatArray result = env->NewFloatArray(n);
    env->SetFloatArrayRegion(result, 0, n, scores.data());
    return result;
}

// prompt に続けて candidate を出すとき、各位置でモデルが一番に選ぶトークン（argmax）が candidate と一致するかを見る
// （Zenzai の検証）。戻り値の先頭のバイトが 0 なら最後（文末記号）まで一致した、またはモデルが途中で文を終えたがった。
// 1 なら、ずれた位置までの candidate の表記＋モデルが選んだトークンの UTF-8 が続く。中断・失敗なら null。
// softmax は取らない（argmax は logits のままで決まる）
JNIEXPORT jbyteArray JNICALL Java_dev_mooma_kanade_zenz_ZenzNative_verify(JNIEnv *env, jobject, jlong handle,
                                                                          jbyteArray promptBytes, jbyteArray candidateBytes) {
    auto *z = reinterpret_cast<Zenz *>(handle);
    const int n_vocab = llama_vocab_n_tokens(z->vocab);
    const llama_token eos = llama_vocab_eos(z->vocab);
    z->abort = false;
    std::vector<llama_token> tokens = tokenize(*z, to_string(env, promptBytes));
    if (tokens.empty()) return nullptr;
    const int P = static_cast<int>(tokens.size());
    std::vector<llama_token> cand = tokenize(*z, to_string(env, candidateBytes));
    tokens.insert(tokens.end(), cand.begin(), cand.end());
    // 位置 P-1+j の logits が candidate の j 番目（j == 長さなら文末記号）を当てる
    int offset = decode(*z, tokens, P - 1);
    if (offset < 0) return nullptr;
    std::string out(1, '\0');
    char piece[64];
    for (size_t j = 0; j <= cand.size(); j++) {
        const float *logits = llama_get_logits_ith(z->ctx, P - 1 + static_cast<int>(j) - offset);
        llama_token want = j < cand.size() ? cand[j] : eos;
        // 候補の途中では文末記号を選ばせない（読みがまだ残っているので、文はそこで終われない。
        // 終えたがったときは、文末記号の次に選ぶ字で直す。「また後で寝」→「また後でね」）
        llama_token best = -1;
        for (int t = 0; t < n_vocab; t++) {
            if (t == eos && j < cand.size()) continue;
            if (best < 0 || logits[t] > logits[best]) best = t;
        }
        if (best == want) continue;
        if (best == eos) break;  // 候補が終わった所で、モデルはまだ続けたがった。読みは使い切っているので直さない
        out[0] = 1;
        for (size_t i = 0; i <= j; i++) {
            llama_token t = i < j ? cand[i] : best;
            int len = llama_token_to_piece(z->vocab, t, piece, sizeof(piece), 0, false);
            if (len > 0) out.append(piece, static_cast<size_t>(len));
        }
        break;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

JNIEXPORT void JNICALL Java_dev_mooma_kanade_zenz_ZenzNative_setThreads(JNIEnv *, jobject, jlong handle, jint threads) {
    auto *z = reinterpret_cast<Zenz *>(handle);
    if (z) llama_set_n_threads(z->ctx, threads, threads);
}

// prompt に続けて文末記号まで貪欲に生成した UTF-8 のバイト列。中断されたら null。
JNIEXPORT jbyteArray JNICALL Java_dev_mooma_kanade_zenz_ZenzNative_greedy(JNIEnv *env, jobject, jlong handle,
                                                                         jbyteArray promptBytes, jint maxTokens) {
    auto *z = reinterpret_cast<Zenz *>(handle);
    const int n_vocab = llama_vocab_n_tokens(z->vocab);
    const llama_token eos = llama_vocab_eos(z->vocab);
    z->abort = false;
    std::vector<llama_token> tokens = tokenize(*z, to_string(env, promptBytes));
    if (tokens.empty()) return nullptr;
    size_t prompt_len = tokens.size();

    for (int step = 0; step < maxTokens && static_cast<int>(tokens.size()) < z->n_ctx; step++) {
        int last = static_cast<int>(tokens.size()) - 1;
        int offset = decode(*z, tokens, last);
        if (offset < 0) return nullptr;
        const float *logits = llama_get_logits_ith(z->ctx, last - offset);
        llama_token best = static_cast<llama_token>(std::max_element(logits, logits + n_vocab) - logits);
        if (best == eos) break;
        tokens.push_back(best);
    }
    std::string out;
    char piece[64];
    for (size_t i = prompt_len; i < tokens.size(); i++) {
        int len = llama_token_to_piece(z->vocab, tokens[i], piece, sizeof(piece), 0, false);
        if (len > 0) out.append(piece, static_cast<size_t>(len));
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    return result;
}

}  // extern "C"
