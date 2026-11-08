// Kanade の fcitx5 のアドオン。キーをエンジン（desktop の常駐する JVM）へ AF_UNIX のソケットで送り、返ってきた命令で
// 入力中の文字・確定・候補の窓・音声入力の表示を書く。やりとりは Windows の TSF の DLL と同じ（docs/WINDOWS-PROTOCOL.md）。
// fcitx5 は1つのプロセスなので、エンジンへのつなぎも1本（入力欄が替わったら、入力中の文字を確定してから次へ）
#include "keymap.h"
#include "protocol.h"

#include <fcitx-utils/event.h>
#include <fcitx-utils/eventloopinterface.h>
#include <fcitx-utils/log.h>
#include <fcitx-utils/utf8.h>
#include <fcitx/action.h>
#include <fcitx/addonfactory.h>
#include <fcitx/addoninstance.h>
#include <fcitx/addonmanager.h>
#include <fcitx/candidatelist.h>
#include <fcitx/event.h>
#include <fcitx/inputcontext.h>
#include <fcitx/inputmethodengine.h>
#include <fcitx/inputpanel.h>
#include <fcitx/instance.h>
#include <fcitx/statusarea.h>
#include <fcitx/text.h>
#include <fcitx/userinterfacemanager.h>

#include <chrono>
#include <cstdlib>
#include <fcntl.h>
#include <map>
#include <optional>
#include <tuple>
#include <poll.h>
#include <set>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

namespace kanade {

FCITX_DEFINE_LOG_CATEGORY(kanadeLog, "kanade");
#define KLOG() FCITX_LOGC(kanadeLog, Info)

using Clock = std::chrono::steady_clock;
using Fields = std::vector<std::string>;

static std::string env(const char *name, const std::string &fallback) {
    const char *v = std::getenv(name);
    return v && *v ? v : fallback;
}

static std::string socketPath() {
    return env("KANADE_SOCKET", env("XDG_RUNTIME_DIR", "/tmp") + "/kanade-engine.sock");
}

static std::string enginePath() {
    std::string home = env("HOME", "");
    return env("KANADE_ENGINE", env("XDG_DATA_HOME", home + "/.local/share") + "/kanade/bin/kanade-engine");
}

static int toInt(const std::string &s, int fallback = 0) {
    char *end = nullptr;
    long v = std::strtol(s.c_str(), &end, 10);
    return end && end != s.c_str() ? static_cast<int>(v) : fallback;
}

class KanadeEngine;

// 候補の窓の1つ。クリックしたら番号をエンジンへ送る（数字キーと同じ。結果はエンジンから自分から届く）
class KanadeCandidate : public fcitx::CandidateWord {
public:
    KanadeCandidate(KanadeEngine *engine, int index, const std::string &text, const std::string &comment);
    void select(fcitx::InputContext *ic) const override;

private:
    KanadeEngine *engine_;
    int index_;
};

class KanadeEngine : public fcitx::InputMethodEngineV2 {
public:
    explicit KanadeEngine(fcitx::Instance *instance) : instance_(instance) {
        // 入力方式の表示（トレイなど）のメニュー。エンジンが設定の画面（linux/settings）をそのページで開く。記録はファイルを開く
        // 表示の言語はエンジンの既定（ui_language=auto）と同じく、LANG が日本語なら日本語、ほかは英語
        bool ja = env("LC_ALL", env("LC_MESSAGES", env("LANG", ""))).rfind("ja", 0) == 0;
        const std::tuple<const char *, const char *, const char *> items[] = {
            {"settings", "設定", "Settings"}, {"voice", "音声入力", "Voice input"}, {"user-dictionary", "ユーザー辞書", "User dictionary"},
            {"snippets", "スニペット", "Snippets"}, {"log", "記録", "Log"}};
        for (const auto &[id, labelJa, labelEn] : items) {
            auto action = std::make_unique<fcitx::SimpleAction>();
            action->setShortText(std::string("Kanade: ") + (ja ? labelJa : labelEn));
            std::string item = id;
            action->connect<fcitx::SimpleAction::Activated>([this, item](fcitx::InputContext *) {
                if (ensureConnected()) send({"menu", item});
            });
            instance_->userInterfaceManager().registerAction(std::string("kanade-") + id, action.get());
            actions_.push_back(std::move(action));
        }
        // 録音キー（音声入力）は、どの入力方式（英字の keyboard-us など）でも効かせる。入力方式より先にキーを見る
        setVoiceKeys("F12,Alt_R", "Control+Alt+space");
        voiceWatcher_ = instance_->watchEvent(fcitx::EventType::InputContextKeyEvent, fcitx::EventWatcherPhase::PreInputMethod,
                                              [this](fcitx::Event &e) { onAnyKey(static_cast<fcitx::KeyEvent &>(e)); });
        // 録音キーの設定をエンジンからもらうため、fcitx5 の起動のあとでつなぐ（エンジンが動いていなければ起こし、少し待ってつなぎ直す）
        connectTimer_ = instance_->eventLoop().addTimeEvent(CLOCK_MONOTONIC, fcitx::now(CLOCK_MONOTONIC) + 1000000, 0,
                                                            [this](fcitx::EventSourceTime *source, uint64_t) {
                                                                if (ensureConnected() || ++connectTries_ >= 20) return true;
                                                                source->setNextInterval(3000000);
                                                                source->setOneShot();
                                                                return true;
                                                            });
    }
    ~KanadeEngine() override { disconnect(); }

