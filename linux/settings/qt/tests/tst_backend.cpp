// 中身（設定のファイル・テーマ・キーの名前・エンジンへの頼みごと）のテスト
#include "engine.h"
#include "keycapture.h"
#include "store.h"
#include "theme.h"

#include <QDir>
#include <QFile>
#include <QGuiApplication>
#include <QKeyEvent>
#include <QSignalSpy>
#include <QTemporaryDir>
#include <QTest>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <cstring>
#include <thread>
#include <xkbcommon/xkbcommon-keysyms.h>

namespace {
void write(const QString &path, const QByteArray &text)
{
    QDir().mkpath(QFileInfo(path).absolutePath());
    QFile f(path);
    QVERIFY(f.open(QIODevice::WriteOnly));
    f.write(text);
}

const QByteArray DARK = "# test\nmode = \"dark\"\naccent = \"#89b4fa\"  # 青\nbackground = \"#1e1e2e\"\nforeground = \"#cdd6f4\"\n"
                        "dark_background = \"#181825\"\nlighter_background = \"#313244\"\nmuted = \"#585b70\"\nred = \"#f38ba8\"\n";
const QByteArray LIGHT = "accent = \"#205EA6\"\nbackground = \"#FFFCF0\"\nforeground = \"#100F0F\"\nred = \"#D14D41\"\n";
}  // namespace

class TestBackend : public QObject {
    Q_OBJECT
    QTemporaryDir tmp;

private slots:
    void initTestCase()
    {
        QVERIFY(tmp.isValid());
        qputenv("XDG_CONFIG_HOME", (tmp.path() + "/config").toUtf8());
        qputenv("XDG_DATA_HOME", (tmp.path() + "/data").toUtf8());
        qputenv("LANG", "ja_JP.UTF-8");
        qunsetenv("LC_ALL");
        qunsetenv("LC_MESSAGES");
        // hyprctl が Hyprland に届かないように
        qputenv("HYPRLAND_INSTANCE_SIGNATURE", "kanade-test-none");
    }

    void themeReadsColors()
    {
        QTemporaryDir state;
        write(state.path() + "/omarchy/current/theme/colors.toml", DARK);
        Theme t(state.path());
        QVERIFY(t.omarchy());
        QVERIFY(t.dark());
        QCOMPARE(t.accent(), QColor("#89b4fa"));
        QCOMPARE(t.background(), QColor("#1e1e2e"));
        QCOMPARE(t.view(), QColor("#181825"));
        QCOMPARE(t.raised(), QColor("#313244"));
        QCOMPARE(t.border(), QColor("#585b70"));
        QCOMPARE(t.red(), QColor("#f38ba8"));
        QCOMPARE(t.green(), QColor("#89b4fa"));  // 無ければアクセント
        // 明るいアクセントの上は暗い背景色の字
        QCOMPARE(t.textOnAccent(), QColor("#1e1e2e"));
        // hyprctl で読めなければ Omarchy の既定の 0
        QCOMPARE(t.rounding(), 0);
    }

    void themeModeFromBrightness()
    {
        QTemporaryDir state;
        write(state.path() + "/omarchy/current/theme/colors.toml", LIGHT);  // mode が無い
        Theme t(state.path());
        QVERIFY(t.omarchy());
        QVERIFY(!t.dark());
        QCOMPARE(t.view(), QColor("#FFFCF0"));    // dark_background が無ければ background
        QCOMPARE(t.border(), QColor("#FFFCF0"));  // muted も lighter_background も無ければ background
        QCOMPARE(t.textOnAccent(), QColor("#FFFCF0"));  // 濃い青の上は明るい背景色の字
    }

    void themeWithoutOmarchy()
    {
        QTemporaryDir state;
        Theme t(state.path());
        QVERIFY(!t.omarchy());
        QCOMPARE(t.rounding(), -1);
        // 壊れたファイル（background が無い）も Omarchy が無いのと同じ
        write(state.path() + "/omarchy/current/theme/colors.toml", "accent = \"#ff0000\"\n");
        Theme u(state.path());
        QVERIFY(!u.omarchy());
    }

