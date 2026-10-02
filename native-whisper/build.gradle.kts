// libkanade_whisper.so（whisper.cpp）を Android 向けにビルドするだけのモジュール。
// zenz（llama.cpp）とは ggml の CMake のターゲット名がぶつかるので、app の CMake とは分けてここでビルドする
plugins {
    id("com.android.library")
}

android {
    namespace = "dev.mooma.kanade.nativewhisper"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 29
        ndk {
            // app と同じ。エミュレータで試すときは -Pkanade.abis=arm64-v8a,x86_64
            abiFilters += (findProperty("kanade.abis") as String? ?: "arm64-v8a").split(',').map { it.trim() }
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/whisper/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildTypes {
        // debug でもネイティブは最適化してビルドする（推論が遅くなりすぎるので）
        debug {
            externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" } }
        }
    }
}
