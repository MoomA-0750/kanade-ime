// エンジンにつながらないとき、エンジンを起動する（Mozc が mozc_server を起動するのと同じ）。
// 起動の命令は HKCU\Software\Kanade の EngineCommand（インストーラーか scripts/win.sh が書く）。
// 管理者権限のアプリや隔離されたアプリ（AppContainer・低い整合性）からは起動しない（エンジンを普通の権限で動かすため）
#pragma once

namespace kanade {
void MaybeLaunchEngine();
}
