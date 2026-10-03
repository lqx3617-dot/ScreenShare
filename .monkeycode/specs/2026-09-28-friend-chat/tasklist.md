# 好友聊天实施任务列表

## 阶段 1：服务端

- [x] T1 db.js 新增 messages 表（seq 自增、id 去重、会话双向索引、未读索引）
- [x] T2 ChatManager.js：send（好友校验+限流+去重+持久化+在线投递/离线推送）、history（afterSeq/beforeSeq 双向分页）、unread、markRead（批量置位+回执通知）、存储治理（1000 条/会话）
- [x] T3 server.js 实例化 ChatManager 并分发 chat-send 等 WS 消息
- [x] T4 AccountRouter 新增 /chat/history、/chat/read、/chat/unread 路由
- [x] T5 服务端自测：23 项全过（发消息/幂等去重/限流/非好友拒绝/文本校验/离线推送/历史分页/未读/已读回执/存储治理）

## 阶段 2：客户端数据层

- [x] T6 ChatDbHelper.kt：本地 messages 表（插入、会话分页、未读计数、标记已读、按 id 更新 ack）
- [x] T7 ChatClient.kt：REST 封装 history/read/unread（复用 AccountClient okhttp 栈）
- [x] T8 PresenceClient 扩展（聊天走全局长连接）：sendChat 发送 + chat-message/ack/read/unread/rejected 收发回调 + auth 后补发

## 阶段 3：客户端 UI

- [x] T9 ChatActivity.kt + 布局：消息列表、输入框、发送、顶栏「发起共享」
- [x] T10 ChatAdapter.kt：左右气泡、发送中/失败/已读状态
- [x] T11 ChatSync.kt：进入增量拉新、上滑翻页拉旧、ack/到达/已读写回本地库、重连补发队列

## 阶段 4：集成

- [x] T12 FriendsFragment 改造：单击好友打开 ChatActivity、长按进详情、未读角标（查本地库 + ChatSync 推送）
- [x] T13 App 启动初始化 ChatSync、登录同步 me、上线 chat-unread 拉取刷新角标

## 阶段 5：验证发版

- [x] T14a 服务端单测 23 项（收发/幂等/限流/校验/未读/已读/历史/存储治理）
- [x] T14b 端到端 13 项（真实账号 + WS：收发/ack/历史/未读/已读回执/非好友拒绝/未登录拒绝）
- [x] T14c 客户端编译通过（assembleDebug BUILD SUCCESSFUL，30.8MB）
- [ ] T14d 真机自测（发送/接收/离线补齐/未读角标/已读回执/消息头像）——v1.408(413) 修复头像布局后待复测
- [x] T15a 版本号 413/1.408 + CHANGELOG + assembleRelease（allarch / arm64）
- [x] T15b 签名（v2/v3，校验通过）+ 8090 可下载（md5 allarch 1cdaa936… / arm64 2adc4db3…）
- [ ] T15c 真机验证通过后更新 version.json 正式发版
