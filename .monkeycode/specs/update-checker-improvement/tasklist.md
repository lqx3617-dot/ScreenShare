# 需求实施计划

- [x] 1. 服务端 version.json 扩展（design.md Components 1）
  - [x] 1.1 download-server.js 新增 forced 与 changelog 字段
    - 新增 RELEASE_CONFIG 发布配置（changelog 多行说明 + forced 强制开关），getVersion() 返回该字段
    - 下载服务已重启，curl 验证字段正常返回

- [x] 2. UpdateChecker 版本检查与提示优化（design.md Components 2/Correctness）
  - [x] 2.1 静默检查 12h 节流
    - 非 manual 时读 SharedPreferences `update_auto_check_ts`，距今 <12h 跳过；请求成功（无论结果）后更新时间戳
  - [x] 2.2 promptUpdate 增强
    - 标题「发现新版本 v${versionName}」；内容含 changelog（无则 note）、当前/目标版本对比、包大小
    - forced=true 时无「暂不」按钮（仅「立即更新」）

- [x] 3. 下载通知栏 + 4 线程 + 重试 + 文件复用（design.md Components 2）
  - [x] 3.1 通知栏进度
    - NotificationCompat + NotificationManagerCompat（channel update_channel），标题含版本号、进度条、下载速度、取消 Action（BroadcastReceiver），全程 applicationContext；完成/取消/失败均清除通知；无通知权限降级 Activity 内进度条
  - [x] 3.2 4 线程下载 + 分段重试
    - THREAD_COUNT=4；downloadToFile 每段失败单独重试至多 3 次（RETRY_TIMES），全部耗尽才整体失败；取消时正常退出
  - [x] 3.3 已下载文件复用
    - 目标文件存在且 MD5 匹配 → 直接安装；不匹配删除重下；清理仅删非当前版本旧包
  - [x] 3.4 清单新增 POST_NOTIFICATIONS 权限
    - AndroidManifest.xml 增加 POST_NOTIFICATIONS 声明

- [x] 4. 检查点 - 编译通过
  - assembleDebug 编译通过（contentLengthLong 修正后成功）

- [x] 5. 构建签名发布 v1.67
  - versionCode=68/versionName="1.67"，assembleRelease + zipalign + apksigner 签名（md5 c5d6bbe4574ea2beee40559c4da50411）
  - 更新 download-server.js 的 changelog/note 并重启下载服务（/version.json 已验证）
  - 更新 tasklist 完成状态
