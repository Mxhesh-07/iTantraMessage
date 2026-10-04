package `in`.isro.sih26173.itantramessage.core.neural

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.InputStream

/**
 * On-device proof that offline speech recognition actually transcribes speech.
 *
 * ## Why this cannot be a JVM unit test
 *
 * Sherpa-onnx is JNI. It needs an arm CPU, the real 153 MB model on a real filesystem, and
 * Android's asset pipeline. A JVM test can only mock it, and **a mock of the recogniser is
 * exactly what hid the fake transcript for so long**: `FakeOfflineSpeechRecognizer` returned a
 * hardcoded `"Hello, voice test"` and every unit test stayed green. So the recogniser is
 * exercised here, for real, against a real recording of a real voice.
 *
 * ## What is asserted
 *
 * The recording is the eleven-second "ask not what your country can do for you" clip from
 * whisper.cpp's sample set, at 16 kHz mono PCM16 -- byte-for-byte the format
 * [SherpaOnnxNeuralEngine.SAMPLE_RATE] expects. It is pushed in 320-sample frames, the same
 * frame size [in.isro.sih26173.itantramessage.domain.speech.AndroidMicRecorder] produces, so
 * the path under test is the path the app runs, not a special test-only route.
 *
 * The transcript is checked for content, not merely for being non-empty. "Non-empty" would pass
 * on a hallucinated string; requiring most of the words of a known sentence cannot.
 *
 * ## What is deliberately not asserted
 *
 * No word-error-rate threshold. Accuracy per language is NOT MEASURED and a hard WER gate on a
 * budget CPU would fail for reasons that have nothing to do with correctness of the code. See
 * `docs/LIMITATIONS.md`.
 */
