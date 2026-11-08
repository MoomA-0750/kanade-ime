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
    // Android 版の設定の書き出し（JSON）を読む。Android と同じ API で、パブリックドメイン
    implementation("org.json:json:20250517")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.mooma.kanade.desktop.MainKt")
    applicationName = "kanade-engine"
    applicationDefaultJvmArgs = listOf("-Xmx512m", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

tasks.test {
    useJUnitPlatform()
}

// 話しながらの音声認識（ReazonSpeech）に使う sherpa-onnx の Java 版と Windows のネイティブ（onnxruntime を含む）。
// Android 版の AAR と同じく、版とハッシュを固定して .cache/ に取る
// 入れる先は -Pkanade.target で選ぶ（win-x64・linux-aarch64・linux-x64。既定は Windows 版の win-x64）。
// ネイティブの jar は入れる先の1つだけを入れる（全部入れると配布物が 50MB ほど増える）
val sherpaVersion = "1.13.8"
val desktopTarget = (findProperty("kanade.target") as String?) ?: "win-x64"
val sherpaNatives = mapOf(
    "win-x64" to "33fbdbd5410e9ba9bdda94aa164ec8f7825bb49246420d8ce9bdd88219d97039",
    "linux-aarch64" to "5123d2e48ae1a7ce82ba89ce153906c651bf418a63db2f7dff82265e6d49c104",
    "linux-x64" to "30c93b59381113f9c20aedbbf9fc1ad399158f6bc03dddc0f8934a6e28e069ba",
)
val sherpaJars = mapOf(
    "sherpa-onnx-jvm-$sherpaVersion.jar" to "77b7b047fade4eadada96b568eb92615049aaf1dc317c7244e46c1ea38b9a63b",
    "sherpa-onnx-native-lib-$desktopTarget-$sherpaVersion.jar" to (sherpaNatives[desktopTarget] ?: error("知らない kanade.target: $desktopTarget")),
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
