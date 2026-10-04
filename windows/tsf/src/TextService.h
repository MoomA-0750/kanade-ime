// TSF のテキストサービス本体。キーはエンジンに渡し、返ってきた命令（入力中の文字・確定・モード）を書くだけ
#pragma once
#include <windows.h>
#include <msctf.h>
#include <wrl/client.h>

#include <functional>
#include <memory>
#include <string>
#include <vector>

#include "EngineClient.h"

namespace kanade {

using Microsoft::WRL::ComPtr;

class TextService : public ITfTextInputProcessorEx,
                    public ITfThreadMgrEventSink,
                    public ITfThreadFocusSink,
                    public ITfKeyEventSink,
                    public ITfCompositionSink,
                    public ITfCompartmentEventSink,
                    public ITfDisplayAttributeProvider {
public:
    TextService();
    ~TextService();

    // IUnknown
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override;
    STDMETHODIMP_(ULONG) AddRef() override;
    STDMETHODIMP_(ULONG) Release() override;

    // ITfTextInputProcessor(Ex)
    STDMETHODIMP Activate(ITfThreadMgr* threadMgr, TfClientId clientId) override;
    STDMETHODIMP ActivateEx(ITfThreadMgr* threadMgr, TfClientId clientId, DWORD flags) override;
    STDMETHODIMP Deactivate() override;

    // ITfThreadMgrEventSink
    STDMETHODIMP OnInitDocumentMgr(ITfDocumentMgr*) override { return S_OK; }
    STDMETHODIMP OnUninitDocumentMgr(ITfDocumentMgr*) override { return S_OK; }
    STDMETHODIMP OnSetFocus(ITfDocumentMgr* focus, ITfDocumentMgr* prevFocus) override;
    STDMETHODIMP OnPushContext(ITfContext*) override { return S_OK; }
    STDMETHODIMP OnPopContext(ITfContext*) override { return S_OK; }

    // ITfThreadFocusSink（アプリをまたいだフォーカスの出入り）
    STDMETHODIMP OnSetThreadFocus() override;
    STDMETHODIMP OnKillThreadFocus() override;

    // ITfKeyEventSink
    STDMETHODIMP OnSetFocus(BOOL) override { return S_OK; }
    STDMETHODIMP OnTestKeyDown(ITfContext* context, WPARAM wParam, LPARAM lParam, BOOL* eaten) override;
    STDMETHODIMP OnTestKeyUp(ITfContext* context, WPARAM wParam, LPARAM lParam, BOOL* eaten) override;
    STDMETHODIMP OnKeyDown(ITfContext* context, WPARAM wParam, LPARAM lParam, BOOL* eaten) override;
    STDMETHODIMP OnKeyUp(ITfContext* context, WPARAM wParam, LPARAM lParam, BOOL* eaten) override;
    STDMETHODIMP OnPreservedKey(ITfContext*, REFGUID, BOOL* eaten) override {
        *eaten = FALSE;
        return S_OK;
    }

    // ITfCompositionSink
    STDMETHODIMP OnCompositionTerminated(TfEditCookie ec, ITfComposition* composition) override;

    // ITfCompartmentEventSink（言語バーなど、キー以外での日本語のオン/オフ）
    STDMETHODIMP OnChange(REFGUID guid) override;

    // ITfDisplayAttributeProvider
    STDMETHODIMP EnumDisplayAttributeInfo(IEnumTfDisplayAttributeInfo** ppEnum) override;
    STDMETHODIMP GetDisplayAttributeInfo(REFGUID guid, ITfDisplayAttributeInfo** ppInfo) override;

    // メッセージだけの窓（エンジンから自分から届いたものを UI のスレッドで受ける）
    void OnPushMessage();

private:
    bool Connect();
    std::vector<std::string> KeyFields(WPARAM wParam, LPARAM lParam, bool down);
    bool ProcessKey(ITfContext* context, const std::vector<std::string>& fields, bool down, Reply* reply);
    BOOL TestKey(ITfContext* context, WPARAM wParam, LPARAM lParam, bool down);
    struct KeyId {
        std::vector<std::string> fields;
        bool operator==(const KeyId&) const = default;
    };
    KeyId MakeKeyId(WPARAM wParam, LPARAM lParam, bool down);
    void DropPending(ITfContext* context);
    void EndCompositionLater();
    static bool IsTransitory(ITfContext* context);
    static void Resend(WPARAM wParam, LPARAM lParam);
    void HandleKey(ITfContext* context, WPARAM wParam, LPARAM lParam, bool down, BOOL* eaten);
    void SendContext(ITfContext* context);
    void SendSelectionCaret(ITfContext* context);
    void SendRect(TfEditCookie ec, ITfContext* context, ITfRange* range);
    HRESULT EditSync(ITfContext* context, DWORD flags, std::function<HRESULT(TfEditCookie)> fn);
    void ApplyCommands(ITfContext* context, std::vector<Command> commands, bool sync);
    void InitDisplayAttributes();

public:
    void SetMode(bool japanese);

private:

    // 書き込み（編集セッションの中で呼ぶ。Composition.cpp）
    void ApplyInSession(TfEditCookie ec, ITfContext* context, const std::vector<Command>& commands);
    HRESULT StartComposition(TfEditCookie ec, ITfContext* context);
    void SetComposingText(TfEditCookie ec, ITfContext* context, const std::wstring& text, int cursor, int focused,
                          const std::vector<int>& segments);
    void Commit(TfEditCookie ec, ITfContext* context, const std::wstring& text);
    void Replace(TfEditCookie ec, ITfContext* context, const std::wstring& expected, const std::wstring& typed,
                 const std::wstring& replacement);
    void EndComposition(TfEditCookie ec);
    void SendCaret(TfEditCookie ec, ITfContext* context);
    static void SetSelectionAt(TfEditCookie ec, ITfContext* context, ITfRange* range, TfAnchor anchor);

    // OnTestKeyDown で処理して、OnKeyDown で書くのを待っているキー
    struct PendingKey {
        bool valid = false;
        KeyId id;
        Reply reply;
    };
    PendingKey pending_;
    // 処理したキーの通し番号（エンジンから自分から届いた入力中の文字が古いかを見る）
    unsigned long long keySeq_ = 0;

    LONG refs_ = 1;
    ComPtr<ITfThreadMgr> threadMgr_;
    TfClientId clientId_ = TF_CLIENTID_NULL;
    DWORD threadMgrCookie_ = TF_INVALID_COOKIE;
    DWORD threadFocusCookie_ = TF_INVALID_COOKIE;
    DWORD compartmentCookie_ = TF_INVALID_COOKIE;
    ComPtr<ITfComposition> composition_;
    ComPtr<ITfContext> compositionContext_;
    std::unique_ptr<EngineClient> engine_;
    HWND window_ = nullptr;
    bool japanese_ = false;
    bool settingCompartment_ = false;
    // 候補の窓を出す位置（選んでいる文節。変換中でなければ入力中の文字の先頭）
    LONG anchorOffset_ = 0;
    LONG anchorLength_ = 0;
    TfGuidAtom atomInput_ = TF_INVALID_GUIDATOM;
    TfGuidAtom atomFocused_ = TF_INVALID_GUIDATOM;
    TfGuidAtom atomConverted_ = TF_INVALID_GUIDATOM;
};

}  // namespace kanade