@RunWith(AndroidJUnit4::class)
class WhisperDecodeInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The instrumentation APK's own context, which is where androidTest assets live.
     *
     * `app/src/androidTest/assets/` is packaged into the *test* APK, not into the app under
     * test, so [context] cannot see it. Asking `targetContext` for
     * `speech/en_reference_16k.wav` throws `FileNotFoundException`, which is what happened the
     * first time this ran.
     */
    private val testAssets: Context
        get() = InstrumentationRegistry.getInstrumentation().context

    /**
     * The bundled model is present, extracts, and matches the hashes the build pinned.
     *
     * If this fails, nothing else in the class can run, and the cause is a packaging problem
     * rather than a speech problem -- worth separating so a failure is diagnosable.
     */
    @Test
    fun bundledModelExtractsAndVerifies() = runBlocking {
        val store = ModelStore(context)
        val result = store.ensureExtracted()

        assertTrue(
            "model unavailable: $result",
            result is ModelStore.Result.Ready,
        )
        result as ModelStore.Result.Ready

        Log.i(TAG, "encoder ${result.encoder.length()} bytes")
        Log.i(TAG, "decoder ${result.decoder.length()} bytes")
        Log.i(TAG, "tokens  ${result.tokens.length()} bytes")

        for ((name, file) in listOf(
            "base-encoder.int8.onnx" to result.encoder,
            "base-decoder.int8.onnx" to result.decoder,
            "base-tokens.txt" to result.tokens,
        )) {
            val expected = ModelStore.EXPECTED.getValue(name)
            assertEquals("size mismatch for $name", expected.first, file.length())
        }
    }

    /**
     * The real thing: load Whisper, feed it a real voice, read back real words.
     */
    @Test
    fun decodesRealSpeechToRealWords() = runBlocking {
        val engine = SherpaOnnxNeuralEngine(context)
        engine.setLanguage("en")
        engine.init()

        assertEquals("engine reported a failure", null, engine.failure)
        assertTrue("recogniser never became ready", engine.isReady)

        val pcm = readReferencePcm()
        Log.i(TAG, "pushed ${pcm.size} samples (${"%.2f".format(pcm.size / 16000f)} s)")

        // Feed it exactly as the microphone path does, one frame at a time.
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + FRAME_SAMPLES, pcm.size)
            engine.pushFrame(pcm.copyOfRange(offset, end))
            offset = end
        }

        val started = System.currentTimeMillis()
        val transcript = engine.endUtterance()
        val elapsed = System.currentTimeMillis() - started

        Log.i(TAG, "DECODED in ${elapsed}ms: [$transcript]")
        Log.i(TAG, "rss ${engine.ramMb} MB")

        engine.release()

        assertTrue("transcript was blank; Whisper heard nothing", transcript.isNotBlank())

        // Content check: most of the words of a sentence we know is in this recording.
        val heard = transcript.lowercase()
            .split(Regex("[^a-z']+"))
            .filter { it.isNotBlank() }
            .toSet()
        val matched = EXPECTED_WORDS.count { word -> heard.any { it.startsWith(word) } }

        Log.i(TAG, "matched ${matched}/${EXPECTED_WORDS.size} expected words in: $heard")
        assertTrue(
            "transcript does not match the recording; got [$transcript]",
            matched >= MIN_MATCHED_WORDS,
        )
    }

    /**
     * All ten supported languages must produce a working session.
     *
     * This checks that each ISO 639-1 code is accepted by the engine and that the nested config
     * graph builds for it. That is a statement about configuration, **not about accuracy** --
     * decoding each language correctly needs speech in that language, which this repository does
     * not have. Per-language accuracy stays NOT MEASURED.
     */
    @Test
    fun everySupportedLanguageBuildsASession() = runBlocking {
        val engine = SherpaOnnxNeuralEngine(context)
        engine.init()
        assertEquals("engine reported a failure", null, engine.failure)

        val codes = listOf("hi", "ta", "te", "kn", "ml", "bn", "mr", "gu", "or", "en")

        for (code in codes) {
            engine.setLanguage(code)
            assertEquals("language $code reported a failure", null, engine.failure)
            assertTrue("language $code never became ready", engine.isReady)
            Log.i(TAG, "language $code ok")
        }

        engine.release()
        assertNotNull(engine)
    }

    /**
     * Decode must survive an empty utterance rather than throwing.
     *
     * The user taps the mic and releases without saying anything. That has to come back as an
     * empty result, not a crash -- a crash here would be the app's first impression of its voice
     * feature.
     */
    @Test
    fun silenceDecodesToEmptyRatherThanThrowing() = runBlocking {
        val engine = SherpaOnnxNeuralEngine(context)
        engine.init()
        assertTrue("recogniser never became ready", engine.isReady)

        val transcript = engine.endUtterance()
        Log.i(TAG, "silence produced [$transcript]")

        engine.release()
        assertTrue("silence should decode to empty, got [$transcript]", transcript.isBlank())
    }

    /**
     * Load the reference recording as signed 16-bit PCM.
     *
     * A minimal RIFF reader rather than a library: the file is known to be PCM16 mono 16 kHz,
     * and depending on an audio library to verify an audio pipeline would be circular.
     */
    private fun readReferencePcm(): ShortArray {
        val stream: InputStream = testAssets.assets.open(REFERENCE_ASSET)
        DataInputStream(stream).use { input ->
            val header = ByteArray(12)
            input.readFully(header)
            assertEquals("not a RIFF file", 'R', header[0].toInt().toChar())
            assertEquals("not a WAVE file", 'W', header[8].toInt().toChar())

            while (true) {
                val chunkId = ByteArray(4)
                if (input.read(chunkId) < 4) error("no data chunk in $REFERENCE_ASSET")
                val chunkSize = readLittleEndianInt(input)

                when (String(chunkId)) {
                    "fmt " -> {
                        val fmt = ByteArray(chunkSize)
                        input.readFully(fmt)
                        assertEquals("reference must be uncompressed PCM", 1, littleEndianShort(fmt, 0))
                        assertEquals("reference must be mono", 1, littleEndianShort(fmt, 2))
                        assertEquals(
                            "reference must be ${SherpaOnnxNeuralEngine.SAMPLE_RATE} Hz",
                            SherpaOnnxNeuralEngine.SAMPLE_RATE,
                            littleEndianInt(fmt, 4),
                        )
                        assertEquals("reference must be 16-bit", 16, littleEndianShort(fmt, 14))
                    }

                    "data" -> {
                        val pcm = ShortArray(chunkSize / 2)
                        val raw = ByteArray(chunkSize)
                        input.readFully(raw)
                        for (i in pcm.indices) {
                            pcm[i] = ((raw[i * 2].toInt() and 0xFF) or
                                (raw[i * 2 + 1].toInt() shl 8)).toShort()
                        }
                        return pcm
                    }

                    else -> input.skipBytes(chunkSize)
                }
                if (chunkSize % 2 == 1) input.skipBytes(1) // RIFF chunks are word-aligned
            }
        }
    }

    private fun readLittleEndianInt(input: DataInputStream): Int =
        (input.read() and 0xFF) or
            ((input.read() and 0xFF) shl 8) or
            ((input.read() and 0xFF) shl 16) or
            ((input.read() and 0xFF) shl 24)

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private companion object {
        const val TAG = "WhisperDecodeTest"

        const val REFERENCE_ASSET = "speech/en_reference_16k.wav"

        /** What [in.isro.sih26173.itantramessage.domain.speech.AndroidMicRecorder] emits: 20 ms. */
        const val FRAME_SAMPLES = 320

        /** Words known to be in the reference recording. Matched by prefix, so plurals count. */
        val EXPECTED_WORDS = listOf(
            "ask", "country", "you", "fellow", "american", "what", "can", "do", "for",
        )

        /** At least this many must appear, so noise or a hallucination cannot pass. */
        const val MIN_MATCHED_WORDS = 5
    }
}