package `in`.isro.sih26173.itantramessage.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import `in`.isro.sih26173.itantramessage.R
import `in`.isro.sih26173.itantramessage.domain.speech.Language
import `in`.isro.sih26173.itantramessage.ui.components.LanguagePicker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    deviceId: String,
    deviceName: String,
    inputLanguage: Language,
    outputLanguage: Language,
    onInputLanguageChange: (Language) -> Unit,
    onOutputLanguageChange: (Language) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Back" },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            SettingsSection(title = stringResource(R.string.settings_device))
            SettingsItem(label = stringResource(R.string.settings_device_name), value = deviceName)
            SettingsItem(label = stringResource(R.string.settings_device_id), value = deviceId)

            Spacer(Modifier.height(24.dp))

            SettingsSection(title = stringResource(R.string.settings_language))
            LanguagePicker(
                label = stringResource(R.string.settings_input_language),
                selected = inputLanguage,
                onSelected = onInputLanguageChange,
            )
            Spacer(Modifier.height(12.dp))
            LanguagePicker(
                label = stringResource(R.string.settings_output_language),
                selected = outputLanguage,
                onSelected = onOutputLanguageChange,
            )

            Spacer(Modifier.height(24.dp))

            SettingsSection(title = stringResource(R.string.settings_encryption))
            SettingsItem(label = stringResource(R.string.settings_encryption), value = "AES-GCM + RSA transport (offline)")

            Spacer(Modifier.height(24.dp))

            SettingsSection(title = stringResource(R.string.settings_about))
            SettingsItem(label = stringResource(R.string.app_name), value = "Offline-first P2P messaging")
        }
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SettingsItem(label: String, value: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(4.dp))
}
