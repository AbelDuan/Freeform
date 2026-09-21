# 小窗缩放手柄 / 比例调整 · 实现与踩坑（2026-09-19）

## 当前形态

| 项 | 做法 | 状态 |
| --- | --- | --- |
| 角柄（底角胶囊） | **交回 MIUI 原生**：锁当前比例改尺寸 | ✅ 真机确认跟手、上限正常、收尾自动写记忆 |
| 三点菜单比例行 | 在 `MiuiCaptionContainerView` 里注入一行：`[18:9][16:9][4:3][1:1][手机图标=方向]` | ✅ 显示/配色/居中正常 |
| 比例的方向 | 方向是**菜单里的状态**（手机形状图标直接画目标方向：竖=窄高、横=宽扁） | ✅ |
| 比例的应用 | 写进尺寸记忆（高度不变、宽度=高×比例、左右居中、上边对齐）+ 让 MIUI 重开小窗套用 | ✅ 重开已打通（见下） |
| 尺寸记忆 | 复用既有链路（原生缩放的 `onMiuiFreeformResizeEnd`、关闭兜底） | ✅ |

## 关键结论（都是真机踩出来的）

### 1. 角柄不要自己接管
- 角柄绘制唯一出口：`MultiTaskingCornerTipAndStrokeAnimation#updateCorner` →
  `Transaction.set{Left,Right}BottomCornerTip`；
- 角柄触摸：`MiuiFreeformModeResizeHandler#handleResize(f, f2, taskInfo, downPoint, i)`，
  `i=0/2/1` = 按下/拖动/抬起，`getCtrlType()` 10=右下、9=左下；
- MIUI 原生本来就是「锁当前比例改尺寸」，收尾还会回调 `onMiuiFreeformResizeEnd`
  喂给记忆链路（真机：`立即写入 … why=resizeEndDelayed` + `记住 …`）。
  **自己接管只会跟它的 leash/scale 打架，收益为零。**

### 2. MIUI freeform 的尺寸模型（所有坑的根源）
```
显示（可视）尺寸 = 任务真实 bounds × freeformScale
```
- `MultiTaskingAnimTarget.setAnimParam(bounds, sx, sy, anchorY)`：`getWidth() = bounds.width() × sx`，**两轴独立**；
- `MultiTaskingCommonUtils.scaleBounds` 以**左上角**为锚；角柄热区/装饰都按 `getScaledBounds()` 算；
- 应用按**真实 bounds** 布局，MIUI 负责缩放显示。

由此推出：
1. **别为了「显示=应用实际尺寸」把 freeformScale 归 1** —— 那会逼应用重排，适配差的应用（真机：小红书）
   只画窗口顶部一块、剩下自己的纯色背景（截图确认）。
2. **`MiuiFreeformModeUtils#scaleDownIfNeeded` 的判据是「可视尺寸 ≤ movableBounds」**，
   一超 MIUI 就自己把 scale 调小 → 表现为「连点同一比例尺寸还在变、背景对不上」
   （真机日志抓到 scale 被它从 0.66 改成 0.37）。要夹就向它要
   `MultiTaskingDisplayInfo.getMovableBounds(...)`，别自己拿屏幕算。

### 3. 在活动窗口上直接改 bounds 是死路
试过四种，全部互相打架（每条都有真机日志）：
1. 直接发 WCT（只 `setBounds`）→ 装饰不重排，「背景维持调节前的样子」；
2. 只改 base anim target 的 current → MIUI 控制器把它拉回旧 destination，比例弹回；
3. 只走 `startGestureAnimation(3, actionMode=1)` → 先按旧比例缩放一次再被拉回，肉眼「变大又弹回来」；
4. 改 base 的 current+destination + WCT → 偶尔能成，但拖动时又弹回上一次比例。
**结论：不要再试图同步 MIUI 的两份状态。** 正确路线是「写记忆 + 让 MIUI 自己重开」。

### 4. 记忆链路的两个坑
- **写进去会被自己的记录链路盖掉**：菜单写完目标 bounds 后，随后的 `relayout/手势` 记录会用旧 bounds
  覆盖它（存储里看到的是旧值）。现在用 60s 的 `pendingTarget` 保护，套用后才恢复记录。
- **读侧有缓存**：`Bounds.get` 原本 30s TTL，system_server 在打开时会拿旧快照
  → 表现为「选横屏又被恢复成竖屏」。已降到 **1.5s**；重开前再等 1.8s。

### 5. 三点菜单注入
- 菜单是 `MiuiDecorationDot#createHandleMenu` 里 new 的 `MiuiCaptionContainerView`（纯代码 LinearLayout）；
  用 `ThreadLocal` 在 `createHandleMenu` 期间记下 decoration，在 `initButtonAndBg`（收尾）里追加一行。
- **菜单高度只按一排按钮算**，而 `MiuiCaptionContainerView#init` 会拿
  `getWCButtonHeight()` 当**按钮自己的高度** → 改它会拉大原按钮、把我们那行顶出去（踩过两次）。
  正确做法：hook `MiuiDecorationDot#addWindow`，用 `Chain.proceed(args)` 只给**菜单表面**加一行高度，
  并把位置往上挪半行；提交后把圆角改回按原高度算的值（MIUI 圆角 = 高度/2）。
