// 音声のモデル（desktop/…/VoiceModels.kt・windows/settings/VoiceModels.cs・kanade-settings-gtk.py と同じ）。落とす・消す
#pragma once

#include <QFile>
#include <QHash>
#include <QNetworkAccessManager>
#include <QObject>
#include <QPointer>
#include <QVariantList>
#include <QVector>

class Store;
class QNetworkReply;

struct VoiceModel {
    QString id, name, ja, en, size, path;
    struct File { QString name, url; qint64 bytes; };
    QVector<File> files;
    bool single = false, japanese = true, english = false;

    QString target(const QString &file) const;
    bool ready() const;
};

class Models : public QObject {
    Q_OBJECT
    Q_PROPERTY(int revision READ revision NOTIFY changed)
public:
    explicit Models(Store *store, QObject *parent = nullptr);

    static const QVector<VoiceModel> &all();
    int revision() const { return m_revision; }

    // language = "ja"（日本語の文を作るモデル）か "en"。[{id, name, size, description, ready, downloading, progress}]
    Q_INVOKABLE QVariantList list(const QString &language) const;
    Q_INVOKABLE double progress(const QString &id) const;
    // 落とす。途中まで落としたもの（.part）があれば続きから。落とし終えたら key の設定をそのモデルにする
    Q_INVOKABLE void download(const QString &id, const QString &key);
    Q_INVOKABLE void stop(const QString &id);
    // 消す（ゴミ箱へ）
    Q_INVOKABLE void remove(const QString &id);

signals:
    void changed();
    void progressChanged(const QString &id);
    void message(const QString &text);

private:
    struct Job {
        const VoiceModel *model = nullptr;
        QString key;
        int index = 0;        // 落としているファイル
        qint64 done = 0;      // 済んだファイルの合計
        qint64 total = 0;
        qint64 written = 0;   // 落としているファイルの .part の大きさ
        bool checked = false; // 続きを頼んだ返事を確かめたか
        bool cancelled = false;
        double progress = 0;
        QPointer<QNetworkReply> reply;
        QFile *out = nullptr;
    };
    void next(const QString &id);
    void finish(const QString &id, bool ok, const QString &error);
    void bump();
    QString t(const QString &ja, const QString &en) const;

    Store *m_store;
    QNetworkAccessManager m_net;
    QHash<QString, Job> m_jobs;
    int m_revision = 0;
};
