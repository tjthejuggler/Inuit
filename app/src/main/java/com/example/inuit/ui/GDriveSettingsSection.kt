package com.example.inuit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.unit.dp
import com.example.inuit.data.AppSettings
import com.example.inuit.data.gdrive.GDriveClient
import com.example.inuit.ui.theme.LogErr
import com.example.inuit.ui.theme.LogOk

/**
 * Settings → Google Drive documents: the Gemini-chat-export source.
 *
 * The user pastes three OAuth values (client ID, client secret, refresh
 * token) from their own Google Cloud project — full step-by-step
 * instructions are rendered inline, since this is the one setup step that
 * cannot be guessed.
 */
@Composable
fun GDriveSettingsSection(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val gdriveState by viewModel.gdriveState.collectAsStateWithLifecycle()

    // Local editable state, re-seeded whenever persisted settings change.
    var clientId by rememberSaveable(settings.gdriveClientId) { mutableStateOf(settings.gdriveClientId) }
    var clientSecret by rememberSaveable(settings.gdriveClientSecret) { mutableStateOf(settings.gdriveClientSecret) }
    var refreshToken by rememberSaveable(settings.gdriveRefreshToken) { mutableStateOf(settings.gdriveRefreshToken) }
    var showSecrets by remember { mutableStateOf(false) }

    SectionCard(
        title = "Google Drive documents",
        subtitle = "Gemini chat exports in Drive root become question material"
    ) {
        Text(
            "Inuit scans the TOP LEVEL of your Google Drive. Documents that look like " +
                "Gemini chat exports (\"Conversation with Gemini …\") are read, their key " +
                "facts distilled into question material, and the document is then moved to " +
                "Drive trash. Any OTHER document in root is left alone but moved to an " +
                "\"${GDriveClient.ARCHIVE_FOLDER_NAME}\" folder to keep root tidy. " +
                "Documents inside folders are never touched.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            label = { Text("OAuth client ID") },
            placeholder = { Text("1234-abc.apps.googleusercontent.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = clientSecret,
            onValueChange = { clientSecret = it },
            label = { Text("OAuth client secret") },
            singleLine = true,
            visualTransformation = if (showSecrets) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = refreshToken,
            onValueChange = { refreshToken = it },
            label = { Text("OAuth refresh token") },
            singleLine = true,
            visualTransformation = if (showSecrets) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        TextButton(onClick = { showSecrets = !showSecrets }) {
            Text(if (showSecrets) "Hide secrets" else "Show secrets")
        }

        Spacer(Modifier.height(4.dp))
        Text(
            GDriveClient.SETUP_HELP,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))
        val configured = settings.gdriveConfigured
        Text(
            if (configured) "Status: configured ✓ — sync runs at app start; new exports are picked up then."
            else "Status: not configured — the Drive document source is off.",
            style = MaterialTheme.typography.labelSmall,
            color = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { viewModel.saveGdriveCredentials(clientId, clientSecret, refreshToken) }) {
                Text("Save")
            }
            OutlinedButton(
                onClick = { viewModel.testGdrive(clientId, clientSecret, refreshToken) },
                enabled = !gdriveState.isTesting
            ) {
                Text("Test")
            }
            OutlinedButton(
                onClick = { viewModel.syncGdriveNow() },
                enabled = configured && !gdriveState.isSyncing
            ) {
                Text("Sync now")
            }
            if (gdriveState.isTesting || gdriveState.isSyncing) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(16.dp).width(16.dp))
            }
        }

        gdriveState.testMessage?.let { msg ->
            Spacer(Modifier.height(6.dp))
            Text(
                (if (gdriveState.testOk) "✓ " else "✗ ") + msg,
                style = MaterialTheme.typography.bodySmall,
                color = if (gdriveState.testOk) LogOk else LogErr,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
        gdriveState.syncMessage?.let { msg ->
            Spacer(Modifier.height(6.dp))
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Fact-pool stats: how much Drive material is already available.
        val factCount = remember { viewModel.gdriveFactCount() }
        if (factCount > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Distilled documents in the pool: $factCount",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
