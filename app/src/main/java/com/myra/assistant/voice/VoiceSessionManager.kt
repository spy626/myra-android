package com.myra.assistant.voice

class VoiceSessionManager {
    enum class Engine { NONE, GEMINI_LIVE, ANDROID_RECOGNIZER }
    @Volatile var activeEngine: Engine = Engine.NONE
        private set
    @Synchronized fun acquire(engine: Engine): Boolean {
        if (activeEngine != Engine.NONE && activeEngine != engine) return false
        activeEngine = engine
        return true
    }
    @Synchronized fun release(engine: Engine) { if (activeEngine == engine) activeEngine = Engine.NONE }
}
