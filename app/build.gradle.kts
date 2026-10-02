plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

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
    doLast {
        rootProject.file("eval/daily.tsv").copyTo(File(out, "eval-daily.tsv"), overwrite = true)
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
            // 実機（Fold 7）と、to-fu のエミュレータ
            abiFilters += listOf("arm64-v8a", "x86_64")
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

    androidResources {
        // 圧縮せずに入れ、辞書は APK から直接 mmap する
        noCompress += listOf("kdic", "kcon", "kpos", "gguf")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 当面ローカルでしか配らないので、debug 鍵で署名して入れられるようにする
            signingConfig = signingConfigs.getByName("debug")
        }
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
}
