# zenz（llama.cpp）を JNI から呼ぶための共有ライブラリ libkanade_zenz.so。
# Android（Gradle の externalNativeBuild）と、手元の評価用のホスト向けビルド（scripts/build-host-zenz.sh）の両方でこれを使う。
cmake_minimum_required(VERSION 3.22)
project(kanade_zenz CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp は静的ライブラリとして取り込み、1つの .so にまとめる
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if (ANDROID)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    if (ANDROID_ABI STREQUAL "arm64-v8a")
        # 内積命令（dotprod）と半精度を使う。Fold 7（Snapdragon 8 Elite）も Pixel 3（Snapdragon 845）も対応している。
        # i8mm は Pixel 3 にないので使わない
        set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
    endif()
endif()
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../third_party/llama.cpp llama.cpp EXCLUDE_FROM_ALL)

find_package(JNI QUIET)
add_library(kanade_zenz SHARED zenz_bridge.cpp)
target_link_libraries(kanade_zenz PRIVATE llama)
if (ANDROID)
    target_link_libraries(kanade_zenz PRIVATE log)
    # 16KB ページの端末（Android 15 以降）でも読めるようにする
    target_link_options(kanade_zenz PRIVATE "-Wl,-z,max-page-size=16384")
elseif (JNI_FOUND)
    target_include_directories(kanade_zenz PRIVATE ${JNI_INCLUDE_DIRS})
endif()
target_compile_options(kanade_zenz PRIVATE -O3)
