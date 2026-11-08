// GTK 版（Python）と同じ書き方をするかを比べる道具（tests/compat.sh が使う）
//   store_cli set <key> <value> | put <kind> <line> <key> <value> | remove <kind> <line> | text <kind> <text> | entries <kind> [query]
#include "store.h"

#include <QCoreApplication>
#include <cstdio>

int main(int argc, char *argv[])
{
    QCoreApplication app(argc, argv);
    const QStringList a = app.arguments().mid(1);
    Store s;
    if (a.value(0) == "set") s.set(a[1], a[2]);
    else if (a.value(0) == "put") s.writeEntry(a[1], a[2].toInt(), a[3], a[4]);
    else if (a.value(0) == "remove") s.writeEntry(a[1], a[2].toInt(), {}, {}, true);
    else if (a.value(0) == "text") s.setText(a[1], a[2]);
    else if (a.value(0) == "entries") {
        for (const QVariant &v : s.entries(a[1], a.value(2))) {
            const QVariantMap m = v.toMap();
            std::printf("%d\t%s\t%s\n", m["line"].toInt(), m["key"].toString().toUtf8().constData(), m["value"].toString().toUtf8().constData());
        }
    } else return 2;
    return 0;
}
