#include "TextService.h"

#include "DisplayAttributes.h"
#include "EngineLauncher.h"
#include "Globals.h"
#include "Log.h"

namespace kanade {

namespace {

constexpr UINT WM_KANADE_PUSH = WM_APP + 1;
constexpr wchar_t kWindowClass[] = L"KanadeTsfMessageWindow";
// エンジンの返事を待つ長さ。これを過ぎたらキーを素通しにする（アプリを固めない）
constexpr DWORD kTimeoutMs = 300;

class EditSession : public ITfEditSession {
public:
    explicit EditSession(std::function<HRESULT(TfEditCookie)> fn) : fn_(std::move(fn)) {}
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (riid == IID_IUnknown || riid == IID_ITfEditSession) {
            *ppv = static_cast<ITfEditSession*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    STDMETHODIMP_(ULONG) AddRef() override { return InterlockedIncrement(&refs_); }
    STDMETHODIMP_(ULONG) Release() override {
        LONG n = InterlockedDecrement(&refs_);
        if (n == 0) delete this;
        return n;
    }
    STDMETHODIMP DoEditSession(TfEditCookie ec) override {
        try {
            return fn_(ec);
        } catch (...) {
            Log("exception in edit session");
            return E_FAIL;
        }
    }

private:
    LONG refs_ = 1;
    std::function<HRESULT(TfEditCookie)> fn_;
};

LRESULT CALLBACK WindowProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    if (msg == WM_KANADE_PUSH) {
        if (auto* self = reinterpret_cast<TextService*>(GetWindowLongPtrW(hwnd, GWLP_USERDATA))) self->OnPushMessage();
        return 0;
    }
    return DefWindowProcW(hwnd, msg, wParam, lParam);
}

bool IsDown(int vk) { return (GetKeyState(vk) & 0x8000) != 0; }

}  // namespace

TextService::TextService() { DllAddRef(); }

TextService::~TextService() { DllRelease(); }

STDMETHODIMP TextService::QueryInterface(REFIID riid, void** ppv) {
    if (!ppv) return E_INVALIDARG;
    *ppv = nullptr;
    if (riid == IID_IUnknown || riid == IID_ITfTextInputProcessor || riid == IID_ITfTextInputProcessorEx) {
        *ppv = static_cast<ITfTextInputProcessorEx*>(this);
    } else if (riid == IID_ITfThreadMgrEventSink) {
        *ppv = static_cast<ITfThreadMgrEventSink*>(this);
    } else if (riid == IID_ITfThreadFocusSink) {
        *ppv = static_cast<ITfThreadFocusSink*>(this);
    } else if (riid == IID_ITfKeyEventSink) {
        *ppv = static_cast<ITfKeyEventSink*>(this);
    } else if (riid == IID_ITfCompositionSink) {
        *ppv = static_cast<ITfCompositionSink*>(this);
    } else if (riid == IID_ITfCompartmentEventSink) {
        *ppv = static_cast<ITfCompartmentEventSink*>(this);
    } else if (riid == IID_ITfDisplayAttributeProvider) {
        *ppv = static_cast<ITfDisplayAttributeProvider*>(this);
    }
    if (!*ppv) return E_NOINTERFACE;
    AddRef();
    return S_OK;
}

STDMETHODIMP_(ULONG) TextService::AddRef() { return InterlockedIncrement(&refs_); }

STDMETHODIMP_(ULONG) TextService::Release() {
    LONG n = InterlockedDecrement(&refs_);
    if (n == 0) delete this;
    return n;
}

STDMETHODIMP TextService::Activate(ITfThreadMgr* threadMgr, TfClientId clientId) { return ActivateEx(threadMgr, clientId, 0); }

STDMETHODIMP TextService::ActivateEx(ITfThreadMgr* threadMgr, TfClientId clientId, DWORD) {
    Log("activate");
    threadMgr_ = threadMgr;
    clientId_ = clientId;
    InitDisplayAttributes();

    WNDCLASSEXW wc = {sizeof(wc)};
    wc.lpfnWndProc = WindowProc;
    wc.hInstance = g_module;
    wc.lpszClassName = kWindowClass;
    RegisterClassExW(&wc);  // 2回目以降は失敗するが、登録済みなのでよい
    window_ = CreateWindowExW(0, kWindowClass, L"", 0, 0, 0, 0, 0, HWND_MESSAGE, nullptr, g_module, nullptr);
    if (window_) SetWindowLongPtrW(window_, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(this));

    ComPtr<ITfSource> source;
    if (SUCCEEDED(threadMgr_.As(&source))) {
        source->AdviseSink(IID_ITfThreadMgrEventSink, static_cast<ITfThreadMgrEventSink*>(this), &threadMgrCookie_);
        source->AdviseSink(IID_ITfThreadFocusSink, static_cast<ITfThreadFocusSink*>(this), &threadFocusCookie_);
    }
    ComPtr<ITfKeystrokeMgr> keystrokes;
    if (SUCCEEDED(threadMgr_.As(&keystrokes))) keystrokes->AdviseKeyEventSink(clientId_, static_cast<ITfKeyEventSink*>(this), TRUE);

    ComPtr<ITfCompartmentMgr> compartments;
    ComPtr<ITfCompartment> openClose;
    if (SUCCEEDED(threadMgr_.As(&compartments)) &&
        SUCCEEDED(compartments->GetCompartment(GUID_COMPARTMENT_KEYBOARD_OPENCLOSE, &openClose))) {
        ComPtr<ITfSource> compartmentSource;
        if (SUCCEEDED(openClose.As(&compartmentSource))) {
            compartmentSource->AdviseSink(IID_ITfCompartmentEventSink, static_cast<ITfCompartmentEventSink*>(this), &compartmentCookie_);
        }
    }

    HWND window = window_;
    engine_ = std::make_unique<EngineClient>([window] {
        if (window) PostMessageW(window, WM_KANADE_PUSH, 0, 0);
    });
    Connect();
    return S_OK;
}

STDMETHODIMP TextService::Deactivate() {
    Log("deactivate");
    if (engine_) {
        engine_->Send({"bye"});
        engine_->Close();  // 読み取りのスレッドを止めてから窓を壊す（窓への投げ込みが残らないように）
        engine_.reset();
    }
    if (window_) {
        DestroyWindow(window_);
        window_ = nullptr;
    }
    ComPtr<ITfSource> source;
    if (threadMgr_ && SUCCEEDED(threadMgr_.As(&source))) {
        if (threadMgrCookie_ != TF_INVALID_COOKIE) source->UnadviseSink(threadMgrCookie_);
        if (threadFocusCookie_ != TF_INVALID_COOKIE) source->UnadviseSink(threadFocusCookie_);
    }
    threadMgrCookie_ = threadFocusCookie_ = TF_INVALID_COOKIE;
    ComPtr<ITfKeystrokeMgr> keystrokes;
    if (threadMgr_ && SUCCEEDED(threadMgr_.As(&keystrokes))) keystrokes->UnadviseKeyEventSink(clientId_);
    if (compartmentCookie_ != TF_INVALID_COOKIE) {
        ComPtr<ITfCompartmentMgr> compartments;
        ComPtr<ITfCompartment> openClose;
        ComPtr<ITfSource> compartmentSource;
        if (SUCCEEDED(threadMgr_.As(&compartments)) &&
            SUCCEEDED(compartments->GetCompartment(GUID_COMPARTMENT_KEYBOARD_OPENCLOSE, &openClose)) &&
            SUCCEEDED(openClose.As(&compartmentSource))) {
            compartmentSource->UnadviseSink(compartmentCookie_);
        }
        compartmentCookie_ = TF_INVALID_COOKIE;
    }
    composition_.Reset();
    compositionContext_.Reset();
    threadMgr_.Reset();
    clientId_ = TF_CLIENTID_NULL;
    return S_OK;
}

bool TextService::Connect() {
    if (!engine_) return false;
    EngineClient::State state = engine_->EnsureConnected();
    if (state == EngineClient::State::NewlyConnected) engine_->Send({"focus", "1"});
    if (state == EngineClient::State::Failed) MaybeLaunchEngine();
    return state != EngineClient::State::Failed;
}

STDMETHODIMP TextService::OnSetFocus(ITfDocumentMgr* focus, ITfDocumentMgr*) {
    // 別の入力欄へ移ったら、入力中の文字はそのまま確定させる（エンジンの入力は捨てる）
    if (composition_ && compositionContext_) {
        ComPtr<ITfContext> context = compositionContext_;
        ComPtr<ITfTextInputProcessorEx> self(this);
        auto* session = new EditSession([this, self](TfEditCookie ec) {
            EndComposition(ec);
            return S_OK;
        });
        HRESULT hr;
        context->RequestEditSession(clientId_, session, TF_ES_ASYNCDONTCARE | TF_ES_READWRITE, &hr);
        session->Release();
        if (engine_) engine_->Send({"reset"});
    }
    if (engine_ && engine_->connected()) engine_->Send({"focus", focus ? "1" : "0"});
    return S_OK;
}

STDMETHODIMP TextService::OnSetThreadFocus() {
    if (Connect()) engine_->Send({"focus", "1"});
    return S_OK;
}

STDMETHODIMP TextService::OnKillThreadFocus() {
    if (engine_ && engine_->connected()) engine_->Send({"focus", "0"});
    return S_OK;
}

std::vector<std::string> TextService::KeyFields(WPARAM wParam, LPARAM lParam, bool down) {
    int vk = static_cast<int>(wParam);
    // 右 Alt を左と分ける（拡張キーの印）
    if (vk == VK_MENU && (lParam & (1 << 24))) vk = VK_RMENU;
    int mods = (IsDown(VK_SHIFT) ? 1 : 0) | (IsDown(VK_CONTROL) ? 2 : 0) | (IsDown(VK_MENU) ? 4 : 0) |
               (IsDown(VK_LWIN) || IsDown(VK_RWIN) ? 8 : 0);
    int ch = 0;
    if (down) {
        BYTE state[256];
        if (GetKeyboardState(state)) {
            wchar_t buf[4] = {};
            UINT scan = (lParam >> 16) & 0xFF;
            // 4: キーボードの状態（デッドキー）を変えない（Windows 10 1607 以降）
            int n = ToUnicodeEx(static_cast<UINT>(wParam), scan, state, buf, 4, 4, GetKeyboardLayout(0));
            if (n == 1) ch = buf[0];
        }
    }
    bool repeat = down && (lParam & (1 << 30));
    return {down ? "d" : "u", std::to_string(vk), std::to_string(ch), std::to_string(mods), repeat ? "1" : "0"};
}

STDMETHODIMP TextService::OnTestKeyDown(ITfContext*, WPARAM wParam, LPARAM lParam, BOOL* eaten) {
    *eaten = FALSE;
    try {
        Reply reply;
        if (Connect() && engine_->Request("test", KeyFields(wParam, lParam, true), kTimeoutMs, &reply)) *eaten = reply.consumed;
        Log("OnTestKeyDown vk=%d lp=%08llx eaten=%d", static_cast<int>(wParam), static_cast<unsigned long long>(lParam), *eaten);
    } catch (...) {
    }
    return S_OK;
}

STDMETHODIMP TextService::OnTestKeyUp(ITfContext*, WPARAM wParam, LPARAM lParam, BOOL* eaten) {
    *eaten = FALSE;
    try {
        Reply reply;
        if (Connect() && engine_->Request("test", KeyFields(wParam, lParam, false), kTimeoutMs, &reply)) *eaten = reply.consumed;
        Log("OnTestKeyUp vk=%d lp=%08llx eaten=%d", static_cast<int>(wParam), static_cast<unsigned long long>(lParam), *eaten);
    } catch (...) {
    }
    return S_OK;
}

STDMETHODIMP TextService::OnKeyDown(ITfContext* context, WPARAM wParam, LPARAM lParam, BOOL* eaten) {
    *eaten = FALSE;
    try {
        KeyRequest("key", context, wParam, lParam, true, eaten);
    } catch (...) {
        Log("exception in OnKeyDown");
    }
    return S_OK;
}

STDMETHODIMP TextService::OnKeyUp(ITfContext* context, WPARAM wParam, LPARAM lParam, BOOL* eaten) {
    *eaten = FALSE;
    try {
        KeyRequest("key", context, wParam, lParam, false, eaten);
    } catch (...) {
        Log("exception in OnKeyUp");
    }
    return S_OK;
}

bool TextService::KeyRequest(const char* type, ITfContext* context, WPARAM wParam, LPARAM lParam, bool down, BOOL* eaten) {
    if (!Connect()) return false;
    std::vector<std::string> fields = KeyFields(wParam, lParam, down);
    // 入力を始める打鍵なら、先にカーソルの前後の文を送る（zenz の文脈）
    if (down && japanese_ && !composition_ && fields[2] != "0" && context) SendContext(context);
    Reply reply;
    if (!engine_->Request(type, fields, kTimeoutMs, &reply)) return false;
    Log("%s %s vk=%s char=%s -> %zu commands, eaten=%d", type, down ? "down" : "up", fields[1].c_str(), fields[2].c_str(),
        reply.commands.size(), reply.consumed);
    if (!reply.commands.empty()) ApplyCommands(context, std::move(reply.commands), true);
    *eaten = reply.consumed;
    return true;
}

void TextService::SendContext(ITfContext* context) {
    std::wstring left, right;
    EditSync(context, TF_ES_READ, [&](TfEditCookie ec) {
        TF_SELECTION sel;
        ULONG fetched = 0;
        if (FAILED(context->GetSelection(ec, TF_DEFAULT_SELECTION, 1, &sel, &fetched)) || fetched == 0) return S_OK;
        ComPtr<ITfRange> range;
        range.Attach(sel.range);
        wchar_t buf[64];
        ULONG n = 0;
        ComPtr<ITfRange> before;
        if (SUCCEEDED(range->Clone(&before))) {
            LONG shifted = 0;
            before->Collapse(ec, TF_ANCHOR_START);
            before->ShiftStart(ec, -40, &shifted, nullptr);
            if (SUCCEEDED(before->GetText(ec, 0, buf, 40, &n))) left.assign(buf, n);
        }
        ComPtr<ITfRange> after;
        if (SUCCEEDED(range->Clone(&after))) {
            LONG shifted = 0;
            after->Collapse(ec, TF_ANCHOR_END);
            after->ShiftEnd(ec, 40, &shifted, nullptr);
            if (SUCCEEDED(after->GetText(ec, 0, buf, 40, &n))) right.assign(buf, n);
        }
        return S_OK;
    });
    engine_->Send({"ctx", ToUtf8(left), ToUtf8(right)});
}

HRESULT TextService::EditSync(ITfContext* context, DWORD flags, std::function<HRESULT(TfEditCookie)> fn) {
    HRESULT hr = E_FAIL;
    auto* session = new EditSession(std::move(fn));
    context->RequestEditSession(clientId_, session, TF_ES_SYNC | flags, &hr);
    session->Release();
    return hr;
}

void TextService::ApplyCommands(ITfContext* context, std::vector<Command> commands, bool sync) {
    // 文字は編集セッションで書き、モードはそのあとで切り替える。先に切り替えると、IMM のアプリ（EDIT など）では
    // 入力中の文字をシステムが確定させ、そのあとの確定と2重になる
    std::vector<Command> edits;
    int mode = -1;
    for (auto& c : commands) {
        if (c[0] == "mode") {
            mode = c.size() > 1 && c[1] == "1";
        } else {
            edits.push_back(std::move(c));
        }
    }
    struct ModeAfter {
        TextService* self;
        int mode;
        ~ModeAfter() {
            if (mode >= 0) self->SetMode(mode == 1);
        }
    } modeAfter{this, mode};
    if (edits.empty() || !context) return;
    ComPtr<ITfContext> ctx = context;
    ComPtr<ITfTextInputProcessorEx> self(this);  // 非同期のとき、書く前に消えないように
    auto* session = new EditSession([this, self, ctx, edits](TfEditCookie ec) {
        ApplyInSession(ec, ctx.Get(), edits);
        return S_OK;
    });
    HRESULT hr = E_FAIL;
    context->RequestEditSession(clientId_, session, (sync ? TF_ES_SYNC : TF_ES_ASYNCDONTCARE) | TF_ES_READWRITE, &hr);
    session->Release();
    if (FAILED(hr)) Log("edit session failed %08lx", hr);
}

void TextService::OnPushMessage() {
    if (!engine_) return;
    for (auto& batch : engine_->TakePushes()) {
        ComPtr<ITfContext> context = compositionContext_;
        if (!context && threadMgr_) {
            ComPtr<ITfDocumentMgr> doc;
            if (SUCCEEDED(threadMgr_->GetFocus(&doc)) && doc) doc->GetTop(&context);
        }
        ApplyCommands(context.Get(), std::move(batch), false);
    }
}

void TextService::SetMode(bool japanese) {
    japanese_ = japanese;
    if (!threadMgr_) return;
    ComPtr<ITfCompartmentMgr> compartments;
    if (FAILED(threadMgr_.As(&compartments))) return;
    settingCompartment_ = true;
    ComPtr<ITfCompartment> openClose;
    if (SUCCEEDED(compartments->GetCompartment(GUID_COMPARTMENT_KEYBOARD_OPENCLOSE, &openClose))) {
        VARIANT v;
        v.vt = VT_I4;
        v.lVal = japanese ? TRUE : FALSE;
        openClose->SetValue(clientId_, &v);
    }
    ComPtr<ITfCompartment> conversion;
    if (SUCCEEDED(compartments->GetCompartment(GUID_COMPARTMENT_KEYBOARD_INPUTMODE_CONVERSION, &conversion))) {
        VARIANT v;
        v.vt = VT_I4;
        v.lVal = japanese ? (TF_CONVERSIONMODE_NATIVE | TF_CONVERSIONMODE_FULLSHAPE) : TF_CONVERSIONMODE_ALPHANUMERIC;
        conversion->SetValue(clientId_, &v);
    }
    settingCompartment_ = false;
}

STDMETHODIMP TextService::OnChange(REFGUID guid) {
    if (settingCompartment_ || guid != GUID_COMPARTMENT_KEYBOARD_OPENCLOSE || !threadMgr_) return S_OK;
    ComPtr<ITfCompartmentMgr> compartments;
    ComPtr<ITfCompartment> openClose;
    if (FAILED(threadMgr_.As(&compartments)) || FAILED(compartments->GetCompartment(GUID_COMPARTMENT_KEYBOARD_OPENCLOSE, &openClose))) {
        return S_OK;
    }
    VARIANT v;
    VariantInit(&v);
    if (FAILED(openClose->GetValue(&v)) || v.vt != VT_I4) return S_OK;
    bool on = v.lVal != 0;
    if (on != japanese_) {
        japanese_ = on;
        if (Connect()) engine_->Send({"mode", on ? "1" : "0"});
    }
    return S_OK;
}

STDMETHODIMP TextService::OnCompositionTerminated(TfEditCookie, ITfComposition*) {
    // アプリが入力中の文字を終わらせた（クリックなど）。文字はアプリに残るので、エンジンの入力は捨てる
    composition_.Reset();
    compositionContext_.Reset();
    if (engine_ && engine_->connected()) engine_->Send({"reset"});
    return S_OK;
}

void TextService::InitDisplayAttributes() {
    ComPtr<ITfCategoryMgr> categories;
    if (FAILED(CoCreateInstance(CLSID_TF_CategoryMgr, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&categories)))) return;
    categories->RegisterGUID(GUID_AttrInput, &atomInput_);
    categories->RegisterGUID(GUID_AttrFocused, &atomFocused_);
    categories->RegisterGUID(GUID_AttrConverted, &atomConverted_);
}

STDMETHODIMP TextService::EnumDisplayAttributeInfo(IEnumTfDisplayAttributeInfo** ppEnum) {
    if (!ppEnum) return E_INVALIDARG;
    *ppEnum = new DisplayAttributeEnum();
    return S_OK;
}

STDMETHODIMP TextService::GetDisplayAttributeInfo(REFGUID guid, ITfDisplayAttributeInfo** ppInfo) {
    if (!ppInfo) return E_INVALIDARG;
    *ppInfo = DisplayAttributeInfo::Create(guid);
    return *ppInfo ? S_OK : E_INVALIDARG;
}

}  // namespace kanade
