// TSF の DLL を本物のキー入力で試す道具。自分の窓（入力欄1つ）を出し、このプロセスだけ Kanade に切り替えて、
// キー列を SendInput で打ち、入力欄の中身を出力する。キーを送る前に毎回、自分の窓が前面にあるかを確かめる（ほかの窓へ打たない）
//   kanade_typetest.exe [--rich] [--in <file>] [--out <file>] "<キー列>"（--in はキー列を UTF-8 のファイルから読む）
//   キー列: 文字はそのまま。{enter} {space} {bs} {esc} {henkan} {muhenkan} {f7} {ctrl+space} {ralt} {wait} など
#include <windows.h>
#include <msctf.h>
#include <richedit.h>
#include <wrl/client.h>

#include <cstdio>
#include <string>
#include <vector>

#include "Globals.h"
#include "Protocol.h"

using Microsoft::WRL::ComPtr;
using namespace kanade;

namespace {

HWND g_main = nullptr;
HWND g_edit = nullptr;

LRESULT CALLBACK MainProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    if (msg == WM_SIZE && g_edit) MoveWindow(g_edit, 0, 0, LOWORD(lParam), HIWORD(lParam), TRUE);
    if (msg == WM_SETFOCUS && g_edit) SetFocus(g_edit);
    if (msg == WM_DESTROY) PostQuitMessage(0);
    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

void Pump(DWORD ms) {
    ULONGLONG end = GetTickCount64() + ms;
    do {
        MSG m;
        while (PeekMessageW(&m, nullptr, 0, 0, PM_REMOVE)) {
            TranslateMessage(&m);
            DispatchMessageW(&m);
        }
        Sleep(5);
    } while (GetTickCount64() < end);
}

bool Foreground() { return GetForegroundWindow() == g_main; }

void Key(WORD vk, bool up, bool extended = false) {
    INPUT in = {};
    in.type = INPUT_KEYBOARD;
    in.ki.wVk = vk;
    in.ki.wScan = static_cast<WORD>(MapVirtualKeyW(vk, MAPVK_VK_TO_VSC));
    in.ki.dwFlags = (up ? KEYEVENTF_KEYUP : 0) | (extended ? KEYEVENTF_EXTENDEDKEY : 0);
    SendInput(1, &in, sizeof(in));
}

bool Press(std::vector<WORD> mods, WORD vk, bool extended = false) {
    if (!Foreground()) return false;
    for (WORD m : mods) Key(m, false);
    Key(vk, false, extended);
    Key(vk, true, extended);
    for (auto it = mods.rbegin(); it != mods.rend(); ++it) Key(*it, true);
    Pump(40);
    return true;
}

bool Named(std::string name) {
    for (auto& c : name) c = static_cast<char>(tolower(c));
    if (name == "wait") {
        Pump(500);
        return true;
    }
    // 候補の窓の N 行目（1 から）をクリックする（候補の窓は、このプロセスの中に DLL が出している）
    if (name.rfind("click", 0) == 0 && name.size() > 5) {
        int row = atoi(name.c_str() + 5) - 1;
        HWND popup = nullptr;
        EnumThreadWindows(GetCurrentThreadId(), [](HWND hwnd, LPARAM lp) -> BOOL {
            wchar_t cls[64];
            GetClassNameW(hwnd, cls, 64);
            if (wcsncmp(cls, L"KanadePopup_", 12) == 0 && IsWindowVisible(hwnd)) {
                RECT r;
                GetWindowRect(hwnd, &r);
                // 候補の窓は、小さな表示（36x36）より大きい
                if (r.bottom - r.top > 60) {
                    *reinterpret_cast<HWND*>(lp) = hwnd;
                    return FALSE;
                }
            }
            return TRUE;
        }, reinterpret_cast<LPARAM>(&popup));
        if (!popup) {
            fprintf(stderr, "no candidate window\n");
            return true;
        }
        RECT r;
        GetWindowRect(popup, &r);
        SetCursorPos(r.left + 60, r.top + 4 + 34 * row + 17);
        Pump(100);
        INPUT in[2] = {};
        in[0].type = in[1].type = INPUT_MOUSE;
        in[0].mi.dwFlags = MOUSEEVENTF_LEFTDOWN;
        in[1].mi.dwFlags = MOUSEEVENTF_LEFTUP;
        SendInput(2, in, sizeof(INPUT));
        Pump(300);
        return true;
    }
    // 窓のまわり（候補の窓が出るところも含めて）を撮って、%USERPROFILE%\kanade\shot-<番号>.bmp に置く
    if (name == "shot") {
        static int count = 0;
        Pump(300);
        RECT r;
        GetWindowRect(g_main, &r);
        int w = 900, h = 700;
        HDC screen = GetDC(nullptr);
        HDC dc = CreateCompatibleDC(screen);
        HBITMAP bmp = CreateCompatibleBitmap(screen, w, h);
        HGDIOBJ old = SelectObject(dc, bmp);
        BitBlt(dc, 0, 0, w, h, screen, r.left - 20, r.top - 20, SRCCOPY | CAPTUREBLT);
        BITMAPINFOHEADER bi = {sizeof(bi), w, h, 1, 24, BI_RGB};
        std::vector<BYTE> pixels(((w * 3 + 3) & ~3) * h);
        GetDIBits(dc, bmp, 0, h, pixels.data(), reinterpret_cast<BITMAPINFO*>(&bi), DIB_RGB_COLORS);
        SelectObject(dc, old);
        DeleteObject(bmp);
        DeleteDC(dc);
        ReleaseDC(nullptr, screen);
        wchar_t path[MAX_PATH];
        swprintf_s(path, L"%s\\kanade\\shot-%d.bmp", _wgetenv(L"USERPROFILE"), ++count);
        FILE* f = nullptr;
        if (_wfopen_s(&f, path, L"wb") == 0 && f) {
            BITMAPFILEHEADER fh = {0x4D42, static_cast<DWORD>(sizeof(fh) + sizeof(bi) + pixels.size()), 0, 0, sizeof(fh) + sizeof(bi)};
            fwrite(&fh, sizeof(fh), 1, f);
            fwrite(&bi, sizeof(bi), 1, f);
            fwrite(pixels.data(), 1, pixels.size(), f);
            fclose(f);
        }
        return true;
    }
    // F12（録音キー）を2秒押し続ける
    if (name == "f12hold") {
        if (!Foreground()) return false;
        Key(VK_F12, false);
        Pump(2000);
        Key(VK_F12, true);
        Pump(40);
        return true;
    }
    std::vector<WORD> mods;
    for (size_t plus; (plus = name.find('+')) != std::string::npos; name = name.substr(plus + 1)) {
        std::string m = name.substr(0, plus);
        mods.push_back(m == "ctrl" ? VK_CONTROL : m == "shift" ? VK_SHIFT : m == "alt" ? VK_MENU : 0);
    }
    struct { const char* name; WORD vk; bool ext; } keys[] = {
        {"enter", VK_RETURN, false}, {"space", VK_SPACE, false}, {"bs", VK_BACK, false}, {"esc", VK_ESCAPE, false},
        {"left", VK_LEFT, true}, {"right", VK_RIGHT, true}, {"up", VK_UP, true}, {"down", VK_DOWN, true},
        {"henkan", VK_CONVERT, false}, {"muhenkan", VK_NONCONVERT, false}, {"f6", VK_F6, false}, {"f7", VK_F7, false},
        {"f8", VK_F8, false}, {"f9", VK_F9, false}, {"f10", VK_F10, false}, {"f12", VK_F12, false}, {"ralt", VK_RMENU, true}, {"tab", VK_TAB, false},
    };
    for (auto& k : keys) {
        if (name == k.name) return Press(mods, k.vk, k.ext);
    }
    if (name.size() == 1) return Press(mods, static_cast<WORD>(toupper(name[0])));
    fprintf(stderr, "unknown key {%s}\n", name.c_str());
    return true;
}

bool TypeScript(const std::string& script) {
    for (size_t i = 0; i < script.size(); i++) {
        char c = script[i];
        if (c == '{') {
            size_t end = script.find('}', i);
            if (end == std::string::npos) return false;
            if (!Named(script.substr(i + 1, end - i - 1))) return false;
            i = end;
            continue;
        }
        SHORT scan = VkKeyScanW(static_cast<wchar_t>(c));
        if (scan == -1) continue;
        std::vector<WORD> mods;
        if (scan & 0x100) mods.push_back(VK_SHIFT);
        if (!Press(mods, LOBYTE(scan))) return false;
    }
    return true;
}

// このプロセスだけ Kanade に切り替える
bool ActivateKanade() {
    ComPtr<ITfInputProcessorProfileMgr> profiles;
    if (FAILED(CoCreateInstance(CLSID_TF_InputProcessorProfiles, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&profiles)))) return false;
    HRESULT hr = profiles->ActivateProfile(TF_PROFILETYPE_INPUTPROCESSOR, kLangId, CLSID_TextService, GUID_Profile, nullptr,
                                           TF_IPPMF_FORPROCESS | TF_IPPMF_DONTCARECURRENTINPUTLANGUAGE);
    return SUCCEEDED(hr);
}

}  // namespace