- 按钮底/文字色复用 MIUI 自己的 `caption_extend_selector` / `caption_extend_text_color`
  （`getIdentifier(..., "com.android.systemui")`），深浅色自动一致。

### 6. 反射坑
- wm shell 的类只在 **SystemUI 的 classloader** 里：点击回调里 `Class.forName(name)` 会
  `ClassNotFoundException`，必须显式带上 install 时拿到的 `cl`。

## 未解决 / 待办（真机反馈，别再重复我的错）

1. **自动重开小窗**：`exitFreeformTask` 成功，但之后 `switchFullscreenToFreeform` 抛
   `IllegalStateException: must be called on Handler (android.os.Handler) {…}` ——
   MIUI 要求跑在它自己的某个 handler 上。已按候选依次试（主线程 / `getSecondaryAnimHandler` /
   `getAnimExecutor` / `getBackgroundExecutor`），仍未命中；下一步应直接**反编译
   `MulWinSwitchTransition.startToFreeform` 链路**找出断言所在，或改用系统的「启动到小窗」入口
   （参考 HyperCeiler 的桌面 hook 思路）。
2. **横屏会被系统夺回**：选横屏后仍被恢复成竖屏，疑似 MIUI 按应用方向策略二次修正
   （`adjustFreeformBoundsAndScaleIfNeed` 一类），未定位。
3. **迷你/贴边态点击恢复**：希望点回时恢复成记忆的位置与尺寸（当前用的是进入迷你时自己存的那份）。

## 上游参考（HyperFreeformX 1.3.0 APK，已反编译）

- 它的「小窗背景」是**自建一层** `MiuiFreeformBackgroundDecoration of Task=N` 的 SurfaceControl：
  `attachToDisplayArea` + `setRelativeLayer(背景, 任务leash, -1)` + `setColor/setAlpha/setBackgroundBlurRadius`。
  说明 MIUI 原生背景层不可靠（我们探针也验证：`MiuiDecorationShadow#updateViewHierarchy` /
  `updateShadowSurface` 在本机窗口上根本没被调用）。
- 它还有「关闭背景（临时）/（直至下次打开）」开关，即允许完全去掉原生背景。

## 主机侧自检

`./check.sh`：编译 `Ratio.kt` 里的纯算术（`ratioFor` / `isLandscape` / `fitRatio` / `fitInto`）到 JVM 跑断言，
不需要设备。真机行为以 `./verify.sh` 为准。

## 弃用路线（留记号）

- **路线 A**：自研手柄 View 塞进装饰 ViewHost —— `createRootView()` 在挂 hook 前就跑完，且装饰是
  `clickable=false` 自绘 View，收不到触摸；
- **路线 B**：自建 SCVH 悬浮层 —— 有原生角柄后没必要；
- **自研接管 `handleResize`**：能做出任意宽高比，但与 MIUI 的 leash/scale 反复打架，已被「原生角柄 + 菜单比例」取代。

## 自绘背景层（用户建议，参考上游 HyperFreeformX）

MIUI 那圈"背景/阴影"与窗口几何经常对不上（真机：「一边内容小于背景、一边内容超出」），
所以**背景改由我们自己画**（上游也是这么干的）：

- 任务表面 = `MiuiDecorationController.mTaskSurface`；**注意** `mMultiTaskingTaskRepository`
  在**装饰基类**上、控制器没有这个字段（踩过：取不到 → 直接 return，日志一条都没有），
  仓库统一走 `MultiTaskingControllerImpl.getMultiTaskingTaskRepository()`；
- 建层：`SurfaceControl.Builder().setContainerLayer()` + `RootTaskDisplayAreaOrganizer.attachToDisplayArea(0, builder)`
  + `setRelativeLayer(背景, 任务表面, -1)` + `setColor/setAlpha`，名字 `OS4FFX Background of Task=N`；
- 几何：`setPosition` + `setWindowCrop(可视宽+12, 可视高+12)`（外扩 6px 当阴影），
  每次 `MiuiDecorationController#relayout` 同步；**比例提交时 MIUI 不触发 relayout**，
  所以落地后额外主动同步 0/350/900ms（用我们提交的目标可视 rect，不信 MIUI 的瞬时值 ——
  它那一刻会短暂报"全屏"，真机日志：`几何=1672x2364 整屏`）；
- 关掉 MIUI 侧阴影：任务表面 + `mTopDecoration/mBottomDecoration/mShadowDecoration` 的
  `getRootSurface()` 做 `setShadowRadius(0)`，装饰表面再 `setVisibility(false)`；
- **诊断日志必须走 `Logx.e`**：`Logx.always` 有 400 行上限，安装期的 hook 日志会把它吃光，
  症状就是"新加的日志一条都不出现"（这次因此白折腾了两轮）。

