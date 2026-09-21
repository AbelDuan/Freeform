# 本地推送指引

bundle 已包含全部提交（27 个）。在你自己的电脑上执行：

```bash
# 1) 从 bundle 克隆出完整仓库
git clone OS4FreeFromX-gestures.bundle Freeform
cd Freeform

# 2) 指向 GitHub 远端
git remote set-url origin git@github.com:AbelDuan/Freeform.git
#    （或 https：git remote set-url origin https://github.com/AbelDuan/Freeform.git）

# 3) 推送
git push origin main
```

灌进你本地已有的 clone（只取需要的提交）：

```bash
git fetch /path/to/OS4FreeFromX-gestures.bundle main:os4ffx-gestures
git merge os4ffx-gestures        # 或按需 cherry-pick
git push origin main
```

## 最近 10 个提交

```
0259ef1 fix(gestures): SoSc→多分屏后延迟 450ms 再插 stage（转场是异步的）
b065738 fix(gestures): 调试轮询改为延迟读配置（安装期 Cfg 还没拿到 remote prefs）
dbdf404 fix(gestures): isSoScActive 探测错类导致分屏内永远走错分支
8107f91 docs: 新增 DELIVERY.md 交付说明（功能、开关、取证、踩坑、构建）
02e5126 docs: README 更新分屏内加分屏的灰度说明与两处踩坑
ecd617c fix(gestures): 分屏加窗改用 insertMultipleSplitByIntent（另一侧黑屏的根因）
c6271f3 docs: 打开分屏内灰度开关的验证说明与回滚方法
8b0832a fix(gestures): 按官方语义修正 transferSoScToMultipleSplit 参数 + 分屏内动作加灰度开关
c64b5ab docs: README 写明分屏内加分屏暂未开放及其原因
69e01dc fix(gestures)!: 分屏场景动作短路，止血闪退
```
