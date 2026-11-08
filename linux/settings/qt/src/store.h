// 設定・ユーザー辞書・スニペットのファイル（書式の正本は desktop/…/DesktopStore.kt。GTK 版の kanade-settings-gtk.py と同じ読み書きをする）
#pragma once

#include <QObject>
#include <QString>
#include <QStringList>
#include <QVariantList>

namespace paths {
QString configDir();   // $XDG_CONFIG_HOME/kanade
QString installDir();  // $XDG_DATA_HOME/kanade
QString dataDir();     // $KANADE_DATA か installDir/data
QString settingsFile();
QString dictionaryFile();
QString snippetsFile();
QString logFile();     // $XDG_STATE_HOME/kanade/engine.log
QString socketPath();  // $KANADE_SOCKET か $XDG_RUNTIME_DIR/kanade-engine.sock
QString engine();      // installDir/bin/kanade-engine
}

// 改行で分ける（Python の splitlines と同じく \r\n・\r・\n で。末尾の改行は行を作らない）
QStringList splitLines(const QString &text);
QString readText(const QString &path);
bool writeText(const QString &path, const QString &text);  // 一時ファイルに書いてから置き換える

class Store : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool english READ english NOTIFY languageChanged)
    Q_PROPERTY(int revision READ revision NOTIFY changed)
    Q_PROPERTY(QString configDir READ configDir CONSTANT)
    Q_PROPERTY(QString logFile READ logFile CONSTANT)
public:
    explicit Store(QObject *parent = nullptr);

    bool english() const { return m_english; }
    int revision() const { return m_revision; }
    QString configDir() const { return paths::configDir(); }
    QString logFile() const { return paths::logFile(); }

    // 設定の値（ファイルになければ既定）
    Q_INVOKABLE QString get(const QString &key) const;
    // 1つの項目だけを書き換える（ほかの行と説明はそのまま。なければ説明と一緒に末尾に足す）
    Q_INVOKABLE void set(const QString &key, const QString &value);

    // ユーザー辞書（kind = "dictionary"）とスニペット（"snippets"）
    Q_INVOKABLE QString text(const QString &kind) const;
    Q_INVOKABLE void setText(const QString &kind, const QString &text);
    // [{line, key, value}]。書式は UserDictionary.parsePairs と同じ。query があれば、キーか値に含むものだけ
    Q_INVOKABLE QVariantList entries(const QString &kind, const QString &query = QString()) const;
    // line が -1 なら足す。remove なら消す
    Q_INVOKABLE void writeEntry(const QString &kind, int line, const QString &key, const QString &value, bool remove = false);

    // 表示の言語（ui_language と LANG）を決め直す
    void updateLanguage();

    static QString defaultValue(const QString &key);
    static QMap<QString, QString> load();  // 既定に、ファイルの値を重ねたもの

signals:
    void changed();
    void languageChanged();

private:
    QString fileOf(const QString &kind) const;
    bool m_english = false;
    int m_revision = 0;
};
