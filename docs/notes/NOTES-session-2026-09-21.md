# 2026-09-21 工作日志

## OS4FreeFromX：四指二次卡死 SystemUI 的根因与修复

- 复现路径（用户）：全屏应用四指上滑→进入双分屏(OK)→退出→开新应用→四指上滑→失败+卡顿+音频刺啦→黑屏→锁屏（SystemUI 重启）。
- 抓取 logcat + `dmesg` 级别错误后定位：
  - ANR 在 `com.android.systemui`：Reason = `Input dispatching timed out ([Gesture Monitor] MultiTaskSwitch is not responding. Waited 5000ms for MotionEvent(action=MOVE))`。
  - 系统 CPU 出现内核态自旋（tr/AnrAuxiliaryTas 占 54~74% kernel）→ 音频线程被饿（刺啦）。
  - 第二次四指时模块**未打"四指起手"日志** → 说明该手势被系统 `MultiTaskSwitch` 监视器优先接管（pilfer），我们 hook 的 MulWinSwitch 没收到。
- 根因：模块把"装饰隐藏"错误地挂在了 **2 分屏**的装饰管理器上：
  - `Hooks.kt` 的 `hideSoScDecor` 挂在 `SoScSplitDecorManager`（2 分屏装饰），且 `SurfaceControl.Transaction#show` hook 匹配了 `SoScSplitDecorManager` 去**抑制 2 分屏装饰 surface 的 show**。
  - 这与用户明确要求"两分屏保持官方原样（Hooks.kt:126）"冲突；更严重的是：退出 2 分屏(dissolve)时系统要 show 某些装饰 surface 做退场，被抑制 → 重组 transition 卡死 shell 主线程 → 第二次四指的系统 MultiTaskSwitch 监视器被坏状态拖死 → 5 秒不响应 → SystemUI 被杀重启。
  - 附带修了一个 harmless 的 AIOOBE：`hideSoScDecor` 里 `chain.getArg(2/3)` 在参数不足 3 个的方法上越界（用 runCatching 兜底取值修掉）。
- 修复（已构建安装并重启 SystemUI）：
  1. `show` hook 只匹配 `MultipleSplitUIController`（3+ 真·多分屏），**不再匹配 `SoScSplitDecorManager`**（2 分屏正常 show）。
  2. `hideSoScDecor` 用 `multiSplitActive()`（activeStages>=3）门控，只处理 3+ 分屏。
- 构建：`C:/AndroidBuild/build_freeform_win.sh` → `dist/OS4FreeFromX-v0.1.0.apk`（v0.1.0/vc=1，降级装到已装 0.1.1 设备需 `adb install -r -d`，签名匹配）。装后 `kill $(pidof com.android.systemui)` 重启加载新模块（安全，不掉 KSU root）。

## 用户手势语义澄清（重要，纠正先前误读）
- 用户给的"单应用三指左滑→贴侧屏+另一侧桌面选应用"、"双分屏下三/四指上滑→单侧分屏多任务界面"**都是系统原生能力**，模块不需要实现，只需不破坏它们（修复 2 分屏 teardown 即达成）。
- **模块要做的"四指逐级分屏"本来就已在代码中实现**：
  - 单任务四指上滑 → `openWindowFromFullscreen` → 双分屏；
  - 分屏中四指上滑 → `fourFingerAddSplit` 路由到 `startMultipleSplits(cur,null)`（cur 每次 `splitTaskIds()` 实时重查当前子任务）→ 加一层，链式 2→3→4→…（对应系统拖角逐级分屏逻辑）。
- 因此：不要改动四指在分屏下的语义（不要把它改成"单侧多任务界面"）；保持现状即可。三分屏及以上入口=四指在分屏中上滑（`startMultipleSplits`），系统原生"底部中间上滑进多分屏"也仍可用。
- 待办：修复卡死后，验证 2→3→4 四指链式分屏是否稳定可用（这是"三分屏及以上开发测试"的主线）。

