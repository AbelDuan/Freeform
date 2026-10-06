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

### 8. 四指手势真机取证与两个新发现（2026-09-21）

**（a）合成四指成功了，但方向极性要注意**：内屏触摸设备是 `/dev/input/event7`
（`Xiaomi_Touch_Input_0`，`ABS_MT_SLOT` max 9 → 最多 10 指），用 MT 协议低层 `sendevent` 可以合成多指；
脚本 `/data/local/tmp/mt4.sh <dev> <sx1> <sy1> <sx2> <sy2> [n]`。
- 用 `300 1500 → 300 900` 时，hook 收到 `peak=4` —— **证明我们的输入源能看到 4 个同时按下的指针**，
  四指识别这条链是通的；
- 反过来 `300 900 → 300 1500` 时报告 `peak=1`、`dy=0`：**外屏那块的 Y 极性与直觉相反**，
  且反向扫时驱动会把事件拆成多次单指 DOWN。以后合成手势先小步试方向。

**（b）`splitTaskIds()` 曾把"历史 stage"当当前分屏组**：真机日志
`四指上滑: SoSc=false 多分屏=false 组内=[6110,6111,6112,6113,6114,6115] shell已知=17`
—— 没有任何分屏在跑，`getAllStageTaskInfo()` 却回了 6 个上次多分屏留下的 stage，
导致候选被全部误排除、`enterSplitScreen` 拿到错参数返回 false。
已加 `if (!splitActive() && !soScActive()) return emptyList()` 守卫。

**（c）按用户提示加了"支持分屏"过滤**：候选现在过一遍
`MultiTaskingCommonUtils.supportSplit(RunningTaskInfo)`（系统自己的判断），
日志会打 `候选池=N 其中支持分屏=M → 选中 <pkg>`。用户实测：**设置不支持分屏**，
要用小红书/酷安这类三方应用测。

**（d）动作路径已跑通（无分屏状态下）**：
```
四指上滑: 走系统分屏吸附 pkg=top.funcun.dshfolk task=5653（组内 6 个）
四指上滑: enterSplitScreen -> false
四指上滑: finishEnterSplitScreen 已调用
```
三步都执行到位、没有异常/闪退；因为当时**没有分屏在进行中**，`enterSplitScreen` 返回 false 属预期。
真正得分屏场景下的效果仍需用户在双分屏里用四指上滑确认。

**（e）本次新问题（留给下一轮）**：第 5 轮重启 SystemUI 后，`MulWinSwitchEventController` 的
`start handle ACTION_DOWN` 在 logcat 里正常刷（Pid 与当前 SystemUI 一致），
但我们的 `installGestures` 只在**主线程**那条日志里出现过，`onInputEvent` 钩子没有产生任何手势日志
—— 即"事件源活着、钩子没被调到"。前几轮同一份代码是能命中的（有 `手势: 角滑命中` 取证），
怀疑与 `createEventReceiver` 的调用时机（主线程 post 与 receiver 创建竞争）有关，下一轮先加
`hook 成功` 级日志确认钩子是否真的挂上，再决定是否改成"钩 DecorViewModel 的 createWindowDecoration"。

### 9. 第 5-6 轮：钩子掉线的原因与最终状态（2026-09-21）

**钩子掉线是假警报**：第 5 轮怀疑"事件源活着但 `onInputEvent` 钩子没被调到"，
第 6 轮加了 `installGestures: onInputEvent 挂载=<bool>` + 钩子内 `Logx.once("ev-first", "钩子已收到事件")`
两处取证后，一次就正常了：
```
installGestures: onInputEvent 挂载=true（gestures=true 屏=1672x2364 密度=2.75）
钩子已收到事件（onInputEvent 生效）
手势: 角滑命中 侧=左 行程=848px dx=600 dy=-600 用时=311ms
角滑: 前台 pkg=top.funcun.dshfolk task=5653 mode=1
角滑: 已请求以小窗启动 top.funcun.dshfolk（x=300 y=1500）
```
**功能① 再次真机确认**：`dumpsys` 里该任务 `mode=freeform`，SystemUI 无 FATAL、`pidof` 稳定。
上一轮的"没反应"更可能是当时 SystemUI 刚被 `fix-lsposed-module.sh` 重启、我读的还是旧进程日志。

**又修掉一个真 bug**：`unwrap()` 曾把 `getTaskInfo()` 返回的 **Integer** 当成任务对象返回，
日志表现 `角滑: 前台任务没有包名 … class=java.lang.Integer`。
现在只在 `o.javaClass.name == "android.app.ActivityManager$RunningTaskInfo"` 时才认。

**四指识别在真实分屏下确认可达**：用户开好双分屏（小红书 `mode=multi-window`）后，
用 MT 协议合成四指，hook 稳定报 `手势: 四指起手 pointers=4 @1374,953` ——
**4 指同时按下确实能进到我们的钩子**，这条链不需要改架构。
但合成手势的 MOVE/UP 传不完整（驱动把后续 SYN 拆成了多次 DOWN，`peak` 停在 4 就没了），
所以"上滑完成 → 触发加分屏"这一段**只能由真手指触发**来验。

**给下一次的抓手**：`/data/local/tmp/mt4c.sh`（慢速 5 步版合成四指上滑）保留在设备上；
日志关键字按顺序应为
`四指起手 pointers=4` → `手势: 四指上滑命中` → `四指上滑: SoSc=… 多分屏=… 组内=[…]` →
`四指上滑: 候选池=N 其中支持分屏=M → 选中 <pkg>` → `四指上滑: 走系统分屏吸附 pkg=… task=…` →
`enterSplitScreen -> …` → `finishEnterSplitScreen 已调用`。

### 10. 四指加分屏接上官方分发点 + 线程断言修法（2026-09-21）

用户澄清了系统原生的进分屏路径：**上滑当前任务到左上角 → 双分屏；双分屏里从底部中间上滑 → 多分屏；
多分屏再上滑 → 继续加**。据此找到官方分发点（`com.android.wm.shell.multitasking.miuimultiwinswitch.miuidraganddrop.MiuiDragAndDropPolicy`）：

| 场景 | 系统方法 | 备注 |
| --- | --- | --- |
| 全屏 → 双分屏 | `MulWinSwitchTransition#startIconDragSplitScreen(PendingIntent, hotAreaType, reason)` | hotAreaType 用 `HOT_AREA_TYPE_SPLIT_LEFT_OR_TOP=1` |
| 多分屏加一个 | `MultipleSplitController#insertMultipleSplitByTask(wct, taskId, index)` | 已跑通到 `applyTransaction` |
| 多分屏再加（inset 版） | `MultipleSplitTransitionHandler#startIconInsetMultipleSplit(wct)` | 备选 |
| 角落热区判定 | `MultiTaskingHotAreaController#getHotAreaTypeForDrag` / `getHotAreaAtPosition` | 常量表见该类字段（SPLIT_LEFT_OR_TOP=1 / MULTIPLE_SPLIT_ADD=0x13 等） |

**关键修法（可复用）**：`startIconDragSplitScreen` 内部会调 `startTransition`，而它对线程有断言 ——
真机报 `java.lang.IllegalStateException: must be called on Handler (android.os.Handler) {7a5cc4f}`。
这正是 NOTES 开头「未解决 / 待办」里那条老问题的同一个断言。**解法：把整段调用投到
`Transitions#getMainExecutor()` 上执行**（`MulWinSwitchTransition.mTransitions` 字段拿 Transitions）。
投递后调用成功、不再抛异常：
```
加分屏: 当前无分屏 → 走 startIconDragSplitScreen 起双分屏
进分屏: 已投递到 Transitions.mainExecutor
四指上滑: 已请求系统分屏吸附（startIconDragSplitScreen pkg=org.lsposed.manager hotArea=1）
```
**尚未成功落地分屏**：这次调用后没有出现分屏（回到了桌面）。原因分析：`startIconDragSplitScreen`
的 `PendingIntent` 在 MIUI 那边来自**真实拖拽会话**（`MiuiDragAndDropPolicy.mLaunchIntent`），
我们用 `getLaunchIntentForPackage` 现造的 PendingIntent 缺会话上下文，转场被消费后只把桌面翻上来。
下一步应当：① 抓一次真实"上滑到左上角"的日志，看 `MiuiDragAndDropPolicy` 在 drop 时
`mLaunchIntent` / `mCaller` / hotArea 各自是什么，按真实参数复刻；或
② 改挂 `MiuiDragAndDropPolicy` 的 drop 处理，在它拿到真实会话参数后由我们重用（而不是自己起会话）。

**给下一次的调试入口（已进包）**：`watchTestHook()` 每 1.5s 轮询 `pending_test_addsplit`，配合
`am start -n com.abel.os4freeformx/.PickActivity --es test "<pkg>|<taskId>"`
就能在 adb 里单独驱动"加分屏"这一段，不必真手指做四指手势。

### 11. ⚠️ 严重冲突：角滑吃掉了 MIUI 自己的「底部中间上滑进多分屏」（2026-09-21）
**用户实测反馈：改完之后连官方操作都做不了了。** 根因两条，都已修：

1. **开关判断顺序错了（真 bug）**：命中角滑时先 `swallow = true`（吞掉整串触摸），**之后**才看
   `if (Cfg.cornerFreeform)`。于是"把角滑关掉"根本不起作用 —— 事件照样被吃掉，
   MIUI 的「底部中间上滑进多分屏」在这片区域起手就被掐死。
   **修法：先判开关，关了就直接放行（既不吞事件也不做动作）**。四指分支同样改掉。
2. **起手区与官方热区重叠**：角滑判定区原本是"屏幕下 45% 的左右各 40%"，与 MIUI 多分屏
   上滑的起手位置冲突。**修法：给角滑加上「底部正中最下缘 120dp、中间 1/3 宽」的让位区**，
   那片区域角滑不起手。

**恢复官方逻辑的紧急开关**（已验证有效）：配置文件写 `gestures=false`（或只关 `corner_freeform`）
后重启 SystemUI 即可；`onMotion` 顶部就有 `Cfg.gestures` 短路，**配置为 false 时完全不碰事件**
（现场取证：单指上滑后我们的手势日志 0 条 = 不介入）。写入方式（容器内无 python 执行环境，
用 `su -c sh` 写 XML，注意要同时写 CE 与 DE、并保持 `app_data_file` 上下文）：
```
/data/data/com.abel.os4freeformx/shared_prefs/os4freeformx_cfg.xml
/data/user_de/0/com.abel.os4freeformx/shared_prefs/os4freeformx_cfg.xml
```
**教训**：以后新增"会吞事件"的手势，开关判断必须放在吞事件之前；起手区必须与系统自身热区
（尤其底部中间、左右边缘返回手势）显式留出让位区。

