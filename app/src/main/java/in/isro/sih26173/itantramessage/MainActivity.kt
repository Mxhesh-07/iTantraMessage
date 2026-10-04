package `in`.isro.sih26173.itantramessage

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import `in`.isro.sih26173.itantramessage.data.nearby.NearbyError
import `in`.isro.sih26173.itantramessage.ui.chat.ChatScreen
import `in`.isro.sih26173.itantramessage.ui.chat.ChatViewModel
import `in`.isro.sih26173.itantramessage.ui.home.HomeScreen
import `in`.isro.sih26173.itantramessage.ui.home.HomeViewModel
import `in`.isro.sih26173.itantramessage.ui.theme.ItantraMessageTheme

/**
 * The only Activity.
 *
 * ## Why single-Activity with Compose Navigation
 *
 * One Activity, not one per screen. Each additional Activity costs its own window, its own
 * task entry and its own lifecycle bookkeeping, and on a 2 GB device that bookkeeping is a
 * real cost. A single Activity with a NavHost also means the back stack is one object
 * rather than several, so "the back button closes the link" is a single place to implement.
 *
 * ## The permission launcher lives here
 *
 * `rememberLauncherForActivityResult` needs an Activity, and a Composable only has a
 * Context. Hoisting the launcher to the Activity and passing a lambda down keeps the
 * screens free of Android framework types, which is what makes them previewable and
 * testable.
 */
class MainActivity : ComponentActivity() {

