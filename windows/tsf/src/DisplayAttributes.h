// 入力中の文字の見た目（下線）。入力中は点線、変換中は選んでいる文節を太線・ほかを細線
#pragma once
#include <windows.h>
#include <msctf.h>

namespace kanade {

class DisplayAttributeInfo : public ITfDisplayAttributeInfo {
public:
    // 知らない GUID なら nullptr
    static DisplayAttributeInfo* Create(REFGUID guid);

    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override;
    STDMETHODIMP_(ULONG) AddRef() override;
    STDMETHODIMP_(ULONG) Release() override;
    STDMETHODIMP GetGUID(GUID* guid) override;
    STDMETHODIMP GetDescription(BSTR* desc) override;
    STDMETHODIMP GetAttributeInfo(TF_DISPLAYATTRIBUTE* attr) override;
    STDMETHODIMP SetAttributeInfo(const TF_DISPLAYATTRIBUTE*) override { return E_NOTIMPL; }
    STDMETHODIMP Reset() override { return S_OK; }

private:
    DisplayAttributeInfo(const GUID& guid, const wchar_t* desc, TF_DISPLAYATTRIBUTE attr);
    ~DisplayAttributeInfo();
    LONG refs_ = 1;
    GUID guid_;
    const wchar_t* desc_;
    TF_DISPLAYATTRIBUTE attr_;
};

class DisplayAttributeEnum : public IEnumTfDisplayAttributeInfo {
public:
    DisplayAttributeEnum();
    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override;
    STDMETHODIMP_(ULONG) AddRef() override;
    STDMETHODIMP_(ULONG) Release() override;
    STDMETHODIMP Clone(IEnumTfDisplayAttributeInfo** ppEnum) override;
    STDMETHODIMP Next(ULONG count, ITfDisplayAttributeInfo** infos, ULONG* fetched) override;
    STDMETHODIMP Reset() override;
    STDMETHODIMP Skip(ULONG count) override;

private:
    ~DisplayAttributeEnum();
    LONG refs_ = 1;
    ULONG index_ = 0;
};

}  // namespace kanade
