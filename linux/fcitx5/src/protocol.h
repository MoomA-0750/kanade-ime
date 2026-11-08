// エンジンとのやりとりの1行（タブ区切り、欄の中の \ タブ 改行 CR は \\ \t \n \r）。docs/WINDOWS-PROTOCOL.md
#pragma once
#include <string>
#include <vector>

namespace kanade {
std::string encode(const std::vector<std::string> &fields);
std::vector<std::string> decode(const std::string &line);

// Java の文字数（UTF-16 の単位）から UTF-8 のバイト位置へ。足りなければ末尾
size_t utf16ToByte(const std::string &utf8, size_t units);
// UTF-8 の文字列の UTF-16 の長さ
size_t utf16Length(const std::string &utf8);
} // namespace kanade
