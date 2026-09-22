package com.opencode.mobile.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        DeviceEntity::class,
        CachedFileEntity::class,
        SessionEntity::class,
        MessageEntity::class,
        WorkspaceEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class OpenCodeDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun cachedFileDao(): CachedFileDao
}
