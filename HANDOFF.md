# 交接文档：多分屏（3/4 分屏）开发状态

> 面向：在**另一台设备**上继续本项目的开发者 / AI。
> 结论时点：2026-09-21。
> 设备约束见 [§6](#6-设备约束必读)。

---

## 1. 项目目标

在 HyperOS 4（Android 17，KernelSU root）上，为 LSPosed 模块 `com.abel.os4freeformx`
实现**四指手势驱动的多分屏**：

| 手势 | 目标行为 |
|---|---|
| 全屏应用「四指上滑」 | 进入 **2 分屏**（原生 SoSc） |
| 分屏中「四指上滑」 | **逐级增加**：2 → 3 → 4 分屏 |
| 2 分屏「四指左滑/上滑」 | 触发系统自己的「收起两侧 → 选第 3 个应用」界面 |

**核心验收标准**：分屏建立后**每个应用窗口都可以被触摸操作**（这是最难的一点）。

---

## 2. 当前状态一览

| 能力 | 状态 | 说明 |
|---|---|---|
| 全屏 → 2 分屏（原生 SoSc） | ✅ **可用且可触摸** | `startIconDragSplitScreen(pi, hotArea=1, 0)` |
| 2 分屏 → 3 分屏（`startMultipleSplits`） | ⚠️ 布局对、**输入死** | 见 [§3.1](#31-死路-startmultiplesplits)，**已放弃** |
| 2 分屏 → 3 分屏（**Dock 模式**） | 🎯 **已定位、待实测** | 见 [§4](#4-待验证方案dock-路径核心) |
| 3 分屏 → 4 分屏 | 🎯 同上，走 `dockMultipleTasks()` | 同 §4 |
| 分屏 UI 装饰隐藏（沉浸） | ✅ 已实现 | 与触摸问题**无关**（已排除） |

---

## 3. 已证实的结论

### 3.1 死路：`startMultipleSplits`

模块曾用 `MultipleSplitController.startMultipleSplits(List<Integer>)` 直接点名 task 列表造 3 分屏。

- **画面正常**：三列（calculator / coolapk / deskclock）各占独立 bounds，渲染无误。
- **输入全死**：三个应用都点不动，只有导航栏可用。决定性证据（`dumpsys input` / `dumpsys window`）：
  - `FocusedWindows: <none>`，`mCurrentFocus=null`
  - 三个应用窗口 `inputConfig = NO_INPUT_CHANNEL | NOT_FOCUSABLE | NOT_TOUCHABLE | PAUSE_DISPATCHING`
  - `touchableRegion=<empty>, token=0`，并带 `miuiEmbeddedHotRegion/MidRegion`
    → MIUI 把它们当 **embedded 窗口**，AIDL 路径**从未给它们建输入通道**
  - `FocusRequests: name='...' result='NO_WINDOW'`
- **已排除的因素**：不是沉浸 hook（`MultipleSplitUIContainer` 置 INVISIBLE 是 SurfaceControl surface，不是 Window；
  关掉 immersive 重启 SystemUI 后依旧不可触摸），不是残留 picker 遮罩。
- **结论**：**模块不能自己合成多分屏**——必须让系统自己走完整的 transition（含焦点转移 + 输入通道建立）。

### 3.2 可用入口：`startIconDragSplitScreen`

```java
// MulWinSwitchTransition
void startIconDragSplitScreen(PendingIntent pi, int hotAreaType, int reason)
```

**必须投递到 `Transitions.mainExecutor` 执行**，否则
`IllegalStateException: must be called on Handler`（`HandlerExecutor.assertCurrentThread()`）。

取 executor 的链路：
```java
MultiTaskingControllerImpl.getInstance()
    .getMulWinSwitchTransition()
    → 字段 mTransitions → getMainExecutor()
```

- 从**全屏**调用 `hotArea=1`（`HOT_AREA_TYPE_SPLIT_LEFT_OR_TOP`）
  → 得到**真·原生 SoSc 双分屏**（左：桌面 / 右：应用），`mInSplitScreen=true`，
  `FocusedWindows` 命中应用，**实测点计算器「7」生效（显示 70）** ✔
- ⚠️ **基座必须是真 SoSc**。模块自造的 2 分屏（`SoSc=false 多分屏=false`）上再调它
  → 误触发 → **SystemUI 重启**。

**hotArea 码表**（`MultiTaskingHotAreaController`）：

| 值 | 含义 |
|---|---|
| 0 | 全屏 |
| 1 | SPLIT_LEFT_OR_TOP |
| 2 | SPLIT_RIGHT_OR_BOTTOM |
| 3 | FREEFORM |
| 4 | BAR_OPEN |
| 5 | FREEFORM_MINI |
| 6 | MULTIPLE_SPLIT |
| 7 | SPLIT_QUICK_VIEW_MODE |
| 8/9/10 | TWOSPLIT_INSET_LEFT/MIDDLE/RIGHT |
| 16 | MULTIPLE_SPLIT_REPLACE |
| 19 | MULTIPLE_SPLIT_ADD |

实测在**真 SoSc 2 分屏**上叠 `hot=6/8/9`：SystemUI 稳定、焦点停在应用；
`hot=7 和 19` → **SystemUI 重启**（各复现一次）。

### 3.3 用户手势语义（纠正早前误读）

- 「单应用三指左滑 → 贴侧屏 + 另一侧桌面选应用」、
  「双分屏下三/四指上滑 → 单侧分屏多任务界面」
  —— **都是系统原生能力**，模块**不需要实现**，只需不破坏它们。
- 模块要做的「四指逐级分屏」：
  - 单任务四指上滑 → `openWindowFromFullscreen` → 2 分屏
  - 分屏中四指上滑 → `fourFingerAddSplit` 路由加一层
- **不要**把分屏下的四指语义改成「单侧多任务界面」，保持现状。

---

## 4. 待验证方案：Dock 路径（核心）

### 4.1 系统真实链路（用户真机演示 + 框架 verbose 日志，18:29:08.3 ~ 18:29:10.6）

```
MultipleSplitRootTaskOrganizer.dockMultipleSplitTasks: TaskInfo{taskId=7705 ...}
SoScStageCoordinator.onPreMultiWindowChangedForMultipleSplit  PreState:TO_DOCK
SoScStageCoordinator.onMultiWindowStateChangedForMultipleSplit state:TO_DOCK MultipleSplitTaskIds:[7705, 7703]
MultipleSplitTransitionHandler.startDockChangeTransition(transitType=3, extraTransitType=11286, index=2)  // 11286=DOCK_ENTER
MultipleSplitRootTaskOrganizer.dock sosc to Rect(-2294, 0 - 70, 1672)      // 2分屏整体挪出屏外
MiuiSplitScreenImpl.updateDockedState, state from 0 switch to 1, launchRootTask : Task{#7694 name=stage_c}
MultipleSplitTransitionHandler.playDockChangeAnimation DOCK_ENTER
   ↓ 用户选中第3个应用（小红书 7706）
MultipleSplitTransitionHandler.requestOpenToExitDockMode, isOpening:true, mode:6, stage:mId STAGE_C taskId:7694
   ↓ 调用栈（18:29:09.530）：
   MultipleSplitRootTaskOrganizer.prepareDragTaskToMultipleSplit(:214/217/220)   // 建 STAGE_A/B/C
    ← MultipleSplitTransitionHandler.handleOpenToExitDockMode
    ← MultipleSplitUtilsImpl.handleOpenToExitDockMode
    ← DefaultMixedHandler.handleRequestOnly
    ← Transitions.requestStartTransition
MultipleSplitTransitionHandler.playDockChangeAnimation: extraType=DOCK_EXIT_SOSC_TO_THREE (11288) index=2 dockOffset=-2294
MultipleSplitRootTaskOrganizer.finishEnterMultipleSplit(:81) → 三分屏落地
```

**三分屏列位**：STAGE_A `Rect(94,66-802,1606)` / STAGE_B `Rect(826,66-1534,1606)` / STAGE_C `Rect(1558,66-2266,1606)`
**四分屏**：stage_a/b/c/d（taskId 7692/7693/7694/7695）

> 教训：探针只命中了 `SoScCoord#isMainStageRootTask` 这类无关方法（SOSC 关键字表太窄），
> 但**框架自己的 verbose 日志已经把整条链路写全了**。
> → **先读框架 tag 日志，再决定 hook 谁**。

### 4.2 反编译出的确切 API

`/system_ext/framework/Miui-WindowManager-Shell.jar` → `classes.dex`（`javap` 读不了，用 `dexdump -p` / `dexdump -d`）：

```java
// MultipleSplitRootTaskOrganizer.dockMultipleSplitTasks(Bundle) : Bundle
public Bundle dockMultipleSplitTasks(Bundle b) {
    RunningTaskInfo task = (RunningTaskInfo) b.getParcelable("multiple_split_dock_task");
    if (task == null)                                  return null;  // "Dock task param error."
    if (mDockedState != null)                          return null;  // "Already in dock mode…"
    if (getCurrentActiveStageCount() >= MAX_STAGES)    return null;  // "Above max stages…"
    if (isMultipleSplitActive())                       return dockMultipleTasks();  // ★ 3分屏→4分屏
    if (SoScUtils.getInstance().inSoScFullMode())      return dockSoScTasks();      // ★ 2分屏→3分屏
    return null;
}
```

- **只有一个人参 key**：`"multiple_split_dock_task"`，值是 `ActivityManager$RunningTaskInfo`
- 输出 Bundle 含 `"multiple_split_dock_support"`(boolean；高温/低内存时 false → 弹 toast)
- `dockSoScTasks()` 内部：`buildHomeToFront(wct)`（**这就是「两个任务收起、桌面出现」**）、
  `setSoScRootForceTranslucent(true)`、`wct.setFocusable(soscRoot,false)`、
  **`mDockedState = new DockedState(...)`（关键状态位）**、`wct.setDockedState(stageA.token, 1)`、
  把 SoSc 根按 `getOffsetWithDockedState()` 挪出屏外（-2294），最后 `mMainExecutor.execute(applyWct)`

**常量**：`EXTRA_TRANSIT_TYPE_STAGE_DOCK_EXIT_SOSC_TO_THREE` = **11288**（在 `MultipleSplitOrganizer`），DOCK_ENTER = **11286**

**命名**：`MultipleSplitOrganizer` 是接口，`MultipleSplitRootTaskOrganizer` 是实现，
`MultipleSplitController`（同包）是转发器（`getInstance()` 可拿，模块已用）。
另有 `MultipleSplitUtilsImpl` / `MultipleSplitTransitionHandler` / `AbsMultipleSplitTransitionHandler` 同族转发。

### 4.3 落地步骤（设备恢复后立刻测）

1. **造真原生 SoSc 双分屏**：`DRAGADD:1` → `startIconDragSplitScreen(pi, hotArea=1, 0)`（已实测可触摸）
2. **调 Dock**：`DOCKADD:<pkg>` →
   `MultipleSplitController.getInstance().dockMultipleSplitTasks(bundle{"multiple_split_dock_task" = RunningTaskInfo})`
   → SoSc 收起、桌面出现（等价于「拖到左上」）
3. **`am start` 启动第 3 个应用** → 系统自己走 `requestOpenToExitDockMode`
   → `DOCK_EXIT_SOSC_TO_THREE` → **原生三分屏**
4. **第 4 个**：在三分屏上重复步骤 2（此时走 `dockMultipleTasks()`）→ 再启动第 4 个应用

**预期**：全程由系统建窗，输入通道完整 → **可触摸**（不再出现「布局对、输入死」）。

**注意**：`MAX_STAGES` 的静态值 `dexdump` 读不到（显示 0），运行时反射读。

---

## 5. 代码与工具地图

```
app/src/main/kotlin/com/abel/os4freeformx/
  Gestures.kt      ★ 主逻辑；测试命令入口：
                     MULTI: / MULTIQ: / FW: / DRAGADD: / DOCKADD:
  SplitTrace.kt    ★ 新增诊断基建：136 个探针，按方法名记录分屏入口调用
  Hooks.kt           注册 SplitTrace；immersive 模式隐藏 MultipleSplitUIContainer
  Constants.kt       DEF_IMMERSIVE 等默认值
tools/adb/
  test17_bash.sh   ★ 端到端测试：双分屏 → Dock → 三分屏 → 四分屏（自动探测设备）
  test3~16_bash.sh   历史迭代测试脚本
  cap.sh             设备端 logcat 抓取（nohup 常驻，跨轮持久）
docs/recon/        逆向产物：demo_A/B/C.txt（用户演示链路）、dock_body/helpers.txt（反汇编）、
                   entry_sigs.txt（签名清单）、*.py（分析脚本）
docs/logs/         test*_win*.txt（窗口 dump）、test*b.log（测试输出）、diag*.log
```

### 5.1 测试命令跑法

```bash
bash tools/adb/test17_bash.sh                 # 自动探测设备
DEV=<serial> OUT=/tmp/out bash tools/adb/test17_bash.sh   # 指定设备/输出目录
```

### 5.2 构建

```bash
# Windows 手动 kotlinc 构建（无 gradle）
powershell -File build_ps.ps1
# 产物：dist/OS4FreeFromX-v0.1.0.apk
# 安装（降级装需 -d）：adb install -r -d dist/OS4FreeFromX-v0.1.0.apk
# 加载新模块（安全，不掉 KSU root）：kill $(pidof com.android.systemui)
```

### 5.3 SplitTrace 已挂的类

| 类 | 探针数 |
|---|---|
| `MulWinSwitchTransition` | 15 |
| `MultipleSplitController` | 23 |
| `MultipleSplitRootTaskOrganizer` | 3 |
| `MultiTaskingHotAreaController` | 47 |
| `SoScStageCoordinator` | 48（**窄关键词，别全挂——它有 475 个方法会刷爆**） |

**踩坑**：Kotlin 字符串模板里 `"$r8$lambda"` / `"-$$Nest$"` 会被当成变量引用
→ 编译错 `unresolved reference: r8/Nest`，必须写成 `"\$r8\$lambda"` / `"-\$\$Nest\$"`。

---

## 6. 设备约束（必读）

### ⚠️ 铁律：**绝不重启设备**

- 调试机是**临时越狱 root**，**重启 = 掉 root**。
- ❌ 禁止 `adb reboot` / `reboot` / `svc power reboot` / 任何形式重启关机。
- ❌ 也不要「重启一次让它生效」。
- ✅ 需要 SystemUI 重载时用：`kill $(pidof com.android.systemui)` 或 `am crash com.android.systemui`
  —— **安全，不掉 KSU root**。
- ✅ KSU 模块升级：把 `/data/adb/modules_update/<id>/` 合并回 `/data/adb/modules/<id>/`，
  删掉模块根目录的 `update` 标记，再 `ksud services`（**别靠重启**）。

### 设备细节

- 旧机：`2608BPX34C` / `lhasa`；序列号 `02040860499C3540`（**已拔线，脚本里已改为自动探测**）
- 新机：**型号未核**，有 root，锁屏密码同旧机
- 屏幕：2364×1672（ROTATION_90，物理 1672×2364）；`input tap` 用**截图同向坐标**
- 锁屏是 swipe-only：`input keyevent KEYCODE_WAKEUP` +
  `input swipe 836 2000 836 700 200` 即解锁（**不要瞎猜 PIN**）
- 截图：**别用 `adb exec-out screencap -p`**（会损坏 PNG）
  → 用 `screencap -p /sdcard/x.png` + `adb pull`

---

## 7. 下一步 TODO（按优先级）

1. **新设备接入**：确认 `adb devices` 可见、RSA 已授权；核对型号与框架签名是否同旧机
2. **实测 Dock 路径**：跑 `test17_bash.sh`，确认 2→3→4 分屏**可触摸**
3. 若成功 → 把 Dock 路径接进 `fourFingerAddSplit`（四指上滑触发）
4. 若失败 → 回到 §4.2 反编译，检查 `dockSoScTasks` 的 WCT 是否漏了什么
   （重点看 `mDockedState` 与 `setDockedState(stageA.token, 1)`）
5. 回归验证：确认修复后**不再**出现「四指二次卡死 SystemUI」

### 已知未决风险

- `hot=7 / hot=19` 在真 SoSc 上会**重启 SystemUI** → 这两个热区码要避开
- 模块自造的分屏（非真 SoSc）上调用分屏 API → **SystemUI 重启**
- `git stash@{0}` 仍保留旧 WIP（`WIP background-ratio PROBE`），与本文档无关，未动
