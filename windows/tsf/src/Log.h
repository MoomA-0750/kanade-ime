// 調べる用の記録。%LOCALAPPDATA%\Kanade\tsf-log というフォルダがあるときだけ、そこへプロセスごとに書く
#pragma once
#include <string>

namespace kanade {
void Log(const char* fmt, ...);
}
