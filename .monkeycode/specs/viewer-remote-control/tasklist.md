# 需求实施计划：观看方远程控制共享方

- [ ] 1. 实现 RemoteControlService 无障碍服务
  - [ ] 1.1 创建 `res/xml/accessibility_service_config.xml`：`canPerformGestures="true"`、`canRetrieveWindowContent="true"`、`accessibilityEventTypes="typeWindowStateChanged"`（设计文档 Components 第 4 节）
  - [ ] 1.2 创建 `RemoteControlService.kt`：静态单例引用、连接时缓存屏幕宽高、`execute(JSONObject)` 分发触摸（dispatchGesture）/按键（performGlobalAction）/文本（focused 节点 ACTION_SET_TEXT）、`isActive()` 判定（需求 R1/R3/R4/R5）
  - [ ] 1.3 `AndroidManifest.xml` 注册服务：`android:permission="BIND_ACCESSIBILITY_SERVICE"` + intent-filter + meta-data 指向配置文件（需求 R1-1）

- [ ] 2. 控制指令协议与通道分发（WebRTCPeer.kt）
  - [ ] 2.1 改造共享方 `registerControlObserver` 分发：`type=="fps"` 保持原帧率逻辑，其余指令转交 `RemoteControl.execute`，回发错误提示（需求 R3/R4/R5）
  - [ ] 2.2 暴露共享方回发能力：控制通道 `sendMessage` 供「无障碍未开启/文本失败/已停止控制」回发（需求 R2-4/R7-2）
  - [ ] 2.3 观看方注册 `controlListener` 接收回发提示并转交 UI（需求 R2-4）

- [ ] 3. 观看方控制面板与触摸捕获（MainActivity.kt + activity_main.xml）
  - [ ] 3.1 `activity_main.xml` 在 `llVideoBtns` 新增控制开关、返回/主页/最近任务、文本输入按钮（需求 R2-1/R4）
  - [ ] 3.2 控制模式显式开关：开启后单指触摸→归一化坐标→`sendControl` 发 touch 指令；双指仍走本地缩放；非控制模式保持原手势（需求 R2-1/R3-1）
  - [ ] 3.3 触摸序列映射：DOWN/MOVE/UP 依次发送，含 fit 黑边内容矩形过滤与铺满裁切坐标换算（需求 R3-5/R6）
  - [ ] 3.4 系统按键与文本输入：点按按钮发送 key/text 指令，文本用 Dialog 输入（需求 R4/R5）
  - [ ] 3.5 通道未就绪时控制开关禁用；断开连接自动退出控制模式（需求 R2-3/R2-5）
  - [ ] 3.6 坐标归一化/还原纯函数单元测试（fit 黑边、铺满裁切、方向）

- [ ] 4. 共享方控制状态与引导（MainActivity.kt + activity_main.xml）
  - [ ] 4.1 共享界面状态卡片：无障碍开启→「远程控制已就绪」；未开启→提示 + 一键跳转 `Settings.ACTION_ACCESSIBILITY_SETTINGS`（需求 R1-2/R1-3）
  - [ ] 4.2 「停止远程控制」开关：关闭后忽略全部控制指令并回发提示（需求 R7-2）

- [ ] 5. 检查点 - 确保构建通过，如有疑问请询问用户
  - 运行 `./gradlew assembleDebug` 无编译错误，控制相关新资源引用完整

- [ ] 6. 发布新版本
  - [ ] 6.1 版本号升为 v1.71（versionCode 72），更新 `download-server.js` RELEASE_CONFIG changelog
  - [ ] 6.2 构建签名、部署公网下载服务器、验证 version.json、提交推送 git
