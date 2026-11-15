// libkanade_zenz_v80.so を作るだけのモジュール。dotprod の無い arm64 の CPU（Pixel 3 など）向けに、armv8-a の命令だけで組んだ版。
// アプリは /proc/cpuinfo に asimddp が無ければこちらを読む（NativeLibs）。中身は app・native-whisper と同じ CMake を KANADE_ARM_BASELINE で組む
plugins {
    id("com.android.library")
}

android {
    namespace = "dev.mooma.kanade.nativezenzv80"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 29
        ndk {
            // arm64 だけ（x86_64 のエミュレータでは使わない）
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release", "-DKANADE_ARM_BASELINE=ON")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
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
