// whisper.cpp を JNI 越しに呼ぶ Kotlin 側（音声入力）。Android に依存しないので、手元の確認（dictool）からも使える。
// ネイティブの libkanade_whisper.so は native/whisper にあり、native-whisper（Android）と scripts/build-host-whisper.sh（手元）がビルドする
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
