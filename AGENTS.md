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
bash tools/fix-lsposed-module.sh               # ← 必须！见下
```
1. **必须用 `--no-incremental`**：增量安装不触发 LSPosed 的包变更处理。
2. **`adb install` 之后必须跑 `tools/fix-lsposed-module.sh`**：
   LSPosed(v2.2.0) 不会跟着更新 `modules.apk_path` / `modules_state`（enabled）/ `scope` 三张表，
   症状是"模块装了但完全不注入"。脚本会修表并重启 `lspd` + SystemUI。
3. **改了配置后必须 `am force-stop com.abel.os4freeformx`**，否则模块 App 内存里的
   SharedPreferences 还是旧值，`StoreProvider` 会一直回旧配置（真机踩过三次）。
4. **`system` 作用域的钩子只在开机时注入** → 改 `installSystemServer` 的内容需要软重启；
   SystemUI 侧钩子 `killall` 即可。

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
