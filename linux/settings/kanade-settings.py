#!/usr/bin/env python3
"""Kanade（Linux 版）の設定の画面。GTK4・libadwaita（GNOME の設定アプリと同じ見た目）。

Windows 版の設定の画面（windows/settings、WinUI 3）と同じ中身を、Linux の作法で作る。
設定・ユーザー辞書・スニペットのファイルの書式はエンジン（desktop/…/DesktopStore.kt）と同じで、
書き換えるとエンジンが 2 秒ほどで読み直す。項目を足すときは DesktopStore.kt と両方を直す。
Android 版の設定の読み込み・書き出し・学習の消去は、エンジンへソケットで頼む（docs/WINDOWS-PROTOCOL.md の admin）。

  kanade-settings [input|voice|dictionary|snippets|backup|usage]   開くページ
"""
import os
import socket
import subprocess
import sys
import threading
import urllib.request

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gdk, Gio, GLib, Gtk  # noqa: E402

HOME = os.path.expanduser("~")


def xdg(name, fallback):
    v = os.environ.get(name)
    return v if v else os.path.join(HOME, fallback)


CONFIG_DIR = os.path.join(xdg("XDG_CONFIG_HOME", ".config"), "kanade")
INSTALL_DIR = os.path.join(xdg("XDG_DATA_HOME", ".local/share"), "kanade")
DATA_DIR = os.environ.get("KANADE_DATA") or os.path.join(INSTALL_DIR, "data")
SETTINGS_FILE = os.path.join(CONFIG_DIR, "settings.properties")
DICTIONARY_FILE = os.path.join(CONFIG_DIR, "user_dictionary.txt")
SNIPPETS_FILE = os.path.join(CONFIG_DIR, "snippets.txt")
LOG_FILE = os.path.join(xdg("XDG_STATE_HOME", ".local/state"), "kanade", "engine.log")
SOCKET_PATH = os.environ.get("KANADE_SOCKET") or os.path.join(os.environ.get("XDG_RUNTIME_DIR", "/tmp"), "kanade-engine.sock")
ENGINE = os.path.join(INSTALL_DIR, "bin", "kanade-engine")

# ---- 設定のファイル ----

DEFAULTS = {
    "full_width_symbols": "true",
    "live_conversion": "true",
    "punctuation": "、。",
    "pair_brackets": "true",
    "spoken_punctuation": "true",
    "voice_final": "parakeet",
    "learning": "true",
    "ui_language": "auto",
    "voice_final_en": "parakeet-en",
    "voice_hold_keys": "F12,Alt_R",
    "voice_toggle_key": "Control+Alt+space",
}

# ファイルにないときに足す説明（エンジンが書くものと同じ）
COMMENTS = {
    "voice_final_en": "# Linux 版: 英語の口述のモデル（Kanade 以外の入力方式で録音キーを押したとき。parakeet-en・whisper-small・whisper-turbo）",
    "voice_hold_keys": "# Linux 版: どの入力方式でも、押している間だけ録音するキー（fcitx5 のキーの名前をカンマ区切り。例 F12,Alt_R,F9）",
    "voice_toggle_key": "# Linux 版: 押すたびに録音を始める・終えるキー（例 Control+Alt+space。空なら使わない）。録音中の Esc で取り消す",
}


def load_settings():
    values = dict(DEFAULTS)
    try:
        with open(SETTINGS_FILE, encoding="utf-8") as f:
            for raw in f:
                line = raw.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip()
    except FileNotFoundError:
        pass
    return values


def save_setting(key, value):
    """1つの項目だけを書き換える（ほかの行と説明はそのまま。なければ末尾に足す）。一時ファイルに書いてから置き換える"""
    os.makedirs(CONFIG_DIR, exist_ok=True)
    try:
        with open(SETTINGS_FILE, encoding="utf-8") as f:
            lines = f.read().splitlines()
    except FileNotFoundError:
        lines = ["# Kanade の設定。保存するとすぐ反映されます"]
    for i, raw in enumerate(lines):
        s = raw.strip()
        if not s.startswith("#") and "=" in s and s.split("=", 1)[0].strip() == key:
            lines[i] = f"{key}={value}"
            break
    else:
        if key in COMMENTS:
            lines.append(COMMENTS[key])
        lines.append(f"{key}={value}")
    write_text(SETTINGS_FILE, "\n".join(lines) + "\n")


def read_text(path):
    try:
        with open(path, encoding="utf-8") as f:
            return f.read()
    except FileNotFoundError:
        return ""


