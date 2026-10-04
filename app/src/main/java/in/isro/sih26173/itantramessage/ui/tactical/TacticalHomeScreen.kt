package `in`.isro.sih26173.itantramessage.ui.tactical

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.isro.sih26173.itantramessage.R
import `in`.isro.sih26173.itantramessage.data.nearby.NearbyDevice
import `in`.isro.sih26173.itantramessage.data.nearby.Transport
import `in`.isro.sih26173.itantramessage.ui.home.HomeUiState

@Composable
fun TacticalHomeScreen(
    state: HomeUiState,
    onSearch: () -> Unit,
    onStopSearch: () -> Unit,
    onSelectDevice: (NearbyDevice) -> Unit,
    onSelectTransport: (Transport) -> Unit,
    onRetry: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = Color.Black
    val accent = Color(0xFF00FF66)
    val warn = Color(0xFFFFD93B)
    val surface = Color(0xFF0B0B0B)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(bg)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "iTANTRA • TACTICAL",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = accent
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(accent, CircleShape)
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    "OFFLINE NET",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent
                )
            }
        }

        if (state.queuedCount > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = surface),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(warn, warn.copy(alpha = 0.5f))
                    )
                )
            ) {
                Text(
                    text = if (state.queuedCount == 1) "${state.queuedCount} MSG QUEUED" else "${state.queuedCount} MSGS QUEUED",
                    color = warn,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.transport == Transport.RFCOMM,
                onClick = { onSelectTransport(Transport.RFCOMM) },
                enabled = !state.isBusy,
                label = { Text("RFCOMM", color = if (state.transport == Transport.RFCOMM) bg else accent) }
            )
            FilterChip(
                selected = state.transport == Transport.GATT,
                onClick = { onSelectTransport(Transport.GATT) },
                enabled = !state.isBusy,
                label = { Text("GATT", color = if (state.transport == Transport.GATT) bg else accent) }
            )
        }

        Button(
            onClick = if (state.isScanning) onStopSearch else onSearch,
            enabled = !state.isConnecting && state.blocker == null,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = bg),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (state.isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = bg, strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("SCANNING...", fontWeight = FontWeight.Bold)
            } else {
                Text("SEARCH NEARBY", fontWeight = FontWeight.Bold)
            }
        }

        Text("NEARBY NODES", color = accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)

        if (state.devices.isEmpty() && !state.isScanning) {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                Text("NO NODES DETECTED", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            items(state.devices, key = { it.address }) { d ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
                    colors = CardDefaults.cardColors(containerColor = surface),
                    onClick = { if (!state.isConnecting) onSelectDevice(d) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(d.displayName, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(d.subtitle, color = Color.Gray, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("READY", color = accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = surface.copy(alpha = 0.9f))
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TelemetryRow("STT Latency", "— ms")
                TelemetryRow("TTS Latency", "— ms")
                TelemetryRow("Wire Transfer", "— ms")
                TelemetryRow("E2E Total", "— ms")
                TelemetryRow("RTF", "—")
                TelemetryRow("RAM", "~0 MB")
                TelemetryRow("BW Saved", "— %")
            }
        }
    }
}

@Composable
private fun TelemetryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        Text(value, color = Color(0xFF4D9DE0), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}
