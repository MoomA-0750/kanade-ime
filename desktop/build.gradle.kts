import java.security.MessageDigest

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
    implementation(project(":whisper"))
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

// 話しながらの音声認識（ReazonSpeech）に使う sherpa-onnx の Java 版と Windows のネイティブ（onnxruntime を含む）。
// Android 版の AAR と同じく、版とハッシュを固定して .cache/ に取る
val sherpaVersion = "1.13.8"
val sherpaJars = mapOf(
    "sherpa-onnx-jvm-$sherpaVersion.jar" to "77b7b047fade4eadada96b568eb92615049aaf1dc317c7244e46c1ea38b9a63b",
    "sherpa-onnx-native-lib-win-x64-$sherpaVersion.jar" to "33fbdbd5410e9ba9bdda94aa164ec8f7825bb49246420d8ce9bdd88219d97039",
)
val sherpaDir = rootProject.file(".cache/sherpa-jvm")
val fetchSherpaJvm by tasks.registering {
    group = "kanade"
    description = "sherpa-onnx の Java 版を取得する"
    outputs.files(sherpaJars.keys.map { File(sherpaDir, it) })
    doLast {
        sherpaDir.mkdirs()
        for ((name, sha) in sherpaJars) {
            val f = File(sherpaDir, name)
            fun hash() = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            if (f.exists() && hash() == sha) continue
            uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/$name").toURL().openStream().use { input ->
                f.outputStream().use { input.copyTo(it) }
            }
            check(hash() == sha) { "$name のハッシュが合わない" }
        }
    }
}
tasks.named("compileKotlin") { dependsOn(fetchSherpaJvm) }
dependencies {
    implementation(files(sherpaJars.keys.map { File(sherpaDir, it) }))
}
