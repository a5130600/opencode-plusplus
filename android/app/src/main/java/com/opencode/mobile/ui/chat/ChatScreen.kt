package com.opencode.mobile.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opencode.mobile.data.remote.dto.FileAttachmentDto
import kotlinx.coroutines.flow.first
import com.opencode.mobile.data.repository.ModelOption
import com.opencode.mobile.ui.components.ChipTone
import com.opencode.mobile.ui.components.EmptyState
import com.opencode.mobile.ui.components.MdPalette
import com.opencode.mobile.ui.components.MdText
import com.opencode.mobile.ui.components.OcIconButton
import com.opencode.mobile.ui.components.StatusChip
import com.opencode.mobile.ui.components.composerInsets
import com.opencode.mobile.ui.components.pressScale
import com.opencode.mobile.ui.components.rememberPress
import com.opencode.mobile.ui.nav.FileViewTarget
import com.opencode.mobile.ui.theme.OcColors
import com.opencode.mobile.ui.theme.OcMotion
import kotlinx.coroutines.delay

/**
 * 会话页。
 *
 * 这里有一条不平滑但正确的原则：**SSE 只负责"增量好看"，REST 负责"状态正确"。**
 * 消息用 SSE 一条条补进来，但运行状态永远以 REST 校准为准 ——
 * 手机上切后台、锁屏、隧道抖动都会丢事件，只靠 SSE 会一直卡在"运行中"。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    sessionId: String,
    fallbackTitle: String,
    selectedModel: ModelOption?,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenFile: (FileViewTarget) -> Unit,
    modifier: Modifier = Modifier,
    prefill: String? = null,
    attachment: FileAttachmentDto? = null,
) {
    val vm: ChatViewModel = hiltViewModel()
    val items by vm.items.collectAsStateWithLifecycle()
    val title by vm.title.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val attached by vm.attachment.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val pendingOpen by vm.pendingOpen.collectAsStateWithLifecycle()
    val thinking by vm.thinking.collectAsStateWithLifecycle()
    val loadingOlder by vm.loadingOlder.collectAsStateWithLifecycle()
    val hasOlder by vm.hasOlder.collectAsStateWithLifecycle()
    val pagedOlder by vm.pagedOlder.collectAsStateWithLifecycle()

    // 最后一条的内容指纹。流式输出时 items.size 不变（只是同一条在变长），
    // 只盯 size 会漏掉跟随，所以内容变化也要进触发条件。
    val tailSignature = items.lastOrNull()?.hashCode() ?: 0

    val listState = rememberLazyListState()

    LaunchedEffect(sessionId, prefill, attachment) { vm.bind(sessionId, prefill, attachment) }

    /** 回到"上次看到哪"，而不是无脑贴底。 */
    var restored by remember(sessionId) { mutableStateOf(false) }

    /*
     * 滚动策略。整个画面只维护一个开关：**stickToBottom**，其余情况一律照它办事。
     *
     *   需求1 翻历史时 stick=false → 键盘弹起不滚（视口变矮但内容仍顶在原处，
     *        看着就是"没被顶起"），可以边看边想下一个问题。
     *   需求2 发消息时把 stick 置 true → AI 每产出一条就滚到最新。
     *   需求3 在底部时 stick=true → 键盘弹起视口变矮会按差值补滚，把底部重新贴住；
     *        键盘不关、新回复照样一路跟到底。
     *
     * ⚠️ 关键：**不能每次临时测"现在在不在底部"再决定滚不滚。**
     * 这是老 bug 的根因 —— 键盘一弹起视口先变矮，此刻测出来的永远是"不在底部"，
     * 于是那一次滚动被跳过；而新布局下列表也不会自己重新贴底，结果就是"对话没被顶上去"。
     * 所以只在**用户自己拖完**那一刻重新裁决，其余时刻一律沿用上次裁决的结果。
     */
    var stickToBottom by remember(sessionId) { mutableStateOf(true) }

    /** 差多少 px 以内算"大致在底部"。给足余量：能看到最后几行就算。 */
    val bottomSlackPx = with(LocalDensity.current) { 160.dp.toPx() }

    /**
     * 还差多少 px 才到底部。<=0 = 已经在底部（或内容不满一屏）；
     * 无穷大 = 最后一条压根不在视口里。
     */
    fun gapToBottom(): Float {
        val info = listState.layoutInfo
        val total = info.totalItemsCount
        if (total == 0) return 0f
        val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
        if (last.index != total - 1) return Float.POSITIVE_INFINITY
        return (last.offset + last.size - info.viewportEndOffset).toFloat()
    }

    /**
     * 贴到底部。
     *
     * 按"还差多少 px"滚，而不是按索引跳：按索引跳会把一条很长的回答重新顶到它的
     * **开头**，流式输出时每来一段就被拽回去一次。
     */
    suspend fun pinToBottom(animated: Boolean) {
        val total = listState.layoutInfo.totalItemsCount
        if (total == 0) return
        val gap = gapToBottom()
        if (gap.isInfinite()) {
            // 最后一条不在视口里：先按索引跳过去，下一轮再按 gap 精确贴底
            runCatching {
                if (animated) listState.animateScrollToItem(total - 1) else listState.scrollToItem(total - 1)
            }
            return
        }
        if (gap <= 0f) return
        runCatching { if (animated) listState.animateScrollBy(gap) else listState.scrollBy(gap) }
    }

    LaunchedEffect(sessionId, items.size) {
        if (restored || items.isEmpty()) return@LaunchedEffect
        val saved = ChatScrollMemory.get(sessionId)
        runCatching {
            if (saved != null && saved > 0) {
                listState.scrollToItem(saved.coerceAtMost(items.lastIndex))
            } else {
                listState.scrollToItem(items.lastIndex)
            }
        }
        // 恢复到最底部 = 继续跟随；恢复到历史位置 = 先不跟随，等用户自己滚回底部。
        stickToBottom = saved == null || saved <= 0 || saved >= items.lastIndex - 1
        restored = true
    }

    // 持续记下滚动位置。进程内记住即可 —— 用户要的是"App 开着时来回切换能回到原处"。
    LaunchedEffect(sessionId, listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { ChatScrollMemory.put(sessionId, it) }
    }

    /*
     * 翻到接近顶部就补一页更早的消息。
     *
     * 会话消息是分页的，不翻页的话长会话只能看到服务端默认给的那一屏 ——
     * 表现就是"往上翻到一定程度再也翻不动"。
     *
     * 不用按按钮：手机上"往下滑到头"本身就是"我要看更早"的意思，再让人点一下是多余的动作。
     * loadOlder() 自带三重护栏（正在翻 / 没 cursor / 已到顶都不会重复请求），
     * 所以这里可以跟着滚动无脑调。
     */
    LaunchedEffect(sessionId, listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index -> if (index <= 2) vm.loadOlder() }
    }

    /** 手指正按在列表上。此时一律不自动滚动 —— AI 边输出边把人拽回底部最招人烦。 */
    var userDragging by remember(sessionId) { mutableStateOf(false) }

    // 重新裁决"跟不跟随"的唯一入口：用户自己拖完（含惯性滑完）。
    // 程序化的贴底滚动不会发 Interaction，所以不会误判。
    LaunchedEffect(sessionId, listState) {
        val settleMs = 120L
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> userDragging = true
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    userDragging = false
                    // 抬手还有惯性，且松手瞬间人还在底部 —— 直接判会永远判成"想留在底部"。
                    // 等到"连续 settleMs 都没在滚"才算停稳。
                    while (true) {
                        snapshotFlow { listState.isScrollInProgress }.first { !it }
                        delay(settleMs)
                        if (!listState.isScrollInProgress) break
                    }
                    stickToBottom = gapToBottom() <= bottomSlackPx
                }
            }
        }
    }

    // 视口变矮/变高（键盘起落、权限卡/提示条/附件条出现消失）：跟随时补滚，重新贴住底部。
    // 这条才是"键盘弹起要把对话顶上去"的执行者 —— LazyList 不会因为视口变矮自己贴底。
    // 用瞬时滚动：键盘是逐帧动画的，animate 会一直慢半拍。
    LaunchedEffect(sessionId, listState) {
        var lastHeight = -1
        snapshotFlow { listState.layoutInfo.viewportSize.height }.collect { h ->
            val changed = lastHeight > 0 && h > 0 && h != lastHeight
            lastHeight = h
            if (changed && stickToBottom) pinToBottom(animated = false)
        }
    }

    // 新消息 / 流式增长 / 等待动画出现：跟随时滚到最新。
    LaunchedEffect(items.size, tailSignature, thinking) {
        if (items.isEmpty()) return@LaunchedEffect
        // 等一帧让新内容先量出来
        withFrameNanos { }
        if (stickToBottom && !userDragging) pinToBottom(animated = true)
    }

    LaunchedEffect(pendingOpen) {
        pendingOpen?.let {
            onOpenFile(it)
            vm.consumeOpen()
        }
    }

    val running = status != null && status != "idle"

    // 键盘高度（px），只用来决定"手势条那块补白画不画"。
    val imeBottomPx = with(LocalDensity.current) {
        WindowInsets.ime.asPaddingValues().calculateBottomPadding().toPx()
    }

    // 手势条高度（px），用来把输入框的白底延到屏幕最下沿。
    val navBottomPx = with(LocalDensity.current) {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().toPx()
    }

    Column(
        modifier
            .fillMaxSize()
            // 手势条补白必须画在 composerInsets() **之前**（也就是整屏范围）：
            // 这一整屏的 bottom padding 都被 inset 吃掉了，画在之后只会落进内容区，
            // 屏幕上照样露出一条页面底色。
            // 用 drawBehind 而不是塞一个子 Box：子 Box 会占掉 navBottom 的布局高度，
            // 把上面 weight(1f) 的消息列表挤短、并在输入框下方留一条空隙。
            .drawBehind {
                // 键盘弹起时不画：那一段被键盘盖着，多垫一块会在键盘上方露出白条。
                if (navBottomPx > 0f && imeBottomPx <= 0f) {
                    drawRect(
                        color = OcColors.Surface,
                        topLeft = Offset(0f, size.height - navBottomPx),
                        size = Size(size.width, navBottomPx),
                    )
                }
            }
            // 键盘与手势条的留白在这里一次性吃掉，而不是加在输入框上。
            // 加在输入框上时会和 weight(1f) 的测量顺序纠缠，实测偶尔不生效；
            // 放在整屏根部只有一个施加点，最稳。
            .composerInsets()
            .background(OcColors.Bg),
    ) {
        ChatTopBar(
            title = title.ifBlank { fallbackTitle },
            subtitle = buildString {
                append(selectedModel?.modelName ?: "服务端默认模型")
                if (running) append(" · ").append(statusLabel(status))
            },
            running = running,
            onBack = onBack,
            onOpenModels = onOpenModels,
            onAbort = vm::abort,
        )

        if (sessionId.isBlank()) {
            EmptyState(
                title = "没有可用的会话",
                hint = "这个文件不属于任何会话。回到会话列表打开一个会话，再在里面引用它。",
            )
            return@Column
        }

        Box(Modifier.weight(1f)) {
            if (items.isEmpty()) {
                EmptyState(
                    title = "还没有消息",
                    hint = "直接在下面输入，指令会发到你电脑上的 opencode",
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // 历史分页的状态行。只在"正在翻"或"翻过且已到顶"时出现 ——
                    // 还能翻的时候自动就翻了，不必占一行告诉用户。
                    if (loadingOlder || (pagedOlder && !hasOlder)) {
                        item(key = "__older__") {
                            OlderMessagesHint(loading = loadingOlder)
                        }
                    }

                    items(items, key = { it.id }) { item ->
                        MessageBlock(
                            item = item,
                            onOpenFile = vm::openFile,
                            onRetry = { localId ->
                                // 重发同样是我主动提问，AI 一回复就该跟到最新
                                stickToBottom = true
                                vm.retry(localId, selectedModel)
                            },
                        )
                    }

                    // 等待动画作为列表最后一项 —— 这样它跟着内容一起滚动，
                    // 贴底逻辑不用为它单独写一套。
                    if (thinking) {
                        item(key = "__thinking__") { ThinkingBubble() }
                    }
                }
            }
        }

        // 审批**不在这里画卡**：它已经提到全局弹窗（AppRoot 的 PermissionPromptDialog）了。
        // 电脑上弹确认框时人可能在任何 Tab，会话页里的一张卡看不见就等于没提示。
        // 两套并存还会出现"卡上批过了、弹窗还在"这种不一致。
        NoticeBanner(notice = notice, onConsumed = vm::consumeNotice)

        Composer(
            draft = draft,
            sending = sending,
            attachment = attached,
            onRemoveAttachment = vm::removeAttachment,
            onDraftChange = vm::onDraftChange,
            onSend = {
                // 需求2：只要是我主动提问，之后 AI 一回复就必须跟到最新
                // （哪怕我中间翻上去看了历史）
                stickToBottom = true
                vm.send(selectedModel)
            },
        )
    }
}

