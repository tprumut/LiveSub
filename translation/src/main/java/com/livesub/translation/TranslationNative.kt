package com.livesub.translation

object TranslationNative {
    init {
        System.loadLibrary("livesub_translation")
    }

    external fun nativeLoadModel(modelPath: String, threads: Int): Long
    external fun nativeTranslate(contextPointer: Long, prompt: String, maxTokens: Int, threads: Int): String
    external fun nativeFree(contextPointer: Long)
}
