# Requirements Document

## Introduction

在现有屏幕共享与相册体系上新增「远程相册同步」能力：共享方设备安装 App 后常驻后台运行轻量 HTTP 服务，观看方**不连接会议**、仅输入共享方的 IP 地址即可远程触发共享方开启相册同步；共享方后台服务通过 MediaStore 读取系统相册，首次全量、后续增量上传到相册服务器，观看方在相册 APP 中按设备查看共享方的全部照片。

## Glossary

- **共享方（Host）**：安装主 App（ScreenShare）并授权读取相册的设备，负责后台同步上传照片。
- **观看方（Viewer）**：使用相册 APP（AlbumViewer）输入 IP 远程触发同步并查看照片的一方。
- **相册服务器（Album Server）**：运行在 8096 端口的聚合相册服务，存储全部会话照片。
- **设备标识（device）**：共享方设备的唯一标识（默认使用设备 IP），用于相册服务器按设备分组。
- **同步会话（Sync Session）**：共享方某次上传相册创建的上传会话（token），携带 device 字段。
- **设备码（Device IP）**：观看方输入以定位共享方设备的 IP 地址。

## Requirements

### Requirement 1: 共享方常驻后台 HTTP 服务

**User Story:** AS 共享方, I want 安装 App 后自动启动常驻后台服务, so that 无需手动操作即可接收观看方的远程指令。

#### Acceptance Criteria

1. WHEN 共享方安装并首次打开主 App，系统 SHALL 启动前台服务并显示常驻通知「相册同步服务运行中」。
2. WHEN 前台服务运行中，系统 SHALL 在内置 HTTP 服务器上监听固定端口并响应健康检查请求。
3. WHILE 前台服务运行中，系统 SHALL 在通知或状态页面展示共享方设备的当前 IP 地址。
4. IF 用户撤销通知权限或强制停止 App，系统 SHALL 停止同步并在下次打开 App 时恢复。

### Requirement 2: 观看方远程触发同步

**User Story:** AS 观看方, I want 仅输入共享方 IP 即可远程开启其相册同步, so that 不连接会议也能获取对方相册。

#### Acceptance Criteria

1. WHEN 观看方在相册 APP 输入共享方 IP 并点击连接，系统 SHALL 向该 IP 的 HTTP 服务发送开启同步请求。
2. IF 连接失败或共享方离线，系统 SHALL 向观看方提示「无法连接共享方设备」。
3. WHEN 共享方 HTTP 服务收到开启同步请求，系统 SHALL 开始首次全量扫描并上传系统相册照片。
4. IF 同步已在运行，系统 SHALL 忽略重复开启指令并返回当前同步状态。

### Requirement 3: 首次全量与后续增量同步

**User Story:** AS 共享方, I want 首次全量上传相册且之后只增量上传新照片, so that 节省流量并保持相册最新。

#### Acceptance Criteria

1. WHEN 同步首次开启，系统 SHALL 扫描 MediaStore 全部图片并逐张压缩上传到相册服务器。
2. WHEN 首次全量完成后，系统 SHALL 记录已上传照片的 MediaStore 标识以便去重。
3. WHILE 同步运行中，系统 SHALL 周期性扫描 MediaStore，仅上传未同步过的新照片。
4. IF 上传过程中断（网络异常/进程被杀），系统 SHALL 在服务恢复后从未完成处继续。

### Requirement 4: 按设备区分相册

**User Story:** AS 观看方, I want 查看特定共享方设备的相册, so that 多设备照片互不混淆。

#### Acceptance Criteria

1. WHEN 共享方上传照片，系统 SHALL 在会话中携带 device 标识（默认设备 IP）。
2. WHEN 相册服务器接收上传，系统 SHALL 将会话按 device 分组存储。
3. WHEN 观看方打开相册 APP，系统 SHALL 展示可用设备列表并按所选设备加载对应相册。
4. WHEN 相册服务器返回聚合数据，系统 SHALL 按 device 过滤照片而非混排所有设备。

### Requirement 5: 相册服务器按设备查询支持

**User Story:** AS 相册服务器, I want 提供按设备查询相册的接口, so that 观看方可按设备拉取照片。

#### Acceptance Criteria

1. WHEN 观看方请求设备列表，系统 SHALL 返回所有有照片的 device 及每设备照片数。
2. WHEN 观看方请求指定 device 的相册，系统 SHALL 返回该设备全部会话的照片。
3. WHEN 设备无照片，系统 SHALL 返回空列表而非错误。
4. WHEN 查询使用不存在的 device，系统 SHALL 返回空结果并保持接口可用。

### Requirement 6: 同步状态可见

**User Story:** AS 观看方, I want 查看共享方同步进度, so that 了解照片是否上传完成。

#### Acceptance Criteria

1. WHEN 观看方连接共享方 IP 后，系统 SHALL 轮询其同步状态接口显示「已同步 X 张」。
2. WHEN 同步进行中，系统 SHALL 在相册 APP 显示进度提示。
3. IF 共享方离线，系统 SHALL 显示最后已知状态并提示设备离线。
