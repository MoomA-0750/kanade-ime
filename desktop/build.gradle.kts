// Windows 版のエンジン（常駐する JVM のプロセス）。TSF の DLL（windows/tsf）とソケットでつながり、core で入力を判断する。
// Windows に依存するのは DLL の側だけにして、ここは Linux でも動かして試せるようにする
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
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.mooma.kanade.desktop.MainKt")
    applicationName = "kanade-engine"
    applicationDefaultJvmArgs = listOf("-Xmx512m", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dsun.java2d.uiScale=1")
}

tasks.test {
    useJUnitPlatform()
}
