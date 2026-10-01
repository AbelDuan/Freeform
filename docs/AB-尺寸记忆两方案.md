# 尺寸记忆：方案 A / 方案 B

> 背景：修改记录 `record()`/`persist()` 写入的记忆尺寸时，必须回答一个问题 ——
> 记忆里的 `bounds` 与 MIUI 的 `freeformScale` 是什么关系？
> **显示尺寸 = bounds × freeformScale**（实测确认）。用户调完尺寸后实测：
> `真实 bounds = 1111×1482`，`可视(getScaledBounds) = 591×788`，`scale = 0.53`。

## 方案 B（当前采用）

**做法**：不动 MIUI 的 `scale`，把要存的 `bounds` 换算成 `可视尺寸 ÷ scale`。

```kotlin
// record() 里
val vis = call(repo2Ti(controller), "getScaledBounds") as? Rect
val base = if (vis 有效) vis else bounds
val store = Rect(bounds.left, bounds.top,
                 bounds.left + (base.width()/scale), bounds.top + (base.height()/scale))
persist(pkg, store, why, /*scale 原样*/ scale, keyScreen)
```
- 效果：`bounds × scale = 用户看到的大小` ⇒ 重开后看到的还是你调的大小 ✓
- 优点：**不破坏 MIUI 的模型**（scale 不变 ⇒ 装饰层/触摸区摆位不受影响 ✓）
- 缺点：记忆里的 `bounds` 不是"屏幕坐标里的真实矩形"，语义稍绕（已用日志标注）

## 方案 A（备选，用户可能要求切换）

**做法**：把 `bounds` 原样存，**同时把 `scale` 存成 1.0**。

```kotlin
persist(pkg, bounds, why, /*scale*/ 1.0f, keyScreen)
```
- 效果：窗口外框 = 你调的 `bounds`，内容按 1:1 渲染填满 ✓
- 风险（**实测踩过**）：MIUI 的装饰层（左右下角手柄 / 顶部三点 / 底部横条）是按
  `bounds × scale` 的显示区摆位的；强改 scale 曾导致**手柄消失、三点找不到、整窗不可用** ✗。
  若要切 A，**必须同时验证装饰层是否仍正常**，并准备好回退。

## 切换方式

- 当前实现：`Hooks.kt` 的 `record()` 里那段以 `方案 B 换算:` 开头的代码；切 A 即把
  `storeBounds` 直接改成 `bounds` 并把传给 persist 的 scale 改成 `1.0f`，同时保留日志。
- 两方案都只在 `record()`/`persist()` 一处，切换面很小，可当日回退。
