# ==================== WebRTC SDK 混淆规则（R8 必配） ====================
# WebRTC 依赖大量 JNI 与反射，且 native 层按 @CalledByNative 注解的
# 方法签名回调 Java 代码。只 keep 类名不够——必须同时：
#   1) 保留全部 org.webrtc 类与成员（含方法名不被改写）
#   2) 保留 @CalledByNative 注解本身（防止注解被 R8 移除后回调失联）
#   3) 保留所有带 @CalledByNative 注解的方法/字段（JNI 回调入口）
#   4) 不警告缺失引用（部分类在特定 ABI/运行时才存在）

# 保留整个 WebRTC SDK
-keep class org.webrtc.** { *; }
-keep interface org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }

# 保留 @CalledByNative 注解类
-keep @org.webrtc.CalledByNative class * { *; }
-keepclassmembers class * {
    @org.webrtc.CalledByNative <methods>;
}
-keepclassmembers class * {
    @org.webrtc.CalledByNative <fields>;
}
-keep class org.webrtc.CalledByNative { *; }
-keep class org.webrtc.CalledByNative$CalledByNativeUnchecked { *; }

# 避免对 WebRTC 内部做激进优化（方法内联等可能破坏 JNI 回调）
-dontwarn org.webrtc.**
-dontoptimize org.webrtc.**
-keepattributes *Annotation*

# 保留全部 WebRTC 类
-keep class org.webrtc.** { *; }
# 保留 JNI 回调注解（native 侧通过方法名查找 Java 方法）
-keep @org.webrtc.CalledByNative class * { *; }
# 保留所有 JNI 回调方法名
-keepclassmembers class * {
    @org.webrtc.CalledByNative <methods>;
}
# 禁止优化 WebRTC 内部（防内联/改写破坏 JNI 注册表）
-dontoptimize

# 保留所有 native 方法名（JNI RegisterNatives 按方法名绑定，必须原名）
-keepclasseswithmembers class * {
    native <methods>;
}

# ==================== 应用内被反射/JNI 引用的类 ====================
# 无障碍服务由系统通过 manifest 名称加载，需保留
-keep class com.screenshare.RemoteControlService { *; }

# ==================== 兜底方案（如仍闪退，取消下行注释改用此模式） ====================
# 只删无用代码、不改类名/方法名：体积仍能缩小，且绝无混淆问题
# -dontobfuscate
