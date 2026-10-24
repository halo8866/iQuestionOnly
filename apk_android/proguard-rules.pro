# ============================================
# 题梦 ProGuard / R8 混淆规则
# ============================================

# ---- 应用自身数据模型（Gson 序列化 / Room 实体）----
# Gson 反射访问字段名，必须保留模型类的字段名
-keep class com.iquestion.app.model.** { *; }
# Room 实体、DAO、POJO 由 Room 框架反射/注解处理访问
-keep class com.iquestion.app.data.** { *; }

# ---- Gson ----
# Gson 使用反射序列化，保留泛型签名与注解
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
# Gson 内部类
-keep class com.google.gson.** { *; }
-keep class sun.misc.Unsafe { *; }
# Gson TypeToken 匿名子类：Gson 构造时通过反射 getGenericSuperclass() 读取泛型参数，
# 混淆/优化会使匿名子类丢失泛型签名导致反射失败，必须保留所有 TypeToken 子类
-keep class * extends com.google.gson.reflect.TypeToken { *; }
# Gson @SerializedName 注解的字段（配合 model 包 keep 已覆盖，此处兜底）
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# ---- Kotlin 协程 ----
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ---- AndroidX / Material（官方库自带 consumer rules，此处仅兜底）----
-dontwarn androidx.**
-dontwarn com.google.android.material.**
-keep class com.google.android.material.** { *; }

# ---- 通用：保留 View 构造、枚举、Parcelable ----
-keepclassmembers class * extends android.app.Activity
-keepclassmembers class * extends androidx.appcompat.app.AppCompatActivity {
    <init>(...);
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# ---- 移除日志（可选）：关闭 verbose/debug 日志输出 ----
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
