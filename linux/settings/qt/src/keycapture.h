// 次に押したキーの名前を取る（fcitx5 のキーの名前。X の keysym の名前と同じ。GTK 版の capture_key と同じ決め方）
#pragma once

#include <QObject>
#include <QString>

class QKeyEvent;

class KeyCapture : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool active READ active NOTIFY activeChanged)
public:
    using QObject::QObject;

    bool active() const { return m_active; }
    // combo なら Ctrl などと組み合わせたキー（Control+Alt+space など）、でなければキー1つ
    Q_INVOKABLE void start(bool combo);
    Q_INVOKABLE void stop();

    // 押したキーから名前を作る。取らないキー（組み合わせで修飾キーだけ、修飾なし）は空、Esc は "Escape"
    static QString nameOf(const QKeyEvent *event, bool combo);
    static QString keysymName(quint32 keysym);

    // キーの名前を画面に出す形に（Alt_R → 右 Alt。組み合わせは + でつなぐ）
    Q_INVOKABLE static QString label(const QString &name, bool english);

signals:
    void captured(const QString &name);
    void cancelled();
    void activeChanged();

protected:
    bool eventFilter(QObject *watched, QEvent *event) override;

private:
    bool m_active = false;
    bool m_combo = false;
};
