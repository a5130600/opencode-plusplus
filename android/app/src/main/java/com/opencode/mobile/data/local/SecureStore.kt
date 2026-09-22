package com.opencode.mobile.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class Credential(val username: String, val password: String)

/**
 * 设备凭据与少量本地偏好。
 *
 * 用 EncryptedSharedPreferences（AES-256-GCM，密钥在 Android Keystore 里），
 * 凭据**绝不落明文**。
 *
 * 如果这里解密失败（换机、Keystore 被重置、用户清了数据），
 * 一律当作"没有凭据"处理并让用户重新配对 —— 绝不能让 App 崩在启动路径上。
 */
@Singleton
class SecureStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private companion object {
        const val FILE = "opencode_secure"
        const val KEY_USERNAME = "username_"
        const val KEY_PASSWORD = "password_"
        const val KEY_ACTIVE_DEVICE = "active_device_id"
        const val KEY_DEFAULT_MODEL = "default_model"
        const val KEY_OFFLINE_PIN = "offline_pin_enabled"
    }

    private val prefs: SharedPreferences? by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ) as SharedPreferences
        }.getOrNull()
    }

    fun putCredential(deviceId: String, username: String, password: String) {
        prefs?.edit()
            ?.putString(KEY_USERNAME + deviceId, username)
            ?.putString(KEY_PASSWORD + deviceId, password)
            ?.apply()
    }

    fun credential(deviceId: String): Credential? {
        val store = prefs ?: return null
        val username = store.getString(KEY_USERNAME + deviceId, null) ?: return null
        val password = store.getString(KEY_PASSWORD + deviceId, null) ?: return null
        return Credential(username, password)
    }

    fun removeCredential(deviceId: String) {
        prefs?.edit()
            ?.remove(KEY_USERNAME + deviceId)
            ?.remove(KEY_PASSWORD + deviceId)
            ?.apply()
    }

    var activeDeviceId: String?
        get() = prefs?.getString(KEY_ACTIVE_DEVICE, null)
        set(value) {
            prefs?.edit()?.apply {
                if (value == null) remove(KEY_ACTIVE_DEVICE) else putString(KEY_ACTIVE_DEVICE, value)
            }?.apply()
        }

    /** 格式："providerID/modelID"，空表示用服务端默认。 */
    var defaultModel: String?
        get() = prefs?.getString(KEY_DEFAULT_MODEL, null)
        set(value) {
            prefs?.edit()?.apply {
                if (value.isNullOrBlank()) remove(KEY_DEFAULT_MODEL) else putString(KEY_DEFAULT_MODEL, value)
            }?.apply()
        }

    /** 是否对离线文件启用加密（v1 默认关，开关位置先留出来）。 */
    var offlineEncryptionEnabled: Boolean
        get() = prefs?.getBoolean(KEY_OFFLINE_PIN, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_OFFLINE_PIN, value)?.apply()
        }
}
