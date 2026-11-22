# 外屏分屏移植 — 反编译取证结论（2026-11-21）

证据源：`/system_ext/framework/Miui-WindowManager-Shell.jar`（8,758,527 B，完整导出）
反编译：jadx 1.5.1，JDK 17

## 一、三点菜单定位机制（MiuiDecorationDot）

### 1. 已有挖孔避让（系统自带）
`adjustHandleMenuPos(int menuW, int menuH, int margin, int horizontalOffset, Point point)`:
```
DisplayCutout cutout = mContext.getDisplay().getCutout();
Rect rect = new Rect(point.x, point.y, point.x + menuW, menuH + point.y);
if (cutout == null || cutout.getBoundingRectTop().isEmpty()
        || !Rect.intersects(rect, cutout.getBoundingRectTop())) return;   // 不重叠就放行
int below = cutout.getBoundingRectTop().bottom + margin;
if (|rect.centerX() - cutout.centerX| < horizontalOffset) { point.y = below; return; }  // 居中 → 下移
if (rect.centerX() >= cutout.centerX) rect.offsetTo(cutout.right, rect.top);
else                                  rect.offsetTo(cutout.left - menuW, rect.top);
point.x = inBounds ? rect.left : point.x;
else point.y = below;
```
⇒ **外屏摄像头正处于顶部居中，菜单会被系统自动下移到摄像头下方**。

### 2. 根因缺陷：显示尺寸是全局静态
`MultiTaskingDisplayInfo`:
- `private static final DisplayLayout mDisplayLayout`
- `getDisplayWidth()/getDisplayHeight()/isLandscape()` 全部读这一个静态实例
- `onDisplayChanging(displayId, fromRot, toRot, ...)`:
  ```
  if (i != 0 || i2 == i3) return;     // ★ 只有 Display 0 才更新 mDisplayLayout
  mDisplayLayout.rotateTo(...)
  ```
⇒ **外屏上菜单宽度/高度/越界判定全部用内屏(1672×2364)的尺寸基准**。
外屏实际 1168×1712，横屏时更小 ⇒ 菜单可能溢出屏幕或位置错乱。
`calculateFreeformHandleMenuPos` 却用 `mDisplayController.getDisplayLayout(0)`
（硬编码 Display 0），两者混用。

### 3. 与本次任务的关系
外屏「三点菜单隐藏到摄像头、点击弹出」主要靠系统已有逻辑；
模块需要补的是**尺寸基准校正**（用真实屏幕宽度替换静态值），否则外屏菜单位置会错。

## 二、分屏方向判定（SoScUtilsImpl / SoScStageCoordinator）

- `SoScStageCoordinator.isLandscape()`:
  ```
  SplitLayout sl = mSplitLayout;
  if (sl == null) return false;
  Rect root = sl.getRootBounds();
  return root.width() > root.height();     // ★ 读当前屏的真实根边界
  ```
  ⇒ **会随屏幕变化**（与上面静态尺寸不同），这是好消息。

- `SoScUtilsImpl.supportTopBottomSplitFeature()`:
  ```
  if (mReady && supportTopBottomSplitDevice()) {
    if (supportedRatioCustomization()) return !(isFoldedState() || isLandscape());
    if (isPad()) return !isLandscape();
  }
  return false;
  ```
- `mStageCoordinator` 由 `injectDependencies(SoScStageCoordinator, ...)` 注入，
  绑定 `mDisplayId`（`SoScStageCoordinator` 字段 `mDisplayId`）。

## 三、外屏能否进分屏
外屏 DisplayInfo: `canHostTasks=false`, `FLAG_PRESENTATION`。
`isSoScSupported()` 与 `isCurrentDisplaySupportSplit()` → `MiuiMultiWindowUtils.isSupportSplitScreenFeature()`
（设备级，不分屏）。**代码层面未见按 canHostTasks 拦截分屏的判定**，
但需真机验证；若外屏无法承载 SoSc，则只能退化为「小窗 + 单应用」。

## 四、设备挖孔实测
| 屏 | DisplayId | 分辨率 | 挖孔(boundingRectTop) |
|---|---|---|---|
| 内屏 | 0 | 1672×2364（横屏 2364×1672） | 竖屏 (0,0)-(164,140) 左侧 |
| 外屏 | 1 | 1168×1712 | **顶部居中 (557,0)-(611,126)** |
