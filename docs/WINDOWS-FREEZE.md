# Windows 版のフリーズ調査（2026-11-25〜）

症状（本人の報告）: 時々、文字入力もほかのキー操作も全部効かなくなり、タスクトレイの IME を右クリックしてもメニューが出ない。再現手順は不明。本人の実機のログ・ダンプはまだ無い。
対象は現行の Kotlin/JVM エンジンと、デスクトップのソケット方式（Rust 書き直しの議論とは別）。**原因は未確定**。直すのは確かになってから。

## 構成のおさらい（読んで分かったこと）

- メニューは DLL の `LangBarButton::ShowMenu`（`TrackPopupMenuEx`）が、**キー入力と同じアプリの入力スレッド**で出す。エンジン（JVM）はメニューを出さない。だから「全キーが効かない」と「トレイのメニューが出ない」が同時なら、まず疑うのは手前のアプリの入力スレッドが止まっていること（DLL の中で待っているか、アプリ自体が止まっているか）。explorer.exe や SearchHost にも DLL は読み込まれている（to-fu で確認）。
- DLL → エンジンのキー要求（`TextService::ProcessKey` → `EngineClient::Request`）は UI スレッドで同期して待つ。上限は 300ms（`kTimeoutMs`）。切れたら接続を閉じてキーを素通しにし、再接続は 1 秒に 1 回まで（`EnsureConnected`）。キーの押下・離しごとに `OnTestKey*`/`OnKey*` が呼ばれる。
- エンジンは DLL からの行をほぼ全部、1 つの `EngineHost.lock` の中で処理する（`EngineHost.onLine`）。VoiceSession・DesktopApp も同じ lock を使う。**lock の中で止まる処理があれば、全アプリの DLL が 300ms ずつ待たされる**（疑い。下）。

## 実測で外れたこと（to-fu、Windows 11）

AF_UNIX（Winsock）単体の実測。`EngineClient` の通信部分は、少なくとも次の点で無限には待たない。

| 調べたこと | 結果 |
|---|---|
| 別スレッドが `recv` 待ちのソケットを `shutdown`+`closesocket`（`Close()` と同じ手順） | `recv` が 10004 で即戻る（`reader_.join()` は固まらない） |
| listen の待ち行列が満杯（accept しない）のときの `connect` | 即 10055 で失敗（ブロックしない） |
| 相手が読まないときの `send`（`SO_SNDTIMEO` 100ms） | 64KB 送った所で 10060 になり 93ms で戻る |
| Audiosrv を止めて JavaSound のマイクを開く | 0.4 秒で例外（`No line matching…`）。ハングしない（スリープ復帰・Bluetooth・排他使用は未確認） |

イベントログ（過去 30 日）: Kanade 由来と分かるクラッシュ・ハングは無い。explorer.exe の Hang/Crash が数回あるが（Xaml 系）、Kanade との関係は不明。
エンジン（to-fu で 3 日動いている javaw、-Xmx512m）は応答が速く、hello の返事は 0ms、thread は全部待機。いまは固まっていない。

## 疑い（証拠が弱いものも含む。未確認）

1. **エンジンの lock の中の、時間の上限が無い処理。** `VoiceSession.begin` → `DesktopVoice.start`（`AudioSystem.getTargetDataLine(..).open`・`l.start()`）は、`onLine("voice"/"key")` の `synchronized(lock)` の中で呼ばれる。`stop()`/`close()` の `l.stop()`/`l.close()`/`join(500)` も同様。マイクの open/close が OS 側で止まると、エンジン全体が止まる。→ 各アプリの DLL は 1 秒に 1 回 300ms 待たされ、そのほかのキーは素通し（日本語入力が効かず、ローマ字が出る）。これだけでは「全部のキーが効かない」ほどにはならない。
2. `client.send`（blocking write）が `synchronized(lock)` の中（rerank の結果送信など）にある。読まない相手（凍結したアプリ）の送信バッファが埋まると、lock を持ったまま止まる。バッファが 64KB なので起きにくい。
3. 止まったエンジンに対し、1 秒ごとの再接続のたびにエンジン側に `client-N` スレッドが増え、lock が空いたあとで古い接続の行（`focus 1` など）を処理して状態を乱しうる。
4. DLL の UI スレッドの待ちは有限だが、`MaybeLaunchEngine` の `CreateProcess`（15 秒に 1 回）など、同期の呼び出しがいくつかある。

「全キー＋トレイが同時に死ぬ」を説明するには、手前のアプリ（または explorer）の入力スレッドが数秒〜以上止まる必要がある。上の疑いだけでは弱く、**本人の実機での採取が要る**。

## フリーズしたときの採取（本人の実機）

`windows/tools/freeze-capture.cmd`（中身は `freeze-capture.ps1`）をダブルクリックする。マウスだけで動かせる。

1. 先に一度、`freeze-capture.cmd -Arm` を実行する（コマンドプロンプトから）。DLL の記録（`%LOCALAPPDATA%\Kanade\tsf-log\<exe>-<pid>.log`）が有効になる。アプリを開き直すと効く。記録を止めるには、そのフォルダーを消す。
2. 固まったら、デスクトップのショートカットから `freeze-capture.cmd` を起動する。既定で 8 秒待つので、その間に**固まっているウィンドウをクリックして手前に出す**。
3. デスクトップに `kanade-freeze-<日時>`（と `.zip`）ができる。`report.txt` を見るか、zip を渡す。`-Dump` を付けると手前のアプリの全メモリのダンプも取る（大きい）。

`report.txt` に入るもの: 手前のウィンドウの応答（`IsHungAppWindow`・`WM_NULL` の応答時間）、タスクバー（explorer）の応答、応答しないウィンドウの一覧、エンジンの CPU・メモリ・スレッド状態、**エンジンのソケットに hello を送って返事までの時間（lock が取れるかの判定）**、エンジンのスレッドダンプ（jcmd があれば）、手前のアプリの Kanade の DLL・スレッド状態、メモリ・CPU・ディスク、直近 6 時間のスリープ復帰・画面ロック/RDP・アプリの Hang/Crash イベント、エンジンの記録と DLL の記録。

読み方:

- 手前のアプリが `NOT_RESPONDING`／`wm_null_ping_ms=-1`、explorer は応答する → 手前のアプリの入力スレッドが止まっている（DLL の中か、アプリ自体か）。`-Dump` のダンプで UI スレッドのスタックを見る。
- explorer も応答しない → explorer に読み込まれた DLL 側か、別の原因。
- 全部応答するのに `probe=… NO_REPLY` → エンジンの lock が取れない。thread dump の `waiting to lock`／`locked` で持ち主を見る。
- すべて応答する → Kanade 以外の可能性（`system` 欄のメモリ・イベントを見る）。

制限: 製品版のランタイム（`C:\Program Files\Kanade\runtime`）は jlink で作ってあり `jcmd`/`jstack` が無い。JDK（Android Studio の JBR など）が見つかれば、そちらの `jcmd` で attach して取る。見つからなければ thread dump は取れない（エンジンに自分でダンプを出す仕組みを足す案は下）。

## 次にやること

- 本人の実機で `-Arm` してから、固まったときに採取（上）。
- エンジンに、lock を一定時間（例 2 秒）持ち続けたスレッドを検知して、全スレッドのスタックを `engine.log` に書く見張りを足す案（診断だけ。入れるなら版が変わる。本人に確認）。
