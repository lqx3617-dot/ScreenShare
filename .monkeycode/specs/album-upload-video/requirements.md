# Requirements Document — 相册上传支持视频（album-upload-video）

Updated: 2026-09-07

## Introduction

会议内观看方触发的「上传相册到服务器」链路（AlbumUploader.uploadAlbum）当前只上传照片；后台自动同步（ScreenSyncService）已支持照片+视频。本特性为上传链路补齐视频上传：共享方执行相册上传时，把本机相册中的视频（转码压缩后）一并上传到同一相册会话，观看端（相册 App / 网页）在既有网格中直接查看与播放视频条目。

## Glossary

- **共享方（host）**：屏幕共享端设备，本地持有相册照片与视频，执行上传。
- **观看方（viewer）**：会议中查看共享画面的设备，经控制通道触发上传并收到相册链接。
- **相册会话（session）**：相册服务器（8096）按 token 组织的一次上传集合，包含照片（index 1..N）与视频（index ≥ 1000001）。
- **视频索引基数（VIDEO_INDEX_BASE = 1000000）**：视频条目 index 的起始偏移，与照片 index 隔离，防止冲突。
- **照片原图按需服务（startOriginalService）**：照片缩略图先行、原图按网页请求补传的后台服务，仅作用于照片。

## Requirements

### Requirement 1 — 视频随相册上传

**User Story:** AS 观看方, I want 共享方执行「上传相册到服务器」时把视频一并传到相册, so that 我在相册里能直接看到并播放共享方的视频。

#### Acceptance Criteria

1. WHEN 共享方执行相册上传且本机存在视频, THE 相册上传流程 SHALL 在照片上传完成后将每个视频上传至同一相册会话（缩略图 → 转码 720p/2Mbps → 分块上传 → finish 标记）。
2. WHEN 视频上传完成, THE 相册会话 SHALL 将该视频以视频条目（index ≥ 1000001）纳入会话视频集合，供相册 App 与网页端网格展示与播放。
3. THE 相册上传流程 SHALL 在视频全部处理完成后执行会话 finish（照片原图按需服务随后启动）。

### Requirement 2 — 视频串行处理

**User Story:** AS 共享方, I want 视频上传不影响照片上传速度与设备流畅度, so that 共享期间设备不因转码明显发热卡顿。

#### Acceptance Criteria

1. THE 相册上传流程 SHALL 在照片并发上传全部结束后再串行（逐个）处理视频。
2. WHILE 视频转码进行中, THE 相册上传流程 SHALL 保证同一时刻最多一个视频在转码/上传。

### Requirement 3 — 单视频失败隔离

**User Story:** AS 共享方, I want 单个视频损坏或转码失败时其余内容仍能上传, so that 一个坏文件不拖垮整次相册上传。

#### Acceptance Criteria

1. IF 单个视频在缩略图、转码、分块上传或 finish 任一步失败, THE 相册上传流程 SHALL 跳过该视频并继续处理后续视频。
2. IF 存在至少一张照片或一个视频上传成功, THE 相册上传流程 SHALL 正常完成会话并回发链接。

### Requirement 4 — 空相册判定

**User Story:** AS 观看方, I want 共享方相册为空时得到明确提示, so that 我知道没有可查看的内容。

#### Acceptance Criteria

1. IF 共享方本机照片与视频均为空, THE 相册上传流程 SHALL 抛出空相册异常并由观看方收到「相册没有照片或视频」提示。
2. IF 照片为空但视频存在, THE 相册上传流程 SHALL 创建会话并仅上传视频。

### Requirement 5 — 取消行为

**User Story:** AS 共享方, I want 相册上传取消后尽快停止, so that 会议结束后后台不继续消耗流量与电量。

#### Acceptance Criteria

1. WHEN 相册上传被取消, THE 相册上传流程 SHALL 在当前视频处理完毕后停止处理后续视频并中止会话（best-effort finish）。
2. THE 相册上传流程 SHALL 保持既有照片阶段取消行为不变（任一照片上传前检查取消标志）。

### Requirement 6 — 权限缺失退化

**User Story:** AS 共享方, I want 未授予视频权限时上传仍能完成, so that 共享过程不因权限弹窗被打断。

#### Acceptance Criteria

1. IF 共享方未授予视频读取权限（Android 13+ READ_MEDIA_VIDEO）, THE 相册上传流程 SHALL 自动退化为仅上传照片，且向观看方正常回发链接。
2. THE 相册上传流程 SHALL 依赖启动时已申请的权限， SHALL 在上传触发时不新增权限弹窗（维持「不打断共享」约定）。

## Non-Goals

- 观看端 UI 改动（相册 App 网格、网页端视频播放均已支持视频条目，v1.188/v1.191）。
- 后台自动同步链路（ScreenSyncService）行为变更。
- 远程拍照上传链路变更。
- 照片原图按需服务覆盖视频（视频转码后即完整上传，无按需阶段）。
