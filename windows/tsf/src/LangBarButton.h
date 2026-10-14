// タスクバーの入力の表示（言語バーの GUID_LBI_INPUTMODE のボタン）。MS-IME と同じ場所に「あ」「A」を出し、
// 左クリックで日本語のオン/オフ、右クリックでメニュー（設定・ユーザー辞書など）を出す
#pragma once
#include <windows.h>
#include <msctf.h>

#include <functional>
#include <string>

namespace kanade {

class LangBarButton : public ITfLangBarItemButton, public ITfSource {
public:
    // [onToggle] は左クリック、[onMenu] は右クリックのメニューで選んだもの（"mode-on"・"settings" など）
    LangBarButton(std::function<void()> onToggle, std::function<void(const std::string&)> onMenu);

    // IUnknown
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override;
    STDMETHODIMP_(ULONG) AddRef() override;
    STDMETHODIMP_(ULONG) Release() override;

    // ITfLangBarItem
    STDMETHODIMP GetInfo(TF_LANGBARITEMINFO* info) override;
    STDMETHODIMP GetStatus(DWORD* status) override;
    STDMETHODIMP Show(BOOL) override { return E_NOTIMPL; }
    STDMETHODIMP GetTooltipString(BSTR* tooltip) override;

    // ITfLangBarItemButton
    STDMETHODIMP OnClick(TfLBIClick click, POINT pt, const RECT* area) override;
    STDMETHODIMP InitMenu(ITfMenu* menu) override;
    STDMETHODIMP OnMenuSelect(UINT id) override;
    STDMETHODIMP GetIcon(HICON* icon) override;
    STDMETHODIMP GetText(BSTR* text) override;

    // ITfSource
    STDMETHODIMP AdviseSink(REFIID riid, IUnknown* sink, DWORD* cookie) override;
    STDMETHODIMP UnadviseSink(DWORD cookie) override;

    // 日本語のオン/オフが変わった（表示を描き直してもらう）
    void SetJapanese(bool japanese);
    // TextService が止まるとき。以後は TextService を呼ばない
    void Detach();

private:
    ~LangBarButton();
    void AddItems(const std::function<void(UINT, bool, bool, const wchar_t*)>& add) const;
    void ShowMenu(POINT pt);

    LONG refs_ = 1;
    bool japanese_ = false;
    ITfLangBarItemSink* sink_ = nullptr;
    std::function<void()> onToggle_;
    std::function<void(const std::string&)> onMenu_;
};

}  // namespace kanade
