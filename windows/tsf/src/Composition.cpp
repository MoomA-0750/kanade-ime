// 入力中の文字（composition）と確定の書き込み。どれも編集セッションの中で呼ぶ
#include <algorithm>

#include "Globals.h"
#include "Log.h"
#include "TextService.h"

namespace kanade {

namespace {

int ToInt(const std::string& s, int fallback) {
    if (s.empty()) return fallback;
    return atoi(s.c_str());
}

std::vector<int> ParseSegments(const std::string& s) {
    std::vector<int> out;
    size_t start = 0;
    while (start < s.size()) {
        size_t comma = s.find(',', start);
        if (comma == std::string::npos) comma = s.size();
        out.push_back(atoi(s.substr(start, comma - start).c_str()));
        start = comma + 1;
    }
    return out;
}

// [range] の先頭から [offset] 文字目〜[length] 文字の範囲
ComPtr<ITfRange> SubRange(TfEditCookie ec, ITfRange* range, LONG offset, LONG length) {
    ComPtr<ITfRange> sub;
    if (FAILED(range->Clone(&sub))) return nullptr;
    LONG shifted = 0;
    sub->Collapse(ec, TF_ANCHOR_START);
    sub->ShiftEnd(ec, offset + length, &shifted, nullptr);
    sub->ShiftStart(ec, offset, &shifted, nullptr);
    return sub;
}

void SetAttribute(TfEditCookie ec, ITfContext* context, ITfRange* range, TfGuidAtom atom) {
    if (atom == TF_INVALID_GUIDATOM) return;
    ComPtr<ITfProperty> prop;
    if (FAILED(context->GetProperty(GUID_PROP_ATTRIBUTE, &prop))) return;
    VARIANT v;
    v.vt = VT_I4;
    v.lVal = static_cast<LONG>(atom);
    prop->SetValue(ec, range, &v);
}

void ClearAttribute(TfEditCookie ec, ITfContext* context, ITfRange* range) {
    ComPtr<ITfProperty> prop;
    if (SUCCEEDED(context->GetProperty(GUID_PROP_ATTRIBUTE, &prop))) prop->Clear(ec, range);
}

}  // namespace

void TextService::ApplyInSession(TfEditCookie ec, ITfContext* context, const std::vector<Command>& commands) {
    for (const auto& c : commands) {
        const std::string& type = c[0];
        if (type == "compose" && c.size() >= 2) {
            SetComposingText(ec, context, FromUtf8(c[1]), c.size() > 2 ? ToInt(c[2], -1) : -1, c.size() > 3 ? ToInt(c[3], -1) : -1,
                             c.size() > 4 ? ParseSegments(c[4]) : std::vector<int>{});
        } else if (type == "commit" && c.size() >= 2) {
            Commit(ec, context, FromUtf8(c[1]));
        } else if (type == "replace" && c.size() >= 4) {
            Replace(ec, context, FromUtf8(c[1]), FromUtf8(c[2]), FromUtf8(c[3]));
        }
    }
    if (composition_) SendCaret(ec, composition_ && compositionContext_ ? compositionContext_.Get() : context);
}

HRESULT TextService::StartComposition(TfEditCookie ec, ITfContext* context) {
    ComPtr<ITfInsertAtSelection> insert;
    ComPtr<ITfContextComposition> contextComposition;
    if (FAILED(context->QueryInterface(IID_PPV_ARGS(&insert))) || FAILED(context->QueryInterface(IID_PPV_ARGS(&contextComposition)))) {
        return E_FAIL;
    }
    ComPtr<ITfRange> range;
    HRESULT hr = insert->InsertTextAtSelection(ec, TF_IAS_QUERYONLY, nullptr, 0, &range);
    if (FAILED(hr)) return hr;
    ComPtr<ITfComposition> composition;
    hr = contextComposition->StartComposition(ec, range.Get(), static_cast<ITfCompositionSink*>(this), &composition);
    if (FAILED(hr) || !composition) {
        Log("StartComposition failed %08lx", hr);
        return FAILED(hr) ? hr : E_FAIL;
    }
    composition_ = composition;
    compositionContext_ = context;
    return S_OK;
}

void TextService::SetComposingText(TfEditCookie ec, ITfContext* context, const std::wstring& text, int cursor, int focused,
                                   const std::vector<int>& segments) {
    if (text.empty()) {
        if (composition_) {
            ComPtr<ITfRange> range;
            if (SUCCEEDED(composition_->GetRange(&range))) range->SetText(ec, 0, L"", 0);
            EndComposition(ec);
        }
        return;
    }
    if (!composition_ && FAILED(StartComposition(ec, context))) return;
    ITfContext* ctx = compositionContext_.Get();
    ComPtr<ITfRange> range;
    if (FAILED(composition_->GetRange(&range))) return;
    if (FAILED(range->SetText(ec, 0, text.c_str(), static_cast<LONG>(text.size())))) {
        Log("SetText failed");
        return;
    }
    LONG length = static_cast<LONG>(text.size());
    if (segments.empty()) {
        SetAttribute(ec, ctx, range.Get(), atomInput_);
    } else {
        LONG offset = 0;
        for (size_t i = 0; i < segments.size() && offset < length; i++) {
            LONG segLength = std::min<LONG>(segments[i], length - offset);
            if (auto sub = SubRange(ec, range.Get(), offset, segLength)) {
                SetAttribute(ec, ctx, sub.Get(), static_cast<int>(i) == focused ? atomFocused_ : atomConverted_);
            }
            offset += segLength;
        }
    }
    // カーソル（-1 は末尾）
    LONG at = cursor < 0 || cursor > length ? length : cursor;
    if (auto caret = SubRange(ec, range.Get(), at, 0)) SetSelectionAt(ec, ctx, caret.Get(), TF_ANCHOR_START);
}

void TextService::Commit(TfEditCookie ec, ITfContext* context, const std::wstring& text) {
    if (composition_) {
        ITfContext* ctx = compositionContext_.Get();
        ComPtr<ITfRange> range;
        if (SUCCEEDED(composition_->GetRange(&range))) {
            range->SetText(ec, 0, text.c_str(), static_cast<LONG>(text.size()));
            ClearAttribute(ec, ctx, range.Get());
            SetSelectionAt(ec, ctx, range.Get(), TF_ANCHOR_END);
        }
        composition_->EndComposition(ec);
        composition_.Reset();
        compositionContext_.Reset();
        return;
    }
    if (text.empty()) return;
    ComPtr<ITfInsertAtSelection> insert;
    if (FAILED(context->QueryInterface(IID_PPV_ARGS(&insert)))) return;
    ComPtr<ITfRange> range;
    if (SUCCEEDED(insert->InsertTextAtSelection(ec, 0, text.c_str(), static_cast<LONG>(text.size()), &range)) && range) {
        SetSelectionAt(ec, context, range.Get(), TF_ANCHOR_END);
    }
}

void TextService::Replace(TfEditCookie ec, ITfContext* context, const std::wstring& expected, const std::wstring& typed,
                          const std::wstring& replacement) {
    TF_SELECTION sel;
    ULONG fetched = 0;
    if (SUCCEEDED(context->GetSelection(ec, TF_DEFAULT_SELECTION, 1, &sel, &fetched)) && fetched == 1) {
        ComPtr<ITfRange> selection;
        selection.Attach(sel.range);
        ComPtr<ITfRange> before;
        if (SUCCEEDED(selection->Clone(&before))) {
            LONG shifted = 0;
            before->Collapse(ec, TF_ANCHOR_START);
            before->ShiftStart(ec, -static_cast<LONG>(expected.size()), &shifted, nullptr);
            std::wstring actual(expected.size() + 1, L'\0');
            ULONG n = 0;
            if (-shifted == static_cast<LONG>(expected.size()) &&
                SUCCEEDED(before->GetText(ec, 0, actual.data(), static_cast<ULONG>(expected.size()), &n))) {
                actual.resize(n);
                if (actual == expected) {
                    before->SetText(ec, 0, replacement.c_str(), static_cast<LONG>(replacement.size()));
                    SetSelectionAt(ec, context, before.Get(), TF_ANCHOR_END);
                    return;
                }
            }
        }
    }
    Commit(ec, context, typed);
}

void TextService::EndComposition(TfEditCookie ec) {
    if (!composition_) return;
    ComPtr<ITfRange> range;
    if (compositionContext_ && SUCCEEDED(composition_->GetRange(&range))) ClearAttribute(ec, compositionContext_.Get(), range.Get());
    composition_->EndComposition(ec);
    composition_.Reset();
    compositionContext_.Reset();
}

void TextService::SendCaret(TfEditCookie ec, ITfContext* context) {
    if (!engine_ || !composition_ || !context) return;
    ComPtr<ITfContextView> view;
    ComPtr<ITfRange> range;
    if (FAILED(context->GetActiveView(&view)) || FAILED(composition_->GetRange(&range))) return;
    RECT rect = {};
    BOOL clipped = FALSE;
    // 描画がまだのアプリ（ブラウザなど）では E_PENDING が返る。そのときは送らない（前の位置のまま）
    if (view->GetTextExt(ec, range.Get(), &rect, &clipped) != S_OK) return;
    engine_->Send({"caret", std::to_string(rect.left), std::to_string(rect.top), std::to_string(rect.right), std::to_string(rect.bottom)});
}

void TextService::SetSelectionAt(TfEditCookie ec, ITfContext* context, ITfRange* range, TfAnchor anchor) {
    ComPtr<ITfRange> point;
    if (FAILED(range->Clone(&point))) return;
    point->Collapse(ec, anchor);
    TF_SELECTION sel = {};
    sel.range = point.Get();
    sel.style.ase = TF_AE_NONE;
    sel.style.fInterimChar = FALSE;
    context->SetSelection(ec, 1, &sel);
}

}  // namespace kanade
