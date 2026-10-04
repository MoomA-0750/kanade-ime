// DLL 全体で使うもの（GUID・参照の数・モジュールのハンドル）
#pragma once
#include <windows.h>

namespace kanade {

extern HINSTANCE g_module;
extern LONG g_refs;

inline void DllAddRef() { InterlockedIncrement(&g_refs); }
inline void DllRelease() { InterlockedDecrement(&g_refs); }

// テキストサービスの CLSID とプロファイル（日本語 0x0411 に登録する）
// {92CD23E6-D514-4099-84D0-81969040EE41}
inline constexpr GUID CLSID_TextService = {0x92cd23e6, 0xd514, 0x4099, {0x84, 0xd0, 0x81, 0x96, 0x90, 0x40, 0xee, 0x41}};
// {92A70E2C-B06A-4AD4-B6CB-7B6DF9CED95E}
inline constexpr GUID GUID_Profile = {0x92a70e2c, 0xb06a, 0x4ad4, {0xb6, 0xcb, 0x7b, 0x6d, 0xf9, 0xce, 0xd9, 0x5e}};
// 表示属性: 入力中（点線）・変換中の選んでいる文節（太線）・変換中のほかの文節（細線）
// {7695C0FD-44E8-4E44-A112-38B7736331D8}
inline constexpr GUID GUID_AttrInput = {0x7695c0fd, 0x44e8, 0x4e44, {0xa1, 0x12, 0x38, 0xb7, 0x73, 0x63, 0x31, 0xd8}};
// {03E3E0FC-3E17-4E19-BE70-D16C201CAD4C}
inline constexpr GUID GUID_AttrFocused = {0x03e3e0fc, 0x3e17, 0x4e19, {0xbe, 0x70, 0xd1, 0x6c, 0x20, 0x1c, 0xad, 0x4c}};
// {0455615F-C73A-4346-B1B1-BF1D501BAB32}
inline constexpr GUID GUID_AttrConverted = {0x0455615f, 0xc73a, 0x4346, {0xb1, 0xb1, 0xbf, 0x1d, 0x50, 0x1b, 0xab, 0x32}};

inline constexpr LANGID kLangId = MAKELANGID(LANG_JAPANESE, SUBLANG_JAPANESE_JAPAN);
inline constexpr wchar_t kDisplayName[] = L"Kanade";

}  // namespace kanade
