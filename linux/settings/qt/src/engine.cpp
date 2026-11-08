#include "engine.h"

#include "store.h"

#include <QCoreApplication>
#include <QFileInfo>
#include <QMetaObject>
#include <QPointer>
#include <QProcess>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <thread>

namespace {
QString tr2(bool english, const char *ja, const char *en) { return QString::fromUtf8(english ? en : ja); }
}

QString Engine::escape(const QString &s)
{
    QString out = s;
    out.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    return out;
}

QString Engine::unescape(const QString &s)
{
    QString out;
    for (qsizetype i = 0; i < s.size(); ++i) {
        if (s[i] == '\\' && i + 1 < s.size()) {
            ++i;
            const QChar c = s[i];
            out += c == 't' ? QChar('\t') : c == 'n' ? QChar('\n') : c == 'r' ? QChar('\r') : c;
        } else {
            out += s[i];
        }
    }
    return out;
}

QPair<bool, QString> Engine::send(const QString &socketPath, const QString &command, const QString &argument, bool english)
{
    const QPair<bool, QString> notRunning{false, tr2(english, "Kanade のエンジンが動いていません（Kanade で文字を1つ打つと起動します）",
                                                     "The Kanade engine is not running (typing one character with Kanade starts it)")};
    const int fd = ::socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return notRunning;
    struct Closer { int fd; ~Closer() { ::close(fd); } } closer{fd};
    timeval tv{20, 0};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);
    sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    const QByteArray path = socketPath.toLocal8Bit();
    if (path.size() >= qsizetype(sizeof addr.sun_path)) return notRunning;
    std::memcpy(addr.sun_path, path.constData(), path.size());
    if (::connect(fd, reinterpret_cast<sockaddr *>(&addr), sizeof addr) != 0) return notRunning;
    const QByteArray line = ("admin\t1\t" + escape(command) + "\t" + escape(argument) + "\n").toUtf8();
    if (::send(fd, line.constData(), line.size(), MSG_NOSIGNAL) != line.size()) return notRunning;
    QByteArray buf;
    char chunk[65536];
    for (;;) {
        const ssize_t n = ::recv(fd, chunk, sizeof chunk, 0);
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK))
            return {false, tr2(english, "エンジンから返事がありません", "The engine did not respond")};
        if (n <= 0) return {false, tr2(english, "エンジンとの接続が切れました", "Lost the connection to the engine")};
        buf.append(chunk, n);
        qsizetype nl;
        while ((nl = buf.indexOf('\n')) >= 0) {
            const QStringList f = QString::fromUtf8(buf.left(nl)).split('\t');
            buf.remove(0, nl + 1);
            if (f.size() >= 3 && f[0] == "admin") return {f[2] == "1", f.size() > 3 ? unescape(f[3]) : QString()};
        }
    }
}

void Engine::request(const QString &command, const QString &argument)
{
    QPointer<Engine> self(this);
    const bool english = m_english;
    std::thread([self, command, argument, english] {
        auto result = send(paths::socketPath(), command, argument, english);
        // エンジンが動いていなければ、エンジンのコマンドで読む（動き出したら読み直す）
        if (command == "import-android" && !result.first && !QFileInfo::exists(paths::socketPath())
            && QFileInfo(paths::engine()).isExecutable()) {
            QProcess p;
            p.start(paths::engine(), {"import-android", argument});
            p.waitForFinished(-1);
            QString out = QString::fromUtf8(p.readAllStandardOutput());
            if (out.isEmpty()) out = QString::fromUtf8(p.readAllStandardError());
            result = {p.exitStatus() == QProcess::NormalExit && p.exitCode() == 0, out.trimmed()};
        }
        QMetaObject::invokeMethod(qApp, [self, result] {
            if (self) emit self->finished(result.first, result.second);
        }, Qt::QueuedConnection);
    }).detach();
}