/* ── 顶栏 ─────────────────────────────────────────────────────────────── */

@Composable
private fun ChatTopBar(
    title: String,
    subtitle: String,
    running: Boolean,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onAbort: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OcColors.Surface)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OcIconButton(Icons.AutoMirrored.Filled.ArrowBack, onClick = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (running) OcColors.Run else OcColors.Ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        ModelChip(label = "模型", onClick = onOpenModels)

        // 中止只在真正跑着的时候出现 —— 常驻会让顶栏失去"状态表达"的作用
        AnimatedVisibility(
            visible = running,
            enter = fadeIn(tween(OcMotion.TAB_MS)),
            exit = fadeOut(tween(OcMotion.TAB_MS)),
        ) {
            OcIconButton(
                icon = Icons.Filled.Stop,
                onClick = onAbort,
                tint = OcColors.Warn,
            )
        }
    }
}

@Composable
private fun ModelChip(label: String, onClick: () -> Unit) {
    val interaction = rememberPress()
    val scale = pressScale(interaction)
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(OcColors.Surface2)
            .border(1.dp, OcColors.Line, CircleShape)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 11.dp, vertical = 6.dp)
            .scale(scale),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = OcColors.Ink2,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/* ── 消息 ─────────────────────────────────────────────────────────────── */

/* ── 滚动位置记忆 ─────────────────────────────────────────────────────── */