### 12. 角滑的最终约束：只在「单应用全屏」生效（2026-09-21，用户指定）
用户明确规则：**角落上滑只在单应用全屏时才触发**。

这是对第 11 节冲突的正解 —— 分屏/多分屏状态下，屏幕下部（尤其右侧斜滑）就是 MIUI 自己的
「上滑到左上角进分屏 / 底部中间上滑进多分屏」热区，模块必须**完全退让**。

实现（`Gestures.onUp`）：
```kotlin
if (Cfg.cornerFreeform && isPlainFullscreen()) { swallow = true; consumed = true; cornerSwipeToFreeform() }
else if (Cfg.cornerFreeform) Logx.always("手势: 角滑命中但当前不是单应用全屏（分屏/小窗），放行给系统")
```
`isPlainFullscreen()` = `!splitActive() && !soScActive() && 前台任务 windowingMode == 1`。

同时判定区收紧（第 11 节基础上）：
| 参数 | 旧 | 新 |
| --- | --- | --- |
| 起手区宽 | 左右各 40% | **左右各 22%** |
| 起手区高 | 屏幕下 45% | **屏幕下 22%** |
| 方向比 \|dy/dx\| | 0.6 ~ 2.6 | **0.75 ~ 1.35**（必须明显斜向） |
| 最小行程 | 140dp | **200dp** |
| 底部让位区 | 120dp × 中间 1/3 | **220dp × 中间 1/2** |

**演示取证（官方逻辑恢复正常）**：用户在被要求演示后完成"双分屏→三分屏→四分屏"全部操作，
期间 SystemUI PID 恒定（无崩溃），官方 `MultipleSplitTransitionHandler` 转场全部成功；
我们的角滑三次命中但都是 `开关=false`（放行），证明"先判开关再吞事件"的修复有效。

### 13. 配置同步 bug + 收紧后功能①复验（2026-09-21）
`Cfg` 平时走**节流异步刷新**（1500ms），手势判定用的往往是启动时的旧快照 ——
真机表现：配置文件里 `corner_freeform=true`，日志却打 `开关=false`。
**修法：`onUp` 里命中时 `Cfg.reload()` 强制同步读一次**（只在抬指时一次，不在热路径）。

收紧判定区（22%×22%、方向比 0.75~1.35、≥200dp）+ 单应用全屏守卫之后，功能①复验通过：
```
手势: 角滑命中 侧=左 行程=1013px dx=760 dy=-670 用时=321ms 开关=true
角滑: 前台 pkg=top.funcun.dshfolk task=6472 mode=1
角滑: 已请求以小窗启动 top.funcun.dshfolk（x=140 y=1620）
→ dumpsys: Task #6472 mode=freeform ✅
```
**当前设备开关状态**：`gestures=true`、`corner_freeform=true`、`four_finger_split=true`
（三项全开，角滑已有全屏守卫，理论上不再抢官方分屏热区；若仍冲突可单独关 `corner_freeform`）。

### 14. 功能②不生效的真因：四指手势起手后被自己撤销（2026-09-21）
真机日志（用户实际操作）：
```
手势: 四指起手 pointers=4 @2301,772     ← 四指识别到了（8 次）
（一条「四指移动」都没有）                ← 起手后立刻被撤销
```
**三个 bug（都在判定条件上写得太严）**：
1. `ACTION_POINTER_UP` 里 `if (ev.pointerCount - 1 < FF_MIN_POINTERS) ffArmed = false`
   —— 4 指手势里手指不可能完全同步抬起，抬一根就把整个手势判死；
2. `onMove` 里 `if (ev.pointerCount < FF_MIN_POINTERS) ffArmed = false`
   —— 同因；两根以上就继续算，掉到 2 根以下才放弃；
3. 阈值本身偏严，一并放宽：最小行程 70dp→**45dp**、时间窗 1.2s→**2.6s**、水平漂移 200dp→260dp。

**教训**：多指手势的"持续条件"不能写成"必须始终 ≥N 指"，要写成"掉到 <2 指才放弃"，
否则真实手指的微小不同步就会把整条手势判死。

### 15. 功能②的生效范围（用户明确要求）
用户要求四指上滑在**全屏单任务 / 双分屏 / 三分屏…所有场景**都生效。
因此它的判定**不加** `isPlainFullscreen()` 这类场景守卫（那是功能①角滑专用的约束，
用来避让 MIUI 自己的分屏热区）；四指与任何官方手势都不冲突（官方没有四指手势），
所以只需在动作分支里区分：多分屏走 `insertMultipleSplitByTask`，其它走 `openWindowFromFullscreen`。

### 16. 四指手势的第四处隐患 + 一次自伤（2026-09-21）
- **松手时不能用"当前指针数"判四指**：`ACTION_UP` 那一刻系统通常只报 1 根手指，
  所以判定必须用**峰值 `ffPeak`**（已改为 `ffPeak >= FF_MIN_POINTERS`，并加注释说明原因）。
  前面三处（`onMove` / `ACTION_POINTER_UP` / 阈值）已在第 14 节修过，四处合起来才是完整修复。
- **自伤记录**：用 python 批量替换时把注释插到了 `if (...)` 与 `{` 之间，
  注释把左花括号吞掉 → Kotlin 报 `unresolved reference 'dy'/'dx'/'used'`。
  教训：批量改写代码时，注释要单独成行，**不要插在语句与花括号之间**。
- 四指过程日志改为限频（`Logx.once("ff-move-<时间片>")`），避免四指滑动时刷屏。

### 17. 功能②动作路径的最终优先级（2026-09-21）
按"优先用真机已跑通的路径"重排：

| 场景 | 走的路 | 真机状态 |
| --- | --- | --- |
| 双分屏（SoSc） | `transferSoScToMultipleSplit(ids, types)` → `insertMultipleSplitByTask(wct, taskId, index)` | 后者已跑通到 `applyTransaction` ✅ |
| 多分屏（3+） | `insertMultipleSplitByTask` 直接插 | ✅ |
| 全屏单任务 | `openWindowFromFullscreen(taskId, null)`（官方入口；线程断言已用 `Transitions.mainExecutor` 修掉） | 待真机确认 |

`openWindowFromFullscreen` / `startIconDragSplitScreen` 都需要**真实拖拽会话**的 PendingIntent
（`MiuiDragAndDropPolicy.mLaunchIntent`），模块自己造的 PendingIntent 会让转场只把桌面翻上来
（第 10 节已记录），因此不作为首选。

**候选**：过一遍 `MultiTaskingCommonUtils.supportSplit(RunningTaskInfo)`（系统自己的"能不能分屏"判断），
日志会打 `候选池=N 其中支持分屏=M → 选中 <pkg>`；用户实测"设置"不支持分屏，测试请用小红书/酷安。

**调试入口**（adb 可单独驱动，不必真手指做四指）：
```bash
# 加分屏：<pkg>|<taskId>
adb shell 'am start -n com.abel.os4freeformx/.PickActivity --es test "com.xingin.xhs|<id>"'
# 程序化配一组 SoSc 分屏：<idA>|<idB>（需要 test_hook=true）
adb shell 'am start -n com.abel.os4freeformx/.PickActivity --es test "MAKEPAIR:<idA>|<idB>"'
```

### 18. 四指手势的最后一环：**不能在 ACTION_UP 上判定**（2026-09-21，有监视日志为证）
加了后台 logcat 监视后拿到完整序列：
```
手势: 四指起手 pointers=4 @1664,1136
手势: 四指移动 pc=4 dy=-6   used=16ms
手势: 四指移动 pc=4 dy=-184 used=99ms      ← 滑动了、四指都在
手势: 四指移动 pc=4 dy=-472 used=274ms     ← 行程早就超过 45dp 阈值
（但全设备日志里 "四指上滑命中 / 四指未命中" 计数 = 0）  ← 说明 ACTION_UP 根本没到
```
**结论：MIUI 的 `monitorGestureInput` 通道在四指抬完时不给收尾事件**（单指时是正常的，
功能①角滑就是靠 ACTION_UP 判定的）。所以四指手势**必须在 MOVE 上判定**：
只要四指还在、行程够、时间窗内，**立即触发一次**（`ffFired` 防重复），不等松手。

至此四指手势一共修了 **5 处**（都在 NOTES 14/16/18 节）：
1. `onMove` 指针数 <4 就撤销 → 改为 <2 才放弃；
2. `ACTION_POINTER_UP` 抬一根就撤销 → 同上；
3. 阈值偏严：行程 70→45dp、窗口 1.2→2.6s；
4. 松手判定误用"当前指针数" → 改用峰值 `ffPeak`（现已不依赖 UP，但保留以防万一）；
5. **判定时机从 UP 改到 MOVE**（本节的根因）。

**通用教训**：MIUI 的多指监视通道**不保证**投递 ACTION_UP，凡是多指手势，判定都要放在 MOVE 上并自带防重复。

### 19. 功能②真机验证通过（2026-09-21）✅
四指手势修完第 5 处（MOVE 上判定）之后，用户一次四指上滑就跑通了整条链：
```
手势: 四指起手 pointers=4 @…
手势: 四指移动 pc=4 dy=0/‑18 used=17/66ms
手势: 四指上滑命中(MOVE) 行程=127px 用时=191ms 手指数=4
四指上滑: SoSc=false 多分屏=false 组内=[] shell已知=18
四指上滑: 选中 pkg=com.tencent.mm task=5805（候选池 18）
四指上滑: 已请求系统分屏吸附（openWindowFromFullscreen task=5805 pkg=com.tencent.mm）
```
`dumpsys activity activities` 取证（**真分屏结构**）：
```
rootTaskId=6661
  Task #6662 name=main → Task #5805 com.tencent.mm    visible=true mode=multi-window
  Task #6663 name=side → Task #6546 top.funcun.dshfolk visible=true mode=multi-window
```
SystemUI PID 15547 全程未变（无崩溃）。

**结论**：`openWindowFromFullscreen(taskId, null)` 这条官方入口**是可用的** —— 之前失败是因为判定没触发
（四指手势那 5 个 bug），不是入口本身的问题。候选池 18 个、`supportSplit` 过滤后选中了微信。

**两个功能的最终状态**
| 功能 | 状态 | 取证 |
| --- | --- | --- |
| ① 角落斜滑 → 小窗 | ✅ 真机通过 | `角滑命中 … 开关=true` → `mode=freeform`（dumpsys） |
| ② 四指上滑 → 加分屏 | ✅ 真机通过 | `四指上滑命中(MOVE)` → `openWindowFromFullscreen` → main/side 双 stage（dumpsys） |

---

## 交付状态（2026-09-21 定稿）

