package `in`.isro.sih26173.itantramessage.data.device

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * This installation's stable, non-identifying device id.
 *
 * ## What it is not
 *
 * It is not the IMEI, the Android ID, the serial, the advertising id, the MAC address,
 * the phone number, or anything derived from them. All of those are either
 * hardware identifiers that survive a factory reset, restricted permissions, or personal
 * data. The brief asks for a lightweight local identifier with no personally identifying
 * information, and a peer only needs something that distinguishes this install from other
 * installs of the app.
 *
 * ## What it is
 *
 * 24 bits of random data, generated once and stored in this app's private
 * SharedPreferences, formatted as `IT-XXXXXX`.
 *
 * ## Why SharedPreferences, and why that was not always true
 *
 * An earlier version stored the id in [android.provider.Settings.Secure] and the class
 * comment explained at length why that was the right choice: "a device id stored in a plain
 * file in shared storage would be both more exposed and less clearable".
 *
 * Both halves of that reasoning are wrong, and the failure was measured on two handsets.
 *
 * `Settings.Secure` is writable only by a holder of `WRITE_SECURE_SETTINGS`, which is a
 * signature-level permission that no ordinary app can obtain. Every write therefore threw,
 * the `runCatching` around it swallowed the exception, and the app minted a **fresh random
 * id on every single launch**. Verified on both test devices:
 *
 * ```
 * adb shell settings get secure itantra_message_device_id   ->  null
 * ```
 *
 * after the app had plainly written it, alongside the framework's own complaint:
 *
 * ```
 * E/DatabaseUtils: java.lang.SecurityException: Permission denial, must have one of:
 *     [android.permission.WRITE_SECURE_SETTINGS]
 * ```
 *
 * The consequences were not cosmetic. The device id is half of every conversation id, so a
 * new id on each launch meant:
 *
 *  * every queued message became addressed to a peer that no longer existed, and the send
 *    path marked it FAILED with "no route to that peer" -- user-visible messages destroyed
 *    by simply opening the app again;
 *  * the chat screen's title showed the id from the moment of navigation while the live
 *    value was different; and
 *  * the two phones agreed on keys within a session but the conversation was unrecognisable
 *    between them.
 *
 * The second error is the more interesting one: SharedPreferences in `MODE_PRIVATE` is not
 * "shared storage" and is not readable by other apps, and it *is* removed by "Clear data"
 * and by uninstall. So the properties the comment wanted are all still true of the storage
 * it rejected. Only the storage was wrong, and it was wrong in the one direction that
 * matters -- it did not persist.
 *
 * This is recorded at length because the failure mode was silent: the write path had a
 * `runCatching`, so the app started normally, looked healthy, and quietly produced a new
 * identity on every launch. A defensive catch around a write that must succeed is only
 * defensible if something reports when it does not.
 *
 * ## Why a reinstall or a wipe yields a *new* id
 *
 * Clearing app data or uninstalling removes the entry, so the next launch mints a new id.
 * That is the correct behaviour: an id that survived a wipe would be a tracking identifier,
 * which is precisely what this design avoids. The cost is that a conversation id containing
 * the old id no longer matches, so old messages become unreachable -- which is why Settings
 * > Storage explains the count rather than showing an empty list silently.
 */
class DeviceIdentity(context: Context) {

    private val appContext = context.applicationContext

    /**
     * The stable id, minted on first call.
     *
     * Read once per process on first use. That is a single small preferences read at
     * startup, and it is cheaper than holding a DataStore flow open for a value that never
     * changes once written.
     */
    val id: String by lazy { loadOrCreate() }

    /**
     * The user-chosen display name, defaulting to the manufacturer model.
     *
     * `Build.MODEL` is a device property, not personal data, and it is far more useful in
     * a peer list than an id would be. It is overridable in Settings.
     */
    val displayName: String by lazy {
        val model = Build.MODEL?.trim().orEmpty()
        if (model.isNotEmpty()) model else "Android device"
    }

