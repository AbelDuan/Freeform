# 本机硬约束与运维约定（OS4FreeFromX 专用）

> 从 2026-09-21 的多轮真机迭代中沉淀。**与 README / NOTES 的分工**：
> README 讲"有什么功能"，NOTES 讲"怎么实现的、踩了什么坑"，本文讲"在这台机器上干活必须遵守什么"。

## 一、设备与作用域

- 机型 Xiaomi 18 Fold（代号 `lhasa`），HyperOS `OS4.0.15.0.XPNCNXM`，Android 17，折叠屏。
- 模块 `com.abel.os4freeformx`，作用域 **`system`（system_server）+ `com.android.systemui`**。
- 桌面进程是 **Rust 实现（`hyper_launcher_app`，包名 `com.miui.home`）→ 不 hook**，
  也不走"模拟点击"路线（用户 2026-09-21 明确）。
- 容器网卡 IP 即手机 IP，`127.0.0.1:5555` 直达 adbd：**adb 走 loopback，与 WiFi 无关**。
  必须设 `ADB_LOCAL_TRANSPORT_MAX_PORT=5553`，否则设备会被登记成 `emulator-5554` + `127.0.0.1:5555` 两份。
- **默认禁止重启设备**；只有 `killall com.android.systemui` 属可自行动作。
  需要输入密码时：点亮 → 上滑 → 截图定位 → 依次点 `080808`（保持唤醒，密码界面停留时间短）。

## 二、每次改代码后的部署纪律（最重要）

```bash
./build.sh                                     # 产物 dist/OS4FreeFromX-v<VER>.apk
adb install --no-incremental -r dist/…apk
adb shell "su -c 'killall com.android.systemui'"   # ← 只需这一步（见 §7.1）
```
1. **必须用 `--no-incremental`**：增量安装不触发 LSPosed 的包变更处理。
2. ⚠️ **不要再无脑跑 `tools/fix-lsposed-module.sh`**（2026-10-06 更正）：它会 `kill lspd`，
   而**重启 daemon 会打断整条注入链路**（§7.1），恢复要框架级重启。
   - `pm install -r`（同签名覆盖）：LSPosed **自己会更新** `modules.apk_path`；
     只要 `modules_state`(enabled) 与 `scope` 还在（用 `tools/lspd-dump.sh` 复核），
     **只需 `killall com.android.systemui`**。
   - 只有 `uninstall`（换签名）才丢 enabled/scope 两张表；那时才需要改库，
     而改库脚本重启 daemon 后**必须再跟一次框架级重启**（`setprop ctl.restart zygote`，需用户授权）。
3. **改了配置后必须 `am force-stop com.abel.os4freeformx`**，否则模块 App 内存里的
   SharedPreferences 还是旧值，`StoreProvider` 会一直回旧配置（真机踩过三次）。
4. **`system` 作用域的钩子只在开机时注入** → 改 `installSystemServer` 的内容需要软重启；
   SystemUI 侧钩子 `killall` 即可（本模块的比例/记忆/颜色钩子都在 SystemUI 侧）。

## 三、代码层面的硬性约定

1. `system` 作用域的模块在**开机阶段只能装 hook**，绝不碰 `Context` / `ContentResolver` / prefs
   （历史事故：`onSystemServerStarting` 里起预热线程读 provider → 提前创建 `MediaRouter`
   → `MediaProjectionManagerService` 构造 NPE → system_server 连崩 → LSPosed 安全模式）。
2. 插件宿主半 `apply()` **绝不允许抛异常**（dsh 启动是 fail-loud，会整个起不来）。
3. 位置/尺寸记忆相关的**恢复端**用 `clampKeepRatio`（等比缩放，不单独裁边），
   **记录端**仍用 `clamp`（改了会动"是否需要 heal"的判定）。
4. 反射探测方法**先去反编译产物里确认它在哪个类**：
   `grep '\.method public <name>' recon/out/wmshell/**/*.smali`。
   本机已经因此踩过两次坑（`isSoScActive()` 在 `SoScUtilsImpl` 上、不在 `MultipleSplitController` 上）。
   静默 `runCatching` 会把这类错误藏得很深。
5. **凡是"取当前分屏组"的地方，只用"可见/活跃"语义的 API**
   （`getVisibleSplitChildTaskInfo()`，兜底 `getActiveStageList()`）——
   `getAllStageTaskInfo()` 一定会带出**历史遗留 stage**（踩过两次）。
6. **吞事件的状态标志必须有超时兜底**：MIUI 的多指通道**不保证投递 `ACTION_UP`**
   （本机因此踩过两次：一次是四指判定放在 UP 上永远不触发，一次是 `swallow` 死锁导致手势永久失灵）。
7. 多指手势的"持续条件"不能写成"必须始终 ≥N 指"，要写成"掉到 <2 指才放弃"——
   真实手指不会同步抬起。
8. 涉及 `Transitions#startTransition` 的调用有**线程断言**
   （`HandlerExecutor.assertCurrentThread()`），必须投到 `Transitions.getMainExecutor()`。
9. 批量改代码（python `str.replace`）后**必须校验替换结果并看编译输出**，
   不能只看"产物"两个字（本项目因此误判过两次"已改+已构建"）。

## 四、与系统手势的共存规则

屏幕下部是 MIUI 自己的热区（上滑到左上角进双分屏 / 底部中间上滑进多分屏）：

