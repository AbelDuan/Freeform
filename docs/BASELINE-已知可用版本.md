# 基线存档：nbi-icon（已知可用版本）

> 用途：出问题时一键回到"能用"的状态。本文件是恢复依据，改代码前先读它。

## 一、这一版是什么

| 项 | 值 |
|---|---|
| 名称 | `nbi-icon`（保留原样，勿删） |
| 文件 | `dist/OS4FreeFromX-v0.4.0-nbi-icon.apk` |
| md5 | `62693bb9733f1df9b2a14af7203ad133` |
| 大小 | 725683 字节 |
| 备份位置 | 同仓库 `dist/`；另可放 `/data/local/tmp/nbi-icon.apk` |

**能力（已实测）**
- 小窗比例切换：竖屏 / 横屏**各按方向**记忆（小米自己识别：竖屏 1:1、横屏 16:9 均验证过）
- 三点菜单：`[16:9] [4:3] [1:1]` + 一个**方向图标**（手机轮廓，描边色取自 MIUI `caption_extend_text_color`）
- 方向切换走"关窗 → 官方接口重开"
- 无大白边 / 无按键错位（迷你态不干预；旋转时关窗重开）
- 空闲隐藏 + 触摸显现（三点/底栏）

**已知不足（记录在案，别当成新问题）**
- 切方向/比例时**会闪一下全屏**（根因：关窗那一刻应用回到全屏）
- 偶发回到 1:1（根因：形状只写当前设备方向的记忆槽；下一版已加"同步到对侧槽"）

## 二、恢复步骤（照抄）

```sh
# 1) 装回去
dsh-native shell --reason "回退到基线版 nbi-icon" -- '
B=/data/data/top.funcun.dshfolk/files/rootfs/root/workspace
cp $B/Freeform/dist/OS4FreeFromX-v0.4.0-nbi-icon.apk /data/local/tmp/nbi-icon.apk && chmod 644 /data/local/tmp/nbi-icon.apk
pm install -r /data/local/tmp/nbi-icon.apk
NEW=$(pm path com.abel.os4freeformx | sed "s/package://"); echo "NEW=$NEW"
cp -f /data/adb/lspd/config/modules_config.db $B/vs/lspdROLL.db; chmod 666 $B/vs/lspdROLL.db
'

# 2) 把 LSPosed 表里的 apk_path 指向新路径（容器里改库，再写回）
#    见本次会话中的 dbfix 流程：sqlite3 update modules set apk_path=? where mid=388

# 3) 重启 SystemUI 触发重新注入（不需要 Vector 开关）
dsh-native shell --reason "重启 SystemUI 使回退版生效" -- '
L=/data/adb/lspd/config/modules_config.db
cp -f <容器里改好的库> $L; rm -f $L-shm $L-wal; chown root:root $L; chmod 600 $L
killall com.android.systemui; sleep 10
pidof com.android.systemui
'

# 4) 校验
#    手机 md5 必须等于 62693bb9733f1df9b2a14af7203ad133
```

## 三、出问题时的判据

- 手机端 `md5sum $(pm path com.abel.os4freeformx | sed 's/package://')` 是否等于上表值
- 模块是否注入：`logcat -d | grep "Loaded module com.abel.os4freeformx"`
- 数据通道是否挂上：`logcat -d -s OS4FreeFromX | grep "数据通道已接入"`

## 四、不要删的东西

- `dist/` 下所有 `OS4FreeFromX-*.apk`（历史版本，用于逐版回退）
- `/root/workspace/nbi-backup/base-orig.apk`（原始壳，重打包要用）
- `/data/local/tmp/nbi-*.json`（NBI 规则回滚资产）
