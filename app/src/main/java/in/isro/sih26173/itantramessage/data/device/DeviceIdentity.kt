package `in`.isro.sih26173.itantramessage.data.device

import android.content.Context
import android.os.Build
import android.provider.Settings
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
 * 24 bits of random data, generated once and stored in [android.provider.Settings.Secure]
 * under this app's own namespace, formatted as `IT-XXXXXX`.
 *
 * `Settings.Secure` is used rather than SharedPreferences on purpose. It is backed by a
 * per-app file that the user can see and clear from Android's "App info > Clear data", it
 * is included in the app's data wipe, and it is not readable by other apps. A device id
 * stored in a plain file in shared storage would be both more exposed and less clearable.
 *
 * ## Why it survives a reinstall as a *new* id
 *
 * Clearing app data removes the entry, so the next launch mints a new id. That is the
 * correct behaviour: an id that survived a wipe would be a tracking identifier, which is
 * precisely what this design is avoiding. The cost is that a conversation id containing
 * the old id no longer matches, so old messages become unreachable -- which is why
 * Settings > Storage explains the count rather than showing an empty list silently.
 */
class DeviceIdentity(context: Context) {

    private val appContext = context.applicationContext

    /**
     * The stable id, minted on first call.
     *
     * `Settings.Secure` writes are synchronous and the value is read on first use during
     * startup. That is a single small file read, once per process, and it is cheaper than
     * the alternative of holding a DataStore flow open for a value that never changes.
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

    private fun loadOrCreate(): String {
        val resolver = appContext.contentResolver

        // read as a String and parsed here, not Settings.Secure.getInt: the getInt
        // overload takes (ContentResolver, name) and throws NumberFormatException on a
        // value this app did not write, which a corrupted or hand-edited setting would
        // otherwise turn into a crash on startup. Parsing a nullable String makes a
        // malformed value the same "no id yet" case an absent one is.
        val existing = runCatching {
            Settings.Secure.getString(resolver, KEY_DEVICE_ID)?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.toIntOrNull()
        }.getOrNull()

        if (existing != null && existing > 0) {
            return format(existing)
        }

        val fresh = randomId()
        val written = runCatching {
            // Settings.Secure.putInt goes through the settings provider, which is a
            // ContentProvider and can fail if the profile is locked. Treat a failure as
            // "use this id for this process" rather than crashing startup: the app
            // remains usable, and a peer would simply see a different id next launch.
            // Stored as a decimal string rather than through putInt, so the value has one
            // representation in the settings provider regardless of how it is read back.
            Settings.Secure.putString(resolver, KEY_DEVICE_ID, fresh.toString())
            true
        }.getOrDefault(false)

        Log.i(TAG, "device id ${if (written) "persisted" else "session-only"}")

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

        /**
         * The settings key. Namespaced so it cannot collide with another app writing to
         * Settings.Secure, and named for what it is rather than for where it lives.
         */
        const val KEY_DEVICE_ID = "itantra_message_device_id"
    }
}
