#include "Log.h"

#include <windows.h>
#include <shlobj.h>

#include <cstdarg>
#include <cstdio>
#include <share.h>
#include <mutex>

namespace kanade {

namespace {
std::once_flag g_once;
FILE* g_file = nullptr;

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
    g_file = _wfsopen(path, L"ab", _SH_DENYNO);
}
}  // namespace

void Log(const char* fmt, ...) {
    std::call_once(g_once, Open);
    if (!g_file) return;
    char buf[2048];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    SYSTEMTIME t;
    GetLocalTime(&t);
    static std::mutex m;
    std::lock_guard lock(m);
    fprintf(g_file, "%02d:%02d:%02d.%03d [%lu] %s\n", t.wHour, t.wMinute, t.wSecond, t.wMilliseconds, GetCurrentThreadId(), buf);
    fflush(g_file);
}

}  // namespace kanade
