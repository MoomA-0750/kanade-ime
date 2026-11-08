// エンジンへの頼みごと（docs/WINDOWS-PROTOCOL.md の admin）。裏のスレッドで送り、終わったら finished を画面のスレッドで出す
#pragma once

#include <QObject>
#include <QPair>
#include <QString>

class Engine : public QObject {
    Q_OBJECT
public:
    using QObject::QObject;

    // import-android・export-android・clear-learning
    Q_INVOKABLE void request(const QString &command, const QString &argument = QString());
    // 返事は「admin 番号 成功(1/0) 文」。（成功したか, 出す文）
    static QPair<bool, QString> send(const QString &socketPath, const QString &command, const QString &argument, bool english);
    static QString escape(const QString &s);
    static QString unescape(const QString &s);

    void setEnglish(bool english) { m_english = english; }

signals:
    void finished(bool ok, const QString &message);

private:
    bool m_english = false;
};