**两个功能全部真机验证通过**：
| 功能 | 取证 |
| --- | --- |
| ① 左右下角斜滑 → 前台应用转小窗 | `角滑命中 … 开关=true` → `dumpsys` 该任务 `mode=freeform` |
| ② 四指上滑 → 加进分屏 | `四指上滑命中(MOVE)` → `openWindowFromFullscreen` → `dumpsys` rootTaskId 下 main/side 双 stage 均 `mode=multi-window` |

**代码与文档**：15 个提交，`README.md`（1.4 节）与本文档（19 节）均已定稿，工作区干净。

**推送 GitHub**：remote 已配好（`https://github.com/AbelDuan/Freeform.git`），本地分支已改名 `main`，
差**鉴权**（容器内无 SSH key / token）。两种交付方式：
1. 提供 token 后由 agent 直接 `git push origin main`；
2. 用 `dist/OS4FreeFromX-gestures.bundle` 在电脑上推：
   `git clone OS4FreeFromX-gestures.bundle Freeform && cd Freeform && git remote set-url origin <repo> && git push origin main`

**遗留调试入口**（默认关闭，`test_hook=false`）：`watchTestHook()` + `PickActivity --es test`
可在 adb 里单独驱动"加分屏 / 配对分屏"，用于以后回归测试；确认不需要可删。

### 20. ⚠️ 分屏场景闪退：已止血，接线方案待确认（2026-09-21）
用户反馈：**双分屏状态下四指上滑 → 黑屏 / 卡顿 / 闪退**。
（全屏场景正常：四指上滑起分屏已验证通过，见第 19 节。）

**原因**：代码里"已在分屏"那一支走的是
`transferSoScToMultipleSplit(...)` + `MultipleSplitController#insertMultipleSplitByTask(wct, taskId, index)`。
但 **SoSc 双分屏是"一对 stage"的结构，多分屏是"多个 stage"的结构**，两者之间需要一个**专用转场**
才能衔接 —— 直接往 SoSc 的 root 里插 stage 会与 SoSc 状态机（`SoScStageCoordinator` /
`DividerSnapAlgorithm` / `SoScUtils` 的 state）冲突，表现为黑屏卡顿甚至 SystemUI 重启。
`transferSoScToMultipleSplit(List, List)` 的两个入参语义（是"任务 id 列表"还是"stage 索引/类型"）
**没有在真机上确认过**，之前是照签名猜的。

**已做的止血（本轮已部署）**：分屏场景**只识别不动作**，命中后打日志立即返回：
```
四指上滑: 当前已在分屏（SoSc=… 多分屏=…），动作暂时短路（避免 SoSc/多分屏状态机冲突导致闪退）
```
这样全屏起分屏（已验证可用）保留，分屏内不再有任何动作、不会再闪退。

**下一步接线方案（按风险从低到高）**：
1. **先取证**：抓一次真实"双分屏里拖第三个应用进左上角"的完整 logcat，
   看系统到底调了哪个方法、传了什么（重点看 `MultipleSplitTransitionHandler: setEnterTransition`、
   `MultipleSplitController#transferSoScToMultipleSplit` 的真实入参、以及
   `SoScUtils#prepareEnterTopBottomSplitFromMultiWindow` 这类"衔接"方法）；
2. **照抄真实参数**再调用，而不是按签名猜；
3. 若转场确实必须由拖拽会话发起，则退回"只在全屏场景加分屏"（已验证可用），
   分屏内加窗交给系统原生手势。

### 21. 找到 `transferSoScToMultipleSplit` 的正确参数语义（2026-09-21，反编译定位）
第 20 节的闪退根因确认：**参数传错了**。官方调用点
（`MultipleSplitShellCommandHandler#runTransferSoScToMultipleSplit`，shell 命令帮助写作
`transferSoScToMultipleSplit <index1> <index2>`）反编译出来是：
```java
SoScUtilsImpl soc = SoScUtilsImpl.getInstance();
List stageList = new ArrayList(); stageList.add(soc.getLeftTopStage()); stageList.add(soc.getRightBottomStage());
List indexList = new ArrayList(); indexList.add(0); indexList.add(1);
MultipleSplitController.transferSoScToMultipleSplit(stageList, indexList);
```
即**第一个参数是 SoSc 的左右 stage 对象列表**（`getLeftTopStage()` / `getRightBottomStage()`），
第二个是**分屏索引**。我上一版传的是 **taskId 列表** → SoSc 状态机直接崩。

**已改正**（照官方语义），但分屏内动作仍**默认关闭**，用独立灰度开关
`four_finger_split_indoor`（默认 `false`）控制：
- `false`（默认）：分屏内四指上滑只打日志短路，**绝不动作**（保证不闪退）；
- `true`：走 `transferSoScToMulti()`（stage 列表 + 索引[0,1]）→ `insertMultipleSplitByTask` 插第三个。

验证顺序建议：先开 `four_finger_split_indoor=true`，在**双分屏**里四指上滑一次；
若看到 `已请求 SoSc→多分屏（stage=[leftTop,rightBottom] index=[0,1]）` 且不闪退，再继续调插入那一步。

### 22. 灰度开关已打开，等真机验证（2026-09-21）
按用户选择，打开了分屏内动作的灰度开关：
```
gestures=true  corner_freeform=true  four_finger_split=true  four_finger_split_indoor=true
```
**注意**：改配置后要 `am force-stop com.abel.os4freeformx` 让模块 App 重读盘 ——
否则它内存里的 SharedPreferences 还是旧值（provider 会一直回旧配置，真机踩过两次）。

**在双分屏里四指上滑的预期日志**（参数已按官方语义改正，见第 21 节）：
```
手势: 四指上滑命中(MOVE) 行程=… 手指数=4
四指上滑: SoSc=true 多分屏=false 组内=[…]
四指上滑: 候选池=N 合格=M
四指上滑: 已请求 SoSc→多分屏（stage=[leftTop,rightBottom] index=[0,1]，照官方语义）
四指上滑: 多分屏插桩已提交（task=… index=…）
```
**若再出现闪退**：把 `four_finger_split_indoor` 改回 false（或直接 `gestures=false`）即可完全止血。

### 23. 分屏内加窗"另一侧黑屏"的原因与修法（2026-09-21，用户实测）
用户反馈：四指上滑后**单侧分屏生效、另一侧黑屏**；系统默认行为是那一侧进"桌面选择应用"。

真机 `dumpsys` 取证：多分屏容器建出来了，但**所有 stage 都是空的**：
```
rootTaskId=6724
  Task #6730 name=stage_f ... sz=0
  Task #6729 name=stage_e ... sz=0
  Task #6728 name=stage_d ... sz=0
  ...
```
即 `insertMultipleSplitByTask(wct, taskId, index)`（塞"已有任务"）在真机上**没能把任务放进 stage**。
系统自己的等价做法是 `insertMultipleSplitByIntent(wct, PendingIntent, index)`
—— 由系统**启动**应用进那个 stage（这也解释了为什么原生是"那一侧让自己选应用"）。

**修法**：`insertPane()` 改为首选 `insertMultipleSplitByIntent`（用候选包的启动 Intent 现造 PendingIntent），
只有造不出 PendingIntent 时才回退 `insertMultipleSplitByTask`。

**另外修掉的候选 bug**：候选池必须过滤掉"取不到包名"的任务
（真机踩过 `选中 pkg=? task=6686`，这种任务既加不进分屏、日志也看不出是谁）。

### 24. `soScActive()` 探测错类（2026-09-21，真机日志定位）
用户验证日志里每一条都是 `四指上滑: SoSc=false 多分屏=false 组内=[]`，
**明明是在分屏里滑的**。原因：`isSoScActive()` 定义在 **`SoScUtilsImpl`** 上，
**不在** `MultipleSplitController` 上（反编译确认：`sosc/SoScUtilsImpl.smali:13415`）。
之前按 `MultipleSplitController` 反射 → 静默失败 → 永远 false →
于是在分屏里也走"重新起分屏"分支（`openWindowFromFullscreen`），
表现就是"动作没效果 / 另一侧黑屏"。

已改为经 `socUtils()`（即 `SoScUtils.getInstance()`）调 `isSoScActive()`。

**教训**：反射探测方法时不要"猜类"，先去反编译产物里 `grep '\.method public <name>'`
确认它到底在哪个类上；静默 `runCatching` 会把这类错误藏得很深。

### 25. 分屏内加窗的时序修复（2026-09-21）
`transferSoScToMultipleSplit` 是**异步转场**。之前代码是"调转分屏 → 立刻 `insertPane`"，
转场还没落地就插 stage，结果就是**stage 建了但空的**（用户看到的"另一侧黑屏"）。
已改为转分屏后 `main.postDelayed({ insertPane(...) }, 450)`，给官方转场留出落地时间。

**分屏内加分屏的完整链路（当前实现）**：
1. 命中四指上滑 → `soScActive()`（经 `SoScUtils.getInstance().isSoScActive()`）判定
2. 双分屏：`transferSoScToMultipleSplit([getLeftTopStage(), getRightBottomStage()], [0,1])`
3. 等 450ms 转场落地
4. `insertMultipleSplitByIntent(wct, PendingIntent(候选包), index)` —— 由系统启动应用进新 stage
5. `ShellTaskOrganizer#applyTransaction(wct)` 提交

### 26. 原生多分屏的真实调用链（2026-09-21，抓用户原生操作日志）
用户实测反馈两条：
1. 四指从全屏→分屏**能实现**，但那一侧有时放应用、有时直接翻桌面（不像原生"半屏应用 + 半屏让用户选"）；
2. 双分屏后四指上滑，**双分屏会向左变小、右侧多出一个黑块**，不是系统多分屏逻辑。

抓原生操作（双分屏→三分屏）的完整日志，拿到系统真实路径：
```
SoScUtilsImpl.prepareDragDropTaskToSoSc
  → SoScSplitScreenController.prepareDragDropTaskToSoSc
  → SoScStageCoordinator.prepareDragDropTaskToSoSc
  → SoScStageCoordinator.onPreSoScStateChanged rootBounds:Rect(0,0-2364,1672)
       lotBounds:Rect(0,0-1170,1672) robBounds:Rect(1194,0-2364,1672)
RecentTasksController: addSplitPair taskId1:6813 taskId2:6749
SoScStageTaskListener: activate: stage=MAIN … prepareEnterSoSc

进入多分屏时：
MultipleSplitUtils: extractAndAddMultipleSplitGroupedTask
    taskId: 6815, pairedTaskIds: [6548, 6814, 6815], splitBounds: mIsMultipleSplit: true …
hyper_launcher_app(3778): grouped_recent_task_info → created MultipleSplitTask
MultipleSplitTransitionHandler: Transition requested: type = TO_FRONT, triggerTask = TaskId 6546
RecentsTransitionHandler$RecentsController.finishInner
MultipleSplitTransitionHandler.onRecentsInSplitAnimationFinishing
MultipleSplitRootTaskOrganizer.prepareExitMultipleSplit
```