/**
 * 每个会话看到哪了。
 *
 * **只在进程内存里记**：用户要的是"软件开着的时候来回切换能回到刚才的位置"，
 * 落盘没必要，也避免数据库多一张表。App 被杀掉就重置，可以接受。
 */
internal object ChatScrollMemory {
    private val positions = mutableMapOf<String, Int>()

    fun get(sessionId: String): Int? = positions[sessionId]

    fun put(sessionId: String, index: Int) {
        if (sessionId.isNotBlank()) positions[sessionId] = index
    }
}

/* ── 投递状态 ─────────────────────────────────────────────────────────── */

/** 深色气泡上的状态色。不能用 OcColors.Warn —— 那是给浅底配的，压深底上看不清。 */
private val FailedOnDark = Color(0xFFFF9E90)
private val DimOnDark = Color(0x99FFFFFF)

@Composable
private fun SendStatusInline(state: SendState, onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (state) {
            SendState.Sending -> Text(
                text = "发送中…",
                style = MaterialTheme.typography.labelSmall,
                color = DimOnDark,
            )

            SendState.Sent -> Text(
                text = "已发送",
                style = MaterialTheme.typography.labelSmall,
                color = DimOnDark,
            )

            SendState.Failed -> {
                Text(
                    text = "发送失败",
                    style = MaterialTheme.typography.labelSmall,
                    color = FailedOnDark,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "重试",
                    style = MaterialTheme.typography.labelSmall,
                    color = FailedOnDark,
                    fontWeight = FontWeight.Medium,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { onRetry() },
                )
            }
        }
    }
}