int wmain(int argc, wchar_t** argv) {
    SetConsoleOutputCP(CP_UTF8);
    bool rich = false;
    std::wstring out;
    std::string script;
    for (int i = 1; i < argc; i++) {
        std::wstring a = argv[i];
        if (a == L"--rich") rich = true;
        else if (a == L"--out" && i + 1 < argc) out = argv[++i];
        else if (a == L"--in" && i + 1 < argc) {
            FILE* f = nullptr;
            if (_wfopen_s(&f, argv[++i], L"rb") == 0 && f) {
                char buf[4096];
                size_t n;
                while ((n = fread(buf, 1, sizeof(buf), f)) > 0) script.append(buf, n);
                fclose(f);
            }
            while (!script.empty() && (script.back() == '\n' || script.back() == '\r')) script.pop_back();
        }
        else script += ToUtf8(a);
    }
    // 固まったとき（メニューのモードに入ったなど）でも、20秒で結果を書いて終わる
    static std::wstring s_out;
    s_out = out;
    CreateThread(nullptr, 0, [](LPVOID) -> DWORD {
        Sleep(20000);
        if (!s_out.empty()) {
            FILE* f = nullptr;
            if (_wfopen_s(&f, s_out.c_str(), L"wb") == 0 && f) {
                fputs("ERROR: 20秒で終わらなかった", f);
                fclose(f);
            }
        }
        ExitProcess(4);
    }, nullptr, 0, nullptr);
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    if (rich) LoadLibraryW(L"Msftedit.dll");

    WNDCLASSW wc = {};
    wc.lpfnWndProc = MainProc;
    wc.hInstance = GetModuleHandleW(nullptr);
    wc.lpszClassName = L"KanadeTypeTest";
    RegisterClassW(&wc);
    g_main = CreateWindowExW(WS_EX_TOPMOST, wc.lpszClassName, L"Kanade type test", WS_OVERLAPPEDWINDOW | WS_VISIBLE, 200, 200, 700, 200,
                             nullptr, nullptr, wc.hInstance, nullptr);
    g_edit = CreateWindowExW(0, rich ? MSFTEDIT_CLASS : L"EDIT", L"", WS_CHILD | WS_VISIBLE | ES_MULTILINE, 0, 0, 700, 200, g_main,
                             nullptr, wc.hInstance, nullptr);
    HFONT font = CreateFontW(-28, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET, 0, 0, 0, 0, L"Yu Gothic UI");
    SendMessageW(g_edit, WM_SETFONT, reinterpret_cast<WPARAM>(font), TRUE);
    if (rich) SendMessageW(g_edit, EM_SETEDITSTYLE, SES_USECTF, SES_USECTF);
    SetForegroundWindow(g_main);
    SetFocus(g_edit);
    Pump(300);

    std::string result;
    int code = 0;
    if (!ActivateKanade()) {
        result = "ERROR: Kanade に切り替えられない";
        code = 2;
    } else {
        Pump(500);
        if (!TypeScript(script)) {
            result = "ERROR: 窓が前面にない（打つのをやめた）\n";
            code = 3;
        }
        Pump(500);
        int n = GetWindowTextLengthW(g_edit);
        std::wstring text(n + 1, L'\0');
        GetWindowTextW(g_edit, text.data(), n + 1);
        text.resize(n);
        result += ToUtf8(text);
    }
    if (!out.empty()) {
        FILE* f = nullptr;
        if (_wfopen_s(&f, out.c_str(), L"wb") == 0 && f) {
            fwrite(result.data(), 1, result.size(), f);
            fclose(f);
        }
    }
    printf("%s\n", result.c_str());
    DestroyWindow(g_main);
    CoUninitialize();
    return code;
}