**结论（下一轮的正确做法）**：
1. **多分屏是"一组任务 id"整体构建的**（`pairedTaskIds` 列表 → `extractAndAddMultipleSplitGroupedTask`），
   不是"先 `transferSoScToMultipleSplit` 再逐个 `insertMultipleSplitBy*`" ——
   后者把原分屏重新布局却没填内容，用户看到的就是"左半屏变小 + 右侧黑块"。
2. 进入多分屏的转场是**伴随 recents 动画**完成的
   （`RecentsTransitionHandler` → `onRecentsInSplitAnimationFinishing`），
   说明系统是从**桌面/最近任务**侧发起"把这组任务铺成多分屏"。
3. **"那一侧留空让用户选应用"是系统原生行为**（半屏应用 + 半屏桌面），
   我们不该自作主张塞候选应用 —— 应改成"把当前任务 + 一个空位"交给系统，
   由系统拉起选择界面（这才是用户最初的需求："弹出应用列表让我点"）。

### 27. 按原生链改的第一版（2026-09-21）
**① 全屏 → 分屏**：改用原生入口 `SoScUtils.prepareDragDropTaskToSoSc(wct, taskId, hotAreaType, caller)`
（抓到的原生链：`SoScUtilsImpl → SoScSplitScreenController → SoScStageCoordinator`），
热区传 `HOT_AREA_SPLIT_LEFT_OR_TOP=1`，随后 `applyTransaction` + `finishEnterSplitScreen`。
替换掉之前的 `openWindowFromFullscreen`（它是上游封装，参数语义不同，会出现"直接翻桌面"）。

**② 分屏内加窗**：**先短路**。因为原生是"一组任务 id 整体构建"
（`extractAndAddMultipleSplitGroupedTask(taskId, pairedTaskIds, splitBounds)`），
而我那条 `transferSoScToMultipleSplit` + `insertMultipleSplitBy*` 是自己拼的，
真机表现就是用户看到的"原分屏被重新布局 + 右侧黑块"。
接线方案已明确（见第 26 节），等这一版验证不再有黑块后再上。

### 28. ⚠️ 回退记录：`prepareDragDropTaskToSoSc` 会黑屏（2026-09-21 实测）
第 27 节我按抓到的原生链换成 `SoScUtils.prepareDragDropTaskToSoSc(wct, taskId, hotArea, caller)`，
**真机结果：双分屏两侧都黑屏，不进系统默认桌面**（用户实测）。
说明这个方法的参数语义（尤其后两个 int）我**并没有摸对** —— 它内部是给"拖拽进行中"的状态机用的，
脱离拖拽会话直接调，状态机停在中间态就成了黑屏。

**已回退到 `openWindowFromFullscreen(taskId, null)`**（`MulWinSwitchTransition`），
因为它真机验证过：
- 能真正起分屏（main/side 双 stage，dumpsys 取证）；
- 行为正是系统默认的"半屏应用 + 半屏桌面让用户选"。

**教训**：日志里看到的方法名 ≠ 可以脱离上下文调用。抓调用链只能确认"谁调了谁"，
**不能确认"脱离原有会话是否还能用"**；这类涉及状态机的方法，必须小步灰度 + 立即可回退。

### 29. ⚠️ 概念纠正（用户说明，2026-09-21）：多分屏 ≠ 分屏加内窗
用户明确指出：**多分屏是系统自带的另一个模式**，从**三分屏**开始进入，**最多六个应用同时运行**。
它**不是**"在双分屏里再塞一个窗口"，两者是不同的 windowing 状态（真机也印证：
多分屏 stage 名为 `stage_c/d/e/f`、容器是独立 rootTask；双分屏是 `main/side` 一对）。

**这意味着我之前的方向是错的**：我一直在做"双分屏 + `insertMultipleSplitBy*` 塞第三个"，
而用户要的是**进入系统的多分屏模式**。所以：
- 不该自己拼 stage（会留黑块 / 状态机崩）；
- 应该找**系统进入多分屏的入口**，用四指手势去触发它。

**已定位的候选入口**（反编译确认签名）：
| 方法 | 签名 | 说明 |
| --- | --- | --- |
| `MultipleSplitUtilsImpl#extractAndAddMultipleSplitGroupedTask` | `(TaskInfo, Map, ArrayList, List) → Z` | 系统按"一组任务"构建多分屏的核心（真机日志里出现） |
| `MultipleSplitUtilsImpl#startMultipleSplits` | `(Bundle) → void` | shell 命令 `startMultipleSplits recent <taskId>…` 走的也是它 |
| `MultipleSplitController#insertMultipleSplitByIntent/ByTask` | `(WCT, …, int)` | 单点插入，**不是**进入多分屏的正确方式 |

**下一步**：需要用户配合抓一次"系统原生进入多分屏"的完整意图/参数
（上次抓到的日志只到 `extractAndAddMultipleSplitGroupedTask` 的调用，没拿到上游的 Bundle/Intent 内容）。
或者反过来：**hook 系统的多分屏入口**，让四指手势等价于用户手动触发那一个动作，而不是自己造调用。

### 30. 找到「进入系统多分屏」的官方入口与参数（2026-09-21，用户完整演示时抓到）
用户做完整演示（单任务→双分屏→三分屏→四分屏→五分屏）时的日志给出决定性证据：
```
hyper_launcher_app(3778): WindowTransitionCoordinator applyInputConsumer
        action=TransitionAction.startMultipleSplits
hyper_launcher_app: split_gesture_callback: enter half split / notify_split_mode_changed
MultipleSplitLayout(29255): MultiTaskingStateManager$IMultiTaskingStateManagerImpl
        .lambda$startMultipleSplits$9
```
即：**桌面进程（Flutter launcher）通过 Binder 调 SystemUI 的 `startMultipleSplits`**，
它再走到 `MultipleSplitRootTaskOrganizer#startMultipleSplits(Bundle)`。

**Bundle 键（反编译 `MultipleSplitRootTaskOrganizer#startMultipleSplits` 里读的）**：
| key | 类型 | 含义 |
| --- | --- | --- |
| `multiple_launch_taskIds` | int[] | 要铺成多分屏的任务 id **整组** |
| `multiple_launch_bounds` | Rect[] | 每个任务的 bounds |
| `multiple_launch_way` | String | 来源（如 `recent`） |
| `multiple_launch_enter_quick_view_mode` | boolean | 是否进快速查看模式 |

**用户强调的系统行为**：每次新增分屏界面，**系统都是给出桌面让用户自己选应用** ——
所以正确做法是"把一组 taskIds 交给系统 `startMultipleSplits`，由系统铺 stage 并留空位"，
而不是自己 `insertMultipleSplitBy*` 往 stage 里塞已有任务（那会留黑块）。

**本轮实现**：四指上滑在分屏状态下调用
`getMultipleSplitController().startMultipleSplits(Bundle)`（失败回退 `getMultiTaskingStateManager()`），
taskIds = 当前分屏组 + 选中候选，bounds 用整屏占位；日志 `已请求进入多分屏 startMultipleSplits(taskIds=[…]) ok=…`。

### 31. 「第一次能进分屏、之后进不去」——诊断版已上线（2026-09-21）
用户实测：**第一次**四指上滑能从单任务进双分屏；**之后再滑进不去**；退回桌面再进单任务，
有时又恢复能力（**概率性**）。这是典型的**状态机没回到空闲**或 **taskId 失效**。

已加诊断（本轮）：每次命中都把完整状态打进日志：
```
四指上滑: SoSc=<bool>(state=<getSoScState()>) 多分屏=<bool> 组内=[…] shell已知=N
          前台=<pkg>/<taskId>/mode=<n>
```
判读方法：
- `state` 不是空闲值（`-1` / `0`）→ 上一次分屏的收尾还没结束，此时再调
  `openWindowFromFullscreen` 会被 SoSc 状态机拒绝（表现就是"滑了没反应"）；
- `前台=null` 或 `taskId=-1` → 取不到前台任务，`openWindowFromFullscreen(-1, null)` 自然无效；
- `mode=6` 但 `SoSc=false` → 说明处在**多分屏残留**状态，需要先 `exitMultipleSplit`。

> 本轮还踩了个自伤：批量插入辅助函数时 python 的 `str.index` 定位失败，
> 导致 `socStateDesc()` 被引用但没定义、Kotlin 编译报 `unresolved reference`。
> 已改成内联表达式。**教训：批量改代码后必须看编译输出，不能只看"产物"字样**。

### 32. ⚠️ 四指功能已关闭 + 用户两条反馈的技术判读（2026-09-21）
用户实测：
1. 四指上滑**会**进双分屏，但**另一侧是自动打开一个应用**（我要的是"完全调用系统逻辑，另一侧进桌面让用户选"）；
2. 双分屏下四指上滑**进不了三分屏**；回桌面后 **dock 消失一会儿再出现**；之后**四指再也进不了分屏**，
   而且**导航手势条上滑也大概率进不了桌面**。

**结论：我的动作把系统状态搞乱了（第 2 条最后那句是红线），已立刻关闭四指功能**
（`four_finger_split=false`、`four_finger_split_indoor=false`、`test_hook=false`，保留 `corner_freeform=true`）。

**技术判读**：
- **第 2 条的因果链**：`openWindowFromFullscreen` 起的是**双分屏（SoSc 一对 stage）**，
  而"三分屏"属于**多分屏模式**（完全不同的容器）。我连续用"起双分屏"的入口去凑多分屏，
  状态机在半途被反复拉扯 → dock 重建、返回手势区域（`GestureTouchableRegion`）被改坏
  → 连带"导航条上滑回桌面"失效。**这不是参数问题，是入口选错了**。
- **第 1 条的因果链**：`openWindowFromFullscreen(taskId, null)` 在 taskId 有效时走任务分支，
  系统直接把那个任务放进另一侧（= 用户看到的"自动打开一个应用"）；
  只有 taskId 无效时才走 intent 分支并可能翻桌面。用户要的是**另一侧留空 + 系统弹桌面选择**。

**正确路线（已由日志确认，下一轮照此实现）**：
`hyper_launcher_app: TransitionAction.startMultipleSplits`
→ `MultiTaskingStateManager.startMultipleSplits(Bundle{multiple_launch_taskIds,…})`
即**从一开始就走多分屏入口**（不要先 `openWindowFromFullscreen` 起双分屏再补），
并且**不要在双分屏状态下调用它**（那时状态机已在 SoSc 模式，需要系统自己的转换动作）。