> 仍未闭环：用户偶发看到一圈"固定不动"的背景，我在复现状态下多次截图 + 量像素都没能复现。
> 下次只要在复现状态下说一声：用 `dumpsys activity` 的 `mBounds` 与日志里的
> `自绘背景: task=N 几何=… 来源=…` 逐条对，就能立刻指出是哪一层、差多少像素。

## 「应用内容不铺满窗口」的最终定位（2026-09-19，给下一次会话）

现象（用户描述）：`小窗的大小和里面图片边缘的大小差异很大`、`一边内容小于背景、一边内容超出背景`、
`1:1 → 16:9 后多出一片白色`。我按 adb 自己复现并抓到：

- 任务 bounds 正确落地（日志 `落地 bounds=Rect(537,420-1443,2032) scale=0.66`）；
- 我们的自绘背景几何也正确（`几何=598x1064 @537,420`）；
- 但**应用内容被显示成"真实尺寸"（未缩放）** → 一边比窗口小、一边超出窗口。

根因链（逐层验证）：
1. 只发 WCT 改 bounds，**leash 的变换不会跟着变**；`setScale(leash, …)` 也无用
   —— leash 只由 MIUI 的 folme 引擎写；
2. 直接改 folme 字段（`setFolmeWidth/Height/CenterX/ElegantY`，日志显示值都对）**同样不生效**
   —— 字段不是引擎，没有动画就不会重写到 leash；
3. 再触发一次 MIUI 的缩放收尾动画（`startGestureAnimation(3,…)`）确实能让引擎写 leash，
   但 MIUI 控制器随后会把 bounds/scale 拉回 → 真机表现**闪烁 + 尺寸对不上**，已撤销。

结论：leash 变换的所有权在 MIUI 的 folme 引擎 + 控制器手里。要既改几何又让 leash 一致，
就必须走 MIUI 自己的「创建/打开」流程（`ActivityOptions.setLaunchWindowingMode(5)` /
`switchFullscreenToFreeform`），而那两条都会重排前台（露桌面）。**这是当前未解的矛盾**，
下一次要么找到 MIUI 内部"只重排 leash 不碰堆栈"的入口（例如从
`MiuiFreeformModeAnimation.applyResizeAnimation` 的入参入手，看它凭什么会被控制器覆盖），
要么接受其中一边的代价。

## 分屏（HyperOS 4）—— 已实现与未解，2026-09-20

### 比例自定义（已实现，真机可用）
- HyperOS 自带开关：`persist.sys.split.ratio.customize.Q18=true`（`SoScUtilsImpl.IS_Q18_CUSTOMIZE`
  静态读取，改后只需重启 SystemUI）。另有代码钩子强制 `supportedRatioCustomization()` 返回 true 兜底。
- 两套吸附实现都要挂：`com.android.wm.shell.common.split.DividerSnapAlgorithm#snap` 与
  折叠屏专用的 `com.android.wm.shell.sosc.common.split.DividerSnapAlgorithm#snap`（本机走后者）。
- 落位规则：1%~15% → 官方 10% 档；85%~99% → 官方 90% 档；中线 ±5% → 官方 5:5；
  35%/65% ±2% → 精确 3.5:6.5；其余 → **松手即落点（任意比例）**；≤1% / ≥99% → `proceed()` 交还官方全屏手势。
- **必须返回官方 SnapTarget 对象**（不能自造 id）：自造 id 会绕过 `SoScUtilsImpl.findSnapTarget`
  的状态机，导致"点击边缘切换比例"失效。我们的 id 只在 35%/65%/任意比例时使用，并在
  `SoScUtilsImpl.findSnapTarget` 里放行（id 101..106）。
- 每组应用的比例记忆：key = `split:<pkgA>+<pkgB>|<screen>`，值用 1×1 Rect 的 left 存千分比；
  记录在 `setDividerPosition(pos, true)`（已定档）；恢复在 `SoScStageCoordinator#onLayoutSizeChanged`
  里**异步**投递一次。

### 栏的隐藏（已实现，2~6 分屏）
分屏每个应用的栏有两类，都要处理：
1. `Miui Caption of Task=N` / `Miui Bottom Caption`（与自由小窗同一个 `MiuiDecorationDot` 装饰类）
   → `markBar` 里**不能再限定 `MODE_FREEFORM`**，否则分屏被排除（原注释写"分屏/桌面不受影响"）；
   同时 `updateInsets` 两处也要去掉同样的限制，否则栏不画了但高度仍占位。
2. `SoScSplitDecorManager`（镜像栏：复制应用标题栏 + 两点）与 `MultipleSplitUIController`（多分屏 UI）
   → 视图置 INVISIBLE/GONE（`inflate`/`onResizing`/`onResized`/`drawNextVeilFrameForSwapAnimation`），
   并在 `SurfaceControl$Transaction.show` 处按表面名拦截（**绝不能拦 `SplitWindowManager`**——
   那是分隔条宿主，拦了就没法拖动；闪退过一次是因为 `show` 返回值必须原样返回 Transaction）。