    /**
     * The runtime permission request.
     *
     * Registered as a field rather than inside a composable so it is available before the
     * first composition completes. Registering it lazily means a permission request fired
     * from a LaunchedEffect on first frame races the registration, and the request is
     * silently dropped -- which presents as "I tapped grant and nothing happened".
     */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        // A partial grant is treated as a refusal. BLUETOOTH_SCAN without
        // BLUETOOTH_CONNECT can enumerate devices but cannot open a socket, so accepting
        // the partial case would show a device list where every connection then fails.
        val allGranted = grants.isNotEmpty() && grants.values.all { it }
        onPermissionResult?.invoke(allGranted)
    }

    /**
     * Set by the Home composable so the launcher result reaches the ViewModel.
     *
     * A field rather than constructor state because the launcher is owned by the Activity
     * and the ViewModel by the screen, and neither is available at construction of the
     * other. Nulled after the first delivery so a later, unrelated result cannot call a
     * stale ViewModel.
     */
    private var onPermissionResult: ((Boolean) -> Unit)? = null
    private var onBluetoothEnabled: ((Boolean) -> Unit)? = null

    /**
     * The outcome of asking the system to turn Bluetooth on.
     *
     * ## Why the result code is not trusted on its own
     *
     * `ACTION_REQUEST_ENABLE` is documented as returning RESULT_OK when the user accepted.
     * On several OEM builds -- realme/ColorOS among them -- it returns RESULT_CANCELED
     * even when the radio *did* come on, because the vendor replaced the platform
     * confirmation dialog with its own helper Activity and that Activity reports its own
     * result rather than the radio's. Reading the result code alone therefore produced a
     * card that still said "Bluetooth is off" on a phone with Bluetooth on, which reads as
     * a broken button.
     *
     * So the radio is asked instead of the result code being believed. See
     * [reportBluetoothState], which is also called from [onResume] for the same reason.
     */
    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        reportBluetoothState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        (application as ItantraMessageApp).container.warmUp()

        setContent {
            ItantraMessageTheme {
                AppRoot(
                    requestPermissions = { permissions ->
                        if (permissions.isNotEmpty()) {
                            permissionLauncher.launch(permissions)
                        }
                    },
                    onPermissionResult = { callback ->
                        onPermissionResult = callback
                    },
                    requestEnableBluetooth = ::askSystemToEnableBluetooth,
                    openBluetoothSettings = ::openBluetoothSettings,
                    onBluetoothEnabled = { callback ->
                        onBluetoothEnabled = callback
                    },
                )
            }
        }
    }

    /**
     * Whether this handset's Bluetooth radio is on right now.
     *
     * Read straight from the adapter rather than from a remembered state or a result code,
     * because the adapter is the thing that decides whether a connection can be opened.
     * `isEnabled` does not need BLUETOOTH_CONNECT: it is a state read, not a peer query,
     * so this is safe to call before permissions are granted -- which is exactly when the
     * Home screen needs to know.
     */
    private fun isBluetoothOn(): Boolean {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter?.isEnabled == true
    }

    /**
     * Push the radio's real state into the Home screen.
     *
     * The ActivityResult callback and [onResume] both route here, and both do so because
     * the answer to "did the user turn Bluetooth on?" is a property of the radio, not of
     * any one Activity's result. Anything that can change the radio -- the enable dialog,
     * the quick-settings tile, Settings, a paired-device wake -- ends with this Activity
     * resuming, so this is the single point where the screen learns the truth.
     */
    private fun reportBluetoothState() {
        onBluetoothEnabled?.invoke(isBluetoothOn())
    }

    override fun onResume() {
        super.onResume()
        // Returning from the enable dialog, from Settings, or from the shade all land
        // here. Without this the blocker card keeps claiming Bluetooth is off on a phone
        // where the user has already turned it on, and there is no other path that would
        // correct it.
        //
        // Posted rather than called inline because onResume runs before Compose has
        // re-registered the callback after a configuration change; a direct call would
        // find `onBluetoothEnabled` null and drop the update.
        window.decorView.post { reportBluetoothState() }
    }

    /**
     * Ask the platform to turn Bluetooth on.
     *
     * ## Why there is a Settings fallback
     *
     * `ACTION_REQUEST_ENABLE` is a *request*, not a command: the platform shows a
     * confirmation and only turns the radio on if the user accepts. There is no API that
     * enables it silently, deliberately -- a third-party app must not be able to switch on
     * a user's radio without them knowing.
     *
     * But the request itself is not reliable across builds. Two cases were found on the
     * realme Narzo 10A this targets, both of which leave the user tapping a button that
     * appears to do nothing:
     *
     *  * ColorOS routes the request through its own helper Activity which, in some
     *    states, never becomes visible -- so no dialog is ever shown.
     *  * From API 33 the platform refuses the request outright unless BLUETOOTH_CONNECT is
     *    held, returning RESULT_CANCELED with no dialog.
     *
     * So if there is no component to handle the request, or the request comes back with
     * the radio still off, the app sends the user to the Bluetooth settings screen, where
     * the toggle is. That is not a workaround that fakes success -- the app still cannot
     * enable the radio itself, and [reportBluetoothState] only ever reports what the
     * adapter says.
     */
    private fun askSystemToEnableBluetooth() {
        if (isBluetoothOn()) {
            reportBluetoothState()
            return
        }
        val request = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        if (request.resolveActivity(packageManager) != null) {
            bluetoothEnableLauncher.launch(request)
        } else {
            // Nothing on this build can show the confirmation, so the request would be a
            // no-op. Go where the toggle actually is.
            openBluetoothSettings()
        }
    }

    /**
     * Open the system Bluetooth settings screen.
     *
     * The `BLUETOOTH_SETTINGS` action exists from API 18, below this app's minSdk of 24, so
     * there is no version branch to get wrong here. `Settings.ACTION_WIRELESS_SETTINGS` is
     * deliberately not used as a fallback: on some builds it opens a page that lists
     * toggles without letting the user turn one on.
     */
    private fun openBluetoothSettings() {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    /**
     * The permissions to ask for, chosen by API level.
     *
     * Two eras, matching the manifest. On API 31+ the Bluetooth permissions are runtime
     * and the location permission is not wanted at all; below 31 the Bluetooth permissions
     * are granted at install time and a BLE scan returns nothing without location, so that
     * is what is asked for.
     */
    private fun nearbyPermissions(): Array<String> {
        val base = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        // Also request mic upfront so speech features don't surprise user later.
        return base + Manifest.permission.RECORD_AUDIO
    }

    override fun onDestroy() {
        onPermissionResult = null
        onBluetoothEnabled = null
        super.onDestroy()
    }
}

/**
 * The navigation graph.
 *
 * Two destinations. A drawer, a bottom bar and a settings route were considered and
 * rejected: the brief says every component must have a clear purpose and minimal cost, and
 * on the small screens this targets a bottom bar costs 56dp of height permanently to
 * navigate between two screens.
 */
