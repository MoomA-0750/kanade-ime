#include "protocol.h"

namespace kanade {

std::string encode(const std::vector<std::string> &fields) {
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

std::vector<std::string> decode(const std::string &line) {
    std::vector<std::string> fields(1);
    for (size_t i = 0; i < line.size(); i++) {
        char c = line[i];
        if (c == '\t') {
            fields.emplace_back();
        } else if (c == '\\' && i + 1 < line.size()) {
            char n = line[++i];
            fields.back() += n == 't' ? '\t' : n == 'n' ? '\n' : n == 'r' ? '\r' : n;
        } else {
            fields.back() += c;
        }
    }
    return fields;
}

static size_t sequenceLength(unsigned char c) {
    if (c < 0x80) return 1;
    if ((c >> 5) == 0x6) return 2;
    if ((c >> 4) == 0xE) return 3;
    if ((c >> 3) == 0x1E) return 4;
    return 1;
}

size_t utf16ToByte(const std::string &utf8, size_t units) {
    size_t i = 0, u = 0;
    while (i < utf8.size() && u < units) {
        size_t n = sequenceLength(static_cast<unsigned char>(utf8[i]));
        u += n == 4 ? 2 : 1;
        i += n;
    }
    return i < utf8.size() ? i : utf8.size();
}

size_t utf16Length(const std::string &utf8) {
    size_t i = 0, u = 0;
    while (i < utf8.size()) {
        size_t n = sequenceLength(static_cast<unsigned char>(utf8[i]));
        u += n == 4 ? 2 : 1;
        i += n;
    }
    return u;
}

} // namespace kanade
