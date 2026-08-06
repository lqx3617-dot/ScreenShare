# Requirements Document

## Introduction

共享方（Host）创建会议后，当前只能手动把 4 位会议号发给对方，对方需打开 App 点击【加入会议】手动输入号码。本功能为共享方生成一个「分享链接」，观看方点击链接即可唤起 App 并自动加入会议观看屏幕，无需手动输入会议号。

## Glossary

- **共享方（Host）**：创建会议并共享自己屏幕的一方
- **观看方（Viewer）**：通过会议号或分享链接加入会议、观看共享屏幕的一方
- **会议号**：4 位数字，用于标识一次会议
- **分享链接**：包含会议号的可点击链接，观看方通过链接还原会议号并加入
- **App 唤起**：Android 通过自定义 scheme 或 App Link 从外部链接启动 ScreenShare 应用

## Requirements

### Requirement 1: 生成分享链接

**User Story:** AS 共享方, I want 一键生成包含会议号的分享链接, so that 对方点击即可加入，无需手动输入会议号

#### Acceptance Criteria

1. WHEN 共享方创建会议并获得 4 位会议号，系统 SHALL 提供「分享链接」操作入口
2. WHEN 共享方点击「分享链接」，系统 SHALL 生成同时包含 https 链接与自定义 scheme 链接的分享文案
3. 分享文案 SHALL 包含可读的会议号信息，WHEN 观看方无法通过链接加入时，SHALL 支持手动输入会议号

### Requirement 2: 系统分享面板

**User Story:** AS 共享方, I want 通过系统分享面板把链接发给对方, so that 任意聊天/短信应用均可发送

#### Acceptance Criteria

1. WHEN 共享方点击「分享链接」，系统 SHALL 调起系统分享面板（ACTION_SEND）并预填分享文案
2. 分享文案 SHALL 以文本形式发送，观看方收到后 SHALL 可直接点击链接

### Requirement 3: 网页兜底页

**User Story:** AS 观看方, I want 在浏览器打开链接时看到会议信息与进入方式, so that 未安装或无法唤起 App 时也能获得加入途径

#### Acceptance Criteria

1. WHEN 观看方在浏览器打开 https 分享链接，系统 SHALL 展示该会议的 4 位会议号
2. 网页 SHALL 提供「打开 App」按钮，WHEN 观看方点击，SHALL 通过自定义 scheme 唤起 App
3. 网页 SHALL 提供 App 下载入口，WHEN 观看方未安装 App，SHALL 可跳转下载

### Requirement 4: App 唤起并自动加入

**User Story:** AS 观看方, I want 点击链接自动进入加入流程, so that 不需要手动输入会议号

#### Acceptance Criteria

1. WHEN 观看方点击自定义 scheme 链接，系统 SHALL 唤起 ScreenShare App 并解析会议号
2. WHEN App 成功解析到 4 位有效会议号，系统 SHALL 自动执行加入会议流程
3. IF 链接中缺少或包含无效会议号，系统 SHALL 提示「无效的分享链接」且不发起加入
4. IF App 当前已在共享中或正在加入其他会议，系统 SHALL 提示当前不可加入并保持现有会话状态

### Requirement 5: 观看方未安装 App

**User Story:** AS 未安装 App 的观看方, I want 打开链接时获得引导, so that 知道如何下载与加入

#### Acceptance Criteria

1. WHEN 观看方在浏览器打开分享链接且设备未安装 App，网页 SHALL 展示 App 下载入口
2. WHEN 观看方下载并安装 App 后再次点击链接，系统 SHALL 按 Requirement 4 正常唤起并加入