    void keyEvent(const fcitx::InputMethodEntry &, fcitx::KeyEvent &event) override {
        auto *ic = event.inputContext();
        current_ = ic->watch();
        WinKey k = toWinKey(event.key());
        bool release = event.isRelease();
        if (debug_) KLOG() << "key " << event.key().toString() << (release ? " up" : " down") << " vk=" << k.vk << " ch=" << k.ch << " mods=" << k.mods;
        if (release) {
            down_.erase(k.vk ? k.vk : static_cast<int>(k.ch));
            // 離したことを使うのは、右 Alt の単押し（日本語のオン/オフ）と録音キー（離したら認識）だけ
            if (k.vk != vk::RMENU && k.vk != vk::F12) return;
        }
        if (k.vk == 0 && k.ch == 0) return;
        if (!ensureConnected()) return;
        if (wantJapanese_) {
            send({"focus", "1"});
            send({"mode", "1"});
            wantJapanese_ = false;
        }
        int code = k.vk ? k.vk : static_cast<int>(k.ch);
        bool repeat = !release && down_.count(code);
        if (!release) down_.insert(code);
        if (!composing_ && !release) sendContext(ic);
        int id = nextId_++;
        send({"key", std::to_string(id), release ? "u" : "d", std::to_string(k.vk), std::to_string(k.ch),
              std::to_string(k.mods), repeat ? "1" : "0"});
        auto consumed = waitFor(id);
        if (!consumed.has_value()) {
            // エンジンが遅い（起動の直後など）。キーは食べて、返事が来たら書く。何度も来なければ切ってキーを素通しにする
            if (++timeouts_ >= 3) {
                KLOG() << "engine does not answer; disconnecting";
                disconnect();
                return;
            }
            late_.insert(id);
            event.filterAndAccept();
            return;
        }
        if (*consumed) event.filterAndAccept();
    }

    void activate(const fcitx::InputMethodEntry &, fcitx::InputContextEvent &event) override {
        auto *ic = event.inputContext();
        current_ = ic->watch();
        for (auto &action : actions_) ic->statusArea().addAction(fcitx::StatusGroup::InputMethod, action.get());
        // Kanade に入ったら（入力欄に戻ったときも）いつも日本語にする。英字は fcitx5 の切り替え（keyboard-us）が受け持つ。
        // fcitx5 は Ctrl+Space・半角/全角などを自分の切り替えに使い、Kanade まで届かないため、Kanade の中に英字の状態を残すと戻せなくなる。
        // エンジンを起こしている途中なら、つながったときに送る
        wantJapanese_ = true;
        if (!ensureConnected()) return;
        send({"focus", "1"});
        if (wantJapanese_) {
            send({"mode", "1"});
            wantJapanese_ = false;
        }
    }

    void deactivate(const fcitx::InputMethodEntry &, fcitx::InputContextEvent &event) override {
        commitAll(event.inputContext());
        if (connected()) send({"focus", "0"});
        clearPanel(event.inputContext());
    }

    // アプリが入力をやり直させた（マウスで入力欄を触ったなど）。入力中の文字は確定する（Windows 版と同じ）
    void reset(const fcitx::InputMethodEntry &, fcitx::InputContextEvent &event) override {
        commitAll(event.inputContext());
        clearPanel(event.inputContext());
    }

