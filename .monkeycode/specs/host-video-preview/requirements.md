# 需求文档：共享方本地视频预览

## Introduction

当前共享方（Host）只将屏幕画面通过 WebRTC 发送给观看方（Viewer），自身界面不显示任何共享画面，只能看到文字诊断信息。本需求为共享方增加与观看方一致的本地画面预览：共享方在连接建立后，可在画面区看到自己正在共享的屏幕内容，并复用观看方已有的缩放、全屏、完整/铺满切换等全部交互。

## Glossary

- **共享方（Host）**：创建会议并采集/发送屏幕画面的设备。
- **观看方（Viewer）**：加入会议并接收/渲染远程画面的设备。
- **本地视频轨道**：共享方 `ScreenCapturerAndroid` 采集生成的 WebRTC `VideoTrack`（即 `localVideoTrack`）。
- **远程视频轨道**：观看方接收到的共享方视频轨道（即 `remoteVideoTrack`）。
- **画面区（flRemoteVideo）**：现有用于渲染视频的容器，含右上角按钮列（完整/全屏）。

## Requirements

### Requirement 1：共享方显示本地预览

**User Story:** AS 共享方, I want 在共享期间看到自己正在共享的画面, so that 确认画面内容与范围是否符合预期

#### Acceptance Criteria

1. WHEN P2P 连接建立成功（`onConnected`），共享方 SHALL 在画面区显示本地视频轨道的实时画面
2. WHILE 本地预览正在显示，共享方 SHALL 持续渲染屏幕采集的每一帧画面
3. IF 连接断开或共享结束，共享方 SHALL 隐藏本地预览画面

### Requirement 2：预览交互与观看方一致

**User Story:** AS 共享方, I want 本地预览提供与观看方相同的交互, so that 操作习惯一致

#### Acceptance Criteria

1. WHEN 本地预览显示，共享方 SHALL 支持双指缩放画面
2. WHEN 本地预览显示，共享方 SHALL 支持进入/退出全屏观看
3. WHEN 本地预览显示，共享方 SHALL 支持完整显示/铺满屏幕两种显示模式切换
4. WHEN 本地预览显示，共享方 SHALL 支持画面旋转后自动适配容器尺寸

### Requirement 3：不改变观看方行为

**User Story:** AS 观看方, I want 现有观看体验保持不变, so that 新功能不影响已确认的观看功能

#### Acceptance Criteria

1. WHEN 观看方加入会议，观看方 SHALL 继续按现有逻辑渲染远程画面
2. WHEN 共享方开启本地预览，观看方 SHALL 不受任何影响
