package app.pocketagent.ui

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.pocketagent.LocalGraph
import app.pocketagent.ai.Provider
import app.pocketagent.ai.Providers
import app.pocketagent.ui.theme.LocalMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val ROLES = listOf("Student", "Developer", "Designer", "Writer", "Founder", "Researcher", "Teacher", "Other")
val STYLES = listOf("short" to "Short", "balanced" to "Balanced", "detailed" to "Detailed")

/**
 * A row of rounded choices; the chosen one fills with colour. When there are more than fit, the row scrolls; on a
 * computer, where there is no finger to drag it, an arrow at each end moves it along.
 */
@Composable
fun Choices(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val arrows = LocalGraph.current.platform.isDesktop && scroll.maxValue > 0
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        @Composable
        fun arrow(back: Boolean) {
            val can = if (back) scroll.canScrollBackward else scroll.canScrollForward
            // A bare arrow, with no ground of its own.
            IconButton(
                { scope.launch { scroll.animateScrollBy(if (back) -260f else 260f, spring(dampingRatio = 0.9f, stiffness = 380f)) } },
                Modifier.size(34.dp), enabled = can,
            ) {
                Icon(if (back) Icons.AutoMirrored.Rounded.KeyboardArrowLeft else Icons.AutoMirrored.Rounded.KeyboardArrowRight, if (back) "Show earlier choices" else "Show more choices", Modifier.size(20.dp), tint = cs.onSurface.copy(alpha = if (can) 1f else 0.3f))
            }
        }
        if (arrows) arrow(back = true)
        Row(Modifier.weight(1f, fill = false).horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                val on = value == selected
                val bg by animateColorAsState(if (on) cs.primary else cs.surfaceContainerHigh, label = "choice")
                val fg by animateColorAsState(if (on) cs.onPrimary else cs.onSurface, label = "choiceText")
                val size by animateFloatAsState(if (on) 1.04f else 1f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "choiceScale")
                Surface(onClick = { onSelect(value) }, shape = CircleShape, color = bg, contentColor = fg, modifier = Modifier.scale(size)) {
                    Text(label, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
        if (arrows) arrow(back = false)
    }
}

/** Picks a service and takes its key. Used on the first screen and when adding another service later. */
@Composable
fun ProviderKeyFields(provider: Provider, onProvider: (Provider) -> Unit, key: String, onKey: (String) -> Unit, error: String?, enabled: Boolean, onDone: () -> Unit, exclude: Set<String> = emptySet()) {
    val uri = LocalUriHandler.current
    var show by remember { mutableStateOf(false) }
    var customName by remember { mutableStateOf("") }
    var customUrl by remember { mutableStateOf("") }
    var ollamaUrl by remember { mutableStateOf(if (provider.local) provider.baseUrl else Providers.OLLAMA_URL) }
    val isLocal = provider.local
    val isCustom = provider.custom && !isLocal
    Choices(
        Providers.PRESETS.filter { it.id !in exclude }.map { it.id to it.name } + listOfNotNull((Providers.OLLAMA to "Ollama").takeIf { Providers.OLLAMA !in exclude }) + ("custom" to "Custom"),
        if (isLocal) Providers.OLLAMA else if (isCustom) "custom" else provider.id,
        { id ->
            onProvider(when (id) {
                "custom" -> Providers.custom(customName, customUrl)
                Providers.OLLAMA -> Providers.ollama(ollamaUrl)
                else -> Providers.PRESETS.first { it.id == id }
            })
        },
    )
    // Ollama runs on the person's own computer: all it needs is where to find it.
    AnimatedVisibility(isLocal) {
        OutlinedTextField(
            ollamaUrl, { ollamaUrl = it.trim(); onProvider(Providers.ollama(ollamaUrl)) }, Modifier.fillMaxWidth().padding(top = 12.dp),
            label = { Text("Ollama address") }, placeholder = { Text(Providers.OLLAMA_URL) }, singleLine = true, enabled = enabled, shape = MaterialTheme.shapes.large,
            supportingText = { Text(if (LocalGraph.current.platform.isDesktop) "Where Ollama listens. On this computer that is the address already filled in." else "Ollama runs on a computer. Enter that computer's address on your Wi-Fi, for example http://192.168.1.20:11434/v1, and start Ollama there with OLLAMA_HOST=0.0.0.0 so the phone can reach it.") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        )
    }
    AnimatedVisibility(isCustom) {
        Column {
            OutlinedTextField(
                customName, { customName = it.take(30); onProvider(Providers.custom(customName, customUrl)) }, Modifier.fillMaxWidth().padding(top = 12.dp),
                label = { Text("Name") }, singleLine = true, enabled = enabled, shape = MaterialTheme.shapes.large,
            )
            OutlinedTextField(
                customUrl, { customUrl = it.trim(); onProvider(Providers.custom(customName, customUrl)) }, Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("Address") }, placeholder = { Text("https://example.com/v1") }, singleLine = true, enabled = enabled, shape = MaterialTheme.shapes.large,
                supportingText = { Text("Any service that speaks the OpenAI-compatible format.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            )
        }
    }
    OutlinedTextField(
        value = key, onValueChange = { onKey(it.trim()) },
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        label = { Text(if (isLocal) "API key (leave empty)" else if (isCustom) "API key" else "${provider.name} API key") },
        placeholder = { if (isLocal) Text("Not needed for Ollama on your own computer") else if (provider.keyHint.isNotEmpty()) Text(provider.keyHint) },
        singleLine = true, enabled = enabled, isError = error != null,
        supportingText = { if (error != null) Text(error) },
        trailingIcon = { IconButton({ show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (show) "Hide key" else "Show key") } },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        shape = MaterialTheme.shapes.large,
    )
    if (provider.keyUrl.isNotEmpty()) TextButton({ runCatching { uri.openUri(provider.keyUrl) } }) { Text(if (isLocal) "Get Ollama" else "Get a ${provider.name} key") }
}

@Composable
private fun Section(index: Int, title: String, content: @Composable ColumnScope.() -> Unit) {
    val motion = LocalMotion.current
    var shown by remember { mutableStateOf(!motion) }
    LaunchedEffect(Unit) { delay(120L + index * 110L); shown = true }
    AnimatedVisibility(shown, enter = fadeIn(tween(350)) + slideInVertically(spring(dampingRatio = 0.75f, stiffness = 300f)) { it / 3 }) {
        if (title.isEmpty()) Column(Modifier.fillMaxWidth().padding(top = 18.dp), content = content)
        else Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
            Column(Modifier.padding(18.dp)) {
                Text(title, Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                content()
            }
        }
    }
}

/** The first screen: who you are, and the key for the AI you want to use. One page, nothing else. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val g = LocalGraph.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var name by remember { mutableStateOf(g.settings.value?.name.orEmpty()) }
    var role by remember { mutableStateOf(g.settings.value?.role.orEmpty()) }
    var city by remember { mutableStateOf(g.settings.value?.city.orEmpty()) }
    var style by remember { mutableStateOf(g.settings.value?.style ?: "balanced") }
    var provider by remember { mutableStateOf(Providers.PRESETS.first()) }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var ok by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var linking by remember { mutableStateOf(false) }
    var beat by remember { mutableIntStateOf(0) }

    fun start() {
        if (busy || ok || (key.isBlank() && !provider.local)) return
        if (provider.custom && !provider.baseUrl.startsWith("http")) { error = "Enter the service's address first."; return }
        busy = true; error = null
        scope.launch {
            error = g.connect(provider, key)
            busy = false
            if (error == null) {
                ok = true; beat++
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                g.updateShared { it.copy(name = name.trim(), role = role, style = style, city = city.trim()) }
                delay(650)
                g.settingsStore.update { it.copy(onboarded = true) }
                onDone()
            } else haptic.performHapticFeedback(HapticFeedbackType.Reject)
        }
    }

    val spin = if (LocalMotion.current) {
        val t = rememberInfiniteTransition(label = "hero")
        val v by t.animateFloat(0f, 360f, infiniteRepeatable(tween(26_000, easing = LinearEasing)), label = "hero")
        v
    } else 0f
    val pop by animateFloatAsState(if (ok) 1.15f else 1f, spring(dampingRatio = 0.35f, stiffness = 300f), label = "pop")

    Box(Modifier.fillMaxSize().background(cs.surface), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.size(112.dp).scale(pop), contentAlignment = Alignment.Center) {
                Box(Modifier.size(112.dp).rotate(spin).background(cs.primaryContainer, MaterialShapes.Cookie12Sided.toShape()))
                Box(Modifier.size(70.dp).rotate(-spin * 1.6f).background(cs.tertiaryContainer, MaterialShapes.SoftBurst.toShape()))
                AnimatedContent(ok, transitionSpec = { (scaleIn(spring(dampingRatio = 0.45f, stiffness = 400f)) + fadeIn()) togetherWith (scaleOut() + fadeOut()) }, label = "ok") { done ->
                    Icon(if (done) Icons.Rounded.Check else Icons.Rounded.AutoAwesome, null, Modifier.size(34.dp), tint = cs.onTertiaryContainer)
                }
            }
            Text("My PAI", Modifier.padding(top = 18.dp), style = MaterialTheme.typography.displaySmall)

            Section(0, "About you") {
                OutlinedTextField(
                    name, { name = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("Your name") }, singleLine = true, enabled = !busy && !ok, shape = MaterialTheme.shapes.large,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    city, { city = it.take(60) }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("City (optional)") }, singleLine = true, enabled = !busy && !ok, shape = MaterialTheme.shapes.large,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                )
                Text("I am a", Modifier.padding(top = 14.dp, bottom = 8.dp), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                Choices(ROLES.map { it to it }, role, { role = if (role == it) "" else it })
                Text("I like answers", Modifier.padding(top = 14.dp, bottom = 8.dp), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                Segmented(STYLES, style, { style = it }, Modifier.fillMaxWidth())
            }

            Section(1, "Your AI") {
                ProviderKeyFields(provider, { provider = it; error = null }, key, { key = it; error = null }, error, !busy && !ok, ::start)
            }

            Section(2, "") {
                Button(::start, Modifier.fillMaxWidth().height(56.dp), enabled = (key.isNotBlank() || provider.local) && !busy && !ok, shape = MaterialTheme.shapes.large) {
                    AnimatedContent(if (ok) 2 else if (busy) 1 else 0, label = "btn") { st ->
                        when (st) {
                            1 -> LoadingIndicator(Modifier.size(32.dp), color = cs.onPrimary)
                            2 -> Text("You're in")
                            else -> Text("Get started")
                        }
                    }
                }
                TextButton({ linking = true }, Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp), enabled = !busy && !ok) { Text("I already use it on another device") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (linking) JoinSyncDialog(onDismiss = { linking = false }, onJoined = { linking = false; onDone() })
}
