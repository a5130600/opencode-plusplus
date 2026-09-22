package com.opencode.mobile.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: DeviceEntity)

    @Query("SELECT * FROM device ORDER BY last_seen_at DESC")
    fun observeAll(): Flow<List<DeviceEntity>>

    @Query("SELECT * FROM device ORDER BY last_seen_at DESC")
    suspend fun all(): List<DeviceEntity>

    @Query("SELECT * FROM device WHERE id = :id")
    suspend fun find(id: String): DeviceEntity?

    @Query("UPDATE device SET last_seen_at = :at, remote_version = :version WHERE id = :id")
    suspend fun markSeen(id: String, at: Long, version: String?)

    @Query("DELETE FROM device WHERE id = :id")
    suspend fun delete(id: String)

    /** 删设备时必须连带删它的会话与缓存索引，否则会留下孤儿数据。 */
    @Query("DELETE FROM session WHERE device_id = :deviceId")
    suspend fun deleteSessionsOf(deviceId: String)

    @Query("DELETE FROM message WHERE session_id IN (SELECT id FROM session WHERE device_id = :deviceId)")
    suspend fun deleteMessagesOf(deviceId: String)

    @Query("DELETE FROM workspace WHERE device_id = :deviceId")
    suspend fun deleteWorkspacesOf(deviceId: String)
}

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sessions: List<SessionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)

    @Query("SELECT * FROM session WHERE device_id = :deviceId ORDER BY COALESCE(updated_at, created_at, 0) DESC")
    fun observe(deviceId: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM session WHERE id = :id")
    suspend fun find(id: String): SessionEntity?

    @Query("UPDATE session SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String?)

    @Query("SELECT id FROM session")
    suspend fun allIds(): List<String>

    /**
     * 最近用过的会话（跨设备）。
     *
     * 待审批必须**按会话逐个问**：`/api/permission/request` 只覆盖服务端默认
     * location，项目在别的目录时那一条压根查不到。所以得先知道该盯哪些会话。
     * 只取最近若干个 —— 对全部历史会话挨个发请求没有意义。
     */
    @Query("SELECT * FROM session ORDER BY COALESCE(updated_at, created_at, 0) DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SessionEntity>

    /**
     * 会话身上记着各自的项目目录，按最近使用排序。
     *
     * 这是工作区列表的**主要来源** —— 服务端的 `/api/location` 只反映
     * "opencode 进程从哪个目录启动"，往往不是用户真正在做的项目
     * （比如桌面版从 C:\Users\xxx 启动，但项目在 Documents\Default Project）。
     */
    @Query(
        "SELECT workspace_path FROM session " +
            "WHERE device_id = :deviceId AND workspace_path IS NOT NULL AND workspace_path != '' " +
            "GROUP BY workspace_path ORDER BY MAX(updated_at) DESC"
    )
    suspend fun workspaceDirs(deviceId: String): List<String>

    @Query("DELETE FROM session WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM message WHERE session_id = :sessionId")
    suspend fun deleteMessages(sessionId: String)
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Query("SELECT * FROM message WHERE session_id = :sessionId ORDER BY sort_key ASC")
    fun observe(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM message WHERE session_id = :sessionId ORDER BY sort_key ASC")
    suspend fun list(sessionId: String): List<MessageEntity>

    /** SSE 的 part 事件只带 messageID，需要按 id 取回整条来合并。 */
    @Query("SELECT * FROM message WHERE id = :id")
    suspend fun find(id: String): MessageEntity?

    @Query("DELETE FROM message WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface WorkspaceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(workspaces: List<WorkspaceEntity>)

    @Query("SELECT * FROM workspace WHERE device_id = :deviceId ORDER BY last_used_at DESC, name ASC")
    fun observe(deviceId: String): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspace WHERE device_id = :deviceId ORDER BY last_used_at DESC, name ASC")
    suspend fun list(deviceId: String): List<WorkspaceEntity>

    @Query("SELECT * FROM workspace WHERE device_id = :deviceId AND path = :path")
    suspend fun find(deviceId: String, path: String): WorkspaceEntity?

    @Query("UPDATE workspace SET is_current = 0 WHERE device_id = :deviceId")
    suspend fun clearCurrent(deviceId: String)

    @Query("UPDATE workspace SET is_current = 1, last_used_at = :at WHERE device_id = :deviceId AND path = :path")
    suspend fun markCurrent(deviceId: String, path: String, at: Long)

    @Query("DELETE FROM workspace WHERE device_id = :deviceId AND path NOT IN (:keep)")
    suspend fun pruneExcept(deviceId: String, keep: List<String>)
}

@Dao
interface CachedFileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CachedFileEntity)

    @Query("SELECT * FROM cached_file WHERE blob_id = :blobId")
    suspend fun find(blobId: String): CachedFileEntity?

    @Query("SELECT * FROM cached_file WHERE blob_id = :blobId")
    fun observe(blobId: String): Flow<CachedFileEntity?>

    @Query("SELECT * FROM cached_file WHERE session_id = :sessionId ORDER BY remote_path ASC")
    fun observeBySession(sessionId: String): Flow<List<CachedFileEntity>>

    @Query("SELECT * FROM cached_file WHERE device_id = :deviceId ORDER BY accessed_at DESC")
    fun observeByDevice(deviceId: String): Flow<List<CachedFileEntity>>

    @Query("UPDATE cached_file SET accessed_at = :at WHERE blob_id = :blobId")
    suspend fun touch(blobId: String, at: Long)

    @Query("UPDATE cached_file SET state = :state, size = 0, content_hash = NULL WHERE blob_id = :blobId")
    suspend fun updateProgress(blobId: String, state: String)

    @Query(
        "UPDATE cached_file SET state = 'ready', local_path = :path, size = :bytes, " +
            "content_hash = :hash, fetched_at = :at WHERE blob_id = :blobId"
    )
    suspend fun markReady(blobId: String, path: String, bytes: Long, hash: String?, at: Long)

    /** 重取后内容没变：只刷新时间戳，省掉一次无谓的落盘替换。 */
    @Query("UPDATE cached_file SET fetched_at = :at, accessed_at = :at WHERE blob_id = :blobId")
    suspend fun markRefreshed(blobId: String, at: Long)

    @Query("UPDATE cached_file SET render_kind = :kind, render_path = :path WHERE blob_id = :blobId")
    suspend fun setRender(blobId: String, kind: String?, path: String?)

    @Query("UPDATE cached_file SET render_kind = NULL, render_path = NULL WHERE blob_id = :blobId")
    suspend fun clearRender(blobId: String)

    /** 离线保存 / 取消离线保存。路径随之在 cacheDir 与 filesDir 之间迁移。 */
    @Query("UPDATE cached_file SET pin = :pinned, local_path = :path WHERE blob_id = :blobId")
    suspend fun setPin(blobId: String, pinned: Boolean, path: String)

    @Query("DELETE FROM cached_file WHERE blob_id = :blobId")
    suspend fun delete(blobId: String)

    /** 只在预览缓存内做 LRU —— pin = 1 的离线文件永不出现。 */
    @Query("SELECT * FROM cached_file WHERE pin = 0 ORDER BY accessed_at ASC")
    suspend fun previewRowsByLru(): List<CachedFileEntity>

    @Query("SELECT * FROM cached_file WHERE pin = 1 ORDER BY accessed_at DESC")
    suspend fun pinnedRows(): List<CachedFileEntity>

    @Query("SELECT COUNT(*) FROM cached_file WHERE pin = 1")
    fun observePinnedCount(): Flow<Int>

    @Query("SELECT * FROM cached_file WHERE device_id = :deviceId")
    suspend fun rowsOfDevice(deviceId: String): List<CachedFileEntity>

    @Query("DELETE FROM cached_file WHERE device_id = :deviceId")
    suspend fun deleteOfDevice(deviceId: String)

    @Query("SELECT * FROM cached_file")
    suspend fun all(): List<CachedFileEntity>

    /** 全表观察 —— 用量条要跟着"缓存写进来了 / 被清掉了"实时变，不能只在启动时算一次。 */
    @Query("SELECT * FROM cached_file")
    fun observeAll(): Flow<List<CachedFileEntity>>
}
