# 会议式两级界面（Meeting Room UI）技术设计文档

## 1. Overview

将单页式 ScreenShare App 重构为腾讯会议式两级界面：

- **MeetingActivity（会议连接页 / 前端）**：App 的 LAUNCHER 入口。展示品牌、创建房间按钮、会议号输入框。用户在此决定「创建」或「加入」，然后跳转至会议室。
- **MainActivity（会议室 / 房间 / 后端）**：改造为沉浸式全屏会议室。启动即通过 Intent 携带的会议号直接进入连接流程，不再展示连接页控件。画面最大化，功能控件收纳为底部悬浮工具条（3 核心 + 更多），空闲自动隐藏。

职责划分清晰：MeetingActivity 只负责「进房间前的入口」；MainActivity 只负责「房间内的一切」。二者通过 Intent 传递会议号，跳转后 MeetingActivity finish 自身，使系统返回键行为符合直觉（会议室退出回到 Launcher，而不是回到连接页重复操作）。

## 2. Architecture

### 2.1 页面跳转与 Intent 协议

```
App 冷启动
   └─ MeetingActivity (LAUNCHER, exported=true)
        ├─ 点击「创建房间」→ 生成 4 位会议号 code
        │      → startActivity(MainActivity, { action=CREATE, code })
        └─ 输入会议号 + 点击「加入会议」→ validate
               → startActivity(MainActivity, { action=JOIN, code })
MainActivity 收到 intent → 按 action 走 host/join 流程 → finish 自身
```

**Intent Extra 协议（常量定义于 MainActivity 伴生对象）：**

| Extra | 类型 | 取值 | 含义 |
|---|---|---|---|
| `EXTRA_MEETING_ACTION` | String | `"create"` / `"join"` | 会议动作 |
| `EXTRA_MEETING_CODE` | String | 4 位数字 | 会议号 |

**MainActivity 启动分流规则（onCreate 内）：**

1. 若 intent 含 `EXTRA_MEETING_ACTION`，则调用 `handleMeetingIntent(intent)` 并跳过连接页 UI 初始化：
   - `action=create` → 直接执行现有 `onSignalHostClicked()` 的等价逻辑（生成/使用传入 code，建立 Host 连接）。
   - `action=join` → 直接执行现有 `joinMeetingWithCode(code)`。
2. 若 intent 为 `screenshare://join?code=XXXX`（分享链接冷启动），复用现有 `handleShareLink(intent)` 直接以 join 模式进入会议室（MeetingActivity 也注册该 scheme intent-filter 或依赖 MainActivity 现有过滤，实现细节见 3.1）。
3. 无任何会议 intent 时：由于 MainActivity 已非 LAUNCHER，正常只会从 MeetingActivity 跳转而来，此时 `finish()` 并跳回 MeetingActivity 兜底（防异常启动）。

### 2.2 Activity 与 Manifest 变更

**新增 MeetingActivity：**

```xml
<activity
    android:name=".MeetingActivity"
    android:exported="true"
    android:launchMode="singleTop"
    android:screenOrientation="fullSensor"
    android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|uiMode">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="screenshare" android:host="join" />
    </intent-filter>
</activity>
```

- 从 MainActivity 移除 `MAIN/LAUNCHER` intent-filter（保留 VIEW 过滤作分享链接兜底，两处 VIEW 过滤同时生效时由系统选择，一般取其栈顶；分享链接的规范化入口在 MeetingActivity，见 3.1）。

**MainActivity 变更：**

- `launchMode="singleTop"` 保持不变。
- 新增 `onNewIntent` 中 `handleMeetingIntent(intent)` 分支：会议室运行中收到新会议 intent 时先清理当前会话再进入新会议。

### 2.3 新增 res 资源

- `layout/activity_meeting.xml`：会议连接页布局。
- `drawable/*`：连接页所需按钮/背景（复用现有 liquid glass 风格的 drawable，尽量不新增）。
- 复用现有：`layout/dialog_join_meeting.xml` 的输入框逻辑可抽到连接页内嵌输入框（不再使用弹窗）；`anim/anim_fade_slide.xml`。

## 3. MeetingActivity 详细设计

### 3.1 功能点

