# Requirements Document

## Introduction

在相册查看 APP（albumviewer）中增加隐藏的发版管理入口。开发者/测试者在 APP 内输入新版本号与更新说明后，一键触发云端发布：服务器自动修改版本号、构建并签名 ScreenShare 与 AlbumViewer 两个 APK、更新下载服务器版本信息，客户端随后即可通过既有更新检查链路发现新版本。该功能取代手工编辑 build.gradle.kts、执行构建、手动上传、编辑 changelog 的重复流程。

## Glossary

- **相册查看 APP（albumviewer）**：独立 Android 应用，applicationId `com.screenshare.albumviewer`，用于查看服务器归拢的相册。
- **下载服务器（download-server）**：运行于 :8090 的 Node HTTP 服务，负责提供 version.json、ScreenShare APK、AlbumViewer APK 与分享兜底页。
- **云发布（publish）**：由相册查看 APP 发起、下载服务器执行的「改版本号 + 构建签名 + 更新版本信息」完整流程。
- **构建任务（build task）**：下载服务器异步执行的发布动作，有唯一 taskId，状态可查询。
- **ScreenShare 主 APP**：applicationId `com.screenshare`，屏幕共享主应用。

## Requirements

### Requirement 1: 隐藏发版管理入口

**User Story:** AS 开发者/测试者, I want 在相册查看 APP 内通过隐蔽操作打开发版管理面板, so that 普通用户不会误触而我能快速发版。

#### Acceptance Criteria

1. WHEN 用户在相册查看 APP 输入页顶部标题连续点击 7 次（每次间隔不超过 800ms），APP SHALL 打开发版管理对话框。
2. WHEN 发版管理对话框打开，APP SHALL 显示当前 APP 版本号（versionName/versionCode）与当前 changelog。
3. WHEN 用户点击管理对话框外区域，APP SHALL 关闭对话框且不执行任何发布动作。
4. WHILE 用户未触发隐藏入口，APP SHALL 保持正常相册查看功能，不显示任何发版入口。

### Requirement 2: 输入新版本信息并触发云发布

**User Story:** AS 开发者, I want 在管理面板输入新版本号与更新说明并点击发布, so that 服务器自动完成改版本号、构建、签名与版本信息更新。

#### Acceptance Criteria

1. WHEN 用户输入新版本名（格式 `^\d+\.\d+$`），APP SHALL 校验格式并在非法时提示「版本号格式应为 数字.数字（如 1.183）」。
2. WHEN 用户输入更新说明（changelog），APP SHALL 校验非空并在为空时提示「请填写更新说明」。
3. WHEN 用户点击「发布」按钮且输入合法，APP SHALL 调用下载服务器 `POST /api/publish` 提交 {versionName, changelog, app:"albumviewer"|"main"|"both"}。
4. WHEN 提交成功，APP SHALL 进入发布进度页，显示构建任务状态。
5. WHEN 提交失败（网络异常或服务器拒绝），APP SHALL 显示失败原因并允许重试。

### Requirement 3: 服务器执行云发布

**User Story:** AS 下载服务器, I want 接收发布请求后自动改版本号、构建签名并更新版本信息, so that 客户端可检查到新版本。

#### Acceptance Criteria

1. WHEN 下载服务器收到合法 `POST /api/publish`，服务器 SHALL 立即返回 `{taskId}` 并异步启动构建任务。
2. WHILE 构建任务执行中，服务器 SHALL 将 build.gradle.kts（主 APP 与/或相册查看 APP）的 versionCode 递增 1 并将 versionName 更新为请求值。
3. WHEN 版本号修改完成，服务器 SHALL 执行 `./gradlew assembleRelease` 构建目标 APK。
4. WHEN 构建完成，服务器 SHALL 执行 zipalign + apksigner 签名并覆盖 `/workspace/ScreenShare-allarch-signed.apk` 与/或 `/workspace/AlbumViewer-signed.apk`。
5. WHEN 签名完成，服务器 SHALL 更新下载服务器版本配置（changelog 为请求值）使 version.json 返回新版本。
6. WHEN 任一环节失败，服务器 SHALL 将构建任务标记为失败并记录失败日志，供客户端查询。
7. IF 已存在正在运行的构建任务，服务器 SHALL 拒绝新的发布请求（返回 409）避免并发覆盖。

### Requirement 4: 客户端查询构建进度

**User Story:** AS 相册查看 APP, I want 轮询构建任务状态并显示进度/结果, so that 开发者了解发布是否成功。

#### Acceptance Criteria

1. WHEN 构建任务已提交，APP SHALL 每 2 秒调用 `GET /api/publish/status?task={taskId}` 查询状态。
2. WHEN 状态为构建中，APP SHALL 显示「构建中…」及当前阶段（改版本号/构建/签名/完成）。
3. WHEN 状态为成功，APP SHALL 显示新版本号与发布成功提示，并停止轮询。
4. WHEN 状态为失败，APP SHALL 显示失败原因并停止轮询。
5. WHILE 发布页面打开，APP SHALL 允许用户取消（停止轮询并返回）。

### Requirement 5: 发布结果生效

**User Story:** AS 用户, I want 云发布完成后客户端能检查到新版本, so that 升级流程闭环。

#### Acceptance Criteria

1. WHEN 构建任务成功完成，ScreenShare 主 APP 与相册查看 APP 的检查更新接口 SHALL 返回新版本号与更新说明。
2. WHEN 客户端下载新 APK，服务器 SHALL 返回的 md5 与签名产物实际 md5 一致。
3. WHEN 发布后首次访问 version.json，服务器 SHALL 返回与 build.gradle.kts 一致的 versionCode/versionName。

### Requirement 6: 构建失败不改坏现有发布

**User Story:** AS 开发者, I want 发布失败时不破坏当前可用的线上版本, so that 用户不受影响。

#### Acceptance Criteria

1. IF 构建任务失败，服务器 SHALL 保留上一次成功的 APK 与版本信息，version.json 仍返回旧版本。
2. IF 构建任务失败于版本号修改之后，服务器 SHALL 将 build.gradle.kts 恢复为构建前版本号。
3. WHEN 构建任务执行中，download-server 对 APK 的下载请求 SHALL 继续正常服务（构建不阻塞下载）。
