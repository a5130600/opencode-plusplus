package com.opencode.mobile.ui.nav

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opencode.mobile.core.AppResult
import com.opencode.mobile.core.ConnectionStatus
import com.opencode.mobile.data.remote.dto.FileAttachmentDto
import com.opencode.mobile.service.ConnectionServiceLauncher
import com.opencode.mobile.ui.artifacts.ArtifactsScreen
import com.opencode.mobile.ui.artifacts.FileViewerScreen
import com.opencode.mobile.ui.chat.ChatScreen
import com.opencode.mobile.ui.components.ConnectionBar
import com.opencode.mobile.ui.components.bottomBarInsets
import com.opencode.mobile.ui.components.horizontalSafeInsets
import com.opencode.mobile.ui.components.pressScale
import com.opencode.mobile.ui.components.rememberPress
import com.opencode.mobile.ui.sessions.AskAiSheet
import com.opencode.mobile.ui.sessions.NewSessionSheet
import com.opencode.mobile.ui.sessions.SessionsScreen
import com.opencode.mobile.ui.settings.DeviceSheet
import com.opencode.mobile.ui.settings.ModelSheet
import androidx.compose.ui.res.stringResource
import com.opencode.mobile.R
import com.opencode.mobile.ui.settings.SettingsScreen
import com.opencode.mobile.ui.permissions.PermissionPromptDialog
import com.opencode.mobile.ui.questions.QuestionPromptDialog
import com.opencode.mobile.ui.settings.WorkspaceSheet
import com.opencode.mobile.ui.theme.OcColors
import com.opencode.mobile.ui.theme.OcMotion

/** 导航目的地。用 depth 表达层级，转场动画据此决定方向。 */
sealed interface Route {
    val depth: Int

    data object Sessions : Route { override val depth = 0 }
    data object Artifacts : Route { override val depth = 0 }
    data object Settings : Route { override val depth = 0 }
    data class Chat(
        val sessionId: String,
        val title: String,
        /** 进入会话时预填到输入框的内容（例如"让 AI 读这个文件"）。 */
        val prefill: String? = null,
        /** 挂在这一轮上的文件附件（"让 AI 读"挑完对话后带过来）。 */
        val attachment: FileAttachmentDto? = null,
    ) : Route {
        override val depth = 1
    }

    data class Viewer(val target: FileViewTarget) : Route { override val depth = 1 }
}

/**
 * 打开文件查看器所需的最小信息。
 *
 * 只有四个字段，而且**没有 size / mtime** —— 服务端的 FileNode 不提供它们，
 * 我们也不该自己编（见 data/storage/Storage.kt 的 BlobId 注释）。
 * 缓存身份就是 deviceId + remotePath，够了。
 */
data class FileViewTarget(
    val deviceId: String,
    val sessionId: String?,
    val remotePath: String,
    val displayName: String,
)