| 功能 | 说明 |
|---|---|
| 品牌展示 | 复用深色科技感风格，居中品牌名（同现有 tvTitleBrand 视觉）。 |
| 创建房间 | 点击生成 4 位会议号并跳转 MainActivity（action=create）。跳转前无需任何权限（权限申请保留在 MainActivity 原流程）。 |
| 加入会议 | 页面内嵌 4 位数字输入框（替代原 dialog_join_meeting 弹窗，布局上置于创建按钮下方，输满/回车即触发加入，非法码 Toast 提示并停留）。 |
| 检查更新 | 连接页底部保留「检查更新」入口（复用 `UpdateChecker.check(this, manual=true)`）。 |
| 分享链接唤起 | 冷启动收到 `screenshare://join?code=XXXX` 时自动进入加入流程并跳转 MainActivity；同时把跳转用的 code 也带给 MainActivity。 |

### 3.2 跳转与生命周期

```kotlin
private fun enterMeeting(action: String, code: String) {
    val intent = Intent(this, MainActivity::class.java)
        .putExtra(EXTRA_MEETING_ACTION, action)
        .putExtra(EXTRA_MEETING_CODE, code)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    startActivity(intent)
    finish()   // 连接页退场，避免返回键回到重复入口
}
```

- `onNewIntent` 同样处理分享链接（singleTop）。
- 返回键默认行为：退出 App（连接页是根）。

## 4. MainActivity（会议室）详细设计

### 4.1 会议启动流程改造

- `onCreate` 在 `checkPermissions()` 前解析 intent；`handleMeetingIntent` 内对 create/join 分别调用：
  - create → 复用 `onSignalHostClicked()`（其内部 `connectSignal(code, asHost=true)` 完全一致，仅 code 改为 intent 传入）。
  - join → 复用 `joinMeetingWithCode(code)`。
- 屏幕共享授权（MediaProjection）流程、会议号弹窗（`showMeetingCodeDialog`）逻辑保持不变，仍在 `onActivityResult` 中触发。
- **删除** `llSignal` 连接区相关 UI 与 `btnSignalHost/btnSignalJoin` 点击入口（逻辑保留为内部函数供 intent 调用）。

### 4.2 沉浸全屏

- 进入会议室后立即进入全屏沉浸模式（隐藏系统状态栏/导航栏，`WindowInsetsControllerCompat` 或 `SYSTEM_UI_FLAG_IMMERSIVE_STICKY`），隐藏顶部 `llTitle`（或降级为悬浮小胶囊显示会议号/状态）。
- 画面容器 `flRemoteVideo` 铺满全屏；观看方收到的共享画面自动最大化。
- 退出会议/断开时恢复系统栏并 `finish()` 回 Launcher（当前 `resetUI` 语义调整为「销毁会话并结束 Activity」）。

### 4.3 底部悬浮工具条

**布局（新增 `layout/view_meeting_toolbar.xml`，悬浮于屏幕底部）：**

```
┌────────────────────────────────────────────┐
│  [麦克风]  [摄像头]  [结束会议]        [更多 ⋯] │
└────────────────────────────────────────────┘
```

| 按钮 | 行为 |
|---|---|
| 麦克风 | 复用 `onMicClicked()`；激活时按钮高亮（同现有 updateMicButton 状态样式）。 |
| 摄像头 | 复用 `onVideoCallClicked()`；激活时高亮「视频中」（同 updateVideoCallButton）。 |
| 结束会议 | 确认弹窗后清理会话并返回 Launcher（Host：同时停止共享；Viewer：断开连接）。 |
| 更多 ⋯ | 展开次级面板，收纳：相册、远程控制（开关）、帧率、画面比例、全屏观看、拍照、控制键（返回/主页/最近/文本/无障碍设置/锁）、检查更新。 |

**自动隐藏逻辑：**

- 容器 `llToolbar` 初始可见；用户 3 秒无触摸（`flRemoteVideo` 及全屏容器上的触摸事件计时）自动隐藏（下滑淡出）。
- 点击屏幕任意位置（不含按钮）→ 显示工具条。
- 「更多」面板展开时不被自动隐藏逻辑打断。
- 工具条悬浮于画面之上（`FrameLayout` 底部对齐，半透明液态玻璃背景），**不参与任何测量挤压**，画面容器始终占满全屏。

### 4.4 控件迁移映射（现有 → 会议室新位置）

