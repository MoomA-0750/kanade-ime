// regsvr32 での登録と解除（COM のクラス・TSF のプロファイル・カテゴリ）。HKLM に書くので管理者権限が要る
#include <windows.h>
#include <msctf.h>
#include <wrl/client.h>

#include <string>

#include "Globals.h"

using Microsoft::WRL::ComPtr;
using namespace kanade;

namespace {

const GUID* const kCategories[] = {
    &GUID_TFCAT_TIP_KEYBOARD,
    &GUID_TFCAT_DISPLAYATTRIBUTEPROVIDER,
    &GUID_TFCAT_TIPCAP_SECUREMODE,
    &GUID_TFCAT_TIPCAP_IMMERSIVESUPPORT,
    &GUID_TFCAT_TIPCAP_SYSTRAYSUPPORT,
    &GUID_TFCAT_TIPCAP_INPUTMODECOMPARTMENT,
};

std::wstring ClsidKey() {
    wchar_t clsid[64];
    StringFromGUID2(CLSID_TextService, clsid, 64);
    return std::wstring(L"Software\\Classes\\CLSID\\") + clsid;
}

std::wstring ModulePath() {
    wchar_t path[MAX_PATH];
    GetModuleFileNameW(g_module, path, MAX_PATH);
    return path;
}

bool SetString(HKEY key, const wchar_t* name, const std::wstring& value) {
    return RegSetValueExW(key, name, 0, REG_SZ, reinterpret_cast<const BYTE*>(value.c_str()),
                          static_cast<DWORD>((value.size() + 1) * sizeof(wchar_t))) == ERROR_SUCCESS;
}

HRESULT RegisterServer() {
    HKEY key, inproc;
    if (RegCreateKeyExW(HKEY_LOCAL_MACHINE, ClsidKey().c_str(), 0, nullptr, 0, KEY_WRITE, nullptr, &key, nullptr) != ERROR_SUCCESS) {
        return E_ACCESSDENIED;
    }
    bool ok = SetString(key, nullptr, kDisplayName);
    if (RegCreateKeyExW(key, L"InprocServer32", 0, nullptr, 0, KEY_WRITE, nullptr, &inproc, nullptr) == ERROR_SUCCESS) {
        ok = SetString(inproc, nullptr, ModulePath()) && SetString(inproc, L"ThreadingModel", L"Apartment") && ok;
        RegCloseKey(inproc);
    } else {
        ok = false;
    }
    RegCloseKey(key);
    return ok ? S_OK : E_FAIL;
}

HRESULT RegisterProfile() {
    ComPtr<ITfInputProcessorProfileMgr> profiles;
    HRESULT hr = CoCreateInstance(CLSID_TF_InputProcessorProfiles, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&profiles));
    if (FAILED(hr)) return hr;
    std::wstring icon = ModulePath();
    hr = profiles->RegisterProfile(CLSID_TextService, kLangId, GUID_Profile, kDisplayName, static_cast<ULONG>(wcslen(kDisplayName)),
                                   icon.c_str(), static_cast<ULONG>(icon.size()), 0, nullptr, 0, TRUE, 0);
    if (FAILED(hr)) return hr;
    ComPtr<ITfCategoryMgr> categories;
    hr = CoCreateInstance(CLSID_TF_CategoryMgr, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&categories));
    if (FAILED(hr)) return hr;
    for (const GUID* cat : kCategories) categories->RegisterCategory(CLSID_TextService, *cat, CLSID_TextService);
    return S_OK;
}

void UnregisterProfile() {
    ComPtr<ITfCategoryMgr> categories;
    if (SUCCEEDED(CoCreateInstance(CLSID_TF_CategoryMgr, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&categories)))) {
        for (const GUID* cat : kCategories) categories->UnregisterCategory(CLSID_TextService, *cat, CLSID_TextService);
    }
    ComPtr<ITfInputProcessorProfileMgr> profiles;
    if (SUCCEEDED(CoCreateInstance(CLSID_TF_InputProcessorProfiles, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&profiles)))) {
        profiles->UnregisterProfile(CLSID_TextService, kLangId, GUID_Profile, 0);
    }
}

struct ComScope {
    HRESULT hr = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    ~ComScope() {
        if (SUCCEEDED(hr)) CoUninitialize();
    }
};

}  // namespace

STDAPI DllRegisterServer() {
    ComScope com;
    HRESULT hr = RegisterServer();
    if (FAILED(hr)) return hr;
    hr = RegisterProfile();
    if (FAILED(hr)) {
        UnregisterProfile();
        RegDeleteTreeW(HKEY_LOCAL_MACHINE, ClsidKey().c_str());
    }
    return hr;
}

STDAPI DllUnregisterServer() {
    ComScope com;
    UnregisterProfile();
    RegDeleteTreeW(HKEY_LOCAL_MACHINE, ClsidKey().c_str());
    return S_OK;
}
