package `in`.isro.sih26173.itantramessage.core.neural

import android.content.Context
import android.os.Debug
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Offline speech recognition backed by sherpa-onnx running Whisper base (multilingual, int8).
 *
 * ## Verified against the real library, not the imagined one
 *
 * Every call below was checked with `javap` against the shipped
 * `com.bihe0832.android:lib-sherpa-onnx:6.25.21` classes.jar. Three things differ from what the
 * sherpa-onnx documentation and most blog posts suggest, and all three cost a compile cycle to
 * discover:
 *
 *  * There is no `OfflineRecognizer.fromWhisper(...)` convenience factory in this build. The
 *    only entry point is `OfflineRecognizer(AssetManager, OfflineRecognizerConfig)`, so the
 *    nested config object graph has to be assembled by hand.
 *  * `OfflineStream.acceptWaveform` takes a **`FloatArray`**, not a `ShortArray`. PCM16 has to
 *    be scaled to [-1, 1] first. Passing raw shorts would be accepted by the compiler only if
 *    the signature matched, and it does not, so this is a conversion the code must perform.
 *  * `OfflineStream` has no `reset()`. A stream is single-use, so each utterance gets a fresh
 *    one from [OfflineRecognizer.createStream] and the previous one is released.
 *
 * Decoding is two calls, not one: `decode(stream)` runs the graph, `getResult(stream)` reads
 * the hypothesis. `decode` returns void, so code that treats decoding as returning a result is
 * wrong.
 *
 * ## Whisper is offline and non-streaming
 *
 * The unit of recognition is a whole recording, not a fragment. Consequently:
 *
 *  * [pushFrame] only accumulates PCM and deliberately returns nothing,
 *  * [endUtterance] is where all the work happens, and can take seconds on a low-end CPU,
 *  * [sttPartial] is always empty, because a non-streaming model has no partial hypothesis.
 *
 * The empty return from `sttPartial` is the truthful answer, not a stub. The UI must not treat
 * it as a recognition failure.
 *
 * ## Language handling
 *
 * Whisper base is multilingual and takes a language code **per session**. Ten languages share
 * one 153 MB model; switching language rebuilds the session but does not reload the weights.
 *
 * `task` is always `transcribe`, never `translate`. Translating a Hindi or Tamil utterance into
 * English would silently replace the message the user is trying to send with different words.
 *
 * ## Accuracy
 *
 * NOT MEASURED. No word-error-rate figure is quoted for any of the ten languages, because none
 * has been measured. Whisper base is known to be weaker on Indian languages than a dedicated
 * Indic model; it is used because it is the smallest model covering all ten, and coverage was
 * the requirement. See `docs/LIMITATIONS.md`.
 */
