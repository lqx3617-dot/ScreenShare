# ==================== 相册查看 APP 混淆规则 ====================
# WebRTC 未在此模块使用，主要保留被反射引用的类
-keep class com.screenshare.albumviewer.MainActivity { *; }

# coil / okhttp / androidx 自带 keep 规则
