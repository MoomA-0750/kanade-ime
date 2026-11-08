#include "models.h"

#include "store.h"

#include <QDir>
#include <QFileInfo>
#include <QNetworkReply>
#include <QNetworkRequest>

namespace {

// 試験のときだけ、落とす先を手元のサーバーに替える（KANADE_MODEL_URL_BASE=http://127.0.0.1:8000/）
QString hf()
{
    const QString v = qEnvironmentVariable("KANADE_MODEL_URL_BASE");
    return v.isEmpty() ? QStringLiteral("https://huggingface.co/") : v;
}

VoiceModel make(const QString &id, const QString &name, const QString &ja, const QString &en, const QString &size, const QString &path,
                const QString &base, const QVector<QPair<QString, qint64>> &files, bool single = false, bool japanese = true,
                bool english = false)
{
    VoiceModel m{id, name, ja, en, size, path, {}, single, japanese, english};
    for (const auto &[n, b] : files) m.files.push_back({n, single ? base : base + n, b});
    return m;
}

}  // namespace

QString VoiceModel::target(const QString &file) const
{
    return single ? paths::dataDir() + "/" + path : paths::dataDir() + "/" + path + "/" + file;
}

bool VoiceModel::ready() const
{
    for (const File &f : files) {
        const QFileInfo info(target(f.name));
        if (!info.isFile() || info.size() != f.bytes) return false;
    }
    return true;
}