**已实现的入口保留在代码里但默认关闭**：`startMultipleSplits(Bundle)`（见第 30 节），
待确认"从单任务直接进多分屏"是否可行后再开灰度。

### 33. 渐进式四指：现状与下一步（2026-09-21）
用户明确交互：**单任务 → 双分屏 → 三分屏 → …**，每次四指上滑加一层。

已验证可用的那一层：**单任务 → 双分屏**（`openWindowFromFullscreen`，另一侧出桌面让用户选；dock/导航条正常）。

**"分屏中再加一层"尚未接线**（当前该分支只打日志、不动作 —— 见第 32 节的止血）。
接线要点（已定位）：
- 已在分屏/多分屏 → 调 `getMultipleSplitController().startMultipleSplits(Bundle)`（失败回退
  `getMultiTaskingStateManager()`），`multiple_launch_taskIds` = **当前各 stage 的任务 id**（+补位到 ≥3），
  bounds 用整屏占位；由系统铺 stage 并为空 stage 留位让用户选应用；
- 不要用 `openWindowFromFullscreen` 去凑多分屏（会拉扯状态机，dock/返回手势失效 —— 第 32 节）。

**工程教训（本轮再次踩到）**：用 python 的 `str.index`/`str.replace` 做批量改写时，
只要有一处理论不匹配就会**中途抛异常**，而异常被 `&&` 链吞掉后我可能误以为"已改+已构建"。
**必须校验替换结果（`print(patched)`）并核对产物**，不能只看"产物"两个字。

### 34. 三分屏"未被调用"的真因：组内 id 是历史残留（2026-09-21）
用户实测"三分屏未被调用"。日志显示函数**其实被调了、而且 `ok=true`**，但参数是错的：
```
四指上滑状态判定: 多分屏=true SoSc=true 当前层数=6 组内=[6995,6997,6998,6999,7000,6996]
四指上滑: 已请求进入多分屏 startMultipleSplits(taskIds=[这 6 个残留 id]) ok=true
```
**根因**：`stageTaskIds()` 用了 `MultipleSplitController#getAllStageTaskInfo()`，
它把**历史遗留的 stage** 一起返回（本项目已第二次踩这个坑）→
`startMultipleSplits` 拿到一堆过期 taskId，系统铺不动，屏幕无变化（但接口返回 true）。

**修法**：改为只取**当前真正可见**的分屏子任务 ——
`MultiTaskingTaskRepository#getVisibleSplitChildTaskInfo()`，
兜底再用 `MultipleSplitController#getActiveStageList()` 里的 `getRunningTaskInfo()`。

**教训**：凡是"取当前分屏组"的地方，都必须用**可见/活跃**语义的 API，
`getAllStageTaskInfo()` 这类"全部 stage"的接口在本机一定会带出历史残留。

### 35. ⚠️ `swallow` 死锁：第一次能进分屏、之后全没反应的真因（2026-09-21）
用户怀疑"是手势问题"，**判断正确**：
```
四指手势进双分屏 → 退出到单任务 → 再四指上滑：无反应
手动进双分屏 → 再四指上滑：也无反应
```
**机制**：四指手势是**在 MOVE 上触发**的（因为 MIUI 多指通道不投递 ACTION_UP，见第 18 节），
触发时我设了 `swallow = true`（本意是"这串事件不再交给 MIUI"）。
而清除 `swallow` 的唯一条件是收到 `ACTION_UP`/`ACTION_CANCEL` —— **多指场景下它常常永远不来**，
于是 `swallow` 一直是 true，**之后每一次触摸都被无条件吞掉**，
表现为"手势彻底失灵"。

**修法**：`swallow` 加 **700ms 超时**（记录 `swallowAt` 时间戳，超时自动解除）。
理由：我们的目的只是"别让 MIUI 把同一串手势再解释一遍"，700ms 足够覆盖那串事件，
而远小于用户两次手势之间的间隔。

**教训**：凡是"吞掉事件"的状态标志，都必须有**超时兜底** ——
不能假设收尾事件一定会到，尤其在这个项目的多指路径上（已经两次被 ACTION_UP 缺席坑到）。

### 36. 黑屏根因：多分屏入口传了**重复 taskId**（2026-09-21）
用户实测（第 35 节修完 swallow 之后）：双分屏正常、**三分屏失败、随后一次黑屏**。

日志（状态判定这次是**对的**）：
```
四指上滑状态判定: 多分屏=false SoSc=true 当前层数=2 组内=[7068, 7073]
四指上滑: 已在分屏（2 层）→ 调**加层/多分屏**接口
四指上滑: 已请求进入多分屏 startMultipleSplits(taskIds=[7068, 7073, 7068]) ok=true
                                                                    ↑ 第三个是补位补出来的
```
**根因**：我之前写了"补位到 ≥3 个 id"（`while (ids.size < 3) ids.add(ids[0])`），
结果传进去的是 `[A, B, A]`。系统按"三个任务"去铺 stage，**拿到重复 id 直接黑屏**。

**修法**：严格**只传真实存在的任务 id**并用 `LinkedHashSet` 去重，**不补位**；
数量不足时把决定权交回系统（它自己会为空 stage 留位/弹选择界面）。

**教训**：这类"整组 id"接口，参数必须是**互不相同的真实实体**；
为了凑数量而复制元素，会被状态机当成非法输入。日志里 `ok=true` 也不代表参数合法
（第 34 节已经吃过一次"ok=true 但参数是残留 id"）。

**当前默认值**：`four_finger_multi` 现已重新打开以便验证去重修复；
若再出现黑屏，立即改回 `false`（单任务→双分屏那一层已验证可用，不受影响）。

### 37. 按"只用官方接口"的底线改造：改调 IMultiTaskingStateManager（2026-09-21）
用户定的底线：**系统已有的功能一律走官方接口，我们只新增一个调用**。

据此把我之前的做法纠正过来。官方链路（日志 + 反编译双重确认）：
```
桌面(hyper_launcher_app)
  └─ bindService → SystemUI 的 MultiTaskingStateManager$OutMultiTaskingStateManagerService
       └─ onBind 返回 IMultiTaskingStateManager（实现是 MultiTaskingControllerImpl）
  └─ Binder 调用 IMultiTaskingStateManager#startMultipleSplits(Bundle)   ← **官方接口**
```
**我之前错在**：直调下层 `MultipleSplitController#startMultipleSplits`（实现细节，不是入口），
所以出现"返回 true 但黑屏 / 甚至 SystemUI 重启"。

**改法**：只经官方接口调用 —— 取 `MultiTaskingControllerImpl.getMultiTaskingStateManager()`，
确认它是 `IMultiTaskingStateManager` 的实例后，调 `startMultipleSplits(Bundle)`；
**不再回退到下层 controller**（宁可不做也不绕官方接口）。

Bundle 键（照抄官方）：`multiple_launch_taskIds`(int[], 互不相同的真实 id) /
`multiple_launch_bounds`(Rect[]) / `multiple_launch_way`(String) /
`multiple_launch_enter_quick_view_mode`(boolean)。

### 38. ⚠️ 仍然缺一环：官方入口需要"被选中的应用"
官方 `startMultipleSplits` 的 Bundle 里 `multiple_launch_taskIds` 是**已确定的任务列表**，
它并不会自己弹"选择应用"的界面 —— 那个选择界面在**桌面进程**里
（日志：`hyper_launcher_app … split_gesture_callback` /
`grouped_recent_task_info → created MultipleSplitTask`）。

因此"整体交给系统、由系统让用户选应用"要成立，只有两条路：
1. **扩模块作用域到桌面进程**（`com.miui.home`），hook 桌面的分屏入口，
   让四指手势等价于用户在那里点一下（**这是最贴近"只新增一个调用"的做法**）；
2. 先取到用户选定的应用，再用官方接口把它加进分屏（需要模块自己出选择界面 ——
   与用户"要有系统自己的选择界面"的要求不符）。

**结论**：要实现"四指上滑 → 系统弹选择应用 → 加进分屏"，必须把 scope 扩到桌面进程。
这一步需要用户决定（LSPosed 里勾选 `com.miui.home` + 模块 scope.list 增加该进程）。

### 39. ⛔ 结论：`startMultipleSplits` 这条路彻底关闭（2026-09-21）
用户实测：四指分屏后**一段时间 SystemUI 会重启**（换成官方接口 `IMultiTaskingStateManager` 之后仍然如此）。

**最终结论（三条路径都已试过，全部失败）**：
| 尝试 | 结果 |
| --- | --- |
| 直调 `MultipleSplitController#startMultipleSplits` | 黑屏、SystemUI 重启 |
| 调官方接口 `IMultiTaskingStateManager#startMultipleSplits` | 同样黑屏 / 延迟 SystemUI 重启 |
| 自己拼 stage（`transferSoScToMultipleSplit` + `insertMultipleSplitBy*`） | 左半屏变小 + 右侧黑块 |

**根因判断**：官方 `startMultipleSplits` 的 Bundle 期望的是**桌面进程构造好的、完整的一次分屏事务**
（含 `multiple_launch_taskIds` 互不相同的真实任务 + 对应 bounds + way），
并且它假定调用方处在"桌面正在做分组动画"的上下文里。
**在 SystemUI 进程里脱离该上下文调用，状态机会停在中间态 → 延迟崩溃/重启。**

用户底线"只用官方接口"**依然成立** —— 官方接口确实找到了、也确实调了，
**是这个接口不允许脱离它的调用上下文使用**（不是参数问题：`ok=true` 也照样崩）。

**因此 `four_finger_multi` 永久锁死为 false**（`Constants.DEF_FOUR_FINGER_MULTI`），
不再提供"加层进多分屏"能力。多分屏请继续使用**系统原生手势**。
**保留并已验证可用的**：单任务 → 双分屏（四指上滑，另一侧出桌面让用户选）。

**用户还指出**：桌面 app 是 **Rust** 写的，**不好 hook**、也不打算走"模拟点击"的路子。
所以"由桌面提供选择界面"这条也不作为实现方向。

---

## 40. 尺寸记忆的真根因：WCT 提交 `freeformScale` 改不动画面（2026-10-01 实证）

### 40.1 现象复盘（用户口径）
"位置和尺寸记忆没实现"：重开小窗后位置/尺寸都不对。真机日志证明**链路是通的、但写进去的值是错的**。

