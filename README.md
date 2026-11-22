# OS4FreeFromX

HyperOS 4（Android 17 / API 37）**小窗（freeform）与分屏体验增强** LSPosed 模块。

基于上游 [HyperFreeformX](https://github.com/Xposed-Modules-Repo/cn.liuyi.hyper_freeform_x) 的功能定位做独立实现
（上游只发布 APK、无源码），通过逆向 HyperOS 4 的 `miui-framework.jar` / `Miui-WindowManager-Shell.jar`
定位 hook 点，并逐条在真机上取证。

- 包名 / 模块 id：`com.abel.os4freeformx` / `os4_freeformx`
- 框架：LibXposed API **102**（`minApiVersion=100`、`targetApiVersion=102`、`staticScope=true`）
- 作用域：**系统框架**（`system`，system_server，负责小窗 bounds 计算）+ **系统界面**（`com.android.systemui`，wm shell 装饰与分屏）
- 验证机型：Xiaomi 18 Fold（`lhasa`，折叠屏），HyperOS `OS4.0.15.0.XPNCNXM`，Android 17

> 实现细节、失败尝试、真机日志证据全部记录在 **[`NOTES-resize-handle.md`](NOTES-resize-handle.md)**；
> 测试期用到的设备操作（含锁屏解锁）在 [`NOTES-device-ops.md`](NOTES-device-ops.md)。
>
> **多分屏（3/4 分屏）开发交接** → **[`HANDOFF.md`](HANDOFF.md)**（当前进度、已证实结论、
> 待验证的 Dock 路径、关键 API 签名、设备约束）。
> 逆向产物与测试日志在 [`docs/`](docs/)（`recon/` 反汇编与链路、`logs/` 窗口 dump、
> `notes/` 逐日开发笔记）。

---

## 1. 功能

### 1.1 自由小窗

| 功能 | 说明 |
| --- | --- |
| **原生角柄** | 角柄交回系统：锁当前比例改尺寸、跟手、上下限正常，收尾回调自动喂给尺寸记忆 |
| **三点菜单比例行** | 在系统三点菜单里注入一行按钮：`18:9` `16:9` `4:3` `1:1`（复用系统资源，居中排列） |
| **切比例** | 写记忆 → 关掉当前小窗 → 用官方接口 `MiuiMultiWindowUtils.getActivityOptions` 重新拉起；1600ms 后核验是否回到 freeform，未回则**重试官方接口**（**不回退转场** —— 转场会把桌面翻上来） |
| **尺寸/位置记忆** | 按「应用 + 屏幕」记忆，重开、迷你态恢复都套用 |
| **折叠屏内外屏分开** | 记忆 key 带 display 维度（内屏 `1672x2364` / 外屏 `1168x1712` 等），内外屏各记一份 |
| **迷你/贴边恢复** | 贴边胶囊态点击恢复到记忆的位置尺寸（只替换恢复矩形、不动 scale，保证 mini→悬浮窗状态转换正常） |
| **沉浸栏** | 顶部三点栏 / 底部手势条**不绘制**（视图与触摸逻辑保留），且不再为其预留 window insets，内容区铺满 |
| **导航栏·隐藏** | 选中的应用**整个底栏消失**（内容铺到屏幕最底）；走 HyperOS 自带沉浸导航名单，**不 hook SystemUI**（见 §1.5） |
| **导航栏·取色** | 选中的应用导航栏**跟随界面主色**（采样） |

> 两个功能共用**一份名单**：每个应用只归属一个功能（先选功能、再勾应用，勾选即覆盖原归属）⇒ 结构上不存在"同一应用两边都选"。 |
| **清理系统干扰视觉** | 隐藏系统自带缩放描边；分屏场景下的镜像栏、应用图标遮罩、快照替身不显示 |
| **通知栏下滑开小窗**（v0.6.4） | 解除 `AppMiniWindowManagerImpl#canNotificationSlide` 的应用限制：**任意应用**的通知都能在通知栏里滑出小窗入口（移植自 HyperFreeformX 1.3.0，2026-10-22） |
| **魔法窗口**（v0.6.4，灰度） | 宽屏下把指定应用收成固定比例、两侧模糊；hook `MiuiEmbeddingWindowService#isEmbeddingEnabledForPackage`，切换**不杀进程**；默认关闭，设置页按应用开启 |


### 1.4 新手势（2026-09-21 新增）

| 手势 | 行为 | 生效范围 |
| --- | --- | --- |
| **角落斜滑** | 屏幕下部**左右角**起手 → 朝屏幕中心**明显斜向**（≈45°）滑 ≥200dp → 把当前前台应用转成小窗（官方 `MiuiMultiWindowUtils.getActivityOptions(ctx,pkg,true,x,y)`，小窗落在起手点附近） | **只在单应用全屏时生效**；分屏/多分屏/已有小窗时完全退让给系统 |
| **四指上滑** | 4 指同时按住一起上滑 ≥45dp → 把下一个可用应用加进分屏：走官方 `MiuiMultiWindowUtils` / `openWindowFromFullscreen` 入口起分屏 | **全屏单任务：✅ 已真机验证**；分屏内：⚠️ 当前只识别不动作（详见下） |

开关（模块设置里）：`gestures` 总开关、`corner_freeform`、`four_finger_split`。
**实现要点**：两条手势共用 MIUI 自己的全局输入源 ——
`MulWinSwitchEventController$EventReceiver#onInputEvent`（MIUI 用 `InputManager.monitorGestureInput`
建的全屏触摸监视器），挂它即可拿到全屏/桌面/小窗/分屏的触摸，无需额外权限。

> ⚠️ **分屏内加分屏（灰度）**：默认关闭（`four_finger_split_indoor=false`）。
> 打开后走「`transferSoScToMultipleSplit`（**stage 列表 + 索引[0,1]**，参数语义照抄官方）
> → `insertMultipleSplitByIntent`（由系统启动应用进新 stage）」。
> 踩坑记录：① 早期误传 **taskId 列表**导致 SoSc 状态机崩（黑屏/闪退）；
> ② 用 `insertMultipleSplitByTask` 塞已有任务时 stage 全为空（`sz=0`）→ 新那一侧黑屏。
> 详见 NOTES 第 20~23 节。

**四指手势的五处坑（真机逐条踩出来的，详见 NOTES 14/16/18 节）**：
① `onMove` 里"指针数<4 就撤销"→ 改为 <2 才放弃；② `ACTION_POINTER_UP` 抬一根就撤销 → 同上；
③ 阈值偏严（行程 70→45dp、窗口 1.2→2.6s）；④ 松手判定误用当前指针数 → 用峰值 `ffPeak`；
⑤ **判定时机**：MIUI 的多指监视通道**不投递 ACTION_UP**，所以四指必须在 **MOVE 上判定并触发**（`ffFired` 防重复）。

> ⚠️ **冲突教训（务必遵守）**：屏幕下部是 MIUI 自己的热区
>（上滑到左上角进双分屏 / 底部中间上滑进多分屏）。模块的手势
>①**必须先判开关再决定是否吞事件**（否则"关掉"也会拦死官方手势），
>②起手区必须给底部正中留让位区，③角滑只在单应用全屏生效。

### 1.5 导航栏：隐藏底栏 / 取色（一份名单，每个应用只归属一个功能）

在 HyperOS 自带的**沉浸导航名单**上做，**不 hook SystemUI**。

界面：设置页「导航栏」→ **导航栏调整** → 打开**全部应用列表**，每个应用左侧一个**下拉三选一**
（不启用 / 隐藏 / 取色），**已启用的应用置顶**；改动**不即时写盘**，点底部「应用」才一次性提交
（写名单 → `cmd … update` → **模块代劳 `am force-stop` 重启所有涉及的应用**，立即生效）。

| 功能 | 规则（`activityRules."*"`） | 效果 |
|---|---|---|
| **一、隐藏导航栏** | `{ "mode": 2 }` | **整个底栏消失**：`setNavigationBarForceImmersive()`，应用内容铺到屏幕最底、不再为导航栏预留区域。与小鹏当初那条厂商规则完全一致 |
| **二、导航栏取色** | `{ "mode": 1 }` | **不带 `color`** ⇒ 走采样 `getNavZoneDominantColor()`，跟随界面主色 |

⚠️ 别把「隐藏」写成 `{ "mode": 1, "color": 0 }`：那只是**把底色变透明**，底栏区域仍然占位，
透出来的是应用自己的窗口背景（深色应用上看起来还是一条黑带）。真机对照（Mobilism，底部 55px）：

```
无规则          → (0,0,0)      纯黑条
mode:1+color:0  → (239,239,239) 平坦背景色，方差 ~80（只是底色透明）
mode:2          → (238~242)     应用内容铺到底，方差 ~2900（内容纹理，整个底栏消失）✓
```

**落地链路（三条，全程不需要重启系统）**

1. 写 `/data/system/cloudFeature_navigation_bar_immersive_rules_list.json`（`dataVersion` 钉 `999999` 防云端覆盖）
2. **`cmd miui_navigation_bar_immersive update`** —— 让 system_server 重读名单。
   **缺这一步，文件写了也不生效**（取证：`MiuiNBIManagerService$Shell.onCommand` 的 `"update"` 分支 → `mService.applyNewNBIConfig()`）
3. **重启目标应用**（不是框架）—— NBI 服务自己的要求："restart the application to take effect"

**字段语义（反编译取证）**

- 文件解析 `com.android.nbi.MiuiParsingNBIRule`：支持 `mode` / `color` / `sf_sampling_mode` / `viewRules` / `appNavColorDisabled` / …
- 策略选择 `com.android.internal.policy.NavigationBarImmersiveController.handleActivityImmersive`：
  `mode` 是**策略选择器** —— `0`=DISABLED · `1`=用自定义颜色（唯一读 `color` 的分支）· `2`=强制沉浸布局
- `color` 是可空 Integer：`null`=不覆盖（走采样）· `0`=全透明

**踩坑记录**

- 曾用 SystemUI hook（`NavBarTransparent`）改 `BarBackgroundDrawable.mMode`：在**确实有黑条**的 Mobilism 上 A/B 实测**零效果**，已删除（连带 1s 轮询线程）。
- 「命令不存在」是误判：`cmd … activityRule` **不带参数**会落到 `handleDefaultCommands`；它要 `<包名> <规则JSON>` 两个参数。
- 名单文件是**持久载体**（只在框架启动时读），`reload-rule` 不被识别、重启 SystemUI 也刷不动 ⇒ 必须走第 2 步的 `update`。
- 写 `/data/system` 与执行 `cmd` 都需要 root：**首次保存会弹 KernelSU 授权，必须允许**，否则写入静默失败（界面上会 Toast 出失败原因）。
- NBI 服务要求"restart the application to take effect" ⇒ 点「应用」后由模块 `am force-stop` 所有涉及的应用（新选的 + 之前选过的）。
- 原 `K_RESIZE`（"允许拖动调整尺寸"）是**死开关**（值存了、读了，全库无人使用），已移除；
  新增 **`K_RATIO_MENU`（小窗比例调节）** 真正管住「三点菜单那排比例按钮 + 放大可调尺寸范围」——
  关掉后模块完全不碰 MIUI 的菜单与缩放上限，可避免"背景框大于应用可操作区、底部一片白"。

**验收**

```bash
cmd miui_navigation_bar_immersive list <包名>    # 隐藏：mode=2；取色：mode=1
logcat -s NavImmersive                          # 隐藏：start choosing immersive policy …, mode: 2
```

- 单测：`./check.sh`（Nbi 48 条断言：两种规则形状 / 按规则值合并 / JSON 生成 / 回读 / su 脚本含 `update`）
### 1.2 分屏（2 应用）—— 可调比例

默认 5:5，松手时的落位规则：

| 松手位置（按跨度百分比） | 落点 |
| --- | --- |
| 官方台阶位 ±4% 跨度（10% / 90%） | 磁吸到**官方台阶**（原样返回系统 `SnapTarget`，状态机才认） |
| **16:9 档 ±4% 跨度**（小边 940px） | 吸到 **16:9**（播放视频左右满铺、上下无黑边） |
| 中线 50% ±4% 跨度 | 精确 **5:5** |
| **中线 ↔ 16:9 之间**（v0.7.10 起） | **就近吸附到 5:5 或 16:9**，不再允许自由停留 |
| **16:9 ↔ 官方台阶之间** | **松手即落点（无极调节，保留）** |
| ≤1% / ≥99%（拉满） | 交还系统手势（全屏 / 退出分屏） |

- **比例记忆（v0.7.11 起，默认开启，设置里可关）**：按「两个应用包名排序组合 + 屏幕」记忆，
  重新组成这组分屏时恢复。**若打开的是一个新应用**（新组合没有自己的记录），
  则沿用**上一次拖到的比例**（全局最近值）—— 不会再回落到 5:5。
- `50%` 是系统默认摆位，**不写入记忆**（否则会把有意义的比例覆盖成 5:5）。
- 记忆触发点：`SoScStageCoordinator#updateSoScState()`（SoSc 状态唯一汇总点，真机日志实证必过）；
  恢复是异步投递 + 同组合 2 秒限频 + 差异 >2% 才动，不会跟正在拖的手势打架。
- 实现要点（踩坑见 NOTES）：
  - **不往吸附目标列表插目标** —— 构造函数按索引取 `first/middle/last`，插进去会打乱特殊目标；
  - 改为钩「就近吸附」`DividerSnapAlgorithm#snap`（通用版与折叠屏专用的 `sosc` 版都挂）；
  - 台阶与中线**返回系统自己的目标对象**（带系统 `snapPosition`），否则会绕过分屏状态机，
    导致「点击边缘切换比例」失效；
  - 自造档位（35%/65%、任意比例）需要在 `SoScUtilsImpl.findSnapTarget` 处放行。

> **单侧分屏：完全不介入（v0.7.5）+ 兜底（v0.7.4）**：竖屏下只把一个应用放进一侧、
> 另一侧是桌面时，SoSc 处于 **minimized 态（`SplitState=-1`）**——
> 官方状态机不认本模块注入的自造吸附目标（id=101/102/103），后果按版本不同：
> v0.7.3 拖一次就 `snapToTarget(null)` NPE 崩 SystemUI；v0.7.4 不崩但窗口弹回、
> 反复拖会把 Shell 窗口状态搅坏（分屏应用消失、状态栏消失、桌面锁死）。
> 因此 v0.7.5 起 `snap` 钩子检测到 `SoScStageCoordinator#mMinimized` 就**完全交还官方**；
> 另有 `SplitSnapGuard.kt` 兜底官方路径本身的 null 目标
> （改用 `mFixedSnapTarget` / `calculateNonDismissingSnapTarget(x)` 就地吸附）。
> 双应用 / 多分屏的磁吸与无极调节不受影响。

**启用系统自带的比例自定义**（部分档位依赖它）：

```bash
adb shell su -c 'setprop persist.sys.split.ratio.customize.Q18 true'
# 该属性是静态读取：改后重启 SystemUI 即可，不必重启设备
adb shell su -c 'killall com.android.systemui'
```

模块同时挂钩 `SoScUtilsImpl.supportedRatioCustomization()` 返回 `true` 作为兜底。

### 1.3 多分屏（3~6 应用）

- 每个应用顶部的三点与底部手势/导航条**不绘制**（与自由小窗同一套做法，保留触摸）；
- 多分屏 UI（`MultipleSplitUIController`）与 SoSc 装饰（`SoScSplitDecorManager`）隐藏；
- 在 `SurfaceControl.Transaction#show` 处按表面名拦截重显 —— 治「切换窗口又冒出来、摸一下再消失」。
  ⚠️ **绝不能拦 `SplitWindowManager`**：那是分隔条宿主，拦了会「拖不动、中间条消失」。

### 1.6 分屏四项增强（v0.7.0，移植自 HyperRefine）

| 功能 | 说明 | 实现 |
| --- | --- | --- |
| **解除分屏方向限制** | 竖屏也能左右分屏、横屏也能上下分屏（放开 `SplitUtilsImpl#allowLeftRightSplitInPortrait` 与 SoSc 的 `supportTopBottomSplitFeature/Device`） | `SplitDirection.kt` |
| **多分屏无级调节宽度** | **3 个及以上**分屏时拖动分隔线可停在任何位置，不再吸附系统档位（含 `MultipleSplitSnapAlgorithm` 的吸附收窄） | `MultiSplitRatio.kt` |
| **切换方向保持比例** | 左右 ↔ 上下切换时，每个应用保持原占比（拦 `switchSplitDirection` 前先记下各 stage 比例，切换后回写） | `SplitRatioKeep.kt` |
| **把手菜单同时显示双向分屏** | ··· 菜单同时给出左右与上下两对按钮（全屏 / 小窗 / 分屏态都生效），不再只按当前方向二选一 | `CaptionSplitBoth.kt` |

- 方向判定统一走 `SoScSupport.kt` 的兼容层（折叠屏 `DividerView` / `SplitLayout` 的字段与方法名在不同 ROM 上有差异，做了反射缓存）。
- 四项都是**热开关**：设置页关掉立刻退回系统原行为，不用重启 SystemUI。
- 2 分屏的吸附行为不受影响（那是 §1.2 里另一套 `DividerSnapAlgorithm` 的逻辑）。

> **系统本身只给「当前方向」那一对**：`MiuiCaptionContainerView#init` 里 `addSplitStateButton`
> 是 if/else 二选一，`isLeftRightSplit=false` 时只建「上/下」两个按钮
> （全屏态实测是 全屏 / 上 / 下 / 小窗 / 关闭 共 5 个）。本模块在它之后补上另一对，
> 变成 全屏 / 上 / 下 / 左 / 右 / 小窗 / 关闭 共 7 个 —— **全屏态同样需要补**，
> 不能按 `windowingMode` 跳过（v0.7.2 曾误按此跳过，导致全屏菜单缺左右图标，已撤回，
> 即为 v0.7.3）。

> **v0.7.0 起移除**「小窗无极调节（四角拖动自由宽高比）」：实测它要与系统的比例吸附、
> `MultiTaskingAnimTarget` 的两套参数（当前态 / 目标态）三方对齐才能自洽，内容层与背景容器
> 容易短暂不一致，稳定性不足以进正式版，故整体下线（`FreeRatio.kt` / `DeviceForm.kt` 已删除）。
> 小窗自身的角柄缩放仍是 MIUI 原生行为，不受影响。

### 1.7 轻折一下 → 分屏（v0.7.6 新增，折叠屏专属）

展开内屏使用手机时，**轻轻折一下再松开**，当前应用就进分屏（另一侧由系统补位）；
已经在多分屏（3 格及以上）时再折一下 → **追加一格**。实现见 `FoldSplitTrigger.kt`。

| 设置项 | 说明 |
| --- | --- |
| **轻折一下加分屏** | 总开关，**自 v0.7.9 起默认开**（已写入用户实机配置，新装即生效）；折叠屏轻折一下进分屏已成主力习惯 |
| **轻折展开方向** | 当前应用留在**左侧**（新格开在右）还是**右侧**（新格开在左），对应 `splitPrimaryTask(pos, taskId)` 的 `pos = 0 / 1` |
| **轻折触发灵敏度** | 折下去多少度才判定为"开始折"：**10°~90° 滑动条，默认 15°**。数值越小越灵敏（易误触），越大越迟钝（需折更深） | 

**判定规格**（角度越大越展开；本机实测 全展 ≈ 179° / 半开 89~92° / 合上 2~3°）：

| 代号 | 值 | 含义 | 判据 |
| --- | --- | --- | --- |
| D1 | 10°~90°（默认 15°） | 进入：比展开基线低这么多 ⇒ 认为开始折了（**设置页滑动条可调**） | `A0 - angle > D1` |
| D2 | 6° | 回弹：回到基线附近 ⇒ 认为折完松手 | `angle ≥ A0 - D2` |
| D3 | 90° | 合上保护：折这么深 ⇒ 判为"要合上手机"，不触发 | `A0 - angle ≥ D3` |
| 窗口 | 1000 ms | 从**开始折**起算的绝对期限，超时不触发 | `now - foldStart > WIN` |

回弹有两条触发路径：`angle ≥ A0 - D2`（完全弹回），或 `angle - 谷底 ≥ D1`（松手必回弹，只判回升）。
合上过程单调下降，不会误入后者。

**三条不能动的约束**（来源项目踩过坑，改动前先读）：

1. **超时必须排在两条回弹之前**。`hinge_angle` 是 **on-change 传感器**，手机静置时零回调。
   「折一下停几秒再展开」这条路上只有"展开那一下"一个样本，它天然满足「自谷底回升 ≥ D1」；
   超时若排在最后就永远够不着 ⇒ 「过了动作时限还是进分屏」。
2. **判为"合上"必须清空展开基线窗口**。否则合上之前那个 179° 还留在 8 秒滑动窗口里，
   重新展开时 `A0(179) - 158 = 21 > D1` ⇒ **打开手机这个动作本身被当成"轻折又松手"**。
3. **计时起点是"开始往下折"那一刻**（自基线下降 5° 即为开局），不是"折深越过 D1"那一刻 ——
   用户口径的「1 秒内」是**一个完整动作**的时长，含"折下去"那段。

**触发后怎么落地：**

- 全屏（stage < 2）⇒ `SoScSplitScreenController#splitPrimaryTask(pos, taskId)` —— 与系统三指滑同一条链，
  它第一行就是 `if (isSplitScreenVisible()) return false;`，天然只做 0 → 2；
- 已在多分屏（≥2）⇒ `MultipleSplitRootTaskOrganizer#dockMultipleSplitTasks(Bundle)`，
  Bundle 装分屏 **root** 的 `RunningTaskInfo`（键 `multiple_split_dock_task`）—— 与「拖到多分屏图标」同一条链；
- 两次调用都投递到 **WMShell 主线程**；`Handler.post` 返回 false 时当场释放并发闸，避免永久卡死。

> **单侧（minimized）分屏绝不触发**：`SplitState=-1` 是雷区（见 §4 与 v0.7.5 记录），
> 动手前先问 `SoScSupport.inMinimizedMode`，命中就放手。

> 实现思路来自 HyperPlus v0.9.0-alpha（`FoldJudge.kt` / `SplitTrigger.kt`，AGPL-3.0），
> 按其公开的行为规格重写；绑定的类名均在机上 dex 里逐个 grep 验证过。

---

## 2. 安装

1. 安装 `dist/OS4FreeFromX-v0.7.9.apk`；
2. 在 LSPosed 里启用模块，并勾选作用域：**系统框架** + **系统界面**；
3. 按需执行 §1.2 的 `setprop`；
4. 重启 SystemUI 载入新 dex：`adb shell su -c 'killall com.android.systemui'`；
   `system`（system_server）侧钩子需要**重启设备 / 软重启**才生效。

---

## 3. 构建与自检

```bash
./build.sh     # 产出 dist/OS4FreeFromX-v0.1.0.apk
./check.sh     # 主机侧自检：比例与尺寸算法断言（不需要设备）
./verify.sh    # 真机取证：hook 安装情况 / 记忆 / 当前 bounds
```

流水线：`aapt2(arm64) → kotlinc → d8 → zip(classes.dex + classes2.dex + META-INF/xposed) → zipalign → apksigner`，
工具在 `tools/`（Debian arm64 的 aapt2/zipalign + Kotlin 2.1.0 + 预编译 `kotlin-stdlib.dex`）。
`Ratio.kt` 为纯算术，`test/RatioCheck.kt` 是它的断言测试。

> 重装 APK 后必须重启 SystemUI 才会载入新 dex；签名密钥在 `keystore/`（不在构建目录内，
> 否则每轮构建换密钥会导致 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，设备上跑的可能不是最新包）。

---

## 4. 已知限制（均已真机取证，别再重复尝试）

1. **分屏上下留白（状态栏 140px / 导航栏 55px）**
   分屏 stage 让位来自**应用根据自己的 `WindowInsets` 决定**，而该 insets 是**应用进程**里
   调 `InsetsState.calculateInsets` 算出来的，本模块作用域（`system` + `com.android.systemui`）**够不到客户端**。
   已排除：`MultipleSplitLayout.mDotInsetsHeight/mBottomInsetsHeight`、`getDisplayStableInsets`
   （multiple / sosc 两版）、`mRootBounds`、`mDividerInsets`、`DisplayLayout.stableInsets()`、
   `WindowState`（该类**没有**返回 `Insets` 的方法）、`InsetsStateController` 各方法。
2. **换掉一侧应用后保持原比例**：系统行为；曾实现「按应用记份额」，会与默认 50% 摆位冲突，**已删除**。
3. **拖动分屏把手时两侧内容不逐帧重排**：系统有意冻结（官方遮罩正是为此）；
   强行逐帧重排（`updateWindowBounds` / `setDividerPosition`）会导致**分隔条卡死**，已放弃。

---

## 5. 目录

```
app/src/main/kotlin/com/abel/os4freeformx/
  MainHook.kt       入口（LibXposed API 102），按进程分发
  Hooks.kt          全部 hook：沉浸栏 / 小窗比例 / 尺寸位置记忆 / 分屏比例与记忆 / 多分屏栏
  Ratio.kt          比例与尺寸纯算术
  Bounds.kt         记忆存取（应用|屏幕 键、clamp、scale 一起存）
  Cfg.kt            配置快照（节流异步经 Provider 拉取）
  StoreProvider.kt  模块 App 侧存储与配置 Provider
  AppPrefs.kt / AppCtx.kt / Constants.kt / Logx.kt / SettingsActivity.kt
test/RatioCheck.kt  算法断言
recon/              jadx 反编译产物（Miui-WindowManager-Shell 等）
tools/              aapt2 / zipalign / kotlinc / jadx
build.sh check.sh verify.sh env.sh
NOTES-resize-handle.md  实现细节与全部踩坑（含失败尝试与证据）
NOTES-device-ops.md     测试用设备操作
```

---

## 6. 环境纪律（两次事故换来的）

1. **`system` 作用域的模块在开机阶段只能装 hook**，绝不碰 `Context` / `ContentResolver` / prefs。
   曾在 `onSystemServerStarting` 里起"预热线程"读 Provider，提前触发 `SystemServiceRegistry`
   创建 `MediaRouter`（此时 `DisplayManagerGlobal` 尚未就绪）→ `MediaProjectionManagerService`
   构造 NPE → system_server 连崩 → LSPosed 进安全模式。
2. **设备重启默认禁止**（需要重启才生效的方案一律先评估）；`adb shell` 侧只用 `su -c`。
3. 修改 `Hooks.kt` 后：`./check.sh`（主机侧）→ `./build.sh` → `adb install -r` → 重启 SystemUI → `./verify.sh` 取证。

---

## 7. 免责声明

仅用于个人设备的功能增强。修改系统行为存在风险（可能导致 SystemUI 崩溃或需要恢复出厂设置），
请自行评估后再使用。核心行为可通过模块设置里的 `immersive` / `rememberBounds` 开关随时关闭。
