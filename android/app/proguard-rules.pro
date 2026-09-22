# ---- kotlinx.serialization ----
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.opencode.mobile.data.remote.dto.** {
    *** Companion;
}
-keepclasseswithmembers class com.opencode.mobile.data.remote.dto.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.opencode.mobile.data.remote.dto.**$$serializer { *; }

# ---- Retrofit / OkHttp ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# ---- Hilt ----
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# ---- 保留 SSE 事件模型（反射反序列化） ----
-keep class com.opencode.mobile.data.remote.sse.** { *; }
