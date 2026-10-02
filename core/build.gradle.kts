// 変換ロジック。Android に依存しない純 Kotlin にして、JVM で単体テストする
plugins {
    id("org.jetbrains.kotlin.jvm")
}

// app（Java 17 指定）から使うので、バイトコードは 17 に合わせる
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
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