### 未解：分屏里的预留空白（状态栏/导航栏高度）
真机量测：右列 y 0..142 空白，等于 `statusBarHeight=140`；底部同类。
- 已尝试且**无效**（均有日志/量测证据）：`MultipleSplitLayout.mDotInsetsHeight/mBottomInsetsHeight`（实际 77/44）、
  `getDisplayStableInsets`（multiple 与 sosc 两个版本）、`mRootBounds`（本来就是整屏）、
  `mDividerInsets`、`DisplayLayout.stableInsets()`、sosc `getTopLeftContentBounds` 扩展。
- 说明这些 inset 在别处（可能由 `MultipleSplitRootTaskOrganizer`/`SoScStageCoordinator` 每帧重算，
  或来自 stage 自己的 task config）。
- 下一次建议：**用 `dumpsys window` 看应用窗口的 `Frames` 与 stage bounds 的差**（本次量到
  WeChat `frame=[1457,0][2364,1672]` 已是满高，说明空白在应用内部而非窗口级），
  再从 `SoScStageCoordinator.updateWindowBounds/updateSurfaceBounds` 反向定位。

## 待验证：软重启后的 insets 钩子（2026-09-20）

用户授权执行软重启（`setprop ctl.restart zygote`）以激活 `installSystemServer` 里新增的
`InsetsState#calculateInsets` 钩子（system_server 启动时才注入）。

**重启后要查（模块日志 `/data/adb/lspd/log/modules_*.log`）：**
1. 是否出现**新的** `installSystemServer: remember=…` 行（时间晚于 2026-09-20 01:12:55）——
   出现=新代码已加载；
2. `insets 样本#n: types=… win=…x… disp=…x… insets=…`（前 8 次涉及系统栏的调用）——
   出现=钩子活着且在采样；
3. `分屏/小窗 insets 清零: … -> top=0 bottom=0 …` —— 出现=命中，分屏上下留白应消失。

**若 0 条样本**：说明应用 insets 不走 `InsetsState.calculateInsets`，改挂
`InsetsStateController` / `WindowState#getInsets` 一类的分支。
**若有样本但未命中**：按样本里的 `types/窗口尺寸/display 尺寸` 修正判据即可（一次可定）。

## 用户偏好（2026-09-20）：少重启
- 曾明确授权我执行软重启；随后要求「**能不重启就别重启，浪费时间**」→ 优先 SystemUI 重启即可生效的路径
  （SystemUI 进程内的钩子：shell/装饰类），system_server 钩子只在确有必要时才请求软重启。

## 分屏留白：当前结论（2026-09-20 01:31 软重启后取证）
- `InsetsState.calculateInsets`（system_server）**只被整屏调用**：样本 8 条全是
  `win=2364x1672 disp=2364x1672 types=263 insets={top=140,bottom=55}` → **应用级 insets 不走这条**。
- `InsetsStateController` 命中方法：getRawInsetsState / notifyInsetsChanged / onPreLayout / onPostLayout /
  updateAboveInsets / getControlsForDispatch / setForcedConsuming / onBarControlTargetChanged / notifyPendingInsets…
- 已确认生效的（SystemUI 侧，重启即生效）：
  - `WindowContainerTransaction#setAppBounds` 就地改整屏 → side stage `mAppBounds` 实测
    从 `(1194,0-2224,1617)` 变 `(1194,0-2364,1672)` ✓
  - `markBar` 去 MODE_FREEFORM 限制 → 日志 `沉浸: task=4450/4451 栏隐藏=true` ✓
  - `updateInsets` 去同限制 → `不再为顶部三点栏/底部手势条预留 insets(task=4450)` ✓
  - SoSc 装饰隐藏 + `show` 拦截 + `SoScShapeView.onDraw` 跳过 ✓（但用户仍看到"•• 微信(15) 🔍➕"镜像栏）
- **剩余两条**（均未解决）：
  1. 应用上下仍按**可见系统栏**让位（top=140/bottom=55）→ 要免重启只能在 SystemUI 侧找下发点，
     否则需 system_server 改 insets（要软重启）。
  2. 分屏那条**镜像栏**（把应用标题栏做位图）仍会画出来 → 下一步：钩
     `SoScSplitDecorManager#attachToParentSurface`（它自己 `setHidden(true)` 建 leash）
     或 `SurfaceControlViewHost` 构造（title 含 "SoScSplitDecor"）直接不让它可见。

## 分屏系统栏留白：终结性结论（2026-09-20 01:47 软重启后）
- 软重启已激活新钩子（`installSystemServer` @01:47:42 ✓）；
- **`WindowState` 上没有任何返回 `android.graphics.Insets` 的方法**（模块自己 dump 出来的清单为空）
  → 服务端不存在"某个窗口该收到多少 insets"的单一返回值可以改写；
- 结合之前的样本（system_server 里 `InsetsState.calculateInsets` 只有**整屏**调用、
  无应用级调用）→ **应用的 insets 是在「应用自己的进程」里由 `InsetsState.calculateInsets`
  算出来的**（客户端持有 WM 下发的 `InsetsState` + controls 自行计算），
  而本模块的 scope 只有 `system`(system_server) 与 `com.android.systemui`，**够不到应用进程**。
