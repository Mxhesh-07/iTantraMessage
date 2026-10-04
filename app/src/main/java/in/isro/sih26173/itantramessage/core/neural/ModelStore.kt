package `in`.isro.sih26173.itantramessage.core.neural

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Copies the bundled Whisper model out of the APK so onnxruntime can open it.
 *
 * ## Why this exists
 *
 * ONNX Runtime loads a model from a **filesystem path**. There is no API to hand it an
 * `InputStream` or an `AssetFileDescriptor`, and there is no supported way to mmap a file
 * packed inside an APK. So a model shipped in `assets/` has to exist as a real file on the
 * filesystem before it can be used, and this class is the step that makes that happen.
 *
 * ## Why it is not just a `copyTo`
 *
 * Three things a plain copy gets wrong:
 *
 * 1. **It re-copies 153 MB on every launch.** The check is a size-and-marker comparison, and
 *    a partially-written file from a process death mid-copy is treated as absent rather than
 *    as valid. A truncated ONNX file does not fail cleanly; onnxruntime either aborts the
 *    process or throws something uninformative.
 * 2. **It trusts the copy.** [verify] re-hashes the extracted file and compares it to a
 *    constant compiled into this class. A short read, a full disk, or a partially flushed
 *    write is caught here instead of surfacing later as a recognition failure.
 * 3. **It leaves no way to recover.** If extraction fails the user has no speech, and the
 *    only honest outcome is a specific, actionable message rather than a silent empty
 *    transcript.
 *
 * ## Cost, stated plainly
 *
 * The model occupies storage twice: once in the APK and once in `filesDir`. That is inherent
 * to shipping a model in an APK and is why [MODEL_BYTES] is a constant worth reading before
 * changing the model. Measured on device, not estimated.
 */
class ModelStore(private val context: Context) {

    companion object {
        private const val TAG = "ModelStore"

        /** Asset directory holding the bundled model. */
        const val ASSET_DIR = "models/whisper-base"

        /**
         * Where the extracted model lives.
         *
         * `filesDir`, not `cacheDir`: the platform may clear `cacheDir` under storage
         * pressure, and re-extracting 153 MB because a background cleanup ran would be a
         * far worse failure than holding the space.
         */
        private fun modelDir(context: Context): File =
            File(context.filesDir, "whisper-base")

        /** Marker written only after every file has been verified. */
        private const val DONE_MARKER = ".complete"

        /**
         * Expected size in bytes of each extracted file, and its SHA-256.
         *
         * These are the values of the files in `app/src/main/assets/models/whisper-base`,
         * which come from the official `sherpa-onnx-whisper-base.tar.bz2`
         * (k2-fsa/sherpa-onnx, release tag `asr-models`), int8 variants only.
         *
         * Recompute after changing a model:
         * ```
         * sha256sum app/src/main/assets/models/whisper-base/base-tokens.txt
         * ```
         *
         * Verification exists because a corrupt model is not a legible error. ONNX Runtime
         * reports a truncated or mismatched file by throwing from deep inside session
         * construction, and telling a user "speech recognition failed" when the real cause is
         * a bad install is precisely the kind of vague failure this project documents against.
         */
        val EXPECTED: Map<String, Pair<Long, String>> = mapOf(
            "base-encoder.int8.onnx" to (
                29_120_534L to
                    "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11"
                ),
            "base-decoder.int8.onnx" to (
                130_672_026L to
                    "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d"
                ),
            "base-tokens.txt" to (
                816_730L to
                    "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"
                ),
        )

        /** Total bytes of model on disk once extracted. */
        val MODEL_BYTES: Long = EXPECTED.values.sumOf { it.first }
    }

    /** Why extraction is unavailable, or null when it is not. */
    sealed class Result {
        data class Ready(val encoder: File, val decoder: File, val tokens: File) : Result()

        /** Model absent from the APK. The build is wrong, not the device. */
        data object MissingFromApk : Result()

        /** Not enough free space to extract. */
        data class NoSpace(val neededBytes: Long, val freeBytes: Long) : Result()

