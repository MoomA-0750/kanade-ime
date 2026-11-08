package dev.mooma.kanade.zenz

/** native/zenz_bridge.cpp の入口。ライブラリの読み込みは呼ぶ側が先に済ませる */
internal object ZenzNative {
    external fun init(modelPath: ByteArray, threads: Int, nCtx: Int): Long
    external fun free(handle: Long)
    external fun cancel(handle: Long)
    external fun score(handle: Long, prompt: ByteArray, candidates: Array<ByteArray>): FloatArray?
    external fun greedy(handle: Long, prompt: ByteArray, maxTokens: Int): ByteArray?
    external fun verify(handle: Long, prompt: ByteArray, candidate: ByteArray): ByteArray?
    external fun setThreads(handle: Long, threads: Int)
}