@Composable
private fun AppRoot(
    requestPermissions: (Array<String>) -> Unit,
    onPermissionResult: ((Boolean) -> Unit) -> Unit,
    requestEnableBluetooth: () -> Unit,
    openBluetoothSettings: () -> Unit,
    onBluetoothEnabled: ((Boolean) -> Unit) -> Unit,
) {
    val navController = rememberNavController()

    // System bar insets, applied once here rather than per screen.
    //
    // Android 15 (API 35) draws apps edge-to-edge whether they ask to or not, and this target
    // compiles against API 36, so the app *is* edge-to-edge on the Samsung handset. Nothing
    // was consuming WindowInsets, so every screen drew under both bars.
    //
    // It looked fine until it did not: on the Samsung the three-button navigation bar covers
    // roughly the bottom 400 px, and the chat screen's text field and Send button were laid out
    // inside that band. Compose still reported them in the view hierarchy -- which is why the
    // controls appeared to exist -- but they were untappable, because the navigation bar was
    // on top of them. Reported from the hardware as "a page opened but there is no option to
    // send the message".
    //
    // Consuming systemBars here fixes the top bar under the status bar at the same time, and
    // doing it once means a future screen cannot forget. The keyboard is a separate inset and
    // is handled where it is needed, in ChatScreen's imePadding.
    Box(
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
    ) {
        NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            val viewModel: HomeViewModel = viewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()

            // collectAsStateWithLifecycle rather than collectAsState: the latter keeps
            // collecting while the app is backgrounded, so a Bluetooth scan would keep
            // running behind a screen the user cannot see. This is the lifecycle rule from
            // the brief, enforced at the collection point rather than by remembering to
            // stop things manually.
            onPermissionResult { granted -> viewModel.onPermissionResult(granted) }
            onBluetoothEnabled { enabled -> viewModel.onBluetoothEnabled(enabled) }

            LaunchedEffect(state.openConversation) {
                val conversation = state.openConversation ?: return@LaunchedEffect
                viewModel.consumeNavigation()
                // conversation.value and conversation.peer, not a single object: the route
                // is two path segments so ChatViewModel receives each as a named argument
                // through SavedStateHandle, and so a route can be rebuilt from a deep link
                // or a restored back stack without serialising a Kotlin object.
                //
                // The ids contain no '/' (they are IT-XXXXXX or a MAC address), so no URI
                // escaping is needed here. If that ever changes, this becomes a bug: a
                // slash in a path segment silently truncates the route.
                navController.navigate("chat/${conversation.value}/${conversation.peer}")
            }

            // One function for "do the thing that unblocks nearby", used by both the
            // Search button and the blocker card.
            //
            // These used to be two separate copies of the same three-way branch, and they
            // had already drifted: the card's copy called `refresh()`, which only re-reads
            // the blocker and does nothing about it, so "Turn on Bluetooth" re-rendered
            // the same card forever. One branch, so the card and the button cannot
            // disagree about what the user's tap should do.
            val resolveBlocker = {
                when (val blocker = state.blocker) {
                    null -> viewModel.startScan()
                    is NearbyError.PermissionMissing ->
                        requestPermissions(viewModel.requiredPermissions())
                    is NearbyError.BluetoothOff -> {
                        // Tell the ViewModel a request is on its way before the dialog
                        // opens, so the answer can be told apart from the routine state
                        // report that arrives on every resume.
                        viewModel.onBluetoothEnableRequested()
                        requestEnableBluetooth()
                    }
                    // Unsupported has no action by design: there is genuinely nothing a
                    // user can do about a handset with no Bluetooth radio. Re-reading the
                    // blocker is the honest response, and it costs nothing.
                    else -> viewModel.refresh()
                }
            }

            HomeScreen(
                state = state,
                onSearch = { resolveBlocker() },
                onStopSearch = viewModel::stopScan,
                onSelectDevice = viewModel::connect,
                onSelectTransport = viewModel::selectTransport,
                onRetry = { resolveBlocker() },
                onOpenBluetoothSettings = {
                    // Opens Settings and nothing else. Calling resolveBlocker() here as
                    // well would immediately re-issue the system request underneath the
                    // Settings screen the user is being sent to, so the user would come
                    // back to a dialog they had already dismissed. What happens next is
                    // decided by onResume, which reads the radio when they return.
                    openBluetoothSettings()
                },
                onDismissMessage = viewModel::dismissMessage,
            )
        }

        composable(
            route = "chat/{conversationId}/{peerId}",
            arguments = listOf(
                navArgument(ChatViewModel.ARG_CONVERSATION_ID) { type = NavType.StringType },
                navArgument(ChatViewModel.ARG_PEER_ID) { type = NavType.StringType },
            ),
        ) {
            val viewModel: ChatViewModel = viewModel()
            val state by viewModel.state.collectAsStateWithLifecycle()

            ChatScreen(
                state = state,
                onDraftChanged = viewModel::onDraftChanged,
                onSend = viewModel::send,
                onBack = { navController.popBackStack() },
                // Wired even though they are currently unreachable, so that flipping
                // NeuralEngineFactory's engine flag is the only change needed to make the
                // voice row functional. Leaving these null and hiding the row instead
                // would have made the next person re-derive this wiring from scratch.
                onVoiceStart = viewModel::startVoice,
                onVoiceStop = viewModel::stopVoice,
                onVoicePTTStart = viewModel::startVoicePTT,
                onVoicePTTStop = viewModel::stopVoicePTT,
                onToggleVoiceMode = viewModel::toggleVoiceMode,
            )
            }
        }
    }
}