/* ── 历史分页的状态行 ───────────────────────────────────────────────────── */

/**
 * 列表最上面那一行。
 *
 * 它是"翻历史"这件事唯一的可见反馈：正在翻时给个转圈，翻到头了明确说一句
 * "没有更早的消息了" —— 否则用户只能靠"翻不动了"来猜到底是没有了，还是没加载出来。
 */
@Composable
private fun OlderMessagesHint(loading: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(13.dp),
                strokeWidth = 1.6.dp,
                color = OcColors.Ink2,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = if (loading) "正在加载更早的消息…" else "没有更早的消息了",
            style = MaterialTheme.typography.bodySmall,
            color = OcColors.Ink3,
        )
    }
}

/* ── 等待动画 ─────────────────────────────────────────────────────────── */

/**
 * 「思考中」的三个点上下起伏。
 *
 * 用三个相位错开的点而不是系统转圈：转圈传达的是"正在加载"，而这里表达的是
 * "对面正在想" —— 起伏的节奏更像有生命的东西在动，也更符合聊天的语感。
 *
 * 三个点各自 tween + Reverse，靠 initialStartOffset 错开 140ms 形成波浪。
 */
@Composable
private fun ThinkingBubble() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(OcColors.Surface2)
                .border(1.dp, OcColors.Line2, RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.SmartToy,
                contentDescription = null,
                tint = OcColors.Ink2,
                modifier = Modifier.size(15.dp),
            )
        }

        Spacer(Modifier.width(10.dp))

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp, 14.dp, 14.dp, 5.dp))
                .background(OcColors.Surface)
                .border(1.dp, OcColors.Line2, RoundedCornerShape(14.dp, 14.dp, 14.dp, 5.dp))
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val transition = rememberInfiniteTransition(label = "thinking")
            repeat(3) { index ->
                val rise by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 620, easing = OcMotion.Standard),
                        repeatMode = RepeatMode.Reverse,
                        initialStartOffset = StartOffset(index * 140),
                    ),
                    label = "dot$index",
                )
                Box(
                    Modifier
                        .offset(y = (-3.5f * rise).dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(OcColors.Ink3.copy(alpha = 0.55f + 0.45f * rise)),
                )
                if (index < 2) Spacer(Modifier.width(5.dp))
            }
            Spacer(Modifier.width(9.dp))
            Text(
                text = "思考中",
                style = MaterialTheme.typography.bodySmall,
                color = OcColors.Ink3,
            )
        }
    }
}