1. **先判开关，再决定是否吞事件** —— 否则"关掉功能"也会拦死官方手势（踩过）。
2. 起手区必须给**底部正中**留让位区。
3. 模块的角滑**只在单应用全屏时生效**，分屏/多分屏/已有小窗一律放行给系统。
4. 官方手势行为以用户实测为准；改完必须回归验证"官方双/三/四分屏 + 导航条上滑回桌面"是否正常。

## 五、已被证伪的路线（不要再试）

| 路线 | 结果 |
| --- | --- |
| 直调 `MultipleSplitController#startMultipleSplits` | 黑屏、SystemUI 重启 |
| 调官方接口 `IMultiTaskingStateManager#startMultipleSplits` | 同样黑屏 / 延迟 SystemUI 重启 |
| 自己拼多分屏（`transferSoScToMultipleSplit` + `insertMultipleSplitBy*`） | 左半屏变小 + 右侧黑块 |
| `SoScUtils#prepareDragDropTaskToSoSc` 脱离拖拽会话直调 | 两侧黑屏 |
| 在 SystemUI 进程里自建选择窗口（Popup / `createWindowContext` + `addView`） | `BadTokenException`（无 overlay 授权） |

**结论**：多分屏（三分屏起）**只用系统原生手势进入**，模块不代劳。
`Constants.DEF_FOUR_FINGER_MULTI` 永久锁死为 `false`。

## 六、构建环境

容器 `/tmp` 会被清空，工具全部落在仓库 `tools/`（不入库）：
- JDK21 + aapt2/zipalign(arm64) + d8/apksigner(纯 Java jar) + 自组 kotlinc（阿里云 Maven 五件套）
- `zip` 用 `tools/zip-shim.py` 自愈安装（`build.sh` 里已带）
- 重建步骤见 `tools/README.md`

签名密钥 `keystore/os4freeformx.jks`（不入库）。**换了密钥必须 `adb uninstall` 再装**，
否则 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。

## 七、2026-10-06 真机迭代踩到的坑（必须遵守）

### 1. 绝不要 `kill lspd` —— 会打断整条注入链路
LSPosed(v2.2.0) 的 daemon 一旦重启，system_server 里的 `LSPosedBridge` 变成孤儿：
daemon 日志持续 `no response from bridge` → `system service is not ready, skip scope request`，
**此后 fork 的所有进程都不再注入任何模块**（不只是本模块；HyperCeiler/NoActive 全灭）。
已运行的进程不受影响 ⇒ 排查时会看到"22 个进程仍有模块"的**幸存者偏差**。
恢复只有一条路：框架级重启（`setprop ctl.restart zygote`，非重启设备）。**免重启路径已穷尽**：
重试 daemon、包变更唤醒 bridge、SELinux/avc 排查、`lspctl`（其 `emulated-soft-reboot.sh` 就是 `lspctl stop`）均无效。

**正确流程**：`pm install -r` 后 LSPosed 自己会更新 `modules.apk_path`；
若 `modules_state`(enabled) / `scope` 还在，**只需 `killall com.android.systemui`**。
只有 `uninstall`（换签名）才会丢这两张表 —— 那时才需要改库 + 重启 daemon，而重启 daemon 必然要跟一次框架级重启。

### 2. 用命令直接开小窗（验证用，别去多任务慢慢点）
```sh
am start --windowingMode 5 -n <pkg>/<launcherActivity>
# 例：am start --windowingMode 5 -n com.tencent.mm/.ui.LauncherUI
```
MIUI 只对**支持小窗的应用**生效（设置类不行，会报 "该应用不支持小窗"）。
`am start --help` 在本 ROM 不是合法选项，别拿它查用法。

### 3. `/data/data`、`/data/user`、`/data/user_de` 在 App 命名空间里是 **tmpfs 桩**
容器/`su` 继承的是调用者的 mount namespace，只能看到自己那条 bind ⇒ 模块 App 的
`shared_prefs` 看起来"不存在"。**要看真实目录必须 `nsenter -t 1 -m -- ls ...`**（PID 1 的命名空间）。
否则会把"看不见"误判成"没落盘"。

### 4. 取证产物不要放 `build/`
`build.sh` 开头就 `rm -rf "$OUT"`，截图/XML/日志放进去会被下一轮构建清空。

### 5. `dsh-native shell` 的特权通道会**按空格重新切分**命令
`sh -c '...'` 会被拆碎（`cp: Needs 1 argument`）。一律先写脚本文件，再 `sh <绝对路径>`。

### 6. 模块记忆/配置存储（便于自测）
- 路径：`/data/user/0/com.abel.os4freeformx/shared_prefs/os4freeformx_bounds.xml`（`pkg|短边x长边` → `l,t,r,b@scale`）
- 读写通道（模块 App 的 ContentProvider，任何进程都能调）：
```sh
content call --uri content://com.abel.os4freeformx.store --method getAll
content call --uri content://com.abel.os4freeformx.store --method put --extra k:s:"<pkg>|<screen>" --extra v:s:"l,t,r,b@scale"
content call --uri content://com.abel.os4freeformx.store --method remove --arg "<pkg>|<screen>"
content call --uri content://com.abel.os4freeformx.store --method selfcheck   # 写→读→删自检
```
⚠️ `selfcheck` 的"写回读"走内存 map，**不证明落盘**；要证明落盘就 `nsenter -t 1 -m -- cat` 那个 XML。
