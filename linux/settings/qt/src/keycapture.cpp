#include "keycapture.h"

#include <QGuiApplication>
#include <QKeyEvent>
#include <QMap>
#include <QSet>
#include <xkbcommon/xkbcommon.h>

namespace {

const QSet<QString> &modifierNames()
{
    static const QSet<QString> s{"Shift_L", "Shift_R", "Control_L", "Control_R", "Alt_L", "Alt_R", "Super_L", "Super_R",
                                 "Meta_L", "Meta_R", "ISO_Level3_Shift", "Caps_Lock"};
    return s;
}

// keysym が取れない描き先（offscreen など）では、Qt のキーから keysym を作る
quint32 keysymFromQt(const QKeyEvent *e)
{
    const int k = e->key();
    if (k >= Qt::Key_F1 && k <= Qt::Key_F35) return XKB_KEY_F1 + (k - Qt::Key_F1);
    static const QMap<int, quint32> table{
        {Qt::Key_Space, XKB_KEY_space}, {Qt::Key_Escape, XKB_KEY_Escape}, {Qt::Key_Return, XKB_KEY_Return},
        {Qt::Key_Enter, XKB_KEY_KP_Enter}, {Qt::Key_Tab, XKB_KEY_Tab}, {Qt::Key_Backspace, XKB_KEY_BackSpace},
        {Qt::Key_Insert, XKB_KEY_Insert}, {Qt::Key_Delete, XKB_KEY_Delete}, {Qt::Key_Home, XKB_KEY_Home}, {Qt::Key_End, XKB_KEY_End},
        {Qt::Key_PageUp, XKB_KEY_Prior}, {Qt::Key_PageDown, XKB_KEY_Next}, {Qt::Key_Left, XKB_KEY_Left}, {Qt::Key_Right, XKB_KEY_Right},
        {Qt::Key_Up, XKB_KEY_Up}, {Qt::Key_Down, XKB_KEY_Down}, {Qt::Key_Pause, XKB_KEY_Pause}, {Qt::Key_Print, XKB_KEY_Print},
        {Qt::Key_ScrollLock, XKB_KEY_Scroll_Lock}, {Qt::Key_CapsLock, XKB_KEY_Caps_Lock}, {Qt::Key_Menu, XKB_KEY_Menu},
        {Qt::Key_Shift, XKB_KEY_Shift_L}, {Qt::Key_Control, XKB_KEY_Control_L}, {Qt::Key_Alt, XKB_KEY_Alt_L},
        {Qt::Key_AltGr, XKB_KEY_ISO_Level3_Shift}, {Qt::Key_Meta, XKB_KEY_Super_L},
    };
    if (table.contains(k)) return table.value(k);
    const QString text = e->text();
    if (!text.isEmpty() && text[0].isPrint()) return xkb_utf32_to_keysym(text.toUcs4().value(0));
    if (k >= 0x20 && k < 0x7f) return xkb_utf32_to_keysym(uint(k));
    return 0;
}

}  // namespace

QString KeyCapture::keysymName(quint32 keysym)
{
    char buf[64];
    const int n = xkb_keysym_get_name(xkb_keysym_to_lower(keysym), buf, sizeof buf);
    return n > 0 ? QString::fromLatin1(buf, n) : QString();
}

QString KeyCapture::nameOf(const QKeyEvent *e, bool combo)
{
    quint32 sym = e->nativeVirtualKey();
    if (!sym) sym = keysymFromQt(e);
    const QString name = keysymName(sym);
    if (name.isEmpty() || name == "Escape") return name;
    if (!combo) return name;
    if (modifierNames().contains(name)) return {};
    QStringList mods;
    const auto m = e->modifiers();
    if (m & Qt::ControlModifier) mods << "Control";
    if (m & Qt::AltModifier) mods << "Alt";
    if (m & Qt::ShiftModifier) mods << "Shift";
    if (m & Qt::MetaModifier) mods << "Super";
    if (mods.isEmpty()) return {};
    return (mods << name).join('+');
}

QString KeyCapture::label(const QString &name, bool english)
{
    static const QMap<QString, QPair<QString, QString>> labels{
        {"Alt_R", {"右 Alt", "Right Alt"}}, {"Alt_L", {"左 Alt", "Left Alt"}}, {"Control_R", {"右 Ctrl", "Right Ctrl"}},
        {"Control_L", {"左 Ctrl", "Left Ctrl"}}, {"Super_R", {"右 Super", "Right Super"}}, {"Shift_R", {"右 Shift", "Right Shift"}},
        {"Control", {"Ctrl", "Ctrl"}}, {"space", {"Space", "Space"}}, {"Menu", {"メニュー", "Menu"}},
    };
    QStringList out;
    for (const QString &p : name.split('+'))
        out << (labels.contains(p) ? (english ? labels[p].second : labels[p].first) : p);
    return out.join('+');
}

void KeyCapture::start(bool combo)
{
    m_combo = combo;
    if (!m_active) {
        qApp->installEventFilter(this);
        m_active = true;
        emit activeChanged();
    }
}

void KeyCapture::stop()
{
    if (!m_active) return;
    qApp->removeEventFilter(this);
    m_active = false;
    emit activeChanged();
}

bool KeyCapture::eventFilter(QObject *watched, QEvent *event)
{
    if (event->type() == QEvent::ShortcutOverride) {
        // ダイアログやボタンのショートカットより先に取る
        event->accept();
        return true;
    }
    if (event->type() != QEvent::KeyPress) return QObject::eventFilter(watched, event);
    // 同じキーの押下は、窓と中の部品の両方に届く。窓（QWindow）に届いた方だけを見る
    if (!watched->isWindowType()) return true;
    const auto *key = static_cast<QKeyEvent *>(event);
    const QString name = nameOf(key, m_combo);
    if (name == "Escape") {
        stop();
        emit cancelled();
        return true;
    }
    if (name.isEmpty()) return true;
    stop();
    emit captured(name);
    return true;
}
