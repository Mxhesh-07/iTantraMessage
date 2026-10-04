package `in`.isro.sih26173.itantramessage.domain.speech

import kotlinx.coroutines.flow.Flow

interface MicRecorder {
    fun start()
    fun stop()
    fun release()
    val samples: Flow<ShortArray>
    val isRecording: Boolean
}