- 因此：只靠这两个进程，无法在不改客户端的情况下清零指定窗口的系统栏 insets。
  可选路径（都需要额外决定）：
  1. 把模块 scope 扩到目标应用（LSPosed 里勾选 app），在应用进程钩 `InsetsState.calculateInsets`
     或 `ViewRootImpl` 的 insets 消费处 —— 但这是"每个 app 都要勾"的运维成本；
  2. 服务端改窗口的 `WindowManager.LayoutParams.fitInsetsTypes = 0`（反射）后再触发一次布局 ——
     属于深入 WM 内部、风险高、且要多次软重启试；
  3. 接受现状：分屏时上下各留状态栏/导航栏高度（系统栏正常显示）。
- 已确认无关的（避免重复尝试）：`InsetsStateController` 各方法、`DisplayLayout.stableInsets`、
  `SplitLayout.getDisplayStableInsets`(multiple/sosc)、`mRootBounds`、`mDividerInsets`、
  `mDotInsetsHeight/mBottomInsetsHeight`、`MultipleSplitLayout`、`SoScShapeView`、`mHostLeash` 等。

---

## 内外屏切换 / 旋转变形 + 外屏菜单溢出：三处修复（2026-09-20）

反馈的三个 bug：

1. 内外屏切换时，小窗变形状、变比例尺寸；
2. 屏幕旋转之后，小窗变更比例和尺寸；
3. 在外屏点三点菜单，弹出的按钮超出了背景。

### 根因

**#1 / #2 是同一个根因：`Bounds.clamp()` 宽、高各自独立裁切**

- 记忆 key `pkg|短边x长边`（`Bounds.screenKey`）本身与旋转、内外屏无关，是**正确**的，
  所以「查记忆」这一步没问题；
- 问题在恢复时调用的 `clamp()`：记忆 bounds 一旦超出目标可视区，它把**宽、高两条边各自独立裁一条**——
  只要某条边放不下就直接砍短，长宽比随之被破坏。旋转（可视区宽高对调）或切到更小的外屏时必然触发，
  表现就是「比例和尺寸一起变」；
- 另外**当前屏还没有记忆时没有任何兜底**，直接落回系统默认矩形（默认形状同样是变形的）。

**#3 是比例按钮写死了固定宽度**

- `ratioButton` 用 `LinearLayout.LayoutParams((42 * density).toInt(), h)`，固定 42dp 宽，
  而注入的那一行是 `MATCH_PARENT`；
- 外屏菜单背景比内屏窄，4 个固定宽按钮加上左右间距放不下，就溢出到背景之外。

### 修复

`Bounds.kt`

- 新增 `clampKeepRatio(r, area)`：按 `scale = min(1, min(areaW/w, areaH/h))` **整体等比缩放**后再夹位置；
  放得下时 `scale = 1`、尺寸原样，**任何情况都不单独裁边** → 形状始终不变，只在越界时整体缩小。
- 新增 `getAny(ctx, pkg)`：遍历 `pkg|` 前缀，取该应用**任一屏幕**下的记忆，供当前屏无记忆时兜底。

`Hooks.kt`

- `installLaunchBounds`（小窗打开恢复，挂在 `getFreeformRect` 家族上）：
  当前屏有记忆 → `clampKeepRatio` 等比夹；当前屏无记忆 → `getAny` 取其它屏记忆再 `clampKeepRatio`
  缩到当前屏（日志前缀 `ffr-fallback-<pkg>`）。
- `getRestoredBounds`（迷你/贴边态点回来）：同样改用 `clampKeepRatio`，换屏/旋转后保形状、不越界。
- `persist` 的存储 / heal 路径**仍用原 `clamp`**：那里是「位置越界」的修正与比较，
  改成等比夹会改变「是否需要 heal」的判定（属于记录逻辑），保持原样更稳。
- `ratioButton`：`LayoutParams((42 * density), h)` → `LayoutParams(0, h, 1f)`（`weight = 1` 弹性宽度），
  四个按钮平分菜单宽度，内屏/外屏都不会再溢出。

### 思考与调整

- **最初判断被修正**：一开始怀疑是「记忆 key 没带屏幕/方向维度」，核对 `screenKey`
  后确认 key（`短边x长边`）本就与旋转、内外屏无关，真正破坏形状的是恢复端的 `clamp`，归因随之修正。
- **`getAny` 是顺带补的兜底**：原逻辑在首屏没有记忆时直接用系统默认，同样会变形；
  补上「借别的屏记忆等比缩过来」后，第一次遇到新几何也能保住形状。