    void themeFollowsSwitch()
    {
        QTemporaryDir state;
        const QString current = state.path() + "/omarchy/current";
        write(current + "/theme/colors.toml", DARK);
        Theme t(state.path());
        QSignalSpy spy(&t, &Theme::changed);
        // Omarchy と同じく、フォルダーごと置き換える
        write(current + "/next-theme/colors.toml", LIGHT);
        QVERIFY(QDir(current + "/theme").removeRecursively());
        QVERIFY(QDir().rename(current + "/next-theme", current + "/theme"));
        QTRY_VERIFY_WITH_TIMEOUT(spy.count() > 0, 3000);
        QVERIFY(!t.dark());
        QCOMPARE(t.accent(), QColor("#205EA6"));
        // 見張りを付け直したので、次の書き換えも届く
        spy.clear();
        write(current + "/theme/colors.toml", DARK);
        QTRY_VERIFY_WITH_TIMEOUT(spy.count() > 0, 3000);
        QVERIFY(t.dark());
        // テーマが消えたら Omarchy が無いときに戻り、また現れたら読む
        spy.clear();
        QVERIFY(QDir(state.path() + "/omarchy").removeRecursively());
        QTRY_VERIFY_WITH_TIMEOUT(!t.omarchy(), 3000);
        write(current + "/theme/colors.toml", LIGHT);
        QTRY_VERIFY_WITH_TIMEOUT(t.omarchy(), 3000);
    }

    void rounding()
    {
        QCOMPARE(Theme::parseRounding(R"({"option":"decoration:rounding","int":10,"set":true})"), 10);
        QCOMPARE(Theme::parseRounding(R"({"option":"decoration:rounding","int":0,"set":false})"), 0);
        QCOMPARE(Theme::parseRounding("no"), -1);
    }

    void parseToml()
    {
        const auto c = Theme::parse("a = \"#010203\"\n  b=\"x\" # c\n# d = \"e\"\nhyprland_active_border = \"rgba(1) rgba(2) 45deg\"\n");
        QCOMPARE(c.value("a"), "#010203");
        QCOMPARE(c.value("b"), "x");
        QVERIFY(!c.contains("d"));
        QCOMPARE(c.value("hyprland_active_border"), "rgba(1) rgba(2) 45deg");
    }

    void settingsFile()
    {
        Store s;
        QCOMPARE(s.get("punctuation"), "、。");  // 既定
        s.set("punctuation", "，．");
        s.set("voice_hold_keys", "F9");
        QCOMPARE(readText(paths::settingsFile()),
                 QString("# Kanade の設定。保存するとすぐ反映されます\npunctuation=，．\n"
                         "# Linux 版: どの入力方式でも、押している間だけ録音するキー（fcitx5 のキーの名前をカンマ区切り。例 F12,Alt_R,F9）\n"
                         "voice_hold_keys=F9\n"));
        // ほかの行と説明はそのまま、1行だけ書き換える
        writeText(paths::settingsFile(), "# x\n live_conversion = false \nfoo=bar\r\n");
        QCOMPARE(s.get("live_conversion"), "false");
        s.set("live_conversion", "true");
        QCOMPARE(readText(paths::settingsFile()), QString("# x\nlive_conversion=true\nfoo=bar\n"));
        QSignalSpy lang(&s, &Store::languageChanged);
        QVERIFY(!s.english());
        s.set("ui_language", "en");
        QVERIFY(s.english());
        QCOMPARE(lang.count(), 1);
        s.set("ui_language", "auto");
        QVERIFY(!s.english());  // LANG=ja_JP
    }

    void pairs()
    {
        Store s;
        writeText(paths::dictionaryFile(), "# 説明\nかなで Kanade\n\nでっくす\t DeX\nひとつ\nあ い う\n");
        const QVariantList e = s.entries("dictionary");
        QCOMPARE(e.size(), 3);
        QCOMPARE(e[1].toMap().value("line").toInt(), 3);
        QCOMPARE(e[1].toMap().value("value").toString(), "DeX");
        QCOMPARE(e[2].toMap().value("value").toString(), "い う");
        QCOMPARE(s.entries("dictionary", "DeX").size(), 1);
        s.writeEntry("dictionary", 3, " でっくす ", "Galaxy DeX");
        s.writeEntry("dictionary", 1, "", "", true);
        s.writeEntry("dictionary", -1, "えー", "A");
        QCOMPARE(readText(paths::dictionaryFile()), QString("# 説明\n\nでっくす Galaxy DeX\nひとつ\nあ い う\nえー A\n"));
        s.setText("snippets", "-> →");
        QCOMPARE(readText(paths::snippetsFile()), QString("-> →\n"));
    }

