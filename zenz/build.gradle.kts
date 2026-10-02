// zenz を JNI 越しに呼ぶ Kotlin 側。Android に依存しないので、手元の評価（dictool）からも使える。
// ネイティブの libkanade_zenz.so は native/ にあり、app（Android）と scripts/build-host-zenz.sh（手元）がビルドする
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

dependencies {
    implementation(project(":core"))
}