### 40.2 三个真机根因（都在代码里，已修）
| # | 根因 | 证据 | 修法 |
| --- | --- | --- | --- |
| 1 | `persist(..., "gestureTaskInfo")`（拖动路径）**不传 scale** ⇒ 写"裸 bounds" ⇒ 跨进程 `load()` 后 `scaleCache` 为空 ⇒ `getScale()=0` ⇒ 尺寸恢复静默跳过 | 存储里 4 条记忆 3 条无 `@scale`；`尺寸记忆:` 日志 0 条 | 拖动改走 `record()`，与缩放同源，恒写 `(bounds, scale)` 一对 |
| 2 | `Bounds.put` 只在 **bounds** 变化时落盘（`if (!changed) return`）⇒ "只改尺寸"（拖角柄时真实 bounds 恒定、只有 scale 变）**完全不写**、连日志都没有 | 真机：真实恒为 `502,494-1672,2364`，scale 0.38/0.41/0.4065 在变 | `changed` 判定加上 scale 变化；`scale<=0` 沿用旧 scale，绝不写裸值 |
| 3 | 记录端拿**屏幕 rect**去 clamp 一个**未缩放坐标**里的 rect（MIUI 默认 `834,397-2004,2267`，屏宽只有 1672）⇒ 用户放好的位置被 `offsetTo` 成"右下贴边" | 日志：记忆 `357,494` 被夹成 `502,494`；重开永远回原位 | 记录端与恢复端都**不再夹取**；几何适配交回 MIUI |

顺带删掉的死闸门：`isGestureRecord`（只认两种 why）、`rec-skip-clamped`+`heal`（按屏幕坐标比较、1px 抖动就翻脸）、`Recorder` 700ms 防抖、`gestureLayout`/`hidden` 两条永远被拦的写入、`Bounds.clamp/clampKeepRatio/getAny`（−60 行）。

### 40.3 ⛔ 尺寸不能用"事后提交 scale"来恢复（**这是第 4 条，也是关键**）
改完上面三条后，日志看"尺寸记忆: 已恢复 scale 1.0 -> 0.25066197"**成功了**，但读 SurfaceFlinger 层变换发现：

```
Layer [76907] VRI-com.ss.android.ugc.aweme/...SplashActivity
    geomBufferSize=[0 0 1133 1672]                                  ← 真实 bounds
    toDisplayTransform={ scale x=0.6600 y=0.6600 tx=75 ty=744 }     ← 实际渲染 0.66！
```
而同一时刻模块读到的字段 `freeformScale` 是 **0.2507**（我们的提交写进去的）。
**⇒ `WindowContainerTransaction#setMiuiFreeformInfoChange(scale)` 只改 MIUI 的字段，不改渲染。**
（对照：MIUI 自己的 task snapshot / 其它小窗 app 的 `mFreeformScale` 都是 0.66 —— 这是 MIUI 打包的"标准小窗缩放"，
**每次新建小窗都用它**，跟我们在字段里写什么无关。）

**危害**：MIUI 的装饰层（三点 / 角柄 / 底栏 / 触摸区）是按 `bounds × 字段` 摆位的 ⇒
一提交就把「看得见的窗口」（渲染 0.66）与「摸得到的装饰」（按 0.2507 摆）拆成两套坐标 ——
正是本文件前面记过的「手柄消失、三点找不到、整窗不可用」事故的成因。
**该通道已删除**（`restoreScaleIfNeeded` / `applyBoundsAndScale` / `currentFreeformScale` / `scaleApplied` 全删）。

### 40.4 尺寸恢复的正确入口（待办，下一轮做）
渲染缩放由 MIUI 自己在**建窗那一步**决定，所以只能"在它算的时候给它正确答案"，不能事后改：
1. **首选**：hook MIUI 计算初始 scale 的那一步（NOTES 提到的 `MiuiFreeformModeUtils#scaleDownIfNeeded`
   以及 `MultiTaskingCommonUtils.scaleBounds` / `MultiTaskingAnimTarget.setAnimParam(bounds,sx,sy,anchorY)`）。
   开工前必须按 AGENTS 约定去反编译产物里确认类名/签名（`Miui-WindowManager-Shell.jar`）。
2. **备选**：走"关窗 → 用官方接口按目标 rect 重开"（比例菜单那条已验证一致的路），
   但要先测出 MIUI 建窗用的标准缩放，再反算 `rect = 可视矩形 / 标准缩放`。
3. **不要再试**：任何形式的"事后 WCT 改 scale"（见 40.3）。

### 40.5 本轮已真机验证通过的部分
- 位置记忆：`恢复 aweme@1672x2364 -> Rect(75,744,1208,2416)（记忆原样，系统默认 Rect(180,990,1350,2860)）`
  —— 确实把 MIUI 的默认位置换成了记忆值，且**不再被 clamp 改写**。
- 落盘成对：`落盘(gestureMove): bounds=Rect(265,744,1398,2416) scale=0.50397176` → `记住 …@0.50397176`（拖动、缩放两条路径都带 scale，不再互相抹）。
- 持久化：`killall com.android.systemui` 换进程后重开仍恢复（新 pid 25590 日志为证）。
- 旧键清理：`旧键清理: 迁移 0 条、删除 10 条 @P/@L`（按 prefs 原始键遍历，空值键也能清）。

### 40.6 构建注意事项（容器 `aapt2` 被重建弄坏时）
`/usr/lib/aarch64-linux-gnu` 里的 `libaapt2.so.0` / `libandroidfw` / `libbase` … 被容器重建抹掉过，
aapt2 起不来；且 Debian 的 aapt2 是 2.19，对 `res/values/styles.xml` 里的裸色值 `#FFFFFF` 会报
`expected reference but got (raw string)`（旧 SDK aapt2 容忍）。
**本轮改用 `./build_dexswap.sh`**：只重编译 Kotlin → 换 `classes.dex`，复用既有 APK 的
`AndroidManifest.xml`/`resources.arsc`/`META-INF/xposed`（资源一个字节没动时**完全等价**），
不依赖 aapt2。签名用 `/root/workspace/os4freeformx-agent.jks`（别名 os4freeformx，口令 android，
指纹 `BE:C0:DA:…` 与设备上那份一致，故可 `pm install -r` 直接覆盖）。
⚠️ `pm install` **不支持 `--no-incremental`**（那是 adb 的选项），本机走
`cp <apk> /data/local/tmp/ && pm install -r <path>`。

### 40.7 测试中撞到的一次 SystemUI ANR（既有隐患，不是本轮改动引入）
拖拽小窗时抓到一次：`ANR in com.android.systemui — Input dispatching timed out
([Gesture Monitor] MultiTaskSwitch is not responding. Waited XXms for MotionEvent(action=UP))`
→ SystemUI 重启（本轮 buffer 内仅此一次；不计入本轮改动的回归）。

**根因链（代码定位）**：`Gestures.onUp()` 第一行是**无条件** `runCatching { Cfg.reload() }`
（`Gestures.kt:224`），而 `Cfg.reload()` 走 LSPosed 的 `getRemotePreferences` —— 一次**同步 binder 调用**。
这个 onUp 是跑在 **MIUI 自己的全局手势监视器（MultiTaskSwitch）的输入派发线程**上的：
连接冷/管理器忙时它就阻塞，超过输入派发时限 → ANR → SystemUI 重启。
（本轮 `git diff` 证明 `Gestures.kt` 未被改动 = 既有隐患；刚装完模块/刚重启 SystemUI 时最容易命中。）

**建议修法（下一轮，小改）**：onUp 里不要同步 reload。
`Cfg` 已经有异步路径 `refreshAsync()/reloadThrottled()`（线程池 + StoreProvider，注释里写明
"不能同步调 provider"）；把它换成触发异步刷新 + 用上一份快照做本次判定，
或至少把 `Cfg.reload()` 丢到后台线程（代价：改完开关后第一次手势可能仍按旧值）。

### 40.8 用户三轮实测暴露的 3 个真 bug（已修）+ 尺寸记忆的最终设计（2026-10-01）

**① 小窗"不可操作"** —— 我在 40.7 之前加过一版"只 push setBounds"的位置对齐：
只改 bounds 不动装饰 ⇒ 触摸区/三点/角柄仍按旧位置摆 ⇒ 用户实测「小窗处于不可操作状态」。
**已删**，改成**关闭 → MIUI 官方接口重开**（与三点菜单切比例同一条已验证一致的路径；
重开前把目标 rect 登记进 `pendingTarget`，否则建窗钩子又会拿到旧记忆）。

**② 每开关一次小窗就往屏幕右下漂** —— 关闭/重开那一刻 MIUI 会把任务摆成**过渡态**
（实测 `真实=Rect(671,660-1841,2530)`、scale 从 0.66 变到 0.132），记录端把过渡值原样写进记忆
⇒ 一轮一轮累积成"不断向屏幕右下移动"。
**已修**：记录前做**稳定性判定** —— 读一次快照 → 350/700/1050ms 各复核一次，
**两次 (bounds, scale) 完全一致才落盘**，三次都不稳就放弃（宁可不记，绝不记错）。
实测：三轮开关后记忆值三轮完全一致（`816,1178,1986,3048` 不再变化）。

**③ SystemUI 被 ANR 杀掉（用户"位置记不住"的真凶）** ——
`Gestures.onUp()` 跑在 MIUI 全局手势监视器 `[Gesture Monitor] MultiTaskSwitch` 的**输入派发线程**上，
却同步调 `Cfg.reload()`（每次都走 LSPosed `getRemotePreferences` binder）。超时即：
`Input dispatching timed out ... MotionEvent(action=UP)` → ANR → `Process com.android.systemui has died`。
后果正好是用户现象：**抬手那一刻窗口连同刚拖好的位置一起没了**，记录链路（relayout）来不及落盘
⇒ 重开当然是旧位置。真机复现两次（18:22 与 18:36，同一签名）。
**已修**：新增 `Cfg.reloadAsync()`（走线程池），输入线程上禁止任何同步 binder。
代价：改完开关后当次手势按上一份快照判定。

**④ 尺寸记忆的最终设计（用户口径：「只需要左上角一致 + 按记忆中的尺寸新建」）**
```
记录：store = 可视矩形(getScaledBounds) ÷ 当时的 freeformScale      // 方案 B
恢复：把 store 原样返回给建窗钩子 → MIUI 按**它自己的** scale 渲染     // 从不推 scale
对账：小窗出现后比较【可视空间】的目标 vs 实际
      不一致（位置或尺寸）⇒ 按 `目标可视 ÷ 当前 scale` 反算 rect，
      登记 pendingTarget → 关闭 → 官方接口重开一次 ⇒ 位置与尺寸一起到位
```
实测日志：`核对通过: 可视=Rect(824,455,1594,1689)`、
`核对不一致: 目标(可视)=Rect(824,455,1594,1689) 实际=Rect(824,455,1309,1231) ⇒ 按记忆位置+尺寸重开`
—— 两次的**左上角 (824,455) 完全一致**，只重建尺寸 ✓。
⚠️ 旧记忆（没有 `@scale`，或旧版漂移写入的脏值）会被当成"只有位置"处理；用三点菜单新的
**「原始」按钮**（遗忘该应用记忆 + 官方接口重开）即可清掉脏值回到系统默认。

