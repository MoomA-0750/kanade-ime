#include "EngineClient.h"

#include <afunix.h>
#include <shlobj.h>

#include "Log.h"

namespace kanade {

namespace {
std::string SocketPath() {
    PWSTR local = nullptr;
    // AppData には置かない（パッケージ化されたアプリでは AppData が仮想化され、接続が WSAEINVAL になる）
    std::wstring profile;
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_Profile, 0, nullptr, &local))) {
        profile = local;
        CoTaskMemFree(local);
    } else {
        // AppContainer の中では取れないことがある
        wchar_t buf[MAX_PATH];
        DWORD n = GetEnvironmentVariableW(L"USERPROFILE", buf, MAX_PATH);
        if (n == 0 || n >= MAX_PATH) return {};
        profile.assign(buf, n);
    }
    std::wstring path = profile + L"\\.kanade\\engine.sock";
    // Java（エンジン）もソケットの場所を ANSI のコードページで渡すので、それに合わせる
    int n = WideCharToMultiByte(CP_ACP, 0, path.c_str(), -1, nullptr, 0, nullptr, nullptr);
    std::string out(n > 0 ? n - 1 : 0, '\0');
    WideCharToMultiByte(CP_ACP, 0, path.c_str(), -1, out.data(), n, nullptr, nullptr);
    return out;
}
}  // namespace

EngineClient::EngineClient(std::function<void()> onPush) : onPush_(std::move(onPush)) {
    WSADATA data;
    wsa_ = WSAStartup(MAKEWORD(2, 2), &data) == 0;
}

EngineClient::~EngineClient() {
    Close();
    if (wsa_) WSACleanup();
}

EngineClient::State EngineClient::EnsureConnected() {
    if (connected_) return State::Connected;
    if (!wsa_) {
        lastError_ = -2;
        return State::Failed;
    }
    ULONGLONG now = GetTickCount64();
    if (lastAttempt_ != 0 && now - lastAttempt_ < 1000) return State::Failed;
    lastAttempt_ = now;
    Close();

    std::string path = SocketPath();
    if (path.empty() || path.size() >= UNIX_PATH_MAX) {
        if (lastError_ != -1) Log("no socket path");
        lastError_ = -1;
        return State::Failed;
    }
    SOCKET s = socket(AF_UNIX, SOCK_STREAM, 0);
    if (s == INVALID_SOCKET) {
        int error = WSAGetLastError();
        if (error != lastError_) Log("socket failed %d", error);
        lastError_ = error;
        return State::Failed;
    }
    SOCKADDR_UN addr = {};
    addr.sun_family = AF_UNIX;
    memcpy(addr.sun_path, path.c_str(), path.size());
    if (connect(s, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
        int error = WSAGetLastError();
        if (error != lastError_) Log("connect failed %d", error);
        lastError_ = error;
        closesocket(s);
        return State::Failed;
    }
    lastError_ = 0;
    socket_ = s;
    connected_ = true;
    reader_ = std::thread([this, s] { ReadLoop(s); });

    wchar_t exe[MAX_PATH] = {};
    GetModuleFileNameW(nullptr, exe, MAX_PATH);
    const wchar_t* name = wcsrchr(exe, L'\\');
    Send({"hello", "1", std::to_string(GetCurrentProcessId()), ToUtf8(name ? name + 1 : exe)});
    Log("connected");
    return State::NewlyConnected;
}

bool EngineClient::Request(const std::string& type, std::vector<std::string> fields, DWORD timeoutMs, Reply* reply) {
    if (!connected_) return false;
    int id = nextId_++;
    if (nextId_ > 1000000000) nextId_ = 1;
    {
        std::lock_guard lock(mutex_);
        waitingId_ = id;
        gotReply_ = false;
        reply_ = {};
    }
    fields.insert(fields.begin(), {type, std::to_string(id)});
    if (!SendLine(EncodeLine(fields))) return false;
    std::unique_lock lock(mutex_);
    bool ok = cv_.wait_for(lock, std::chrono::milliseconds(timeoutMs), [&] { return gotReply_ || !connected_; }) && gotReply_;
    waitingId_ = 0;
    if (!ok) {
        Log("no reply for %s %d", type.c_str(), id);
        return false;
    }
    *reply = std::move(reply_);
    return true;
}

void EngineClient::Send(const std::vector<std::string>& fields) {
    if (connected_) SendLine(EncodeLine(fields));
}

bool EngineClient::SendLine(const std::string& line) {
    std::string data = line + "\n";
    const char* p = data.data();
    int left = static_cast<int>(data.size());
    while (left > 0) {
        int n = send(socket_, p, left, 0);
        if (n <= 0) {
            connected_ = false;
            cv_.notify_all();
            return false;
        }
        p += n;
        left -= n;
    }
    return true;
}

std::vector<std::vector<Command>> EngineClient::TakePushes() {
    std::lock_guard lock(mutex_);
    std::vector<std::vector<Command>> out(pushes_.begin(), pushes_.end());
    pushes_.clear();
    return out;
}

void EngineClient::ReadLoop(SOCKET s) {
    std::string buffer;
    char chunk[4096];
    while (true) {
        int n = recv(s, chunk, sizeof(chunk), 0);
        if (n <= 0) break;
        buffer.append(chunk, n);
        size_t start = 0;
        for (size_t nl; (nl = buffer.find('\n', start)) != std::string::npos; start = nl + 1) {
            if (nl > start) OnLine(buffer.substr(start, nl - start));
        }
        buffer.erase(0, start);
    }
    {
        std::lock_guard lock(mutex_);
        connected_ = false;
    }
    cv_.notify_all();
    Log("disconnected");
}

void EngineClient::OnLine(const std::string& line) {
    std::vector<std::string> f = DecodeLine(line);
    if (f[0] == "c" && f.size() >= 3) {
        batch_.emplace_back(f.begin() + 2, f.end());
        return;
    }
    if (f[0] != "done" || f.size() < 2) return;
    int id = atoi(f[1].c_str());
    bool consumed = f.size() >= 3 && f[2] == "1";
    std::vector<Command> commands = std::move(batch_);
    batch_.clear();
    if (id == 0) {
        if (commands.empty()) return;
        {
            std::lock_guard lock(mutex_);
            pushes_.push_back(std::move(commands));
        }
        if (onPush_) onPush_();
        return;
    }
    {
        std::lock_guard lock(mutex_);
        if (id != waitingId_) return;  // 待つのをやめた古い要求の返事
        reply_.consumed = consumed;
        reply_.commands = std::move(commands);
        gotReply_ = true;
    }
    cv_.notify_all();
}

void EngineClient::Close() {
    if (socket_ != INVALID_SOCKET) {
        shutdown(socket_, SD_BOTH);
        closesocket(socket_);
        socket_ = INVALID_SOCKET;
    }
    if (reader_.joinable()) reader_.join();
    connected_ = false;
    batch_.clear();
}

}  // namespace kanade
