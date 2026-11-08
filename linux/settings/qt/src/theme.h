// Omarchy のテーマ（$XDG_STATE_HOME/omarchy/current/theme/colors.toml）の色と、Hyprland の角の丸み。
// 色の対応は omarchy-gtk-tint と同じ（Vault の「LinuxアプリのQML版の画面の決まり」）。ファイルを見張り、テーマを替えるとその場で変わる
#pragma once

#include <QColor>
#include <QFileSystemWatcher>
#include <QMap>
#include <QObject>
#include <QTimer>
#include <initializer_list>

class Theme : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool omarchy READ omarchy NOTIFY changed)  // colors.toml を読めたか
    Q_PROPERTY(bool dark READ dark NOTIFY changed)
    Q_PROPERTY(QColor accent READ accent NOTIFY changed)
    Q_PROPERTY(QColor background READ background NOTIFY changed)        // 窓の地
    Q_PROPERTY(QColor view READ view NOTIFY changed)                    // 一覧・入力欄・横の欄
    Q_PROPERTY(QColor raised READ raised NOTIFY changed)                // 見出しの帯・カード・ダイアログ
    Q_PROPERTY(QColor foreground READ foreground NOTIFY changed)
    Q_PROPERTY(QColor border READ border NOTIFY changed)
    Q_PROPERTY(QColor red READ red NOTIFY changed)
    Q_PROPERTY(QColor green READ green NOTIFY changed)
    Q_PROPERTY(QColor yellow READ yellow NOTIFY changed)
    Q_PROPERTY(QColor textOnAccent READ textOnAccent NOTIFY changed)            // アクセントの上の文字
    Q_PROPERTY(QColor textOnRed READ textOnRed NOTIFY changed)
    Q_PROPERTY(int rounding READ rounding NOTIFY changed)               // 角の丸み（px）。Omarchy が無く分からなければ -1
public:
    // stateDir が空なら $XDG_STATE_HOME（無ければ ~/.local/state）
    explicit Theme(const QString &stateDir = QString(), QObject *parent = nullptr);

    QString colorsFile() const;
    bool omarchy() const { return m_omarchy; }
    bool dark() const { return m_dark; }
    QColor accent() const { return pick({"accent", "blue"}); }
    QColor background() const { return color("background"); }
    QColor view() const { return pick({"dark_background", "background"}); }
    QColor raised() const { return pick({"lighter_background", "background"}); }
    QColor foreground() const { return color("foreground"); }
    QColor border() const { return pick({"muted", "lighter_background", "background"}); }
    QColor red() const { return pick({"red", "accent", "blue"}); }
    QColor green() const { return pick({"green", "accent", "blue"}); }
    QColor yellow() const { return pick({"yellow", "accent", "blue"}); }
    QColor textOnAccent() const { return on(accent()); }
    QColor textOnRed() const { return on(red()); }
    int rounding() const { return m_rounding; }

    // colors.toml の「key = "値"」を読む（表や配列は使っていない）
    static QMap<QString, QString> parse(const QString &text);
    // hyprctl getoption decoration:rounding -j の出力から丸みを読む（読めなければ -1）
    static int parseRounding(const QByteArray &json);
    // WCAG の相対輝度と、2色の差
    static double luminance(const QColor &c);
    static double contrast(const QColor &a, const QColor &b);

public slots:
    void reload();

signals:
    void changed();

private:
    QColor color(const QString &key) const { return QColor(m_colors.value(key)); }
    // 最初にあるもの（無ければ窓の地）
    QColor pick(std::initializer_list<const char *> keys) const
    {
        for (const char *k : keys)
            if (m_colors.contains(k)) return color(k);
        return background();
    }
    // 色の上に載せる文字は、テーマの背景色と文字色のうち差が大きい方
    QColor on(const QColor &bg) const
    {
        return contrast(bg, background()) >= contrast(bg, foreground()) ? background() : foreground();
    }
    void rewatch();
    int readRounding() const;

    QString m_stateDir;
    QMap<QString, QString> m_colors;
    bool m_omarchy = false;
    bool m_dark = false;
    int m_rounding = -1;
    QFileSystemWatcher m_watcher;
    QTimer m_debounce;
};
