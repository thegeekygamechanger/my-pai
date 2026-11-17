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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.pocketagent.Host
import app.pocketagent.LocalGraph
import app.pocketagent.LocalWide
import app.pocketagent.ui.theme.LocalMotion
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
    // The settings take a moment to load. Until they have, and for a beat after, the opening screen is all there is.
    val s = g.settings.collectAsState().value
    val first = remember { app.pocketagent.data.Settings() }
    CompositionLocalProvider(LocalGraph provides g) {
        PocketTheme((s ?: first).theme, (s ?: first).dynamicColor, (s ?: first).pureBlack) {
            Box(Modifier.fillMaxSize()) {
                if (s != null) AppBody(host, s)
                // A question from another program on this computer is asked over whatever screen is open.
                if (s != null && s.serve) ServeAskDialog()
                Opening(loaded = s != null)
            }
        }
    }
}

/** The app once its settings are known: the lock, the first-run screen, or the app itself. */
@Composable
private fun AppBody(host: Host, s: app.pocketagent.data.Settings) {
    val g = host.graph
    // Locked from a cold start whenever app lock is on.
    val startLocked = remember { s.lock }
    var unlockedOnce by remember { mutableStateOf(false) }
    var keyTick by remember { mutableIntStateOf(0) }
    val dark = isDark(s.theme)
    LaunchedEffect(s.secureScreen) { g.platform.setSecureScreen(s.secureScreen) }
    LaunchedEffect(dark) { g.platform.systemBars(dark) }
    val ready = remember(keyTick, s.onboarded, s.sharedRev) { g.connected().isNotEmpty() }
    when {
        s.lock && g.platform.canLock && (host.locked.value || (startLocked && !unlockedOnce)) -> LockScreen { host.locked.value = false; unlockedOnce = true }
        !s.onboarded || !ready -> WelcomeScreen { keyTick++ }
        else -> Nav(host)
    }
}