@Composable
fun AppRoot(modifier: Modifier = Modifier) {
    val vm: AppViewModel = hiltViewModel()
    val context = LocalContext.current

    val status by vm.status.collectAsStateWithLifecycle()
    val lastError by vm.lastError.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val activeDevice by vm.activeDevice.collectAsStateWithLifecycle()
    val workspaces by vm.workspaces.collectAsStateWithLifecycle()
    val currentWorkspace by vm.currentWorkspace.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val selectedModel by vm.selectedModel.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()

    var route by remember { mutableStateOf<Route>(Route.Sessions) }
    /** 只记一层：够用，且避免维护一个栈带来的状态同步问题。 */
    var previousRoute by remember { mutableStateOf<Route?>(null) }
    var showDeviceSheet by remember { mutableStateOf(false) }
    var showWorkspaceSheet by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showNewSession by remember { mutableStateOf(false) }
    /** 正在挑"@ 到哪个对话"的文件。非 null 时 AskAiSheet 展开。 */
    var askTarget by remember { mutableStateOf<FileViewTarget?>(null) }

    val sessions by vm.sessions.collectAsStateWithLifecycle()

    // 全局待审批：电脑上弹确认框时，人可能在任何页面，甚至是刚从通知点进来
    val pendingPermissions by vm.pendingPermissions.collectAsStateWithLifecycle()
    val pendingForms by vm.pendingForms.collectAsStateWithLifecycle()
    /** 被"稍后"掉的那条：本轮不再自动弹，通知栏那条还在，随时能回来处理。 */
    var snoozedPermission by remember { mutableStateOf<String?>(null) }
    var snoozedForm by remember { mutableStateOf<String?>(null) }
    val promptPermission = pendingPermissions.firstOrNull { it.id != snoozedPermission }
    // 审批优先：它拦的是"要不要执行某个操作"，比提问更急，
    // 而且同一时刻两条弹窗互相盖住只会让人点错。
    val promptForm = if (promptPermission == null) {
        pendingForms.firstOrNull { it.id != snoozedForm }
    } else {
        null
    }

    /**
     * 被推迟、但**还在挂起列表里**的那些（按 id 回查，那条在电脑端被自己处理掉了就自动不算）。
     *
     * 「稍后」不能是单向门：点了之后弹窗再也不出现、通知栏那条又容易被划掉，
     * 人一回过神来就找不回这一步 —— 而电脑端还在死等，agent 就卡在那儿了。
     * 所以推迟期间顶部必须常驻一条能一键叫回来的入口。
     */
    val snoozedPendingPermission = pendingPermissions.firstOrNull { it.id == snoozedPermission }
    val snoozedPendingForm = pendingForms.firstOrNull { it.id == snoozedForm }
    val resumePending: () -> Unit = {
        snoozedPermission = null
        snoozedForm = null
    }

    // 通知被系统关掉 = 这条链路整个失效，必须让人看见并给一键去开的路
    var notificationsEnabled by remember { mutableStateOf(true) }
    val openNotificationSettings: () -> Unit = {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    val push: (Route) -> Unit = { next ->
        previousRoute = route
        route = next
    }
    // 返回必须回到"来的地方"：从会话里打开的文件，退回会话，而不是退到列表
    val pop: () -> Unit = {
        route = previousRoute ?: Route.Sessions
        previousRoute = null
    }
    val selectTab: (Route) -> Unit = { tab ->
        previousRoute = null
        route = tab
    }

    // 有设备就常驻前台服务；没有就停掉 —— 避免无意义地占着一条通知
    LaunchedEffect(activeDevice?.id) {
        ConnectionServiceLauncher.ensureRunning(context, activeDevice != null)
    }

    // 回到前台校准一次。后台期间 SSE 事件会丢（系统的后台限制），
    // 光靠事件流补不回来 —— 用一次 REST 把会话列表和状态拉准。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.onForeground()
                // 每次回前台重查：用户可能刚在设置里改过
                notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 通知点进来：直达那条审批所属的会话，而不是停在默认 Tab 让人自己找
    val pendingSession by PendingRoute.session.collectAsStateWithLifecycle()
    LaunchedEffect(pendingSession) {
        val id = pendingSession ?: return@LaunchedEffect
        // 点通知进来 = 明确表达"我要处理它"，之前推迟过的一律解冻，
        // 否则深链把我们送到会话页、弹窗却因为 snoozed 不再出现 —— 等于白点。
        resumePending()
        push(Route.Chat(sessionId = id, title = vm.titleOf(id)))
        PendingRoute.consume()
    }

    // 委派属性（collectAsStateWithLifecycle）没法被智能转换，所以先取到局部变量。
    // 直接写 `activeDevice != null` 再访问 `activeDevice.host` 是编译不过的。
    val device = activeDevice
    val workspaceDir = currentWorkspace?.path

    /**
     * 跳进对话并把文件挂上。
     *
     * 附件由 [AppViewModel.buildAttachment] 构造：优先把内容内联成 data URL ——
     * 只给 `file://` 路径的话，服务端要自己读一遍文件，而这次读取受 location 沙箱管辖，
     * 工作区之外的文件一律 400。
     */
    fun openAskChat(
        sessionId: String,
        title: String,
        attachment: FileAttachmentDto,
        target: FileViewTarget,
    ) {
        askTarget = null
        push(
            Route.Chat(
                sessionId = sessionId,
                title = title,
                prefill = "请读取并总结这个文件：${target.remotePath}",
                attachment = attachment,
            )
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // 只挡侧边：挖孔屏横屏时摄像头在左右边缘。纵向留给顶栏/底栏各自处理，
            // 这里要是再统一加一圈，屏幕就白铺不满、顶上露出色带了。
            .horizontalSafeInsets()
            .background(OcColors.Bg),
    ) {
        ConnectionBar(
            status = status,
            deviceName = device?.name,
            latencyHint = when {
                // 连上了显示地址；没连上就把失败原因显示出来，不然只有一个"重连中"没法查
                status == ConnectionStatus.CONNECTED && device != null -> "${device.host}:${device.port}"
                status != ConnectionStatus.CONNECTED -> lastError
                else -> null
            },
            onClick = { showDeviceSheet = true },
        )
        HorizontalDivider(color = OcColors.Line2)

        // 推迟中的待办放在最上面：它拦着电脑端的任务，比"通知没开"更紧急
        if (snoozedPendingPermission != null || snoozedPendingForm != null) {
            SnoozedPendingBanner(
                hasPermission = snoozedPendingPermission != null,
                hasQuestion = snoozedPendingForm != null,
                rest = pendingPermissions.size + pendingForms.size - 1,
                onResume = resumePending,
            )
        }

        if (!notificationsEnabled) {
            NotificationOffBanner(onOpen = openNotificationSettings)
        }

        Box(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = route,
                transitionSpec = {
                    val bothTabs = initialState.depth == 0 && targetState.depth == 0
                    if (bothTabs) {
                        // Tab 是平级关系：只淡入 + 轻微上移，不做左右滑动
                        fadeIn(tween(OcMotion.TAB_MS, easing = OcMotion.Standard)) togetherWith
                            fadeOut(tween(OcMotion.TAB_MS, easing = OcMotion.Standard))
                    } else {
                        val forward = targetState.depth > initialState.depth
                        val direction = if (forward) 1 else -1
                        (
                            slideInHorizontally(
                                animationSpec = tween(OcMotion.PUSH_MS, easing = OcMotion.Emphasized),
                                initialOffsetX = { full -> full * direction },
                            ) + fadeIn(tween(OcMotion.PUSH_MS / 2))
                            ) togetherWith (
                            slideOutHorizontally(
                                animationSpec = tween(OcMotion.PUSH_MS, easing = OcMotion.Emphasized),
                                // 视差：旧页只移 1/3，制造纵深
                                targetOffsetX = { full -> -full * direction / 3 },
                            ) + fadeOut(tween(OcMotion.PUSH_MS / 2))
                            )
                    }.using(SizeTransform(clip = false))
                },
                label = "route",
            ) { current ->
                when (current) {
                    Route.Sessions -> SessionsScreen(
                        activeDevice = activeDevice,
                        currentWorkspace = currentWorkspace,
                        workspaces = workspaces,
                        onOpenWorkspaces = { showWorkspaceSheet = true },
                        onOpenSession = { id, title -> push(Route.Chat(id, title)) },
                        onAddDevice = { showDeviceSheet = true },
                        onNewSession = { showNewSession = true },
                    )

                    Route.Artifacts -> ArtifactsScreen(
                        activeDevice = activeDevice,
                        workspaces = workspaces,
                        onSelectWorkspace = vm::selectWorkspace,
                        onOpenFile = { target -> push(Route.Viewer(target)) },
                    )

                    Route.Settings -> SettingsScreen(
                        status = status,
                        devices = devices,
                        activeDevice = activeDevice,
                        workspaces = workspaces,
                        currentWorkspace = currentWorkspace,
                        selectedModel = selectedModel,
                        usage = usage,
                        onOpenDevices = { showDeviceSheet = true },
                        onOpenWorkspaces = { showWorkspaceSheet = true },
                        onOpenModels = { showModelSheet = true },
                        onClearPreviewCache = vm::clearPreviewCache,
                        onRemoveDevice = vm::removeDevice,
                    )

                    is Route.Chat -> ChatScreen(
                        sessionId = current.sessionId,
                        fallbackTitle = current.title,
                        selectedModel = selectedModel,
                        prefill = current.prefill,
                        attachment = current.attachment,
                        onBack = pop,
                        onOpenModels = { showModelSheet = true },
                        onOpenFile = { target -> push(Route.Viewer(target)) },
                    )

                    is Route.Viewer -> FileViewerScreen(
                        target = current.target,
                        onBack = pop,
                        // 不直接跳会话：从产物页打开的文件不属于任何会话，
                        // 以前那样写会让按钮变成死的。先让用户挑对话。
                        onAskAi = { askTarget = current.target },
                    )
                }
            }
        }

        if (route.depth == 0) {
            OcTabBar(
                current = route,
                onSelect = { route = it },
            )
        }
    }

    if (showDeviceSheet) {
        DeviceSheet(
            devices = devices,
            activeDeviceId = activeDevice?.id,
            onDismiss = { showDeviceSheet = false },
            onSelect = {
                vm.selectDevice(it)
                showDeviceSheet = false
            },
            onAdd = { draft ->
                vm.addDevice(draft)
                showDeviceSheet = false
            },
        )
    }

    if (showWorkspaceSheet) {
        WorkspaceSheet(
            workspaces = workspaces,
            currentPath = currentWorkspace?.path,
            deviceName = activeDevice?.name,
            onDismiss = { showWorkspaceSheet = false },
            onSelect = {
                vm.selectWorkspace(it.path)
                showWorkspaceSheet = false
            },
            onRefresh = vm::syncWorkspaces,
        )
    }

    if (showNewSession) {
        NewSessionSheet(
            workspaces = workspaces,
            currentPath = workspaceDir,
            onDismiss = { showNewSession = false },
            onCreate = { path -> vm.createSession(path) },
            onCreated = {
                showNewSession = false
                push(Route.Chat(it.id, it.title))
            },
        )
    }

    askTarget?.let { target ->
        AskAiSheet(
            fileName = target.displayName,
            sessions = sessions,
            onDismiss = { askTarget = null },
            // 返回 null = 成功；非 null = 要显示在面板里的错误文案
            onPick = { session ->
                val dir = session.workspacePath ?: workspaceDir
                openAskChat(
                    sessionId = session.id,
                    title = session.title,
                    attachment = vm.buildAttachment(target, dir),
                    target = target,
                )
                null
            },
            onNewSession = {
                when (val result = vm.createSession(workspaceDir)) {
                    is AppResult.Ok -> {
                        val created = result.value
                        openAskChat(
                            sessionId = created.id,
                            title = created.title,
                            attachment = vm.buildAttachment(target, workspaceDir),
                            target = target,
                        )
                        null
                    }

                    is AppResult.Err -> result.message
                }
            },
        )
    }

    // 审批弹窗放在最后：它走独立窗口，与当前在哪个页面无关
    promptPermission?.let { permission ->
        PermissionPromptDialog(
            permission = permission,
            index = pendingPermissions.indexOf(permission) + 1,
            total = pendingPermissions.size,
            onDecide = { decision -> vm.decidePermission(permission, decision) },
            onLater = { snoozedPermission = permission.id },
        )
    }

    promptForm?.let { form ->
        QuestionPromptDialog(
            form = form,
            index = pendingForms.indexOf(form) + 1,
            total = pendingForms.size,
            onSubmit = { answer -> vm.answerForm(form, answer) },
            onSkip = { vm.skipForm(form) },
            onLater = { snoozedForm = form.id },
        )
    }

    if (showModelSheet) {
        ModelSheet(
            options = models,
            selected = selectedModel,
            onDismiss = { showModelSheet = false },
            onSelect = {
                vm.selectModel(it)
                showModelSheet = false
            },
        )
    }
}

/**
 * 被「稍后」推迟掉的待办的**召回入口**。
 *
 * 常驻，直到那条在电脑端消失（被答复 / 被跳过 / 自己结束）为止。
 * 颜色用 Warn 而不是报错色：它不是故障，是"有件事在等你"。
 */
@Composable
private fun SnoozedPendingBanner(
    hasPermission: Boolean,
    hasQuestion: Boolean,
    /** 除了这一条之外还剩几条（0 就不显示）。 */
    rest: Int,
    onResume: () -> Unit,
) {
    val text = when {
        hasPermission && hasQuestion -> stringResource(R.string.pending_banner_both)
        hasPermission -> stringResource(R.string.pending_banner_permission)
        else -> stringResource(R.string.pending_banner_question)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OcColors.WarnSoft)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (hasQuestion && !hasPermission) Icons.Filled.SmartToy else Icons.Filled.Shield,
            contentDescription = null,
            tint = OcColors.Warn,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = OcColors.Warn,
            )
            if (rest > 0) {
                Text(
                    text = stringResource(R.string.pending_banner_more, rest),
                    style = MaterialTheme.typography.labelSmall,
                    color = OcColors.Warn,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.pending_banner_resume),
            style = MaterialTheme.typography.labelSmall,
            color = OcColors.Warn,
            modifier = Modifier.clickable { onResume() },
        )
    }
}

