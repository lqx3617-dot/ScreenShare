# 情侣共享房间 - 实施任务清单

## 服务端（已完成）

- [x] S1 `RoomManager.isValidCode` 放宽为 4-6 位数字（`/^[0-9]{4,6}$/`）
- [x] S2 `rooms.create(code, hostWs, hostUserId, coupleOnly)` 支持 couple 标记；新增 `isCoupleRoom(code)`、`getHostUserId(code)`
- [x] S3 `CoupleManager.areCouple(a,b)` 双向 OR + 实时查库（解绑自动失效）
- [x] S4 `server.js` create-room 读 `msg.couple` 传 rooms.create
- [x] S5 join 分支：coupleOnly 房间校验 areCouple，拒绝「仅情侣可加入共享房间」
- [x] S6 share-invite 分支：coupleOnly 走情侣校验，否则走好友校验
- [x] S7 文案 4 位 → 4-6 位；单测 21 项全过（`/tmp/opencode/couple-room-test.js`）
- [x] S8 8095 重启加载新代码

## 客户端（已完成）

- [x] C1 `SignalClient.connect` 加 `coupleOnly` 参数，create 消息 `put("couple", true)`
- [x] C2 `MainActivity` 新增 `EXTRA_COUPLE_ROOM`；create 分支读取并传 connectSignal；created 回调保存房间号
- [x] C3 `CoupleShareStarter`（新建）：弹窗预填上次房间号、6 位本地校验（格式错保留弹窗）、跳 MainActivity
- [x] C4 `fragment_couple.xml` boundView 加「共享房间」卡片；`CoupleFragment.renderSpace` 绑定点击 + 预填提示
- [x] C5 assembleDebug 编译通过
- [x] C6 版本号 414/1.409；allarch + arm64 签名包生成并覆盖 8090

## 待办

- [ ] T1 用户真机复测：建情侣房间 → 对方加入 / 非情侣加入被拒 / 房间号记住与修改
- [ ] T2 复测通过后更新 version.json 正式发版
