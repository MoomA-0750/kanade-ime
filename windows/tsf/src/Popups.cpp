#include "Popups.h"

#include <dwmapi.h>
#include <windowsx.h>

#include <algorithm>

#include "Globals.h"
#include "Log.h"

namespace kanade {

namespace {

constexpr wchar_t kFont[] = L"Yu Gothic UI";
// 候補の窓の寸法（DIP）
constexpr float kPad = 4.0f;
constexpr float kRow = 34.0f;
constexpr float kFooter = 22.0f;
constexpr float kNumberX = 14.0f;
constexpr float kTextX = 34.0f;
constexpr float kTextSize = 16.0f;
constexpr float kSmallSize = 12.0f;

D2D1_COLOR_F Rgb(UINT32 rgb, float a = 1.0f) {
    return D2D1::ColorF(((rgb >> 16) & 0xFF) / 255.0f, ((rgb >> 8) & 0xFF) / 255.0f, (rgb & 0xFF) / 255.0f, a);
}

bool AppsUseDark() {
    DWORD value = 1;
    DWORD size = sizeof(value);
    RegGetValueW(HKEY_CURRENT_USER, L"Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", L"AppsUseLightTheme",
                 RRF_RT_REG_DWORD, nullptr, &value, &size);
    return value == 0;
}

// Windows のアクセントの色（なければ既定の青）
D2D1_COLOR_F AccentColor(bool dark) {
    DWORD abgr = 0;
    DWORD size = sizeof(abgr);
    if (RegGetValueW(HKEY_CURRENT_USER, L"Software\\Microsoft\\Windows\\DWM", L"AccentColor", RRF_RT_REG_DWORD, nullptr, &abgr, &size) ==
        ERROR_SUCCESS) {
        return D2D1::ColorF((abgr & 0xFF) / 255.0f, ((abgr >> 8) & 0xFF) / 255.0f, ((abgr >> 16) & 0xFF) / 255.0f, 1.0f);
    }
    return dark ? Rgb(0x60CDFF) : Rgb(0x0067C0);
}

}  // namespace

PopupWindow::~PopupWindow() {
    if (!hwnd_) return;
    SetWindowLongPtrW(hwnd_, GWLP_USERDATA, 0);
    if (GetCurrentThreadId() == thread_) {
        DestroyWindow(hwnd_);
    } else {
        // 窓は作ったスレッドでしか壊せない（TextService の最後の Release が別のスレッドで起きたとき）
        PostMessageW(hwnd_, WM_CLOSE, 0, 0);
    }
}

bool PopupWindow::Ensure() {
    // 拡大率への対応はスレッドごとに切り替えられる（Office など）。窓を作ったときと違えば作り直す
    if (hwnd_ && !AreDpiAwarenessContextsEqual(GetWindowDpiAwarenessContext(hwnd_), GetThreadDpiAwarenessContext())) {
        SetWindowLongPtrW(hwnd_, GWLP_USERDATA, 0);
        DestroyWindow(hwnd_);
        hwnd_ = nullptr;
        target_.Reset();
    }
    if (hwnd_) return true;
    if (!d2d_ && FAILED(D2D1CreateFactory(D2D1_FACTORY_TYPE_SINGLE_THREADED, d2d_.GetAddressOf()))) return false;
    if (!dwrite_ &&
        FAILED(DWriteCreateFactory(DWRITE_FACTORY_TYPE_SHARED, __uuidof(IDWriteFactory), reinterpret_cast<IUnknown**>(dwrite_.GetAddressOf())))) {
        return false;
    }
    WNDCLASSEXW wc = {sizeof(wc)};
    wc.style = CS_DROPSHADOW;
    wc.lpfnWndProc = Proc;
    wc.hInstance = g_module;
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.lpszClassName = PopupWindowClass();
    RegisterClassExW(&wc);
    hwnd_ = CreateWindowExW(WS_EX_TOPMOST | WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW, PopupWindowClass(), L"", WS_POPUP, 0, 0, 1, 1, nullptr, nullptr,
                            g_module, nullptr);
    if (!hwnd_) return false;
    thread_ = GetCurrentThreadId();
    SetWindowLongPtrW(hwnd_, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(this));
    // Windows 11 の角の丸め
    DWORD corner = 2;  // DWMWCP_ROUND
    DwmSetWindowAttribute(hwnd_, 33 /* DWMWA_WINDOW_CORNER_PREFERENCE */, &corner, sizeof(corner));
    return true;
}

void PopupWindow::ShowAt(const RECT& anchor, float alignX) {
    if (!Ensure()) return;
    // 拡大率は、この窓を出す場所のものを窓から聞く（拡大率に対応していないアプリでは 96 が返り、Windows が引き伸ばす）。
    // 先に窓を出す場所へ動かしてから聞く
    SetWindowPos(hwnd_, HWND_TOPMOST, anchor.left, anchor.bottom, 1, 1, SWP_NOACTIVATE | SWP_NOREDRAW);
    scale_ = GetDpiForWindow(hwnd_) / 96.0f;
    if (scale_ <= 0) scale_ = 1.0f;
    dark_ = AppsUseDark();
    background_ = dark_ ? Rgb(0x2C2C2C) : Rgb(0xF9F9F9);
    text_ = dark_ ? Rgb(0xFFFFFF) : Rgb(0x1A1A1A);
    secondary_ = dark_ ? Rgb(0xC5C5C5) : Rgb(0x5C5C5C);
    selection_ = dark_ ? Rgb(0xFFFFFF, 0.08f) : Rgb(0x000000, 0.06f);
    accent_ = AccentColor(dark_);
    D2D1_SIZE_F size = Measure();
    int w = static_cast<int>(size.width * scale_ + 0.5f);
    int h = static_cast<int>(size.height * scale_ + 0.5f);
    MONITORINFO mi = {sizeof(mi)};
    GetMonitorInfoW(MonitorFromRect(&anchor, MONITOR_DEFAULTTONEAREST), &mi);
    RECT work = mi.rcWork;
    int gap = static_cast<int>(4 * scale_);
    int x = anchor.left - static_cast<int>(alignX * scale_);
    int y = anchor.bottom + gap;
    if (y + h > work.bottom) y = anchor.top - h - gap;
    x = std::clamp<int>(x, work.left, std::max<int>(work.left, static_cast<int>(work.right) - w));
    y = std::max<int>(y, work.top);
    // 描く準備をしてから見せる（作りかけの中身が一瞬見えないように）
    SetWindowPos(hwnd_, HWND_TOPMOST, x, y, w, h, SWP_NOACTIVATE | SWP_NOREDRAW);
    if (!EnsureTarget(w, h)) return;
    SetWindowPos(hwnd_, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOACTIVATE | SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW);
    Redraw();
}

bool PopupWindow::EnsureTarget(int w, int h) {
    if (target_) {
        target_->Resize(D2D1::SizeU(w, h));
    } else if (FAILED(d2d_->CreateHwndRenderTarget(D2D1::RenderTargetProperties(), D2D1::HwndRenderTargetProperties(hwnd_, D2D1::SizeU(w, h)),
                                                   &target_))) {
        return false;
    }
    target_->SetDpi(96.0f * scale_, 96.0f * scale_);
    return true;
}

void PopupWindow::Hide() {
    if (hwnd_) {
        KillTimer(hwnd_, 1);
        ShowWindow(hwnd_, SW_HIDE);
    }
}

void PopupWindow::Redraw() {
    if (hwnd_) InvalidateRect(hwnd_, nullptr, FALSE);
}

void PopupWindow::StartTimer(UINT ms) {
    if (!hwnd_) return;
    KillTimer(hwnd_, 1);
    if (ms > 0) SetTimer(hwnd_, 1, ms, nullptr);
}

void PopupWindow::OnPaint() {
    PAINTSTRUCT ps;
    BeginPaint(hwnd_, &ps);
    // スリープ明けなどで描画先をなくしていたら作り直す
    if (!target_) {
        RECT rc;
        GetClientRect(hwnd_, &rc);
        EnsureTarget(rc.right - rc.left, rc.bottom - rc.top);
    }
    if (target_) {
        target_->BeginDraw();
        target_->Clear(background_);
        Paint(target_.Get());
        if (target_->EndDraw() == D2DERR_RECREATE_TARGET) target_.Reset();
    }
    EndPaint(hwnd_, &ps);
}

ComPtr<IDWriteTextLayout> PopupWindow::Layout(const std::wstring& text, float size, DWRITE_FONT_WEIGHT weight) {
    ComPtr<IDWriteTextFormat> format;
    ComPtr<IDWriteTextLayout> layout;
    if (FAILED(dwrite_->CreateTextFormat(kFont, nullptr, weight, DWRITE_FONT_STYLE_NORMAL, DWRITE_FONT_STRETCH_NORMAL, size, L"ja-jp", &format))) {
        return nullptr;
    }
    format->SetWordWrapping(DWRITE_WORD_WRAPPING_NO_WRAP);
    dwrite_->CreateTextLayout(text.c_str(), static_cast<UINT32>(text.size()), format.Get(), 4000.0f, 200.0f, &layout);
    return layout;
}

void PopupWindow::DrawLayout(ID2D1RenderTarget* rt, IDWriteTextLayout* layout, float x, float y, const D2D1_COLOR_F& color) {
    if (!layout) return;
    ComPtr<ID2D1SolidColorBrush> brush;
    if (FAILED(rt->CreateSolidColorBrush(color, &brush))) return;
    // 絵文字を色付きで描く（Windows 8.1 以降）
    rt->DrawTextLayout(D2D1::Point2F(x, y), layout, brush.Get(), D2D1_DRAW_TEXT_OPTIONS_ENABLE_COLOR_FONT);
}

LRESULT CALLBACK PopupWindow::Proc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    auto* self = reinterpret_cast<PopupWindow*>(GetWindowLongPtrW(hwnd, GWLP_USERDATA));
    switch (msg) {
        case WM_MOUSEACTIVATE:
            // 押しても前面にしない（アプリの入力欄からフォーカスを奪わない）。左ボタンなら、ここで選んで、クリックのメッセージは捨てる。
            // IMM のアプリでは、クリックのメッセージを見たシステムが（この窓宛てでも）入力中の文字を確定させてしまうため
            if (self && HIWORD(lParam) == WM_LBUTTONDOWN) {
                // 押した時点の位置（今のカーソルの位置ではなく）
                DWORD pos = GetMessagePos();
                POINT pt = {GET_X_LPARAM(pos), GET_Y_LPARAM(pos)};
                ScreenToClient(hwnd, &pt);
                self->OnClick(pt.x / self->scale_, pt.y / self->scale_);
                return MA_NOACTIVATEANDEAT;
            }
            return MA_NOACTIVATE;
        case WM_ERASEBKGND:
            return 1;  // 背景は描くときに塗る（ちらつかないように）
        case WM_PAINT:
            if (self) {
                self->OnPaint();
                return 0;
            }
            break;
        case WM_MOUSEMOVE:
            if (self) {
                TRACKMOUSEEVENT tme = {sizeof(tme), TME_LEAVE, hwnd, 0};
                TrackMouseEvent(&tme);
                self->OnHover(GET_X_LPARAM(lParam) / self->scale_, GET_Y_LPARAM(lParam) / self->scale_);
            }
            return 0;
        case WM_MOUSELEAVE:
            if (self) self->OnHover(-1, -1);
            return 0;
        case WM_TIMER:
            if (self && wParam == 1) {
                KillTimer(hwnd, 1);
                self->OnTimer();
            }
            return 0;
        default:
            break;
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

void CandidateWindow::Show(std::vector<std::wstring> items, std::vector<std::wstring> labels, int selected, const RECT& anchor) {
    items_ = std::move(items);
    labels_ = std::move(labels);
    selected_ = std::clamp(selected, 0, std::max(0, static_cast<int>(items_.size()) - 1));
    hover_ = -1;
    if (items_.empty()) {
        Hide();
        return;
    }
    // 候補の文字の書き始めを、入力中の文字の左端にそろえる
    ShowAt(anchor, kTextX);
}

D2D1_SIZE_F CandidateWindow::Measure() {
    float textW = 0, labelW = 0;
    for (int i = 0; i < pageCount(); i++) {
        int index = pageStart() + i;
        DWRITE_TEXT_METRICS m;
        if (auto l = Layout(items_[index], kTextSize); l && SUCCEEDED(l->GetMetrics(&m))) textW = std::max(textW, m.widthIncludingTrailingWhitespace);
        if (index < static_cast<int>(labels_.size()) && !labels_[index].empty()) {
            if (auto l = Layout(labels_[index], kSmallSize); l && SUCCEEDED(l->GetMetrics(&m))) labelW = std::max(labelW, m.width);
        }
    }
    float w = kTextX + textW + (labelW > 0 ? 20 + labelW : 0) + 14;
    float footer = items_.size() > kPage ? kFooter : 0;
    return D2D1::SizeF(std::max(w, 140.0f), kPad * 2 + pageCount() * kRow + footer);
}

void CandidateWindow::Paint(ID2D1RenderTarget* rt) {
    D2D1_SIZE_F size = rt->GetSize();
    ComPtr<ID2D1SolidColorBrush> brush;
    if (FAILED(rt->CreateSolidColorBrush(selection_, &brush))) return;
    for (int i = 0; i < pageCount(); i++) {
        int index = pageStart() + i;
        float top = kPad + i * kRow;
        D2D1_RECT_F row = D2D1::RectF(kPad, top + 1, size.width - kPad, top + kRow - 1);
        if (index == selected_ || index == hover_) {
            brush->SetColor(selection_);
            brush->SetOpacity(index == selected_ ? 1.0f : 0.6f);
            rt->FillRoundedRectangle(D2D1::RoundedRect(row, 4, 4), brush.Get());
            brush->SetOpacity(1.0f);
        }
        if (index == selected_) {
            // 選んでいる行の左の印（Windows 11 の一覧と同じ）
            brush->SetColor(accent_);
            rt->FillRoundedRectangle(D2D1::RoundedRect(D2D1::RectF(kPad + 1, top + kRow / 2 - 8, kPad + 4, top + kRow / 2 + 8), 1.5f, 1.5f),
                                     brush.Get());
        }
        DWRITE_TEXT_METRICS m;
        auto number = Layout(std::to_wstring(i + 1), kSmallSize);
        if (number && SUCCEEDED(number->GetMetrics(&m))) DrawLayout(rt, number.Get(), kNumberX, top + (kRow - m.height) / 2, secondary_);
        auto text = Layout(items_[index], kTextSize);
        if (text && SUCCEEDED(text->GetMetrics(&m))) DrawLayout(rt, text.Get(), kTextX, top + (kRow - m.height) / 2, text_);
        if (index < static_cast<int>(labels_.size()) && !labels_[index].empty()) {
            auto label = Layout(labels_[index], kSmallSize);
            if (label && SUCCEEDED(label->GetMetrics(&m))) {
                DrawLayout(rt, label.Get(), size.width - 14 - m.width, top + (kRow - m.height) / 2, secondary_);
            }
        }
    }
    if (items_.size() > kPage) {
        std::wstring page = std::to_wstring(selected_ + 1) + L"/" + std::to_wstring(items_.size());
        auto footer = Layout(page, kSmallSize);
        DWRITE_TEXT_METRICS m;
        if (footer && SUCCEEDED(footer->GetMetrics(&m))) {
            DrawLayout(rt, footer.Get(), size.width - 14 - m.width, kPad + pageCount() * kRow + (kFooter - m.height) / 2, secondary_);
        }
    }
}

int CandidateWindow::RowAt(float y) const {
    if (y < kPad) return -1;
    int row = static_cast<int>((y - kPad) / kRow);
    return row < pageCount() ? pageStart() + row : -1;
}

void CandidateWindow::OnClick(float, float y) {
    int index = RowAt(y);
    if (index >= 0 && onSelect_) onSelect_(index);
}

void CandidateWindow::OnHover(float, float y) {
    int index = y < 0 ? -1 : RowAt(y);
    if (index != hover_) {
        hover_ = index;
        Redraw();
    }
}

void IndicatorWindow::Show(const std::wstring& text, UINT durationMs, const RECT& anchor) {
    message_ = text;
    ShowAt(anchor, 0);
    StartTimer(durationMs);
}

D2D1_SIZE_F IndicatorWindow::Measure() {
    DWRITE_TEXT_METRICS m = {};
    if (auto l = Layout(message_, 16, DWRITE_FONT_WEIGHT_SEMI_BOLD)) l->GetMetrics(&m);
    return D2D1::SizeF(std::max(36.0f, m.widthIncludingTrailingWhitespace + 24), 36);
}

void IndicatorWindow::Paint(ID2D1RenderTarget* rt) {
    D2D1_SIZE_F size = rt->GetSize();
    auto l = Layout(message_, 16, DWRITE_FONT_WEIGHT_SEMI_BOLD);
    DWRITE_TEXT_METRICS m;
    // 「あ」「A」はアクセントの色、音声入力の様子はふつうの文字の色
    bool mode = message_ == L"あ" || message_ == L"A";
    if (l && SUCCEEDED(l->GetMetrics(&m))) DrawLayout(rt, l.Get(), (size.width - m.width) / 2, (size.height - m.height) / 2, mode ? accent_ : text_);
}

}  // namespace kanade