- **没有在 `persist` 里也换成 `clampKeepRatio`**：有意为之，理由见上（会动 record/heal 的判定）。
- **顺带修了两处 Kotlin 编译错误**：在 Windows + Android SDK 35 `build-tools`（`d8` + `apksigner`）
  本地构建时暴露；容器 arm64 工具链此前未暴露，属工具链/版本差异，**行为不变**：
  - `AppCtx.fixPackage`：`fixed` 经 `getOrDefault(ctx)` 后按平台可空处理，
    `fixed.opPackageName` 报「可空接收者」→ 改 `fixed?.opPackageName`；
  - `Hooks.call`：参数声明为非空 `Any`，但调用点 `call(ti, …)` 的 `ti` 是 `Any?` →
    参数改 `Any?`，函数体一并 `o?.javaClass?.getMethod(name)?.invoke(o)`。

### 待真机验证（未完成）

- **旋转修复的前提**：旋转时小窗要**走 `getFreeformRect` 重开**，恢复路径才会被调到。
  若 HyperOS 在旋转时只对现存窗口做 relayout、不重开，模块够不到，需要另挂「显示 / 配置变更」钩子。
  真机用 `./verify.sh` 看是否出现「恢复 …」/「ffr-fallback-…」日志来确认命中的是哪条路径。
- #3 的 `weight = 1` 需在外屏实际点开三点菜单，确认按钮已收回背景内。

---

## 旋转 / 内外屏切换保比例：relayout 配置变更套用（2026-09-20 续）

### 背景与确认

上一个小节的三处修复上线后，真机反馈：**窗口不再乱变形，但旋转屏幕 / 切换内外屏，小窗仍会恢复为系统默认比例。**

这印证了上一节「待真机验证」里担心的那条路径：HyperOS 在旋转 / 内外屏切换时，
对**现存**小窗做的是 **relayout（重新布局）而非重开**，`getFreeformRect`（`installLaunchBounds`）
这条恢复钩子根本不会被调用 —— 记忆恢复没机会介入，小窗就被 MIUI 塞回默认比例。

### 修复思路

挂点选在已经 hook 的 `MiuiDecorationController.relayout`：它每次装饰重排都会触发，
旋转 / 换屏必过。在它的 after-hook 里做两件事：

1. **检测配置变更**：用 `displayId:orientation` 作为配置键（按 `taskId` 索引，避免换屏时
   装饰实例被重建导致漏触发），与上次见到的比较。只有 orientation 或 displayId 真正变了才继续。
2. **套回记忆**：命中后延迟 350ms（等 MIUI 把旋转/换屏后的布局安定下来，否则会被它的最终布局覆盖），
   把记忆里的尺寸经 `clampKeepRatio` 等比缩到当前可视区、连同 `freeformScale`，
   通过 **`WindowContainerTransaction`**（`setBounds` + `setMiuiFreeformInfoChange`）直接套回窗口。
   这条 WCT 通道与 `restoreScaleIfNeeded` 已验证可用，是 SystemUI 进程内对运行中小窗改尺寸/位置的标准做法，
   **不重开应用、无闪烁**。

兜底与防护：

- 当前屏有记忆用当前屏（key = `短边x长边`）；当前屏无记忆（首次换屏）则用 `getAny` 借其它屏记忆等比缩过来，形状照样保住。
- 同一配置键只排程一次（`appliedForConfig`），且只在「配置键首次出现之后」才套用 ——
  开窗那一刻 `lastConfigKey` 为空，直接记下、不打扰 `installLaunchBounds` 自己的恢复。
- 若用户刚在拖动/缩放（`gestureEndedAt` 2.5s 内）则跳过，让位给手势。
- 窗口不可见 / 非 freeform / 取不到包名 / 没有任何记忆，一律不套用（保持系统行为，不会更差）。

### 新增代码

`Hooks.kt`

- `maybeReapplyOnConfigChange(ctrl, info)`：配置变更检测 + 排程。
- `reapplyBoundsFromMemory(ctrl, dispId)`：取记忆 → `clampKeepRatio` → 调套用。
- `applyBoundsAndScale(decoration, id, target, scale)`：WCT `setBounds` + `setMiuiFreeformInfoChange` → `applyTransaction`。
- 状态表 `lastConfigKey` / `appliedForConfig`（`ConcurrentHashMap<Int,String>`，按 taskId）。
- `relayout` after-hook 顶部插入 `runCatching { maybeReapplyOnConfigChange(ctrl, info) }`。

### 待真机验证（未完成）

- `relayout` 是否在旋转 / 内外屏切换时确实触发，且 `displayId` / `orientation` 能正确反映变化
  （尤其内屏↔外屏：两个 display 的 `displayId` 是否真不同）。看日志 `配置变更: … -> …`。
- 套用是否真正生效、且不被 MIUI 后续布局覆盖（看 `配置套用: … -> target=…` 与 `配置套用提交`）。
- 若 relayout 在换屏时**不触发**（装饰被整段销毁重建、且新实例拿不到旧 `lastConfigKey` 之外还读不到新 displayId），
  需改挂 `DisplayManager` / `onConfigurationChanged` 广播或 `ShellTaskOrganizer` 的 task 变更回调。
- 套用延迟 350ms 是否合适：若真机看到「先弹默认比例、再跳回记忆比例」的闪一下，可上调到 500~600ms。

---

