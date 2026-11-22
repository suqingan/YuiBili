# YuiBili R8 / ProGuard 压缩与混淆规则

# 保留所有通过 .name / valueOf() 持久化或传递的枚举项名称
-keepclassmembers enum com.yuilittle.bili.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Android 回调与 Manifest 声明组件由 AGP 自动保留；显式保护自定义 View 与 Service 构造
-keep class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Media3 / OkHttp / Kotlin 协程可选依赖防告警（实际未引入可选模块）
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.**
-dontwarn kotlin.annotations.jvm.**

# 保留堆栈行号便于真机排查，去掉源码文件名映射信息不影响运行
-keepattributes SourceFile,LineNumberTable