| 现有控件 | 会议室新归属 |
|---|---|
| `llTitle` / `tvCheckUpdate` | 全屏沉浸后隐藏；检查更新移入「更多」 |
| `llStatus` / `btnFpsToggle` / `btnMic` / `btnCamera` / `btnAspectToggle` | 状态文字改为悬浮小胶囊（顶部），fps/比例移入「更多」；麦克风/摄像头提为工具条核心按钮 |
| `flRemoteVideo` + 内部 `llVideoBtns`(远程控制/文本/相册) / `llCtrlKeys` / `llRemoteRight` / `llCtrlStatus` / `tvZoomHint` | 保持画面最大化；远程控制/相册/控制键/缩放提示收纳进「更多」面板 |
| `llSignal` / `btnSignalHost` / `btnSignalJoin` / `tvScanResult` | 删除（连接页承担）；`tvScanResult` 诊断文本保留为悬浮小胶囊或临时 Toast |
| `btnStop` | 替换为工具条「结束会议」 |
| `flCameraPip` | 不变（视频通话 PIP 仍悬浮右上角，位于沉浸画面之上） |
| `flFullscreen` | 保留（观看方全屏沉浸观看的现有实现，与会议室全屏共存） |

### 4.5 状态回调适配

- `updateUI(msg)` / `onConnected` / `onDisconnected` / `onConnectionFailed` 中涉及 `llStatus`/`llSignal` 的更新改为操作悬浮胶囊与工具条状态；断开时自动返回 Launcher。
- `resetUI()` 调整为会议结束清理：释放 peer、隐藏工具条、`finish()`。
- `onVideoCallClicked` 的摄像头权限前置流程（PERM_REQUEST_VIDEO_CALL）与麦克风权限（PERM_REQUEST_MIC）保持不变。
- 屏幕共享授权取消（用户未点「立即开始」）→ 清理并返回 Launcher，而非停留空白页面。

## 5. 复用与不变项

- **协议层零改动**：`server.js`、`RoomManager.js`、`album-server.js` 不修改。
- **WebRTC 层零改动**：`WebRTCPeer.kt` 的协商/轨道/PIP/麦克风联动逻辑完全复用。
- **权限流程**：MediaProjection 授权、相机、麦克风、相册、无障碍服务的申请与引导流程保持不变。
- **分享链接**：`buildShareText` / `shareMeetingLink` 复用（分享链接仍可唤起 App 进入 join 流程）。

## 6. 风险与对策

| 风险 | 对策 |
|---|---|
| MainActivity 曾是 LAUNCHER，改入口后升级用户桌面图标指向 MeetingActivity，分享链接唤起地址不变 | MeetingActivity 注册 MAIN/LAUNCHER 且保留 VIEW 过滤；MainActivity 保留 VIEW 过滤双保险；升级安装后图标行为正确 |
| `handleShareLink` 在 MeetingActivity 解析后需把 code 透传 MainActivity | 解析出 code 后走 `enterMeeting("join", code)` 统一路径 |
| 沉浸全屏下键盘/无障碍弹窗遮挡工具条 | 工具条悬浮于底部安全区内，全屏模式下预留 insets 边距 |
| 悬浮工具条自动隐藏与远程控制触摸手势冲突（handleControlTouch 在 flRemoteVideo 上） | 工具条只监听自己的容器；画面触摸区分「点击唤出工具条」与「拖拽控制手势」：按下抬起间隔 <200ms 视为点击（唤出），否则按控制手势处理 |
| 返回键行为变化 | 会议室中返回键 = 结束会议确认；连接页返回键 = 退出 App |
| 单 Activity 内大量控件删除后代码残留 | 删除对应 binding 引用并移除未用 import，编译期强校验 |

## 7. 实施任务拆解（供 implementation-planner 使用）

1. 新增 `activity_meeting.xml` + `MeetingActivity.kt`（品牌/创建/加入输入框/检查更新/分享链接解析）。
2. AndroidManifest：MeetingActivity 挂 LAUNCHER；MainActivity 移除 LAUNCHER。
3. MainActivity 新增 `EXTRA_MEETING_ACTION`/`EXTRA_MEETING_CODE` 解析 + `handleMeetingIntent`；create/join 分流。
4. 新增 `view_meeting_toolbar.xml` + 悬浮工具条逻辑（3 核心 + 更多面板 + 3 秒自动隐藏 + 点击唤出）。
5. MainActivity 布局改造：删 llSignal/llTitle/llStatus 堆叠，flRemoteVideo 全屏，控件迁移映射。
6. 沉浸全屏（隐藏系统栏）+ 状态小胶囊 + 断开/授权取消返回 Launcher。
7. 编译、回归（创建/加入/屏幕共享/视频通话/麦克风/相册/远程控制/全屏）、发版 v1.158（versionCode 161）。