## 新手势：全局输入挂钩的两个坑（2026-09-21，真机取证）

### 0. LSPosed 重装后不注入模块（卡了整整一轮，务必先看这条）
`adb install -r` 每次都会给应用**新的 codePath**，而 LSPosed v2.2.0 的
`/data/adb/lspd/config/modules_config.db` **不会**跟着更新这三张表：

| 表 | 症状 | 修法 |
| --- | --- | --- |
| `modules.apk_path` | 指向已删除的旧目录 | 改成 `pm path <pkg>` 的真实路径 |
| `modules_state`（enabled） | 整行**消失** → 模块被当成"未启用" | 插回 `(pkg,0,1,0)` |
| `scope` | 整行**消失** → 没有注入目标进程 | 插回 `system` + `com.android.systemui` |

修完必须 **重启 lspd**（它把表读进内存）——`kill <lspd pid>` 之后 init 不会自动拉起，
要手动 `cd /data/adb/modules/zygisk_lsposed && setsid ./daemon --force &`，再 `killall com.android.systemui`。
**一键脚本：`tools/fix-lsposed-module.sh`**（每次 `adb install` 之后跑一次）。

> 排查过程中的弯路（别再走）：`modules.apk_path` 是**旧值**时把新 APK 复制回旧路径**没用**；
> 守护进程内存里的表才是权威。另外**不要**把 `modules_config.db` 的 WAL 删掉——
> 新写入都在 WAL 里，删掉等于回滚到旧快照（真机踩过：`scope` 行凭空消失）。

### 1. 全局输入源：`MulWinSwitchEventController$EventReceiver#onInputEvent`
- MIUI 自己用 `InputManager.monitorGestureInput` 拿了一条 InputMonitor，全屏触摸都从这里过；
  挂它的 `onInputEvent(MotionEvent)` 就等于拿到**全局触摸流**（全屏/桌面/小窗/分屏都能看到），
  不需要任何额外权限。`Logx.v` 级别够用，命中才 `Logx.always`。
- receiver 平时由 MIUI 在**第一个小窗/分屏装饰创建时**才建（`MulWinSwitchDecorViewModel` 里
  `mWindowDecorByTaskId.isEmpty()` 分支）→ 全屏场景下可能还不存在。模块安装时主动
  `createEventReceiver(ctx)`（方法内部幂等）+ `registerEventHandler(proxy)` 补一手，才稳定。
  ⚠️ 必须在**主线程**调用（它用 `Looper.myLooper()`），安装期 `main.post {}` 里做。
- 真机确认：`MulWinSwitchEventController: start handle ACTION_DOWN ...` 正常刷，
  说明 receiver 活着、事件在流。

### 2. 任务对象是**包装类**，不是 RunningTaskInfo
`MultiTaskingTaskRepository.getVisibleFullTaskInfo()` 返回 **`MultiTaskingTaskInfo`**
（继承 `MultiTaskingBaseTaskInfo`），`topActivity` / `baseIntent` 都在它内层的 `mTaskInfo` 上。
只在这个包装上找 `topActivity` 永远是 null，日志表现是
`class=...MultiTaskingTaskInfo fields[topActivity=null ...] methods[getTaskId=Integer, getTopActivity=null]`。
**解包入口：`getTaskInfo()`**（→ 字段 `mTaskInfo`）。真机取证：解包后
`角滑: 前台 pkg=com.tencent.mm task=5702 mode=1` 一次就对。

### 3. 角滑判定区必须按**屏幕比例**算
一开始用固定 dp（左右 150dp / 底下 110dp）标定角区，`input swipe` 打进 (1324,1709) 时
起手就被否掉——折叠屏内外屏、横竖屏切换时屏幕尺寸会变，固定 dp 必然失真。
改成 `y ≥ h*0.55 且 (x ≤ w*0.40 或 x ≥ w*0.60)` 后稳定命中。

### 4. 功能①「角落斜滑 → 前台应用转小窗」已真机验证 ✅
`input swipe 200 1600 → 900 900 300`（内屏 1168x1712）：
```
手势: 角滑命中 侧=左 行程=989px dx=700 dy=-700 用时=302ms
角滑: 前台 pkg=com.tencent.mm task=5702 mode=1
角滑: 已请求以小窗启动 com.tencent.mm（x=200 y=1600）
```
`dumpsys activity activities` 取证：该任务 `mode=freeform`、`mBounds=Rect(273, 426 - 1441, 2292)`
—— 小窗就落在起手点附近。走的是官方 `MiuiMultiWindowUtils.getActivityOptions(ctx,pkg,true,x,y)`。
分屏内的应用另有专门入口 `MulWinSwitchAnimStarter.switchSplitToFreeform(taskId)`（已接好，待测）。

### 5. 功能②「四指上滑 → 加分屏」：识别已完成，动作待接线
四指识别已实现并编译进包（≥4 指、上滑 ≥70dp、≤1.2s、水平漂移 <200dp）。动作侧已定位到的官方入口：
- `MultipleSplitController#insertMultipleSplitByTask(WindowContainerTransaction, taskId, index)`
  / `#insertMultipleSplitByIntent(wct, PendingIntent, index)`
  / `#startMultipleSplits(Bundle)`（shell 命令 `startMultipleSplits recent <taskId>...` 走的也是它）；
