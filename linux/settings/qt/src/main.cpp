// Kanade（Linux 版）の設定の画面の QML 版（C++・Qt Quick・Material）。GTK 版（kanade-settings-gtk.py）と同じ中身を持つ。
// Omarchy のテーマの色と角の丸みに合わせる（theme.h）。
//
//   kanade-settings-qt [input|voice|dictionary|snippets|backup|usage]   開くページ
//
// 2つ目を開くと、開いている窓にページを渡して終わる（GTK 版の GApplication と同じ）。
// 試験用: --screenshot <出力の頭> [--size WxH] [--shots 撮るものをカンマ区切り]  QQuickWindow::grabWindow で PNG にして終わる
#include "engine.h"
#include "keycapture.h"
#include "models.h"
#include "store.h"
#include "theme.h"

#include <QDate>
#include <QDir>
#include <QFontDatabase>
#include <QGuiApplication>
#include <QIcon>
#include <QImage>
#include <QLocalServer>
#include <QLocalSocket>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QQuickStyle>
#include <QQuickWindow>
#include <QTimer>
#include <QDesktopServices>
#include <QFileInfo>
#include <QProcess>
#include <QStandardPaths>
#include <QUrl>

namespace {

const QStringList PAGES{"input", "voice", "dictionary", "snippets", "backup", "usage"};

QString pageOf(const QString &arg) { return PAGES.contains(arg) ? arg : QStringLiteral("input"); }

QString instanceSocket()
{
    QString run = qEnvironmentVariable("XDG_RUNTIME_DIR");
    if (run.isEmpty()) run = QDir::tempPath();
    return run + "/kanade-settings-qt.sock";
}

// ファイルを開く・プログラムを起こすなど、QML から使う小物
class Desktop : public QObject {
    Q_OBJECT
public:
    using QObject::QObject;
    Q_INVOKABLE bool exists(const QString &path) const { return QFileInfo::exists(path); }
    Q_INVOKABLE void open(const QString &path) const { QDesktopServices::openUrl(QUrl::fromLocalFile(path)); }
    Q_INVOKABLE bool hasProgram(const QString &name) const { return !QStandardPaths::findExecutable(name).isEmpty(); }
    Q_INVOKABLE void launch(const QString &name) const { QProcess::startDetached(name, {}); }
    Q_INVOKABLE QString downloadsDir() const
    {
        return QUrl::fromLocalFile(QStandardPaths::writableLocation(QStandardPaths::DownloadLocation)).toString();
    }
    Q_INVOKABLE QString localPath(const QUrl &url) const { return url.toLocalFile(); }
    Q_INVOKABLE QString today() const { return QDate::currentDate().toString("yyyyMMdd"); }
};

}  // namespace

int main(int argc, char *argv[])
{
    QGuiApplication::setApplicationName("kanade-settings");
    QGuiApplication::setOrganizationName("Kanade");
    QGuiApplication::setDesktopFileName("dev.mooma.kanade.Settings");
    QGuiApplication app(argc, argv);
    QGuiApplication::setWindowIcon(QIcon::fromTheme("input-keyboard"));

    QString page = "input", shotPrefix, shots;
    QSize shotSize;
    const QStringList args = app.arguments().mid(1);
    for (int i = 0; i < args.size(); ++i) {
        if (args[i] == "--screenshot" && i + 1 < args.size()) shotPrefix = args[++i];
        else if (args[i] == "--size" && i + 1 < args.size()) {
            const QStringList wh = args[++i].split('x');
            if (wh.size() == 2) shotSize = QSize(wh[0].toInt(), wh[1].toInt());
        } else if (args[i] == "--shots" && i + 1 < args.size()) shots = args[++i];
        else if (!args[i].startsWith("--")) page = args[i];
    }
    page = pageOf(page);

    // 開いている窓があれば、そちらにページを渡す
    QLocalServer server;
    if (shotPrefix.isEmpty()) {
        QLocalSocket probe;
        probe.connectToServer(instanceSocket());
        if (probe.waitForConnected(500)) {
            probe.write(page.toUtf8() + "\n");
            probe.waitForBytesWritten(1000);
            probe.disconnectFromServer();
            return 0;
        }
        QLocalServer::removeServer(instanceSocket());
        server.listen(instanceSocket());
    }

    // 日本語の字は日本の字形で（ほかの言語の CJK の字体が先に選ばれないように）
    if (QFontDatabase::families().contains("Noto Sans CJK JP")) {
        QFont font = QGuiApplication::font();
        QStringList families = font.families();
        if (families.isEmpty()) families << font.family();
        families << "Noto Sans CJK JP";
        font.setFamilies(families);
        QGuiApplication::setFont(font);
    }

    QQuickStyle::setStyle("Material");
    Store store;
    Theme theme;
    Engine engine;
    engine.setEnglish(store.english());
    QObject::connect(&store, &Store::languageChanged, [&] { engine.setEnglish(store.english()); });
    Models models(&store);
    KeyCapture keys;
    Desktop desktop;
    qmlRegisterSingletonInstance("Kanade.Backend", 1, 0, "Store", &store);
    qmlRegisterSingletonInstance("Kanade.Backend", 1, 0, "Theme", &theme);
    qmlRegisterSingletonInstance("Kanade.Backend", 1, 0, "Engine", &engine);
    qmlRegisterSingletonInstance("Kanade.Backend", 1, 0, "Models", &models);
    qmlRegisterSingletonInstance("Kanade.Backend", 1, 0, "KeyGrab", &keys);
    qmlRegisterSingletonInstance("Kanade.Backend", 1, 0, "Desktop", &desktop);

    QQmlApplicationEngine qml;
    qml.setInitialProperties({{"startPage", page}});
    qml.loadFromModule("Kanade", "Main");
    if (qml.rootObjects().isEmpty()) return 1;
    auto *window = qobject_cast<QQuickWindow *>(qml.rootObjects().first());

    QObject::connect(&server, &QLocalServer::newConnection, [&] {
        while (QLocalSocket *s = server.nextPendingConnection()) {
            QObject::connect(s, &QLocalSocket::readyRead, s, [s, window] {
                const QString p = pageOf(QString::fromUtf8(s->readAll()).trimmed());
                qInfo("ほかから開くページ: %s", qPrintable(p));
                QMetaObject::invokeMethod(window, "showPage", Q_ARG(QVariant, p));
                window->show();
                window->raise();
                window->requestActivate();
                s->disconnectFromServer();
            });
            QObject::connect(s, &QLocalSocket::disconnected, s, &QObject::deleteLater);
        }
    });

    if (!shotPrefix.isEmpty()) {
        // 撮る: 名前ごとに QML の prepareShot(名前) で画面を整えてから、少し待って撮る
        if (shotSize.isValid()) window->resize(shotSize);
        const QStringList names = shots.isEmpty() ? PAGES : shots.split(',');
        auto index = std::make_shared<int>(0);
        auto step = std::make_shared<std::function<void()>>();
        *step = [&, index, step, names] {
            if (*index >= names.size()) {
                app.quit();
                return;
            }
            const QString name = names[*index];
            QMetaObject::invokeMethod(window, "prepareShot", Q_ARG(QVariant, name));
            QTimer::singleShot(700, [&, index, step, name] {
                const QImage image = window->grabWindow();
                const QString out = shotPrefix + "-" + QString(name).replace(':', '-') + ".png";
                image.save(out);
                qInfo("%s %dx%d", qPrintable(out), image.width(), image.height());
                ++*index;
                (*step)();
            });
        };
        QTimer::singleShot(500, *step);
    }
    return app.exec();
}

#include "main.moc"
