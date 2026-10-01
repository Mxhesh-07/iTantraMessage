package `in`.isro.sih26173.itantramessage

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
                )
            }
        }
    }

    /** Request the permissions this API level needs, if any. */
    fun requestNearbyPermissions() {
        permissionLauncher.launch(nearbyPermissions())
    }

    /**
     * The permissions to ask for, chosen by API level.
     *
     * Two eras, matching the manifest. On API 31+ the Bluetooth permissions are runtime
     * and the location permission is not wanted at all; below 31 the Bluetooth permissions
     * are granted at install time and a BLE scan returns nothing without location, so that
     * is what is asked for.
     */
    private fun nearbyPermissions(): Array<String> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    override fun onDestroy() {
        onPermissionResult = null
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
) {
    val navController = rememberNavController()

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

            HomeScreen(
                state = state,
                onSearch = {
                    val blocker = state.blocker
                    if (blocker == null) {
                        viewModel.startScan()
                    } else if (blocker is `in`.isro.sih26173.itantramessage.data.nearby.NearbyError.PermissionMissing) {
                        // Only the permission case can be fixed from here without leaving
                        // the app. Bluetooth-off is fixed in system Settings, which this
                        // screen deliberately does not launch: a user who tapped "turn on
                        // Bluetooth" deserves to see the system toggle, not a silent retry.
                        requestPermissions(viewModel.requiredPermissions())
                    } else {
                        viewModel.refresh()
                    }
                },
                onStopSearch = viewModel::stopScan,
                onSelectDevice = viewModel::connect,
                onSelectTransport = viewModel::selectTransport,
                onRetry = viewModel::refresh,
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
            )
        }
    }
}
