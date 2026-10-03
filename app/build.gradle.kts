import java.util.zip.GZIPOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** ネイティブをビルドする ABI。既定は arm64 だけ（APK を小さくする） */
val kanadeAbis = (findProperty("kanade.abis") as String? ?: "arm64-v8a").split(',').map { it.trim() }

// 辞書（Mozc から変換）と zenz のモデルは、リポジトリに入れずビルド時にそろえて assets に入れる。
// 取得物は .cache/ に置き、2回目以降は取得も変換もしない
val kanadeAssetsDir = layout.buildDirectory.dir("generated/kanadeAssets")
val prepareKanadeAssets by tasks.registering(JavaExec::class) {
    group = "kanade"
    description = "辞書・モデル・評価セットを assets 用にそろえる"
    val dictool = project(":dictool")
    dependsOn(dictool.tasks.named("jar"))
    classpath = dictool.extensions.getByType<JavaPluginExtension>().sourceSets.getByName("main").runtimeClasspath
    mainClass.set("dev.mooma.kanade.dictool.MainKt")
    maxHeapSize = "3g"
    val cache = rootProject.file(".cache")
    val out = kanadeAssetsDir.get().asFile
    args("assets", cache.absolutePath, out.absolutePath)
    outputs.dir(out)
    inputs.property("dictool", dictool.file("src/main/kotlin/dev/mooma/kanade/dictool/Main.kt").readText().hashCode())
    inputs.file(rootProject.file("docs/USAGE.md"))
    inputs.file(rootProject.file("eval/daily.tsv"))
    inputs.file(rootProject.file("eval/daily2.tsv"))
    doLast {
        rootProject.file("eval/daily.tsv").copyTo(File(out, "eval-daily.tsv"), overwrite = true)
        rootProject.file("eval/daily2.tsv").copyTo(File(out, "eval-daily2.tsv"), overwrite = true)
        rootProject.file("docs/USAGE.md").copyTo(File(out, "usage.md"), overwrite = true)
        // 音声認識の速さを端末で測るための見本（to-fu の Windows の音声合成で作った 8.8 秒の日本語）
        rootProject.file("eval/voice-sample-ja.wav").copyTo(File(out, "voice-sample-ja.wav"), overwrite = true)
        // 辞書と接続行列はよく縮む（37MB→16MB、14MB→2MB）ので gzip で入れ、初回にアプリ内へ展開して mmap する。
        // 拡張子を .gz にすると AGP が assets をまとめるときに勝手に展開してしまうので、.kgz にする
        for (name in listOf("system.kdic", "connection.kcon")) {
            val src = File(out, name)
            GZIPOutputStream(File(out, "$name.kgz").outputStream().buffered(), 1 shl 16).use { gz ->
                src.inputStream().use { it.copyTo(gz) }
            }
            src.delete()
        }
    }
}

android {
    namespace = "dev.mooma.kanade"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "dev.mooma.kanade"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        ndk {
            // 既定は実機（Fold 7）の arm64 だけ。to-fu のエミュレータで試すときは -Pkanade.abis=arm64-v8a,x86_64
            abiFilters += kanadeAbis
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static", "-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    sourceSets {
        getByName("main").assets.srcDir(kanadeAssetsDir)
    }

    packaging {
        // ネイティブ（特に sherpa-onnx の 24MB）を APK の中で圧縮する。入れるときに展開されるので、APK の受け渡しが軽くなる
        jniLibs.useLegacyPackaging = true
    }

    androidResources {
        // 圧縮せずに入れ、辞書は APK から直接 mmap する
        noCompress += listOf("kpos", "gguf", "kgz")
    }

    buildTypes {
        release {
            // 設定画面の Compose を入れてコードが大きくなったので、R8 で要るものだけにする（APK が軽く、起動も速い）
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 当面ローカルでしか配らないので、debug 鍵で署名して入れられるようにする
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.named("preBuild") { dependsOn(prepareKanadeAssets) }

dependencies {
    implementation(project(":core"))
    implementation(project(":zenz"))
    implementation(project(":whisper"))
    implementation(project(":native-whisper"))
    // 話しながら文字が出る音声認識（ReazonSpeech）に使う sherpa-onnx。prepareKanadeAssets が .cache/ へ取得する
    implementation(files(rootProject.file(".cache/sherpa-aar/sherpa-onnx-static-link-onnxruntime-1.13.8.aar")))

    // 設定画面（Jetpack Compose、Material 3 Expressive）。Expressive の部品は material3 1.5 の alpha でだけ公開されている。
    // 1.5.0-alpha19 以降は compileSdk 37・AGP 9.1 が要るので、それより前で止める
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.material3:material3:1.5.0-alpha18")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.13.0")
}