- `MultiTaskingStateManager#dockSplitFromRecent(Bundle)`（launcher 的"加进分屏"走这条，
  `commands` 里对应 `dockSplitFromRecent options:` 日志）；
- 查询侧：`MultipleSplitUtilsImpl#getAllStageTaskInfo / getMultipleSplitTaskIds / getActiveStageTaskIdByIndex`。

用户选择的是**弹出应用列表让他点选**，所以下一步是：模块 App 侧加一个选择器 Activity
（`showDialog` 认证或自绘悬浮列表）列出最近任务 → 选中后把 taskId 交给上面的入口。

### 6. 功能② 实现进展（2026-09-21 续）
四指识别 + 动作骨架已进包，三条链路的真机结论：

1. **识别**：`四指上滑命中 行程=… 手指数=…` 正常（真机用 1 指临时阈值验过识别与后续链路；
   正式阈值已改回 4）。
2. **候选来源**：`MultiTaskingTaskRepository#getMultiTaskingTaskInfoList()` 能列出 shell 已知任务
   （真机：16 个），配合 `taskIdOf`/`pkgOf` 组装成 `pkg|taskId` 候选。
   ⚠️ `MultipleSplitController#getAllStageTaskInfo()` 在**没有多分屏时返回全部 stage**（真机拿到 6 个 id），
   不能直接当"当前分屏组"，需要再按 `isMultipleSplitActive()` 分支。
3. **选择器窗口**：**SystemUI 进程里开不出窗口** —— 两条路都失败过：
   - `PopupWindow` + 未附着窗口的 View 当锚点 → `BadTokenException: token null is not valid`；
   - `createWindowContext(TYPE_APPLICATION_OVERLAY)` + `WindowManager.addView` → 同样 BadToken
     （SystemUI 进程没有 overlay 授权）。
   **正解：交给模块 App 的 Activity**（`PickActivity`，`Theme.DeviceDefault.Dialog`），
   它有正常 window token。回传走 `AppPrefs` 写 `PREFS_CFG.pending_pick = "<一次性token>|pkg|taskId"`，
   SystemUI 侧用带 token 的轮询经 `StoreProvider` 取（20s 超时，token 不匹配不认）。
   ⚠️ SystemUI 里 `ctx.packageName` 是 `com.android.systemui`，拉起模块 Activity 必须显式写
   `setClassName("com.abel.os4freeformx", "com.abel.os4freeformx.PickActivity")`，
   否则 `ActivityNotFoundException`（真机踩过）。
4. **插入**：`MultipleSplitController#insertMultipleSplitByTask(wct, taskId, index)` +
   `ShellTaskOrganizer#applyTransaction`（帮助函数 `orgOf()` 按类型扫字段，名字随版本变）。
   双分屏（SoSc）先 `transferSoScToMultipleSplit(ids, types)` 转多分屏，再插第三个。
   **这一段还没在真机跑到**（等用户在真实分屏场景下用四指上滑触发）。

### 7. 功能② 改走「系统自己的分屏吸附」（2026-09-21，用户指定方案）
用户明确：**不要自建窗口让用户点选**，要调用系统自己的分屏吸附（等价于把应用上滑甩到角落、系统自动吸进分屏）。
已按此改实现 `Gestures.fourFingerAddSplit()`，入口是 `com.android.wm.shell.sosc.SoScUtils`：

| 方法 | 用途 |
| --- | --- |
| `enterSplitScreen(RunningTaskInfo, WindowContainerTransaction, boolean)` | 把任务送进分屏（**boolean 是"是否为拖拽进入"**，看它内部把 `wct` 当拖拽事务用） |
| `finishEnterSplitScreen(SurfaceControl$Transaction)` | 收尾/落地（**参数是 SurfaceControl 的 Transaction，不是 WCT** —— 真机第一次按 WCT 猜，报 `NoSuchMethodException` 才纠正） |
| `addSplitPair(int, int)` | 把两个任务配成一组 SoSc 分屏 |

实现里：候选 = shell 已知任务里第一个不在当前分屏组、且不是自由小窗的任务；拿到候选后
`enterSplitScreen(候选, wct, true)` → 自己 new 一个 `SurfaceControl$Transaction` 交给 `finishEnterSplitScreen`；
任一环节失败都退回 `ShellTaskOrganizer#applyTransaction(wct)`。全程 `Logx.always` 打点。
自建选择器（`PickActivity` + `pending_pick` 回传）已从动作路径移除，类保留但不再被调用。

**待真机验证**：需要**真实分屏进行中**触发（四指上滑）。adb 造不出四指多点触控，也造不出分屏 UI 操作，
只能由用户手动触发；日志关键字：`四指上滑: 走系统分屏吸附 pkg=… task=…` / `enterSplitScreen -> …` /
`finishEnterSplitScreen 已调用`。