## 备注
- `git stash@{0}` 仍保留旧 WIP：`WIP background-ratio PROBE (candidate B + DIAG + PROBE, device offline, unfinished)` —— 与本次无关，未动。
- 当前分支 main 已 fast-forward 到 13c171d（GitHub 多分屏代码）。本次修复为未提交本地改动（待验证后再 commit/push）。

---

## 【重大更正】`startMultipleSplits` 产出的多分屏"布局对、输入死"

- 现象（用户实测）：3 分屏**画面正常渲染**（calculator | coolapk | deskclock 三列，各占独立 bounds），
  但**三个应用都点不动**，导航栏还能用。
- 决定性证据（`dumpsys input` / `dumpsys window`）：
  - `FocusedWindows: <none>`，`mCurrentFocus=null`；三个应用 `topResumedActivity` 都在（是 resumed），
    但 WMS `mFocusedApp` 停在**过期的 Settings** activity → 多分屏建立时**没有转移输入焦点**。
  - 三个应用窗口的 `inputConfig = NO_INPUT_CHANNEL | NOT_FOCUSABLE | NOT_TOUCHABLE | PAUSE_DISPATCHING`，
    `touchableRegion=<empty>, token=0`，并带 `miuiEmbeddedHotRegion/MidRegion` → MIUI 把它当成 "embedded" 窗口，
    AIDL 路径从没给它们建输入通道。
  - `dumpsys input` 里 `FocusRequests: name='...deskclock/...' result='NO_WINDOW'`（shell 有请求焦点，WMS 说没有窗口）。
  - 已排除：不是沉浸 hooks（`MultipleSplitUIContainer` 置 INVISIBLE 那套，是 SurfaceControl surface 不是 Window）、
    不是残留 picker 遮罩（窗口栈上方 22 个都是 NOT_TOUCHABLE 系统层）。
  - 关掉 immersive (`immersive=false`，CE+DE 两个 prefs) 重启 SystemUI 后**依旧不可触摸** → 与 immersive 无关。
- 与 AGENTS.md 里"多分屏已证伪（黑屏/重启）"一致：**模块不能自己合成多分屏**。

## 【关键突破】原生入口 `startIconDragSplitScreen` 产出的分屏**可触摸**

- `MulWinSwitchTransition#startIconDragSplitScreen(PendingIntent, int hotAreaType, int reason)`（已实测调用成功）：
  - 必须投递到 `Transitions.mainExecutor` 上执行，否则 `IllegalStateException: must be called on Handler`
    （`HandlerExecutor.assertCurrentThread()`）。取法：`MultiTaskingControllerImpl.getInstance()`
    → `getMulWinSwitchTransition()` → 字段 `mTransitions` → `getMainExecutor()`。
  - **从全屏调用 `hotArea=1`（`HOT_AREA_TYPE_SPLIT_LEFT_OR_TOP`）→ 得到真·原生 SoSc 双分屏**
    （左：桌面 / 右：应用），`mInSplitScreen=true`，`FocusedWindows` 命中应用，**实测点计算器 "7" 生效（显示 70）** ✔
  - 对照：同样从全屏，模块自己的 `FW:`(`openWindowFromFullscreen`) 造的 2 分屏 `SoSc=false 多分屏=false`，
    在其上再调 `startIconDragSplitScreen` 会**误触发 → SystemUI 重启**。所以基座必须是真 SoSc。
- hotArea 码表（`MultiTaskingHotAreaController`）：0=全屏 1=SPLIT_LEFT_OR_TOP 2=SPLIT_RIGHT_OR_BOTTOM
  3=FREEFORM 4=BAR_OPEN 5=FREEFORM_MINI 6=MULTIPLE_SPLIT 7=SPLIT_QUICK_VIEW_MODE
  8/9/10=TWOSPLIT_INSET_LEFT/MIDDLE/RIGHT 16=MULTIPLE_SPLIT_REPLACE 19=MULTIPLE_SPLIT_ADD。
  - 实测在**真 SoSc 2 分屏**上叠 `hot=6/8/9`：SystemUI 稳定、焦点停在应用；`hot=7 和 19` → **SystemUI 重启**（各一次）。
