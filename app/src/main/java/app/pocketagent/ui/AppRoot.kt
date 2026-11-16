package app.pocketagent.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.pocketagent.Host
import app.pocketagent.LocalGraph
import app.pocketagent.LocalWide
import app.pocketagent.ui.theme.PocketTheme
import app.pocketagent.ui.theme.isDark
import kotlinx.coroutines.launch

sealed interface Screen {
    val key: String

    data class Chat(val id: Long, val text: String = "") : Screen { override val key = "chat$id" }
    data object History : Screen { override val key = "history" }
    data object Settings : Screen { override val key = "settings" }
    data object Workspace : Screen { override val key = "workspace" }
    data object Schedules : Screen { override val key = "schedules" }
    data class Doc(val kind: String) : Screen { override val key = "doc-$kind" }
}

/** The whole interface. The same code draws the Android app and the Linux and Windows apps. */
@Composable
fun AppRoot(host: Host) {
    val g = host.graph
    val s = g.settings.collectAsState().value ?: return
    // Locked from a cold start whenever app lock is on.
    val startLocked = remember { s.lock }
    var unlockedOnce by remember { mutableStateOf(false) }
    var keyTick by remember { mutableIntStateOf(0) }
    val dark = isDark(s.theme)
    LaunchedEffect(s.secureScreen) { g.platform.setSecureScreen(s.secureScreen) }
    LaunchedEffect(dark) { g.platform.systemBars(dark) }
    CompositionLocalProvider(LocalGraph provides g) {
        PocketTheme(s.theme, s.dynamicColor, s.pureBlack) {
            val ready = remember(keyTick, s.onboarded, s.sharedRev) { g.connected().isNotEmpty() }
            when {
                s.lock && g.platform.canLock && (host.locked.value || (startLocked && !unlockedOnce)) -> LockScreen { host.locked.value = false; unlockedOnce = true }
                !s.onboarded || !ready -> WelcomeScreen { keyTick++ }
                else -> Nav(host)
            }
        }
    }
}

/** One entry of the side navigation: an icon with its name under it, and a pill behind the icon when it is the current place. */
@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) cs.secondaryContainer else Color.Transparent, label = "nav")
    val grow by animateFloatAsState(if (selected) 1f else 0.86f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "navScale")
    Column(Modifier.clip(MaterialTheme.shapes.large).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(width = 56.dp, height = 32.dp).scale(grow).background(bg, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, label, Modifier.size(22.dp), tint = if (selected) cs.onSecondaryContainer else cs.onSurfaceVariant)
        }
        Text(label, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = if (selected) cs.onSurface else cs.onSurfaceVariant, maxLines = 1)
    }
}

