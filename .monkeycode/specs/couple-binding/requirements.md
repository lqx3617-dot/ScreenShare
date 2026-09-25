# 情侣绑定与情侣空间 需求文档

## Introduction

在现有好友体系（users / friends / friend_requests + FriendManager）之上新增情侣绑定关系：用户通过好友码发起情侣邀请，对方同意后建立唯一绑定关系，并开通情侣空间。情侣空间首版提供：绑定关系展示、在一起天数（自绑定日起自动累计）、情侣共享相册（双方均可上传与查看）。

## Glossary

- **情侣绑定（Couple Binding）**：两名用户之间唯一的 1 对 1 关联关系，记录于 couples 表
- **情侣邀请（Couple Invitation）**：一方通过对方好友码发起、需对方确认的绑定申请
- **情侣空间（Couple Space）**：绑定后开通的共享页面，含关系展示、在一起天数、共享相册
- **好友码（Friend Code）**：users.friend_code，6 位大写字母数字，现有体系
- **在一起天数（Days Together）**：自绑定建立日起到当日的自然天数

## Requirements

### REQ-1 发起情侣邀请

**User Story:** AS 已登录用户，I want 通过输入对方好友码发起情侣邀请，so that 与伴侣建立绑定关系

#### Acceptance Criteria

1. WHEN 用户在情侣空间页输入有效好友码并提交，THE 系统 SHALL 创建一条 pending 状态的情侣邀请，并给目标用户推送通知
2. IF 目标用户不存在或好友码无效，THE 系统 SHALL 返回错误且不创建任何邀请
3. IF 目标用户与发起用户相同，THE 系统 SHALL 拒绝创建邀请
4. IF 发起用户已存在生效中的情侣绑定，THE 系统 SHALL 拒绝创建邀请并提示先解绑
5. IF 目标用户已存在生效中的情侣绑定，THE 系统 SHALL 拒绝创建邀请并提示对方已绑定

### REQ-2 接受/拒绝情侣邀请

**User Story:** AS 被邀请用户，I want 接受或拒绝收到的情侣邀请，so that 决定是否建立绑定关系

#### Acceptance Criteria

1. WHEN 被邀请用户接受邀请，THE 系统 SHALL 建立 couples 表双向绑定记录（双方各一行，对称）、记录绑定起始时间、自动使双方成为好友（若尚未是好友），并清除该邀请
2. WHEN 被邀请用户拒绝邀请，THE 系统 SHALL 将邀请标记为 rejected 且不建立任何绑定
3. IF 邀请不存在或状态非 pending，THE 系统 SHALL 拒绝处理并返回错误
4. WHEN 绑定建立成功，THE 系统 SHALL 向双方各推送一条绑定成功通知

### REQ-3 唯一绑定约束

**User Story:** AS 用户，I want 每人同时只能有一个伴侣，so that 情侣关系明确不混乱

#### Acceptance Criteria

1. WHEN 建立绑定时任一方已存在生效绑定，THE 系统 SHALL 拒绝建立并回滚（邀请保持 pending 由被邀请方处理）
2. THE 系统 SHALL 保证同一用户在 couples 表中最多出现于一组生效绑定

### REQ-4 情侣空间首页

**User Story:** AS 已绑定用户，I want 打开情侣空间看到伴侣信息与在一起天数，so that 感受关系状态

#### Acceptance Criteria

1. WHILE 用户处于已绑定状态，THE 系统 SHALL 在情侣空间首页展示双方头像、昵称与在一起天数（服务端按绑定起始时间计算）
2. WHILE 用户处于未绑定状态，THE 系统 SHALL 展示发起绑定的入口（输入好友码/扫码）与待处理邀请列表
3. IF 绑定关系不存在或已解绑，THE 系统 SHALL 返回未绑定状态且不展示伴侣信息

### REQ-5 情侣共享相册（照片与视频）

**User Story:** AS 已绑定用户，I want 与伴侣共享照片和视频， so that 共同保存恋爱回忆

#### Acceptance Criteria

1. WHEN 已绑定用户上传照片（单张上限 10MB），THE 系统 SHALL 将照片归属于该情侣组（以 couple_id 标识）并立即可被双方查看
2. WHEN 已绑定用户上传视频（单条上限 100MB），THE 系统 SHALL 提取首帧作为缩略图用于网格展示、按分块顺序落盘，并在完成后对双方可见
3. WHILE 用户处于已绑定状态，THE 系统 SHALL 返回该情侣组的全部媒体列表（照片与视频混合、按时间倒序、含缩略图）
4. IF 上传者未处于已绑定状态，THE 系统 SHALL 拒绝上传并返回错误
5. WHEN 任一绑定成员删除情侣相册中的媒体，THE 系统 SHALL 立即对该媒体对双方不可见

### REQ-6 解除绑定

**User Story:** AS 已绑定用户，I want 解除情侣绑定， so that 结束关系

#### Acceptance Criteria

1. WHEN 任一绑定成员发起解绑，THE 系统 SHALL 立即解除绑定（双方均变为未绑定状态），无需对方确认
2. WHEN 解绑发生时，THE 系统 SHALL 保留情侣空间历史内容（相册媒体仍对原双方可见但不可再上传），并向双方推送解绑通知
3. WHEN 用户建立新的情侣绑定，THE 系统 SHALL 清除其上一段情侣关系的空间内容（相册媒体）
4. IF 发起者未处于已绑定状态，THE 系统 SHALL 拒绝处理

### REQ-7 伴侣在线状态与位置共享

**User Story:** AS 已绑定用户，I want 知道伴侣是否在线以及最近位置， so that 感到彼此连接

#### Acceptance Criteria

1. WHILE 用户处于已绑定状态打开情侣空间，THE 系统 SHALL 展示伴侣当前在线状态（复用 Presence 在线判定）
2. WHEN 已绑定用户上报自身位置（经纬度），THE 系统 SHALL 仅记录其当前情侣组可见的位置，且仅伴侣可查询
3. WHILE 用户处于已绑定状态，THE 系统 SHALL 返回伴侣最近一次位置与上报时间
4. IF 位置上报者未处于已绑定状态，THE 系统 SHALL 拒绝记录
5. WHEN 解绑发生，THE 系统 SHALL 使双方历史位置不再对任何新伴侣可见；新绑定时按 REQ-6.3 一并清除

## 已确认设计决策

| 决策点 | 取值 |
|--------|------|
| 绑定是否需先加好友 | 否：邀请接受时自动加好友；已有好友关系则跳过 |
| 每人绑定数量上限 | 1（唯一绑定） |
| 解绑是否需双方确认 | 否：任一方单方解绑即生效 |
| 解绑后空间内容 | 保留可见、不可上传；重新绑定新伴侣时清空旧内容 |
| 相册媒体类型 | 照片（单张 10MB）+ 视频（单条 100MB，首帧缩略图） |
| 位置共享 | 做：仅当前情侣组可见，解绑/新绑定按规则清除 |
| 在线状态 | 做：复用 Presence 判定，空间页展示 |
