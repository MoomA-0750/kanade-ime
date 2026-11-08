# fcitx5 のアドオン（Linux 版の入口）。キーをエンジン（desktop、常駐する JVM）へソケットで送り、返ってきた命令で
# 入力中の文字・確定・候補の窓を書く。Windows 版の TSF の DLL（windows/tsf）と同じ役目で、同じやりとり（docs/WINDOWS-PROTOCOL.md）
cmake_minimum_required(VERSION 3.16)
project(fcitx5-kanade VERSION 0.1 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
find_package(Fcitx5Core REQUIRED)
include(FeatureSummary)
include(GNUInstallDirs)

add_library(kanade MODULE src/kanade.cpp src/keymap.cpp src/protocol.cpp)
target_link_libraries(kanade PRIVATE Fcitx5::Core)
set_target_properties(kanade PROPERTIES PREFIX "")
target_compile_options(kanade PRIVATE -O2 -Wall)

install(TARGETS kanade DESTINATION "${CMAKE_INSTALL_LIBDIR}/fcitx5")
install(FILES kanade-addon.conf RENAME kanade.conf DESTINATION "${CMAKE_INSTALL_DATADIR}/fcitx5/addon")
install(FILES kanade.conf DESTINATION "${CMAKE_INSTALL_DATADIR}/fcitx5/inputmethod")