const QVector<VoiceModel> &Models::all()
{
    static const QVector<VoiceModel> models = [] {
        const QString HF = hf();
        return QVector<VoiceModel>{
            make("parakeet", "parakeet-ja", "日本語・高い精度・速い・句読点あり", "Japanese, high accuracy, fast, punctuation", "656MB", "parakeet-ja",
                 HF + "csukuangfj/sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8/resolve/bef18eb066808c90bd0f5df5be685767b0732de8/",
                 {{"model.int8.onnx", 655'542'604}, {"tokens.txt", 28'557}}),
            make("reazon", "ReazonSpeech", "日本語・高い精度・とても速い・句読点なし。話している途中の文字にも使います",
                 "Japanese, high accuracy, very fast, no punctuation. Also used for the text shown while you speak", "73MB", "reazonspeech",
                 HF + "csukuangfj/reazonspeech-k2-v2-ja-en/resolve/main/",
                 {{"encoder-epoch-35-avg-1.int8.onnx", 70'876'409}, {"decoder-epoch-35-avg-1.int8.onnx", 1'308'690},
                  {"joiner-epoch-35-avg-1.int8.onnx", 1'033'417}, {"tokens.txt", 26'631}}),
            make("sensevoice", "SenseVoice", "中国語・英語・日本語・韓国語・精度はふつう・速い・句読点あり",
                 "Chinese, English, Japanese, Korean, medium accuracy, fast, punctuation", "239MB", "sensevoice-2024-07-17",
                 HF + "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07/",
                 {{"model.int8.onnx", 239'233'841}, {"tokens.txt", 315'894}}),
            make("whisper-small", "whisper small", "多言語・精度は低め・遅い・句読点あり", "Multilingual, lower accuracy, slow, punctuation", "190MB",
                 "ggml-small-q5_1.bin", HF + "ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin", {{"ggml-small-q5_1.bin", 190'085'487}},
                 true, true, true),
            make("whisper-kotoba", "kotoba-whisper v2.0", "日本語・高い精度・とても遅い・句読点は少なめ",
                 "Japanese, high accuracy, very slow, little punctuation", "538MB", "ggml-kotoba-whisper-v2.0-q5_0.bin",
                 HF + "kotoba-tech/kotoba-whisper-v2.0-ggml/resolve/main/ggml-kotoba-whisper-v2.0-q5_0.bin",
                 {{"ggml-kotoba-whisper-v2.0-q5_0.bin", 537'819'875}}, true),
            make("whisper-turbo", "whisper large-v3-turbo", "多言語・高い精度・とても遅い・句読点あり", "Multilingual, high accuracy, very slow, punctuation",
                 "574MB", "ggml-large-v3-turbo-q5_0.bin", HF + "ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
                 {{"ggml-large-v3-turbo-q5_0.bin", 574'041'195}}, true, true, true),
            make("parakeet-en", "parakeet-tdt v3", "英語とヨーロッパの 25 の言語・高い精度・速い・句読点と大文字あり",
                 "English and 24 other European languages, high accuracy, fast, punctuation and capitals", "670MB", "parakeet-v3",
                 HF + "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/ed5d745623472f97cfc2e6c2916ee248798fc5a8/",
                 {{"encoder.int8.onnx", 652'282'294}, {"decoder.int8.onnx", 11'845'274}, {"joiner.int8.onnx", 6'355'277}, {"tokens.txt", 93'939}},
                 false, false, true),
        };
    }();
    return models;
}

Models::Models(Store *store, QObject *parent) : QObject(parent), m_store(store)
{
    m_net.setTransferTimeout(60000);
}

QString Models::t(const QString &ja, const QString &en) const { return m_store->english() ? en : ja; }

void Models::bump()
{
    ++m_revision;
    emit changed();
}

QVariantList Models::list(const QString &language) const
{
    QVariantList out;
    for (const VoiceModel &m : all()) {
        if (language == "en" ? !m.english : !m.japanese) continue;
        out << QVariantMap{{"id", m.id}, {"name", m.name}, {"size", m.size}, {"description", t(m.ja, m.en)},
                           {"ready", m.ready()}, {"downloading", m_jobs.contains(m.id)}};
    }
    return out;
}

double Models::progress(const QString &id) const { return m_jobs.contains(id) ? m_jobs[id].progress : 0; }

void Models::download(const QString &id, const QString &key)
{
    if (m_jobs.contains(id)) return;
    for (const VoiceModel &m : all()) {
        if (m.id != id) continue;
        Job job;
        job.model = &m;
        job.key = key;
        for (const auto &f : m.files) job.total += f.bytes;
        m_jobs.insert(id, job);
        bump();
        next(id);
        return;
    }
}

void Models::stop(const QString &id)
{
    if (!m_jobs.contains(id)) return;
    Job &job = m_jobs[id];
    job.cancelled = true;
    if (job.reply) job.reply->abort();
}

void Models::next(const QString &id)
{
    Job &job = m_jobs[id];
    const VoiceModel &m = *job.model;
    // 済んでいるファイルは飛ばす
    while (job.index < m.files.size()) {
        const auto &f = m.files[job.index];
        const QFileInfo info(m.target(f.name));
        if (!(info.isFile() && info.size() == f.bytes)) break;
        job.done += f.bytes;
        ++job.index;
    }
    if (job.index >= m.files.size()) {
        finish(id, true, {});
        return;
    }
    const auto &f = m.files[job.index];
    const QString target = m.target(f.name);
    QDir().mkpath(QFileInfo(target).absolutePath());
    const QString part = target + ".part";
    const qint64 have = QFileInfo(part).isFile() ? QFileInfo(part).size() : 0;
    QNetworkRequest request{QUrl(f.url)};
    request.setHeader(QNetworkRequest::UserAgentHeader, "kanade-settings");
    if (have) request.setRawHeader("Range", "bytes=" + QByteArray::number(have) + "-");
    job.out = new QFile(part, this);
    job.written = have;
    job.checked = false;
    QNetworkReply *reply = m_net.get(request);
    job.reply = reply;

    connect(reply, &QNetworkReply::readyRead, this, [this, id, reply, have] {
        if (!m_jobs.contains(id)) return;
        Job &job = m_jobs[id];
        if (!job.checked) {
            job.checked = true;
            // 続きを頼んでも最初から返すサーバーなら、最初から書き直す
            const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
            // 失敗の返事（404 など）の中身は書かない（finished で失敗として出す）
            if (status != 200 && status != 206) {
                reply->readAll();
                return;
            }
            const bool resume = have && status == 206;
            if (!resume) job.written = 0;
            if (!job.out->open(resume ? QIODevice::Append : QIODevice::WriteOnly | QIODevice::Truncate)) {
                job.cancelled = false;
                reply->abort();
                return;
            }
        }
        const QByteArray data = reply->readAll();
        if (!job.out->isOpen()) return;
        job.out->write(data);
        job.written += data.size();
        job.progress = job.total ? double(job.done + job.written) / job.total : 0;
        emit progressChanged(id);
    });
    connect(reply, &QNetworkReply::finished, this, [this, id, reply] {
        reply->deleteLater();
        if (!m_jobs.contains(id)) return;
        Job &job = m_jobs[id];
        const VoiceModel &m = *job.model;
        const auto &f = m.files[job.index];
        if (job.out->isOpen()) job.out->close();
        job.out->deleteLater();
        job.out = nullptr;
        if (job.cancelled) {
            finish(id, false, {});
            return;
        }
        if (reply->error() != QNetworkReply::NoError) {
            finish(id, false, reply->errorString());
            return;
        }
        const QString target = m.target(f.name);
        const QString part = target + ".part";
        if (QFileInfo(part).size() != f.bytes) {
            finish(id, false, t(f.name + " の大きさが合いません", f.name + " has the wrong size"));
            return;
        }
        QFile::remove(target);
        QFile::rename(part, target);
        job.done += f.bytes;
        job.written = 0;
        ++job.index;
        next(id);
    });
}

void Models::finish(const QString &id, bool ok, const QString &error)
{
    const Job job = m_jobs.take(id);
    if (ok) {
        // 落としたら、それを使う
        m_store->set(job.key, id);
        emit message(t(job.model->name + " を落としました", "Downloaded " + job.model->name));
    } else if (!error.isEmpty()) {
        emit message(t("落とせませんでした: " + error, "Download failed: " + error));
    }
    bump();
}

void Models::remove(const QString &id)
{
    for (const VoiceModel &m : all()) {
        if (m.id != id) continue;
        const QString path = paths::dataDir() + "/" + m.path;
        if (QFileInfo::exists(path)) QFile::moveToTrash(path);
        emit message(t(m.name + " をゴミ箱へ移しました", "Moved " + m.name + " to the trash"));
        bump();
        return;
    }
}