    void keyNames()
    {
        auto ev = [](int key, Qt::KeyboardModifiers mods, quint32 sym, const QString &text = {}) {
            return QKeyEvent(QEvent::KeyPress, key, mods, 0, sym, 0, text);
        };
        QKeyEvent f12 = ev(Qt::Key_F12, {}, XKB_KEY_F12);
        QCOMPARE(KeyCapture::nameOf(&f12, false), "F12");
        QKeyEvent altR = ev(Qt::Key_Alt, Qt::AltModifier, XKB_KEY_Alt_R);
        QCOMPARE(KeyCapture::nameOf(&altR, false), "Alt_R");
        QCOMPARE(KeyCapture::nameOf(&altR, true), "");  // 組み合わせでは修飾キーだけは取らない
        QKeyEvent space = ev(Qt::Key_Space, Qt::ControlModifier | Qt::AltModifier, XKB_KEY_space, " ");
        QCOMPARE(KeyCapture::nameOf(&space, true), "Control+Alt+space");
        QKeyEvent plain = ev(Qt::Key_A, {}, XKB_KEY_a, "a");
        QCOMPARE(KeyCapture::nameOf(&plain, true), "");  // 組み合わせでは修飾なしは取らない
        QKeyEvent shiftA = ev(Qt::Key_A, Qt::ShiftModifier | Qt::MetaModifier, XKB_KEY_A, "A");
        QCOMPARE(KeyCapture::nameOf(&shiftA, true), "Shift+Super+a");
        QKeyEvent esc = ev(Qt::Key_Escape, {}, XKB_KEY_Escape);
        QCOMPARE(KeyCapture::nameOf(&esc, true), "Escape");
        // keysym が取れない描き先では Qt のキーから
        QKeyEvent f9 = ev(Qt::Key_F9, {}, 0);
        QCOMPARE(KeyCapture::nameOf(&f9, false), "F9");
        QKeyEvent menu = ev(Qt::Key_Menu, {}, 0);
        QCOMPARE(KeyCapture::nameOf(&menu, false), "Menu");
        QCOMPARE(KeyCapture::label("Control+Alt+space", false), "Ctrl+Alt+Space");
        QCOMPARE(KeyCapture::label("Alt_R", false), "右 Alt");
        QCOMPARE(KeyCapture::label("Alt_R", true), "Right Alt");
        QCOMPARE(KeyCapture::label("F12", true), "F12");
    }

    void engineRequest()
    {
        QCOMPARE(Engine::unescape(Engine::escape("a\tb\\n\nc\r")), "a\tb\\n\nc\r");
        const QString path = tmp.path() + "/engine.sock";
        const int server = ::socket(AF_UNIX, SOCK_STREAM, 0);
        sockaddr_un addr{};
        addr.sun_family = AF_UNIX;
        std::strcpy(addr.sun_path, path.toLocal8Bit().constData());
        QVERIFY(::bind(server, reinterpret_cast<sockaddr *>(&addr), sizeof addr) == 0);
        QVERIFY(::listen(server, 1) == 0);
        QByteArray got;
        std::thread t([&] {
            const int c = ::accept(server, nullptr, nullptr);
            char buf[4096];
            const ssize_t n = ::recv(c, buf, sizeof buf, 0);
            got = QByteArray(buf, int(n));
            // 関係ない行のあとに返事
            const char reply[] = "c\t0\tcommit\tx\nadmin\t1\t1\t2 件\\n読み込みました\n";
            ::send(c, reply, sizeof reply - 1, 0);
            ::close(c);
        });
        const auto r = Engine::send(path, "import-android", "/tmp/a\tb.json", false);
        t.join();
        ::close(server);
        QCOMPARE(got, QByteArray("admin\t1\timport-android\t/tmp/a\\tb.json\n"));
        QVERIFY(r.first);
        QCOMPARE(r.second, "2 件\n読み込みました");
        const auto none = Engine::send(tmp.path() + "/none.sock", "clear-learning", "", true);
        QVERIFY(!none.first);
        QVERIFY(none.second.contains("not running"));
    }
};

QTEST_MAIN(TestBackend)
#include "tst_backend.moc"
