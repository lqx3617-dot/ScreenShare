# 需求实施计划：会议式两级界面（Meeting Room UI）

设计文档：`design.md`（2026-08-12）

- [ ] 1. 新增会议连接页 MeetingActivity
  - [ ] 1.1 创建 `layout/activity_meeting.xml`
    - 实现需求 1 AC1：品牌标识 + 「创建房间」按钮 + 内嵌 4 位会议号输入框 + 「加入会议」按钮
    - 复用现有深色科技感风格与 liquid glass drawable，底部保留「检查更新」入口
  - [ ] 1.2 创建 `MeetingActivity.kt`
    - 实现需求 1 AC2/AC3：点击创建生成会议号跳转 MainActivity(action=create)；输入框回车/确认触发加入校验（复用 validateSignalCode 逻辑）后跳转 action=join
    - 实现需求 5 AC1 配套：跳转后 `finish()` 自身，返回键退出 App
  - [ ] 1.3 AndroidManifest 变更
    - MeetingActivity 注册 MAIN/LAUNCHER + `screenshare://join` VIEW intent-filter
    - MainActivity 移除 MAIN/LAUNCHER intent-filter（保留 VIEW 过滤）

- [ ] 2. MainActivity 会议入口改造
  - [ ] 2.1 新增会议 Intent 解析
    - 伴生对象定义 `EXTRA_MEETING_ACTION`（create/join）与 `EXTRA_MEETING_CODE`
    - 实现 `handleMeetingIntent(intent)`：action=create 复用 onSignalHostClicked 等价逻辑（用 intent 的 code），action=join 复用 joinMeetingWithCode；无会议 intent 时 finish 回 Launcher
    - onCreate 与 onNewIntent 接入分流（含分享链接 screenshare://join 冷启动）
  - [ ] 2.2 会议室全屏沉浸
    - 进入会议后隐藏系统状态栏/导航栏（沉浸模式），隐藏 llTitle 与 llStatus 堆叠布局
    - flRemoteVideo 铺满全屏，观看方画面最大化
  - [ ] 2.3 新增底部悬浮工具条布局 `view_meeting_toolbar.xml`
    - 实现需求 3 AC1/AC4：底部悬浮麦克风/摄像头/结束会议 3 核心按钮 + 「更多」入口
  - [ ] 2.4 实现工具条交互逻辑
    - 实现需求 3 AC2：3 秒无触摸自动隐藏、点击屏幕任意位置唤出；区分点击唤出与远程控制拖拽手势（<200ms 为点击）
    - 实现需求 3 AC3：麦克风/摄像头激活状态高亮复用 updateMicButton/updateVideoCallButton
    - 「更多」面板收纳：相册、远程控制开关、帧率、画面比例、全屏观看、拍照、控制键（返回/主页/最近/文本/无障碍设置/锁）、检查更新

- [ ] 3. 控件迁移与状态适配
  - [ ] 3.1 删除 llSignal/btnSignalHost/btnSignalJoin 连接区 UI 与入口（内部函数保留）
  - [ ] 3.2 控件迁移映射
    - 实现需求 4 AC1/AC2：llVideoBtns/llCtrlKeys/llRemoteRight/llCtrlStatus/tvZoomHint 收纳进「更多」面板，flCameraPip 保持右上角悬浮，flFullscreen 保留
  - [ ] 3.3 状态回调适配
    - 实现需求 5 AC2：updateUI/onDisconnected/onConnectionFailed 改更新悬浮胶囊与工具条；断开、授权取消、结束会议时清理会话并返回 Launcher
    - resetUI() 调整为会议结束清理（释放 peer、隐藏工具条、finish）
    - 实现需求 4 AC3：视频通话开启时 flCameraPip 持续显示

- [ ] 4. 检查点 - 编译与回归
  - 确保编译通过、无未使用 binding 引用；回归创建/加入/屏幕共享/视频通话/麦克风/相册/远程控制/全屏/检查更新全部功能

- [ ] 5. 发版流程
  - 递增 versionCode=161 / versionName="1.158"，构建签名 APK（keystore `/workspace/signing/release.keystore`）
  - 部署到下载服务器（DOWNLOAD_BASE），更新 changelog，更新 MEMORY.md，git commit + push（`env -u GIT_CONFIG_COUNT -u GIT_CONFIG_KEY_0 -u GIT_CONFIG_VALUE_0 git push`）