@Composable
private fun MessageBlock(
    item: ChatItem,
    onOpenFile: (String, String) -> Unit,
    onRetry: (String) -> Unit,
) {
    if (item.isUser) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .fillMaxWidth(0.86f)
                    .clip(RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp))
                    .background(OcColors.Ink)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item.blocks.forEach { block ->
                            when (block) {
                                // 用户粘贴过来的也可能是 Markdown（代码块、表格），一起渲染。
                                // 气泡是深色底，配色要反过来，否则文字看不见。
                                is ChatBlock.Text -> MdText(
                                    markdown = block.text,
                                    palette = MdPalette.onDarkBubble(),
                                    compact = true,
                                )

                                else -> MessageBlockContent(block, onLight = true, onOpenFile = onOpenFile)
                            }
                        }

                        // 投递状态画在自己这条气泡里 —— 用户要能判断发出去了没有
                        val localId = item.localId
                        item.sendState?.let { state ->
                            SendStatusInline(
                                state = state,
                                onRetry = { if (localId != null) onRetry(localId) },
                            )
                        }
                    }
                }
            }
        }
    } else {
        Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(OcColors.Surface2)
                    .border(1.dp, OcColors.Line2, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.SmartToy,
                    contentDescription = null,
                    tint = OcColors.Ink2,
                    modifier = Modifier.size(15.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            SelectionContainer {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    // 连续的「思考 / 工具调用」合并成一段并默认折起来。
                    // 这些东西又长又碎，展开看是噪音；正文单独显示，结论才是一眼要看的。
                    // remember：分组是纯计算，但流式输出时列表每帧都在重组，白算很浪费。
                    val groups = remember(item.blocks) { groupBlocks(item.blocks) }
                    groups.forEach { group ->
                        when (group) {
                            is BlockGroup.Single ->
                                MessageBlockContent(group.block, onLight = false, onOpenFile = onOpenFile)

                            is BlockGroup.Process ->
                                ProcessGroup(group.blocks, onLight = false, onOpenFile = onOpenFile)
                        }
                    }
                }
            }
        }
    }
}

/* ── 过程折叠 ─────────────────────────────────────────────────────────── */

private sealed interface BlockGroup {
    data class Single(val block: ChatBlock) : BlockGroup
    data class Process(val blocks: List<ChatBlock>) : BlockGroup
}

/** 把块切成「单块」与「连续过程段」。 */
private fun groupBlocks(blocks: List<ChatBlock>): List<BlockGroup> {
    val out = mutableListOf<BlockGroup>()
    val run = mutableListOf<ChatBlock>()

    fun flush() {
        if (run.isNotEmpty()) {
            out += BlockGroup.Process(run.toList())
            run.clear()
        }
    }

    blocks.forEach { block ->
        if (block.isProcess) {
            run += block
        } else {
            flush()
            out += BlockGroup.Single(block)
        }
    }
    flush()
    return out
}

