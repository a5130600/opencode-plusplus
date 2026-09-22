package com.opencode.mobile.data.repository

import com.opencode.mobile.core.AppResult
import com.opencode.mobile.core.IoDispatcher
import com.opencode.mobile.core.resultOf
import com.opencode.mobile.data.local.SessionDao
import com.opencode.mobile.data.local.WorkspaceDao
import com.opencode.mobile.data.local.WorkspaceEntity
import com.opencode.mobile.data.remote.OpenCodeApi
import com.opencode.mobile.data.remote.dto.ProjectDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工作区 = 电脑上 opencode 打开过的项目目录。
 *
 * v2 里：
 *   - `GET /api/project` 列出项目（字段 id / directory / canonical）
 *   - `GET /api/location` 给出**当前**位置，其 project.id 就是当前项目
 *   - 没有"切换电脑当前项目"的端点，所以"选中的工作区"仍是本地状态
 */
@Singleton
class WorkspaceRepository @Inject constructor(
    private val dao: WorkspaceDao,
    private val sessionDao: SessionDao,
    private val api: OpenCodeApi,
    private val json: Json,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    fun observe(deviceId: String): Flow<List<WorkspaceEntity>> = dao.observe(deviceId)

    suspend fun current(deviceId: String): WorkspaceEntity? = withContext(io) {
        val all = dao.list(deviceId)
        all.firstOrNull { it.isCurrent } ?: all.firstOrNull()
    }

    suspend fun sync(deviceId: String): AppResult<List<WorkspaceEntity>> = withContext(io) {
        // 三个来源，优先级从高到低：
        //   1. 会话身上的 workspace_path —— 用户真正在做的项目（最准，也是他要的那个）
        //   2. /api/project —— 服务端记过的项目列表
        //   3. /api/location —— 只是"opencode 进程的工作目录"，经常是 C:\Users\xxx，不可靠
        //
        // 上一版把 location 排第一，结果工作区被定位到了 C 盘用户目录 ——
        // 因为桌面版就是从那儿启动的，而项目其实在 Documents\Default Project。
        val sessionDirs = sessionDao.workspaceDirs(deviceId)

        val listed = when (val r = resultOf { api.listProjects() }) {
            is AppResult.Ok -> parseProjects(r.value)
            // 项目列表取不到不算失败 —— 会话里已经有目录了
            is AppResult.Err -> emptyList()
        }

        val location = runCatching { api.location() }.getOrNull()
        val locationDir = location?.let { loc ->
            (loc.project?.directory?.ifBlank { null } ?: loc.directory).ifBlank { null }
        }

        val projects = buildList {
            sessionDirs.forEach { add(ProjectDto(id = it, directory = it, canonical = it)) }
            listed.forEach { add(it) }
            location?.let { loc ->
                locationDir?.let { dir ->
                    add(
                        ProjectDto(
                            id = loc.project?.id ?: dir,
                            directory = dir,
                            canonical = loc.project?.canonical ?: dir,
                        )
                    )
                }
            }
        }.distinctBy { it.directory }

        val previous = dao.list(deviceId).associateBy { it.path }

        // 当前工作区 = 最近用过的那个项目目录（会话列表第一条的目录），
        // 而不是 location —— location 是进程目录，用户已经明确指出它不对。
        val preferred = sessionDirs.firstOrNull()
            ?: listed.firstOrNull()?.directory
            ?: locationDir

        val entities = projects.mapNotNull { project ->
            // 项目对象里装路径的字段是 canonical，不是 directory（见 Dtos.ProjectDto 的注释）
            val path = project.pathOrEmpty.trim().ifBlank { return@mapNotNull null }
            val fallbackName =
                path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
            WorkspaceEntity(
                deviceId = deviceId,
                path = path,
                name = project.name?.takeIf { it.isNotBlank() } ?: fallbackName.ifBlank { path },
                vcs = project.vcs?.takeIf { it.isNotBlank() },
                isCurrent = path == preferred,
                lastUsedAt = previous[path]?.lastUsedAt,
            )
        }

        dao.upsertAll(entities)
        if (entities.isNotEmpty()) dao.pruneExcept(deviceId, entities.map { it.path })

        // 没有任何一条被标为当前（比如会话全删了），退到第一条，避免界面空白
        if (dao.list(deviceId).none { it.isCurrent }) {
            entities.firstOrNull()?.let { markCurrent(deviceId, it.path) }
        }

        if (entities.isEmpty()) AppResult.Err("电脑端没有报出工作目录") else AppResult.Ok(entities)
    }

    suspend fun select(deviceId: String, path: String) = withContext(io) {
        markCurrent(deviceId, path)
    }

    private suspend fun markCurrent(deviceId: String, path: String) {
        dao.clearCurrent(deviceId)
        dao.markCurrent(deviceId, path, System.currentTimeMillis())
    }

    suspend fun listLocal(deviceId: String): List<WorkspaceEntity> = withContext(io) {
        dao.list(deviceId)
    }

    /**
     * 解析 `/api/project` 的响应。
     *
     * **两种形状都认**：契约里它是裸数组，实际抓到的版本又包了一层 `{location,data}`。
     * 只认一种的代价是解析直接抛异常、整份列表被静默吞成空 ——
     * 界面上的表现就是"点了刷新，电脑上明明有的工作目录就是不出来"。
     */
    private fun parseProjects(raw: JsonElement): List<ProjectDto> {
        val array = raw as? JsonArray
            ?: (raw as? JsonObject)?.get("data") as? JsonArray
            ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(ProjectDto.serializer(), element) }.getOrNull()
        }
    }
}
