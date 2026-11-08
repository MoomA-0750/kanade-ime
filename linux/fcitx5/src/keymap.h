// fcitx5 のキーを Windows の仮想キーへ写す。キーの割り当て（どのキーで何をするか）はエンジン（WinKeyMapper.kt）が
// 決めるので、ここは Windows の DLL と同じ形で送るための写しだけ
#pragma once
#include <cstdint>
#include <fcitx-utils/key.h>

namespace kanade {
namespace vk {
constexpr int BACK = 0x08, TAB = 0x09, RETURN = 0x0D, CAPITAL = 0x14, ESCAPE = 0x1B, CONVERT = 0x1C, NONCONVERT = 0x1D,
              SPACE = 0x20, F12 = 0x7B, LSHIFT = 0xA0, RMENU = 0xA5, OEM_ATTN = 0xF0, OEM_COPY = 0xF2, OEM_AUTO = 0xF3;
}

struct WinKey {
    int vk = 0;
    uint32_t ch = 0;
    int mods = 0;
};

// 写せないキーは vk が 0（文字があれば文字だけ送る）
WinKey toWinKey(const fcitx::Key &key);
} // namespace kanade
