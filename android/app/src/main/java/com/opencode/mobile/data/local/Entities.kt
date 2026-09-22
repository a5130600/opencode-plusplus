package com.opencode.mobile.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 电脑端设备。凭据本身不放这里，走 EncryptedSharedPreferences。 */
@Entity(tableName = "device")
data class DeviceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val scheme: String,
    val host: String,
    val port: Int,
    val username: String,
    /** tailscale | wireguard | lan */
    val transport: String,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long? = null,
    @ColumnInfo(name = "remote_version") val remoteVersion: String? = null,
)

/**
 * 本地缓存索引。
 *
 * [localPath] 一列同时覆盖两层：文件在 cacheDir（临时预览）或在 filesDir（离线保存）
 * 时都指过去。区分靠 [pin]，不靠路径 —— 这样"离线保存"只需 rename + 改这一列。
 *
 * 关于 [contentHash]：它是**下载完成之后**才算出来的，不是远端元数据。
 * 服务端不给我们 size/mtime，所以无法在下载前判断"内容变了没有"。
 * 这个哈希负责两件事：校验落盘完整性、以及下次重取时判断内容是否真的变了。
 */
@Entity(
    tableName = "cached_file",
    indices = [
        Index("device_id"),
        Index("session_id"),
        Index(value = ["pin", "accessed_at"]),
    ],
)
data class CachedFileEntity(
    @PrimaryKey @ColumnInfo(name = "blob_id") val blobId: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "session_id") val sessionId: String?,
    @ColumnInfo(name = "remote_path") val remotePath: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val ext: String,
    /** FileKind.name */
    val kind: String,
    /** 本地实际落盘字节数。state != ready 时是 0。 */
    val size: Long = 0L,
    /** 落盘内容的 sha256。文件被系统清掉或校验失败时置空。 */
    @ColumnInfo(name = "content_hash") val contentHash: String? = null,
    /** none | downloading | ready | failed */
    val state: String,
    @ColumnInfo(name = "local_path") val localPath: String? = null,
    @ColumnInfo(name = "render_kind") val renderKind: String? = null,
    @ColumnInfo(name = "render_path") val renderPath: String? = null,
    /** true = 用户显式离线保存，永不参与 LRU 淘汰 */
    val pin: Boolean = false,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long = 0L,
    @ColumnInfo(name = "accessed_at") val accessedAt: Long = 0L,
)

@Entity(
    tableName = "session",
    indices = [Index("device_id")],
)
data class SessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "device_id") val deviceId: String,
    val title: String,
    @ColumnInfo(name = "parent_id") val parentId: String? = null,
    /** idle | busy | retry | …（以服务端返回为准） */
    val status: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long? = null,
    @ColumnInfo(name = "workspace_path") val workspacePath: String? = null,
)

/**
 * 消息以原始 JSON 落库（payload），不做字段级拆解。
 * 理由：服务端 part 结构还在演进，拆解会让我们每次都要改表和迁移；
 * 存原文则 App 只需能渲染自己认识的部分，未知 part 直接跳过。
 */
@Entity(
    tableName = "message",
    indices = [Index("session_id")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val role: String,
    val payload: String,
    @ColumnInfo(name = "sort_key") val sortKey: Long,
)

/** 工作区 = 电脑上 opencode 打开过的项目目录。从 GET /project 同步。 */
@Entity(
    tableName = "workspace",
    primaryKeys = ["device_id", "path"],
)
data class WorkspaceEntity(
    @ColumnInfo(name = "device_id") val deviceId: String,
    val path: String,
    val name: String,
    val vcs: String? = null,
    @ColumnInfo(name = "is_current") val isCurrent: Boolean = false,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long? = null,
)

object CachedState {
    const val NONE = "none"
    const val DOWNLOADING = "downloading"
    const val READY = "ready"
    const val FAILED = "failed"
}

object RenderKind {
    const val PDF = "pdf"
    const val HTML = "html"
    const val TEXT = "text"
}
