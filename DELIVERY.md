# 交付说明（2026-09-21）

本次在上游 `AbelDuan/Freeform`（LSPosed 模块 OS4FreeFromX，HyperOS 4 / Android 17 / 折叠屏 lhasa）
基础上新增**两个全局手势**，两个功能均已在本机真机逐条取证。

## 一、功能与取证

### ① 左右下角斜向中间滑 → 前台应用转小窗 ✅ 真机验证通过
```
手势: 角滑命中 侧=左 行程=1013px dx=760 dy=-670 用时=321ms 开关=true
角滑: 前台 pkg=top.funcun.dshfolk task=6472 mode=1
角滑: 已请求以小窗启动 top.funcun.dshfolk（x=140 y=1620）
→ dumpsys activity activities: Task #6472 mode=freeform
```
- 走官方 `MiuiMultiWindowUtils.getActivityOptions(ctx, pkg, true, x, y)`，小窗落在起手点附近，不重开应用
- **只在单应用全屏时生效**（分屏/多分屏/已有小窗一律放行给系统，避免抢 MIUI 自己的分屏热区）
- 判定参数：起手区 = 屏幕下部左右各 22% 宽 × 下 22% 高；方向比 0.75~1.35（明显 45°）；最小行程 200dp
- 底部正中 220dp × 中间 1/2 宽为**让位区**（留给 MIUI「底部中间上滑进多分屏」）

### ② 四指上滑 → 增加分屏
- **全屏场景 ✅ 真机验证通过**：
```
手势: 四指上滑命中(MOVE) 行程=127px 用时=191ms 手指数=4
四指上滑: 选中 pkg=com.tencent.mm task=5805（候选池 18）
四指上滑: 已请求系统分屏吸附（openWindowFromFullscreen task=5805 pkg=com.tencent.mm）
→ dumpsys: rootTaskId=6661 下 main/side 双 stage 均 mode=multi-window
```
- **分屏内（灰度 `four_finger_split_indoor`，默认关）**：结构能建出、不闪退；
  「新那一侧黑屏」已修（改用 `insertMultipleSplitByIntent`，由系统启动应用进 stage），待最终实测

## 二、开关（模块设置界面）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `gestures` | true | 手势总开关（关掉后模块完全不介入触摸事件） |
| `corner_freeform` | true | ① 角落斜滑转小窗 |
| `four_finger_split` | true | ② 四指上滑加分屏 |
| `four_finger_split_indoor` | **false** | 灰度：分屏内也用四指加分屏（风险较高） |
| `test_hook` | false | 调试：adb 可单独驱动"加分屏"做回归测试 |

**紧急止血**：任何手势导致异常时，把 `gestures` 写 false 并重启 SystemUI 即可；
配置文件在 CE 与 DE 两处（`/data/data/…/shared_prefs/` 与 `/data/user_de/0/…/shared_prefs/`），
改完需 `am force-stop com.abel.os4freeformx` 让模块 App 重读盘。

## 三、实现要点（两个手势共用一个全局输入源）

`MulWinSwitchEventController$EventReceiver#onInputEvent` —— MIUI 自己用
`InputManager.monitorGestureInput` 建的**全屏触摸监视器**，挂它即可拿到
全屏/桌面/小窗/分屏的所有触摸事件，**不需要任何额外权限**。

## 四、踩坑与结论（详见 NOTES-resize-handle.md 第 10~23 节）

1. **LSPosed 重装后模块不注入**：库里 `modules.apk_path` / `modules_state` / `scope` 三张表不随安装更新
   → `tools/fix-lsposed-module.sh` 一键修（每次 `adb install` 后跑一次）
2. **MIUI 线程断言** `IllegalStateException: must be called on Handler`
   → 把调用投到 `Transitions.getMainExecutor()`（解开了项目 NOTES 里挂了很久的老问题）
3. **四指手势 5 处 bug**：撤销条件过严 ×2、阈值过严、松手用当前指针数、
   **判定时机在 ACTION_UP（MIUI 多指通道根本不投递 ACTION_UP）→ 改到 MOVE 上触发**
4. **`transferSoScToMultipleSplit` 参数语义**：官方是 **stage 对象列表 + 索引[0,1]**，
   早期误传 taskId 列表 → SoSc 状态机崩（黑屏/闪退）
5. **`insertMultipleSplitByTask` 塞已有任务时 stage 全空（`sz=0`）** → 新那一侧黑屏
   → 改用 `insertMultipleSplitByIntent`（系统自己启动应用进 stage，等价于原生"那一侧选应用"）
6. **配置读取**：`Cfg` 是节流异步刷新，手势命中时需 `Cfg.reload()` 强制同步读，否则用启动快照

## 五、构建

```bash
./check.sh                      # 主机侧算法自检
./build.sh                      # 产出 dist/OS4FreeFromX-v0.1.1.apk（arm64 容器离线链路）
adb install --no-incremental -r dist/OS4FreeFromX-v0.1.1.apk
./tools/fix-lsposed-module.sh   # 重装后修 LSPosed 三张表并重启 lspd + SystemUI
```
工具链重建说明见 `tools/README.md`（容器 /tmp 会被清空，工具全部落在仓库 `tools/`）。