def write_text(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(text)
    os.replace(tmp, path)


# ---- 表示の言語 ----

ENGLISH = False


def update_language():
    global ENGLISH
    setting = load_settings().get("ui_language", "auto")
    if setting == "auto":
        lang = os.environ.get("LC_ALL") or os.environ.get("LC_MESSAGES") or os.environ.get("LANG") or ""
        ENGLISH = not lang.startswith("ja")
    else:
        ENGLISH = setting == "en"


def T(ja, en):
    return en if ENGLISH else ja


# ---- エンジンへの頼みごと ----


def engine_request(command, argument=""):
    """エンジンに頼む。（成功したか, 出す文）を返す。返事は「admin 番号 成功(1/0) 文」"""

    def esc(s):
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r")

    def unesc(s):
        out, i = [], 0
        while i < len(s):
            if s[i] == "\\" and i + 1 < len(s):
                i += 1
                out.append({"t": "\t", "n": "\n", "r": "\r"}.get(s[i], s[i]))
            else:
                out.append(s[i])
            i += 1
        return "".join(out)

    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as s:
            s.settimeout(20)
            s.connect(SOCKET_PATH)
            s.sendall(f"admin\t1\t{esc(command)}\t{esc(argument)}\n".encode())
            buf = b""
            while True:
                chunk = s.recv(65536)
                if not chunk:
                    return False, T("エンジンとの接続が切れました", "Lost the connection to the engine")
                buf += chunk
                while b"\n" in buf:
                    line, buf = buf.split(b"\n", 1)
                    f = line.decode("utf-8", "replace").split("\t")
                    if len(f) >= 3 and f[0] == "admin":
                        return f[2] == "1", unesc(f[3]) if len(f) > 3 else ""
    except socket.timeout:
        return False, T("エンジンから返事がありません", "The engine did not respond")
    except OSError:
        return False, T("Kanade のエンジンが動いていません（Kanade で文字を1つ打つと起動します）",
                        "The Kanade engine is not running (typing one character with Kanade starts it)")


# ---- 音声のモデル（desktop/…/VoiceModels.kt・windows/settings/VoiceModels.cs と同じ） ----


class Model:
    def __init__(self, id, name, ja, en, size, path, base, files, single=False, japanese=True, english=False):
        self.id, self.name, self.ja, self.en, self.size, self.path = id, name, ja, en, size, path
        self.files = [(n, base if single else base + n, b) for n, b in files]
        self.single, self.japanese, self.english = single, japanese, english

    def target(self, name):
        return os.path.join(DATA_DIR, self.path) if self.single else os.path.join(DATA_DIR, self.path, name)

    @property
    def ready(self):
        return all(os.path.isfile(self.target(n)) and os.path.getsize(self.target(n)) == b for n, _, b in self.files)

    def delete(self):
        """消す（ゴミ箱へ）"""
        path = os.path.join(DATA_DIR, self.path)
        if os.path.exists(path):
            Gio.File.new_for_path(path).trash(None)

    def download(self, progress, cancel):
        """落とす。途中まで落としたもの（.part）があれば続きから。progress(0〜1) は裏のスレッドから呼ぶ"""
        total = sum(b for _, _, b in self.files)
        done = 0
        for name, url, size in self.files:
            target = self.target(name)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            if os.path.isfile(target) and os.path.getsize(target) == size:
                done += size
                continue
            part = target + ".part"
            have = os.path.getsize(part) if os.path.exists(part) else 0
            request = urllib.request.Request(url, headers={"User-Agent": "kanade-settings"})
            if have:
                request.add_header("Range", f"bytes={have}-")
            with urllib.request.urlopen(request, timeout=60) as response:
                # 続きを頼んでも最初から返すサーバーなら、最初から書き直す
                if have and response.status != 206:
                    have = 0
                with open(part, "ab" if have else "wb") as out:
                    written = have
                    while True:
                        if cancel.is_set():
                            return False
                        chunk = response.read(1 << 20)
                        if not chunk:
                            break
                        out.write(chunk)
                        written += len(chunk)
                        progress((done + written) / total)
            if os.path.getsize(part) != size:
                raise IOError(T(f"{name} の大きさが合いません", f"{name} has the wrong size"))
            os.replace(part, target)
            done += size
        progress(1.0)
        return True


HF = "https://huggingface.co/"
MODELS = [
    Model("parakeet", "parakeet-ja", "日本語・高い精度・速い・句読点あり", "Japanese, high accuracy, fast, punctuation", "656MB", "parakeet-ja",
          HF + "csukuangfj/sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8/resolve/bef18eb066808c90bd0f5df5be685767b0732de8/",
          [("model.int8.onnx", 655_542_604), ("tokens.txt", 28_557)]),
    Model("reazon", "ReazonSpeech", "日本語・高い精度・とても速い・句読点なし。話している途中の文字にも使います",
          "Japanese, high accuracy, very fast, no punctuation. Also used for the text shown while you speak", "73MB", "reazonspeech",
          HF + "csukuangfj/reazonspeech-k2-v2-ja-en/resolve/main/",
          [("encoder-epoch-35-avg-1.int8.onnx", 70_876_409), ("decoder-epoch-35-avg-1.int8.onnx", 1_308_690),
           ("joiner-epoch-35-avg-1.int8.onnx", 1_033_417), ("tokens.txt", 26_631)]),
    Model("sensevoice", "SenseVoice", "中国語・英語・日本語・韓国語・精度はふつう・速い・句読点あり",
          "Chinese, English, Japanese, Korean, medium accuracy, fast, punctuation", "239MB", "sensevoice-2024-07-17",
          HF + "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07/",
          [("model.int8.onnx", 239_233_841), ("tokens.txt", 315_894)]),
    Model("whisper-small", "whisper small", "多言語・精度は低め・遅い・句読点あり", "Multilingual, lower accuracy, slow, punctuation", "190MB",
          "ggml-small-q5_1.bin", HF + "ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin", [("ggml-small-q5_1.bin", 190_085_487)],
          single=True, english=True),
    Model("whisper-kotoba", "kotoba-whisper v2.0", "日本語・高い精度・とても遅い・句読点は少なめ", "Japanese, high accuracy, very slow, little punctuation",
          "538MB", "ggml-kotoba-whisper-v2.0-q5_0.bin",
          HF + "kotoba-tech/kotoba-whisper-v2.0-ggml/resolve/main/ggml-kotoba-whisper-v2.0-q5_0.bin",
          [("ggml-kotoba-whisper-v2.0-q5_0.bin", 537_819_875)], single=True),
    Model("whisper-turbo", "whisper large-v3-turbo", "多言語・高い精度・とても遅い・句読点あり", "Multilingual, high accuracy, very slow, punctuation",
          "574MB", "ggml-large-v3-turbo-q5_0.bin", HF + "ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin",
          [("ggml-large-v3-turbo-q5_0.bin", 574_041_195)], single=True, english=True),
    Model("parakeet-en", "parakeet-tdt v3", "英語とヨーロッパの 25 の言語・高い精度・速い・句読点と大文字あり",
          "English and 24 other European languages, high accuracy, fast, punctuation and capitals", "670MB", "parakeet-v3",
          HF + "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/ed5d745623472f97cfc2e6c2916ee248798fc5a8/",
          [("encoder.int8.onnx", 652_282_294), ("decoder.int8.onnx", 11_845_274), ("joiner.int8.onnx", 6_355_277), ("tokens.txt", 93_939)],
          japanese=False, english=True),
]

# 落としている途中のもの（ページを作り直しても続ける）: id → (進み具合, 止める合図)
DOWNLOADS = {}


# ---- キーの名前（fcitx5 の名前。X の keysym の名前と同じ） ----

KEY_LABELS = {"Alt_R": ("右 Alt", "Right Alt"), "Alt_L": ("左 Alt", "Left Alt"), "Control_R": ("右 Ctrl", "Right Ctrl"),
              "Control_L": ("左 Ctrl", "Left Ctrl"), "Super_R": ("右 Super", "Right Super"), "Shift_R": ("右 Shift", "Right Shift"),
              "Control": ("Ctrl", "Ctrl"), "space": ("Space", "Space"), "Menu": ("メニュー", "Menu")}


def key_label(name):
    return "+".join(T(*KEY_LABELS[p]) if p in KEY_LABELS else p for p in name.split("+"))


MODIFIER_NAMES = {"Shift_L", "Shift_R", "Control_L", "Control_R", "Alt_L", "Alt_R", "Super_L", "Super_R", "Meta_L", "Meta_R",
                  "ISO_Level3_Shift", "Caps_Lock"}


# ---- 画面 ----


def group(title=None, description=None):
    g = Adw.PreferencesGroup()
    if title:
        g.set_title(title)
    if description:
        g.set_description(description)
    return g


def switch_row(title, subtitle, key):
    row = Adw.SwitchRow(title=title, subtitle=subtitle)
    row.set_active(load_settings().get(key) == "true")
    row.connect("notify::active", lambda r, _p: save_setting(key, "true" if r.get_active() else "false"))
    return row


def combo_row(title, subtitle, key, choices, on_change=None):
    """choices: [(値, 表示)]"""
    row = Adw.ComboRow(title=title, subtitle=subtitle)
    row.set_model(Gtk.StringList.new([label for _, label in choices]))
    current = load_settings().get(key)
    for i, (value, _) in enumerate(choices):
        if value == current:
            row.set_selected(i)

    def changed(r, _p):
        value = choices[r.get_selected()][0]
        save_setting(key, value)
        if on_change:
            on_change(value)

    row.connect("notify::selected", changed)
    return row


class SettingsWindow(Adw.ApplicationWindow):
    PAGES = ["input", "voice", "dictionary", "snippets", "backup", "usage"]

    def __init__(self, app, page):
        super().__init__(application=app, title="Kanade")
        self.set_default_size(920, 680)
        self.build(page)

    def build(self, page):
        update_language()
        self.set_title(T("Kanade の設定", "Kanade Settings"))
        self.toasts = Adw.ToastOverlay()
        split = Adw.NavigationSplitView()
        split.set_min_sidebar_width(220)
        self.split = split

        titles = {
            "input": (T("入力", "Input"), "input-keyboard-symbolic"),
            "voice": (T("音声入力", "Voice input"), "audio-input-microphone-symbolic"),
            "dictionary": (T("ユーザー辞書", "User dictionary"), "accessories-dictionary-symbolic"),
            "snippets": (T("スニペット", "Snippets"), "edit-paste-symbolic"),
            "backup": (T("設定の保存と読み込み", "Backup and restore"), "document-save-symbolic"),
            "usage": (T("使い方", "How to use"), "help-about-symbolic"),
        }
        self.titles = titles
        listbox = Gtk.ListBox()
        listbox.add_css_class("navigation-sidebar")
        for name in self.PAGES:
            title, icon = titles[name]
            row = Gtk.ListBoxRow()
            box = Gtk.Box(spacing=12, margin_top=10, margin_bottom=10, margin_start=6, margin_end=6)
            box.append(Gtk.Image.new_from_icon_name(icon))
            box.append(Gtk.Label(label=title, xalign=0))
            row.set_child(box)
            row.page = name
            listbox.append(row)
        listbox.connect("row-activated", lambda _l, row: self.show_page(row.page))
        self.listbox = listbox
        sidebar_view = Adw.ToolbarView()
        sidebar_view.add_top_bar(Adw.HeaderBar(title_widget=Adw.WindowTitle(title="Kanade", subtitle="")))
        scroller = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER)
        scroller.set_child(listbox)
        sidebar_view.set_content(scroller)
        split.set_sidebar(Adw.NavigationPage(title="Kanade", child=sidebar_view))
        self.toasts.set_child(split)

        # 幅が狭いときは一覧→中身の1列にする
        bp = Adw.Breakpoint.new(Adw.BreakpointCondition.parse("max-width: 600sp"))
        bp.add_setter(split, "collapsed", True)
        self.add_breakpoint(bp)
        self.set_content(self.toasts)
        self.show_page(page if page in self.PAGES else "input")

    def show_page(self, name):
        self.current = name
        for row in self.listbox:
            if getattr(row, "page", None) == name:
                self.listbox.select_row(row)
        content = {
            "input": self.input_page, "voice": self.voice_page, "dictionary": lambda: self.pair_page(False),
            "snippets": lambda: self.pair_page(True), "backup": self.backup_page, "usage": self.usage_page,
        }[name]()
        view = Adw.ToolbarView()
        view.add_top_bar(Adw.HeaderBar())
        view.set_content(content)
        self.split.set_content(Adw.NavigationPage(title=self.titles[name][0], child=view))
        self.split.set_show_content(True)

    def toast(self, text):
        self.toasts.add_toast(Adw.Toast(title=text, timeout=4))

    def run_in_background(self, work, done):
        """時間のかかるもの（エンジンへの頼みごと）を裏で動かし、終わったら画面のスレッドで done(結果)"""
        threading.Thread(target=lambda: GLib.idle_add(done, work()), daemon=True).start()

    # ---- 入力 ----

    def input_page(self):
        page = Adw.PreferencesPage()
        g = group(T("変換", "Conversion"))
        g.add(switch_row(T("ライブ変換", "Live conversion"),
                         T("打つたびに変換した結果を出します。切ると Space を押すまでかなのままです",
                           "Shows the converted text as you type. When off, text stays in kana until you press Space"), "live_conversion"))
        g.add(switch_row(T("数字・記号を全角にする", "Full-width numbers and symbols"),
                         T("日本語入力中の数字・記号・大文字を全角で入れます（Space は半角のまま）",
                           "Types numbers, symbols and capital letters in full width while typing Japanese (Space stays half width)"),
                         "full_width_symbols"))
        g.add(combo_row(T("句読点", "Punctuation"), T("「,」と「.」で入る句読点です", "The marks typed with the comma and period keys"),
                        "punctuation", [(p, p) for p in ["、。", "，．", "、．", "，。"]]))
        g.add(switch_row(T("括弧を対で入れる", "Insert brackets in pairs"),
                         T("開き括弧を打つと閉じ括弧も入れ、カーソルを中に置きます。空の括弧で Space を押すと括弧の種類が替わります",
                           "Typing an opening bracket also inserts the closing one and puts the cursor between them. "
                           "Press Space in empty brackets to change their kind"), "pair_brackets"))
        page.add(g)

        g = group(T("日本語と英字の切り替え", "Switching between Japanese and English"),
                  T("fcitx5 が受け持ちます。Kanade に切り替えると、いつも日本語から始まります。英字は fcitx5 の keyboard-us などで打ちます",
                    "fcitx5 handles this. Switching to Kanade always starts in Japanese; type English with fcitx5's keyboard-us or similar"))
        if GLib.find_program_in_path("fcitx5-configtool"):
            row = Adw.ActionRow(title=T("切り替えのキーを変える", "Change the switching keys"),
                                subtitle=T("fcitx5 の設定の「全体の設定」で決めます", "Set them under Global Options in the fcitx5 settings"))
            button = Gtk.Button(label=T("fcitx5 の設定を開く", "Open fcitx5 settings"), valign=Gtk.Align.CENTER)
            button.connect("clicked", lambda _b: subprocess.Popen(["fcitx5-configtool"]))
            row.add_suffix(button)
            g.add(row)
        page.add(g)

        g = group(T("学習", "Learning"))
        g.add(switch_row(T("選んだ変換を覚える", "Remember chosen conversions"),
                         T("候補から選んで確定したものを、次から先に出します", "Candidates you pick are shown first next time"), "learning"))
        row = Adw.ActionRow(title=T("覚えたものを消す", "Clear learned conversions"),
                            subtitle=T("学習した変換をすべて消します（ユーザー辞書は消えません）", "Deletes everything learned (your user dictionary is kept)"))
        button = Gtk.Button(label=T("消す", "Clear"), valign=Gtk.Align.CENTER)
        button.add_css_class("destructive-action")
        button.connect("clicked", lambda _b: self.confirm_clear_learning())
        row.add_suffix(button)
        g.add(row)
        page.add(g)

        g = group(T("表示", "Display"))
        g.add(combo_row(T("表示の言語（Language）", "Language（表示の言語）"),
                        T("この画面・メニュー・カーソルの近くの表示の言語です", "The language of this window, the menu and the messages near the cursor"),
                        "ui_language", [("auto", T("システムに合わせる", "Same as the system")), ("ja", "日本語"), ("en", "English")],
                        on_change=lambda _v: GLib.idle_add(self.rebuild)))
        page.add(g)
        return page

    def rebuild(self):
        self.build(self.current)
        return False

    def confirm_clear_learning(self):
        dialog = Adw.AlertDialog(heading=T("覚えたものを消しますか？", "Clear what Kanade has learned?"),
                                 body=T("元に戻せません。ユーザー辞書は残ります", "This cannot be undone. Your user dictionary is kept"))
        dialog.add_response("cancel", T("やめる", "Cancel"))
        dialog.add_response("clear", T("消す", "Clear"))
        dialog.set_response_appearance("clear", Adw.ResponseAppearance.DESTRUCTIVE)
        dialog.set_close_response("cancel")

        def response(_d, r):
            if r == "clear":
                self.run_in_background(lambda: engine_request("clear-learning"), lambda res: self.toast(res[1]))

        dialog.connect("response", response)
        dialog.present(self)

    # ---- 音声入力 ----

    def voice_page(self):
        page = Adw.PreferencesPage()
        s = load_settings()
        g = group(T("録音キー", "Record keys"),
                  T("どの入力方式でも使えます。Kanade のときは日本語、ほかの入力方式（英字など）のときは英語で書きます。録音中に Esc で取り消します",
                    "Work with any input method. With Kanade you get Japanese, with other input methods (such as English) you get English. "
                    "Press Esc while recording to cancel"))
        holds = [k for k in s.get("voice_hold_keys", "").split(",") if k]
        for key in holds:
            row = Adw.ActionRow(title=key_label(key), subtitle=T("押している間だけ録音し、離すと文字にします", "Records while held and writes the text when released"))
            row.add_prefix(Gtk.Image.new_from_icon_name("audio-input-microphone-symbolic"))
            remove = Gtk.Button(icon_name="list-remove-symbolic", valign=Gtk.Align.CENTER, tooltip_text=T("外す", "Remove"))
            remove.add_css_class("flat")
            remove.connect("clicked", lambda _b, k=key: self.set_hold_keys([h for h in holds if h != k]))
            row.add_suffix(remove)
            g.add(row)
        add = Adw.ButtonRow(title=T("押している間だけ録音するキーを足す", "Add a hold-to-record key"), start_icon_name="list-add-symbolic")
        add.connect("activated", lambda _r: self.capture_key(False, lambda k: self.set_hold_keys(holds + [k] if k not in holds else holds)))
        g.add(add)
        toggle = s.get("voice_toggle_key", "")
        row = Adw.ActionRow(title=T("押すたびに始める・終える", "Press to start and stop"),
                            subtitle=key_label(toggle) if toggle else T("使わない", "Not used"))
        row.add_prefix(Gtk.Image.new_from_icon_name("media-record-symbolic"))
        change = Gtk.Button(label=T("変える", "Change"), valign=Gtk.Align.CENTER)
        change.connect("clicked", lambda _b: self.capture_key(True, lambda k: self.save_and_refresh("voice_toggle_key", k)))
        row.add_suffix(change)
        if toggle:
            off = Gtk.Button(label=T("使わない", "Turn off"), valign=Gtk.Align.CENTER)
            off.add_css_class("flat")
            off.connect("clicked", lambda _b: self.save_and_refresh("voice_toggle_key", ""))
            row.add_suffix(off)
        g.add(row)
        page.add(g)

        g = group(T("書き方", "Writing"))
        g.add(switch_row(T("話した句読点", "Spoken punctuation"),
                         T("日本語で「まる」「てん」「かいぎょう」などを、句読点や改行にします",
                           "In Japanese, turns spoken words such as maru, ten and kaigyou into punctuation and line breaks"), "spoken_punctuation"))
        page.add(g)

        self.model_group(page, T("日本語の文を作るモデル", "Model for Japanese"),
                         T("録音を終えたときに、句読点付きの文を作るモデルです。使うには落としておきます（途中で止めても続きから落とせます）。"
                           "ReazonSpeech は話している途中の文字にも使います",
                           "Writes the sentence with punctuation when recording ends. Download it to use it (a stopped download resumes). "
                           "ReazonSpeech is also used for the text shown while you speak"),
                         "voice_final", [m for m in MODELS if m.japanese])
        self.model_group(page, T("英語の文を作るモデル", "Model for English"),
                         T("Kanade 以外の入力方式のときに使います", "Used when another input method is active"),
                         "voice_final_en", [m for m in MODELS if m.english])
        return page

    def set_hold_keys(self, keys):
        self.save_and_refresh("voice_hold_keys", ",".join(keys))

    def save_and_refresh(self, key, value):
        save_setting(key, value)
        self.show_page(self.current)

    def capture_key(self, combo, done):
        """次に押したキーの名前を取る。combo なら Ctrl などと組み合わせたキー（Control+Alt+space など）、でなければキー1つ"""
        dialog = Adw.AlertDialog(
            heading=T("キーを押してください", "Press a key"),
            body=T("Ctrl・Alt などと組み合わせたキーを押します（Esc でやめる）", "Press a key together with Ctrl, Alt or similar (Esc to cancel)") if combo
            else T("F9・右 Alt など、ふだん使わないキーを押します（Esc でやめる）", "Press a key you do not normally use, such as F9 or Right Alt (Esc to cancel)"))
        dialog.add_response("cancel", T("やめる", "Cancel"))
        dialog.set_close_response("cancel")
        controller = Gtk.EventControllerKey()
        controller.set_propagation_phase(Gtk.PropagationPhase.CAPTURE)

        def pressed(_c, keyval, _code, state):
            name = Gdk.keyval_name(Gdk.keyval_to_lower(keyval))
            if not name or name == "Escape":
                return False
            if combo:
                if name in MODIFIER_NAMES:
                    return True
                mods = [n for mask, n in [(Gdk.ModifierType.CONTROL_MASK, "Control"), (Gdk.ModifierType.ALT_MASK, "Alt"),
                                          (Gdk.ModifierType.SHIFT_MASK, "Shift"), (Gdk.ModifierType.SUPER_MASK, "Super")] if state & mask]
                if not mods:
                    return True
                name = "+".join(mods + [name])
            dialog.close()
            done(name)
            return True

        controller.connect("key-pressed", pressed)
        dialog.add_controller(controller)
        dialog.present(self)

    def model_group(self, page, title, description, key, models):
        g = group(title, description)
        current = load_settings().get(key)
        first = None
        for m in models:
            row = Adw.ActionRow(title=f"{m.name}（{m.size}）", subtitle=T(m.ja, m.en))
            radio = Gtk.CheckButton(valign=Gtk.Align.CENTER)
            if first is None:
                first = radio
            else:
                radio.set_group(first)
            radio.set_active(m.id == current)
            radio.set_sensitive(m.ready)
            radio.connect("toggled", lambda b, mid=m.id: b.get_active() and save_setting(key, mid))
            row.add_prefix(radio)
            row.set_activatable_widget(radio)
            if m.id in DOWNLOADS:
                fraction, cancel = DOWNLOADS[m.id]
                bar = Gtk.ProgressBar(fraction=fraction[0], valign=Gtk.Align.CENTER, width_request=140, show_text=True)
                stop = Gtk.Button(icon_name="process-stop-symbolic", valign=Gtk.Align.CENTER, tooltip_text=T("止める", "Stop"))
                stop.add_css_class("flat")
                stop.connect("clicked", lambda _b, c=cancel: c.set())
                row.add_suffix(bar)
                row.add_suffix(stop)

                def tick(bar=bar, fraction=fraction, mid=m.id):
                    if mid not in DOWNLOADS or bar.get_root() is None:
                        return False
                    bar.set_fraction(fraction[0])
                    bar.set_text(f"{int(fraction[0] * 100)}%")
                    return True

                GLib.timeout_add(300, tick)
            elif m.ready:
                if m.id == current:
                    tag = Gtk.Label(label=T("使用中", "In use"), valign=Gtk.Align.CENTER)
                    tag.add_css_class("dim-label")
                    row.add_suffix(tag)
                delete = Gtk.Button(icon_name="user-trash-symbolic", valign=Gtk.Align.CENTER, tooltip_text=T("消す（ゴミ箱へ）", "Delete (to trash)"))
                delete.add_css_class("flat")
                delete.connect("clicked", lambda _b, model=m: self.delete_model(model))
                row.add_suffix(delete)
            else:
                get = Gtk.Button(label=T("落とす", "Download"), valign=Gtk.Align.CENTER)
                get.connect("clicked", lambda _b, model=m, k=key: self.download_model(model, k))
                row.add_suffix(get)
            g.add(row)
        page.add(g)

    def download_model(self, model, key):
        fraction, cancel = [0.0], threading.Event()
        DOWNLOADS[model.id] = (fraction, cancel)

        def progress(f):
            fraction[0] = f

        def work():
            try:
                return model.download(progress, cancel), None
            except Exception as e:  # noqa: BLE001 失敗は画面に出す
                return False, str(e)

        def done(result):
            ok, error = result
            DOWNLOADS.pop(model.id, None)
            if ok:
                # 落としたら、それを使う
                save_setting(key, model.id)
                self.toast(T(f"{model.name} を落としました", f"Downloaded {model.name}"))
            elif error:
                self.toast(T(f"落とせませんでした: {error}", f"Download failed: {error}"))
            if self.current == "voice":
                self.show_page("voice")

        self.run_in_background(work, done)
        self.show_page("voice")

    def delete_model(self, model):
        model.delete()
        self.toast(T(f"{model.name} をゴミ箱へ移しました", f"Moved {model.name} to the trash"))
        self.show_page("voice")

    # ---- ユーザー辞書・スニペット ----

    def pair_page(self, snippets):
        path = SNIPPETS_FILE if snippets else DICTIONARY_FILE
        key_label_text = T("打つ文字", "Typed text") if snippets else T("読み", "Reading")
        value_label_text = T("置き換える文", "Replacement") if snippets else T("単語", "Word")
        description = (T("Kanade で打った文字がキーと一致した瞬間に、文に置き換えます。例: 「->」→「→」、「@@」→ メールアドレス。改行は \\n と書きます",
                         "As soon as what you type with Kanade matches a key, it is replaced with the text. "
                         "Examples: -> becomes →, @@ becomes your email address. Write \\n for a line break") if snippets
                       else T("読みと単語を登録すると、変換の候補の先頭に出ます", "Words you add here are shown first among the conversion candidates for their reading"))
        page = Adw.PreferencesPage()
        g = group(None, description)
        header = Gtk.Box(spacing=6)
        search = Gtk.SearchEntry(placeholder_text=T("探す", "Search"), hexpand=True)
        text_mode = Gtk.ToggleButton(label=T("テキストで編集", "Edit as text"))
        add = Gtk.Button(label=T("足す", "Add"))
        add.add_css_class("suggested-action")
        header.append(search)
        header.append(text_mode)
        header.append(add)
        g.add(header)
        page.add(g)

        list_group = group()
        page.add(list_group)
        text_group = group()
        editor = Gtk.TextView(monospace=True, wrap_mode=Gtk.WrapMode.NONE, top_margin=8, bottom_margin=8, left_margin=8, right_margin=8)
        frame = Gtk.ScrolledWindow(min_content_height=360)
        frame.add_css_class("card")
        frame.set_child(editor)
        text_group.add(frame)
        save = Gtk.Button(label=T("保存", "Save"), halign=Gtk.Align.END, margin_top=12)
        save.add_css_class("suggested-action")
        text_group.add(save)
        text_group.set_visible(False)
        page.add(text_group)

        rows = []

        def entries():
            """（行の番号, キー, 値）。書式は UserDictionary.parsePairs と同じ"""
            out = []
            for i, raw in enumerate(read_text(path).splitlines()):
                s = raw.strip()
                if not s or s.startswith("#"):
                    continue
                cut = next((j for j, c in enumerate(s) if c in " \t"), -1)
                if cut <= 0:
                    continue
                value = s[cut + 1:].lstrip()
                if value:
                    out.append((i, s[:cut], value))
            return out

        def refresh():
            for r in rows:
                list_group.remove(r)
            rows.clear()
            q = search.get_text().strip()
            items = [e for e in entries() if not q or q in e[1] or q in e[2]]
            if not items:
                empty = Adw.ActionRow(title=T("まだありません", "Nothing yet") if not q else T("見つかりません", "No matches"))
                empty.add_css_class("dim-label")
                rows.append(empty)
                list_group.add(empty)
            for line, k, v in items:
                row = Adw.ActionRow(title=GLib.markup_escape_text(v), subtitle=GLib.markup_escape_text(k), activatable=True)
                row.add_suffix(Gtk.Image.new_from_icon_name("go-next-symbolic"))
                row.connect("activated", lambda _r, e=(line, k, v): edit(e))
                rows.append(row)
                list_group.add(row)

        def write_entry(entry, new_line):
            lines = read_text(path).splitlines()
            if entry is None:
                lines.append(new_line)
            elif new_line is None:
                del lines[entry[0]]
            else:
                lines[entry[0]] = new_line
            write_text(path, "\n".join(lines) + "\n")

        def edit(entry):
            dialog = Adw.AlertDialog(heading=T("足す", "Add") if entry is None else T("直す", "Edit"))
            box = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
            box.add_css_class("boxed-list")
            key_row = Adw.EntryRow(title=key_label_text, text=entry[1] if entry else "")
            value_row = Adw.EntryRow(title=value_label_text, text=entry[2] if entry else "")
            box.append(key_row)
            box.append(value_row)
            dialog.set_extra_child(box)
            dialog.add_response("cancel", T("やめる", "Cancel"))
            if entry is not None:
                dialog.add_response("delete", T("消す", "Delete"))
                dialog.set_response_appearance("delete", Adw.ResponseAppearance.DESTRUCTIVE)
            dialog.add_response("save", T("保存", "Save"))
            dialog.set_response_appearance("save", Adw.ResponseAppearance.SUGGESTED)
            dialog.set_default_response("save")
            dialog.set_close_response("cancel")

            def validate(*_a):
                k, v = key_row.get_text().strip(), value_row.get_text().strip()
                dialog.set_response_enabled("save", bool(k) and not any(c in k for c in " \t") and bool(v))

            key_row.connect("changed", validate)
            value_row.connect("changed", validate)
            validate()

            def response(_d, r):
                if r == "save":
                    write_entry(entry, f"{key_row.get_text().strip()} {value_row.get_text().strip()}")
                elif r == "delete":
                    write_entry(entry, None)
                else:
                    return
                refresh()

            dialog.connect("response", response)
            dialog.present(self)

        def toggle_text(button):
            on = button.get_active()
            list_group.set_visible(not on)
            text_group.set_visible(on)
            search.set_sensitive(not on)
            add.set_sensitive(not on)
            if on:
                editor.get_buffer().set_text(read_text(path))

        def save_text(_b):
            buffer = editor.get_buffer()
            text = buffer.get_text(buffer.get_start_iter(), buffer.get_end_iter(), False)
            write_text(path, text if text.endswith("\n") else text + "\n")
            self.toast(T("保存しました", "Saved"))
            refresh()

        search.connect("search-changed", lambda _s: refresh())
        add.connect("clicked", lambda _b: edit(None))
        text_mode.connect("toggled", toggle_text)
        save.connect("clicked", save_text)
        refresh()
        return page

    # ---- 設定の保存と読み込み ----

    def backup_page(self):
        page = Adw.PreferencesPage()
        g = group(T("Android 版との行き来", "Moving to and from Android"),
                  T("設定・ユーザー辞書・スニペットを1つのファイルにします。Android 版の「設定の書き出し」「設定の読み込み」と同じ形です",
                    "Settings, the user dictionary and snippets in one file, the same format as Export and Import settings in the Android app"))
        row = Adw.ActionRow(title=T("読み込む", "Import"),
                            subtitle=T("Android 版で書き出したファイル（kanade-settings-….json）を読みます。今のユーザー辞書・スニペットは .bak に残します",
                                       "Reads a file exported from the Android app (kanade-settings-….json). The current dictionary and snippets are kept as .bak"))
        button = Gtk.Button(label=T("ファイルを選ぶ", "Choose file"), valign=Gtk.Align.CENTER)
        button.connect("clicked", lambda _b: self.import_android())
        row.add_suffix(button)
        g.add(row)
        row = Adw.ActionRow(title=T("書き出す", "Export"), subtitle=T("Android 版で読み込める形で書き出します", "Writes a file the Android app can import"))
        button = Gtk.Button(label=T("保存先を選ぶ", "Choose where"), valign=Gtk.Align.CENTER)
        button.connect("clicked", lambda _b: self.export_android())
        row.add_suffix(button)
        g.add(row)
        page.add(g)

        g = group(T("ファイル", "Files"))
        for title, subtitle, target in [
            (T("設定のフォルダーを開く", "Open the settings folder"), CONFIG_DIR, CONFIG_DIR),
            (T("記録を開く", "Open the log"), LOG_FILE, LOG_FILE),
        ]:
            row = Adw.ActionRow(title=title, subtitle=subtitle, activatable=True)
            row.add_suffix(Gtk.Image.new_from_icon_name("adw-external-link-symbolic"))
            row.connect("activated", lambda _r, t=target: self.open_path(t))
            g.add(row)
        page.add(g)
        return page

    def open_path(self, path):
        if not os.path.exists(path):
            self.toast(T("まだありません", "Does not exist yet"))
            return
        Gtk.FileLauncher.new(Gio.File.new_for_path(path)).launch(self, None, None, None)

    def json_filter(self):
        f = Gtk.FileFilter(name=T("Kanade の設定（JSON）", "Kanade settings (JSON)"))
        f.add_pattern("*.json")
        filters = Gio.ListStore.new(Gtk.FileFilter)
        filters.append(f)
        return filters

    def import_android(self):
        dialog = Gtk.FileDialog(title=T("読み込むファイル", "File to import"), filters=self.json_filter())
        downloads = GLib.get_user_special_dir(GLib.UserDirectory.DIRECTORY_DOWNLOAD)
        if downloads:
            dialog.set_initial_folder(Gio.File.new_for_path(downloads))

        def chosen(d, result):
            try:
                file = d.open_finish(result)
            except GLib.Error:
                return
            path = file.get_path()

            def work():
                ok, message = engine_request("import-android", path)
                # エンジンが動いていなければ、エンジンのコマンドで読む（動き出したら読み直す）
                if not ok and not os.path.exists(SOCKET_PATH) and os.access(ENGINE, os.X_OK):
                    r = subprocess.run([ENGINE, "import-android", path], capture_output=True, text=True)
                    return r.returncode == 0, (r.stdout or r.stderr).strip()
                return ok, message

            self.run_in_background(work, lambda res: self.toast(res[1]))

        dialog.open(self, None, chosen)

    def export_android(self):
        import datetime

        dialog = Gtk.FileDialog(title=T("書き出す先", "Export to"), filters=self.json_filter(),
                                initial_name=f"kanade-settings-{datetime.date.today():%Y%m%d}.json")

        def chosen(d, result):
            try:
                file = d.save_finish(result)
            except GLib.Error:
                return
            path = file.get_path()
            self.run_in_background(lambda: engine_request("export-android", path), lambda res: self.toast(res[1]))

        dialog.save(self, None, chosen)

    # ---- 使い方 ----

    def usage_page(self):
        page = Adw.PreferencesPage()
        sections = [
            (T("打つ", "Typing"), [
                T("fcitx5 で Kanade に切り替えると日本語。英字は keyboard-us などに切り替えて打ちます",
                  "Switch to Kanade in fcitx5 for Japanese; switch to keyboard-us or similar for English"),
                T("ローマ字で打つと、打つたびに変換した結果が出ます（ライブ変換）。Enter で確定",
                  "Type in romaji and the converted text appears as you type (live conversion). Enter commits"),
                T("Space で変換し、候補の窓から Space・↑↓・数字・クリックで選びます。←→ で文節を移り、Shift+←→ で区切りを変えます",
                  "Space converts; choose with Space, ↑↓, numbers or a click. ←→ moves between phrases, Shift+←→ changes the boundaries"),
                T("F6〜F10（Ctrl+U/I/O/P/T）でひらがな・カタカナ・半角カナ・全角英数・半角英数",
                  "F6–F10 (Ctrl+U/I/O/P/T) convert to hiragana, katakana, half-width kana, full-width and half-width letters"),
            ]),
            (T("音声入力", "Voice input"), [
                T("録音キー（既定は F12 と右 Alt）を押している間だけ録音し、離すと句読点付きの文が入ります",
                  "Hold a record key (F12 or Right Alt by default) to record; release it to type the sentence with punctuation"),
                T("Ctrl+Alt+Space は押すたびに録音を始める・終えます。録音中の Esc で取り消します",
                  "Ctrl+Alt+Space starts and stops recording. Esc cancels while recording"),
                T("Kanade のときは日本語、ほかの入力方式のときは英語で書きます。話している途中の文字はカーソルの近くに出ます",
                  "With Kanade you get Japanese, with other input methods English. The text appears near the cursor while you speak"),
            ]),
            (T("設定", "Settings"), [
                T("この画面で変えたものは、すぐ（2 秒ほどで）効きます", "Changes made here take effect within about two seconds"),
                T("fcitx5 のメニュー（トレイ）の「Kanade: 設定」からもこの画面を開けます", "You can also open this window from Kanade: Settings in the fcitx5 menu"),
            ]),
        ]
        for title, items in sections:
            g = group(title)
            for text in items:
                label = Gtk.Label(label="・" + text, wrap=True, xalign=0, margin_top=6, margin_bottom=6)
                g.add(label)
            page.add(g)
        return page


class App(Adw.Application):
    def __init__(self, page):
        super().__init__(application_id="dev.mooma.kanade.Settings", flags=Gio.ApplicationFlags.HANDLES_COMMAND_LINE)
        self.page = page
        self.window = None

    def do_command_line(self, command_line):
        args = command_line.get_arguments()[1:]
        page = args[0] if args else self.page
        if self.window is None:
            self.window = SettingsWindow(self, page)
        else:
            self.window.show_page(page if page in SettingsWindow.PAGES else "input")
        self.window.present()
        return 0


if __name__ == "__main__":
    sys.exit(App("input").run(sys.argv))
