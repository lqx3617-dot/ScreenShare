# 需求文档：检查更新优化

## Introduction

现有检查更新功能已具备版本检查、2 线程分段下载、MD5 校验与自动安装。本次优化四个方向：更新提示更友好（版本对比/更新说明/强制更新）、后台下载与通知栏进度（可离开页面、失败重试）、提高下载速度（更多线程）、启动自动静默检查（节流）。

## Glossary

- **更新服务器**：提供 `/version.json` 的下载服务（`BuildConfig.UPDATE_URL`）。
- **version.json**：版本元数据，含 `versionCode/versionName/url/md5/size/note`，本次新增 `forced`（是否强制）与 `changelog`（更新说明）。
- **静默检查**：应用启动时不弹窗的自动检查；仅在发现新版本时提示。
- **手动检查**：用户点击「检查更新」触发的检查；结果总以 Toast 反馈。

## Requirements

### Requirement 1：更新提示更友好

**User Story:** AS 用户, I want 更新弹窗展示完整信息与强制策略, so that 了解更新内容并明确是否必须更新

#### Acceptance Criteria

1. WHEN 检测到新版本，系统 SHALL 在弹窗标题展示新版本号（如「发现新版本 v1.67」）
2. WHEN 检测到新版本，系统 SHALL 在弹窗内容展示更新说明（`changelog`，多行）、当前版本与目标版本对比、安装包大小
3. WHEN `version.json` 的 `forced` 为 true，系统 SHALL 在弹窗中禁止跳过（无「暂不」按钮），仅提供「立即更新」
4. WHEN 用户点击「立即更新」，系统 SHALL 开始下载并安装

### Requirement 2：后台下载与通知栏进度

**User Story:** AS 用户, I want 下载在后台进行并在通知栏查看进度, so that 下载期间可以正常使用其他功能

#### Acceptance Criteria

1. WHEN 开始下载，系统 SHALL 在通知栏创建带进度条的下载通知（标题含版本号）
2. WHEN 下载进行中，系统 SHALL 每 150ms 更新通知栏进度与下载速度
3. WHEN 下载完成，系统 SHALL 更新通知并直接进入安装流程
4. IF 下载失败，系统 SHALL 自动重试；连续失败达到上限后 SHALL 在通知栏提示失败
5. WHEN 用户点击「取消」，系统 SHALL 停止下载并清除通知

### Requirement 3：提高下载速度

**User Story:** AS 用户, I want 下载更快, so that 尽快完成更新

#### Acceptance Criteria

1. WHEN 开始下载，系统 SHALL 使用 4 线程分段并发下载（原为 2 线程）
2. WHEN 单个分段下载失败，系统 SHALL 单独重试该分段，不中断其他分段

### Requirement 4：启动自动静默检查

**User Story:** AS 用户, I want 应用启动时自动检查更新, so that 及时获知新版本

#### Acceptance Criteria

1. WHEN 应用启动，系统 SHALL 静默检查更新；发现新版本 SHALL 弹出更新提示，无新版本 SHALL 不打扰
2. WHILE 距离上次静默检查不足 12 小时，系统 SHALL 跳过本次静默检查（避免频繁请求）
3. WHEN 用户手动检查，系统 SHALL 不受节流限制，立即检查并反馈结果
4. IF 静默检查网络失败，系统 SHALL 静默忽略，不提示错误

### Requirement 5：已下载文件复用

**User Story:** AS 用户, I want 下载中断后不重复下载, so that 节省流量

#### Acceptance Criteria

1. WHEN 存在与目标版本相同且 MD5 校验通过的安装包文件，系统 SHALL 直接进入安装流程，不重新下载
2. WHEN 已有文件 MD5 校验失败，系统 SHALL 删除并重新下载
