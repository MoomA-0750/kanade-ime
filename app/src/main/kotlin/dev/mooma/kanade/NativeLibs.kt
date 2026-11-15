package dev.mooma.kanade

import android.os.Build
import java.io.File

/**
 * zenz・whisper のネイティブを、CPU に合う版で読む。ふだんの版は内積命令（dotprod）を使い、それの無い arm64 の CPU
 * （Pixel 3 の Snapdragon 845 など）では落ちる（SIGILL）ので、armv8-a だけで組んだ `_v80` の版を読む
 */
object NativeLibs {
    /** この端末の CPU が dotprod（/proc/cpuinfo の asimddp）を持つか。arm64 でなければ true（ふだんの版で動く） */
    val hasDotprod: Boolean by lazy {
        if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") return@lazy true
        // 読めなければ安全な方（dotprod なし）にする
        val features = runCatching { File("/proc/cpuinfo").readLines() }.getOrDefault(emptyList())
            .filter { it.startsWith("Features") }
        features.isNotEmpty() && features.all { line -> line.substringAfter(':').split(' ').contains("asimddp") }
    }

    /** [name]（`kanade_zenz` など）を読む */
    fun load(name: String) {
        System.loadLibrary(if (hasDotprod) name else "${name}_v80")
    }
}