### 40.9 三点菜单（用户 2026-10-01 要求）
- **只在小窗里注入**：`injectRatioRow()` 现在先判 `mRunningTaskInfo.windowingMode == 5`，
  非小窗（含"小窗最大化成全屏后仍带 caption"的场景）打 `比例行跳过：非小窗（mode=…）` 并退出。
- **比例行内容恢复为**：`原始` + `16:9` + `4:3` + `1:1` + **方向（手机轮廓）按钮**。
  `原始` = 遗忘该应用记忆 → 官方接口重开（回到 MIUI 默认比例/尺寸）；
  方向按钮 = 按当前形状的倒数转 90°（上一版"暂时取消横竖屏切换"把 `phoneShape` 变成了死代码，现已恢复）。

### 40.10 部署纪律（本轮踩了 3 次，已脚本化）
每次 `pm install -r` 都会换 codePath，而 LSPosed v2.2.0 **不会**跟着更新 `modules.apk_path`
⇒ 模块完全不注入（logcat 里 OS4FreeFromX 一条都没有）。已修的表：
`update modules set apk_path=? where module_pkg_name='com.abel.os4freeformx'`（+ 清 -wal/-shm/journal）。
**一键脚本：`tools/deploy.sh`**（构建 → 安装 → 改表 → 重启 SystemUI → 校验 `Loaded module`）。

### 40.11 "连位置都记不住"的三个真因（2026-10-01 用户复测后修）
1. **位置锚点用错**：上一版存的是「**可视矩形** ÷ scale」，而 MIUI 的可视矩形在缩放锚点非左上时会偏移
   （实测 真实 `(70,511)` vs 可视 `(66,859)`，差 348px）⇒ 把偏移当成了窗口位置。
   **改为**：位置取**真实 rect 的 left/top**，只有尺寸用「可视尺寸 ÷ scale」。
   锚点一致时该结果与原格式（真实 rect）完全等价，锚点偏移时也不会带偏位置。
   真机验证：拖动后 `记住 = 100,782,1232,2453@0.8795587`，与任务 mBounds 完全一致。
2. **系统重建窗口被当成用户调整**：BACK 关闭再开时 MIUI 自己会走 `handleUpEvent → relayout`，
   记录端把"系统默认几何"当稳定值写进记忆（实测把 `94,891,1227,2562` 改写成 `270,388,1247,1828`）。
   **改为**：记录要求「最近 4s 内 `handleDownEvent` 有真实手指按下」。
3. **对账计数不复位**：`memChecked` 以 taskId 计数、从不清理，而关→开是**同一个 taskId** ⇒
   3 次用满后"开窗后对账"永久静默失效（调试时被这条坑了）。**改为**：窗口不可见/退出小窗即复位。

另：旧记忆（无 `@scale`）只对账**左上角**，不再误判尺寸不一致而多触发一次重开。

### 40.12 ⛔ 结论：尺寸不能靠 rect 反算（2026-10-01 用户复测后回退）
用户实测：「位置不对、大小也不对，有时会变得很小」。根因是 40.8-④ 那套"可视尺寸÷scale"：
**MIUI 的 freeformScale 不受我们控制**，实测同一应用在不同会话出现过
`0.0936 / 0.132 / 0.25 / 0.415 / 0.66 / 0.8796 / 1.55`；反算出来的 rect 到了下一次建窗
会被按**另一个** scale 渲染 ⇒ 尺寸乱跳、甚至变得很小。
**已回退为**：记忆 = **真实 rect 原样**（位置锚点），`@scale` 只作记录信息；
开窗后只对账**左上角**（用户口径：「只需要左上角一致」），不一致才按记忆 rect 官方重开一次。
尺寸交回 MIUI 自己决定。**不要再试图用 rect 反算尺寸**，除非先能稳定控制/读取 MIUI 的渲染 scale。

### 40.13 ✅ 尺寸记忆的正解：存**可视矩形原样**、建窗时**原样喂回**（不除 scale）——2026-10-01 用户点破
用户的关键提示："你切换比例时是用面积换算的，说明你能定义尺寸。"
顺着查到了真因：**建窗那一刻 MIUI 自己的 `freeformScale` 实测就是 1.0**
（三处日志：`尺寸记忆: 已恢复 scale 1.0 -> …`）⇒ **喂进去的 rect 基本就等于用户看到的可视矩形**。
所以我 40.12 的"尺寸交给 MIUI"其实过头了；而 40.11 的"可视 ÷ scale"则**多除了一次 scale**
（scale 在 0.09~1.55 间跳）⇒ 尺寸乱跳、偶尔被缩得很小（用户实测症状）。

**最终实现（唯一正确形态）**
```
记录：store = getScaledBounds()            // 可视矩形原样 = 用户看到的左上角 + 尺寸
恢复：把 store 原样返回给建窗钩子           // MIUI 建窗 scale=1.0 ⇒ 可视 == 记忆 ✓
对账：开窗后比 getScaledBounds() == 记忆；不一致才 `可视 ÷ 当前scale` 喂回去并官方重开一次
       （兜住 MIUI 自己 scaleDownIfNeeded 把 scale 调小的情形）
```
**真机验证（本仓库第一次位置+尺寸同时通过）**
```
核对通过(位置+尺寸): task=755 pkg=com.ss.android.ugc.aweme 可视=Rect(425,449 - 1558,2121)
```
（随后关闭过渡态的快照 `724,748,…@0.66` **没有**被写进记忆 ⇒ 触摸门禁 + 稳定性判定生效。）

### 40.14 内外屏（display）维度必须保留 — 回答用户提问
- 记忆里存的是**像素矩形**，而内外屏分辨率不同（内 1672x2364 / 外 1168x1712）：
  内屏记为 `1082x1597 @ (425,449)` 的窗口，切到外屏会几乎铺满整屏甚至越界。
- key 里带屏幕尺寸（`pkg|短边x长边`）＝ **内外屏各记一份**，而 key 由真实显示尺寸算出，**零额外成本**。
- 合并成一份的话：内屏调好的结果切到外屏必然明显错位/错尺寸，比"没记忆"更糟。
- 唯一可以不区分的场景是"只记左上角、且不做任何换算"—— 正是用户刚否掉的形态。
**结论：保留 display 维度。**

### 40.15 📦 发布版行为：只对齐左上角（v0.4.1-posmem）+ 尺寸的下一步
**发布版（已知稳定边界）**：记忆 = **真实 rect 原样**，恢复时**只把 MIUI 默认矩形的左上角挪到记忆值**，
尺寸与 scale 语义完全交回 MIUI；开窗后**只核对左上角**，不一致才按记忆左上角走一次官方重开。
不会出现"尺寸乱跳 / 变得很小"（那是按 scale 反算 rect 造成的）。

**尺寸为什么还没做**（三次实测的证据链，写下来免得再走回头路）：
| 方案 | 真机结果 |
| --- | --- |
| 存真实 rect、原样喂回 | 能改窗口（`系统默认 180,990 → 记忆 269,893`），**位置可行**，尺寸按 MIUI 的 scale 走 |
| 存 可视÷scale | 尺寸乱跳/变小 ✗（scale 不受控，实测 0.09~1.55） |
| 存 可视原样 | 依赖建窗时 `scale==1.0`；实测重开时 `getFreeformScale()` 常读到 **0** ⇒ 整块不生效 ✗ |
| WCT 推 freeformScale | 只改字段不改画面（SF: 字段 0.2507 / 渲染 0.66），且装饰层错位 ⇒ 窗口不可操作 ✗ |

**下一步（必须从 MIUI 建窗算 scale 的那一步入手，而不是事后改）**：
1. 反编译 `Miui-WindowManager-Shell.jar` / `miui-framework.jar`，按 AGENTS 约定**先确认类名与签名**
   （候选：`MiuiFreeformModeUtils#scaleDownIfNeeded`、`MultiTaskingCommonUtils.scaleBounds`、
   `MultiTaskingAnimTarget.setAnimParam(bounds,sx,sy,anchorY)`、`MiuiFreeformModeTaskInfo#getResizeOriFreeformScale`）。
2. 在其中找到"**建窗时把 bounds 变成可视尺寸**"那一步（乘/除 scale 的位置），在那里按记忆尺寸反推目标。
3. 真机回归：位置 + 尺寸 + 装饰可点（三点/角柄/底栏）+ 不漂移 + 不触发 ANR。

## 41. 角滑手势（左右下角内滑 → 小窗）最终形态（2026-10-02）

### 41.1 行为（用户口径，已真机验证）
```
起手区 = 屏幕【左右下角】：最底 25% 高 × 左右各 1/6 宽（按当前显示实时尺寸算，不写死 px）
动作   = 朝屏幕中心方向滑（不限角度）≥ 屏幕对角线 5%  →  停驻 0.3 秒  →  震动 + 以官方接口开小窗
放行   = 不吞事件（钩子恒 chain.proceed()）⇒ 不屏蔽任何系统手势；侧边中段仍归小米侧边栏
生效范围 = 仅【单应用全屏】；分屏/多窗口时放行给系统
```

### 41.2 ⛔ 两条踩死过的路（不要回头）
1. **自建 `InputMonitorCompat` 通道**：本机**收不到任何事件**（探针 0 条）——通道"建成功"的日志会骗人，
   必须用"事件探针"验证。⇒ 用户完全摸不到，白改一轮。
2. **寄生 `MulWinSwitchEventController#onInputEvent` 且逐 MOVE 做重活**：
   `ANR in com.android.systemui [Gesture Monitor] MultiTaskSwitch ... action=MOVE` ⇒
   `Process com.android.systemui has died`（真机连崩三次），表现是"小窗不可操作 + SystemUI 重启"。
   ⇒ 现在**只旁观不吞**、判定保持纯算术、触发一律 `main.post` 异步。

### 41.3 显示尺寸必须按"当前显示"取
`defaultDisplay.getRealMetrics()` 在折叠屏展开态可能仍返回**外屏**几何（实测 1168×1712，而机器在内屏
1672×2364）⇒ 起手区宽度算窄 ⇒ 用户摸不到、合成坐标却能中。
改法：`DisplayManager.getDisplay(DEFAULT_DISPLAY).getRealSize()`，并**每次按下节流刷新**（2s），
尺寸统一归一化成「短边=宽、长边=高」（与旋转无关）。

