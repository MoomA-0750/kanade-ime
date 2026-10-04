// 命令を AppContainer（スタートメニューの検索などと同じ隔離）の中で動かす試験の道具
//   kanade_appcontainer.exe <命令>...   例: kanade_appcontainer.exe C:\...\kanade_probe.exe abc
#include <windows.h>
#include <userenv.h>

#include <cstdio>
#include <string>

int wmain(int argc, wchar_t** argv) {
    if (argc < 2) {
        fwprintf(stderr, L"usage: kanade_appcontainer <command>...\n");
        return 2;
    }
    std::wstring command;
    for (int i = 1; i < argc; i++) {
        if (i > 1) command += L' ';
        command += L'"';
        command += argv[i];
        command += L'"';
    }
    const wchar_t* name = L"Kanade.AppContainerTest";
    PSID sid = nullptr;
    HRESULT hr = CreateAppContainerProfile(name, name, name, nullptr, 0, &sid);
    if (hr == HRESULT_FROM_WIN32(ERROR_ALREADY_EXISTS)) hr = DeriveAppContainerSidFromAppContainerName(name, &sid);
    if (FAILED(hr)) {
        fwprintf(stderr, L"AppContainer を作れない %08lx\n", hr);
        return 1;
    }
    SECURITY_CAPABILITIES caps = {};
    caps.AppContainerSid = sid;
    SIZE_T size = 0;
    InitializeProcThreadAttributeList(nullptr, 1, 0, &size);
    auto* attrs = static_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(HeapAlloc(GetProcessHeap(), 0, size));
    InitializeProcThreadAttributeList(attrs, 1, 0, &size);
    UpdateProcThreadAttribute(attrs, 0, PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES, &caps, sizeof(caps), nullptr, nullptr);
    STARTUPINFOEXW si = {};
    si.StartupInfo.cb = sizeof(si);
    si.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    si.StartupInfo.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
    si.StartupInfo.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE);
    si.StartupInfo.hStdError = GetStdHandle(STD_ERROR_HANDLE);
    si.lpAttributeList = attrs;
    PROCESS_INFORMATION pi = {};
    if (!CreateProcessW(nullptr, command.data(), nullptr, nullptr, TRUE, EXTENDED_STARTUPINFO_PRESENT, nullptr, nullptr,
                        &si.StartupInfo, &pi)) {
        fwprintf(stderr, L"起動できない %lu\n", GetLastError());
        return 1;
    }
    WaitForSingleObject(pi.hProcess, 30000);
    DWORD code = 1;
    GetExitCodeProcess(pi.hProcess, &code);
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    FreeSid(sid);
    return static_cast<int>(code);
}