    /**
     * The preferences file holding the id.
     *
     * `commit()` rather than `apply()` at the one place that matters. `apply()` returns
     * immediately and flushes in the background, so a process killed moments after first
     * launch -- which is exactly what happens when the user installs and the system reclaims
     * memory -- can lose the write and mint a second id. One synchronous write of six bytes
     * on first launch only is not a cost worth optimising away.
     */
    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun loadOrCreate(): String {
        // Read and validate rather than trusting the stored string. Parsed here instead of
        // relying on getInt, so a malformed value is the same "no id yet" case an absent one
        // is rather than a NumberFormatException on startup.
        val existing = prefs.getString(KEY_DEVICE_ID, null)
            ?.trim()
            ?.toIntOrNull()

        if (existing != null && existing > 0) {
            return format(existing)
        }

        val fresh = randomId()

        // No runCatching. The previous version wrapped this write and swallowed the failure,
        // which is how a permission error that made the app mint a new identity on every
        // launch stayed invisible for an entire round of hardware testing. A write that the
        // app cannot survive losing should fail loudly; if this ever throws, the process
        // dies at startup instead of quietly corrupting every conversation id.
        val written = prefs.edit().putString(KEY_DEVICE_ID, fresh.toString()).commit()

        if (!written) {
            Log.e(TAG, "device id could not be persisted; refusing to continue with an id that will not survive")
            throw IllegalStateException("device id could not be persisted")
        }

        Log.i(TAG, "device id persisted for this install")

        return format(fresh)
    }

    /**
     * A random 24-bit id.
     *
     * 16.7 million values is a deliberate, and deliberate limitation. Two installs in the
     * same room colliding is a birthday problem: at 2 devices the chance is about 1 in
     * 8 million, which is fine for a phone-to-phone demo and would not be for a
     * deployment with thousands. The field is one byte wider than it needs to be for the
     * demo case, and the documentation says so rather than implying a larger space than
     * exists. Widening [COLUMN_ID] to a long is a one-line change when it is needed --
     * the id is opaque on the wire, so no protocol change follows from it.
     */
    private fun randomId(): Int {
        val random = java.security.SecureRandom()
        var value: Int
        do {
            // `1 + random.nextInt(0x1000000)`, NOT `random.nextInt(1, 0x1000000)`.
            //
            // The two-argument `Random.nextInt(origin, bound)` is a Java 17 method, present
            // from Android API 34. This project sets `minSdk = 24` and does not enable core
            // library desugaring, so the ranged form compiles happily and then dies at
            // runtime on every older device with
            //
            //     java.lang.NoSuchMethodError: No virtual method nextInt(II)I
            //       in class Ljava/security/SecureRandom
            //
            // Found by running the release build on an Android 11 handset (API 30): the
            // process died the moment a user tapped a peer, because that is the first thing
            // that touches `identity.id`. An API 35 phone in the same test never hit it,
            // which is exactly why a single modern device cannot validate `minSdk`.
            //
            // The single-argument `nextInt(bound)` is API 1 and has always worked. The
            // offset is added here rather than passed in, which is the whole fix.
            value = 1 + random.nextInt(0x1000000)
            // 0x000000 and 0xFFFFFF are avoided so the formatted id never reads as a
            // placeholder or an all-ones sentinel.
        } while (value == 1 || value == 0xFFFFFF)
        return value
    }

    private fun format(value: Int): String =
        String.format("IT-%06X", value)

    private companion object {
        const val TAG = "DeviceIdentity"

        const val PREFS_NAME = "itantra_message_device"

        /**
         * The key. Named for what it is rather than for where it lives.
         *
         * Kept as the same string the previous `Settings.Secure` version used, so the intent
         * is legible to anyone comparing the two. It is now a SharedPreferences key in this
         * app's private file, not a global settings entry.
         */
        const val KEY_DEVICE_ID = "itantra_message_device_id"
    }
}
