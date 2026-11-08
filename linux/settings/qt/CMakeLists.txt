# 設定の画面の QML 版（kanade-settings-qt）。要るもの: Qt 6.5 以上（qt6-base・qt6-declarative）、libxkbcommon
cmake_minimum_required(VERSION 3.21)
project(kanade-settings-qt LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Qt6 6.5 REQUIRED COMPONENTS Gui Network Qml Quick QuickControls2)
find_package(PkgConfig REQUIRED)
pkg_check_modules(XKB REQUIRED IMPORTED_TARGET xkbcommon)
qt_standard_project_setup(REQUIRES 6.5)

set(BACKEND src/store.cpp src/theme.cpp src/engine.cpp src/models.cpp src/keycapture.cpp)
add_library(kanade_backend STATIC ${BACKEND})
target_link_libraries(kanade_backend PUBLIC Qt6::Gui Qt6::Network PkgConfig::XKB)
target_include_directories(kanade_backend PUBLIC src)

qt_add_executable(kanade-settings-qt src/main.cpp)
set(QML Main Pal Icon KButton IconButton PrefPage PrefGroup PrefRow SwitchRow ComboRow KDialog
  InputPage VoicePage PairPage BackupPage UsagePage)
set(QML_FILES)
foreach(name ${QML})
  # qml/ の下に置いたまま、モジュールの中では頭に置く（qmldir を1つで済ませる）
  set_source_files_properties(qml/${name}.qml PROPERTIES QT_RESOURCE_ALIAS ${name}.qml)
  list(APPEND QML_FILES qml/${name}.qml)
endforeach()
set_source_files_properties(qml/Pal.qml PROPERTIES QT_QML_SINGLETON_TYPE TRUE)
qt_add_qml_module(kanade-settings-qt
  URI Kanade
  VERSION 1.0
  QML_FILES ${QML_FILES}
)
target_link_libraries(kanade-settings-qt PRIVATE kanade_backend Qt6::Quick Qt6::QuickControls2 Qt6::Qml)

option(KANADE_TESTS "テストを組む" ON)
if(KANADE_TESTS)
  find_package(Qt6 COMPONENTS Test)
  if(Qt6Test_FOUND)
    enable_testing()
    qt_add_executable(tst_backend tests/tst_backend.cpp)
    target_link_libraries(tst_backend PRIVATE kanade_backend Qt6::Test)
    add_test(NAME backend COMMAND tst_backend)
    # GTK 版（Python）と同じ書き方をするかを比べる道具
    qt_add_executable(store_cli tests/store_cli.cpp)
    target_link_libraries(store_cli PRIVATE kanade_backend)
  endif()
endif()

install(TARGETS kanade-settings-qt RUNTIME DESTINATION .)
