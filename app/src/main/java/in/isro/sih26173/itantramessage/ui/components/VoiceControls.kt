package `in`.isro.sih26173.itantramessage.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun PushToTalkButton(
    capturing: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (capturing) "Listening..." else "Push to Talk",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = if (capturing) onRelease else onPress,
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (capturing) Color(0xFFB71C1C) else MaterialTheme.colorScheme.primary,
            ),
            content = {
                Icon(
                    imageVector = if (capturing) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = "Push to Talk",
                    modifier = Modifier.size(40.dp),
                )
            },
        )
        // Note: hold-to-talk requires interaction detection; kept simple for now.
    }
}

@Composable
fun VoiceStatusBar(
    capturing: Boolean,
    speaking: Boolean,
    inputLang: String,
    outputLang: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("In: $inputLang  Out: $outputLang", style = MaterialTheme.typography.bodySmall)
        Text(
            text = when {
                speaking -> "Speaking..."
                capturing -> "Listening..."
                else -> "Idle"
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