- 用户澄清的语义：系统是「**已开双分屏 → 拖到左上 → 三分屏**」，此前两个任务收起、出现桌面让选第 3 个。
  即入口是"拖图标到热区"，不是"点名 3 个 task 调 startMultipleSplits"。

## 新增诊断基建（已构建安装，136 个探针）

- 新文件 `app/src/main/kotlin/.../SplitTrace.kt`：按方法名把分屏入口全部打日志（方法名 + 参数值）。
  已挂：`MulWinSwitchTransition`(15) / `MultipleSplitController`(23) / `MultipleSplitRootTaskOrganizer`(3)
  / `MultiTaskingHotAreaController`(47) / `SoScStageCoordinator`(48，**窄关键词**，别全挂——它有 475 个方法会刷爆)。
  在 `Hooks.installSystemUi` 末尾 `SplitTrace.install(m, cl)` 调用。
- 踩坑：Kotlin 字符串模板里 `"$r8$lambda"` / `"-$$Nest$"` 会被当成变量引用 → 编译错 `unresolved reference: r8/Nest`，
  必须写成 `"\$r8\$lambda"` / `"-\$\$Nest\$"`。
- 抓取脚本 `/data/local/tmp/cap.sh`（源 `cap.sh`）：`logcat -b all -c` 后
  `logcat -v time -b all | grep -iE 'MulWin|Split|multiple|HotArea|SoSc|freeform|inset|MultiTasking|TRACE|OS4FreeFromX' > /data/local/tmp/cap.txt`
  （启动用 `nohup sh /data/local/tmp/cap.sh >/dev/null 2>&1 &`，设备端跑，跨轮持久）。
- 有用工具细节：
  - `Miui-WindowManager-Shell.jar` 从 `/system_ext/framework/` 拉取；里面是 `classes.dex`，
    `javap` 读不了 → 用 `dexdump -p`（签名）/ `dexdump -d`（反汇编，产物 153MB，要按行号切片提取）。
  - 截图：**别用 `adb exec-out screencap -p`**（会损坏 PNG），用 `screencap -p /sdcard/x.png` + `adb pull`。
  - 屏幕 2364×1672（ROTATION_90，物理 1672×2364）；`input tap` 用**截图同向坐标**。
  - 锁屏是 swipe-only：`KEYCODE_WAKEUP` + `input swipe 836 2000 836 700 200` 即解锁（**不要瞎猜 PIN**）。
  - 设备铁律：**绝不重启**；`am crash com.android.systemui` 重启 SystemUI 是安全的（不掉 KSU root）。
    截至本轮 uptime ≈ 96446s，root 一直完好。

---

## 【真正的入口找到了】系统「双分屏→三分屏」= Dock 模式（`DOCK_EXIT_SOSC_TO_THREE`）

### 方法：录用户真实手势，扒框架日志
- 用户演示：桌面 → 单任务 → 双任务 → 三任务 → 四任务，全程开抓取（`cap.sh`，6.2MB）。
- 关键：**探针只命中了 `SoScCoord#isMainStageRootTask` 这类无关方法**（我的 SOSC 关键字表太窄），
  但**框架自己的 verbose 日志已经把整条链路写全了**。→ 教训：先读框架 tag 日志，再决定 hook 谁。

