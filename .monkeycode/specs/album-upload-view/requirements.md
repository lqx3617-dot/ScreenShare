# Requirements Document

## Introduction

为屏幕共享应用新增「相册上传查看」能力。共享方（host）在共享过程中将手机相册照片上传至专用照片服务器，服务器生成网页链接；观看方（viewer）在手机浏览器打开该链接查看网页版相册。照片在共享方后台读取与上传，不显示在共享方屏幕画面上，不影响正在进行中的屏幕共享。

## Glossary

- **共享方（host）**：发起屏幕共享、提供相册照片的手机
- **观看方（viewer）**：加入会议、观看共享画面并打开相册链接的手机
- **相册上传**：共享方读取本机相册照片并上传至照片服务器的过程
- **照片服务器**：接收照片存储并托管相册网页的服务器（独立于信令/下载服务器）
- **相册网页**：照片服务器生成的、按相册分组展示照片的网页
- **相册链接**：相册网页的 URL，观看方凭此链接访问照片
- **媒体权限**：Android 系统读取媒体文件所需的权限（READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE）

## Requirements

### Requirement 1：共享方相册上传入口

**User Story:** AS 共享方，I want 在共享界面一键上传相册，so that 观看方能查看我手机里的照片

#### Acceptance Criteria

1. WHEN 共享方点击共享界面上的「上传相册」按钮，应用 SHALL 申请媒体读取权限；已授权则 SHALL 直接进入上传流程
2. WHEN 共享方拒绝媒体权限，应用 SHALL 提示权限被拒原因并提供跳转系统设置重新授权的入口
3. WHILE 上传进行中，应用 SHALL 显示上传进度（已上传数量/总数）并可取消
4. 上传完成时，应用 SHALL 展示相册链接并复制到剪贴板，供共享方转发给观看方

### Requirement 2：照片读取

**User Story:** AS 共享方，I want 后台读取整个相册的照片，so that 观看方能查看完整相册

#### Acceptance Criteria

1. 应用 SHALL 通过 MediaStore 查询本机全部图片，按拍摄时间倒序排列
2. 读取过程 SHALL 在后台线程执行，不阻塞屏幕共享画面
3. 读取到的照片 SHALL 在共享方屏幕上不展示、不弹出任何界面

### Requirement 3：照片上传

**User Story:** AS 共享方，I want 把照片上传到照片服务器，so that 观看方能通过链接查看

#### Acceptance Criteria

1. 应用 SHALL 将读取到的照片经 HTTPS 分块上传至照片服务器，单张图片压缩后上传（最长边压缩至 2048px，JPEG 质量 85）
2. 上传 SHALL 支持断点续传或失败重试，网络异常时已上传照片 SHALL 保留
3. 上传过程 SHALL 不阻塞共享画面，与屏幕共享并行进行
4. 上传完成后应用 SHALL 显示相册链接，链接可复制分享

### Requirement 4：照片服务器

**User Story:** AS 照片服务器，I want 接收照片并托管相册网页，so that 观看方能访问

#### Acceptance Criteria

1. 服务器 SHALL 提供上传接口接收照片（含会话标识与照片序号），校验后落盘
2. 服务器 SHALL 为每次上传会话生成唯一访问链接，链接不可被未上传会话使用
3. 服务器 SHALL 提供相册网页，按照片顺序展示全部照片，支持点击查看原图
4. 服务器 SHALL 在会话过期（如 24 小时）后清理照片文件与链接

### Requirement 5：观看方查看

**User Story:** AS 观看方，I want 通过链接查看共享方相册，so that 我能浏览照片

#### Acceptance Criteria

1. 观看方 SHALL 在手机浏览器打开相册链接查看网页版相册，无需安装或打开 App
2. 相册网页 SHALL 按时间倒序网格展示照片缩略图，点击任意照片 SHALL 全屏查看原图
3. 网页 SHALL 支持移动端自适应布局（响应式），在手机浏览器正常显示

### Requirement 6：隐私与安全

**User Story:** AS 共享方，I want 相册访问受限，so that 我的照片不被任意人获取

#### Acceptance Criteria

1. 相册链接 SHALL 包含不可猜测的随机标识（至少 128 位随机熵）
2. 服务器 SHALL 不提供相册列表或遍历入口，无链接 SHALL 无法访问任何照片
3. 相册 SHALL 仅通过 HTTPS 访问，会话过期后链接 SHALL 失效
4. 上传的压缩照片 SHALL 不含 EXIF 地理位置等元数据，避免泄露隐私
