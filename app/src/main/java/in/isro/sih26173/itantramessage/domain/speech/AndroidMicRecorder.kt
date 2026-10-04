package `in`.isro.sih26173.itantramessage.domain.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.app.ActivityCompat
import `in`.isro.sih26173.itantramessage.domain.audio.AudioFormatDefaults
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AudioRecord-based mic capture. Emits frames of 20ms (320 samples @16kHz).
 */
class AndroidMicRecorder(
    private val context: Context,
    private val frameMs: Long = 20L,
) : MicRecorder {
    private var recorder: AudioRecord? = null
    private val recording = AtomicBoolean(false)

    override val isRecording: Boolean
        get() = recording.get() && recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    override val samples: Flow<ShortArray> = callbackFlow {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            close(IllegalStateException("RECORD_AUDIO permission not granted"))
            return@callbackFlow
        }

        val sampleRate = AudioFormatDefaults.SAMPLE_RATE
        val channel = AudioFormatDefaults.CHANNEL_IN
        val encoding = AudioFormatDefaults.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
            .coerceAtLeast((sampleRate * frameMs / 1000).toInt() * 2 * 2)

        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channel,
            encoding,
            bufferSize,
        )
        recorder = rec

        rec.startRecording()
        recording.set(true)

        val frameSize = ((sampleRate * frameMs) / 1000).toInt().coerceAtLeast(1)
        val buf = ShortArray(frameSize)

        try {
            while (isActive && recording.get() && rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = rec.read(buf, 0, frameSize)
                if (read > 0) {
                    val out = if (read == frameSize) buf.copyOf() else buf.copyOfRange(0, read)
                    trySend(out)
                }
            }
        } finally {
            recording.set(false)
            try { rec.stop() } catch (_: Exception) {}
            try { rec.release() } catch (_: Exception) {}
            recorder = null
        }
        awaitClose {}
    }

    override fun start() {
        // Flow consumption starts samples; start is a no-op hook for symmetry
    }

    override fun stop() {
        recording.set(false)
    }

    override fun release() {
        recording.set(false)
        recorder?.release()
        recorder = null
    }
}