@Composable
private fun ProcessGroup(
    blocks: List<ChatBlock>,
    onLight: Boolean,
    onOpenFile: (String, String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    val tools = blocks.filterIsInstance<ChatBlock.Tool>()
    val running = tools.any {
        it.status == "running" || it.status == "pending" || it.status == "streaming"
    }
    val failed = tools.any { it.status == "error" }

    val summary = buildString {
        if (running) append("正在处理") else if (failed) append("处理出错") else append("处理过程")
        append(" · ")
        append(blocks.size)
        append(" 步")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(if (onLight) OcColors.Surface.copy(alpha = 0.12f) else OcColors.Surface2),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = OcColors.Run,
                )
            } else {
                Icon(
                    Icons.Filled.Build,
                    contentDescription = null,
                    tint = if (failed) OcColors.Warn else OcColors.Ink3,
                    modifier = Modifier.size(13.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = OcColors.Ink3,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = OcColors.Ink3,
                modifier = Modifier.size(16.dp),
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                blocks.forEach { block ->
                    MessageBlockContent(block, onLight = onLight, onOpenFile = onOpenFile)
                }
            }
        }
    }
}

@Composable
private fun MessageBlockContent(
    block: ChatBlock,
    onLight: Boolean,
    onOpenFile: (String, String) -> Unit,
) {
    when (block) {
        is ChatBlock.Text -> MdText(
            markdown = block.text,
            palette = MdPalette.onLightBg(),
        )

        is ChatBlock.Reasoning -> Text(
            text = block.text,
            style = MaterialTheme.typography.bodySmall,
            color = OcColors.Ink3,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(9.dp))
                .background(if (onLight) OcColors.Surface.copy(alpha = 0.08f) else OcColors.Surface)
                .padding(horizontal = 9.dp, vertical = 7.dp),
        )

        is ChatBlock.Tool -> ToolRow(block, onLight)
        is ChatBlock.FileRef -> FileRow(block, onLight, onOpenFile)
    }
}

@Composable
private fun ToolRow(block: ChatBlock.Tool, onLight: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val running = block.status == "running" || block.status == "pending"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(if (onLight) OcColors.Surface.copy(alpha = 0.12f) else OcColors.Surface2)
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Build,
            contentDescription = null,
            tint = if (onLight) OcColors.Surface else OcColors.Ink2,
            modifier = Modifier.size(14.dp).rotate(if (running) -12f else 0f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = block.title ?: block.name,
            style = MaterialTheme.typography.bodySmall,
            color = if (onLight) OcColors.Surface else OcColors.Ink,
            maxLines = if (expanded) 6 else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        StatusChip(
            text = statusLabel(block.status),
            tone = when {
                running -> ChipTone.Run
                block.status == "completed" || block.status == "success" -> ChipTone.Ok
                block.status == "error" -> ChipTone.Warn
                else -> ChipTone.Neutral
            },
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = if (onLight) OcColors.Surface else OcColors.Ink3,
            modifier = Modifier.size(15.dp),
        )
    }
}

@Composable
private fun FileRow(
    block: ChatBlock.FileRef,
    onLight: Boolean,
    onOpenFile: (String, String) -> Unit,
) {
    val interaction = rememberPress()
    val scale = pressScale(interaction)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(if (onLight) OcColors.Surface.copy(alpha = 0.12f) else OcColors.Surface2)
            .border(
                1.dp,
                if (onLight) OcColors.Surface.copy(alpha = 0.2f) else OcColors.Line2,
                RoundedCornerShape(11.dp),
            )
            .clickable(interactionSource = interaction, indication = null) {
                onOpenFile(block.path, block.name)
            }
            .padding(horizontal = 10.dp, vertical = 9.dp)
            .scale(scale),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Description,
            contentDescription = null,
            tint = if (onLight) OcColors.Surface else OcColors.Ink2,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = block.name,
                style = MaterialTheme.typography.bodySmall,
                color = if (onLight) OcColors.Surface else OcColors.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = block.path,
                style = MaterialTheme.typography.labelSmall,
                color = if (onLight) OcColors.Surface.copy(alpha = 0.7f) else OcColors.Ink3,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = if (onLight) OcColors.Surface.copy(alpha = 0.7f) else OcColors.Ink3,
            modifier = Modifier.size(15.dp),
        )
    }
}

