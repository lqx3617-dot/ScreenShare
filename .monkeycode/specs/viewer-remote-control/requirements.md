# Requirements Document

## Introduction

为屏幕共享应用新增「观看方远程控制共享方」能力。观看方在共享画面上进行触摸操作，指令通过 WebRTC 控制数据通道（CONTROL_LABEL）下发，共享方侧由无障碍服务（AccessibilityService）模拟执行，实现远程操控对方手机。

## Glossary

- **共享方（被控端）**：发起屏幕共享、被远程操作的手机
- **观看方（控制端）**：加入会议、观看共享画面并发起控制操作的手机
- **控制模式**：观看方在共享画面上启用触摸转发而非本地缩放的状态
- **控制指令**：观看方经控制数据通道发送给共享方的操作描述（JSON）
- **无障碍服务**：Android AccessibilityService，模拟触摸/按键/文本输入的唯一合法入口
- **归一化坐标**：将观看方画面坐标换算为 0~1 的横向/纵向比例值，供共享方按真实屏幕分辨率还原

## Requirements

### Requirement 1：共享方无障碍服务接入

**User Story:** AS 共享方，I want 开启无障碍服务接收控制指令，so that 观看方能远程操控我的手机

#### Acceptance Criteria

1. WHEN 共享方安装应用并进入无障碍服务设置列表，系统 SHALL 展示「屏幕共享远程控制」无障碍服务，用户 SHALL 可手动开启
2. WHILE 无障碍服务处于开启状态，应用 SHALL 在共享界面显示「远程控制已就绪」状态
3. WHILE 无障碍服务未开启，应用 SHALL 在共享界面显示「未开启无障碍服务，观看方无法控制」提示，并提供一键跳转无障碍设置入口
4. IF 无障碍服务在会议中被系统关闭，应用 SHALL 提示共享方重新开启
5. 无障碍服务 SHALL 仅解析控制数据通道内容为合法指令后执行，其他来源消息 SHALL 忽略

### Requirement 2：观看方控制模式

**User Story:** AS 观看方，I want 一键切换控制模式，so that 我的触摸能下发到共享方而不是本地缩放

#### Acceptance Criteria

1. WHEN 观看方进入控制模式，共享画面上的单指操作 SHALL 作为控制指令下发，双指捏合仍 SHALL 保持本地缩放
2. WHEN 观看方退出控制模式，触摸操作 SHALL 恢复为本地缩放与单击复位
3. WHILE 控制通道未建立或连接未就绪，控制模式开关 SHALL 禁用并提示原因
4. WHEN 控制指令下发且被控端无障碍服务未开启，应用 SHALL 在观看方显示「对方未开启无障碍服务」提示
5. 退出会议或连接断开时，应用 SHALL 自动退出控制模式

### Requirement 3：触摸操作支持

**User Story:** AS 观看方，I want 点按、滑动、长按操作，so that 我能操控共享方屏幕

#### Acceptance Criteria

1. WHEN 观看方在控制模式下单指按下、移动、抬起，共享方 SHALL 依次执行对应 DOWN/MOVE/UP 触摸动作
2. WHEN 观看方单指按下后保持 500 毫秒未移动，共享方 SHALL 执行长按（长按压）动作
3. WHEN 观看方在画面上快速轻点，共享方 SHALL 执行一次点击（DOWN+UP）
4. 控制指令 SHALL 以归一化坐标描述触点位置，共享方 SHALL 按真实屏幕尺寸还原为像素坐标执行
5. 触摸事件延迟（观看方触控到共享方执行）SHALL 小于 200 毫秒

### Requirement 4：系统级操作支持

**User Story:** AS 观看方，I want 下发返回、主页、最近任务等系统按键，so that 我能完整操控共享方手机

#### Acceptance Criteria

1. WHEN 观看方点击「返回」按钮，共享方 SHALL 执行全局返回动作
2. WHEN 观看方点击「主页」按钮，共享方 SHALL 执行全局回到桌面动作
3. WHEN 观看方点击「最近任务」按钮，共享方 SHALL 执行全局最近任务动作
4. 系统按键操作失败（无障碍权限丢失等）时，观看方 SHALL 收到失败提示

### Requirement 5：文本输入支持

**User Story:** AS 观看方，I want 下发文本到共享方输入框，so that 我能输入文字

#### Acceptance Criteria

1. WHEN 观看方点击「输入文本」并提交文字，共享方 SHALL 将文字写入当前聚焦的输入框
2. WHEN 共享方当前无聚焦输入框，文本写入失败，观看方 SHALL 收到失败提示
3. 文本内容 SHALL 经控制数据通道以 UTF-8 传输

### Requirement 6：坐标映射与画面适配

**User Story:** AS 观看方，I want 触摸位置准确对应共享方屏幕，so that 控制不偏移

#### Acceptance Criteria

1. 坐标映射 SHALL 考虑共享画面在观看方的显示模式（等比完整、铺满裁切）、缩放与旋转方向
2. 铺满裁切模式下，画面外区域触摸 SHALL 不产生控制指令
3. 共享方屏幕方向（横竖屏）变化时，坐标映射 SHALL 跟随最新画面尺寸计算

### Requirement 7：安全性

**User Story:** AS 共享方，I want 控制操作受限且可控，so that 我的手机不被恶意操控

#### Acceptance Criteria

1. 控制指令 SHALL 仅经已建立的 WebRTC 加密数据通道传输
2. 共享方 SHALL 提供「停止远程控制」开关，开启后观看方所有控制指令 SHALL 被忽略
3. 无障碍服务 SHALL 不读取也不上传任何屏幕内容、剪贴板或账号信息，仅执行指令
4. 会议退出或数据通道断开时，共享方 SHALL 停止执行后续控制指令
5. 无障碍服务 SHALL 仅在会议进行中执行控制指令，非会议状态收到指令 SHALL 忽略