/**
 * 通知被关掉的提示条。
 *
 * 这条链路一旦失效是**静默**的：App 内一切正常，但电脑上卡住的确认框你永远收不到。
 * 所以不能只在申请权限时弹一次系统框就算完，得给一个常驻的、能一键去开的路。
 */
@Composable
private fun NotificationOffBanner(onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OcColors.WarnSoft)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.permission_notifications_off),
            style = MaterialTheme.typography.bodySmall,
            color = OcColors.Warn,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.permission_notifications_open),
            style = MaterialTheme.typography.labelSmall,
            color = OcColors.Warn,
            modifier = Modifier.clickable { onOpen() },
        )
    }
}

private data class TabSpec(val route: Route, val label: String, val icon: ImageVector)

private val TABS = listOf(
    TabSpec(Route.Sessions, "会话", Icons.Filled.ChatBubbleOutline),
    TabSpec(Route.Artifacts, "产物", Icons.Filled.FolderOpen),
    TabSpec(Route.Settings, "设置", Icons.Filled.Settings),
)

@Composable
private fun OcTabBar(current: Route, onSelect: (Route) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OcColors.Surface)
            // 背景铺到屏幕最下沿，内容压在手势条上方
            .bottomBarInsets()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TABS.forEach { tab ->
            val selected = current::class == tab.route::class
            val interaction = rememberPress()
            val scale = pressScale(interaction)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                    ) { if (!selected) onSelect(tab.route) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tab.label,
                    tint = if (selected) OcColors.Ink else OcColors.Ink3,
                    modifier = Modifier
                        .size(22.dp)
                        .scale(if (selected) 1.06f * scale else scale),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) OcColors.Ink else OcColors.Ink3,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                )
            }
        }
    }
}