    std::string subMode(const fcitx::InputMethodEntry &, fcitx::InputContext &) override {
        return japanese_ ? "ひらがな" : "Direct";
    }
    std::string subModeLabelImpl(const fcitx::InputMethodEntry &, fcitx::InputContext &) override {
        return japanese_ ? "あ" : "A";
    }

    void selectCandidate(int index) {
        if (connected()) send({"select", std::to_string(index)});
    }

private:
    // ---- 音声入力（どの入力方式でも） ----
    // 押している間だけ録音するキー・押すたびに始める/終えるキー・録音中の Esc（取り消し）。
    // Kanade のときは日本語、ほかの入力方式（keyboard-us など）のときは英語のモデルで文字にする（エンジンが選ぶ）
    enum class VoiceMode { None, Hold, Toggle };

    void setVoiceKeys(const std::string &hold, const std::string &toggle) {
        holdKeys_.clear();
        size_t pos = 0;
        while (pos <= hold.size()) {
            size_t comma = hold.find(',', pos);
            std::string name = hold.substr(pos, comma == std::string::npos ? std::string::npos : comma - pos);
            if (!name.empty()) {
                fcitx::Key key(name);
                if (key.isValid()) holdKeys_.push_back(key.sym());
                else KLOG() << "unknown voice key: " << name;
            }
            if (comma == std::string::npos) break;
            pos = comma + 1;
        }
        toggleKey_ = toggle.empty() ? fcitx::Key() : fcitx::Key(toggle).normalize();
        KLOG() << "voice keys: hold=" << hold << " toggle=" << toggle;
    }

    bool isHoldKey(const fcitx::Key &key) const {
        for (auto sym : holdKeys_) {
            if (key.sym() == sym) return true;
        }
        return false;
    }

    void onAnyKey(fcitx::KeyEvent &event) {
        auto *ic = event.inputContext();
        const fcitx::Key &key = event.key();
        bool release = event.isRelease();
        auto sym = key.sym();
        if (release) {
            // 押したときに使ったキーは、離したときも使う（アプリへ片方だけ届かないように）
            if (!voiceDown_.erase(sym)) return;
            event.filterAndAccept();
            if (voiceMode_ == VoiceMode::Hold && isHoldKey(key)) {
                // すぐ離した（押し間違い）なら、認識せずに捨てる
                bool tap = Clock::now() - voiceStarted_ < std::chrono::milliseconds(300);
                send({"voice", tap ? "cancel" : "stop", tap ? "quiet" : ""});
                voiceMode_ = VoiceMode::None;
            }
            return;
        }
        bool repeat = voiceDown_.count(sym) > 0;
        if (voiceMode_ != VoiceMode::None && sym == FcitxKey_Escape) {
            voiceDown_.insert(sym);
            event.filterAndAccept();
            send({"voice", "cancel"});
            voiceMode_ = VoiceMode::None;
            return;
        }
        bool hold = isHoldKey(key);
        bool toggle = toggleKey_.isValid() && key.normalize().check(toggleKey_);
        if (!hold && !toggle) return;
        voiceDown_.insert(sym);
        event.filterAndAccept();
        if (repeat) return;
        if (voiceMode_ != VoiceMode::None) {
            // 録音中にもう一度押したら終える（トグルのキーで始めたとき。押しているキーでは離したときに終える）
            if (toggle || voiceMode_ == VoiceMode::Toggle) {
                send({"voice", "stop"});
                voiceMode_ = VoiceMode::None;
            }
            return;
        }
        if (!ensureConnected()) {
            showNotice(ic, env("LANG", "").rfind("ja", 0) == 0 ? "Kanade を起動しています…" : "Starting Kanade…");
            return;
        }
        current_ = ic->watch();
        // 入力中の文字を確定してから録音を始める（Kanade のときはエンジンが確定を返す）
        if (instance_->inputMethod(ic) == "kanade" && composing_) commitAll(ic);
        voiceMode_ = hold ? VoiceMode::Hold : VoiceMode::Toggle;
        voiceStarted_ = Clock::now();
        send({"voice", "start", instance_->inputMethod(ic) == "kanade" ? "ja" : "en"});
    }

    // カーソルの近くに短く出す（エンジンにつながっていないとき）
    void showNotice(fcitx::InputContext *ic, const std::string &text) {
        apply(ic, {{"indicator", text, "2000"}});
    }

