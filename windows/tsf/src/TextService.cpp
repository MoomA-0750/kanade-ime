#include "TextService.h"

#include "DisplayAttributes.h"
#include "EngineLauncher.h"
#include "Globals.h"
#include "Log.h"

namespace kanade {

namespace {

constexpr UINT WM_KANADE_PUSH = WM_APP + 1;
// 窓のクラス名は DLL ごとに分ける（開発中に別の版の DLL が同じプロセスに読み込まれたとき、外れた DLL の窓の手続きを呼ばないように）
const wchar_t* WindowClassName() {
    static wchar_t name[64] = {};
    if (!name[0]) swprintf_s(name, L"KanadeTsfMessageWindow_%p", static_cast<void*>(g_module));
    return name;
}
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
    wc.lpszClassName = WindowClassName();
    RegisterClassExW(&wc);  // 2回目以降は失敗するが、登録済みなのでよい
    window_ = CreateWindowExW(0, WindowClassName(), L"", 0, 0, 0, 0, 0, HWND_MESSAGE, nullptr, g_module, nullptr);
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
    pending_ = {};
    if (composition_ && compositionContext_) {
        // 入力中の文字をそのまま確定させる（下線つきのまま残らないように）
        ComPtr<ITfContext> context = compositionContext_;
        EditSync(context.Get(), TF_ES_READWRITE, [this](TfEditCookie ec) {
            EndComposition(ec);
            return S_OK;
        });
    }
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
    pending_ = {};
    // 別の入力欄へ移ったら、入力中の文字はそのまま確定させる（エンジンの入力は捨てる）
    if (composition_ && compositionContext_) {
        ComPtr<ITfContext> context = compositionContext_;
        ComPtr<ITfComposition> captured = composition_;
        ComPtr<ITfTextInputProcessorEx> self(this);
        auto* session = new EditSession([this, self, captured](TfEditCookie ec) {
            // 後回しになっているあいだに新しい入力欄で入力が始まっていたら、そちらには触らない
            if (composition_.Get() == captured.Get()) EndComposition(ec);
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

// キーの扱い: OnTestKeyDown（アプリが「このキーを使うか」を聞く）の時点でエンジンに実際に処理させ、結果を取っておく。
// 使わないキーには、その場で正確に「使わない」と答える。IMM のアプリ（EDIT など）では、OnTestKeyDown で「使う」と答えたキーを
// OnKeyDown で「やっぱり使わない」と返すと、キーがアプリに届かずに消えるため。続く OnKeyDown では取っておいた結果を書く。
// OnTestKeyDown を呼ばずに OnKeyDown だけ呼ぶアプリ（RichEdit など）では、OnKeyDown で処理する

STDMETHODIMP TextService::OnTestKeyDown(ITfContext* context, WPARAM wParam, LPARAM lParam, BOOL* eaten) {
    *eaten = FALSE;
    try {
        *eaten = TestKey(context, wParam, lParam, true);
    } catch (...) {
        Log("exception in OnTestKeyDown");
    }
    return S_OK;
}

STDMETHODIMP TextService::OnTestKeyUp(ITfContext* context, WPARAM wParam, LPARAM lParam, BOOL* eaten) {
    *eaten = FALSE;
    try {
        *eaten = TestKey(context, wParam, lParam, false);
    } catch (...) {
        Log("exception in OnTestKeyUp");
    }
    return S_OK;
}

STDMETHODIMP TextService::OnKeyDown(ITfContext* context, WPARAM wParam, LPARAM lParam, BOOL* eaten) {
    *eaten = FALSE;
    try {
        HandleKey(context, wParam, lParam, true, eaten);
    } catch (...) {
        Log("exception in OnKeyDown");
    }
    return S_OK;
}

STDMETHODIMP TextService::OnKeyUp(ITfContext* context, WPARAM wParam, LPARAM lParam, BOOL* eaten) {
    *eaten = FALSE;
    try {
        HandleKey(context, wParam, lParam, false, eaten);
    } catch (...) {
        Log("exception in OnKeyUp");
    }
    return S_OK;
}

bool TextService::ProcessKey(ITfContext* context, const std::vector<std::string>& fields, bool down, Reply* reply) {
    if (!Connect()) return false;
    // 入力を始める打鍵なら、先にカーソルの前後の文を送る（zenz の文脈）
    if (down && japanese_ && !composition_ && fields[2] != "0" && context) SendContext(context);
    if (!engine_->Request("key", fields, kTimeoutMs, reply)) return false;
    Log("key %s vk=%s char=%s -> %zu commands, eaten=%d", down ? "down" : "up", fields[1].c_str(), fields[2].c_str(), reply->commands.size(),
        reply->consumed);
    return true;
}

TextService::KeyId TextService::MakeKeyId(WPARAM wParam, LPARAM lParam, bool down) {
    // エンジンに送る中身（仮想キー・文字・修飾キー・連打か）で見分ける。lParam やメッセージの時刻は使わない
    // （IMM のアプリでは OnTestKeyDown と OnKeyDown で lParam の印や時刻が違い、同じキーと見分けられず2回処理した）。
    // 押し続けの連打は中身が同じだが、OnKeyDown で取っておいたものを使い切るので、次の打鍵とは混ざらない
    return {KeyFields(wParam, lParam, down)};
}

void TextService::DropPending(ITfContext* context) {
    // 書くものを残したまま捨てるときは、二重に処理しないよう、エンジンに送り直さずに書く
    if (pending_.valid && !pending_.reply.commands.empty() && context) ApplyCommands(context, std::move(pending_.reply.commands), false);
    pending_ = {};
}

BOOL TextService::TestKey(ITfContext* context, WPARAM wParam, LPARAM lParam, bool down) {
    KeyId id = MakeKeyId(wParam, lParam, down);
    // 同じキーをもう一度聞かれた（OnKeyDown の前に2回）ときは、取っておいた答えを返す
    if (pending_.valid && pending_.id == id) return TRUE;
    DropPending(context);
    Reply reply;
    if (!ProcessKey(context, KeyFields(wParam, lParam, down), down, &reply)) {
        EndCompositionLater();
        return FALSE;
    }
    // 使わず、書くものもないキーは、そのままアプリへ
    if (!reply.consumed && reply.commands.empty()) return FALSE;
    // 書くものがあるとき（使わないキーでも、入力中の文字を先に確定するなど）は OnKeyDown で書く
    pending_ = {true, id, std::move(reply)};
    return TRUE;
}

void TextService::HandleKey(ITfContext* context, WPARAM wParam, LPARAM lParam, bool down, BOOL* eaten) {
    KeyId id = MakeKeyId(wParam, lParam, down);
    Reply reply;
    if (pending_.valid && pending_.id == id) {
        reply = std::move(pending_.reply);
        pending_ = {};
    } else {
        DropPending(context);
        if (!ProcessKey(context, KeyFields(wParam, lParam, down), down, &reply)) {
            EndCompositionLater();
            return;
        }
    }
    keySeq_++;
    bool modeChanged = false;
    bool hasCommands = !reply.commands.empty();
    for (const auto& c : reply.commands) modeChanged = modeChanged || c[0] == "mode";
    if (hasCommands) ApplyCommands(context, std::move(reply.commands), true);
    // 「あ」「A」をカーソルの近くに出すため、切り替えたらカーソルの位置を送る
    if (modeChanged && context && !composition_) SendSelectionCaret(context);
    *eaten = reply.consumed;
    // 入力中の文字を確定してからキーをアプリへ渡すとき、IMM のアプリ（一時的な文書）では FALSE を返してもキーが届かない。
    // キーを食べて、確定のあとで同じキーを送り直す（送り直したキーは、入力中の文字がないのでそのままアプリへ届く）
    if (down && !reply.consumed && hasCommands && context && IsTransitory(context)) {
        *eaten = TRUE;
        Resend(wParam, lParam);
    }
}

void TextService::EndCompositionLater() {
    // エンジンにつながらない・返事がないとき、入力中の文字をそのまま確定させる（下線つきのまま残らないように）
    if (!composition_ || !compositionContext_) return;
    ComPtr<ITfContext> context = compositionContext_;
    ComPtr<ITfComposition> captured = composition_;
    ComPtr<ITfTextInputProcessorEx> self(this);
    auto* session = new EditSession([this, self, captured](TfEditCookie ec) {
        if (composition_.Get() == captured.Get()) EndComposition(ec);
        return S_OK;
    });
    HRESULT hr;
    context->RequestEditSession(clientId_, session, TF_ES_ASYNCDONTCARE | TF_ES_READWRITE, &hr);
    session->Release();
}

bool TextService::IsTransitory(ITfContext* context) {
    TF_STATUS status = {};
    return SUCCEEDED(context->GetStatus(&status)) && (status.dwStaticFlags & TF_SS_TRANSITORY);
}

void TextService::Resend(WPARAM wParam, LPARAM lParam) {
    INPUT in[2] = {};
    for (int i = 0; i < 2; i++) {
        in[i].type = INPUT_KEYBOARD;
        in[i].ki.wVk = static_cast<WORD>(wParam);
        in[i].ki.wScan = static_cast<WORD>((lParam >> 16) & 0xFF);
        in[i].ki.dwFlags = (lParam & (1 << 24) ? KEYEVENTF_EXTENDEDKEY : 0) | (i == 1 ? KEYEVENTF_KEYUP : 0);
    }
    // 押して離すまでを送る（本物の「離した」が先に並んでいても、押したままにならないように）
    SendInput(2, in, sizeof(INPUT));
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

void TextService::SendSelectionCaret(ITfContext* context) {
    EditSync(context, TF_ES_READ, [&](TfEditCookie ec) {
        TF_SELECTION sel;
        ULONG fetched = 0;
        if (FAILED(context->GetSelection(ec, TF_DEFAULT_SELECTION, 1, &sel, &fetched)) || fetched == 0) return S_OK;
        ComPtr<ITfRange> range;
        range.Attach(sel.range);
        SendRect(ec, context, range.Get());
        return S_OK;
    });
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
    HRESULT requested = context->RequestEditSession(clientId_, session, (sync ? TF_ES_SYNC : TF_ES_ASYNCDONTCARE) | TF_ES_READWRITE, &hr);
    if (sync && (FAILED(requested) || hr == TF_E_SYNCHRONOUS || hr == TF_E_LOCKED)) {
        // 文書がロックされているなど、同期では書けないアプリ。命令を捨てずに非同期で頼み直す
        Log("sync edit session refused %08lx %08lx, retry async", requested, hr);
        requested = context->RequestEditSession(clientId_, session, TF_ES_ASYNCDONTCARE | TF_ES_READWRITE, &hr);
    }
    session->Release();
    if (FAILED(requested) || FAILED(hr)) Log("edit session failed %08lx %08lx", requested, hr);
}

void TextService::OnPushMessage() {
    if (!engine_) return;
    for (auto& batch : engine_->TakePushes()) {
        // 届いたあとにキーを処理していたら、この入力中の文字（並べ替えの結果など）は古い。確定の文やモードは捨てない
        unsigned long long seq = keySeq_;
        for (auto& c : batch) {
            if (c[0] == "compose") c.insert(c.begin() + 1, "@" + std::to_string(seq));
        }
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
    if (FAILED(openClose->GetValue(&v))) return S_OK;
    if (v.vt != VT_I4) {
        VariantClear(&v);
        return S_OK;
    }
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