/* ── 提示与输入 ───────────────────────────────────────────────────────── */

@Composable
private fun NoticeBanner(notice: String?, onConsumed: () -> Unit) {
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(3_000)
            onConsumed()
        }
    }
    AnimatedVisibility(
        visible = notice != null,
        enter = expandVertically(tween(OcMotion.TAB_MS)) + fadeIn(tween(OcMotion.TAB_MS)),
        exit = shrinkVertically(tween(OcMotion.TAB_MS)) + fadeOut(tween(OcMotion.TAB_MS)),
    ) {
        Text(
            text = notice.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = OcColors.Warn,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(OcColors.WarnSoft)
                .border(1.dp, OcColors.WarnLine, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 9.dp),
        )
    }
}

@Composable
private fun Composer(
    draft: String,
    sending: Boolean,
    attachment: FileAttachmentDto?,
    onRemoveAttachment: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val canSend = draft.isNotBlank() && !sending

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 键盘/手势条的留白由 ChatScreen 根部统一吃，这里只铺底色
            .background(OcColors.Surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        // 附件挂在输入框上方：不藏起来，用户得看得见"这条会带上哪个文件"
        AnimatedVisibility(
            visible = attachment != null,
            enter = expandVertically(tween(OcMotion.TAB_MS)) + fadeIn(tween(OcMotion.TAB_MS)),
            exit = shrinkVertically(tween(OcMotion.TAB_MS)) + fadeOut(tween(OcMotion.TAB_MS)),
        ) {
            if (attachment != null) {
                AttachmentChip(attachment = attachment, onRemove = onRemoveAttachment)
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 46.dp, max = 132.dp),
            placeholder = { Text("给电脑上的 opencode 下指令…", style = MaterialTheme.typography.bodyMedium) },
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = OcColors.Line,
                unfocusedBorderColor = OcColors.Line2,
                focusedContainerColor = OcColors.Surface2,
                unfocusedContainerColor = OcColors.Surface2,
                // 显式钉死文字与光标颜色：不能依赖 MaterialTheme 的默认值，
                // 否则主题一旦被切成深色，这里会拿到近白的 onSurface，压在浅底上看不见。
                focusedTextColor = OcColors.Ink,
                unfocusedTextColor = OcColors.Ink,
                cursorColor = OcColors.Ink,
                focusedPlaceholderColor = OcColors.Ink3,
                unfocusedPlaceholderColor = OcColors.Ink3,
            ),
        )

        Spacer(Modifier.width(10.dp))

        val interaction = rememberPress()
        val scale = pressScale(interaction)
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(if (canSend) OcColors.Ink else OcColors.Line)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = canSend,
                ) { onSend() }
                .scale(scale),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "发送",
                tint = if (canSend) OcColors.Surface else OcColors.Ink3,
                modifier = Modifier.size(19.dp),
            )
        }
        }
    }
}

/**
 * 待发送附件的标签。
 *
 * 显示的是**真实路径**（去掉 file:// 前缀），因为用户要能一眼核对自己挂的是不是
 * 那一个文件 —— 只写文件名的话，同名文件根本分不出来。
 */
@Composable
private fun AttachmentChip(attachment: FileAttachmentDto, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(OcColors.Surface2)
            .border(1.dp, OcColors.Line, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Description,
            contentDescription = null,
            tint = OcColors.Ink2,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = attachment.name.ifBlank { "附件" },
                style = MaterialTheme.typography.bodySmall,
                color = OcColors.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = attachment.uri.substringAfter("file://"),
                style = MaterialTheme.typography.labelSmall,
                color = OcColors.Ink3,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = "移除",
            style = MaterialTheme.typography.labelSmall,
            color = OcColors.Ink3,
            modifier = Modifier.clickable { onRemove() },
        )
    }
}

private fun statusLabel(status: String?): String = when (status) {
    null, "idle" -> "已完成"
    "busy" -> "运行中"
    "retry" -> "重试中"
    "running" -> "运行中"
    "pending" -> "等待中"
    "completed" -> "已完成"
    "success" -> "成功"
    "error", "failed" -> "失败"
    else -> status
}
