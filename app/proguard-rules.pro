# CastHub ProGuard 规则

# jUPnP 通过反射实例化部分组件（ActionCallback / Service / StateVariable），
# 混淆后会导致 UPnP 动作无法绑定，因此保留整个包。
-keep class org.jupnp.** { *; }
-keep class org.eclipse.jetty.** { *; }
-dontwarn org.jupnp.**
-dontwarn org.eclipse.jetty.**
-dontwarn javax.enterprise.**
-dontwarn org.osgi.**

# 协议模块实现类由 app 层直接实例化，但保留以防未来改为反射注册
-keep class com.casthub.dlna.** { *; }
-keep class com.casthub.airplay.** { *; }
-keep class com.casthub.core.** { *; }

# Media3 / ExoPlayer
-dontwarn androidx.media3.**

# 保留 JNI 桥接方法名（AirPlay native 依赖精确签名匹配）
-keepclasseswithmembernames class com.casthub.airplay.AirPlayNativeBridge {
    native <methods>;
}
