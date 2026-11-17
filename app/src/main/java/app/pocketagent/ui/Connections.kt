package app.pocketagent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.pocketagent.LocalGraph
import app.pocketagent.ai.Integrations
import app.pocketagent.data.ToolError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Connections part of Settings: accounts the agent can work with. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectionsSection() {
    val g = LocalGraph.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val settings by g.settings.collectAsState()
    // Re-read after every change (the secrets themselves live in the vault, not in settings).
    val githubOn = remember(settings?.sharedRev) { g.integrations.githubOn() }
    val gmailOn = remember(settings?.sharedRev) { g.integrations.gmailOn() }

    Text("Type @github, @gmail or @mcp in any chat, or just ask. Nothing is sent or changed without your OK.", Modifier.padding(start = 4.dp, top = 8.dp, bottom = 12.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)

    // GitHub
    var token by remember { mutableStateOf("") }
    var ghBusy by remember { mutableStateOf(false) }
    var ghNote by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("GitHub", style = MaterialTheme.typography.titleMedium)
        if (githubOn) Icon(Icons.Rounded.CheckCircle, "Connected", Modifier.padding(start = 8.dp).size(18.dp), tint = cs.primary)
    }
    if (githubOn) {
        Text("Connected. It reads your repositories, and changes them only with your OK.", Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        OutlinedButton({ scope.launch { g.setConnection(mapOf(Integrations.GITHUB to null)); ghNote = null } }) { Text("Disconnect GitHub") }
    } else {
        OutlinedTextField(
            token, { token = it.trim(); ghNote = null }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Personal access token") }, placeholder = { Text("github_pat_… or ghp_…") }, singleLine = true, enabled = !ghBusy,
            isError = ghNote?.second == true, supportingText = { Text(ghNote?.first ?: "Give the token only the access you want it to have.") },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), shape = MaterialTheme.shapes.large,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton({
                ghBusy = true
                scope.launch {
                    ghNote = try {
                        val login = withContext(Dispatchers.IO) { g.integrations.checkGithub(token) }
                        g.setConnection(mapOf(Integrations.GITHUB to token)); token = ""
                        "Connected as $login." to false
                    } catch (e: ToolError) { (e.message ?: "Could not connect.") to true } catch (e: Exception) { "Could not reach GitHub (${e.message ?: e.javaClass.simpleName})." to true }
                    ghBusy = false
                }
            }, enabled = token.isNotBlank() && !ghBusy) { if (ghBusy) LoadingIndicator(Modifier.size(22.dp)) else Text("Connect") }
            TextButton({ runCatching { uri.openUri("https://github.com/settings/personal-access-tokens/new") } }) { Text("Create a token") }
        }
    }

    HorizontalDivider(Modifier.padding(vertical = 14.dp), color = cs.outlineVariant.copy(alpha = 0.5f))

    // Gmail
    var address by remember { mutableStateOf("") }
    var appPass by remember { mutableStateOf("") }
    var gmBusy by remember { mutableStateOf(false) }
    var gmNote by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Gmail", style = MaterialTheme.typography.titleMedium)
        if (gmailOn) Icon(Icons.Rounded.CheckCircle, "Connected", Modifier.padding(start = 8.dp).size(18.dp), tint = cs.primary)
    }
    if (gmailOn) {
        Text("Connected as ${g.vault.read(Integrations.GMAIL_USER).orEmpty()}. It reads your mail, and sends only with your OK.", Modifier.padding(start = 4.dp, top = 4.dp, bottom = 6.dp), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        OutlinedButton({ scope.launch { g.setConnection(mapOf(Integrations.GMAIL_USER to null, Integrations.GMAIL_PASS to null)); gmNote = null } }, Modifier.padding(bottom = 6.dp)) { Text("Disconnect Gmail") }
    } else {
        OutlinedTextField(
            address, { address = it.trim(); gmNote = null }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Gmail address") }, singleLine = true, enabled = !gmBusy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false), shape = MaterialTheme.shapes.large,
        )
        OutlinedTextField(
            appPass, { appPass = it; gmNote = null }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("App password") }, placeholder = { Text("16 letters") }, singleLine = true, enabled = !gmBusy,
            isError = gmNote?.second == true, supportingText = { Text(gmNote?.first ?: "A Google app password, not your normal one. Needs 2-step verification.") },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), shape = MaterialTheme.shapes.large,
        )
        Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton({
                gmBusy = true
                scope.launch {
                    gmNote = try {
                        val pass = appPass.replace(" ", "")
                        withContext(Dispatchers.IO) { g.integrations.checkGmail(address, pass) }
                        g.setConnection(mapOf(Integrations.GMAIL_USER to address, Integrations.GMAIL_PASS to pass)); appPass = ""
                        "Connected." to false
                    } catch (e: ToolError) { (e.message ?: "Could not connect.") to true } catch (e: Exception) { "Could not reach Gmail (${e.message ?: e.javaClass.simpleName})." to true }
                    gmBusy = false
                }
            }, enabled = address.contains('@') && appPass.isNotBlank() && !gmBusy) { if (gmBusy) LoadingIndicator(Modifier.size(22.dp)) else Text("Connect") }
            TextButton({ runCatching { uri.openUri("https://myaccount.google.com/apppasswords") } }) { Text("Create an app password") }
        }
    }

    McpSection()
}
