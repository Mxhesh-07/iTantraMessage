package `in`.isro.sih26173.itantramessage.domain.audio

import android.media.AudioFormat

/**
 * Common audio format constants used across mic capture and speech I/O.
 */
object AudioFormatDefaults {
    const val SAMPLE_RATE = 16000
    const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
    const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
    const val ENCODING_PCM_16BIT = AudioFormat.ENCODING_PCM_16BIT
    const val FRAME_SIZE_BYTES = 2 // 16-bit
}
