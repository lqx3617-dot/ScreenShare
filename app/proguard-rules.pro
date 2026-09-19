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

# ViewBinding：生成的 Binding 类由布局反射 inflate，类名/字段名必须保留
-keep class com.screenshare.databinding.** { *; }

# Fragment：FragmentManager 按类名反射实例化/进程重建恢复，必须保留类名与构造
-keep class com.screenshare.*Fragment { *; }
-keep class com.screenshare.*Fragment$* { *; }

# RecyclerView Adapter + ViewHolder：onCreateViewHolder 反射 inflate item binding
-keep class com.screenshare.*Adapter { *; }
-keep class com.screenshare.*Adapter$* { *; }

# Application 入口
-keep class com.screenshare.App { *; }

# 枚举反射（valueOf/values）
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Parcelable 序列化字段
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# 保留泛型签名（JSON 解析/反射需要）
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# ==================== WebRTC 回调实现类（native 按方法名 JNI 回调）====================
# org.webrtc 包本身已 keep，但应用侧实现的回调接口（PeerConnection.Observer、
# VideoSink、CapturerObserver 等）由 native 层通过 JNI 按类名+方法名查找。
# 混淆后方法名消失 → native 回调失败 → 进程被杀且 Java 崩溃日志捕获不到
# （v1.213/v1.324/v1.325 的 create 房间后 native 崩溃根因）。
# 必须保留所有实现 org.webrtc 接口的类名与成员，以及直接操作 native 的类。
-keep class com.screenshare.WebRTCPeer { *; }
-keep class com.screenshare.AppEglBase { *; }
-keep class com.screenshare.ScreenCapturerFactory { *; }
-keep class com.screenshare.SignalManager { *; }
-keep class com.screenshare.MainActivity { *; }
-keep class com.screenshare.MainActivity$* { *; }
-keepclassmembers class * implements org.webrtc.VideoSink { *; }
-keepclassmembers class * implements org.webrtc.CapturerObserver { *; }
-keepclassmembers class * implements org.webrtc.PeerConnection$Observer { *; }
-keepclassmembers class * implements org.webrtc.AudioDeviceModule$AudioRecordSamplesCallback { *; }

# ==================== ActivityResult 机制（v1.324 崩溃根因）====================
# ActivityResultContracts/ActivityResultLauncher 被 R8 改名（如
# StartActivityForResult -> c.d）后，进程重建时 ActivityResultRegistry 无法
# 按原 key 恢复 launcher 绑定，运行时抛 IllegalStateException:
# "Attempting to launch an unregistered ActivityResultLauncher"。
# 库自带 consumer rules 只保护了 ActivityResultRegistry 本体，contract/launcher
# 各层级必须显式 keep。
-keep class androidx.activity.result.ActivityResultLauncher { *; }
-keep class androidx.activity.result.ActivityResultLauncher$* { *; }
-keep class androidx.activity.result.ActivityResultRegistry { *; }
-keep class androidx.activity.result.ActivityResultRegistry$* { *; }
-keep class androidx.activity.result.contract.ActivityResultContract { *; }
-keep class androidx.activity.result.contract.ActivityResultContract$* { *; }
-keep class androidx.activity.result.contract.ActivityResultContracts$* { *; }

# ==================== 兜底方案 ====================
# v1.324/325/326 三轮 keep 规则均未消除崩溃：create 房间 native 崩溃 +
# ActivityResultLauncher unregistered（contract 已恢复原名仍崩）。
# 结论：根因不在"类名改名"，而在 R8 的代码移动/裁剪/方法处理对 JNI 注册表与
# ActivityResultRegistry 恢复链路的破坏，逐个 keep 无法穷尽。
# 改用 -dontobfuscate：保留无用代码与资源裁剪（isShrinkResources/minify 的
# 体积与攻击面收益），彻底不做任何名称改写，零混淆风险。
# 上述 keep 规则保留（-dontobfuscate 下无害，若将来重新启用混淆可作参考）。
-dontobfuscate