/**
 * The frame of the app. It opens straight into a chat. History, the workspace and settings live in a side
 * navigation: a rail that is always there on a wide window, a drawer that slides in on a phone.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Nav(host: Host) {
    val g = LocalGraph.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<Screen?>(null) }
    var lastChat by remember { mutableStateOf<Screen.Chat?>(null) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var aboutOpen by rememberSaveable { mutableStateOf(false) }
    val docs = listOf(Docs.TERMS to Icons.Rounded.Gavel, Docs.PRIVACY to Icons.Rounded.PrivacyTip, Docs.LICENSES to Icons.Rounded.Code)
    val drawer = rememberDrawerState(DrawerValue.Closed)

    fun go(s: Screen) {
        if (s is Screen.Chat) lastChat = s
        // The side panels fold away by themselves once you have gone somewhere.
        aboutOpen = false
        if (s !is Screen.Chat) historyOpen = false
        current = s
    }
    val openChat: (Long) -> Unit = { id -> g.platform.clearFinishedNotice(id); go(Screen.Chat(id)) }
    val newChat: () -> Unit = { scope.launch { go(Screen.Chat(g.newChat())) } }
    fun backToChat() { lastChat?.let { go(it) } ?: newChat() }

    // Straight into a fresh chat on opening.
    LaunchedEffect(Unit) { if (current == null) go(Screen.Chat(g.newChat())) }
    LaunchedEffect(host.openChat.value) {
        val id = host.openChat.value ?: return@LaunchedEffect
        host.openChat.value = null
        if (g.dao.chat(id) != null) openChat(id)
    }
    LaunchedEffect(host.sharedText.value) {
        val text = host.sharedText.value ?: return@LaunchedEffect
        host.sharedText.value = null
        go(Screen.Chat(g.newChat(), text))
    }

    var railOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    val screen = current ?: return

    BoxWithConstraints(Modifier.fillMaxSize().background(cs.surfaceContainerLowest)) {
        AppBackdrop(Modifier.fillMaxSize())
        val total = maxWidth
        val wide = total >= 840.dp
        CompositionLocalProvider(LocalWide provides wide) {
            if (wide) {
                OnBack(screen !is Screen.Chat) { backToChat() }
                Row(Modifier.fillMaxSize()) {
                    // The rail.
                    // Tapping the app's mark folds the rail away to just the mark, and opens it again.
                    val railWidth by androidx.compose.animation.core.animateDpAsState(if (railOpen) 84.dp else 68.dp, spring(dampingRatio = 0.85f, stiffness = 380f), label = "rail")
                    Column(Modifier.width(railWidth).fillMaxHeight().statusBarsPadding().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.padding(bottom = 16.dp).size(44.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(cs.primaryContainer)
                                .clickable(onClickLabel = if (railOpen) "Collapse the side menu" else "Open the side menu") { railOpen = !railOpen; if (!railOpen) { historyOpen = false; aboutOpen = false } },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, if (railOpen) "Collapse the side menu" else "Open the side menu", Modifier.size(20.dp), tint = cs.onPrimaryContainer)
                        }
                        AnimatedVisibility(railOpen, Modifier.weight(1f), enter = fadeIn() + androidx.compose.animation.expandVertically(spring(dampingRatio = 0.85f, stiffness = 380f)), exit = fadeOut() + androidx.compose.animation.shrinkVertically(spring(stiffness = 500f))) {
                            Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                NavItem(Icons.Rounded.History, "History", historyOpen) { historyOpen = !historyOpen; aboutOpen = false }
                                NavItem(Icons.Rounded.FolderOpen, "Files", screen == Screen.Workspace) { if (screen == Screen.Workspace) backToChat() else go(Screen.Workspace) }
                                NavItem(Icons.Rounded.Schedule, "Tasks", screen == Screen.Schedules) { if (screen == Screen.Schedules) backToChat() else go(Screen.Schedules) }
                                Spacer(Modifier.weight(1f))
                                NavItem(Icons.Rounded.Info, "About", aboutOpen || screen is Screen.Doc) { aboutOpen = !aboutOpen; if (aboutOpen) historyOpen = false }
                                NavItem(Icons.Rounded.Settings, "Settings", screen == Screen.Settings) { if (screen == Screen.Settings) backToChat() else go(Screen.Settings) }
                                Text("v" + g.platform.versionName, style = MaterialTheme.typography.labelSmall, color = cs.outline)
                            }
                        }
                    }
                    AnimatedVisibility(historyOpen, enter = expandHorizontally(spring(dampingRatio = 0.85f, stiffness = 380f)) + fadeIn(), exit = shrinkHorizontally(spring(stiffness = 500f)) + fadeOut()) {
                        Box(Modifier.width(if (total >= 1280.dp) 360.dp else 300.dp).fillMaxHeight()) {
                            HistoryScreen(onOpen = openChat, onMenu = null, onNew = newChat, selected = (screen as? Screen.Chat)?.id)
                        }
                    }
                    AnimatedVisibility(aboutOpen, enter = expandHorizontally(spring(dampingRatio = 0.85f, stiffness = 380f)) + fadeIn(), exit = shrinkHorizontally(spring(stiffness = 500f)) + fadeOut()) {
                        Column(Modifier.width(260.dp).fillMaxHeight().statusBarsPadding().padding(top = 18.dp, end = 10.dp)) {
                            Text("About", Modifier.padding(start = 16.dp, bottom = 10.dp), style = MaterialTheme.typography.titleLarge)
                            docs.forEach { (kind, icon) ->
                                NavigationDrawerItem({ Text(Docs.title(kind)) }, (screen as? Screen.Doc)?.kind == kind, { go(Screen.Doc(kind)) }, Modifier.padding(vertical = 2.dp), icon = { Icon(icon, null) })
                            }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().padding(top = 10.dp, end = 10.dp, bottom = 10.dp).clip(RoundedCornerShape(28.dp)).background(cs.surface)) {
                        AnimatedContent(
                            targetState = screen, contentKey = { it.key }, modifier = Modifier.fillMaxSize(),
                            transitionSpec = { (fadeIn(spring(stiffness = 500f)) + scaleIn(spring(dampingRatio = 0.86f, stiffness = 420f), initialScale = 0.98f)) togetherWith fadeOut(spring(stiffness = 700f)) },
                            label = "detail",
                        ) { s ->
                            when (s) {
                                is Screen.Chat -> ChatScreen(s.id, s.text, onMenu = null, onNew = newChat, onWorkspace = { go(Screen.Workspace) }, onDeleted = newChat, onOpenChat = openChat)
                                Screen.Settings -> SettingsScreen(onBack = ::backToChat)
                                Screen.Workspace -> WorkspaceScreen(onBack = ::backToChat)
                                Screen.Schedules -> SchedulesScreen(onBack = ::backToChat, onOpenChat = openChat)
                                Screen.History -> HistoryScreen(onOpen = openChat, onMenu = null, onNew = newChat, selected = null)
                                is Screen.Doc -> DocScreen(s.kind, onBack = ::backToChat)
                            }
                        }
                    }
                }
            } else {
                val openDrawer: () -> Unit = { scope.launch { drawer.open() } }
                fun pick(s: Screen) { scope.launch { drawer.close() }; go(s) }
                // The back gesture drags the screen with the finger before it commits.
                val drag = remember { Animatable(0f) }
                OnBackDrag(screen !is Screen.Chat && !drawer.isOpen, drag) { backToChat() }
                OnBack(drawer.isOpen) { scope.launch { drawer.close() } }
                ModalNavigationDrawer(
                    drawerState = drawer,
                    drawerContent = {
                        ModalDrawerSheet(Modifier.width(300.dp), drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)) {
                          Box {
                            AppBackdrop(Modifier.matchParentSize())
                            Column(Modifier.fillMaxSize()) {
                            Row(Modifier.padding(start = 24.dp, top = 22.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(40.dp).background(cs.primaryContainer, MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp), tint = cs.onPrimaryContainer)
                                }
                                Text("Pocket Agent", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleLarge)
                            }
                            val item = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                            NavigationDrawerItem({ Text("New chat") }, false, { scope.launch { drawer.close() }; newChat() }, item, icon = { Icon(Icons.Rounded.Add, null) })
                            NavigationDrawerItem({ Text("History") }, screen == Screen.History, { pick(Screen.History) }, item, icon = { Icon(Icons.Rounded.History, null) })
                            NavigationDrawerItem({ Text("Workspace files") }, screen == Screen.Workspace, { pick(Screen.Workspace) }, item, icon = { Icon(Icons.Rounded.FolderOpen, null) })
                            NavigationDrawerItem({ Text("Scheduled tasks") }, screen == Screen.Schedules, { pick(Screen.Schedules) }, item, icon = { Icon(Icons.Rounded.Schedule, null) })
                            NavigationDrawerItem({ Text("Settings") }, screen == Screen.Settings, { pick(Screen.Settings) }, item, icon = { Icon(Icons.Rounded.Settings, null) })
                            // Terms, privacy and licences sit behind one line that opens downward.
                            val turn by animateFloatAsState(if (aboutOpen) 180f else 0f, spring(dampingRatio = 0.7f, stiffness = 400f), label = "about")
                            NavigationDrawerItem({ Text("About") }, false, { aboutOpen = !aboutOpen }, item, icon = { Icon(Icons.Rounded.Info, null) }, badge = { Icon(Icons.Rounded.ExpandMore, if (aboutOpen) "Close" else "Open", Modifier.rotate(turn)) })
                            AnimatedVisibility(aboutOpen, enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = 380f)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                                Column {
                                    docs.forEach { (kind, icon) ->
                                        NavigationDrawerItem({ Text(Docs.title(kind)) }, (screen as? Screen.Doc)?.kind == kind, { pick(Screen.Doc(kind)) }, Modifier.padding(start = 28.dp, end = 12.dp, top = 2.dp, bottom = 2.dp), icon = { Icon(icon, null) })
                                    }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Text("Pocket Agent " + g.platform.versionName, Modifier.padding(start = 28.dp, bottom = 20.dp, top = 12.dp), style = MaterialTheme.typography.labelMedium, color = cs.outline)
                            }
                          }
                        }
                    },
                ) {
                    AnimatedContent(
                        targetState = screen,
                        contentKey = { it.key },
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            val p = drag.value
                            scaleX = 1f - 0.08f * p
                            scaleY = 1f - 0.08f * p
                            translationX = p * 48.dp.toPx()
                        }.clip(RoundedCornerShape((28 * drag.value).dp)),
                        transitionSpec = {
                            val move = spring<IntOffset>(dampingRatio = 0.86f, stiffness = 420f)
                            val grow = spring<Float>(dampingRatio = 0.86f, stiffness = 420f)
                            if (targetState !is Screen.Chat) (slideInHorizontally(move) { it / 4 } + fadeIn(spring(stiffness = 500f)) + scaleIn(grow, initialScale = 0.96f)) togetherWith
                                (fadeOut(spring(stiffness = 700f)) + scaleOut(grow, targetScale = 0.97f))
                            else (fadeIn(spring(stiffness = 500f)) + scaleIn(grow, initialScale = 0.97f)) togetherWith
                                (slideOutHorizontally(move) { it / 4 } + fadeOut(spring(stiffness = 700f)) + scaleOut(grow, targetScale = 0.96f))
                        },
                        label = "nav",
                    ) { s ->
                        when (s) {
                            is Screen.Chat -> ChatScreen(s.id, s.text, onMenu = openDrawer, onNew = newChat, onWorkspace = { go(Screen.Workspace) }, onDeleted = newChat, onOpenChat = openChat)
                            Screen.History -> HistoryScreen(onOpen = openChat, onMenu = openDrawer, onNew = newChat, selected = lastChat?.id)
                            Screen.Settings -> SettingsScreen(onBack = ::backToChat)
                            Screen.Workspace -> WorkspaceScreen(onBack = ::backToChat)
                            Screen.Schedules -> SchedulesScreen(onBack = ::backToChat, onOpenChat = openChat)
                            is Screen.Doc -> DocScreen(s.kind, onBack = ::backToChat)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    val g = LocalGraph.current
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { g.platform.authenticate("Unlock Pocket Agent", onUnlock) }
    Column(Modifier.fillMaxSize().background(cs.surface), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(96.dp).background(cs.primaryContainer, MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Lock, null, Modifier.size(36.dp), tint = cs.onPrimaryContainer)
        }
        Text("Pocket Agent is locked", Modifier.padding(top = 22.dp), style = MaterialTheme.typography.titleLarge)
        Button({ g.platform.authenticate("Unlock Pocket Agent", onUnlock) }, Modifier.padding(top = 18.dp)) { Text("Unlock") }
    }
}