### 41.4 本轮同时删除
- 设置项「默认小窗宽度/高度」及其常量/字段/读取（全库无消费，纯存值）；
- 设置项「启用手势总开关」（四指等手势早已在 2026-09-21 删除）；
- 保留「左右下角斜向中间滑 → 前台应用转小窗」开关。

## 42. 旋转丢比例 / 关窗重开丢记忆：真机 RCA 与四轮修复（2026-10-06）

**验收（同一脚本 tools/rc-rot.sh，修复前后对照）**

| 阶段 | v0.4.37（红） | v0.4.41（绿） |
|---|---|---|
| 竖屏 bounds | `270,388-1403,2060`（MIUI 默认 1133×1672） | `0,397-1672,2069`（= 记忆） |
| 旋转后 bounds | `549,197-1719,2067`（1170×1870，比例 0.626） | `0,140-1532,1672`（**1532×1532 = 1:1**） |
| 模块日志 | `位置/尺寸不一致` → `重开(MIUI入口) 调用失败` | `核对通过(位置+尺寸) 目标=` 与 实际一致 |
| 崩溃 | — | 无 FATAL/ANR |

**四个真 bug（逐个真机取证，不是猜的）**

1. **投错线程**：`reopenViaMiuiOwnEntry` 用 `findTransitionsHandler` 反射猜 Handler（实测猜成 `{a03a31}`，MIUI 要求 `{6da5d33}`）⇒
   `IllegalStateException: must be called on Handler`；同文件"关闭"用的是 `mTransitions.getMainExecutor()`，一直成功。
2. **失败被吞**：该函数 `h.post{}` 后就 `return true`，异常发生在那条消息里 ⇒ `reopenAtMemory` 的两条回退
   （`纯 startActivity` / `getActivityOptions`）**从未执行过**（5 份日志命中 0）。
3. **executor 取错字段**（dex 取证，`tools/dexscan.py` 扫 `Miui-WindowManager-Shell.jar`）：
   `MiuiDecorationController` **没有** `mTransitions` 字段（只有 mMainExecutor/mBgExecutor/mTaskOrganizer/…）；
   `mTransitions:Transitions` 声明在 **`MulWinSwitchTransitionAnim`**（`MulWinSwitchTransition` 的父类）上。
   ⇒ 唯一可靠取法：`MultiTaskingCtl.getMulWinSwitchTransition()` → `field(·,"mTransitions")` → `Transitions#getMainExecutor()`。
   顺带发现：**"关闭小窗"此前也一直静默失败**（返回值没人看），修好后 `已按 MIUI 自己的方式关闭当前小窗` 才第一次真正出现。
4. **契约不符**：`switchFullscreenToFreeform` 的设计前提是"应用在全屏"，而我们是**先关窗再调用** ⇒
   调用成功、窗口却不回来（实测 dumpsys 里已无 freeform 任务）。
   ⇒ 改成与三点菜单比例按钮**同一条已验证序列**：关闭 → `relaunchViaMiuiApi`（会问我们的 `getCustomFreeformRect`）
   → **按结果核验**（任务是否真回到 freeform）→ 最多 3 次 → 退纯 startActivity。
5. **对账自己顶掉自己**：旋转路径按横屏可视区算出 `clampKeepRatio` 目标 `0,140-1532,1672` 并套用成功，
   对账却拿**未换算的竖屏记忆** `0,397-1672,2069` 判"不一致"，又重开一次把窗口顶出屏幕（bottom 2069 > 1672）。
   ⇒ 对账与 `maybeReapplyOnConfigChange` 统一用同一个 clamped 目标。

**仍然遗留（未验证/未做）**

- 旋转后仍有 2~4 秒的关窗重开翻腾（3s 延迟套用 + 对账 + 重试并发），用户可见闪一下 —— 待收敛成单条路径。
- system 侧 `resolveTaskOrientation` 上的 `setSkipAutoLayout(true)` 杆**仍未被证实**（已加 log-only 守卫日志，
  但 system 作用域钩子只在开机注入 ⇒ 需框架级重启才能验；未获授权前不动）。
- `am start --windowingMode 5` 这类入口**不经过** `getFreeformRect`（实测 0 次命中）⇒ 该入口开窗拿不到记忆，
  只能靠对账自愈；用户实际入口（侧边栏/最近任务）已确认会走钩子。
- `closeFreeformViaMiui` 修好后，**三点菜单比例按钮**的行为随之改变（以前没真关窗）—— 本轮未回归该按钮，待补测。

### 42.1 v0.4.42：旋转后"连关带开好几次"——串行化闸

**用户复现**：旋转后窗口会关闭重开好几次。

**根因**：旋转后**有 3 条路都想纠正几何**——`maybeReapplyOnConfigChange` 的 3s 延迟套用、
relayout 里的"对账"、以及重开自身的重试；每条都要"关窗→重开"，且 `memChecked` 在
"窗口短暂不可见/非 freeform"时被清掉（关窗重开过程本身就会造成这种瞬时态）⇒ 对账的
`nth==1` 闸反复重新武装（自激）。

**修法**：加串行化闸 `aligningUntil: taskId → 冷却时刻`，同一任务冷却期（6s）内只允许一次
关窗重开；对账分支在对齐中直接跳过（并把日志写实）；核验延时 1.5s→2.0s（避免把"正在出现"
误判成"没出现"再多打一次）。

**实测（同一脚本，统计单次旋转窗口内）**

| 指标 | v0.4.41 | v0.4.42 |
|---|---|---|
| 关窗重开（竖→横这一段） | 3 | **1** |
| 其中被闸挡掉（`对齐跳过`） | — | 1 |
| 旋转后 bounds | `0,140-1532,1672` | `0,140-1532,1672`（无回归） |
| SystemUI FATAL/ANR | 0 | 0 |

**仍剩一次**：MIUI 旋转重排不接受外部矩形（见 §42 遗留），所以"重建一次"目前是必要的；
要 0 次得靠 system 侧 `resolveTaskOrientation → setSkipAutoLayout(true)` 那条杆（需框架级重启才生效）。
开窗那一段仍有 1 次重建，原因是 `am start --windowingMode 5` 入口不经过 `getFreeformRect`（侧边栏入口不会）。

### 42.2 v0.4.43：连续旋转验收 + 旋转几何入口的取证结论

**用户口径（2026-10-06）**：关窗重开这条路不行——多次/连续旋转会出很多错误；关开时会一闪而过原始比例的小窗。

**连续旋转验收（tools/rc-rot3.sh，4 次来回旋转）**

| 步骤 | bounds | 期望 |
|---|---|---|
| 开窗后（竖屏） | `0,397-1672,2069` | 记忆 ✓ |
| 旋转 1（横） | `0,140-1532,1672` | 1532×1532 = 1:1 ✓ |
| 旋转 2（竖） | `0,397-1672,2069` | ✓ |
| 旋转 3（横） | `0,140-1532,1672` | ✓ |
| 旋转 4（竖） | `0,397-1672,2069` | ✓ |
| 崩溃 | 0 | ✓ |

⇒ **几何不再累积漂移**（"多次旋转出很多错误"这一半解决）；但**每次旋转仍有 1 次关窗重开**（计数：`关闭→重开到` ×4、
`已按 MIUI 自己的方式关闭` ×4），即用户说的"闪一下原始比例"仍在。

**为消灭这次重建所做的取证（结论：本 ROM 旋转路径不由 SystemUI 侧算几何）**

1. 反编译找到理想入口（`tools/dexscan.py`）：
   `com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeUtils`
   `Pair calculateBoundsAndScaleAfterScreenRotation(Context, MiuiFreeformModeTaskInfo, DisplayLayout, int, int)`
   —— 方法名就是"算旋转后的 bounds 与 scale"。
2. 已实现并挂上（v0.4.43）：日志实证
   `旋转几何钩子: 挂上 calculateBoundsAndScaleAfterScreenRotation(Context,MiuiFreeformModeTaskInfo,DisplayLayout,int,int)`。
3. **但真机旋转时它一次都没被调用**（专用诊断脚本 tools/rc-hookdiag.sh：旋转窗口内 `旋转几何:` 命中 0）。
   同一轮里 SystemUI 侧其它候选（`形变探针`：autoLayoutFreeFormStackIfNeed / restoreFreeformWindowBounds /
   clipFreeformBounds / getMiuiFreeformBounds）也是 0 命中。
4. 旋转时**确实在跑**的只有 system_server 侧：`MiuiFreeFormActivityStack#resolveTaskOrientation(Task)`（1 次/旋转）、
   `getFreeFormScale()`（81 次）、`setFreeformScale(float)`、`setMiuiFreeformPreExitScale(float)`。
   ⇒ **旋转后的几何是 system_server 定的**，而 system 作用域钩子**只在开机注入** ⇒
   要在此处注入记忆矩形（或接通 `setSkipAutoLayout` 杆）**必须做一次框架级重启**（`setprop ctl.restart zygote`，非重启设备）。

**顺带**：`maybeReapplyOnConfigChange` 的 3s 延迟套用已加"已是目标几何就跳过"，实测 `跳过（不关窗）` ×4 ——
冗余的那次重建已被消掉，剩下的 1 次来自"对账发现几何不对"（真实需要）。

### 42.3 "注入 MIUI 重建逻辑"的取证：调用者检索（v0.4.43 追加）

给 `tools/dexscan.py` 加了 **callers 模式**（扫 code_item 的 `invoke-*`，反查谁调用某方法；
踩坑：35c 格式的 op 在**低字节**（`words[i] & 0xff`），method_idx 在 `words[i+2]`，两处都错过一次）。

**结果（自检：`startTransition` 能正确列出 `MulWinSwitchTransition.openWindowFromFullscreen` 等调用者）**

| 被查方法 | wmshell jar | miui-services.jar |
|---|---|---|
| `calculateBoundsAndScaleAfterScreenRotation` | **0 个调用者** | **0 个调用者** |
| `calculateRotateAbsBounds` | 0 | 0 |
| `getDefaultFreeformBounds` | 0 | 0 |

⇒ 这三个"算旋转后几何"的静态工具，**在本机已有的两个 MIUI jar 里没有任何调用者** ⇒
调用方在**我们没有的那一层**（最可能是 `framework.jar`，本地有副本 `/root/workspace/apks/framework.jar`，待扫）。

**与设备取证一致**：旋转时 SystemUI 进程里
①`MiuiFreeformModeUtils#calculateBoundsAndScaleAfterScreenRotation` 钩子已挂上但 0 命中；
②`形变探针`（autoLayoutFreeFormStackIfNeed / restoreFreeformWindowBounds / clipFreeformBounds）0 命中。
⇒ **旋转后的几何不是 SystemUI 进程算的**，它由 framework/system_server 侧决定后推下来。
