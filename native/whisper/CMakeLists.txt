# whisper.cpp を JNI から呼ぶための共有ライブラリ libkanade_whisper.so（音声入力）。
# llama.cpp（zenz）とは別に作る。どちらも ggml を持つので、静的に取り込んだ ggml の記号は .so の外へ出さない
cmake_minimum_required(VERSION 3.22)
project(kanade_whisper CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if (APPLE)
    # macOS: GPU（Metal）は使わず CPU で動かす（zenz は小さいモデルで、Metal の初期化のほうが重い。whisper も同じ形にそろえる）
    set(GGML_METAL OFF CACHE BOOL "" FORCE)
endif()
if (ANDROID)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    if (ANDROID_ABI STREQUAL "arm64-v8a")
        # zenz と同じく dotprod と半精度を使う。KANADE_ARM_BASELINE なら dotprod の無い CPU 向けの armv8-a だけの版
        # （libkanade_whisper_v80.so、native-whisper-v80 のモジュール）
        if (KANADE_ARM_BASELINE)
            set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)
        else()
            set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
        endif()
    endif()
endif()
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if (MSVC)
    # zenz と同じく VC++ のランタイムを静的に（JBR 同梱の古い msvcp140.dll で落ちないように）
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded" CACHE STRING "" FORCE)
endif()

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/whisper.cpp whisper.cpp EXCLUDE_FROM_ALL)

find_package(JNI QUIET)
add_library(kanade_whisper SHARED whisper_bridge.cpp)
if (KANADE_ARM_BASELINE)
    set_target_properties(kanade_whisper PROPERTIES OUTPUT_NAME kanade_whisper_v80)
endif()
target_link_libraries(kanade_whisper PRIVATE whisper)
if (ANDROID)
    target_link_libraries(kanade_whisper PRIVATE log)
    target_link_options(kanade_whisper PRIVATE "-Wl,-z,max-page-size=16384")
elseif (KANADE_JNI_INCLUDE)
    # Windows（to-fu）: 手元から送った JNI のヘッダー（scripts/win.sh whisper）
    target_include_directories(kanade_whisper PRIVATE ${KANADE_JNI_INCLUDE} ${KANADE_JNI_INCLUDE}/win32)
elseif (JNI_FOUND)
    target_include_directories(kanade_whisper PRIVATE ${JNI_INCLUDE_DIRS})
endif()
if (MSVC)
    target_compile_options(kanade_whisper PRIVATE /O2 /utf-8)
else()
    target_compile_options(kanade_whisper PRIVATE -O3)
    if (APPLE)
        # macOS の ld には --exclude-libs がないので、JNI の関数だけを外へ出す
        target_link_options(kanade_whisper PRIVATE "-Wl,-exported_symbol,_Java_*")
    else()
        target_link_options(kanade_whisper PRIVATE "-Wl,--exclude-libs,ALL")
    endif()
endif()
