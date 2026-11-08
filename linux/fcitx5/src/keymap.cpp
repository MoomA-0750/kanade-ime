#include "keymap.h"
#include <fcitx-utils/keysym.h>

namespace kanade {

static int vkOf(fcitx::KeySym sym) {
    using namespace fcitx;
    if (sym >= FcitxKey_a && sym <= FcitxKey_z) return 'A' + (sym - FcitxKey_a);
    if (sym >= FcitxKey_A && sym <= FcitxKey_Z) return 'A' + (sym - FcitxKey_A);
    if (sym >= FcitxKey_0 && sym <= FcitxKey_9) return '0' + (sym - FcitxKey_0);
    if (sym >= FcitxKey_KP_0 && sym <= FcitxKey_KP_9) return 0x60 + (sym - FcitxKey_KP_0);
    if (sym >= FcitxKey_F1 && sym <= FcitxKey_F24) return 0x70 + (sym - FcitxKey_F1);
    switch (sym) {
    case FcitxKey_BackSpace: return vk::BACK;
    case FcitxKey_Tab:
    case FcitxKey_ISO_Left_Tab: return vk::TAB;
    case FcitxKey_Return:
    case FcitxKey_KP_Enter: return vk::RETURN;
    case FcitxKey_Escape: return vk::ESCAPE;
    case FcitxKey_space:
    case FcitxKey_KP_Space: return vk::SPACE;
    case FcitxKey_Page_Up: return 0x21;
    case FcitxKey_Page_Down: return 0x22;
    case FcitxKey_End:
    case FcitxKey_KP_End: return 0x23;
    case FcitxKey_Home:
    case FcitxKey_KP_Home: return 0x24;
    case FcitxKey_Left:
    case FcitxKey_KP_Left: return 0x25;
    case FcitxKey_Up:
    case FcitxKey_KP_Up: return 0x26;
    case FcitxKey_Right:
    case FcitxKey_KP_Right: return 0x27;
    case FcitxKey_Down:
    case FcitxKey_KP_Down: return 0x28;
    case FcitxKey_Delete:
    case FcitxKey_KP_Delete: return 0x2E;
    case FcitxKey_Shift_L: return vk::LSHIFT;
    case FcitxKey_Shift_R: return 0xA1;
    case FcitxKey_Control_L: return 0xA2;
    case FcitxKey_Control_R: return 0xA3;
    case FcitxKey_Alt_L:
    case FcitxKey_Meta_L: return 0xA4;
    // 右 Alt（US 配列で ISO_Level3_Shift になる配列もある）。単押しで日本語のオン/オフ
    case FcitxKey_Alt_R:
    case FcitxKey_Meta_R:
    case FcitxKey_ISO_Level3_Shift: return vk::RMENU;
    case FcitxKey_Super_L: return 0x5B;
    case FcitxKey_Super_R: return 0x5C;
    case FcitxKey_Menu: return 0x5D;
    case FcitxKey_Caps_Lock: return vk::CAPITAL;
    case FcitxKey_Num_Lock: return 0x90;
    case FcitxKey_Scroll_Lock: return 0x91;
    // JIS 配列: 変換・無変換・カタカナ/ひらがな・半角/全角・英数
    case FcitxKey_Henkan_Mode: return vk::CONVERT;
    case FcitxKey_Muhenkan: return vk::NONCONVERT;
    case FcitxKey_Hiragana_Katakana:
    case FcitxKey_Hiragana:
    case FcitxKey_Katakana: return vk::OEM_COPY;
    case FcitxKey_Zenkaku_Hankaku:
    case FcitxKey_Zenkaku:
    case FcitxKey_Hankaku: return vk::OEM_AUTO;
    case FcitxKey_Eisu_toggle:
    case FcitxKey_Eisu_Shift: return vk::OEM_ATTN;
    default: return 0;
    }
}

WinKey toWinKey(const fcitx::Key &key) {
    WinKey k;
    auto sym = key.sym();
    k.vk = vkOf(sym);
    k.ch = fcitx::Key::keySymToUnicode(sym);
    if (k.ch < 0x20 || k.ch == 0x7F) k.ch = 0;
    auto states = key.states();
    if (states.test(fcitx::KeyState::Shift)) k.mods |= 1;
    if (states.test(fcitx::KeyState::Ctrl)) k.mods |= 2;
    if (states.test(fcitx::KeyState::Alt)) k.mods |= 4;
    if (states.test(fcitx::KeyState::Super) || states.test(fcitx::KeyState::Super2)) k.mods |= 8;
    return k;
}

} // namespace kanade
