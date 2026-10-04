// 常駐のエンジンとのつなぎ（AF_UNIX のソケット）。返事を待つ要求は UI のスレッドから呼び、エンジンが自分から送るもの（id 0）は
// 読み取りのスレッドで溜めて onPush を呼ぶ（呼ばれた側は UI のスレッドへ投げ直すこと）
#pragma once
#include <winsock2.h>

#include <condition_variable>
#include <deque>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "Protocol.h"

namespace kanade {

struct Reply {
    bool consumed = false;
    std::vector<Command> commands;
};

class EngineClient {
public:
    enum class State { Connected, NewlyConnected, Failed };

    explicit EngineClient(std::function<void()> onPush);
    ~EngineClient();
    EngineClient(const EngineClient&) = delete;
    EngineClient& operator=(const EngineClient&) = delete;

    // つながっていなければつなぐ（失敗したら1秒は試さない）。つないだ直後は hello を送る
    State EnsureConnected();
    bool connected() const { return connected_; }
    int lastError() const { return lastError_; }

    // 要求を送って返事を待つ。返事が来ない・切れたら false（呼んだ側は素通しにする）
    bool Request(const std::string& type, std::vector<std::string> fields, DWORD timeoutMs, Reply* reply);
    // 返事の要らないものを送る
    void Send(const std::vector<std::string>& fields);
    // エンジンが自分から送ってきた命令のまとまり
    std::vector<std::vector<Command>> TakePushes();

    void Close();

private:
    void ReadLoop(SOCKET s);
    void OnLine(const std::string& line);
    bool SendLine(const std::string& line);

    std::function<void()> onPush_;
    SOCKET socket_ = INVALID_SOCKET;
    std::thread reader_;
    volatile bool connected_ = false;
    ULONGLONG lastAttempt_ = 0;
    int nextId_ = 1;
    bool wsa_ = false;
    int lastError_ = 0;

    std::mutex mutex_;
    std::condition_variable cv_;
    int waitingId_ = 0;
    bool gotReply_ = false;
    Reply reply_;
    std::vector<Command> batch_;  // 読み取りのスレッドだけが触る
    std::deque<std::vector<Command>> pushes_;
};

}  // namespace kanade
