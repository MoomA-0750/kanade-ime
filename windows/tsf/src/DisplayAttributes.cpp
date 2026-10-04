#include "DisplayAttributes.h"

#include <algorithm>

#include "Globals.h"

namespace kanade {

namespace {
const GUID* const kGuids[] = {&GUID_AttrInput, &GUID_AttrFocused, &GUID_AttrConverted};
constexpr ULONG kCount = 3;

TF_DISPLAYATTRIBUTE Attr(TF_DA_LINESTYLE style, BOOL bold, TF_DA_ATTR_INFO info) {
    TF_DISPLAYATTRIBUTE a = {};
    a.crText.type = TF_CT_NONE;
    a.crBk.type = TF_CT_NONE;
    a.lsStyle = style;
    a.fBoldLine = bold;
    a.crLine.type = TF_CT_NONE;
    a.bAttr = info;
    return a;
}
}  // namespace

DisplayAttributeInfo* DisplayAttributeInfo::Create(REFGUID guid) {
    if (guid == GUID_AttrInput) return new DisplayAttributeInfo(guid, L"Kanade 入力中", Attr(TF_LS_DOT, FALSE, TF_ATTR_INPUT));
    if (guid == GUID_AttrFocused) {
        return new DisplayAttributeInfo(guid, L"Kanade 変換中の文節", Attr(TF_LS_SOLID, TRUE, TF_ATTR_TARGET_CONVERTED));
    }
    if (guid == GUID_AttrConverted) return new DisplayAttributeInfo(guid, L"Kanade 変換中", Attr(TF_LS_SOLID, FALSE, TF_ATTR_CONVERTED));
    return nullptr;
}

DisplayAttributeInfo::DisplayAttributeInfo(const GUID& guid, const wchar_t* desc, TF_DISPLAYATTRIBUTE attr)
    : guid_(guid), desc_(desc), attr_(attr) {
    DllAddRef();
}

DisplayAttributeInfo::~DisplayAttributeInfo() { DllRelease(); }

STDMETHODIMP DisplayAttributeInfo::QueryInterface(REFIID riid, void** ppv) {
    if (!ppv) return E_INVALIDARG;
    if (riid == IID_IUnknown || riid == IID_ITfDisplayAttributeInfo) {
        *ppv = static_cast<ITfDisplayAttributeInfo*>(this);
        AddRef();
        return S_OK;
    }
    *ppv = nullptr;
    return E_NOINTERFACE;
}

STDMETHODIMP_(ULONG) DisplayAttributeInfo::AddRef() { return InterlockedIncrement(&refs_); }

STDMETHODIMP_(ULONG) DisplayAttributeInfo::Release() {
    LONG n = InterlockedDecrement(&refs_);
    if (n == 0) delete this;
    return n;
}

STDMETHODIMP DisplayAttributeInfo::GetGUID(GUID* guid) {
    if (!guid) return E_INVALIDARG;
    *guid = guid_;
    return S_OK;
}

STDMETHODIMP DisplayAttributeInfo::GetDescription(BSTR* desc) {
    if (!desc) return E_INVALIDARG;
    *desc = SysAllocString(desc_);
    return *desc ? S_OK : E_OUTOFMEMORY;
}

STDMETHODIMP DisplayAttributeInfo::GetAttributeInfo(TF_DISPLAYATTRIBUTE* attr) {
    if (!attr) return E_INVALIDARG;
    *attr = attr_;
    return S_OK;
}

DisplayAttributeEnum::DisplayAttributeEnum() { DllAddRef(); }

DisplayAttributeEnum::~DisplayAttributeEnum() { DllRelease(); }

STDMETHODIMP DisplayAttributeEnum::QueryInterface(REFIID riid, void** ppv) {
    if (!ppv) return E_INVALIDARG;
    if (riid == IID_IUnknown || riid == IID_IEnumTfDisplayAttributeInfo) {
        *ppv = static_cast<IEnumTfDisplayAttributeInfo*>(this);
        AddRef();
        return S_OK;
    }
    *ppv = nullptr;
    return E_NOINTERFACE;
}

STDMETHODIMP_(ULONG) DisplayAttributeEnum::AddRef() { return InterlockedIncrement(&refs_); }

STDMETHODIMP_(ULONG) DisplayAttributeEnum::Release() {
    LONG n = InterlockedDecrement(&refs_);
    if (n == 0) delete this;
    return n;
}

STDMETHODIMP DisplayAttributeEnum::Clone(IEnumTfDisplayAttributeInfo** ppEnum) {
    if (!ppEnum) return E_INVALIDARG;
    auto* e = new DisplayAttributeEnum();
    e->index_ = index_;
    *ppEnum = e;
    return S_OK;
}

STDMETHODIMP DisplayAttributeEnum::Next(ULONG count, ITfDisplayAttributeInfo** infos, ULONG* fetched) {
    ULONG n = 0;
    while (n < count && index_ < kCount) {
        infos[n++] = DisplayAttributeInfo::Create(*kGuids[index_++]);
    }
    if (fetched) *fetched = n;
    return n == count ? S_OK : S_FALSE;
}

STDMETHODIMP DisplayAttributeEnum::Reset() {
    index_ = 0;
    return S_OK;
}

STDMETHODIMP DisplayAttributeEnum::Skip(ULONG count) {
    index_ = std::min(index_ + count, kCount);
    return index_ < kCount ? S_OK : S_FALSE;
}

}  // namespace kanade
