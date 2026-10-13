// 候補の窓と、カーソルの近くの小さな表示（「あ」「A」、音声入力の様子）。DLL がアプリのプロセスの中で描く（MS-IME と同じ作り）。
// Direct2D と DirectWrite で描き、角を丸めて影を付ける（Windows 11 の見た目）。触っても前面にならない窓にして、
// アプリの入力欄からフォーカスを奪わない（候補はクリックで選べる）
#pragma once
#include <windows.h>
#include <d2d1.h>
#include <dwrite.h>
#include <wrl/client.h>

#include <functional>
#include <string>
#include <vector>

namespace kanade {

using Microsoft::WRL::ComPtr;

class PopupWindow {
public:
    virtual ~PopupWindow();
    void Hide();
    bool visible() const { return hwnd_ && IsWindowVisible(hwnd_); }

protected:
    // 窓の大きさ（DIP）を返し、描く
    virtual D2D1_SIZE_F Measure() = 0;
    virtual void Paint(ID2D1RenderTarget* rt) = 0;
    virtual void OnClick(float, float) {}
    virtual void OnHover(float, float) {}
    virtual void OnTimer() {}

    // [anchor]（画面の座標）の下に出す。はみ出すなら上に。[alignX] は窓の左端から、文字の書き始めまでの距離（DIP）
    void ShowAt(const RECT& anchor, float alignX);
    void Redraw();
    float scale() const { return scale_; }

    // 色（Windows のライト/ダークとアクセントの色に合わせる）
    bool dark_ = false;
    D2D1_COLOR_F background_ = {}, text_ = {}, secondary_ = {}, selection_ = {}, accent_ = {};
    ComPtr<IDWriteFactory> dwrite_;
    ComPtr<ID2D1Factory> d2d_;
    HWND hwnd_ = nullptr;

    ComPtr<IDWriteTextLayout> Layout(const std::wstring& text, float size, DWRITE_FONT_WEIGHT weight = DWRITE_FONT_WEIGHT_NORMAL);
    void DrawLayout(ID2D1RenderTarget* rt, IDWriteTextLayout* layout, float x, float y, const D2D1_COLOR_F& color);
    void StartTimer(UINT ms);

private:
    bool Ensure();
    void OnPaint();
    static LRESULT CALLBACK Proc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam);

    ComPtr<ID2D1HwndRenderTarget> target_;
    float scale_ = 1.0f;
};

// 候補の窓（9件ずつのページ。番号・候補・説明。選んでいる行に印）
class CandidateWindow : public PopupWindow {
public:
    explicit CandidateWindow(std::function<void(int)> onSelect) : onSelect_(std::move(onSelect)) {}
    void Show(std::vector<std::wstring> items, std::vector<std::wstring> labels, int selected, const RECT& anchor);

protected:
    D2D1_SIZE_F Measure() override;
    void Paint(ID2D1RenderTarget* rt) override;
    void OnClick(float x, float y) override;
    void OnHover(float x, float y) override;

private:
    int RowAt(float y) const;
    int pageStart() const { return selected_ < 0 ? 0 : selected_ / kPage * kPage; }
    int pageCount() const { return std::min<int>(kPage, static_cast<int>(items_.size()) - pageStart()); }

    static constexpr int kPage = 9;
    std::vector<std::wstring> items_, labels_;
    int selected_ = 0;
    int hover_ = -1;
    std::function<void(int)> onSelect_;
};

// カーソルの近くの小さな表示（「あ」「A」は少しのあいだ、音声入力の様子は消すまで）
class IndicatorWindow : public PopupWindow {
public:
    void Show(const std::wstring& text, UINT durationMs, const RECT& anchor);

protected:
    D2D1_SIZE_F Measure() override;
    void Paint(ID2D1RenderTarget* rt) override;
    void OnTimer() override { Hide(); }

private:
    std::wstring message_;
};

}  // namespace kanade
