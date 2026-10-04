// エンジンとのやりとりの形（docs/WINDOWS-PROTOCOL.md）。1行1メッセージ、タブ区切り、\ でエスケープ、UTF-8
#pragma once
#include <string>
#include <vector>

namespace kanade {

std::string ToUtf8(const std::wstring& s);
std::wstring FromUtf8(const std::string& s);

// 欄を並べて1行にする（改行は付けない）
std::string EncodeLine(const std::vector<std::string>& fields);
// 1行を欄に分ける
std::vector<std::string> DecodeLine(const std::string& line);

// エンジンから届いた命令（"c" の行の、id より後ろ）
using Command = std::vector<std::string>;

}  // namespace kanade
