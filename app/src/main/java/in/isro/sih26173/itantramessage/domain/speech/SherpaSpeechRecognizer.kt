package `in`.isro.sih26173.itantramessage.domain.speech

import android.content.Context
import android.util.Log
import `in`.isro.sih26173.itantramessage.core.neural.SherpaOnnxNeuralEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Real offline speech recogniser: microphone capture plus Whisper decoding, on device.
 *
 * ## Why this class exists
 *
 * The two halves of speech recognition were in different places and neither was connected.
 * [AndroidMicRecorder] genuinely captures 16 kHz mono PCM from `AudioRecord`, and
 * [SherpaOnnxNeuralEngine] genuinely decodes it, but nothing carried audio between them: the
 * caller passed `ShortArray(1)`, a one-sample dummy, straight into a fake recogniser. This
 * class owns both halves and the loop between them, so a caller only has to say "start" and
 * "finish".
 *
 * ## Non-streaming, so [finish] is where the wait happens
 *
 * Whisper transcribes a completed recording. There is no partial result to show while the user
 * is still talking, so [RecognitionResult.partial] is never true and the UI has nothing to
 * render until the user releases the button. That is a property of the model, not a missing
 * feature, and the interface says so rather than leaving callers to guess.
 *
 * ## Language codes
 *
 * Whisper expects an ISO 639-1 code. The UI carries region-qualified tags like `hi-IN`, which
 * Whisper does not recognise, so [normaliseLanguage] strips the region. Passing `hi-IN`
 * through unchanged would silently fall back to English and produce a plausible, wrong
 * transcript, which is the worst possible failure for a messaging app.
 */
class SherpaSpeechRecognizer(
    private val context: Context,
) : SpeechRecognizer {

    private val engine = SherpaOnnxNeuralEngine(context)
    private val recorder = AndroidMicRecorder(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The capture loop. Null when not recording. */
    private var capture: Job? = null

    @Volatile
    private var active = false

    /**
     * Serialises decoding.
     *
     * A `Mutex`, not `synchronized`: decoding is a suspending call, and holding a monitor across
     * a suspension point blocks the carrier thread for the whole Whisper inference -- seconds on
     * a budget CPU. A monitor is also wrong in principle here, since the thread is released and
     * possibly a different one resumes.
     */
    private val decodeLock = Mutex()

    @Volatile
    private var configuredLanguage: String = "en"

    override val isActive: Boolean get() = active

    override var languageCode: String
        get() = configuredLanguage
        set(value) {
            val normalised = normaliseLanguage(value)
            if (normalised == configuredLanguage) return
            configuredLanguage = normalised
        }

    /**
     * Begin capturing microphone audio into the decoder's buffer.
     *
     * Loads the model on first use. The 153 MB extraction happens here rather than at app
     * start so a user who never taps the microphone never pays for it.
     */
    override suspend fun start() {
        if (active) return
        engine.setLanguage(configuredLanguage)
        engine.init()
        engine.resetStream()

        active = true
        capture = scope.launch {
            try {
                recorder.samples.collect { frame -> engine.pushFrame(frame) }
            } catch (error: Throwable) {
                // A closed flow here means the mic was revoked or the device denied it. The
                // user-visible consequence is that finish() returns nothing, so log it rather
                // than letting it disappear into a coroutine exception handler.
                Log.w(TAG, "capture stopped: ${error.message}")
                active = false
            }
        }
    }

    /**
     * Stop capturing and decode what was said.
     *
     * Returns an empty result when nothing was captured, when the model failed to load, or when
     * Whisper heard no speech. All three are ordinary outcomes, not errors, and the caller
     * distinguishes them by [SherpaOnnxNeuralEngine.failure] if it needs to.
     */
    override suspend fun endUtterance(): RecognitionResult {
        teardownCapture()
        if (engine.failure != null) {
            return RecognitionResult(text = "", isFinal = true, confidence = 0f)
        }
        val text = decodeLock.withLock { engine.endUtterance() }
        return RecognitionResult(
            text = text,
            partial = false,
            confidence = if (text.isBlank()) 0f else 1f,
            isFinal = true,
        )
    }

    /** Stop capturing without decoding, discarding the utterance. */
    override suspend fun stop() {
        teardownCapture()
        engine.resetStream()
    }

    /**
     * Append one PCM frame to the buffer, without decoding.
     *
     * Part of [SpeechRecognizer] and shaped by the engine rather than by convenience. Whisper is
     * non-streaming, so there is no hypothesis to return per frame: this buffers audio and
     * reports an empty, non-final result. Callers that want text must use [endUtterance].
     *
     * The previous caller passed `ShortArray(1)` here, a one-sample dummy, and expected a
     * transcript. That could only ever work against a stub returning canned text.
     */
    override suspend fun recognize(frame: ShortArray, offset: Int, length: Int): RecognitionResult {
        if (!active || length <= 0) return RecognitionResult(partial = true)
        engine.pushFrame(frame.copyOfRange(offset, offset + length))
        return RecognitionResult(text = "", partial = true)
    }

    override fun reset() {
        capture?.cancel()
        capture = null
        active = false
        recorder.stop()
    }

    /** Release the recogniser and its native memory. */
    fun release() {
        reset()
        recorder.release()
        engine.release()
        scope.cancel()
    }

    private suspend fun teardownCapture() {
        active = false
        // Stop the recorder first: that ends the samples flow, which is what lets the collect
        // in start() return. Cancelling the job alone would leave AudioRecord holding the mic.
        recorder.stop()
        capture?.cancelAndJoin()
        capture = null
    }

    private companion object {
        const val TAG = "SherpaRecognizer"

        /**
         * Reduce a UI language tag to something Whisper accepts.
         *
         * `hi-IN` -> `hi`, `en-US` -> `en`, `ENGLISH` -> `english`. Whisper takes the
         * subtag before the hyphen; anything without one is passed through lowercased.
         */
        fun normaliseLanguage(tag: String): String {
            val base = tag.trim().lowercase().substringBefore('-')
            return base.ifEmpty { "en" }
        }
    }
}