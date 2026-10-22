# magic-windows（宽屏居中 + 两侧模糊）侦察结论

> 2026-10-20 真机取证。目标：任意 APP 可启用该模式，且**切换不重启应用进程**。

## 1. 这个效果到底是什么

真机截图量化（外屏 2364x1672 横屏，小红书/省钱快报）：
- 内容区：`Rect(591, 0 - 1773, 1672)` → 宽 **1180px**，两侧各留 **592px**
- 两侧是**壁纸模糊**（相邻像素梯度 0.43/0.60，内容区 41.21）→ 确认是 `MiuiBlurWallpaperProviderImpl` 画的
- 窗口模式名：**`miui-magic-windows`**（MIUI 自定义 windowingMode，不是 AOSP 原生）

## 2. 系统实现链路（反编译取证）

| 层 | 类 / 方法 | 作用 |
|---|---|---|
| system_server | `AppCompatUtils.fillAppCompatTaskInfo` | 填充 `AppCompatTaskInfo` |
| 写模糊开关 | `task.mTaskStub.shouldShowBlurWallpaper(top)` | `miuiShouldShowBlurWallpaper`（基类返回 false，MIUI 子类实现） |
| shell | `LetterboxTaskListenerAdapter.onTaskInfoChanged$lambda$0` | 读 `miuiShouldShowBlurWallpaper` → `useBlurWallpaper` |
| shell | `LetterboxControllerStrategy.configureLetterboxMode` | `useBlurWallpaper=true` → `SINGLE_SURFACE` 模式 |
| shell | `MiuiShellLetterboxManagerImpl.notifyLetterboxChanged` | 由 system 通知，非主动驱动 |
| shell | `MiuiBlurWallpaperProviderImpl` | 实际模糊壁纸 provider |
| system_server | `MiuiBlurWallpaperManager`（miui-services） | 预生成模糊壁纸 |

## 3. 启用开关（关键发现）

### 3.1 云配置白名单
`/data/system/cloudFeature_embedded_rules_list.xml`（8052 条），**immutable 属性**（需 `chattr -i` 才能改）。

对比同一条规则的两个应用：

```xml
<!-- 省钱快报：生效 magic-windows -->
<package name="com.jzyd.coupon" isShowDivider="true" supportFullSize="true"
         splitRatio="0.5" relaunch="false" skipSelfAdaptive="true" />
<!-- 小红书：不生效 -->
<package name="com.xingin.xhs" />
```

差异在 `relaunch` / `skipSelfAdaptive` 等属性。

### 3.2 运行时命令（更可靠，不需要改云文件）

```
cmd miui_embedding_window enable  <PACKAGE>        # 启用
cmd miui_embedding_window disable <PACKAGE>        # 停用
cmd miui_embedding_window list    [PACKAGE]
cmd miui_embedding_window set-relaunch <PACKAGE> 0|1
cmd miui_embedding_window set-scale <PACKAGE> 0|1|2
cmd miui_embedding_window set-skipSelfAdaptive <PACKAGE> true|false
cmd miui_embedding_window set-forceKillWhenSwitch <PACKAGE> true|false
cmd miui_embedding_window update-rule <PACKAGE> <RULE::PARAM>
```

### 3.3 核心判定（hook 点候选）

```java
// com.android.server.wm.MiuiEmbeddingWindowService
public boolean supportsEmbeddedWindow(TaskFragment tf, int windowingMode) {
    if (tf == null || tf.getTask() == null || (task = tf.getTask()) == null
        || task == tf || !tf.isEmbedded() || task.getWindowingMode() != 1
        || windowingMode != 6
        || !(isEmbeddingEnabledForPackage(task.getPackageName())
             || isEmbeddingEnabledForPackage(getTopRunningPackage(tf)))) {
        return false;
    }
    return DEBUG;
}
```

`isEmbeddingEnabledForPackage` 被 20 处调用（`canResumeTop` / `getEmbeddedOrientation` / `adaptConfigChangeForRelaunch` 等），是**运行时总开关**。

## 4. 「不重启进程」的难点（实测）

| 操作 | 进程 PID | 结论 |
|---|---|---|
| `enable` 后启动应用 | 新生 | 正常 |
| 运行中 `disable` | **PID 消失** | ❌ 进程被结束 |
| 运行中 `enable` | **PID 消失** | ❌ 进程被结束 |
| `set-relaunch 0` + enable/disable | **PID 消失** | ❌ 无效 |

`isNeedRelaunch` 默认返回 `true`（`MiuiSystemEmbeddedRule`），且切换要走 `forceKillAPP`。

## 5. 下一步（实现方向）

- **方案 A（推荐）**：hook `isEmbeddingEnabledForPackage`，模块内维护一份内存开关表，返回 true/false 覆盖系统值 → 运行时生效；
  再用 WCT 把 Task 转成 embedded TaskFragment + 设 bounds（16:9），全程不杀进程。
- **方案 B（备选）**：走 `cmd miui_embedding_window` + 允许一次重启；用「关闭→官方接口重开」代替杀进程（Freeform 已有的 `reopenAtMemory` 链路）。

## 6. 风险与回滚

- 云文件改的是 `/data/system`，随时可用备份回滚：`/data/local/tmp/ff_emb_backup.xml`
- 已改：`com.xingin.xhs` 加了 magic 属性；`com.jzyd.coupon` 保持原样
- 已设置：`cmd MiuiSizeCompat setFixedAspectRatio com.xingin.xhs 16:9`

## 7. 实测补充（关键）：命令行切换必然杀进程

| 测试 | 操作 | 结果 |
|---|---|---|
| `set-forceKillWhenSwitch com.xingin.xhs false` | 返回成功 | 无报错 |
| 运行中 `disable` | PID 12231 → **空** | ❌ 进程仍被杀 |
| 运行中 `enable` | PID 27874 → **空** | ❌ 进程仍被杀 |

**结论**：`cmd miui_embedding_window` 的任何 enable/disable 都会走
「规则变更 → relaunch/forceKill」，**无法通过命令行实现"不重启应用"**。

⇒ 必须走 **Xposed hook**：在 system_server 内直接把
`isEmbeddingEnabledForPackage(pkg)` 的返回值改掉（内存态），
让系统**以为**规则变了，但不去触发 relaunch 流程。
这才是"任意 APP + 切换不重启"的唯一可行路径。

### hook 点定稿

```
com.android.server.wm.MiuiEmbeddingWindowService#isEmbeddingEnabledForPackage(String): boolean
```
- 纯函数、无副作用、被 20 处调用（含 `supportsEmbeddedWindow` 这个总闸）
- 模块内维护 `Set<String> enabledPkgs`（来自模块自己的配置）
- hook 返回：`moduleEnabled(pkg) || original(pkg)`

节省风险：先做 **一次性 armed 模式**（只对配置名单生效），默认空名单 = 零行为变化。