/**
 * What the app shows as it opens: its mark arriving on the app's own colours, then its name, and then the whole
 * thing lifting away from the app that has been getting ready underneath. Shown once per start, not again when a
 * phone is turned. With motion switched off it is a brief still.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Opening(loaded: Boolean) {
    val cs = MaterialTheme.colorScheme
    val motion = LocalMotion.current
    var show by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(loaded) { if (loaded && show) { kotlinx.coroutines.delay(if (motion) 1150 else 250); show = false } }
    AnimatedVisibility(
        show, enter = androidx.compose.animation.EnterTransition.None,
        exit = fadeOut(androidx.compose.animation.core.tween(420)) + scaleOut(androidx.compose.animation.core.tween(420), targetScale = 1.07f),
    ) {
        val hour = remember { java.time.LocalTime.now().hour }
        Box(
            // It covers everything and takes every touch until it has gone.
            Modifier.fillMaxSize().background(cs.surfaceContainerLowest)
                .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {},
            contentAlignment = Alignment.Center,
        ) {
            Backdrop(backdropColors(hour, null), Modifier.fillMaxSize(), speed = 7f, strength = 1f)
            val arrive = remember { Animatable(if (motion) 0f else 1f) }
            val name = remember { Animatable(if (motion) 0f else 1f) }
            val line = remember { Animatable(if (motion) 0f else 1f) }
            LaunchedEffect(Unit) { arrive.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 170f)) }
            LaunchedEffect(Unit) { kotlinx.coroutines.delay(200); name.animateTo(1f, androidx.compose.animation.core.tween(520, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
            LaunchedEffect(Unit) { kotlinx.coroutines.delay(380); line.animateTo(1f, androidx.compose.animation.core.tween(520, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // The mark turns into place as it grows; the spark on it stays upright.
                Box(
                    Modifier.size(104.dp).graphicsLayer {
                        val p = arrive.value
                        val size = 0.45f + 0.55f * p
                        scaleX = size; scaleY = size; rotationZ = (1f - p) * -120f; alpha = (p * 1.6f).coerceIn(0f, 1f)
                    }.clip(MaterialShapes.Cookie9Sided.toShape()).background(cs.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(46.dp).graphicsLayer { rotationZ = (1f - arrive.value) * 120f }, tint = cs.onPrimaryContainer)
                }
                Text(
                    "My PAI", Modifier.padding(top = 24.dp).graphicsLayer { alpha = name.value; translationY = (1f - name.value) * 16.dp.toPx() },
                    style = MaterialTheme.typography.displaySmall.copy(fontFamily = app.pocketagent.ui.theme.Heros, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = cs.onSurface,
                )
                Text(
                    "Your personal AI", Modifier.padding(top = 6.dp).graphicsLayer { alpha = line.value; translationY = (1f - line.value) * 12.dp.toPx() },
                    style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant,
                )
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
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
        // Moving on from a chat to another one ends it for good if it was incognito.
        if (s is Screen.Chat) lastChat?.id?.takeIf { it != s.id }?.let { old -> g.scope.launch { g.leftChat(old) } }
        if (s is Screen.Chat) lastChat = s
        // The side panels fold away by themselves once you have gone somewhere.
        aboutOpen = false
        if (s !is Screen.Chat) historyOpen = false
        current = s
    }
    val openChat: (Long) -> Unit = { id -> g.platform.clearFinishedNotice(id); go(Screen.Chat(id)) }
    val newChat: () -> Unit = { scope.launch { go(Screen.Chat(g.newChat())) } }
    val incognitoChat: () -> Unit = { scope.launch { go(Screen.Chat(g.newIncognitoChat())) } }
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
            // History slides up over whatever is on screen, and goes away again when a chat is picked.
            if (historyOpen) androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { historyOpen = false },
                sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(0.86f)) {
                    HistoryScreen(onOpen = { id -> historyOpen = false; openChat(id) }, onMenu = null, onNew = { historyOpen = false; newChat() }, selected = (current as? Screen.Chat)?.id, inSheet = true, onDeleted = { id -> if ((current as? Screen.Chat)?.id == id) newChat() })
                }
            }
            if (wide) {
                OnBack(screen !is Screen.Chat) { backToChat() }
                Row(Modifier.fillMaxSize()) {
                    // The rail.
                    // Tapping the app's mark folds the rail away to just the mark, and opens it again.
                    val railWidth by androidx.compose.animation.core.animateDpAsState(if (railOpen) 288.dp else 68.dp, spring(dampingRatio = 0.85f, stiffness = 380f), label = "rail")
                    Box(Modifier.width(railWidth).fillMaxHeight().clipToBounds()) {
                        if (railOpen) SideMenu(
                            screen = screen, selected = (screen as? Screen.Chat)?.id, onMark = { railOpen = false; aboutOpen = false },
                            onNew = newChat, onOpen = openChat, onGo = { s -> if (screen == s) backToChat() else go(s) }, onSearch = { historyOpen = true },
                            onDeleted = { id -> if ((current as? Screen.Chat)?.id == id) newChat() },
                            modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(288.dp).fillMaxHeight().statusBarsPadding(),
                        ) else Column(Modifier.width(68.dp).fillMaxHeight().statusBarsPadding().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.size(44.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(cs.primaryContainer).clickable(onClickLabel = "Open the side menu") { railOpen = true },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.AutoAwesome, "Open the side menu", Modifier.size(20.dp), tint = cs.onPrimaryContainer)
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
                                is Screen.Chat -> ChatScreen(s.id, s.text, onMenu = null, onNew = newChat, onWorkspace = { go(Screen.Workspace) }, onDeleted = newChat, onOpenChat = openChat, onIncognito = incognitoChat)
                                Screen.Settings -> SettingsScreen(onBack = ::backToChat, onDoc = { kind -> go(Screen.Doc(kind)) })
                                Screen.Workspace -> WorkspaceScreen(onBack = ::backToChat)
                                Screen.Schedules -> SchedulesScreen(onBack = ::backToChat, onOpenChat = openChat)
                                Screen.History -> HistoryScreen(onOpen = openChat, onMenu = null, onNew = newChat, selected = null)
                                is Screen.Doc -> DocScreen(s.kind, onBack = { go(Screen.Settings) })
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
                        // The sheet itself takes no room for the status and navigation bars, so its colours run from the very top to
                        // the very bottom; the menu's contents keep clear of the bars instead.
                        ModalDrawerSheet(Modifier.width(300.dp), drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp), windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)) {
                          Box(Modifier.fillMaxSize()) {
                            AppBackdrop(Modifier.matchParentSize())
                            SideMenu(
                                screen = screen, selected = (screen as? Screen.Chat)?.id, onMark = null,
                                onNew = { scope.launch { drawer.close() }; newChat() }, onOpen = { id -> scope.launch { drawer.close() }; openChat(id) }, onGo = { s -> pick(s) },
                                onSearch = { scope.launch { drawer.close() }; historyOpen = true },
                                onDeleted = { id -> if ((current as? Screen.Chat)?.id == id) newChat() },
                                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
                            )
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
                            is Screen.Chat -> ChatScreen(s.id, s.text, onMenu = openDrawer, onNew = newChat, onWorkspace = { go(Screen.Workspace) }, onDeleted = newChat, onOpenChat = openChat, onIncognito = incognitoChat)
                            Screen.History -> HistoryScreen(onOpen = openChat, onMenu = openDrawer, onNew = newChat, selected = lastChat?.id)
                            Screen.Settings -> SettingsScreen(onBack = ::backToChat, onDoc = { kind -> go(Screen.Doc(kind)) })
                            Screen.Workspace -> WorkspaceScreen(onBack = ::backToChat)
                            Screen.Schedules -> SchedulesScreen(onBack = ::backToChat, onOpenChat = openChat)
                            is Screen.Doc -> DocScreen(s.kind, onBack = { go(Screen.Settings) })
                        }
                    }
                }
            }
        }
    }
}

/**
 * The app's mark, alive: its scalloped ground turns slowly while the spark on it stays upright and breathes.
 * With motion switched off it simply sits still.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LivingMark(label: String?, onClick: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val motion = LocalMotion.current
    // It moves about thirty times a second, and only while the window is in front: enough to look alive, with little work.
    val inFront = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    var seconds by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(motion, inFront) {
        if (!motion || !inFront) return@LaunchedEffect
        var last = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            kotlinx.coroutines.delay(AMBIENT_FRAME_MS)
            val now = androidx.compose.runtime.withFrameNanos { it }
            seconds += (now - last) / 1e9f
            last = now
        }
    }
    val turn = (seconds * 360f / 22f) % 360f
    val breath = 0.99f + 0.13f * kotlin.math.sin(seconds * 2f * Math.PI.toFloat() / 3.8f)
    Box(
        Modifier.size(40.dp).graphicsLayer { rotationZ = turn }.clip(MaterialShapes.Cookie9Sided.toShape()).background(cs.primaryContainer)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = label, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.AutoAwesome, label, Modifier.size(18.dp).graphicsLayer { rotationZ = -turn; scaleX = breath; scaleY = breath }, tint = cs.onPrimaryContainer)
    }
}

/**
 * The side menu, the same in the drawer of a phone and in the open side panel of a computer: the places to go, then,
 * after a little air, the chats themselves, newest first and as many as there are. A long press or a right click on
 * one opens the same menu it has in History.
 * @param onMark what a tap on the app's mark does (folding the panel away on a computer); null where it does nothing
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SideMenu(
    screen: Screen, selected: Long?, onMark: (() -> Unit)?, onNew: () -> Unit, onOpen: (Long) -> Unit, onGo: (Screen) -> Unit,
    onSearch: () -> Unit, onDeleted: (Long) -> Unit, modifier: Modifier = Modifier,
) {
    val g = LocalGraph.current
    val cs = MaterialTheme.colorScheme
    val chats by remember { g.dao.chats() }.collectAsState(emptyList())
    val runs by g.allRuns.collectAsState()
    val listed = chats.filter { it.title.isNotBlank() || runs.containsKey(it.id) }
    val unread = g.settings.collectAsState().value?.unreadChats.orEmpty()
    Column(modifier) {
        Row(Modifier.padding(start = 24.dp, top = 22.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            LivingMark(if (onMark != null) "Collapse the side menu" else null, onMark)
            // The name is set in the app's Helvetica, like a wordmark.
            Text("My PAI", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.titleLarge.copy(fontFamily = app.pocketagent.ui.theme.Heros, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified), maxLines = 1)
        }
        val item = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
        NavigationDrawerItem({ Text("New chat") }, false, onNew, item, icon = { Icon(Icons.Rounded.Add, null) })
        NavigationDrawerItem({ Text("Workspace files") }, screen == Screen.Workspace, { onGo(Screen.Workspace) }, item, icon = { Icon(Icons.Rounded.FolderOpen, null) })
        NavigationDrawerItem({ Text("Scheduled tasks") }, screen == Screen.Schedules, { onGo(Screen.Schedules) }, item, icon = { Icon(Icons.Rounded.Schedule, null) })

        // History: a quiet heading with search beside it, then every chat. The list takes the room that is left and scrolls.
        Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 14.dp, top = 18.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("History", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
            androidx.compose.material3.IconButton(onSearch, Modifier.size(36.dp)) { Icon(Icons.Rounded.Search, "Search chats", Modifier.size(19.dp), tint = cs.onSurfaceVariant) }
        }
        if (listed.isEmpty()) {
            Text("Your chats will be listed here.", Modifier.padding(start = 28.dp, end = 20.dp, top = 6.dp), style = MaterialTheme.typography.bodyMedium, color = cs.outline)
            Spacer(Modifier.weight(1f))
        } else androidx.compose.foundation.lazy.LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(listed.size, key = { listed[it].id }) { n ->
                val chat = listed[n]
                var menu by remember { mutableStateOf(false) }
                val on = chat.id == selected
                Box(Modifier.animateItem(fadeInSpec = spring(stiffness = 300f), placementSpec = spring(dampingRatio = 0.8f, stiffness = 350f))) {
                    Row(
                        Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.CircleShape).background(if (on) cs.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                            .onSecondaryClick { menu = true }.combinedClickable(onLongClick = { menu = true }) { onOpen(chat.id) }
                            .padding(start = 16.dp, end = 14.dp, top = 11.dp, bottom = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val tint = if (on) cs.onSecondaryContainer else cs.onSurfaceVariant
                        if (runs.containsKey(chat.id)) androidx.compose.material3.LoadingIndicator(Modifier.size(20.dp), color = cs.primary)
                        else Icon(if (chat.agent) Icons.Rounded.AutoAwesome else Icons.Rounded.ChatBubbleOutline, null, Modifier.size(18.dp), tint = tint.copy(alpha = 0.8f))
                        Text(
                            chat.title.ifBlank { "New chat" }, Modifier.weight(1f).padding(start = 14.dp), style = MaterialTheme.typography.bodyMedium,
                            color = if (on) cs.onSecondaryContainer else cs.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        // A small dot: a reply here has not been looked at yet. It goes when the chat is opened.
                        if (chat.id in unread) Box(Modifier.padding(start = 8.dp).size(7.dp).background(cs.primary, androidx.compose.foundation.shape.CircleShape).semantics { contentDescription = "Unread" })
                        if (chat.pinned) Icon(Icons.Rounded.PushPin, "Pinned", Modifier.padding(start = 6.dp).size(14.dp), tint = cs.primary)
                    }
                    ChatMenu(chat, menu, { menu = false }, onDeleted)
                }
            }
        }

        // The foot of the menu: the version on one side, Settings on the other. Terms, privacy and licences are inside Settings.
        Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp, top = 10.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("My PAI " + g.platform.versionName, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = cs.outline, maxLines = 1)
            val inSettings = screen == Screen.Settings || screen is Screen.Doc
            androidx.compose.material3.Surface(
                onClick = { onGo(Screen.Settings) }, shape = androidx.compose.foundation.shape.CircleShape,
                color = if (inSettings) cs.secondaryContainer else cs.surface.copy(alpha = 0.55f), contentColor = if (inSettings) cs.onSecondaryContainer else cs.onSurface,
            ) {
                Row(Modifier.padding(start = 14.dp, end = 18.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Settings, null, Modifier.size(20.dp))
                    Text("Settings", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelLarge)
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
    LaunchedEffect(Unit) { g.platform.authenticate("Unlock My PAI", onUnlock) }
    Column(Modifier.fillMaxSize().background(cs.surface), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(96.dp).background(cs.primaryContainer, MaterialShapes.Cookie9Sided.toShape()), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Lock, null, Modifier.size(36.dp), tint = cs.onPrimaryContainer)
        }
        Text("My PAI is locked", Modifier.padding(top = 22.dp), style = MaterialTheme.typography.titleLarge)
        Button({ g.platform.authenticate("Unlock My PAI", onUnlock) }, Modifier.padding(top = 18.dp)) { Text("Unlock") }
    }
}
