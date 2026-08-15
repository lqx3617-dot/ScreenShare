# 需求实施计划

- [ ] 1. 服务器端：发布接口与异步构建任务
  - [ ] 1.1 修改 /workspace/server/download-server.js：新增持久化 release-config.json（changelog/forced 读写）、内存 RELEASE_CONFIG 初始化从文件加载
  - [ ] 1.2 实现单任务互斥队列（currentTask 检查，存在返回 409）+ PublishTask 对象（id/state/phase/versionName/app/log/error/bumpedBackup）
  - [ ] 1.3 实现 POST /api/publish 路由：JSON 解析 + 校验（versionName `^\d+\.\d+$`、changelog 非空、app ∈ {main,albumviewer,both}，非法返回 400）→ 入队 → 立即返回 {taskId}
  - [ ] 1.4 实现 GET /api/publish/status 路由：?task= 查任务返回 {state,phase,versionName,error,log}，不存在返回 404
  - [ ] 1.5 实现构建任务执行函数：bump 版本号（build.gradle.kts versionCode+1/versionName 改值，备份原内容）→ gradle assembleRelease → zipalign+apksigner 签名两个 APK → 更新 release-config.json + 内存 changelog → state=success
  - [ ] 1.6 失败回滚：任一环节异常 → 恢复 bump 前 build.gradle.kts → state=failed+error；APK 原子替换（临时文件+rename）防半截文件
  - [ ] 1.7 构建期间不阻塞下载：异步执行（不 await 在请求处理里），下载路由保持原有行为
  - [ ] 1.8* 接口层 curl 测试：合法→200/重复→409/非法版本号→400；轮询 status 到 success；失败注入→回滚验证

- [ ] 2. AlbumViewer 端：隐藏入口与发布面板
  - [ ] 2.1 gradle.properties 新增 screenshare.download.url；albumviewer/build.gradle.kts defaultConfig 新增 buildConfigField PUBLISH_URL
  - [ ] 2.2 输入页顶部标题连点 7 次（相邻间隔 ≤800ms）触发 showPublishPanel()，时间戳数组计数
  - [ ] 2.3 新增 PublishApi.kt：publish(versionName,changelog,app) -> taskId、publishStatus(taskId) -> state/phase/error，复用 OkHttpClient 模式
  - [ ] 2.4 新增发布面板 Dialog：当前版本显示 + 版本号输入（正则校验）+ changelog 输入 + 目标 RadioGroup（主APP/相册查看/两者，默认两者）+ 发布/取消按钮 + 状态区
  - [ ] 2.5 发布流程：校验→POST→轮询每2s→成功显示新版本号/失败显示 error+允许重试；取消=停止轮询关闭
  - [ ] 2.6* 客户端手动验证：连点入口→输入发布→进度到成功→version.json 更新

- [ ] 3. 检查点 - 发布全链路验证（curl publish→轮询 success→version.json 新版本→md5 一致→apksigner verify 通过→主 APP 检查更新发现新版本）