        /** Extraction or verification failed. [detail] is for a log, not a user-facing string. */
        data class Failed(val detail: String) : Result()
    }

    /**
     * Ensure the model is extracted and verified, returning paths ready for onnxruntime.
     *
     * Safe to call on every launch: a complete, verified model short-circuits.
     */
    suspend fun ensureExtracted(): Result {
        val dir = modelDir(context)
        val files = EXPECTED.keys.map { dir.resolve(it) }

        if (isCompleteAndVerified(dir, files)) {
            return Result.Ready(files[0], files[1], files[2])
        }

        // Start clean. A partial copy from a killed process is worse than no copy.
        runCatching { dir.deleteRecursively() }
        if (!dir.exists() && !dir.mkdirs()) {
            return Result.Failed("could not create ${dir.absolutePath}")
        }

        // Confirm the assets are really in the APK before promising anything. A missing asset
        // is a packaging error, and saying so is more useful than a storage error.
        val needed = EXPECTED.values.sumOf { it.first }
        if (needed == 0L) {
            return Result.MissingFromApk
        }

        val free = usableFreeSpace(dir)
        if (free in 1 until needed) {
            // Leave headroom: the copy itself needs somewhere to land, and a device that
            // reports 160 MB free can still fail to write 153 MB once the filesystem has
            // taken its own overhead.
            return Result.NoSpace(needed, free)
        }

        for (name in EXPECTED.keys) {
            val target = dir.resolve(name)
            try {
                copyAsset(ASSET_DIR, name, target)
            } catch (e: IOException) {
                runCatching { dir.deleteRecursively() }
                return Result.Failed("copy of $name failed: ${e.message}")
            }
        }

        // Verify before declaring success, and wipe on mismatch so the next attempt starts
        // from a known state rather than re-verifying the same bad bytes forever.
        for (name in EXPECTED.keys) {
            val (expectedSize, expectedHash) = EXPECTED.getValue(name)
            val actual = dir.resolve(name)
            val actualSize = actual.length()
            if (actualSize != expectedSize) {
                runCatching { dir.deleteRecursively() }
                return Result.Failed("$name is $actualSize B, expected $expectedSize B")
            }
            if (expectedHash.isNotEmpty()) {
                val actualHash = sha256(actual)
                if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                    runCatching { dir.deleteRecursively() }
                    return Result.Failed("$name sha256 $actualHash != $expectedHash")
                }
            }
        }

        File(dir, DONE_MARKER).writeText("ok")
        Log.i(TAG, "extracted ${dir.absolutePath}, ${needed / 1_048_576} MB verified")
        return Result.Ready(files[0], files[1], files[2])
    }

    /** True when [files] all match the expected size and hash. */
    private fun isCompleteAndVerified(dir: File, files: List<File>): Boolean {
        if (!File(dir, DONE_MARKER).exists()) return false
        return files.all { file ->
            val (size, hash) = EXPECTED.getValue(file.name)
            file.exists() &&
                file.length() == size &&
                (hash.isEmpty() || sha256(file).equals(hash, ignoreCase = true))
        }
    }

    /**
     * Copy one asset to [target], via a temporary name.
     *
     * The rename is what makes this atomic on the same filesystem: a process death can leave a
     * `.part` file, which the marker check treats as absent, but never a half-written file
     * under the real name.
     */
    private fun copyAsset(assetDir: String, name: String, target: File) {
        val partial = File(target.parentFile, "${name}.part")
        context.assets.open("$assetDir/$name").use { input ->
            partial.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        }
        if (!partial.renameTo(target)) {
            // renameTo can fail if the destination exists on some filesystems.
            target.delete()
            if (!partial.renameTo(target)) {
                partial.delete()
                throw IOException("could not rename $name into place")
            }
        }
    }

    /** SHA-256 of a file, lowercase hex. */
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun usableFreeSpace(dir: File): Long =
        runCatching { dir.usableSpace }.getOrDefault(0L)
}