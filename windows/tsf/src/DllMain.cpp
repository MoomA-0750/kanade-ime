// DLL の入口と、COM のクラスファクトリ
#include <windows.h>

#include <new>
#include <string>

#include "Globals.h"
#include "TextService.h"

namespace kanade {

HINSTANCE g_module = nullptr;
LONG g_refs = 0;

// 関数の中の static は、初めて呼ばれたときにスレッド安全に作られる
const wchar_t* MessageWindowClass() {
    static const std::wstring name = L"KanadeTsfMessageWindow_" + std::to_wstring(reinterpret_cast<uintptr_t>(g_module));
    return name.c_str();
}

const wchar_t* PopupWindowClass() {
    static const std::wstring name = L"KanadePopup_" + std::to_wstring(reinterpret_cast<uintptr_t>(g_module));
    return name.c_str();
}

namespace {
class ClassFactory : public IClassFactory {
public:
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override {
        if (!ppv) return E_INVALIDARG;
        if (riid == IID_IUnknown || riid == IID_IClassFactory) {
            *ppv = static_cast<IClassFactory*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    STDMETHODIMP_(ULONG) AddRef() override {
        DllAddRef();
        return 2;
    }
    STDMETHODIMP_(ULONG) Release() override {
        DllRelease();
        return 1;
    }
    STDMETHODIMP CreateInstance(IUnknown* outer, REFIID riid, void** ppv) override {
        if (!ppv) return E_INVALIDARG;
        *ppv = nullptr;
        if (outer) return CLASS_E_NOAGGREGATION;
        auto* service = new (std::nothrow) TextService();
        if (!service) return E_OUTOFMEMORY;
        HRESULT hr = service->QueryInterface(riid, ppv);
        service->Release();
        return hr;
    }
    STDMETHODIMP LockServer(BOOL lock) override {
        if (lock) DllAddRef(); else DllRelease();
        return S_OK;
    }
};

ClassFactory g_factory;
}  // namespace

}  // namespace kanade

using namespace kanade;

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID reserved) {
    if (reason == DLL_PROCESS_ATTACH) {
        g_module = instance;
        DisableThreadLibraryCalls(instance);
    } else if (reason == DLL_PROCESS_DETACH && reserved == nullptr) {
        // DLL だけが外れるとき（プロセスの終了ではない）は、窓のクラスを外す（クラスが外れた DLL の手続きを指したまま残らないように）
        UnregisterClassW(MessageWindowClass(), instance);
        UnregisterClassW(PopupWindowClass(), instance);
    }
    return TRUE;
}

STDAPI DllGetClassObject(REFCLSID clsid, REFIID riid, void** ppv) {
    if (!ppv) return E_INVALIDARG;
    *ppv = nullptr;
    if (clsid != CLSID_TextService) return CLASS_E_CLASSNOTAVAILABLE;
    return g_factory.QueryInterface(riid, ppv);
}

STDAPI DllCanUnloadNow() { return g_refs <= 0 ? S_OK : S_FALSE; }