    bool connected() const { return fd_ >= 0; }

    bool ensureConnected() {
        if (fd_ >= 0) return true;
        int fd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (fd < 0) return false;
        sockaddr_un addr{};
        addr.sun_family = AF_UNIX;
        std::string path = socketPath();
        if (path.size() >= sizeof(addr.sun_path)) {
            close(fd);
            return false;
        }
        std::copy(path.begin(), path.end(), addr.sun_path);
        if (::connect(fd, reinterpret_cast<sockaddr *>(&addr), sizeof(addr)) != 0) {
            close(fd);
            launchEngine();
            return false;
        }
        fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) | O_NONBLOCK);
        fd_ = fd;
        buffer_.clear();
        pending_.clear();
        late_.clear();
        timeouts_ = 0;
        composing_ = false;
        io_ = instance_->eventLoop().addIOEvent(fd_, fcitx::IOEventFlag::In, [this](fcitx::EventSourceIO *, int, fcitx::IOEventFlags) {
            readAvailable();
            return true;
        });
        send({"hello", "1", std::to_string(getpid()), "fcitx5"});
        KLOG() << "connected to " << path;
        return true;
    }

    // エンジンが動いていなければ起こす（ログインの直後、最初のキーで）。続けて何度も起こさない
    void launchEngine() {
        auto now = Clock::now();
        if (launched_ != Clock::time_point{} && now - launched_ < std::chrono::seconds(30)) return;
        launched_ = now;
        std::string path = enginePath();
        if (access(path.c_str(), X_OK) != 0) {
            KLOG() << "engine not found: " << path;
            return;
        }
        KLOG() << "starting " << path;
        pid_t child = fork();
        if (child == 0) {
            // 2回 fork して、fcitx5 の子として残らないようにする
            if (fork() == 0) {
                setsid();
                int null = open("/dev/null", O_RDWR);
                dup2(null, 0);
                dup2(null, 1);
                dup2(null, 2);
                execl(path.c_str(), path.c_str(), "serve", static_cast<char *>(nullptr));
                _exit(127);
            }
            _exit(0);
        }
        if (child > 0) waitpid(child, nullptr, 0);
    }

    void disconnect() {
        io_.reset();
        voiceMode_ = VoiceMode::None;
        if (fd_ >= 0) close(fd_);
        fd_ = -1;
        composing_ = false;
        if (auto *ic = current_.get()) clearPanel(ic);
    }

    void send(const Fields &fields) {
        if (fd_ < 0) return;
        std::string line = encode(fields) + "\n";
        size_t off = 0;
        while (off < line.size()) {
            ssize_t n = ::send(fd_, line.data() + off, line.size() - off, MSG_NOSIGNAL);
            if (n > 0) {
                off += n;
            } else if (n < 0 && (errno == EAGAIN || errno == EINTR)) {
                pollfd p{fd_, POLLOUT, 0};
                poll(&p, 1, 100);
            } else {
                disconnect();
                return;
            }
        }
    }

    // 届いている分を読んで、1行ずつ処理する。切れたら false
    bool readAvailable() {
        char buf[8192];
        while (fd_ >= 0) {
            ssize_t n = recv(fd_, buf, sizeof(buf), 0);
            if (n > 0) {
                buffer_.append(buf, n);
                continue;
            }
            if (n < 0 && (errno == EAGAIN || errno == EINTR)) break;
            KLOG() << "engine closed the connection";
            disconnect();
            return false;
        }
        size_t start = 0, nl;
        while ((nl = buffer_.find('\n', start)) != std::string::npos) {
            std::string line = buffer_.substr(start, nl - start);
            start = nl + 1;
            if (!line.empty()) onLine(line);
        }
        buffer_.erase(0, start);
        return fd_ >= 0;
    }

    // [id] の返事（done）が来るまで待つ。来たら consumed、時間切れなら空
    std::optional<bool> waitFor(int id) {
        waiting_ = id;
        answered_.reset();
        auto deadline = Clock::now() + std::chrono::milliseconds(300);
        while (!answered_.has_value() && fd_ >= 0) {
            int left = static_cast<int>(std::chrono::duration_cast<std::chrono::milliseconds>(deadline - Clock::now()).count());
            if (left <= 0) break;
            pollfd p{fd_, POLLIN, 0};
            if (poll(&p, 1, left) <= 0) continue;
            if (!readAvailable()) break;
        }
        waiting_ = -1;
        return answered_;
    }

    void onLine(const std::string &line) {
        Fields f = decode(line);
        if (f[0] == "c" && f.size() >= 5 && f[2] == "voicekeys") {
            // 録音キーの設定（つないだときと、設定を変えたとき）。入力欄がなくても受ける
            setVoiceKeys(f[3], f[4]);
        } else if (f[0] == "c" && f.size() >= 3) {
            pending_[toInt(f[1])].push_back(Fields(f.begin() + 2, f.end()));
        } else if (f[0] == "done" && f.size() >= 3) {
            int id = toInt(f[1]);
            auto commands = std::move(pending_[id]);
            pending_.erase(id);
            timeouts_ = 0;
            bool mine = id == waiting_;
            bool late = late_.erase(id) > 0;
            if (id == 0 || mine || late) {
                if (auto *ic = current_.get()) apply(ic, commands);
            }
            if (mine) answered_ = f[2] == "1";
        }
    }

    void apply(fcitx::InputContext *ic, const std::vector<Fields> &commands) {
        if (commands.empty()) return;
        auto &panel = ic->inputPanel();
        for (const auto &c : commands) {
            const std::string &type = c[0];
            if (type == "compose" && c.size() >= 2) {
                setPreedit(ic, c);
            } else if (type == "commit" && c.size() >= 2) {
                setPreedit(ic, {"compose", ""});
                ic->commitString(c[1]);
            } else if (type == "mode" && c.size() >= 2) {
                bool on = c[1] == "1";
                if (on != japanese_) {
                    japanese_ = on;
                    ic->updateUserInterface(fcitx::UserInterfaceComponent::StatusArea);
                    instance_->showInputMethodInformation(ic);
                }
            } else if (type == "cands" && c.size() >= 3) {
                showCandidates(ic, c);
            } else if (type == "hidecands") {
                hideCandidates(ic);
            } else if (type == "indicator" && c.size() >= 2) {
                panel.setAuxUp(fcitx::Text(c[1]));
                int ms = c.size() >= 3 ? toInt(c[2]) : 0;
                indicatorTimer_.reset();
                if (ms > 0) {
                    auto ref = ic->watch();
                    indicatorTimer_ = instance_->eventLoop().addTimeEvent(
                        CLOCK_MONOTONIC, fcitx::now(CLOCK_MONOTONIC) + ms * 1000ULL, 0, [this, ref](fcitx::EventSourceTime *, uint64_t) {
                            if (auto *target = ref.get()) {
                                target->inputPanel().setAuxUp(fcitx::Text());
                                target->updateUserInterface(fcitx::UserInterfaceComponent::InputPanel);
                            }
                            return true;
                        });
                }
            } else if (type == "hideindicator") {
                indicatorTimer_.reset();
                panel.setAuxUp(fcitx::Text());
            } else if (type == "replace" && c.size() >= 4) {
                replaceBeforeCursor(ic, c[1], c[2], c[3]);
            }
        }
        ic->updatePreedit();
        ic->updateUserInterface(fcitx::UserInterfaceComponent::InputPanel);
    }

    // compose <文字> <カーソル> <選んでいる文節> <文節の長さ,…>。長さとカーソルは Java の文字数（UTF-16）
    void setPreedit(fcitx::InputContext *ic, const Fields &c) {
        const std::string &text = c[1];
        composing_ = !text.empty();
        fcitx::Text preedit;
        if (!text.empty()) {
            int focused = c.size() >= 4 ? toInt(c[3], -1) : -1;
            std::vector<int> lengths;
            if (c.size() >= 5 && !c[4].empty()) {
                size_t pos = 0;
                while (pos <= c[4].size()) {
                    size_t comma = c[4].find(',', pos);
                    lengths.push_back(toInt(c[4].substr(pos, comma == std::string::npos ? std::string::npos : comma - pos)));
                    if (comma == std::string::npos) break;
                    pos = comma + 1;
                }
            }
            int focusedStart = -1;
            if (lengths.size() == 1 && focused == 0) {
                // 1文節だけの変換
                preedit.append(text, {fcitx::TextFormatFlag::Underline, fcitx::TextFormatFlag::HighLight});
                focusedStart = 0;
            } else if (lengths.size() <= 1) {
                preedit.append(text, fcitx::TextFormatFlag::Underline);
            } else {
                size_t units = 0;
                for (size_t i = 0; i < lengths.size(); i++) {
                    size_t from = utf16ToByte(text, units);
                    units += lengths[i];
                    size_t to = i + 1 == lengths.size() ? text.size() : utf16ToByte(text, units);
                    fcitx::TextFormatFlags flags = fcitx::TextFormatFlag::Underline;
                    if (static_cast<int>(i) == focused) {
                        flags |= fcitx::TextFormatFlag::HighLight;
                        focusedStart = static_cast<int>(from);
                    }
                    preedit.append(text.substr(from, to - from), flags);
                }
            }
            // 変換中は、カーソルを選んでいる文節の頭に置く。Wayland のアプリ（text-input-v3）へは文節の色分けが届かず、
            // fcitx5 は「カーソルが強調の頭にあるときだけ、強調の範囲をカーソルの範囲として渡す」ため、こうしないと
            // どの文節を変換しているか・区切りがどこかがアプリに映らない（mozc と同じ置き方）
            int cursor = c.size() >= 3 ? toInt(c[2], -1) : -1;
            if (focusedStart >= 0) preedit.setCursor(focusedStart);
            else preedit.setCursor(cursor < 0 ? static_cast<int>(text.size()) : static_cast<int>(utf16ToByte(text, cursor)));
        }
        auto &panel = ic->inputPanel();
        if (ic->capabilityFlags().test(fcitx::CapabilityFlag::Preedit)) {
            panel.setClientPreedit(preedit);
            panel.setPreedit(fcitx::Text());
        } else {
            panel.setPreedit(preedit);
            panel.setClientPreedit(fcitx::Text());
        }
    }

    // cands <選んでいる位置> <数> <候補> <説明>…。エンジンは9件ずつの頁で数字を受けるので、いまの頁だけを出す
    void showCandidates(fcitx::InputContext *ic, const Fields &c) {
        int selected = toInt(c[1], -1);
        int count = toInt(c[2]);
        int page = selected >= 0 ? selected / PAGE : 0;
        auto list = std::make_unique<fcitx::CommonCandidateList>();
        list->setPageSize(PAGE);
        list->setLayoutHint(fcitx::CandidateLayoutHint::Vertical);
        std::vector<std::string> labels;
        for (int i = 1; i <= PAGE; i++) labels.push_back(std::to_string(i) + ". ");
        list->setLabels(labels);
        for (int i = page * PAGE; i < count && i < (page + 1) * PAGE; i++) {
            size_t at = 3 + 2 * static_cast<size_t>(i);
            if (at >= c.size()) break;
            list->append<KanadeCandidate>(this, i, c[at], at + 1 < c.size() ? c[at + 1] : "");
        }
        if (list->totalSize() == 0) {
            hideCandidates(ic);
            return;
        }
        if (selected >= 0) list->setGlobalCursorIndex(selected - page * PAGE);
        int pages = (count + PAGE - 1) / PAGE;
        if (pages > 1) ic->inputPanel().setAuxDown(fcitx::Text(std::to_string(page + 1) + "/" + std::to_string(pages)));
        else ic->inputPanel().setAuxDown(fcitx::Text());
        ic->inputPanel().setCandidateList(std::move(list));
    }

    // スニペット（直接入力中）: カーソルの前が expected なら消して replacement を入れる。違えば打った文字を入れる
    void replaceBeforeCursor(fcitx::InputContext *ic, const std::string &expected, const std::string &typed, const std::string &replacement) {
        int ch = toInt(typed);
        std::string typedText = fcitx::utf8::UCS4ToUTF8(static_cast<uint32_t>(ch));
        auto &st = ic->surroundingText();
        if (ic->capabilityFlags().test(fcitx::CapabilityFlag::SurroundingText) && st.isValid()) {
            const std::string &text = st.text();
            size_t cursor = fcitx::utf8::ncharByteLength(text.begin(), st.cursor());
            size_t n = fcitx::utf8::length(expected);
            if (cursor >= expected.size() && text.compare(cursor - expected.size(), expected.size(), expected) == 0) {
                ic->deleteSurroundingText(-static_cast<int>(n), static_cast<unsigned int>(n));
                ic->commitString(replacement);
                return;
            }
        }
        ic->commitString(typedText);
    }

    // 入力を始める前の、カーソルの前後の文（zenz の文脈）
    void sendContext(fcitx::InputContext *ic) {
        auto &st = ic->surroundingText();
        if (!ic->capabilityFlags().test(fcitx::CapabilityFlag::SurroundingText) || !st.isValid()) return;
        const std::string &text = st.text();
        size_t length = fcitx::utf8::length(text);
        size_t cursor = std::min<size_t>(st.cursor(), length);
        size_t from = cursor > CONTEXT ? cursor - CONTEXT : 0;
        size_t fromByte = fcitx::utf8::ncharByteLength(text.begin(), from);
        size_t cursorByte = fcitx::utf8::ncharByteLength(text.begin(), cursor);
        size_t toByte = fcitx::utf8::ncharByteLength(text.begin(), std::min(length, cursor + CONTEXT));
        send({"ctx", text.substr(fromByte, cursorByte - fromByte), text.substr(cursorByte, toByte - cursorByte)});
    }

    void commitAll(fcitx::InputContext *ic) {
        if (!composing_ || !connected()) return;
        auto ref = ic->watch();
        current_ = ref;
        int id = nextId_++;
        send({"commitall", std::to_string(id)});
        waitFor(id);
        composing_ = false;
    }

    // 候補の窓を閉じる。頁の表示（「1/3」）も一緒に消す（残すと、カーソルの下に頁の表示だけが出続ける）
    void hideCandidates(fcitx::InputContext *ic) {
        ic->inputPanel().setCandidateList(nullptr);
        ic->inputPanel().setAuxDown(fcitx::Text());
    }

    void clearPanel(fcitx::InputContext *ic) {
        auto &panel = ic->inputPanel();
        indicatorTimer_.reset();
        // 何も出していなければ触らない（抜けるときに空の入力中の文字を送り直すと、次のキーが落ちることがある）
        if (panel.empty()) return;
        panel.reset();
        indicatorTimer_.reset();
        ic->updatePreedit();
        ic->updateUserInterface(fcitx::UserInterfaceComponent::InputPanel);
    }

    static constexpr int PAGE = 9;
    static constexpr size_t CONTEXT = 64;

    fcitx::Instance *instance_;
    int fd_ = -1;
    std::unique_ptr<fcitx::EventSourceIO> io_;
    std::unique_ptr<fcitx::EventSourceTime> indicatorTimer_;
    std::string buffer_;
    std::map<int, std::vector<Fields>> pending_;
    std::set<int> late_;
    std::set<int> down_;
    int nextId_ = 1;
    int waiting_ = -1;
    int timeouts_ = 0;
    std::optional<bool> answered_;
    bool composing_ = false;
    bool japanese_ = true;
    bool wantJapanese_ = false;
    // KANADE_DEBUG=1 で fcitx5 を起こすと、届いたキーを記録に出す
    bool debug_ = std::getenv("KANADE_DEBUG") != nullptr;
    Clock::time_point launched_{};
    fcitx::TrackableObjectReference<fcitx::InputContext> current_;
    std::vector<std::unique_ptr<fcitx::SimpleAction>> actions_;
    std::unique_ptr<fcitx::HandlerTableEntry<fcitx::EventHandler>> voiceWatcher_;
    std::unique_ptr<fcitx::EventSourceTime> connectTimer_;
    int connectTries_ = 0;
    std::vector<fcitx::KeySym> holdKeys_;
    fcitx::Key toggleKey_;
    std::set<fcitx::KeySym> voiceDown_;
    VoiceMode voiceMode_ = VoiceMode::None;
    Clock::time_point voiceStarted_{};
};

KanadeCandidate::KanadeCandidate(KanadeEngine *engine, int index, const std::string &text, const std::string &comment)
    : fcitx::CandidateWord(fcitx::Text(text)), engine_(engine), index_(index) {
    if (!comment.empty()) setComment(fcitx::Text(comment));
}

void KanadeCandidate::select(fcitx::InputContext *) const { engine_->selectCandidate(index_); }

class KanadeFactory : public fcitx::AddonFactory {
public:
    fcitx::AddonInstance *create(fcitx::AddonManager *manager) override { return new KanadeEngine(manager->instance()); }
};

} // namespace kanade

FCITX_ADDON_FACTORY_V2_BACKWARDS(kanade, kanade::KanadeFactory)
