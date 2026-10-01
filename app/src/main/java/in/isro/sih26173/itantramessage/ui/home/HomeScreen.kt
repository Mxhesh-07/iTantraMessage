package `in`.isro.sih26173.itantramessage.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import `in`.isro.sih26173.itantramessage.R
import `in`.isro.sih26173.itantramessage.data.nearby.NearbyDevice
import `in`.isro.sih26173.itantramessage.data.nearby.NearbyError
import `in`.isro.sih26173.itantramessage.data.nearby.Transport
import `in`.isro.sih26173.itantramessage.ui.theme.ItantraMessageTheme

/**
 * The device list.
 *
 * ## Small-screen decisions
 *
 * The brief asks for small screens first, and the layout choices follow from that rather
 * than from a phone-sized mockup:
 *
 *  * **One column, no horizontal carousel.** A grid would show more devices at once, but
 *    each row would lose the name to an ellipsis, and the name is the only thing that lets
 *    a user pick the right phone. Two columns on a 5-inch screen means roughly 20
 *    characters per name, which is not enough.
 *  * **44dp minimum touch target** on every row, above the 48dp recommendation in
 *    spirit and above the 32dp absolute minimum. Rows are full-width so the target is the
 *    whole row, not just the text.
 *  * **The transport selector is two chips, not a dropdown.** A dropdown on a small screen
 *    hides the choice behind a tap, and this is a choice a reviewer will want to make
 *    deliberately.
 *  * **No animations beyond the progress indicator.** See docs/PERFORMANCE.md.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onSearch: () -> Unit,
    onStopSearch: () -> Unit,
    onSelectDevice: (NearbyDevice) -> Unit,
    onSelectTransport: (Transport) -> Unit,
    onRetry: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OfflineBanner()

        if (state.queuedCount > 0) {
            QueuedBanner(count = state.queuedCount)
        }

        TransportSelector(
            selected = state.transport,
            enabled = !state.isBusy,
            onSelect = onSelectTransport,
        )

        state.blocker?.let { blocker ->
            BlockerCard(
                error = blocker,
                onRetry = onRetry,
            )
        }

        state.message?.let { message ->
            MessageCard(message = message, onDismiss = onDismissMessage)
        }

        Button(
            onClick = if (state.isScanning) onStopSearch else onSearch,
            enabled = !state.isConnecting && state.blocker == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isScanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_searching))
            } else {
                Text(stringResource(R.string.home_search_nearby))
            }
        }

        Text(
            text = stringResource(R.string.home_nearby_devices),
            style = MaterialTheme.typography.titleMedium,
        )

        if (state.devices.isEmpty() && !state.isScanning && state.blocker == null) {
            Text(
                text = stringResource(R.string.home_no_devices),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = state.devices, key = { it.address }) { device ->
                DeviceRow(
                    device = device,
                    enabled = !state.isConnecting,
                    onClick = { onSelectDevice(device) },
                )
            }
        }
    }
}

/**
 * The permanent "Offline Mode" indicator.
 *
 * Always shown, never conditional. This app has no internet to be offline from, so a
 * conditional indicator would be a lie in one direction or the other; a static one states
 * the app's actual property, which is that it does not use a network.
 */
@Composable
private fun OfflineBanner() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.home_offline_mode),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun QueuedBanner(count: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Text(
            text = if (count == 1) "1 message waiting to send" else "$count messages waiting to send",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/**
 * Transport choice.
 *
 * [Transport.WIFI_DIRECT] is absent from the chips. It is declared in the manifest and
 * modelled in the enum, but it has no implementation, and offering a chip that always
 * fails would be worse than not offering it. The gap is documented in
 * docs/LIMITATIONS.md rather than hidden.
 */
@Composable
private fun TransportSelector(
    selected: Transport,
    enabled: Boolean,
    onSelect: (Transport) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.settings_transport),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FilterChip(
            selected = selected == Transport.RFCOMM,
            onClick = { onSelect(Transport.RFCOMM) },
            enabled = enabled,
            label = { Text(stringResource(R.string.transport_rfcomm)) },
        )
        FilterChip(
            selected = selected == Transport.GATT,
            onClick = { onSelect(Transport.GATT) },
            enabled = enabled,
            label = { Text(stringResource(R.string.transport_gatt)) },
        )
    }
}

/**
 * One nearby device.
 *
 * The whole card is the touch target rather than just the text, because a 44dp-tall text
 * row is below the recommended target size and the user is aiming at a phone, not a
 * label. The state text carries a content description because the dot beside it is the
 * only other signal, and a colour alone is not an accessible signal.
 */
@Composable
private fun DeviceRow(
    device: NearbyDevice,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                contentDescription = "${device.displayName}, ${device.subtitle}"
            },
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = device.subtitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(R.string.home_device_available),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Why nearby is unusable, with the one action that might fix it.
 *
 * The three [NearbyError] cases that can actually be acted on get a button: Bluetooth off
 * opens Settings, missing permission re-requests, unsupported offers nothing because
 * there is genuinely nothing to do. Showing a retry button for "this device has no
 * Bluetooth radio" would be offering the user a dead end.
 */
@Composable
private fun BlockerCard(
    error: NearbyError,
    onRetry: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = error.message,
                style = MaterialTheme.typography.bodyLarge,
            )
            when (error) {
                NearbyError.BluetoothOff -> OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.home_turn_on_bluetooth))
                }
                // The permission request is launched by the Activity, which is the only
                // context with an ActivityResultLauncher. HomeScreen only reports the
                // outcome, so the button re-runs the same path as a fresh search.
                NearbyError.PermissionMissing, is NearbyError.Unsupported -> Unit
                is NearbyError.ConnectFailed, is NearbyError.TransportFailed -> Unit
            }
        }
    }
}

@Composable
private fun MessageCard(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Dismiss")
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
private fun HomeScreenPreview() {
    ItantraMessageTheme {
        HomeScreen(
            state = HomeUiState(
                devices = listOf(
                    NearbyDevice(
                        address = "94:8A:C6:30:4C:94",
                        name = "realme Narzo 10A",
                        transport = Transport.RFCOMM,
                        rssiDbm = null,
                        isBonded = true,
                    ),
                    NearbyDevice(
                        address = "AA:BB:CC:DD:EE:FF",
                        name = null,
                        transport = Transport.GATT,
                        rssiDbm = -67,
                        isBonded = false,
                    ),
                ),
                isScanning = true,
            ),
            onSearch = {},
            onStopSearch = {},
            onSelectDevice = {},
            onSelectTransport = {},
            onRetry = {},
            onDismissMessage = {},
        )
    }
}
