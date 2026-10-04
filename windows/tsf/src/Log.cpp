#include "Log.h"

#include <windows.h>
#include <shlobj.h>

#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <mutex>

namespace kanade {

namespace {
std::once_flag g_once;
HANDLE g_file = INVALID_HANDLE_VALUE;

void Open() {
    PWSTR local = nullptr;
    if (FAILED(SHGetKnownFolderPath(FOLDERID_LocalAppData, 0, nullptr, &local))) return;
    std::wstring dir = std::wstring(local) + L"\\Kanade\\tsf-log";
    CoTaskMemFree(local);
    DWORD attr = GetFileAttributesW(dir.c_str());
    if (attr == INVALID_FILE_ATTRIBUTES || !(attr & FILE_ATTRIBUTE_DIRECTORY)) return;
    wchar_t exe[MAX_PATH] = {};
    GetModuleFileNameW(nullptr, exe, MAX_PATH);
    const wchar_t* name = wcsrchr(exe, L'\\');
    name = name ? name + 1 : exe;
    wchar_t path[MAX_PATH * 2];
    swprintf_s(path, L"%s\\%s-%lu.log", dir.c_str(), name, GetCurrentProcessId());
    // 同じプロセスに別の版の DLL が読み込まれていても書けるよう、共有して開く
    g_file = CreateFileW(path, FILE_APPEND_DATA, FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_ALWAYS,
                         FILE_ATTRIBUTE_NORMAL, nullptr);
}
}  // namespace

void Log(const char* fmt, ...) {
    std::call_once(g_once, Open);
    if (g_file == INVALID_HANDLE_VALUE) return;
    char buf[2048];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    SYSTEMTIME t;
    GetLocalTime(&t);
    char line[2200];
    int n = snprintf(line, sizeof(line), "%02d:%02d:%02d.%03d [%lu] %s\r\n", t.wHour, t.wMinute, t.wSecond, t.wMilliseconds,
                     GetCurrentThreadId(), buf);
    if (n <= 0) return;
    DWORD written = 0;
    WriteFile(g_file, line, static_cast<DWORD>(std::min<size_t>(n, sizeof(line) - 1)), &written, nullptr);
}

}  // namespace kanade