### 完整链路（18:29:08.3 ~ 18:29:10.6，双分屏 → 三分屏）
```
MultipleSplitRootTaskOrganizer.dockMultipleSplitTasks: TaskInfo{taskId=7705 ...}
SoScStageCoordinator.onPreMultiWindowChangedForMultipleSplit  PreState:TO_DOCK
SoScStageCoordinator.onMultiWindowStateChangedForMultipleSplit state:TO_DOCK MultipleSplitTaskIds:[7705, 7703]
MultipleSplitTransitionHandler.startDockChangeTransition(transitType=3, extraTransitType=11286, index=2)   // 11286=DOCK_ENTER
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
- 三分屏列位：STAGE_A `Rect(94,66-802,1606)` / STAGE_B `Rect(826,66-1534,1606)` / STAGE_C `Rect(1558,66-2266,1606)`。
- 四分屏随后出现 stage_a/b/c/d（taskId 7692/7693/7694/7695）。
- 常量：`EXTRA_TRANSIT_TYPE_STAGE_DOCK_EXIT_SOSC_TO_THREE`（在 `MultipleSplitOrganizer`），值 **11288**；DOCK_ENTER = **11286**。

### 反编译出的确切 API（`Miui-WindowManager-Shell.jar`）
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
- **`dockMultipleSplitTasks` 只有一个入参 key：`"multiple_split_dock_task"`，值是一个 `ActivityManager$RunningTaskInfo`。**
- 输出 Bundle 里有 `"multiple_split_dock_support"`(boolean，高温/低内存时 false → 弹 toast)。
- `dockSoScTasks()` 内部做的事：`buildHomeToFront(wct)`（**这就是"两个任务收起、桌面出现"**）、
  `setSoScRootForceTranslucent(true)`、`wct.setFocusable(soscRoot,false)`、
  `mDockedState = new DockedState(...)`（**关键状态位**）、`wct.setDockedState(stageA.token, 1)`、
  把 SoSc 根按 `getOffsetWithDockedState()` 挪出屏外（-2294），最后 `mMainExecutor.execute(applyWct)`。
- `dockMultipleTasks()` 是 3+ 分屏时的对应实现。

### 结论 / 待验证的落地方案（设备恢复后立刻测）
1. 造**真原生 SoSc 双分屏**：`startIconDragSplitScreen(pi, hotArea=1, 0)`（已实测可触摸）。
2. 调 `MultipleSplitController.getInstance().dockMultipleSplitTasks(bundle{"multiple_split_dock_task"=RunningTaskInfo})`
   → SoSc 收起、桌面出现（等价于"拖到左上"）。
3. 正常 `am start` 启动第 3 个应用 → 系统自己走 `requestOpenToExitDockMode` → `DOCK_EXIT_SOSC_TO_THREE` → **原生三分屏**。
4. 第 4 个：在三分屏上重复 2（此时走 `dockMultipleTasks()`）→ 再启动第 4 个应用。
- 预期：**全程由系统建窗，输入通道完整 → 可触摸**（不再是 `startMultipleSplits` 那种"布局对、输入死"的空壳）。
- `MAX_STAGES` 的静态值 dexdump 读不到（显示 0），运行时反射读。

### 命名说明
- `MultipleSplitOrganizer` 是接口，`MultipleSplitRootTaskOrganizer` 是实现；
  `MultipleSplitController`（同包）是它的转发器（`getInstance()` 可拿，模块已用）。
  另有 `MultipleSplitUtilsImpl` / `MultipleSplitTransitionHandler` / `AbsMultipleSplitTransitionHandler` 同族转发。

## 设备状况（本轮结束时的阻塞点）
- 用户**换了一台调试机**（有 root，锁屏密码同旧机）。旧机 `02040860499C3540` 已拔。
- 新机**尚未在本机可见**：`adb devices` 为空，Windows USB 里只有笔记本自带摄像头/蓝牙 → 需要用户接上并开启 USB 调试、授权 RSA。
- 所有测试脚本都硬编码了旧序列号 `DEV="02040860499C3540"` → 恢复后要改成自动探测（`adb devices` 取第一个 device）。
