#include "store.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QMap>
#include <QSaveFile>

namespace {

QString env(const char *name) { return qEnvironmentVariable(name); }

QString xdg(const char *name, const char *fallback)
{
    const QString v = env(name);
    return v.isEmpty() ? QDir::homePath() + "/" + fallback : v;
}

const QMap<QString, QString> &defaults()
{
    static const QMap<QString, QString> d{
        {"full_width_symbols", "true"},
        {"live_conversion", "true"},
        {"punctuation", "、。"},
        {"pair_brackets", "true"},
        {"spoken_punctuation", "true"},
        {"voice_final", "parakeet"},
        {"learning", "true"},
        {"ui_language", "auto"},
        {"voice_final_en", "parakeet-en"},
        {"voice_hold_keys", "F12,Alt_R"},
        {"voice_toggle_key", "Control+Alt+space"},
    };
    return d;
}

// ファイルにないときに足す説明（エンジンが書くものと同じ）
const QMap<QString, QString> &comments()
{
    static const QMap<QString, QString> c{
        {"voice_final_en", "# Linux 版: 英語の口述のモデル（Kanade 以外の入力方式で録音キーを押したとき。parakeet-en・whisper-small・whisper-turbo）"},
        {"voice_hold_keys", "# Linux 版: どの入力方式でも、押している間だけ録音するキー（fcitx5 のキーの名前をカンマ区切り。例 F12,Alt_R,F9）"},
        {"voice_toggle_key", "# Linux 版: 押すたびに録音を始める・終えるキー（例 Control+Alt+space。空なら使わない）。録音中の Esc で取り消す"},
    };
    return c;
}

bool isBreak(QChar c)
{
    switch (c.unicode()) {
    case '\n': case '\r': case 0x0b: case 0x0c: case 0x1c: case 0x1d: case 0x1e: case 0x85: case 0x2028: case 0x2029:
        return true;
    default:
        return false;
    }
}

}  // namespace

namespace paths {
QString configDir() { return xdg("XDG_CONFIG_HOME", ".config") + "/kanade"; }
QString installDir() { return xdg("XDG_DATA_HOME", ".local/share") + "/kanade"; }
QString dataDir()
{
    const QString v = env("KANADE_DATA");
    return v.isEmpty() ? installDir() + "/data" : v;
}
QString settingsFile() { return configDir() + "/settings.properties"; }
QString dictionaryFile() { return configDir() + "/user_dictionary.txt"; }
QString snippetsFile() { return configDir() + "/snippets.txt"; }
QString logFile() { return xdg("XDG_STATE_HOME", ".local/state") + "/kanade/engine.log"; }
QString socketPath()
{
    const QString v = env("KANADE_SOCKET");
    if (!v.isEmpty()) return v;
    const QString run = env("XDG_RUNTIME_DIR");
    return (run.isEmpty() ? QStringLiteral("/tmp") : run) + "/kanade-engine.sock";
}
QString engine() { return installDir() + "/bin/kanade-engine"; }
}  // namespace paths

QStringList splitLines(const QString &text)
{
    QStringList out;
    qsizetype start = 0;
    for (qsizetype i = 0; i < text.size(); ++i) {
        if (!isBreak(text[i])) continue;
        out << text.mid(start, i - start);
        if (text[i] == '\r' && i + 1 < text.size() && text[i + 1] == '\n') ++i;
        start = i + 1;
    }
    if (start < text.size()) out << text.mid(start);
    return out;
}

QString readText(const QString &path)
{
    QFile f(path);
    if (!f.open(QIODevice::ReadOnly)) return {};
    return QString::fromUtf8(f.readAll());
}

bool writeText(const QString &path, const QString &text)
{
    QDir().mkpath(QFileInfo(path).absolutePath());
    QSaveFile f(path);
    if (!f.open(QIODevice::WriteOnly)) return false;
    f.write(text.toUtf8());
    return f.commit();
}

Store::Store(QObject *parent) : QObject(parent) { updateLanguage(); }

