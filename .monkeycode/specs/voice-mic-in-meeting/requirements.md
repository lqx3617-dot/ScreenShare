# Requirements Document

## Introduction

在现有 ScreenShare 会议（会议号模式）内新增**麦克风实时语音**功能：会议双方（共享方与观看方）均可开启麦克风进行双向对讲，麦克风语音与现有系统内录音频（DataChannel 传输的屏幕声音）叠加播放。该功能不影响既有屏幕共享、系统音频与视频传输链路。

## Glossary

- **会议（Meeting）**：由共享方通过 4 位会议号创建、观看方加入的一对一实时会话。
- **共享方（Host）**：创建会议并共享屏幕的一端。
- **观看方（Viewer）**：输入会议号加入、观看屏幕画面的一端。
- **麦克风语音（Voice）**：通过 WebRTC 标准音频轨道（Opus 编码）实时传输的双向语音。
- **系统内录音频（SystemAudio）**：通过 DataChannel（PCM）传输的屏幕声音（应用播放的媒体声音）。
- **静音（Mute）**：停止采集并发送麦克风语音的状态。

## Requirements

### Requirement 1: 会议内双向麦克风语音

**User Story:** AS 会议参与者, I want 在会议中开启麦克风说话, so that 对方能实时听到我的声音。

#### Acceptance Criteria

1. WHEN 任一会议参与者开启麦克风，系统 SHALL 采集该参与者麦克风音频并通过 WebRTC 音频轨道实时发送给对方。
2. WHEN 会议参与者收到对端麦克风音频，系统 SHALL 通过设备扬声器播放。
3. WHILE 双方均在会议中，系统 SHALL 支持双方同时说话（全双工对讲）。

### Requirement 2: 麦克风静音控制

**User Story:** AS 会议参与者, I want 一键静音/取消静音, so that 我能控制自己是否说话。

#### Acceptance Criteria

1. WHEN 用户点击静音按钮，系统 SHALL 停止发送麦克风音频。
2. WHEN 用户再次点击静音按钮，系统 SHALL 恢复发送麦克风音频。
3. WHILE 用户处于静音状态，系统 SHALL 在界面显示静音状态标识。

### Requirement 3: 与系统内录音频叠加

**User Story:** AS 观看方, I want 同时听到共享方的说话声和屏幕声音, so that 语音讲解与屏幕内容声音不冲突。

#### Acceptance Criteria

1. WHILE 屏幕共享正在进行，系统 SHALL 同时传输麦克风语音与系统内录音频。
2. WHEN 观看方同时收到麦克风语音与系统内录音频，系统 SHALL 同时播放两者（叠加混音）。

### Requirement 4: 麦克风权限与异常处理

**User Story:** AS 会议参与者, I want 在麦克风不可用时得到明确提示, so that 我知道为什么没有声音。

#### Acceptance Criteria

1. IF 用户拒绝麦克风权限，系统 SHALL 显示中文提示并保持屏幕共享与系统音频不受影响。
2. IF 麦克风被占用或采集失败，系统 SHALL 提示麦克风不可用。
3. WHILE 麦克风不可用，系统 SHALL 保持静音状态标识，避免误以为正在说话。

### Requirement 5: 音质与延迟

**User Story:** AS 会议参与者, I want 语音清晰且延迟低, so that 对话自然顺畅。

#### Acceptance Criteria

1. WHEN 网络正常，系统 SHALL 保证语音端到端延迟低于 500 毫秒。
2. WHILE 麦克风语音启用，系统 SHALL 启用回声消除，避免对方听到回声。
3. WHILE 麦克风语音启用，系统 SHALL 启用噪声抑制，减少环境噪音。

### Requirement 6: 功能独立性

**User Story:** AS 共享方, I want 关闭麦克风不影响其他功能, so that 我能灵活控制共享内容。

#### Acceptance Criteria

1. WHEN 用户关闭麦克风语音，系统 SHALL 停止采集麦克风，同时保持屏幕共享、系统音频与视频传输正常运行。
2. WHEN 观看方关闭麦克风，系统 SHALL 仅停止观看方的语音上行，仍保持接收屏幕画面。
