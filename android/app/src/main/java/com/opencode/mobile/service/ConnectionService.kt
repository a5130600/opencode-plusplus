package com.opencode.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.opencode.mobile.MainActivity
import com.opencode.mobile.R
import com.opencode.mobile.core.AppResult
import com.opencode.mobile.core.ConnectionStatus
import com.opencode.mobile.core.PermissionResponses
import com.opencode.mobile.data.remote.dto.FormDto
import com.opencode.mobile.data.remote.dto.PermissionDto
import com.opencode.mobile.data.repository.ConnectionRepository
import com.opencode.mobile.data.repository.PendingRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 常驻前台服务。
 *
 * 它存在的**真正理由不是"保持连接"这个技术指标**，而是：
 * 电脑端发起权限请求时，你必须能收到通知并一键批准。
 *
 * 远程场景下错过一次审批 = 任务卡死在那里等你，而你看不到电脑屏幕。
 * 所以这个服务的核心职责是「不让你错过一次审批」，
 * 保活只是达成这个目的的手段。
 */
@AndroidEntryPoint
class ConnectionService : LifecycleService() {

    companion object {
        const val CHANNEL_CONNECTION = "oc_connection"
        const val CHANNEL_PERMISSION = "oc_permission"
        const val CHANNEL_QUESTION = "oc_question"
        const val NOTIF_CONNECTION_ID = 1001
        /** 审批结果的回显通知，固定一个 id 反复用（自动消失，不占地方）。 */
        const val NOTIF_RESULT_ID = 1002

        const val ACTION_START = "com.opencode.mobile.START"
        const val ACTION_STOP = "com.opencode.mobile.STOP"
        const val ACTION_APPROVE = "com.opencode.mobile.APPROVE"
        const val ACTION_ALWAYS = "com.opencode.mobile.ALWAYS"
        const val ACTION_REJECT = "com.opencode.mobile.REJECT"

        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_PERMISSION_ID = "permission_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        fun start(context: Context) {
            val intent = Intent(context, ConnectionService::class.java).setAction(ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        /**
         * 停止长连接并下线服务。
         *
         * 这里**不**直接调 connection.stop()：`connection` 是实例属性
         * （Hilt 注入的），companion object 是静态的，根本拿不到它 ——
         * 之前那行会直接编译不过。
         * 正确做法是交给服务自己的 ACTION_STOP 分支去收尾（见 onStartCommand），
         * 那里既有实例、又保证在同一个生命周期里停干净。
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
        }

        private var permissionNotificationSeq = 2000
        fun nextPermissionNotificationId(): Int = permissionNotificationSeq++
    }

    @Inject lateinit var connection: ConnectionRepository
    @Inject lateinit var pending: PendingRepository

    private val manager by lazy { NotificationManagerCompat.from(this) }

    /** permissionId → 通知 id。同一条审批始终用同一个 id，避免刷出多条。 */
    private val permissionIds = mutableMapOf<String, Int>()

    /** formId → 通知 id。同上。 */
    private val formIds = mutableMapOf<String, Int>()

    override fun onCreate() {
        super.onCreate()
        createChannels()
        serviceScopeLaunch()
        // 待审批 / 待回答的轮询由数据源统一做（服务与界面共用一份），服务只负责把它变成通知
        pending.start()
    }

    private fun serviceScopeLaunch() {
        var started = false
        lifecycleScope.launch {
            connection.status.collect { status ->
                if (!started) {
                    started = true
                    startForegroundCompat(buildConnectionNotification(status))
                } else {
                    post(NOTIF_CONNECTION_ID, buildConnectionNotification(status))
                }
            }
        }
        lifecycleScope.launch {
            pending.permissions.collect { list -> syncPermissionNotifications(list) }
        }
        lifecycleScope.launch {
            pending.forms.collect { list -> syncFormNotifications(list) }
        }
    }

    /**
     * 提问（form）的通知。
     *
     * 这里**刻意不加操作按钮**：问题是有选项的，一两句话塞不进通知栏，
     * 硬塞只会让人盲选。通知的作用只是"喊你回来"，作答这件事交给 App 里的弹窗。
     */
    private fun syncFormNotifications(list: List<FormDto>) {
        val live = list.map { it.id }.toSet()
        (formIds.keys - live).forEach { gone ->
            formIds.remove(gone)?.let { manager.cancel(it) }
        }
        list.forEach { form ->
            if (formIds.containsKey(form.id)) return@forEach
            val notifId = nextPermissionNotificationId()
            formIds[form.id] = notifId
            postQuestionNotification(form, notifId)
        }
    }

    /**
     * 把"当前待审批"这份数据映射成通知。
     *
     * 两个方向都要做：新的要发，**已经不在列表里的要撤** ——
     * 在手机上（或通知上）批过之后，服务端那条就没了，
     * 不撤的话通知栏会永远杵着一条点了没反应的"待审批"。
     */
    private fun syncPermissionNotifications(list: List<PermissionDto>) {
        val live = list.map { it.id }.toSet()
        (permissionIds.keys - live).forEach { gone ->
            permissionIds.remove(gone)?.let { manager.cancel(it) }
        }
        list.forEach { permission ->
            if (permissionIds.containsKey(permission.id)) return@forEach
            val notifId = nextPermissionNotificationId()
            permissionIds[permission.id] = notifId
            postPermissionNotification(permission, notifId)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                connection.stop()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_APPROVE -> handleDecision(intent, PermissionResponses.ALLOW)
            ACTION_ALWAYS -> handleDecision(intent, PermissionResponses.ALWAYS)
            ACTION_REJECT -> handleDecision(intent, PermissionResponses.REJECT)
            else -> {
                // 兜底：即使 UI 没调 start()，服务被拉起也能自愈
                startForegroundCompat(buildConnectionNotification(ConnectionStatus.CONNECTING))
                connection.start()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    /**
     * 服务被销毁 = 长连接该断。
     *
     * 这一步不能省：`context.stopService()` 走的是 onDestroy，**不会**经过
     * onStartCommand 的 ACTION_STOP 分支。而 ConnectionRepository 是
     * application scope 下的单例，服务没了它照样活着 ——
     * 少了这里，用户点了"断开"SSE 还会安静地连着，白耗电。
     */
    override fun onDestroy() {
        if (this::pending.isInitialized) {
            pending.stop()
            permissionIds.values.forEach { manager.cancel(it) }
            formIds.values.forEach { manager.cancel(it) }
        }
        if (this::connection.isInitialized) connection.stop()
        super.onDestroy()
    }

    /**
     * 从通知直达审批。这是"一次点击完成决策"的落点：
     * 用户不需要先打开 App 再找那个卡片。
     *
     * 决策统一走 PermissionRepository：失败时它会把这条**留**在待审批列表里，
     * 于是下一轮轮询通知会自己再冒出来，用户能重试 —— 而不是悄悄没了。
     */
    private fun handleDecision(intent: Intent, decision: String) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val permissionId = intent.getStringExtra(EXTRA_PERMISSION_ID) ?: return

        lifecycleScope.launch {
            val result = pending.replyPermission(sessionId, permissionId, decision)

            // 不管成功失败都先撤掉"待审批"通知：成功的话它本来就该消失，
            // 失败的话结果回显会说明；撤掉是为了不让通知栏留一条点了没反应的旧通知。
            permissionIds.remove(permissionId)?.let { manager.cancel(it) }

            val text = when {
                result is AppResult.Ok && decision == PermissionResponses.REJECT ->
                    getString(R.string.decision_rejected)
                result is AppResult.Ok && decision == PermissionResponses.ALWAYS ->
                    getString(R.string.decision_approved_always)
                result is AppResult.Ok -> getString(R.string.decision_approved)
                else -> getString(R.string.decision_failed)
            }
            // 结果必须有回显：用户在远程看不到电脑屏幕，视觉反馈是唯一的确认
            post(
                NOTIF_RESULT_ID,
                NotificationCompat.Builder(this@ConnectionService, CHANNEL_PERMISSION)
                    .setSmallIcon(R.drawable.ic_stat_opencode)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText(text)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    private fun postPermissionNotification(permission: PermissionDto, notifId: Int) {
        val title = permission.summary.ifBlank { getString(R.string.notif_permission_title) }

        // SINGLE_TOP：Activity 已在栈顶时走 onNewIntent 而不是重建，
        // 否则深链（跳到这条审批所属的会话）会被丢掉。
        val contentIntent = PendingIntent.getActivity(
            this,
            notifId,
            Intent(this, MainActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                .putExtra(EXTRA_SESSION_ID, permission.sessionID)
                .putExtra(EXTRA_PERMISSION_ID, permission.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_PERMISSION)
            .setSmallIcon(R.drawable.ic_stat_opencode)
            .setContentTitle(getString(R.string.notif_permission_title))
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(permission.detail))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(false)
            .setOngoing(false)
            .setContentIntent(contentIntent)
            .addAction(
                R.drawable.ic_action_deny,
                getString(R.string.action_reject),
                decisionIntent(ACTION_REJECT, permission, notifId),
            )
            .addAction(
                R.drawable.ic_action_allow,
                getString(R.string.action_approve),
                decisionIntent(ACTION_APPROVE, permission, notifId),
            )
            .addAction(
                R.drawable.ic_action_allow,
                getString(R.string.action_approve_always),
                decisionIntent(ACTION_ALWAYS, permission, notifId),
            )

        post(notifId, builder.build())
    }

    private fun postQuestionNotification(form: FormDto, notifId: Int) {
        val fields = form.visibleFields
        val body = if (fields.isEmpty()) {
            form.title
        } else {
            form.title + "\n" + fields.joinToString("\n") { it.title.ifBlank { it.key } }
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            notifId,
            Intent(this, MainActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                .putExtra(EXTRA_SESSION_ID, form.sessionID),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        post(
            notifId,
            NotificationCompat.Builder(this, CHANNEL_QUESTION)
                .setSmallIcon(R.drawable.ic_stat_opencode)
                .setContentTitle(getString(R.string.notif_question_title))
                .setContentText(form.title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(false)
                .setOngoing(false)
                .setContentIntent(contentIntent)
                .build(),
        )
    }

    private fun decisionIntent(
        action: String,
        permission: PermissionDto,
        notifId: Int,
    ): PendingIntent {
        val intent = Intent(this, ConnectionService::class.java)
            .setAction(action)
            .putExtra(EXTRA_SESSION_ID, permission.sessionID)
            .putExtra(EXTRA_PERMISSION_ID, permission.id)
            .putExtra(EXTRA_NOTIFICATION_ID, notifId)
        return PendingIntent.getService(
            this,
            action.hashCode() + notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildConnectionNotification(status: ConnectionStatus): Notification {
        val text = when (status) {
            ConnectionStatus.CONNECTED -> getString(R.string.conn_connected)
            ConnectionStatus.CONNECTING -> getString(R.string.conn_connecting)
            ConnectionStatus.RECONNECTING -> getString(R.string.conn_reconnecting)
            ConnectionStatus.DISCONNECTED -> getString(R.string.conn_offline)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_stat_opencode)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .build()
    }

    /** 通知可能被用户在系统设置里关掉 —— 那种情况下发通知会抛异常，不能让它带崩服务。 */
    private fun post(id: Int, notification: Notification) {
        runCatching { manager.notify(id, notification) }
    }

    private fun startForegroundCompat(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIF_CONNECTION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CONNECTION,
                getString(R.string.notif_channel_connection),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.notif_channel_connection_desc) },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PERMISSION,
                getString(R.string.notif_channel_permission),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.notif_channel_permission_desc)
                // 这个渠道里的每一条都是"电脑端在等你"，必须能弹出横幅 + 响铃
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_QUESTION,
                getString(R.string.notif_channel_question),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.notif_channel_question_desc)
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }
}

/**
 * 便于 UI 层启动/停止服务。
 */
object ConnectionServiceLauncher {
    fun ensureRunning(context: Context, shouldRun: Boolean) {
        if (shouldRun) ConnectionService.start(context) else ConnectionService.stop(context)
    }
}
