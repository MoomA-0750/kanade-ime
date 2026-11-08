#include "theme.h"

#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QGuiApplication>
#include <QJsonDocument>
#include <QJsonObject>
#include <QProcess>
#include <QRegularExpression>
#include <QStandardPaths>
#include <QStyleHints>
#include <cmath>

Theme::Theme(const QString &stateDir, QObject *parent) : QObject(parent), m_stateDir(stateDir)
{
    if (m_stateDir.isEmpty()) {
        const QString v = qEnvironmentVariable("XDG_STATE_HOME");
        m_stateDir = v.isEmpty() ? QDir::homePath() + "/.local/state" : v;
    }
    // テーマを替えると、ファイルもフォルダーも置き換わって見張りが外れる。変わるたびに少し待って読み直し、付け直す
    m_debounce.setSingleShot(true);
    m_debounce.setInterval(150);
    connect(&m_debounce, &QTimer::timeout, this, &Theme::reload);
    connect(&m_watcher, &QFileSystemWatcher::fileChanged, &m_debounce, qOverload<>(&QTimer::start));
    connect(&m_watcher, &QFileSystemWatcher::directoryChanged, &m_debounce, qOverload<>(&QTimer::start));
    if (auto *hints = QGuiApplication::styleHints())
        connect(hints, &QStyleHints::colorSchemeChanged, this, &Theme::reload);
    reload();
}

QString Theme::colorsFile() const { return m_stateDir + "/omarchy/current/theme/colors.toml"; }

QMap<QString, QString> Theme::parse(const QString &text)
{
    static const QRegularExpression line(QStringLiteral(R"re(^\s*([A-Za-z0-9_]+)\s*=\s*"([^"]*)")re"));
    QMap<QString, QString> out;
    for (const QString &l : text.split('\n')) {
        const auto m = line.match(l);
        if (m.hasMatch()) out[m.captured(1)] = m.captured(2).trimmed();
    }
    return out;
}

int Theme::parseRounding(const QByteArray &json)
{
    const QJsonObject o = QJsonDocument::fromJson(json).object();
    if (!o.contains("int")) return -1;
    return qMax(0, o.value("int").toInt());
}

double Theme::luminance(const QColor &c)
{
    auto ch = [](double v) { return v <= 0.03928 ? v / 12.92 : std::pow((v + 0.055) / 1.055, 2.4); };
    return 0.2126 * ch(c.redF()) + 0.7152 * ch(c.greenF()) + 0.0722 * ch(c.blueF());
}

double Theme::contrast(const QColor &a, const QColor &b)
{
    const double la = luminance(a), lb = luminance(b);
    return la > lb ? (la + 0.05) / (lb + 0.05) : (lb + 0.05) / (la + 0.05);
}

int Theme::readRounding() const
{
    const QString hyprctl = QStandardPaths::findExecutable("hyprctl");
    if (hyprctl.isEmpty()) return -1;
    QProcess p;
    p.start(hyprctl, {"getoption", "decoration:rounding", "-j"});
    if (!p.waitForFinished(1000) || p.exitCode() != 0) return -1;
    return parseRounding(p.readAllStandardOutput());
}

void Theme::reload()
{
    QFile f(colorsFile());
    QMap<QString, QString> colors;
    if (f.open(QIODevice::ReadOnly)) colors = parse(QString::fromUtf8(f.readAll()));
    const bool omarchy = colors.contains("background") && colors.contains("foreground")
                         && QColor(colors["background"]).isValid() && QColor(colors["foreground"]).isValid();
    bool dark;
    if (omarchy) {
        const QString mode = colors.value("mode");
        dark = mode == "dark" || (mode != "light" && luminance(QColor(colors["background"])) < 0.5);
    } else {
        colors.clear();
        auto *hints = QGuiApplication::styleHints();
        dark = hints && hints->colorScheme() == Qt::ColorScheme::Dark;
    }
    int rounding = readRounding();
    // Omarchy の既定は 0（角張る）。hyprctl で読めない（Hyprland の外から開いた）ときもそれに合わせる
    if (rounding < 0 && omarchy) rounding = 0;
    const bool same = colors == m_colors && omarchy == m_omarchy && dark == m_dark && rounding == m_rounding;
    m_colors = colors;
    m_omarchy = omarchy;
    m_dark = dark;
    m_rounding = rounding;
    rewatch();
    if (!same) emit changed();
}

void Theme::rewatch()
{
    if (!m_watcher.files().isEmpty()) m_watcher.removePaths(m_watcher.files());
    if (!m_watcher.directories().isEmpty()) m_watcher.removePaths(m_watcher.directories());
    // ファイル、その入ったフォルダー（theme）、さらに上（current・omarchy）。無いものは、上の方で現れるのを待つ
    const QString file = colorsFile();
    QStringList paths{file, QFileInfo(file).absolutePath(), m_stateDir + "/omarchy/current", m_stateDir + "/omarchy", m_stateDir};
    for (const QString &p : paths)
        if (QFileInfo::exists(p)) m_watcher.addPath(p);
}
