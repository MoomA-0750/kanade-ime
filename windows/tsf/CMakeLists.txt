# Kanade の TSF テキストサービス（Windows の入力の仕組みとのつなぎ）。キーを常駐のエンジンへ送り、返ってきた命令で入力中の文字・確定を書くだけの薄い DLL。
# 全アプリのプロセスに読み込まれるので、重い処理はしない・例外を外へ出さない・エンジンがなければ素通しにする
cmake_minimum_required(VERSION 3.20)
project(kanade_tsf CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
# 実行時ライブラリを静的に（アプリごとに VC++ ランタイムの有無が違っても動くように）
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
add_compile_definitions(UNICODE _UNICODE WIN32_LEAN_AND_MEAN NOMINMAX)
add_compile_options(/utf-8 /W4 /EHsc)

set(COMMON_SOURCES src/Protocol.cpp src/EngineClient.cpp src/Log.cpp)

add_library(kanade_tsf SHARED
    ${COMMON_SOURCES}
    src/DllMain.cpp
    src/Register.cpp
    src/TextService.cpp
    src/Composition.cpp
    src/DisplayAttributes.cpp
    src/EngineLauncher.cpp
    src/LangBarButton.cpp
    src/Popups.cpp
    kanade_tsf.def)
target_link_libraries(kanade_tsf PRIVATE ws2_32 uuid ole32 oleaut32 advapi32 user32 shell32 d2d1 dwrite dwmapi)

# エンジンとのやりとりだけを試す道具（TSF なしで、ssh からも動かせる）
add_executable(kanade_probe ${COMMON_SOURCES} src/Probe.cpp)
target_link_libraries(kanade_probe PRIVATE ws2_32 shell32 ole32 user32)

# 本物のキー入力で DLL を試す道具（自分の窓が前面のときだけ打つ）
add_executable(kanade_typetest WIN32 src/Protocol.cpp src/TypeTest.cpp)
set_target_properties(kanade_typetest PROPERTIES WIN32_EXECUTABLE OFF)
target_link_libraries(kanade_typetest PRIVATE ole32 user32 gdi32 uuid)

# Windows Terminal と同じく、プロセスの文字コードを UTF-8 にした試験の道具（違いを調べる用）
add_executable(kanade_probe_utf8 ${COMMON_SOURCES} src/Probe.cpp utf8.manifest)
target_link_libraries(kanade_probe_utf8 PRIVATE ws2_32 shell32 ole32 user32)

# 命令を AppContainer の中で動かす（スタートメニューの検索と同じ隔離でつながるかを試す）
add_executable(kanade_appcontainer src/AppContainerRun.cpp)
target_link_libraries(kanade_appcontainer PRIVATE userenv)
