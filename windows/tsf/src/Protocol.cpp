#include "Protocol.h"

#include <windows.h>

namespace kanade {

std::string ToUtf8(const std::wstring& s) {
    if (s.empty()) return {};
    int n = WideCharToMultiByte(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0, nullptr, nullptr);
    std::string out(n, '\0');
    WideCharToMultiByte(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), out.data(), n, nullptr, nullptr);
    return out;
}

std::wstring FromUtf8(const std::string& s) {
    if (s.empty()) return {};
    int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), nullptr, 0);
    std::wstring out(n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.data(), static_cast<int>(s.size()), out.data(), n);
    return out;
}

std::string EncodeLine(const std::vector<std::string>& fields) {
    std::string out;
    for (size_t i = 0; i < fields.size(); i++) {
        if (i) out += '\t';
        for (char c : fields[i]) {
            switch (c) {
                case '\\': out += "\\\\"; break;
                case '\t': out += "\\t"; break;
                case '\n': out += "\\n"; break;
                case '\r': out += "\\r"; break;
                default: out += c;
            }
        }
    }
    return out;
}

std::vector<std::string> DecodeLine(const std::string& line) {
    std::vector<std::string> fields(1);
    for (size_t i = 0; i < line.size(); i++) {
        char c = line[i];
        if (c == '\t') {
            fields.emplace_back();
        } else if (c == '\\' && i + 1 < line.size()) {
            char e = line[++i];
            fields.back() += e == 't' ? '\t' : e == 'n' ? '\n' : e == 'r' ? '\r' : e;
        } else {
            fields.back() += c;
        }
    }
    return fields;
}

}  // namespace kanade
