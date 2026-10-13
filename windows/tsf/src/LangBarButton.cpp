#include "LangBarButton.h"

#include <olectl.h>

#include <vector>

#include "Globals.h"
#include "Log.h"

namespace kanade {

namespace {

// タスクバーの入力の表示に出す言語バーのボタン（Windows 8 以降）。SDK のヘッダーに無いことがあるので値を書く
constexpr GUID kGuidLbiInputMode = {0x2c77a81e, 0x41cc, 0x4178, {0xa3, 0xa7, 0x5f, 0x8a, 0x98, 0x75, 0x68, 0xe6}};

// メニューの項目（右クリック）
enum MenuId : UINT {
    kMenuJapanese = 1,
    kMenuDirect,
    kMenuSettings,
    kMenuUserDictionary,
    kMenuSnippets,
    kMenuImportAndroid,
    kMenuLog,
};

// タスクバーがライトなら黒、ダークなら白で描く
bool TaskbarIsLight() {
    DWORD value = 0;
    DWORD size = sizeof(value);
    if (RegGetValueW(HKEY_CURRENT_USER, L"Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", L"SystemUsesLightTheme",
                     RRF_RT_REG_DWORD, nullptr, &value, &size) != ERROR_SUCCESS) {
        return false;
    }
    return value != 0;
}

// 「あ」「A」のアイコンをその場で描く（文字の濃さをそのまま透明度にする）
HICON MakeIcon(const wchar_t* text) {
    UINT dpi = GetDpiForSystem();
    int size = GetSystemMetricsForDpi(SM_CXSMICON, dpi);
    BITMAPINFO bi = {};
    bi.bmiHeader.biSize = sizeof(bi.bmiHeader);
    bi.bmiHeader.biWidth = size;
    bi.bmiHeader.biHeight = -size;
    bi.bmiHeader.biPlanes = 1;
    bi.bmiHeader.biBitCount = 32;
    bi.bmiHeader.biCompression = BI_RGB;
    void* bits = nullptr;
    HDC screen = GetDC(nullptr);
    HBITMAP color = CreateDIBSection(screen, &bi, DIB_RGB_COLORS, &bits, nullptr, 0);
    HDC dc = CreateCompatibleDC(screen);
    ReleaseDC(nullptr, screen);
    if (!color || !dc) {
        if (color) DeleteObject(color);
        if (dc) DeleteDC(dc);
        return nullptr;
    }
    HGDIOBJ oldBitmap = SelectObject(dc, color);
    RECT rc = {0, 0, size, size};
    FillRect(dc, &rc, static_cast<HBRUSH>(GetStockObject(BLACK_BRUSH)));
    HFONT font = CreateFontW(-MulDiv(size, 85, 100), 0, 0, 0, FW_SEMIBOLD, 0, 0, 0, DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                             ANTIALIASED_QUALITY, DEFAULT_PITCH, L"Yu Gothic UI");
    HGDIOBJ oldFont = SelectObject(dc, font);
    SetBkMode(dc, TRANSPARENT);
    SetTextColor(dc, RGB(255, 255, 255));
    DrawTextW(dc, text, -1, &rc, DT_CENTER | DT_VCENTER | DT_SINGLELINE | DT_NOPREFIX);
    GdiFlush();
    BYTE fg = TaskbarIsLight() ? 0 : 255;
    auto* px = static_cast<BYTE*>(bits);
    for (int i = 0; i < size * size; i++) {
        BYTE coverage = px[i * 4 + 2];
        px[i * 4 + 0] = fg;
        px[i * 4 + 1] = fg;
        px[i * 4 + 2] = fg;
        px[i * 4 + 3] = coverage;
    }
    SelectObject(dc, oldFont);
    SelectObject(dc, oldBitmap);
    DeleteObject(font);
    DeleteDC(dc);
    std::vector<BYTE> zeros(((size + 15) / 16) * 2 * size, 0);
    HBITMAP mask = CreateBitmap(size, size, 1, 1, zeros.data());
    ICONINFO info = {TRUE, 0, 0, mask, color};
    HICON icon = CreateIconIndirect(&info);
    DeleteObject(mask);
    DeleteObject(color);
    return icon;
}

}  // namespace

LangBarButton::LangBarButton(std::function<void()> onToggle, std::function<void(const std::string&)> onMenu)
    : onToggle_(std::move(onToggle)), onMenu_(std::move(onMenu)) {
    DllAddRef();
}

LangBarButton::~LangBarButton() {
    if (sink_) sink_->Release();
    DllRelease();
}

STDMETHODIMP LangBarButton::QueryInterface(REFIID riid, void** ppv) {
    if (!ppv) return E_INVALIDARG;
    *ppv = nullptr;
    if (riid == IID_IUnknown || riid == IID_ITfLangBarItem || riid == IID_ITfLangBarItemButton) {
        *ppv = static_cast<ITfLangBarItemButton*>(this);
    } else if (riid == IID_ITfSource) {
        *ppv = static_cast<ITfSource*>(this);
    }
    if (!*ppv) return E_NOINTERFACE;
    AddRef();
    return S_OK;
}

STDMETHODIMP_(ULONG) LangBarButton::AddRef() { return InterlockedIncrement(&refs_); }

STDMETHODIMP_(ULONG) LangBarButton::Release() {
    LONG n = InterlockedDecrement(&refs_);
    if (n == 0) delete this;
    return n;
}

STDMETHODIMP LangBarButton::GetInfo(TF_LANGBARITEMINFO* info) {
    if (!info) return E_INVALIDARG;
    info->clsidService = CLSID_TextService;
    info->guidItem = kGuidLbiInputMode;
    info->dwStyle = TF_LBI_STYLE_BTN_BUTTON | TF_LBI_STYLE_SHOWNINTRAY;
    info->ulSort = 0;
    wcscpy_s(info->szDescription, L"Kanade の入力モード");
    return S_OK;
}

STDMETHODIMP LangBarButton::GetStatus(DWORD* status) {
    if (!status) return E_INVALIDARG;
    *status = 0;
    return S_OK;
}

STDMETHODIMP LangBarButton::GetTooltipString(BSTR* tooltip) {
    if (!tooltip) return E_INVALIDARG;
    *tooltip = SysAllocString(japanese_ ? L"Kanade: ひらがな（クリックで半角英数）" : L"Kanade: 半角英数（クリックでひらがな）");
    return *tooltip ? S_OK : E_OUTOFMEMORY;
}

STDMETHODIMP LangBarButton::GetText(BSTR* text) {
    if (!text) return E_INVALIDARG;
    *text = SysAllocString(japanese_ ? L"あ" : L"A");
    return *text ? S_OK : E_OUTOFMEMORY;
}

STDMETHODIMP LangBarButton::GetIcon(HICON* icon) {
    if (!icon) return E_INVALIDARG;
    *icon = MakeIcon(japanese_ ? L"あ" : L"A");
    return *icon ? S_OK : E_FAIL;
}

STDMETHODIMP LangBarButton::OnClick(TfLBIClick click, POINT, const RECT*) {
    // 右クリックは、システムが InitMenu で項目を聞いてメニューを描く（Windows 11 の見た目になる）
    Log("OnClick %d", static_cast<int>(click));
    try {
        if (click == TF_LBI_CLK_LEFT && onToggle_) onToggle_();
    } catch (...) {
        Log("exception in OnClick");
    }
    return S_OK;
}

STDMETHODIMP LangBarButton::InitMenu(ITfMenu* menu) {
    Log("InitMenu");
    if (!menu) return E_INVALIDARG;
    auto add = [menu](UINT id, DWORD flags, const wchar_t* text) {
        menu->AddMenuItem(id, flags, nullptr, nullptr, text, text ? static_cast<ULONG>(wcslen(text)) : 0, nullptr);
    };
    add(kMenuJapanese, japanese_ ? TF_LBMENUF_RADIOCHECKED : 0, L"ひらがな（あ）");
    add(kMenuDirect, japanese_ ? 0 : TF_LBMENUF_RADIOCHECKED, L"半角英数（A）");
    add(0, TF_LBMENUF_SEPARATOR, nullptr);
    add(kMenuSettings, 0, L"設定");
    add(kMenuUserDictionary, 0, L"ユーザー辞書");
    add(kMenuSnippets, 0, L"スニペット");
    add(kMenuImportAndroid, 0, L"Android 版の設定を読み込む");
    add(0, TF_LBMENUF_SEPARATOR, nullptr);
    add(kMenuLog, 0, L"記録を開く");
    return S_OK;
}

STDMETHODIMP LangBarButton::OnMenuSelect(UINT id) {
    Log("OnMenuSelect %u", id);
    const char* action = nullptr;
    switch (id) {
        case kMenuJapanese: action = "mode-on"; break;
        case kMenuDirect: action = "mode-off"; break;
        case kMenuSettings: action = "settings"; break;
        case kMenuUserDictionary: action = "user-dictionary"; break;
        case kMenuSnippets: action = "snippets"; break;
        case kMenuImportAndroid: action = "import-android"; break;
        case kMenuLog: action = "log"; break;
        default: break;
    }
    try {
        if (action && onMenu_) onMenu_(action);
    } catch (...) {
        Log("exception in OnMenuSelect");
    }
    return S_OK;
}

STDMETHODIMP LangBarButton::AdviseSink(REFIID riid, IUnknown* sink, DWORD* cookie) {
    if (!cookie) return E_INVALIDARG;
    if (riid != IID_ITfLangBarItemSink) return CONNECT_E_CANNOTCONNECT;
    if (sink_) return CONNECT_E_ADVISELIMIT;
    if (FAILED(sink->QueryInterface(IID_PPV_ARGS(&sink_)))) {
        sink_ = nullptr;
        return E_NOINTERFACE;
    }
    *cookie = 1;
    return S_OK;
}

STDMETHODIMP LangBarButton::UnadviseSink(DWORD cookie) {
    if (cookie != 1 || !sink_) return CONNECT_E_NOCONNECTION;
    sink_->Release();
    sink_ = nullptr;
    return S_OK;
}

void LangBarButton::SetJapanese(bool japanese) {
    if (japanese_ == japanese) return;
    japanese_ = japanese;
    if (sink_) sink_->OnUpdate(TF_LBI_ICON | TF_LBI_TEXT | TF_LBI_TOOLTIP);
}

}  // namespace kanade
