# ==================== WebRTC SDK 混淆规则 ====================
# WebRTC 依赖大量 JNI 与反射（PeerConnectionFactory 等），必须完整保留
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { *; }
-keep class org.webrtc.voiceengine.** { *; }

# ==================== 应用内被反射/JNI 引用的类 ====================
# 无障碍服务由系统通过 manifest 名称加载，需保留
-keep class com.screenshare.RemoteControlService { *; }

# okhttp / androidx 自带 keep 规则（依赖库的 consumer rules 自动生效）