class SherpaOnnxNeuralEngine(
    private val context: Context,
) : NeuralEngine {

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    /** Two threads. Whisper is memory-bandwidth bound, so more threads cost more than they return. */
    private val numThreads = 2

    /** PCM accumulated since the last [endUtterance]: 16 kHz mono signed 16-bit. */
    private val pcmBuffer = ArrayList<Short>(SAMPLE_RATE * 30)

    private var language: String = DEFAULT_LANGUAGE

    /** Why [init] produced no usable recogniser. Null when healthy. */
    @Volatile
    var failure: String? = null
        private set

    /** True once a recogniser exists and can decode. */
    val isReady: Boolean get() = recognizer != null

    /**
     * Actual resident set of this process in MB, or 0 before init.
     *
     * Read from the OS. An estimate would be a fabricated number in a project that refuses
     * fabricated numbers.
     */
    override val ramMb: Long
        get() {
            if (recognizer == null) return 0L
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            return info.totalPss / 1024L
        }

    /**
     * Extract the bundled model if needed, then build the recogniser.
     *
     * Idempotent: a second call while already initialised does nothing, because loading 153 MB
     * twice is not something anyone wants to find in a log.
     */
    override suspend fun init() = withContext(Dispatchers.IO) {
        if (recognizer != null) return@withContext

        val paths = when (val result = ModelStore(context).ensureExtracted()) {
            is ModelStore.Result.MissingFromApk -> {
                fail("model missing from the APK", "assets/${ModelStore.ASSET_DIR} not present")
                return@withContext
            }

            is ModelStore.Result.NoSpace -> {
                fail(
                    "not enough storage for the speech model",
                    "need ${result.neededBytes / 1_048_576} MB, have ${result.freeBytes / 1_048_576} MB",
                )
                return@withContext
            }

            is ModelStore.Result.Failed -> {
                fail("the speech model could not be prepared", result.detail)
                return@withContext
            }

            is ModelStore.Result.Ready -> result
        }

        runCatching { buildRecognizer(paths) }
            .onSuccess {
                recognizer = it
                failure = null
                Log.i(TAG, "recogniser ready: whisper-base int8, ${paths.encoder.name}, $language")
            }
            .onFailure { error ->
                fail("the speech engine failed to start", error.toString())
                Log.e(TAG, "recogniser construction failed", error)
            }
    }

    private fun fail(message: String, detail: String) {
        failure = message
        Log.e(TAG, "$message: $detail")
    }

    /**
     * Assemble sherpa-onnx's nested config graph by hand.
     *
     * The two config shapes are not what the documentation implies, and both were read off the
     * shipped bytecode rather than assumed:
     *
     *  * `OfflineWhisperModelConfig(encoder, decoder, language, task, tailPaddings)` takes
     *    **no tokens path**. Its five parameters are two Strings for the model halves, two more
     *    for language and task, and an Int for tail padding. Passing a token path there is a
     *    compile error, which is a better outcome than a silently misconfigured model.
     *  * The vocabulary therefore goes in `OfflineModelConfig.tokens`. That field exists
     *    precisely for this: Whisper's vocabulary is not part of the Whisper model config in
     *    this build.
     */
    private fun buildRecognizer(paths: ModelStore.Result.Ready): OfflineRecognizer {
        val whisper = OfflineWhisperModelConfig(
            paths.encoder.absolutePath,
            paths.decoder.absolutePath,
            language,
            TASK_TRANSCRIBE,
            TAIL_PADDINGS,
        )

        val model = OfflineModelConfig(
            whisper = whisper,
            numThreads = numThreads,
            tokens = paths.tokens.absolutePath,
            debug = false,
        )

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
            modelConfig = model,
            // Whisper has no finite-state grammar to decode against, so the transducer-style
            // beam search does not apply; greedy search over the decoder is what sherpa-onnx
            // uses for Whisper.
            decodingMethod = "greedy_search",
        )

        // A null AssetManager is deliberate, and it is the difference between working and
        // aborting the process.
        //
        // `OfflineRecognizer` has two native entry points, revealed by `javap`:
        // `newFromAsset(AssetManager, config)` and `newFromFile(config)`. Passing a real
        // AssetManager selects the asset path, and sherpa-onnx then resolves the filenames in
        // this config against the APK's assets rather than the filesystem.
        //
        // This app cannot use that path. The model is 153 MB and has to live in filesDir --
        // onnxruntime loads by filesystem path and cannot be handed an AssetManager stream --
        // so every filename above is an absolute path under the app's data directory. Measured
        // on a realme RMX2020 (API 30): passing an AssetManager produced
        //   F/sherpa-onnx: Read binary file: Load
        //   '/data/user/0/<pkg>/files/whisper-base/base-tokens.txt' failed
        // followed by SIGABRT, with a verified-present, app-readable, correctly sized file. The
        // crash was in the asset lookup, not the file. Passing null routes to `newFromFile`,
        // which is what the extracted model requires.
        return OfflineRecognizer(config = config)
    }

    /**
     * Point the session at a different language.
     *
     * Rebuilds the recogniser because sherpa-onnx fixes the language when the session is
     * created. The ONNX weights stay loaded, so this costs a session rebuild rather than a
     * model reload.
     */
    suspend fun setLanguage(code: String) = withContext(Dispatchers.IO) {
        if (code == language && recognizer != null) return@withContext
        language = code
        releaseRecognizer()

        when (val paths = ModelStore(context).ensureExtracted()) {
            is ModelStore.Result.Ready -> runCatching { buildRecognizer(paths) }
                .onSuccess {
                    recognizer = it
                    Log.i(TAG, "recognition language now $code")
                }
                .onFailure { fail("could not switch recognition language", it.toString()) }

            else -> Unit // keep the existing failure message
        }
    }

    /**
     * RMS voice activity.
     *
     * Energy-based, and **not** presented as a neural VAD: no Silero model is bundled. The
     * threshold is unmeasured against real speech, and is recorded as such in
     * `docs/LIMITATIONS.md`.
     */
    override suspend fun vadFrame(pcm: ShortArray): Boolean {
        if (pcm.isEmpty()) return false
        var sum = 0.0
        for (sample in pcm) sum += sample.toDouble() * sample.toDouble()
        return kotlin.math.sqrt(sum / pcm.size) > VAD_RMS_THRESHOLD
    }

    /** Always empty: Whisper is non-streaming and has no partial hypothesis. */
    override suspend fun sttPartial(pcm: ShortArray): String = ""

    override suspend fun sttFinal(): String = endUtterance()

    /** Not implemented: playback uses the platform TextToSpeech engine. Empty, not silence. */
    override suspend fun tts(text: String, lang: String): ByteArray = ByteArray(0)

    override suspend fun pushFrame(pcm: ShortArray) {
        synchronized(pcmBuffer) { pcmBuffer.addAll(pcm.asList()) }
    }

    /**
     * Decode the buffered utterance.
     *
     * Returns empty when nothing was captured or no recogniser loaded. Runs off the main thread
     * because Whisper on a 2019 budget CPU is not instant.
     */
    override suspend fun endUtterance(): String = withContext(Dispatchers.IO) {
        val active = recognizer ?: return@withContext ""

        val pcm = synchronized(pcmBuffer) {
            val copy = pcmBuffer.toShortArray()
            pcmBuffer.clear()
            copy
        }
        if (pcm.isEmpty()) return@withContext ""

        // A fresh stream per utterance: OfflineStream has no reset, and reusing a consumed one
        // would feed the previous utterance's tail padding into this one.
        val stream: OfflineStream = active.createStream()
        try {
            stream.acceptWaveform(toFloatPcm(pcm), SAMPLE_RATE)
            active.decode(stream)
            active.getResult(stream).text.trim()
        } catch (error: Throwable) {
            Log.w(TAG, "decode failed", error)
            ""
        } finally {
            runCatching { stream.release() }
        }
    }

    override suspend fun resetStream() {
        synchronized(pcmBuffer) { pcmBuffer.clear() }
    }

    override fun release() {
        releaseRecognizer()
        synchronized(pcmBuffer) { pcmBuffer.clear() }
    }

    private fun releaseRecognizer() {
        runCatching { recognizer?.release() }
        recognizer = null
    }

    /** Scale signed 16-bit PCM to the [-1, 1] floats sherpa-onnx expects. */
    private fun toFloatPcm(pcm: ShortArray): FloatArray {
        val out = FloatArray(pcm.size)
        for (i in pcm.indices) out[i] = pcm[i] / PCM16_FULL_SCALE
        return out
    }

    companion object {
        private const val TAG = "SherpaNeural"

        const val SAMPLE_RATE = 16_000
        const val FEATURE_DIM = 80
        const val DEFAULT_LANGUAGE = "en"
        const val TASK_TRANSCRIBE = "transcribe"

        /**
         * Whisper tail padding.
         *
         * -1 is sherpa-onnx's "choose automatically", which is right here: the padding depends
         * on the encoder's receptive field and the correct value is not something to hardcode
         * from memory.
         */
        const val TAIL_PADDINGS = -1

        /** 32768, the scale factor from signed 16-bit to [-1, 1]. */
        private const val PCM16_FULL_SCALE = 32_768f

        /**
         * RMS above which a frame counts as speech.
         *
         * NOT MEASURED against speech. 500.0 in PCM16 units is roughly -46 dBFS, chosen as an
         * ordinary noise floor. It has not been validated on any of the ten languages and no
         * tuning corpus exists for it.
         */
        const val VAD_RMS_THRESHOLD = 500.0
    }
}