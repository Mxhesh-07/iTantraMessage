package `in`.isro.sih26173.itantramessage.data.device

import android.content.Context
import android.util.Log

/**
 * What this app knows about the device id behind each Bluetooth address it has met.
 *
 * ## Why this has to exist
 *
 * A conversation key is built from two **device ids** -- `IT-8F3A21` -- because both ends must
 * be able to compute the same key without negotiating anything. That is the whole property
 * that makes offline chat work: neither phone tells the other what to call the conversation.
 *
 * The problem is that at the moment the user taps a peer in the list, this app knows the
 * peer's *MAC address* and nothing else. The device id only appears inside an encrypted
 * message envelope, which means:
 *
 *  - the sender cannot build a conversation key before it has sent something, and
 *  - the receiver cannot build one before it has received something.
 *
 * So the UI built its key from `identity.id` and `device.address` -- a device id and a MAC --
 * while the repository built its key from two device ids. Those two keys never match, the
 * chat screen opens on a conversation with no rows in it, and every message the user sends
 * goes to a conversation nothing will ever read. Silently, with no error on either side.
 *
 * This class is the missing link: the peer tells us its device id once, over the link, and we
 * remember which MAC it belongs to.
 *
 * ## Why a handshake rather than deriving the id from the MAC
 *
 * Hashing the MAC would remove the need for a handshake entirely, which is tempting. It does
 * not work: `BluetoothAdapter.getAddress()` is a hidden API from Android 12, so on any modern
 * phone the app cannot read its own address and therefore cannot compute the hash. The id has
 * to be *exchanged*, because it cannot be *derived*.
 *
 * The MAC is used only as a local lookup key. It is never sent and never trusted for
 * anything else: see [docs/SECURITY.md] §6.1 on why a display name and a MAC together are
 * not an identity.
 *
 * ## Why `SharedPreferences` and not a Room table
 *
 * This is a handful of entries, read on connect and written on handshake, and losing it
 * costs one extra handshake -- not data. A Room table would mean a migration and a foreign-key
 * story for a cache whose worst case is "ask again".
 */
class PeerIdentity(context: Context) {

    private val appContext = context.applicationContext
    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * The device id previously announced by [address], or null if never seen.
     *
     * Returning null rather than a guess is deliberate. A wrong answer here produces a
     * conversation key that matches no rows, which presents as "my messages have
     * disappeared" -- the worst possible symptom for a messaging app, and one with no
     * visible cause. Waiting for the handshake costs a few hundred milliseconds and is
     * visible as a brief "identifying".
     */
    fun deviceIdFor(address: String): String? =
        prefs.getString(keyFor(address), null)?.takeIf { it.isNotBlank() }

    /**
     * Record the device id announced by [address].
     *
     * Overwrites without complaint. A MAC can legitimately change — private addresses rotate,
     * and a user can reset Bluetooth settings — and a phone that changes its own address while
     * keeping its install is the same conversation partner. A conflicting id is therefore
     * treated as "the peer is new" rather than as an error, because blocking on it would mean
     * one stale row permanently blocks a conversation.
     */
    fun remember(address: String, deviceId: String) {
        if (!isPlausibleDeviceId(deviceId)) {
            Log.w(TAG, "ignoring implausible device id '$deviceId' from $address")
            return
        }
        prefs.edit().putString(keyFor(address), deviceId).apply()
        Log.i(TAG, "peer $address announced $deviceId")
    }

    /** Whether any device id is known for [address]. Lets the UI avoid a pointless wait. */
    fun knows(address: String): Boolean = deviceIdFor(address) != null

    /**
     * Accept only the shape this app mints.
     *
     * The value arrives from a peer, so it is untrusted input that will be used as a database
     * key. An arbitrary string here could contain the `|` that separates conversation keys,
     * and `ConversationId.parse` splits on it — so a hostile value could make a conversation
     * resolve to a different peer than the one actually connected.
     *
     * Validating against the known format is the defence. Rejecting is silent for the sender:
     * they never learn they were ignored, which is correct, since an invalid id is not worth
     * an error dialog.
     */
    private fun isPlausibleDeviceId(value: String): Boolean = DEVICE_ID.matches(value)

    private fun keyFor(address: String) = "$PREFIX${address.uppercase()}"

    private companion object {
        const val TAG = "PeerIdentity"
        const val PREFS_NAME = "itantra_message_peers"
        const val PREFIX = "mac:"

        /** `IT-` plus exactly six uppercase hex digits, which is what `DeviceIdentity` mints. */
        val DEVICE_ID = Regex("""IT-[0-9A-F]{6}""")
    }
}