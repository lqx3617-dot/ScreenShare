# Requirements Document: 好友聊天（Friend Chat）

## Introduction

在现有账号与好友体系上补齐一对一文字聊天能力。当前好友之间只能通过「发起屏幕共享 / 一起看」互动，简单的沟通也要先建立共享会话，门槛高且打断当前活动。本功能让好友之间可以直接收发文字消息，消息落盘服务端，对方离线时推送通知、上线后拉取历史，支持未读提醒与历史记录查看。

复用现有 8095 信令进程的 WS 通道做实时投递，复用 SQLite 做消息持久化，复用 notifyUser 做离线推送。首期只做纯文本的一对一私聊，不含群聊、图片、语音。

## Glossary

- **聊天会话（conversation）**：两个好友之间的一对一消息集合，以双方 userId 标识
- **消息（message）**：一条文字内容，含发送者、接收者、内容、时间戳、服务端分配的 ID
- **已读（read）**：接收方打开过包含该消息的聊天页
- **未读数（unread count）**：某好友发来但接收方尚未在聊天页查看的消息条数

## Requirements

### Requirement 1: 发送文本消息

**User Story:** AS 已登录用户，我想给好友发文字消息，这样不用发起屏幕共享也能沟通

#### Acceptance Criteria

1. WHEN 用户在聊天页输入非空文本并点击发送，the App SHALL 通过信令 WS 发送 `chat-send` 消息给服务端，载荷为 `{toUserId, text, clientMsgId}`
2. WHEN 服务端收到 `chat-send`，the 服务端 SHALL 校验发送者与接收者存在好友关系，非好友时回复 `chat-rejected`
3. WHEN 服务端校验通过，the 服务端 SHALL 为消息分配唯一 ID 与时间戳并写入 SQLite，随后回复发送方 `chat-ack` 携带服务端 ID
4. IF 单条消息文本超过 2000 字符，the App SHALL 拒绝发送并提示「消息过长」

### Requirement 2: 实时投递在线消息

**User Story:** AS 收消息的用户，我想对方发来的消息立刻显示，这样能像聊天一样实时交流

#### Acceptance Criteria

1. WHEN 服务端持久化消息成功且接收方在线，the 服务端 SHALL 通过 `chat-message` 实时推送给接收方的全部在线连接
2. WHILE 接收方停留在与发送者的聊天页，the App SHALL 立即在消息列表末尾追加并滚动到底部
3. IF 同一消息通过多个连接重复到达（多设备在线），the App SHALL 依据服务端 ID 去重，只显示一次

### Requirement 3: 离线消息持久化与拉取

**User Story:** AS 收消息的用户，我想在好友不在线时也能发消息，这样对方上线后一定能看到

#### Acceptance Criteria

1. WHEN 服务端持久化消息时接收方离线，the 服务端 SHALL 保留消息直到接收方拉取
2. WHEN 用户完成 `auth` 认领，the 服务端 SHALL 下发 `chat-unread` 携带每个好友的未读数
3. WHEN 用户打开与某好友的聊天页，the App SHALL 调用 REST 接口拉取最近的历史消息，并按时间升序展示
4. WHEN 历史消息拉取成功，the App SHALL 将聊天页定位到最新消息

### Requirement 4: 离线推送通知

**User Story:** AS 收消息的用户，我想在没开应用时也知道有新消息，这样不会漏掉重要沟通

#### Acceptance Criteria

1. WHEN 服务端持久化消息时接收方离线，the 服务端 SHALL 调用 JPush 推送通知，标题为发送者昵称、内容为消息文本截断
2. IF 接收方在短时间内收到多条消息，the 服务端 SHALL 合并推送避免连续打扰
3. WHEN 用户点击推送打开应用，the App SHALL 定位到对应好友的聊天页

### Requirement 5: 未读提醒

**User Story:** AS 收消息的用户，我想一眼看到哪个好友有未读消息，这样不漏回

#### Acceptance Criteria

1. WHILE 用户在线且未停留在发送者的聊天页，the App SHALL 在好友列表该好友项上显示未读消息数角标
2. WHEN 用户打开与某好友的聊天页，the App SHALL 通过 REST 接口将该会话未读数清零
3. WHEN 服务端处理清零请求后收到新消息，the 服务端 SHALL 重新开始计数

### Requirement 6: 消息已读回执

**User Story:** AS 发消息的用户，我想知道对方是否已看到消息，这样判断是否需要等待回复

#### Acceptance Criteria

1. WHEN 接收方打开聊天页并加载到某消息，the App SHALL 上报已读位置（最新已读消息 ID）
2. WHEN 服务端收到已读上报，the 服务端 SHALL 向发送方推送 `chat-read` 携带已读消息 ID
3. WHEN 发送方收到 `chat-read`，the App SHALL 将对应 ID 及更早的消息标记为已读

### Requirement 7: 好友列表与聊天页入口

**User Story:** AS 用户，我想从好友列表直接进入聊天，这样操作路径短

#### Acceptance Criteria

1. WHEN 用户在好友列表点击某好友，the App SHALL 打开与该好友的聊天页
2. WHILE 聊天页显示，the App SHALL 保留「发起屏幕共享」按钮入口，不阻断现有共享流程
3. WHEN 用户在好友详情页选择「发消息」，the App SHALL 打开与该好友的聊天页

### Requirement 8: 限流与滥用防护

**User Story:** AS 系统运营者，我想防止消息接口被刷屏，这样服务端不被滥用

#### Acceptance Criteria

1. WHEN 单用户在 10 秒内发送超过 30 条消息，the 服务端 SHALL 拒绝后续请求并回复 `chat-rejected`
2. WHEN 服务端收到重复 `clientMsgId`，the 服务端 SHALL 视为同一条消息只持久化一次

### Requirement 9: 消息存储治理

**User Story:** AS 系统运营者，我想限制消息存储无限增长，这样数据库不膨胀

#### Acceptance Criteria

1. WHEN 服务端写入消息时，the 服务端 SHALL 限制单个会话保留最近 1000 条消息
2. WHEN 消息超过保留上限，the 服务端 SHALL 异步清理最早的消息，清理过程不阻塞发送链路
