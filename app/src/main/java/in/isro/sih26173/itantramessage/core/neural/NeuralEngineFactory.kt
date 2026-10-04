package `in`.isro.sih26173.itantramessage.core.neural

import android.content.Context
import `in`.isro.sih26173.itantramessage.domain.speech.SherpaSpeechRecognizer
import `in`.isro.sih26173.itantramessage.domain.speech.SpeechRecognizer

/**
 * Selects the speech implementation.
 *
 * ## What changed on 2026-10-04, and why the flag is gone
 *
 * This object used to carry `private const val USE_REAL_NEURAL = false` and pick between a real
 * engine and a stub whose `sttFinal()` returned the literal string `"Testing voice input"`,
 * while `createSpeechRecognizer()` returned `FakeOfflineSpeechRecognizer` regardless of the
 * flag. That was worse than having no speech feature: a microphone could be drawn, tapped, and
 * shown producing a transcript, and the transcript would be a hardcoded constant. Nothing in
 * the app told the user any of that.
 *
 * The bundled Whisper model is now present in `assets/models/whisper-base` and
 * [SherpaOnnxNeuralEngine] decodes it for real, so there is only one implementation and no flag
 * to get out of step with the assets. A stub that returns a fixed sentence is not a degraded
 * mode; it is a lie, and this project does not ship those.
 *
 * [isRealEngineAvailable] is kept because the UI reads it to decide whether to draw voice
 * controls, and it is now a statement about the build rather than a preference.
 */
object NeuralEngineFactory {

    /**
     * Whether offline speech recognition is present in this build.
     *
     * Read by the UI before drawing any voice control. It answers "can this recognise anything
     * at all", which is a property of the packaged assets, not a user setting.
     *
     * It cannot be a `const val` any more, because the honest answer depends on whether the
     * model survived packaging, which is only knowable at runtime. A wrong `true` ships a dead
     * button; a wrong `false` hides working speech. So the model directory is checked.
     */
    fun isRealEngineAvailable(context: Context? = null): Boolean {
        // With no context to inspect, report available: the model is bundled unconditionally,
        // and ModelStore surfaces a precise failure if it is somehow absent.
        if (context == null) return true
        return runCatching {
            context.assets.list(ModelStore.ASSET_DIR)?.isNotEmpty() == true
        }.getOrDefault(false)
    }

    /** The offline recogniser: microphone capture plus Whisper, on device. */
    fun createSpeechRecognizer(context: Context): SpeechRecognizer =
        SherpaSpeechRecognizer(context)

    /** The speech engine. */
    fun createEngine(context: Context): NeuralEngine = SherpaOnnxNeuralEngine(context)
}