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
| **清理系统干扰视觉** | 隐藏系统自带缩放描边；分屏场景下的镜像栏、应用图标遮罩、快照替身不显示 |


### 1.4 新手势（2026-09-21 新增）

| 手势 | 行为 | 生效范围 |
| --- | --- | --- |
| **角落斜滑** | 屏幕下部**左右角**起手 → 朝屏幕中心**明显斜向**（≈45°）滑 ≥200dp → 把当前前台应用转成小窗（官方 `MiuiMultiWindowUtils.getActivityOptions(ctx,pkg,true,x,y)`，小窗落在起手点附近） | **只在单应用全屏时生效**；分屏/多分屏/已有小窗时完全退让给系统 |
| **四指上滑** | 4 指同时按住一起上滑 ≥45dp → 把下一个可用应用加进分屏：走官方 `MiuiMultiWindowUtils` / `openWindowFromFullscreen` 入口起分屏 | **全屏单任务：✅ 已真机验证**；分屏内：⚠️ 当前只识别不动作（详见下） |

开关（模块设置里）：`gestures` 总开关、`corner_freeform`、`four_finger_split`。
**实现要点**：两条手势共用 MIUI 自己的全局输入源 ——
`MulWinSwitchEventController$EventReceiver#onInputEvent`（MIUI 用 `InputManager.monitorGestureInput`
建的全屏触摸监视器），挂它即可拿到全屏/桌面/小窗/分屏的触摸，无需额外权限。

> ⚠️ **分屏内加分屏暂未开放**：真机实测「双分屏下四指上滑」会黑屏/卡顿/闪退 ——
> SoSc 双分屏（一对 stage）与多分屏（多个 stage）结构不同，需要一个**专用转场**才能衔接，
> 直接 `insertMultipleSplitByTask` 会与 SoSc 状态机冲突。现已**短路**（只识别、打日志、不做动作），
> 不再闪退。接线方案见 NOTES 第 20 节（先抓真实"拖第三个应用进左上角"的日志，照抄参数）。

**四指手势的五处坑（真机逐条踩出来的，详见 NOTES 14/16/18 节）**：
① `onMove` 里"指针数<4 就撤销"→ 改为 <2 才放弃；② `ACTION_POINTER_UP` 抬一根就撤销 → 同上；
③ 阈值偏严（行程 70→45dp、窗口 1.2→2.6s）；④ 松手判定误用当前指针数 → 用峰值 `ffPeak`；
⑤ **判定时机**：MIUI 的多指监视通道**不投递 ACTION_UP**，所以四指必须在 **MOVE 上判定并触发**（`ffFired` 防重复）。

> ⚠️ **冲突教训（务必遵守）**：屏幕下部是 MIUI 自己的热区
>（上滑到左上角进双分屏 / 底部中间上滑进多分屏）。模块的手势
>①**必须先判开关再决定是否吞事件**（否则"关掉"也会拦死官方手势），
>②起手区必须给底部正中留让位区，③角滑只在单应用全屏生效。

### 1.2 分屏（2 应用）—— 可调比例

默认 5:5，松手时的落位规则：

| 松手位置（按跨度百分比） | 落点 |
| --- | --- |
| **1% ~ 25%**（左右分屏同理） | 磁吸到 **10% 台阶** |
| 75% ~ 99% | 磁吸到 **90% 台阶** |
| 中线 ±5% 跨度 | 精确 **5:5** |
| 35% / 65% ±2% | 精确 **3.5:6.5 / 6.5:3.5** |
| 其余 | **松手即落点（任意比例）** |
| ≤1% / ≥99%（拉满） | 交还系统手势（全屏 / 退出分屏） |

- **比例记忆**：按「两个应用包名排序组合 + 屏幕」记忆；重新组成这组分屏时恢复。
- `50%` 是系统默认摆位，**不写入记忆**（否则会把有意义的比例覆盖成 5:5）。
- 实现要点（踩坑见 NOTES）：
  - **不往吸附目标列表插目标** —— 构造函数按索引取 `first/middle/last`，插进去会打乱特殊目标；
  - 改为钩「就近吸附」`DividerSnapAlgorithm#snap`（通用版与折叠屏专用的 `sosc` 版都挂）；
  - 台阶与中线**返回系统自己的目标对象**（带系统 `snapPosition`），否则会绕过分屏状态机，
    导致「点击边缘切换比例」失效；
  - 自造档位（35%/65%、任意比例）需要在 `SoScUtilsImpl.findSnapTarget` 处放行。

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

---

## 2. 安装

1. 安装 `dist/OS4FreeFromX-v0.1.0.apk`；
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
