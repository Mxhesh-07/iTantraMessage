package `in`.isro.sih26173.itantramessage.ui.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import `in`.isro.sih26173.itantramessage.ItantraMessageApp
import `in`.isro.sih26173.itantramessage.data.database.DeliveryStatus
import `in`.isro.sih26173.itantramessage.data.nearby.ByteLink
import `in`.isro.sih26173.itantramessage.data.nearby.NearbyDevice
import `in`.isro.sih26173.itantramessage.data.nearby.NearbyError
import `in`.isro.sih26173.itantramessage.data.nearby.NearbyManager
import `in`.isro.sih26173.itantramessage.data.nearby.Transport
import `in`.isro.sih26173.itantramessage.domain.model.ConversationId
import `in`.isro.sih26173.itantramessage.domain.repository.MessageRepository
import `in`.isro.sih26173.itantramessage.data.device.PeerIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * State for the device list.
 *
 * A single immutable [HomeUiState] rather than several independent flows. The reason is
 * not style: the screen must never be able to show "scanning" and "no devices found" at
 * the same time, and separate flows make that combination reachable during a recomposition
 * while one of them is still emitting its previous value. One object, one source of truth,
 * no impossible intermediate states.
 */
data class HomeUiState(
    val devices: List<NearbyDevice> = emptyList(),
    val isScanning: Boolean = false,
    val isConnecting: Boolean = false,
    val connectingTo: String? = null,

    /**
     * How many messages are stored but not yet delivered.
     *
     * Surfaced on Home because a queued message is invisible once the user leaves the
     * chat: they tapped Send, the bubble said "Queued", and there is no other place in
     * the app that tells them whether it went out. A count is cheaper than a notification
     * and cannot become stale, because it is derived from the same query that drives it.
     */
    val queuedCount: Int = 0,
    val transport: Transport = Transport.RFCOMM,
    val blocker: NearbyError? = null,
    val message: String? = null,
    val openConversation: ConversationId? = null,
) {
    val isBusy: Boolean get() = isScanning || isConnecting

    /**
     * Whether there is anything to connect to.
     *
     * A single bonded device that is this handset itself is not a peer. Every phone's
     * bonded list includes the devices it has paired with, and a phone paired with itself
     * is a real configuration, so it is filtered rather than shown and then failing.
     */
    val hasPeers: Boolean get() = devices.isNotEmpty()
}

/** One row in the conversation list on the Home screen. */
data class ConversationSummary(
    val conversationId: String,
    val peerId: String,
    val peerName: String,
    val lastPreview: String,
    val lastTimestamp: Long,
    val undeliveredCount: Int,
)

