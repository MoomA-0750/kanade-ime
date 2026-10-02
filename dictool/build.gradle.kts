// 辞書の取得・変換・評価をする作業用の道具（APK には入らない）
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
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
    implementation(project(":zenz"))
    implementation(project(":whisper"))
}

application {
    mainClass.set("dev.mooma.kanade.dictool.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx3g")
}
