// エンジンとのやりとりだけを試す道具。DLL と同じ EngineClient で、キー列（引数）を test → key の順に送り、届いた命令を出す
//   kanade_probe.exe "^nihongo\n"     ^ は変換キー（日本語オン）、\n は Enter、空白は Space
#include <windows.h>

#include <cstdio>

#include "EngineClient.h"

using namespace kanade;

static void Print(const std::vector<Command>& commands) {
    for (const auto& c : commands) {
        std::string line;
        for (const auto& f : c) line += "[" + f + "]";
        printf("  %s\n", line.c_str());
    }
}

int main(int argc, char** argv) {
    SetConsoleOutputCP(CP_UTF8);
    EngineClient client([] {});
    if (client.EnsureConnected() == EngineClient::State::Failed) {
        printf("エンジンにつながらない\n");
        return 1;
    }
    for (int a = 1; a < argc; a++) {
        std::string keys = argv[a];
        for (size_t i = 0; i < keys.size(); i++) {
            char ch = keys[i];
            int vk = 0, chr = 0;
            if (ch == '^') vk = VK_CONVERT;
            else if (ch == '\\' && i + 1 < keys.size() && keys[i + 1] == 'n') { vk = VK_RETURN; i++; }
            else if (ch == ' ') { vk = VK_SPACE; chr = ' '; }
            else if (ch >= 'a' && ch <= 'z') { vk = ch - 'a' + 'A'; chr = ch; }
            else { chr = ch; }
            for (const char* ud : {"d", "u"}) {
                std::vector<std::string> fields = {ud, std::to_string(vk), std::to_string(*ud == 'd' ? chr : 0), "0", "0"};
                ULONGLONG t0 = GetTickCount64();
                Reply test, key;
                if (!client.Request("test", fields, 1000, &test)) { printf("test に返事がない\n"); return 1; }
                bool consumed = false;
                if (test.consumed) {
                    if (!client.Request("key", fields, 1000, &key)) { printf("key に返事がない\n"); return 1; }
                    consumed = key.consumed;
                }
                if (*ud == 'd') {
                    printf("%c vk=%d test=%d consumed=%d %llums\n", ch, vk, test.consumed, consumed, GetTickCount64() - t0);
                    Print(key.commands);
                }
            }
        }
    }
    for (const auto& batch : client.TakePushes()) { printf("push:\n"); Print(batch); }
    client.Send({"bye"});
    client.Close();
    return 0;
}