/**
 * Home screen ViewModel: discovery, connection, and the conversation list.
 *
 * ## Scoping
 *
 * [androidx.lifecycle.viewModelScope] rather than a hand-rolled scope, because it is
 * cancelled automatically when the ViewModel clears. The scan is collected inside that
 * scope, which is what guarantees the radio is switched off when the user leaves: the
 * flow's `awaitClose` runs on cancellation, and cancellation is tied to the screen's
 * lifetime. There is no `onCleared` override to forget.
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as ItantraMessageApp).container
    private val nearby = container.nearby
    private val identity = container.identity
    private val repository = container.repository
    private val peers = container.peers

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** The scan job, held so a second Search press cancels rather than doubles up. */
    private var scanJob: Job? = null

    /**
     * The identification deadline, cancelled the moment the peer identifies itself.
     *
     * Held rather than fired-and-forgotten, because an uncancelled deadline will cheerfully
     * overwrite the state with a failure message ten seconds *after* a connection that
     * worked. Found on hardware: the chat opened correctly, the user pressed back to Home,
     * and found "That device did not identify itself" describing a failure that never
     * happened. A timeout that cannot be cancelled is a lie waiting for a slow success.
     */
    private var identifyJob: Job? = null

    init {
        // Reflect the real blocker at construction rather than waiting for the first
        // action. A user whose Bluetooth is off should see that on arrival, not after
        // pressing a button that cannot work.
        _state.update { it.copy(blocker = nearby.currentBlocker()) }

        observeQueueDepth()
        observePeerIdentification()
        listenForIncoming()
    }

    /**
     * Answer RFCOMM connections initiated by the other phone.
     *
     * Started at construction rather than when the user taps a device, because this is what
     * makes the *other* phone's tap work. Only one phone can be the initiator for a given
     * connection, so whichever app the user taps first is dialling and whichever they did not
     * tap must already be listening -- and there is no way to know in advance which that is.
     * Listening from the moment the Home screen exists is the only arrangement that works
     * without the user being asked to choose a role.
     *
     * The collection is scoped to [viewModelScope], so leaving the screen closes the listening
     * socket and releases the radio. That matters for battery: a listening socket holds the
     * adapter awake, and an app that listens while backgrounded is the usual way a "no
     * internet" messenger turns out to cost 4% an hour doing nothing.
     *
     * An incoming link replaces whatever is attached, so answering a second peer disconnects
     * the first. The app holds one conversation at a time by design; see
     * `docs/LIMITATIONS.md`.
     */
    private fun listenForIncoming() {
        viewModelScope.launch {
            try {
                nearby.acceptIncoming().collect { link: ByteLink ->
                    Log.i(TAG, "accepted an incoming connection from ${link.peerLabel}")
                    _state.update {
                        it.copy(
                            isConnecting = false,
                            connectingTo = null,
                            message = "${link.peerLabel} connected",
                        )
                    }
                    repository.attach(link)
                }
            } catch (t: CancellationException) {
                throw t // Leaving the screen is not a failure.
            } catch (t: Throwable) {
                // A listener that cannot start must not take the rest of the screen with it.
                // The app still works as a dialer; it just cannot be dialled.
                Log.w(TAG, "incoming listener stopped: ${t.javaClass.simpleName}")
                _state.update {
                    it.copy(message = "This phone cannot accept incoming connections.")
                }
            }
        }
    }

    /**
     * Open the chat as soon as the peer announces itself.
     *
     * This is what unblocks the connection path: [connect] opens a link and then waits,
     * because until the handshake arrives there is no device id to build a conversation key
     * from. Watching the flow here rather than blocking inside `connect` keeps the identifying
     * pause cancellable -- if the user navigates away, this collector's scope ends and the
     * wait stops with it.
     */
    private fun observePeerIdentification() {
        viewModelScope.launch {
            repository.peerDeviceId.collect { peerId ->
                if (peerId == null || _state.value.openConversation != null) return@collect
                // Only while a connection is actually pending. A hello arriving later, with
                // no connect in flight, must not navigate the user somewhere unasked.
                if (!_state.value.isConnecting) return@collect
                openConversationWith(peerId)
            }
        }
    }

    /**
     * How many messages are waiting to be delivered.
     *
     * Shown on the Home screen because an undelivered queue is the one piece of app state
     * a user can be surprised by: they sent something, saw it disappear, and have no way
     * to know it is still waiting. Making the count visible is cheaper than an
     * explanation dialog.
     */
    private fun observeQueueDepth() {
        viewModelScope.launch {
            repository.observeQueue().collect { queue ->
                val undelivered = queue.count { entity ->
                    val status = DeliveryStatus.entries.firstOrNull { it.name == entity.status }
                    status?.isRetriable == true
                }
                _state.update { it.copy(queuedCount = undelivered) }
            }
        }
    }

    // ---- discovery -------------------------------------------------------------------

    /**
     * Start scanning, replacing any scan already running.
     *
     * Bonded devices are listed immediately and unconditionally, because they need no
     * radio operation: they are already known to the platform. Only the BLE scan actually
     * costs power, and it runs only while this screen is collecting.
     */
    fun startScan() {
        scanJob?.cancel()

        val blocker = nearby.currentBlocker()
        if (blocker != null) {
            _state.update {
                it.copy(isScanning = false, devices = emptyList(), blocker = blocker)
            }
            return
        }

        val bonded = visibleDevices(nearby.bondedDevices())

        _state.update {
            it.copy(
                isScanning = true,
                devices = bonded,
                blocker = null,
                message = null,
            )
        }

        scanJob = viewModelScope.launch {
            val found = mutableMapOf<String, NearbyDevice>()
            // Seed with the bonded set so a BLE hit for an already-paired device updates
            // the existing row rather than appending a second one.
            bonded.forEach { found[it.address] = it }

            nearby.scanBle().collect { event ->
                when (event) {
                    is NearbyManager.ScanEvent.Found -> {
                        val device = event.device
                        // A scan will always find this phone, since a phone is within
                        // range of itself. Skipping it here as well as in
                        // visibleDevices() because a scan hit re-enters the list after
                        // the bonded set was filtered.
                        if (visibleDevices(listOf(device)).isEmpty()) {
                            return@collect
                        }
                        val merged = found[device.address]?.copy(
                            // A bonded peer has no RSSI; a scan hit does. Take whichever
                            // the radio actually reported rather than keeping a stale null.
                            rssiDbm = device.rssiDbm ?: found[device.address]?.rssiDbm,
                        ) ?: device
                        found[device.address] = merged

                        _state.update { current ->
                            current.copy(
                                devices = found.values
                                    // Bonded first, then strongest signal. Sorting on every
                                    // emission is fine: the list is bounded by the number
                                    // of devices in a room, and a stable order stops the
                                    // row a user is about to tap from moving.
                                    .sortedWith(
                                        compareByDescending<NearbyDevice> { it.isBonded }
                                            .thenByDescending { it.rssiDbm ?: Int.MIN_VALUE },
                                    ),
                            )
                        }
                    }

                    is NearbyManager.ScanEvent.Failed -> {
                        _state.update { it.copy(isScanning = false, blocker = event.error) }
                    }
                }
            }
            _state.update { it.copy(isScanning = false) }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _state.update { it.copy(isScanning = false) }
    }

    /** Switch the transport, dropping any scan in progress. */
    fun selectTransport(transport: Transport) {
        stopScan()
        _state.update { it.copy(transport = transport, devices = visibleDevices(nearby.bondedDevices())) }
        startScan()
    }

    // ---- connection ------------------------------------------------------------------

    /**
     * Connect to [device] and open its conversation.
     *
     * Failures are surfaced as a message on the existing state rather than as a thrown
     * exception. The user cannot act on a stack trace, and the UI has no way to display
     * one, so the failure text comes from the transport and is already written for a
     * person.
     */
    fun connect(device: NearbyDevice) {
        if (_state.value.isConnecting) return

        _state.update {
            it.copy(isConnecting = true, connectingTo = device.displayName, message = null)
        }

        viewModelScope.launch {
            // One call, rather than a `when` over transports here.
            //
            // This ViewModel used to open the link itself, passing a literal `null` adapter
            // to RfcommTransport.connect -- so every RFCOMM connection failed with "no
            // Bluetooth adapter", on every phone, with no visible cause. NearbyManager
            // resolves the adapter because it owns it, and because a caller that cannot see
            // the adapter cannot pass a wrong one.
            //
            // It also means this file holds no transport knowledge at all: adding Wi-Fi
            // Direct later is a change in NearbyManager, not in the ViewModel.
            nearby.openLink(device)
                .onSuccess { link: ByteLink ->
                    repository.attach(link)

                    // The conversation key needs the peer's *device id*, not its MAC, and the
                    // MAC is all discovery tells us. Building the key from the address here
                    // -- which is what this used to do -- produced a key no stored message
                    // would ever match: the chat opened on an empty conversation while
                    // messages went to the key the repository uses, so they were invisible
                    // and undeliverable with no error anywhere.
                    //
                    // So navigation waits for the handshake, and uses the id the peer
                    // announces. A previously-met peer resolves from the registry with no
                    // round trip; a new one costs one exchange and a visible pause.
                    val known = peers.deviceIdFor(device.address)
                    if (known != null) {
                        openConversationWith(known)
                    } else {
                        // Stay in "connecting" until the handshake lands. `isConnecting`
                        // rather than a separate flag so the row cannot be tapped again and
                        // the spinner is the same one the user is already watching.
                        //
                        // Bounded by [IDENTIFY_TIMEOUT_MS]. An open link does not imply a
                        // peer that talks: the far app may be an older build, or stopped
                        // mid-session with the socket still up. Without the deadline the
                        // row would spin indefinitely with no way out but leaving the screen.
                        _state.update {
                            it.copy(
                                isConnecting = true,
                                connectingTo = device.displayName,
                                message = "Identifying ${device.displayName}...",
                            )
                        }
                        identifyJob?.cancel()
                        identifyJob = viewModelScope.launch {
                            delay(IDENTIFY_TIMEOUT_MS)
                            abandonIdentification()
                        }
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "connect to ${device.address} failed: ${error.message}")
                    _state.update {
                        it.copy(
                            isConnecting = false,
                            connectingTo = null,
                            message = error.message ?: NearbyError.TransportFailed("unknown").message,
                        )
                    }
                }
        }
    }

    /**
     * Open the chat with a peer we can now name.
     *
     * Both ends derive the same key from the same two ids, because [ConversationId.of]
     * sorts them -- so it does not matter which side calls this.
     */
    private fun openConversationWith(peerDeviceId: String) {
        // The deadline has done its job. Cancelling it here is what stops it later reporting
        // a failure for the connection it was watching succeed.
        identifyJob?.cancel()
        identifyJob = null
        _state.update {
            it.copy(
                isConnecting = false,
                connectingTo = null,
                message = null,
                openConversation = ConversationId.of(identity.id, peerDeviceId),
            )
        }
    }

    /** Called once the chat screen has taken over, so Home does not reopen it. */
    fun consumeNavigation() {
        _state.update { it.copy(openConversation = null) }
    }

    /**
     * Give up on the handshake and show a message the user can act on.
     *
     * Reached only from the identifying timeout. Without this the row would spin forever: the
     * link can be open while the peer never announces, if the far app is an older build or
     * was force-stopped mid-session.
     */
    private fun abandonIdentification() {
        identifyJob = null
        // Never overwrite a conversation that already opened: if the peer identified after
        // the deadline elapsed but before this state update landed, the user is in a working
        // chat and this would throw them back out with an error.
        if (_state.value.openConversation != null) return
        _state.update {
            it.copy(
                isConnecting = false,
                connectingTo = null,
                message = "That device did not identify itself. " +
                    "Both phones need iTantra Message open on the same version.",
            )
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    /** Re-check permissions and radio, e.g. on return from the system settings screen. */
    fun refresh() {
        _state.update { it.copy(blocker = nearby.currentBlocker()) }
    }

    fun requiredPermissions(): Array<String> = nearby.requiredRuntimePermissions()

    fun onPermissionResult(granted: Boolean) {
        if (granted) {
            _state.update { it.copy(blocker = null) }
            startScan()
        } else {
            // A denial is not a dead end: the state carries a message the Home screen
            // renders next to a "grant" action, so the user can retry without reinstalling.
            _state.update {
                it.copy(
                    isScanning = false,
                    blocker = NearbyError.PermissionMissing,
                    message = "Nearby permission required to find nearby devices",
                )
            }
        }
    }

    /**
     * Peers to show, minus this handset.
     *
     * A phone is always within radio range of itself, so without this filter the bonded
     * list contains "this device" and tapping it opens a conversation with yourself --
     * same keystore, same screen, same message history twice.
     *
     * The filter is best-effort by design and the honest reason is in
     * [NearbyManager.localBluetoothAddress]: on API 31+ the platform will not report this
     * phone's own Bluetooth address, and the supported substitute is a different
     * identifier under the same name. Showing one entry too many is a cosmetic miss;
     * labelling one identifier as another would be a lie on screen.
     */
    private fun visibleDevices(devices: List<NearbyDevice>): List<NearbyDevice> {
        val selfAddress = nearby.localBluetoothAddress() ?: return devices
        return devices.filterNot { it.address.equals(selfAddress, ignoreCase = true) }
    }

    private companion object {
        const val TAG = "HomeViewModel"

        /**
         * How long to wait for a peer's identification before giving up.
         *
         * `NOT MEASURED`: chosen from the protocol, not from a stopwatch. The handshake is a
         * single 15-byte frame on a link that is already open and flowing, so the floor is
         * one RFCOMM round trip plus the peer's own startup work. Ten seconds is several
         * orders of magnitude above that, which makes it safe against a slow device, and
         * short enough that a user who tapped the wrong phone is not left watching a spinner.
         *
         * Recorded as unmeasured because it *is* unmeasured -- no two-device run has been
         * performed. See `docs/PERFORMANCE.md`.
         */
        const val IDENTIFY_TIMEOUT_MS = 10_000L
    }
}
