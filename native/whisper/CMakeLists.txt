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
if (ANDROID)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    if (ANDROID_ABI STREQUAL "arm64-v8a")
        # zenz と同じく、Fold 7 と Pixel 3 の両方で動く命令まで
        set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
    endif()
endif()
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/whisper.cpp whisper.cpp EXCLUDE_FROM_ALL)

find_package(JNI QUIET)
add_library(kanade_whisper SHARED whisper_bridge.cpp)
target_link_libraries(kanade_whisper PRIVATE whisper)
target_link_options(kanade_whisper PRIVATE "-Wl,--exclude-libs,ALL")
if (ANDROID)
    target_link_libraries(kanade_whisper PRIVATE log)
    target_link_options(kanade_whisper PRIVATE "-Wl,-z,max-page-size=16384")
elseif (JNI_FOUND)
    target_include_directories(kanade_whisper PRIVATE ${JNI_INCLUDE_DIRS})
endif()
target_compile_options(kanade_whisper PRIVATE -O3)
