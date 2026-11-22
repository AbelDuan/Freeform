package com.abel.os4freeformx;

import android.content.Context;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 旋屏三件套的宿主适配层（**纯 Java**）。
 *
 * <p>为什么必须有这个类：`RotationHint` / `OrientationForcer` / `RotationButtonOverlay`
 * 是从 OS4 Themer **原样移植**的 Java 代码（合计 4137 行），它们直接写
 * {@code MainHook.sRotationHintEnable.get()} 这类引用。而构建顺序是
 * **javac 先、kotlinc 后** —— javac 阶段看不到 Kotlin 的 `RotationHost` 类，
 * 所以这些成员必须由**Java 类**提供，否则编译不过。
 *
 * <p>于是这里就出现了与 Kotlin 入口 `RotationHost` 同名的类。
 * 两者用途完全不同、互不干扰：
 * <ul>
 *   <li>Kotlin 侧（`MainHook.kt`）是 LSPosed 的 {@code XposedModule} 入口；</li>
 *   <li>本类只提供原版代码需要的 6 个静态成员（3 个开关 + 3 个查询）。</li>
 * </ul>
 * 字段值由 Kotlin 侧的 `Cfg` 在每次刷新配置时同步进来。
 *
 * <p>⚠️ 不要在这里加"优化"：原版语义就是「开关是模块级全局状态，
 * 配置一变立刻生效」，加缓存或订阅只会制造不一致。
 */
public final class RotationHost {

    private RotationHost() {
    }

    // ======================================================================
    //  三个开关（由 Cfg 刷新时同步）
    // ======================================================================

    /** 方向不一致时提示旋转。 */
    public static final AtomicBoolean sRotationHintEnable = new AtomicBoolean(true);

    /** 点击旋钮 = 强制旋转。 */
    public static final AtomicBoolean sForceRotateEnable = new AtomicBoolean(true);

    /** 展开时联动自动旋转（跟随 N 秒后锁定）。 */
    public static final AtomicBoolean sInnerFollowRotation = new AtomicBoolean(true);

    // ======================================================================
    //  宿主查询
    // ======================================================================

    /** 取 {@code IWindowManager} 的 public 代理（与原版实现一致）。 */
    static Object getWindowManagerServicePublic() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            java.lang.reflect.Method getService = sm.getDeclaredMethod("getService", String.class);
            getService.setAccessible(true);
            Object binder = getService.invoke(null, "window");
            if (binder == null) {
                return null;
            }
            Class<?> stub = Class.forName("android.view.IWindowManager$Stub");
            java.lang.reflect.Method asInterface =
                    stub.getDeclaredMethod("asInterface", android.os.IBinder.class);
            asInterface.setAccessible(true);
            return asInterface.invoke(null, binder);
        } catch (Throwable th) {
            LogUtil.logAlways("[旋屏] 取 IWindowManager 失败: " + th);
            return null;
        }
    }

    /**
     * 当前的应用 Context（原版 {@code MainHook.currentApplication()}）。
     *
     * <p>原版是从 ActivityThread 反射取 mInitialApplication；
     * 本模块的 Kotlin 侧已有等价的 {@code AppCtx.get()}，这里转发过去，
     * 保证原版代码的调用形态不变、拿到的也是同一个对象。
     */
    public static Object currentApplication() {
        // AppCtx 是 Kotlin 的 object，javac 阶段看不到它（编译顺序是 javac 先），
        // 所以这里按 Kotlin object 的 JVM 形态反射取值：com.abel.os4freeformx.AppCtx.INSTANCE.get()
        try {
            Class<?> k = Class.forName("com.abel.os4freeformx.AppCtx");
            Object inst = k.getField("INSTANCE").get(null);
            return k.getMethod("get").invoke(inst);
        } catch (Throwable th) {
            return null;
        }
    }

    /** 展开防抖窗口是否生效（钉 270° 过程中）。 */
    static boolean isUnfoldSettling() {
        return InnerRotationHost.isUnfoldSettling();
    }

    /** 内屏状态（边沿记忆版）。 */
    static boolean isInnerScreenRemembered(Context ctx) {
        return InnerRotationHost.isInnerScreenRemembered(ctx);
    }

    /** 展开窗口期是否生效（防抖中 或 跟随中且开关打开）。 */
    static boolean isUnfoldWindowActive() {
        return InnerRotationHost.isUnfoldWindowActive();
    }
}