QString Store::defaultValue(const QString &key) { return defaults().value(key); }

QMap<QString, QString> Store::load()
{
    QMap<QString, QString> values = defaults();
    for (const QString &raw : splitLines(readText(paths::settingsFile()))) {
        const QString line = raw.trimmed();
        if (line.isEmpty() || line.startsWith('#') || !line.contains('=')) continue;
        const qsizetype eq = line.indexOf('=');
        values[line.left(eq).trimmed()] = line.mid(eq + 1).trimmed();
    }
    return values;
}

QString Store::get(const QString &key) const { return load().value(key); }

void Store::set(const QString &key, const QString &value)
{
    QDir().mkpath(paths::configDir());
    QStringList lines;
    if (QFileInfo::exists(paths::settingsFile()))
        lines = splitLines(readText(paths::settingsFile()));
    else
        lines << QStringLiteral("# Kanade の設定。保存するとすぐ反映されます");
    bool found = false;
    for (QString &raw : lines) {
        const QString s = raw.trimmed();
        if (!s.startsWith('#') && s.contains('=') && s.left(s.indexOf('=')).trimmed() == key) {
            raw = key + "=" + value;
            found = true;
            break;
        }
    }
    if (!found) {
        if (comments().contains(key)) lines << comments().value(key);
        lines << key + "=" + value;
    }
    writeText(paths::settingsFile(), lines.join('\n') + "\n");
    if (key == "ui_language") updateLanguage();
    ++m_revision;
    emit changed();
}

void Store::updateLanguage()
{
    const QString setting = get("ui_language");
    bool english;
    if (setting == "auto") {
        QString lang = env("LC_ALL");
        if (lang.isEmpty()) lang = env("LC_MESSAGES");
        if (lang.isEmpty()) lang = env("LANG");
        english = !lang.startsWith("ja");
    } else {
        english = setting == "en";
    }
    if (english != m_english) {
        m_english = english;
        emit languageChanged();
    }
}

QString Store::fileOf(const QString &kind) const
{
    return kind == "snippets" ? paths::snippetsFile() : paths::dictionaryFile();
}

QString Store::text(const QString &kind) const { return readText(fileOf(kind)); }

void Store::setText(const QString &kind, const QString &text)
{
    writeText(fileOf(kind), text.endsWith('\n') ? text : text + "\n");
    ++m_revision;
    emit changed();
}

QVariantList Store::entries(const QString &kind, const QString &query) const
{
    const QString q = query.trimmed();
    QVariantList out;
    const QStringList lines = splitLines(text(kind));
    for (int i = 0; i < lines.size(); ++i) {
        const QString s = lines[i].trimmed();
        if (s.isEmpty() || s.startsWith('#')) continue;
        qsizetype cut = -1;
        for (qsizetype j = 0; j < s.size(); ++j) {
            if (s[j] == ' ' || s[j] == '\t') { cut = j; break; }
        }
        if (cut <= 0) continue;
        // Python の lstrip() と同じく、頭の空白を全部落とす
        QString value = s.mid(cut + 1);
        qsizetype k = 0;
        while (k < value.size() && value[k].isSpace()) ++k;
        value = value.mid(k);
        if (value.isEmpty()) continue;
        const QString key = s.left(cut);
        if (!q.isEmpty() && !key.contains(q) && !value.contains(q)) continue;
        out << QVariantMap{{"line", i}, {"key", key}, {"value", value}};
    }
    return out;
}

void Store::writeEntry(const QString &kind, int line, const QString &key, const QString &value, bool remove)
{
    QStringList lines = splitLines(text(kind));
    const QString newLine = key.trimmed() + " " + value.trimmed();
    if (line < 0)
        lines << newLine;
    else if (line < lines.size()) {
        if (remove) lines.removeAt(line);
        else lines[line] = newLine;
    }
    writeText(fileOf(kind), lines.join('\n') + "\n");
    ++m_revision;
    emit changed();
}
