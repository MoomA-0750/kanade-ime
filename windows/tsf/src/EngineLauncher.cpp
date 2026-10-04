#include "EngineLauncher.h"

#include <windows.h>

#include <string>

#include "Log.h"

namespace kanade {

namespace {

// このプロセスが普通の権限（整合性が中、AppContainer でない）か
bool IsMediumIntegrity() {
    HANDLE token = nullptr;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) return false;
    bool ok = false;
    DWORD appContainer = 0, size = 0;
    if (GetTokenInformation(token, TokenIsAppContainer, &appContainer, sizeof(appContainer), &size) && !appContainer) {
        BYTE buf[64];
        if (GetTokenInformation(token, TokenIntegrityLevel, buf, sizeof(buf), &size)) {
            auto* label = reinterpret_cast<TOKEN_MANDATORY_LABEL*>(buf);
            DWORD rid = *GetSidSubAuthority(label->Label.Sid, *GetSidSubAuthorityCount(label->Label.Sid) - 1);
            ok = rid == SECURITY_MANDATORY_MEDIUM_RID;
        }
    }
    CloseHandle(token);
    return ok;
}

std::wstring EngineCommand() {
    wchar_t buf[2048];
    DWORD size = sizeof(buf);
    if (RegGetValueW(HKEY_CURRENT_USER, L"Software\\Kanade", L"EngineCommand", RRF_RT_REG_SZ, nullptr, buf, &size) != ERROR_SUCCESS) {
        return {};
    }
    return buf;
}

}  // namespace

void MaybeLaunchEngine() {
    // 起動には1〜2秒かかるので、同じプロセスからは15秒に1回まで（ほかのプロセスと重なっても、2つ目のエンジンは自分で終わる）
    static ULONGLONG last = 0;
    ULONGLONG now = GetTickCount64();
    if (last != 0 && now - last < 15000) return;
    last = now;
    if (!IsMediumIntegrity()) return;
    std::wstring command = EngineCommand();
    if (command.empty()) return;

    STARTUPINFOW si = {sizeof(si)};
    PROCESS_INFORMATION pi = {};
    // アプリを閉じてもエンジンが巻き添えで終わらないよう、アプリのジョブから外して起動する（外せなければそのまま）
    for (DWORD flags : {DWORD(DETACHED_PROCESS | CREATE_BREAKAWAY_FROM_JOB), DWORD(DETACHED_PROCESS)}) {
        std::wstring mutableCommand = command;
        if (CreateProcessW(nullptr, mutableCommand.data(), nullptr, nullptr, FALSE, flags, nullptr, nullptr, &si, &pi)) {
            CloseHandle(pi.hThread);
            CloseHandle(pi.hProcess);
            Log("launched engine");
            return;
        }
    }
    Log("failed to launch engine %lu", GetLastError());
}

}  // namespace kanade
