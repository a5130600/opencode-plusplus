package com.opencode.mobile

import android.app.Application
import com.opencode.mobile.data.repository.ConnectionRepository
import com.opencode.mobile.data.repository.DeviceRepository
import com.opencode.mobile.data.storage.CacheEvictor
import com.opencode.mobile.data.storage.StorageLayout
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

@HiltAndroidApp
class OpenCodeApp : Application() {

    @Inject lateinit var deviceRepository: DeviceRepository
    @Inject lateinit var connectionRepository: ConnectionRepository
    @Inject lateinit var cacheEvictor: CacheEvictor
    @Inject lateinit var storageLayout: StorageLayout
    @Inject @Named("application_scope") lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()

        appScope.launch {
            // 目录必须先建好：BlobStore 的所有写入都假设它们存在
            storageLayout.ensureDirs()

            // 回到进程 / 回到前台时执行一次配额淘汰。
            // 放在这里而不是"写入时才发现"——避免用户在一次点击里等一次清理。
            cacheEvictor.enforceQuota()

            // 恢复上次使用的设备，并启动长连接
            val restored = deviceRepository.restoreActive()
            if (restored != null) {
                connectionRepository.start()
            }
        }
    }
}
