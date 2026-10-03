# sherpa-onnx のネイティブは、設定のクラスの項目を名前で読む（GetFieldID）。名前を変えず、項目も消さない
-keep class com.k2fsa.sherpa.onnx.** { *; }
# zenz・whisper の JNI（native メソッドの名前は既定の規則で残るが、クラスごと残しておく）
-keep class dev.mooma.kanade.zenz.ZenzNative { *; }
-keep class dev.mooma.kanade.whisper.WhisperNative { *; }
