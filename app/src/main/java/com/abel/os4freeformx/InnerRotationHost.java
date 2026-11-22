package com.abel.os4freeformx;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * 内屏联动状态机 —— **自 OS4 Themer 的 MainHook 原样移植**（约 960 行）。
 *
 * <p>用户 2026-11-20 要求：「所有相关的功能全部按原版对齐，代码都可以用一样的」。
 * 所以这里**不做任何"优化"或"简化"**，连注释一起搬。
 * 那些看起来啰嗦的地方（亮屏钉回熄屏方向、展开伴随亮屏不钉回、
 * 跟随窗口互斥、占用标记自愈……）每一条都对应一次真机事故，
 * 注释里写着日期与现象，删掉就会把老问题带回来。
 *
 * <h2>三个触发点（与 OS4 Themer 完全一致）</h2>
 * <ol>
 *   <li><b>展开</b>：合上时已把内屏预置成 270°，展开边沿到达即解除锁定跟随，
 *       {@code INNER_FOLLOW_DURATION_MS} 后回锁；</li>
 *   <li><b>亮屏</b>：先把屏幕钉回熄屏时的方向（避免 AOD 一亮就变横），
 *       再走一遍上面的跟随流程；若正伴随展开则不钉回（交给展开流程定 270°）；</li>
 *   <li><b>解锁</b>（{@code ACTION_USER_PRESENT}）：同样临时解除锁定跟随。</li>
 * </ol>
 *
 * <h2>铁律</h2>
 * 模块只切 {@code accelerometer_rotation*} 这个**开关**，绝不写 {@code user_rotation*}。
 * 唯一例外是「合上时预置 270°」—— 它作用于此刻不可见的内屏。
 */
public final class InnerRotationHost {

    private InnerRotationHost() {
    }

    // ─── 屏幕开关 / 亮熄屏 ───
    private static final android.os.Handler sInnerHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private static volatile boolean sScreenWatchersInstalled = false;
    private static volatile boolean sBootstrapPosted = false;
    private static volatile int sBootstrapAttempts = 0;

    // ─── 展开边沿 ───
    private static volatile boolean sIsInnerNow = false;

    /**
     * 是否已经观测过至少一次真实状态。
     *
     * <p>**为什么需要它**（用户 2026-11-21）：
     * 「每次重启 SystemUI 之后，本来锁定的屏幕旋转被关掉，并且不自动恢复。」
     *
     * 根因：`sIsInnerNow` 初值 false，SystemUI 重启后手机本来就在内屏，
     * 第一次判定看到「当前是内屏 + 上次不是」⇒ 被当成**用户刚展开**，
     * 于是跑了完整的展开流程（解除旋转锁 5 秒 → 再锁回），
     * 而这时用户根本没动手机，等于把锁定状态搅乱了。
     *
     * 修法：**进程内第一次观测只是"认路"** —— 记录当前是不是内屏，
     * 不把它当作边沿事件，不触发任何开关动作。
     * 之后真正的合上/展开才会产生边沿。
     */
    private static volatile boolean sObserved = false;
    private static volatile boolean sUnfoldSettling = false;
    private static volatile int sUnfoldGen = 0;

    // ─── 亮屏窗口（区分「亮屏唤醒」与「真的展开」）───
    private static volatile long sScreenOnUntil = 0L;
    private static final long SCREEN_ON_WINDOW_MS = 1500L;

    // ─── 熄屏方向 ───
    private static volatile int sScreenOffRotation = -1;

    // ─── 展开时刻 ───
    private static volatile long sLastUnfoldAt = 0L;
    private static final long UNFOLD_RECENT_MS = 2000L;

    // ─── 跟随窗口 ───
    static volatile boolean sFollowing = false;
    private static volatile long sLastWindowEndAt = 0L;
    private static final long INNER_WINDOW_COOLDOWN_MS = 4000L;

    // ─── 用户意志 / 占用标记 ───
    static volatile long sOccupyUntil = 0L;
    static volatile int sUserIntent = -2;
    private static volatile int sLockSnapshot = -1;
    private static volatile long sSelfWriteAt = 0L;
    private static volatile int sSelfWrittenCr = -1;
    /** 上次由模块写入的 accelerometer_rotation_inner 值（自写识别）。 */
    private static volatile int sSelfWrittenInner = -1;

    // ─── 折叠状态（边沿记忆）───
    static volatile boolean sInnerActive = false;
    static volatile long sInnerStateAt = 0L;
    private static volatile boolean sInnerObserverInstalled = false;
    private static volatile int sLastSeenCr = -1;
    static volatile Context sInnerCtx = null;

    private static volatile int sIsSystemUiProc = -1;

    // ─── 联动参数（与原版 Constants 一致）───
    /** 展开/亮屏/解锁后跟随多久再重新锁定。 */
    static final long INNER_FOLLOW_DURATION_MS = 5000L;
    /** 合上时是否把内屏预置成横屏。 */
    static final boolean PIN_INNER_270_ON_UNFOLD = true;
    /** 预置方向：270° = 摄像头在右上角的横屏。 */
    static final int UNFOLD_PIN_ROTATION = 3;

    // ─── 落盘（用户意志 / 占用标记）───
    private static final String INNER_STATE_PREFS = "os4freeformx_inner_state";
    private static final String KEY_USER_INTENT = "user_intent";
    private static final String KEY_OCCUPY_UNTIL = "module_unlock_until";

    private static void ensureScreenWatchers(final Context ctx) {
        if (sScreenWatchersInstalled || ctx == null) {
            return;
        }
        // ⚡ 屏幕开关联动：熄屏时注销方向传感器（省电）。
        //   旋转按钮只在亮屏时有意义，而加速度计按 60Hz 上报会持续
        //   唤醒 CPU —— 一天里大半时间屏幕是灭的，这处优化收益最大。
        try {
            ctx.registerReceiver(
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            try {
                                String a = intent.getAction();
                                if (Intent.ACTION_SCREEN_ON.equals(a)) {
                                    RotationHint.onScreenStateChanged(true);
                                    // 点亮屏幕也当作「刚刚打开内屏」判定一次。
                                    // 先清状态，否则会被「已经是内屏」的重复触发挡回去。
                                    sIsInnerNow = false;
                                    // 🔑 v3.295.3：标记本次来源是「亮屏」而不是「真的展开」。
                                    //   用到期时刻而不是布尔值：亮屏会连发多次回调，
                                    //   谁先到都能拿到正确结论（见 sScreenOnUntil 注释）。
                                    sScreenOnUntil = android.os.SystemClock.uptimeMillis()
                                            + SCREEN_ON_WINDOW_MS;
                                    // 🔴 v3.295.3（用户 2026-11-12）：
                                    //   「熄屏时屏幕在什么方向，点亮后就停在什么方向」。
                                    //   亮屏瞬间先把屏幕钉回熄屏时的方向，
                                    //   避免系统在点亮过程中按旧状态把方向带偏。
                                    //   随后 applyInnerFixedLandscape 会按「亮屏」分支
                                    //   临时解除锁定几秒（与展开逻辑一致），
                                    //   让方向能跟随此刻的传感器。
                                    // 🔴 v3.295.11（用户 2026-11-13「展开不是 270 而是锁屏前的角度」）：
                                    //   **正在展开的过程中到来的亮屏广播，不许把方向钉回去**。
                                    //
                                    //   日志实证：
                                    //     22.556  展开：先钉 3°（此刻屏幕方向=0）
                                    //     22.885  屏幕已到达 270°，解除锁定        ← 已经钉好 270
                                    //     23.003  setRotationLockForFold(true, 0) ← 被这里改回 0！
                                    //     23.004  亮屏：先钉回熄屏时的方向=0
                                    //
                                    //   展开时 SCREEN_ON 几乎同时到达，若这里无条件
                                    //   「钉回熄屏方向」，就把刚刚钉好的 270° 覆盖成 0°。
                                    //   展开流程自己会把方向定为 270°，此时不该插手。
                                    int offRot = sScreenOffRotation;
                                    if (sUnfoldSettling || unfoldJustHappened()) {
                                        LogUtil.logAlways("[内屏联动] 亮屏：伴随展开，"
                                                + "不钉回熄屏方向（交给展开流程定 270°）");
                                    } else if (offRot >= 0 && isInnerDisplayActiveNow(context)) {
                                        pinInnerRotation(offRot);
                                        LogUtil.logAlways("[内屏联动] 亮屏：先钉回熄屏时的方向="
                                                + offRot);
                                    }
                                    applyInnerFixedLandscape();
                                    // 补一次延迟判定：亮屏广播可能早于设备状态刷新
                                    applyInnerFixedLandscapeDeferred();
                                } else if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                                    RotationHint.onScreenStateChanged(false);
                                    // 🔴 v3.295.4（用户 2026-11-12）：
                                    //   「竖屏锁屏，等到 AOD 亮起时已经变成横屏了」。
                                    //
                                    //   根因是熄屏时的**锁定顺序错了**：
                                    //   原来先 pinInnerRotation(记录的方向)，
                                    //   紧接着 relockInner() 内部又调
                                    //   callWindowManagerRotation(true) —— 那是
                                    //   setRotationLockForFold(true, -1, false)，
                                    //   而 -1 的语义是「以**当前**方向为准锁定」。
                                    //   熄屏瞬间系统可能已按传感器把内容转成横屏，
                                    //   于是这次 -1 把刚钉好的竖屏覆盖成横屏，
                                    //   AOD 一亮就是横的。
                                    //
                                    //   修法：**只锁一次，且显式锁在熄屏这一刻的方向上**。
                                    //   不再走 relockInner 的 -1 版锁定。
                                    // ⚠️ 用按折叠状态明确指认内屏的方式读方向
                                    //   （OrientationForcer.rotationByFoldState），
                                    //   而不是 readScreenRotationNow() 挑「第一个 STATE_ON 的屏」——
                                    //   熄屏瞬间内屏可能已是 OFF/DOZE，挑不到就会读错或读不到。
                                    boolean wasInner = isInnerDisplayActiveNow(context);
                                    if (wasInner) {
                                        sScreenOffRotation =
                                                OrientationForcer.rotationByFoldState(sInnerCtx);
                                    } else {
                                        // 外屏熄屏 → 清掉旧值，避免之后内屏亮屏
                                        // 误用「上次内屏熄屏」留下的方向。
                                        sScreenOffRotation = -1;
                                    }
                                    LogUtil.logAlways("[内屏联动] 熄屏：记录方向="
                                            + sScreenOffRotation + "（内屏=" + wasInner + "）");
                                    if (sScreenOffRotation >= 0) {
                                        // 显式钉在熄屏这一刻的方向，绝不使用 -1
                                        pinInnerRotation(sScreenOffRotation);
                                        // 只写开关值，不再触发任何方向改写
                                        setInnerAutoRotate(context.getContentResolver(), false);
                                    }
                                    // 熄屏后重置内屏状态：下次唤醒若仍是展开态，
                                    // 会重新走一遍判定，不会因为状态残留被当成重复触发。
                                    sIsInnerNow = false;
                                    sUnfoldSettling = false;
                                    sUnfoldGen++;
                                    // 跟随窗口一律终止（不调 relockInner，避免它的 -1 锁方向）
                                    cancelFollowTimer();
                                    sFollowing = false;
                                    sOccupyUntil = 0L;
                                    persistInnerState();
                                } else if (Intent.ACTION_USER_PRESENT.equals(a)) {
                                    // 🔴 v3.295.6（用户 2026-11-12 要求）：
                                    //   在「展开」「亮屏」之外，再增加「解锁」这个触发点，
                                    //   同样临时解除方向锁定几秒，让屏幕按当前传感器自由旋转。
                                    //   只在内屏生效（见 onInnerUnlocked 内部判断）。
                                    onInnerUnlocked();
                                }
                            } catch (Throwable unused) {
                            }
                        }
                    },
                    new IntentFilter(Intent.ACTION_SCREEN_ON) {{
                        addAction(Intent.ACTION_SCREEN_OFF);
                        addAction(Intent.ACTION_USER_PRESENT);
                    }});
            LogUtil.logAlways("[旋转提示] 已注册屏幕开关监听（熄屏注销传感器）");
        } catch (Throwable t2) {
            LogUtil.logAlways("[旋转提示] 屏幕监听注册失败: " + t2);
        }

        // 📌 内屏固定还需要跟随「展开/折叠」事件。
        //   DisplayManager.DisplayListener 在屏幕增删、状态变化时回调，
        //   展开内屏（Display 1 关闭 / Display 0 变为活跃）会走到这里。
        try {
            android.hardware.display.DisplayManager dmg =
                    (android.hardware.display.DisplayManager)
                            ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dmg != null) {
                dmg.registerDisplayListener(
                        new android.hardware.display.DisplayManager.DisplayListener() {
                            @Override
                            public void onDisplayAdded(int displayId) {
                                try {
                                    applyInnerFixedLandscape();
                                } catch (Throwable unused) {
                                }
                            }

                            @Override
                            public void onDisplayRemoved(int displayId) {
                            }

                            @Override
                            public void onDisplayChanged(int displayId) {
                                try {
                                    // 屏幕尺寸/状态变了 → 丢弃悬浮窗，下次显示时重建
                                    RotationButtonOverlay.get().reset();
                                } catch (Throwable unused) {
                                }
                                try {
                                    applyInnerFixedLandscape();
                                } catch (Throwable unused) {
                                }
                            }
                        }, null);
                LogUtil.logAlways("[内屏固定] 已注册屏幕变化监听");
            }
        } catch (Throwable td) {
            LogUtil.logAlways("[内屏固定] 屏幕变化监听注册失败: " + td);
        }
        sScreenWatchersInstalled = true;
    }

    private static void ensureInnerBootstrap() {
        if (sBootstrapPosted || sScreenWatchersInstalled) {
            return;
        }
        sBootstrapPosted = true;
        sBootstrapAttempts = 0;
        postBootstrapRetry(1200L);
    }

    private static void postBootstrapRetry(final long delay) {
        try {
            sInnerHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (sScreenWatchersInstalled || sBootstrapAttempts >= 6) {
                        sBootstrapPosted = false;
                        return;
                    }
                    sBootstrapAttempts++;
                    try {
                        applyInnerFixedLandscape();
                    } catch (Throwable unused) {
                    }
                    if (!sScreenWatchersInstalled) {
                        postBootstrapRetry(1500L);
                    } else {
                        sBootstrapPosted = false;
                    }
                }
            }, delay);
        } catch (Throwable th) {
            sBootstrapPosted = false;
        }
    }

    private static void cancelFollowTimer() {
        try {
            sInnerHandler.removeCallbacksAndMessages(null);
        } catch (Throwable unused) {
        }
        sFollowing = false;
    }

    private static int readSysInt(android.content.ContentResolver cr, String key, int def) {
        if (cr == null) {
            return def;
        }
        try {
            return android.provider.Settings.System.getInt(cr, key, def);
        } catch (Throwable unused) {
            return def;
        }
    }

    private static void persistInnerState() {
        Context c = sInnerCtx;
        if (c == null) {
            return;
        }
        try {
            c.getSharedPreferences(INNER_STATE_PREFS, Context.MODE_PRIVATE).edit()
                    .putInt(KEY_USER_INTENT, sUserIntent)
                    .putLong(KEY_OCCUPY_UNTIL, sOccupyUntil)
                    .apply();
        } catch (Throwable th) {
            LogUtil.logAlways("[用户意志] 保存本地状态失败: " + th);
        }
    }

    private static void ensureInnerStateObserver(Context ctx) {
        if (ctx == null) {
            return;
        }
        sInnerCtx = ctx;
        // 用 ApplicationContext 写 prefs，避免持有 Activity 之类短命 Context
        loadInnerState(ctx.getApplicationContext() != null
                ? ctx.getApplicationContext() : ctx);
        if (sInnerObserverInstalled) {
            return;
        }
        try {
            final android.content.ContentResolver cr0 = ctx.getContentResolver();
            sLastSeenCr = readSysInt(cr0, "accelerometer_rotation", 0);
            android.database.ContentObserver obs =
                    new android.database.ContentObserver(
                            new android.os.Handler(android.os.Looper.getMainLooper())) {
                        @Override
                        public void onChange(boolean selfChange) {
                            try {
                                onInnerSwitchMaybeChanged();
                            } catch (Throwable th) {
                                LogUtil.logAlways("[用户意志] 处理设置变化失败: " + th);
                            }
                        }
                    };
            android.content.ContentResolver cr = ctx.getContentResolver();
            cr.registerContentObserver(
                    android.provider.Settings.System.getUriFor("accelerometer_rotation"),
                    false, obs);
            cr.registerContentObserver(
                    android.provider.Settings.System.getUriFor("accelerometer_rotation_inner"),
                    false, obs);
            // 🔑 v3.301.0（用户 2026-11-17）：外屏专用键也要监听。
            //
            //   用户要求：「系统设置里旋屏锁定是关掉的时候，所有应用都能旋转，要保持一致性。」
            //   外屏的锁定键是 accelerometer_rotation_outer，与通用键并不同步；
            //   不监听它，就发现不了"用户把外屏旋转锁关掉了"这件事，
            //   模块接管过的整屏锁定会一直挂着，导致应用都不自动转。
            cr.registerContentObserver(
                    android.provider.Settings.System.getUriFor("accelerometer_rotation_outer"),
                    false, obs);
            sInnerObserverInstalled = true;
            LogUtil.logAlways("[用户意志] 已监听旋转开关（含外屏专用键），用户手动改动优先于联动");
            // 进程刚起来（可能刚被重启过）先自愈一次遗留占用
            sInnerHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        healInnerLockIfStale("SystemUI 启动后自检");
                    } catch (Throwable unused) {
                    }
                }
            }, 1500L);
        } catch (Throwable th) {
            LogUtil.logAlways("[用户意志] 监听注册失败: " + th);
        }
    }

    private static boolean healInnerLockIfStale(String reason) {
        if (!RotationHost.sInnerFollowRotation.get()) {
            return false;
        }
        if (!isSystemUiProc()) {
            return false;
        }
        Context ctx = sInnerCtx;
        if (ctx == null) {
            return false;
        }
        long until = sOccupyUntil;
        if (until <= 0L) {
            return false;   // 模块没有占用锁，无事可做
        }
        if (sFollowing) {
            return false;   // 计时器还活着，交给它
        }
        if (System.currentTimeMillis() < until) {
            return false;
        }
        try {
            relockInner(ctx.getContentResolver(),
                    "跟随窗口已过期（进程可能被杀）→ " + reason + "，已恢复锁定");
            return true;
        } catch (Throwable th) {
            LogUtil.logAlways("[内屏联动] 自愈失败: " + th);
            return false;
        }
    }

    private static boolean callWindowManagerRotation(boolean locked) {
        // ① 首选：折叠屏专用接口
        try {
            Object wm = getWindowManagerService();
            if (wm != null) {
                java.lang.reflect.Method m =
                        wm.getClass().getMethod("setRotationLockForFold",
                                boolean.class, int.class, boolean.class);
                m.setAccessible(true);
                // ✅ 2026-10-20 用 app_process 直接调用 WMS 实测确认：
                //   第三个参数（isFold）语义：
                //     true  → 操作**外屏**（mUserRotationModeOuter）
                //     false → 操作**内屏**（mUserRotationModeInner）
                //
                //   实测记录：
                //     setRotationLockForFold(false, -1, false)
                //       → Inner: LOCKED → FREE（解除锁定）✅
                //     setRotationLockForFold(true, -1, false)
                //       → Inner: FREE → LOCKED，且锁在 ROTATION_270（当前方向）✅
                //     Outer 全程不受影响 ✅
                //
                //   所以锁内屏必须传 false。
                m.invoke(wm, locked, -1, false);
                LogUtil.logAlways("[内屏固定] setRotationLockForFold("
                        + locked + ", -1, false) 调用成功（目标=内屏）");
                return true;
            }
        } catch (Throwable th) {
            LogUtil.logAlways("[内屏固定] setRotationLockForFold 失败: " + th);
        }
        // ② 次选：freeze / thaw
        try {
            Object wm = getWindowManagerService();
            if (wm == null) {
                return false;
            }
            if (locked) {
                java.lang.reflect.Method m =
                        wm.getClass().getMethod("freezeRotation", int.class, String.class);
                m.setAccessible(true);
                m.invoke(wm, -1, "HyperOSGlass");
            } else {
                java.lang.reflect.Method m =
                        wm.getClass().getMethod("thawRotation", String.class);
                m.setAccessible(true);
                m.invoke(wm, "HyperOSGlass");
            }
            LogUtil.logAlways("[内屏固定] freeze/thawRotation → " + locked);
            return true;
        } catch (Throwable th) {
            LogUtil.logAlways("[内屏固定] freeze/thawRotation 失败: " + th);
        }
        return false;
    }

    private static void setInnerAutoRotate(android.content.ContentResolver cr, boolean unlocked) {
        if (cr == null) {
            return;
        }
        int v = unlocked ? 1 : 0;
        // 通用键：控制中心 / 设置页那个「自动旋转」开关读的就是它
        try {
            android.provider.Settings.System.putInt(cr, "accelerometer_rotation", v);
        } catch (Throwable th) {
            LogUtil.logAlways("[内屏联动] 回写 accelerometer_rotation 失败: " + th);
        }
        // 内屏专用键：MIUI 的 WMS 内屏旋转策略读它
        try {
            android.provider.Settings.System.putInt(cr, "accelerometer_rotation_inner", v);
        } catch (Throwable th) {
            LogUtil.logAlways("[内屏联动] 回写 accelerometer_rotation_inner 失败: " + th);
        }
        LogUtil.logAlways("[内屏联动] 已回写 accelerometer_rotation=" + v
                + "，accelerometer_rotation_inner=" + v);
        // 标记「这次是我们写的」，让设置观察者把随之而来的回调当成回声而不是用户操作
        markSelfWrite(v, v);
    }

    private static boolean isAutoRotateOn(android.content.ContentResolver cr) {
        if (cr == null) {
            return false;
        }
        try {
            if (android.provider.Settings.System.getInt(cr, "accelerometer_rotation", 0) != 0) {
                return true;
            }
        } catch (Throwable unused) {
        }
        try {
            return android.provider.Settings.System.getInt(
                    cr, "accelerometer_rotation_inner", 0) != 0;
        } catch (Throwable unused) {
            return false;
        }
    }

    private static boolean isSystemUiProc() {
        int cached = sIsSystemUiProc;
        if (cached >= 0) {
            return cached == 1;
        }
        boolean r = false;
        boolean decided = false;
        // ① 首选：Application 的包名
        try {
            Object app = currentApplication();
            if (app instanceof Context) {
                r = "com.android.systemui".equals(((Context) app).getPackageName());
                decided = true;
            }
        } catch (Throwable unused) {
        }
        // ② 兜底：/proc/self/cmdline（此时只信「是」的结果，不信「否」）
        if (!decided) {
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/self/cmdline"))) {
                StringBuilder sb = new StringBuilder();
                int ch;
                while ((ch = br.read()) > 0) {
                    sb.append((char) ch);
                }
                String name = sb.toString();
                if ("com.android.systemui".equals(name)) {
                    r = true;
                    decided = true;
                }
                // name 是 zygote64 / zygote 之类 → 进程还没 setArgV0，不做结论
            } catch (Throwable unused) {
            }
        }
        if (decided) {
            sIsSystemUiProc = r ? 1 : 0;
        }
        return r;
    }

    /**
     * 最近一次折角读数（度）。由 `FoldSplitTrigger` 的 hinge_angle 监听写入。
     *
     * <p>**为什么需要它**（用户 2026-11-21）：
     * 「内屏折叠过程中，没合到外屏、没切到外屏时不要触发旋转锁定。」
     *
     * 实机现象：内屏折到一半，`current_device_state` 就从 3 变成 2，
     * 模块据此误判「已切到外屏」并跑了整套合上流程（含预置 270°、恢复锁定）。
     * 而此刻屏幕还没真的合上，用户看到的就是"折一下就闪一下锁定"。
     *
     * <p>折角传感器才是物理真相：真正合上时角度 ≈ 0~3°；
     * 折叠过程（90°、120°…）都还不算切到外屏。所以合上判定额外要求折角足够小。
     *
     * <p>值为 {@link #HINGE_UNKNOWN} 表示还没收到过读数（此时不做额外限制，
     * 保持原有行为，避免在传感器没起来时把功能整个卡死）。
     */
    public static final float HINGE_UNKNOWN = Float.NaN;

    private static volatile float sHingeAngle = HINGE_UNKNOWN;

    /** 真正算"已合到外屏"的最大折角（度）。fold 到 10° 以内视为合上。 */
    private static final float HINGE_CLOSED_MAX = 10f;

    /** 由折角传感器回调写入（同进程内直接调用，无 IPC 开销）。 */
    public static void setHingeAngle(float degrees) {
        sHingeAngle = degrees;
    }

    /**
     * 当前是否**真的**已经合到外屏。
     *
     * <p>`current_device_state` 说不是内屏，**并且**折角确实接近合上。
     * 折角未知时退回只看设备状态（保持原有行为）。
     */
    private static boolean isReallyFoldedClosed() {
        float a = sHingeAngle;
        if (Float.isNaN(a)) return true;   // 没读数 ⇒ 不做额外限制
        return a <= HINGE_CLOSED_MAX;
    }

    private static boolean isInnerDisplayActive(android.hardware.display.DisplayManager dm,
                                                android.content.ContentResolver cr) {
        try {
            int st = android.provider.Settings.Global.getInt(
                    cr, "current_device_state", -1);
            if (st >= 0) {
                boolean inner = (st == 3);
                // 🔑 v3.294.6（用户 2026-11-12 要求「我不懂为什么要轮询」）：
                //   这里原来无条件打日志，而这个方法会被高频调用（每次屏幕事件、
                //   每次 reconcile 都走一遍），实测每秒刷十几次 —— 既费资源又会掩盖
                //   真正的信号。改成只在状态**发生变化**时记录。
                if (inner != sInnerActive) {
                    LogUtil.logAlways("[内屏固定] current_device_state=" + st
                            + " → 内屏=" + inner);
                }
                return inner;
            }
        } catch (Throwable th) {
            LogUtil.logAlways("[内屏固定] 读 current_device_state 失败: " + th);
        }
        // 兜底（非澎湃机型）：用「面积最大的屏」判断
        int best = -1;
        int activeArea = -1;
        for (android.view.Display d : dm.getDisplays()) {
            if (d == null || !d.isValid()) {
                continue;
            }
            android.util.DisplayMetrics mx = new android.util.DisplayMetrics();
            d.getRealMetrics(mx);
            int a = mx.widthPixels * mx.heightPixels;
            if (a > best) {
                best = a;
            }
            if (d.getState() == android.view.Display.STATE_ON) {
                activeArea = a;
            }
        }
        if (best > 0 && activeArea > 0) {
            boolean inner = (activeArea == best);
            LogUtil.logAlways("[内屏联动] 兜底判据 area=" + activeArea
                    + " 最大=" + best + " → 内屏=" + inner);
            return inner;
        }
        return false;
    }

    private static void relockInner(android.content.ContentResolver cr, String reason) {
        callWindowManagerRotation(true);
        setInnerAutoRotate(cr, false);
        sLockSnapshot = 0;
        sFollowing = false;
        // 占用标记一并清掉：从这里开始，键上的 0 不再属于模块
        sOccupyUntil = 0L;
        persistInnerState();
        LogUtil.logAlways("[内屏联动] " + reason);
        RotationHint.reconcileRotationHint("内屏联动：" + reason);
    }

    private static void scheduleRelock(
            final android.content.ContentResolver cr, long delayMs) {
        try {
            sInnerHandler.removeCallbacksAndMessages(null);
        } catch (Throwable unused) {
        }
        sInnerHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!sFollowing) {
                        return;   // 已被合上 / 熄屏取消
                    }
                    // 🔴 v3.295.10（用户 2026-11-12「手动开关展开后几秒被覆盖」）：
                    //   恢复锁定前必须再确认一次「开关还是模块打开的那个」。
                    //
                    //   ⚠️ 判据不能用 sOccupyUntil 是否过期：
                    //     它是「跟随窗口 + 3000ms 自愈余量」= 8 秒，
                    //     而回锁发生在 5 秒 → 到点这一刻它永远没过期，
                    //     拿它判断等于什么都没判。
                    //
                    //   正确判据：跟随开始时我们把 sLockSnapshot 记成 1，
                    //   表示「此刻键上的值是我写的」。到点再看：
                    //     · 键上已经不是 1（用户关掉了）→ 走下面的 isAutoRotateOn 分支；
                    //     · 键上还是 1，但 sUserIntent==1 → 用户中途打开过，不能回锁；
                    //   两个分支之后才是「确实还是模块的那把 1」→ 回锁。
                    //
                    //   这里额外加一条：若占用标记已被用户操作清零
                    //   （onInnerSwitchMaybeChanged 会置 0L），也不再回锁。
                    if (sOccupyUntil <= 0L) {
                        sFollowing = false;
                        persistInnerState();
                        LogUtil.logAlways("[内屏联动] 计时结束：占用已被用户操作清零，"
                                + "开关不归模块管，保持现状不回锁");
                        return;
                    }
                    // 用户在窗口期内自己动了开关 → 已记为用户意志，最高优先级
                    if (sUserIntent == 1) {
                        sFollowing = false;
                        sOccupyUntil = 0L;
                        persistInnerState();
                        LogUtil.logAlways("[内屏联动] 计时结束：用户已选择自由旋转，保持现状不回锁");
                        return;
                    }
                    // 两个键都已是 0（用户自己关了）→ 已是锁定态，只需对齐 WMS
                    if (!isAutoRotateOn(cr)) {
                        callWindowManagerRotation(true);
                        sFollowing = false;
                        sOccupyUntil = 0L;
                        persistInnerState();
                        LogUtil.logAlways("[内屏联动] 计时结束（开关已被关闭）"
                                + "，已对齐旋转锁，不改写设置");
                        return;
                    }
                    relockInner(cr, "计时结束，已恢复锁定");
                } catch (Throwable th) {
                    LogUtil.logAlways("[内屏联动] 打开锁定失败: " + th);
                } finally {
                    sFollowing = false;
                    // 只有「计时器真的跑到点」才记冷却起点：
                    // 合上/熄屏是取消（回调被移除，不会进这里），不影响下次展开
                    sLastWindowEndAt = android.os.SystemClock.uptimeMillis();
                }
            }
        }, delayMs);
    }

    private static boolean isInnerDisplayActiveNow(Context ctx) {
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager)
                            ctx.getSystemService(Context.DISPLAY_SERVICE);
            android.content.ContentResolver cr = ctx.getContentResolver();
            return isInnerDisplayActive(dm, cr);
        } catch (Throwable unused) {
            return sInnerActive;
        }
    }

    private static boolean isInteractiveNow(Context ctx) {
        try {
            if (ctx == null) {
                ctx = sInnerCtx;
            }
            if (ctx == null) {
                return true;      // 拿不到 Context 时不拦截，保持原行为
            }
            android.os.PowerManager pm =
                    (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable unused) {
            return true;
        }
    }

    static void applyInnerFixedLandscape() {
        if (!RotationHost.sInnerFollowRotation.get()) {
            // 诊断用：开关没生效时也能在日志里看到，不再"静默无反应"
            LogUtil.logAlwaysOnce("inner-off", "[内屏联动] 跳过：联动开关未开启");
            return;
        }
        // 模块作用域含 systemui / settings / miui 插件三个进程，
        // 只允许 SystemUI 执行，避免多进程互相覆盖设置。
        if (!isSystemUiProc()) {
            LogUtil.logAlwaysOnce("inner-proc", "[内屏联动] 跳过：当前不是 SystemUI 进程");
            return;
        }
        try {
            Object app = currentApplication();
            if (!(app instanceof Context)) {
                LogUtil.logAlways("[内屏联动] 跳过：currentApplication 不可用（" + app + "）");
                return;
            }
            Context ctx = (Context) app;
            final android.content.ContentResolver cr = ctx.getContentResolver();

            // 触发源兜底：SystemUI 被重生后 onPackageLoaded 可能没走到，
            // 这里补装屏幕开关 / 屏幕变化监听（幂等）
            ensureScreenWatchers(ctx);

            // 装上「用户意志」监听（幂等），并确保本地状态已加载
            ensureInnerStateObserver(ctx);

            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager)
                            ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                LogUtil.logAlways("[内屏联动] 跳过：DisplayManager 不可用");
                return;
            }

            boolean isInnerActive = isInnerDisplayActive(dm, cr);

            // 🔴 v3.295.4（用户 2026-11-12「竖屏锁屏，AOD 亮起时已变横屏」）：
            //   **熄屏期间一律不进入「展开」判定**。
            //
            //   日志实测：熄屏 130ms 后仍会收到 DisplayListener / 延迟判定回调，
            //   sIsInnerNow 已被熄屏分支清成 false，于是被当成「刚展开」，
            //   走「钉 270° → 700ms 后解除锁定 5 秒 → 再锁在当前方向」的完整流程。
            //   结果就是：熄屏那一刻的竖屏，被这一整套流程改成了横屏，
            //   AOD 一亮就是横的。
            //
            //   熄屏时屏幕不面向用户，没有任何「展开/跟随」的意义，
            //   方向已在熄屏分支里显式钉死，这里直接返回即可。
            if (!isInteractiveNow(ctx)) {
                LogUtil.logAlwaysOnce("inner-sleep",
                        "[内屏联动] 熄屏期间，跳过展开/跟随判定");
                return;
            }

            // 每次进来都先自愈一次「上次跟随被打断」的遗留（进程重启也能收回来）
            boolean healed = healInnerLockIfStale("联动入口");

            // ── 首次观测：只认路，不触发任何动作 ──
            //   SystemUI 重启 / 进程重建后，手机当前可能是内屏也可能是外屏，
            //   这都是"本来就在那里"的状态，不是用户刚做的边沿动作。
            if (!sObserved) {
                sObserved = true;
                sIsInnerNow = isInnerActive;
                sInnerActive = isInnerActive;
                sInnerStateAt = android.os.SystemClock.uptimeMillis();
                LogUtil.logAlways("[内屏联动] 首次观测：当前=" + (isInnerActive ? "内屏" : "外屏")
                        + " ⇒ 只记录状态，不触发开关动作");
                return;
            }

            // ── 合上 / 切到外屏 ──
            // ⚠️ 折叠**过程中** `current_device_state` 就会从 3 变 2，但屏幕并没有真的合上。
            //    必须再确认折角已接近合上（≤10°），否则会误跑整套"合上"流程。
            if (!isInnerActive && !isReallyFoldedClosed()) {
                LogUtil.logAlwaysOnce("inner-fold-mid",
                        "[内屏联动] 折叠中（折角=" + sHingeAngle + "°），尚未合到外屏 ⇒ 不触发合上流程");
                return;
            }
            if (!isInnerActive) {
                if (sIsInnerNow) {
                    LogUtil.logAlways("[内屏联动] 已合上");
                }
                sIsInnerNow = false;
                // 🔑 v3.294.4：合上边沿写状态值（内屏 → 外屏）
                sInnerActive = false;
                sInnerStateAt = android.os.SystemClock.uptimeMillis();
                // 折叠状态真的变了 → 清掉窗口冷却，下次展开照常解锁
                sLastWindowEndAt = 0L;
                // 钉 270° 还没结束就合上了 → 作废，避免迟到的回调在合上后解锁
                if (sUnfoldSettling) {
                    sUnfoldSettling = false;
                    sUnfoldGen++;
                    LogUtil.logAlways("[内屏联动] 钉 270° 期间合上，本次钉住作废");
                }
                // 规则 3：5 秒内合上 → 立即停止计时，并把旋转锁打开
                if (sFollowing) {
                    cancelFollowTimer();
                    relockInner(cr, "合上时仍在计时，已取消并恢复锁定");
                } else if (sOccupyUntil > 0L) {
                    // 没有计时器但还留着占用标记：说明键上的 1 是我们的，收回
                    relockInner(cr, "合上时发现跟随遗留，已恢复锁定");
                }
                // 🔴 v3.9.2（用户 2026-11-13）：**合上时就把内屏方向定成 270°**。
                //
                //   用户原话：「只需要在合上内屏切换到外屏的时候，将内屏的方向
                //   调到 270 度，这样展开内屏的时候，它的第一画面就是在 270 度，
                //   然后解除方向锁定之后，根据用户的方向去看传感器方向。」
                //
                //   这样比在展开时钉 270° 更干净：展开的第一帧就已经是 270，
                //   不需要「先转到 270 再被传感器拉回来」那一整套动作。
                //
                //   内屏此刻不显示内容，钉它不影响用户；展开时方向已经是 270，
                //   随后解除锁定即可按传感器走。
                if (PIN_INNER_270_ON_UNFOLD) {
                    pinInnerRotation(UNFOLD_PIN_ROTATION);
                    LogUtil.logAlways("[内屏联动] 合上：内屏方向已预置为 "
                            + UNFOLD_PIN_ROTATION + "°，展开时第一帧即 270°");
                }
                return;
            }

            // ── 进入内屏：识别展开边沿 ──
            // ⚠️ 2026-10-22 用户反馈「展开时关闭自动旋转开关失效」。
            //   原来这里用 sIsInnerNow + sWasUnfolded 两个状态来识别边沿：
            //   只要有一次「合上」没有走到（比如熄屏唤醒、监听漏触发），
            //   sIsInnerNow 就会一直停在 true，之后每次展开都被当成重复触发
            //   而直接 return —— 功能永久失效，且没有任何日志。
            //
            //   现在只用一个状态：只要当前已是内屏就直接返回。
            if (sIsInnerNow) {
                return;
            }
            sIsInnerNow = true;
            // 🔑 v3.294.4：展开边沿写状态值（外屏 → 内屏）
            sInnerActive = true;
            sInnerStateAt = android.os.SystemClock.uptimeMillis();
            LogUtil.logAlways("[内屏联动] 检测到展开");

            // 🔴 2026-11-22 修复（用户：「亮屏和解锁还是没有自动解锁旋转」）：
            //
            //   `sLastUnfoldAt` 此前**只声明、从未赋值**（恒为 0L），
            //   因此 `unfoldJustHappened()` 恒为 false —— 下面「亮屏」分支
            //   的 `if (sUnfoldSettling || unfoldJustHappened())` 那层保护永不生效，
            //   于是「展开 + 亮屏」几乎同时到达时，亮屏会把刚放开的锁
            //   又按熄屏方向钉回去（日志实证：
            //     27.094 展开：已放开旋转锁
            //     27.159 亮屏：先钉回熄屏时的方向=3  ← 把放锁覆盖掉）。
            //   在展开边沿记录时刻，保护才会真正起作用。
            sLastUnfoldAt = android.os.SystemClock.uptimeMillis();

            // 已在计时中（多次入口重复触发）：忽略
            if (sFollowing) {
                LogUtil.logAlways("[内屏联动] 计时中，忽略重复触发");
                return;
            }

            // 本轮刚做过「遗留自愈」→ 已经收回锁定，本次不再重复放开，
            // 否则会变成「收回来又立刻放开」的空转
            if (healed) {
                return;
            }

            // ⚠️ 2026-10-25 v3.283.5「用户意志最高优先级」重写：
            //   v3.283.4 在这里对「旋转锁已是打开」**无差别**补一个跟随窗口，
            //   DURATION 后强制关掉 —— 这样用户自己在控制中心打开的自动旋转
            //   也会被关掉，等于模块压过了用户。用户明确要求以用户意志为准。
            //
            //   现在按来源区分，不再靠猜：
            //     ① 有占用标记（sOccupyUntil，落盘）→ 这个 1 是我们写的，
            //        例如上次跟随被 SystemUI 重启打断 → 收回锁定（自愈）；
            //     ② 没有占用标记 → 那是用户自己开的，**一律不碰**。
            //   占用标记让「自愈」和「尊重用户」可以同时成立，这是 v3.283.4
            //   粗暴做法的正确替代。
            // ⚠️ 判据用「用户面开关」accelerometer_rotation，而不是两键取或：
            //   实测 _inner 会被 MIUI 自己同步成 1（用户关了开关它也可能还是 1），
            //   取或会把「用户要锁定」误读成「用户要自由旋转」。用户能看见、
            //   能操作的就是 accelerometer_rotation，以它为准。
            int userSwitch = readSysInt(cr, "accelerometer_rotation", -1);
            boolean switchOn = userSwitch >= 0 ? userSwitch != 0 : isAutoRotateOn(cr);
            if (switchOn) {
                // 🔴 v3.9.1：sOccupyUntil 在跟随窗口内恒 >0（now+5000+3000），
                //   只判 >0 会在刚放行用户选择后立刻又回锁。必须判「已过期」。
                if (sOccupyUntil > 0L && sOccupyUntil < System.currentTimeMillis()) {
                    relockInner(cr, "检测到上次跟随的遗留占用，已恢复锁定（自愈）");
                    return;
                }
                // 只有「不是模块刚写的 1」才算用户亲手打开（见 isUserOpenedAutoRotate）
                if (isUserOpenedAutoRotate()) {
                    if (sUserIntent != 1) {
                        sUserIntent = 1;
                        persistInnerState();
                    }
                    sFollowing = false;
                    LogUtil.logAlways("[内屏联动] 旋转锁本来就是打开且不是模块所写"
                            + " → 视为用户选择自由旋转，联动让位");
                    return;
                }
                // 是模块刚写的 1 → 不是用户意志，继续走正常流程
                LogUtil.logAlways("[内屏联动] 开关为 1 但是模块刚写的（非用户意志），继续联动流程");
            }

            // 规则 1：锁定中 → 先钉住 270°，稳定后再解除锁定跟随
            // ⚠️ v3.294.0 修复「偶尔展开后是竖屏」：
            //   展开边沿到达时，system_server 还没把屏幕踢回 ROTATION_0 ——
            //   此时直接解除锁定，那一次短暂的「竖屏」就会被当成当前方向接住，
            //   表现为「展开后是竖屏（摄像头在顶部）」，偶尔发生、复现靠时序。
            //   所以改成：边沿到达 → 立刻钉 270° → 等系统把方向定下来 → 再解除锁定。
            //
            // 🔴 v3.295.3（用户 2026-11-12）：**内屏亮屏唤醒不钉 270°**。
            //   一直停在内屏、锁屏后再唤醒时，也走这条「展开」路径，
            //   于是被硬钉回 270°，不跟随唤醒时的传感器方向。
            //   亮屏场景下屏幕方向本来就是对的（折叠状态没变过），
            //   不需要「先钉再放」，直接走下面的解除锁定即可。
            //
            //   ⚠️ 用「亮屏窗口」判断而不是一次性标记：亮屏会连发多次回调
            //   （SCREEN_ON 广播 + DisplayListener + 延迟判定），无论谁先到都能正确分类。
            // 🔴 v3.9.2（用户 2026-11-13）：**展开不再钉 270°**。
            //
            //   用户方案：「合上内屏切换到外屏时把内屏方向调到 270°，
            //   展开时第一画面就是 270°，然后解除锁定按传感器走。」
            //
            //   这样比原来「展开时先钉 270 再解除」干净得多：
            //   原来钉完解除后，系统立刻按传感器把 270 拉回手机实际朝向，
            //   视觉上是「转过去又转回来」。
            //   现在 270 在合上时就设好，展开第一帧即是 270，直接解除锁定跟随。
            //
            //   ⚠️ 仍然要作废可能遗留的 sUnfoldSettling，否则解锁会被永久挡住。
            if (sUnfoldSettling) {
                sUnfoldSettling = false;
                sUnfoldGen++;
                LogUtil.logAlways("[内屏联动] 展开：作废遗留的钉 270° 标志位");
            }
            boolean screenOnEdge = inScreenOnWindow();
            if (screenOnEdge) {
                LogUtil.logAlways("[内屏联动] 内屏亮屏唤醒：按当前方向解除锁定");
            }
            // 窗口冷却：同一次亮屏里 DisplayListener / SCREEN_ON 会连续进来，
            // 冷却期内不再开第二个窗口。
            // ⚠️ 亮屏 / 解锁是用户重新发起的独立事件，不该被上次跟随的冷却挡住
            //   （实测被挡时 3.5 秒后才解锁，正是「进桌面才解」的成因）。
            if (!screenOnEdge) {
                long sinceEnd = android.os.SystemClock.uptimeMillis() - sLastWindowEndAt;
                if (sLastWindowEndAt > 0L && sinceEnd >= 0
                        && sinceEnd < INNER_WINDOW_COOLDOWN_MS) {
                    LogUtil.logAlways("[内屏联动] 距上次跟随仅 " + sinceEnd
                            + "ms，冷却中，本次不重复解锁");
                    return;
                }
            }
            sFollowing = true;
            callWindowManagerRotation(false);
            // ★ 2026-10-24 修复「展开/唤醒自动旋转解锁」不生效：
            //   setRotationLockForFold 只改 WMS 内部锁定态，**不回写** Settings，
            //   而两处判定都读 accelerometer_rotation_inner。不补写就会出现：
            //     • 系统按旧值(0=锁定)把刚解开的锁定改回去 → 看起来「解锁没生效」；
            //     • 5 秒后快照(1)与真实值(0)不符 → 误判为「用户改过」→ 放弃重新锁定。
            //   这里补写 1，并**以写完后的真实值**作为快照，两种回推行为都能自洽。
            setInnerAutoRotate(cr, true);
            sLockSnapshot = 1;
            // 记下「这段时间这把 1 是模块的」，落盘 —— 即使 SystemUI 下一秒被杀，
            // 下次启动也能认出来并收回，不会永久放养。
            markOccupy();
            persistInnerState();
            LogUtil.logAlways("[内屏联动] 展开：已放开旋转锁，"
                    + INNER_FOLLOW_DURATION_MS + "ms 后恢复锁定");
            // 锁暂时打开 → 让旋转提示收起（屏幕会自己转，不需要旋钮）
            RotationHint.reconcileRotationHint("内屏联动：临时放开锁定");

            scheduleRelock(cr, INNER_FOLLOW_DURATION_MS);
        } catch (Throwable th) {
            LogUtil.logAlways("[内屏联动] 异常: " + th);
        }
    }

    static void onInnerUnlocked() {
        // ★ 先记录解锁时刻：isUnfoldWindowActive() 依赖它判断「刚解锁」窗口期。
        //   必须放在所有 return 之前，否则被前面的守卫挡住时就记不到。
        sLastUserPresentAt = android.os.SystemClock.uptimeMillis();
        LogUtil.logAlwaysOnce("unlock-enter", "[内屏联动诊断] 收到解锁/亮屏事件，开始判定");
        if (!RotationHost.sInnerFollowRotation.get()) {
            LogUtil.logAlwaysOnce("unlock-off", "[内屏联动诊断] 联动开关关闭 ⇒ 不处理");
            return;
        }
        if (!isSystemUiProc()) {
            LogUtil.logAlwaysOnce("unlock-notsu", "[内屏联动诊断] 非 SystemUI 进程 ⇒ 不处理");
            return;
        }
        Context ctx = sInnerCtx;
        if (ctx == null) {
            LogUtil.logAlwaysOnce("unlock-noctx", "[内屏联动诊断] sInnerCtx 为空 ⇒ 不处理");
            return;
        }
        try {
            // 🔴 v3.301.2（用户 2026-11-19 反馈「亮屏/解锁时偶尔没及时解开自动旋转」）：
            //
            //   这里原来用 isInteractiveNow() 挡熄屏，但本方法的唯一调用点是
            //   ACTION_USER_PRESENT —— 那个广播本身**就是**「用户已解锁、屏幕已点亮」
            //   的权威信号，再查一次 PowerManager.isInteractive() 属于重复判断，
            //   而且会引入时序竞态：广播发出与 PowerManager 状态更新之间有一帧间隙，
            //   偶尔读到 false 就直接 return，这次解锁就漏掉了（"偶尔"的来源）。
            //
            //   正确的做法：信任广播语义，不在解锁路径上重复判屏幕状态。
            //   （"熄屏不进入展开判定" 那个场景在 applyInnerFixedLandscape 里另有
            //    isInteractiveNow 把关，与这里互不影响。）
            // 只在内屏生效
            if (!isInnerDisplayActiveNow(ctx)) {
                LogUtil.logAlwaysOnce("unlock-notinner", "[内屏联动诊断] 当前不是内屏 ⇒ 不处理");
                return;
            }
            // 🔴 2026-11-21（用户：「每次重启 SystemUI 之后，本来锁定的屏幕旋转被关掉」）：
            //
            //   `ACTION_USER_PRESENT` 是**每次解锁都会发**的广播 ——
            //   包括「SystemUI 重启后用户什么都没做」「普通亮屏解锁」。
            //   原来这里只检查"当前是不是内屏"就无条件解除旋转锁，
            //   于是每次重启 SystemUI / 每次解锁都把用户的锁定状态搅乱一次。
            //
            //   这个入口的真实语义是：「用户刚**展开**手机、还没来得及看到画面就被
            //   锁屏打断，解锁时要赶紧把方向放开」。所以必须要求**确实处于展开窗口期**，
            //   而不是"当前恰好是内屏"。
            if (!isUnfoldWindowActive()) {
                LogUtil.logAlwaysOnce("inner-userpresent-idle",
                        "[内屏联动] 解锁但不在展开窗口期 ⇒ 不动旋转锁");
                return;
            }
            // 正在钉 270° 的展开窗口里：交给展开流程，别抢
            if (sUnfoldSettling) {
                LogUtil.logAlways("[内屏联动] 解锁：正在展开钉 270° 窗口内，交给展开流程");
                return;
            }
            // 已经在跟随计时中：不重复开窗
            if (sFollowing) {
                LogUtil.logAlways("[内屏联动] 解锁：跟随计时中，忽略");
                return;
            }
            // 用户自己开着自动旋转 → 尊重用户，不接管
            android.content.ContentResolver cr = ctx.getContentResolver();
            int userSwitch = readSysInt(cr, "accelerometer_rotation", -1);
            boolean switchOn = userSwitch >= 0 ? userSwitch != 0 : isAutoRotateOn(cr);
            if (switchOn) {
                // 🔴 v3.9.1：同展开路径，只判 >0 会把窗口内的占用误当遗留
                if (sOccupyUntil > 0L && sOccupyUntil < System.currentTimeMillis()) {
                    relockInner(cr, "解锁：检测到上次跟随的遗留占用，已恢复锁定（自愈）");
                    return;
                }
                // 同展开路径：只在「不是模块所写」时才算用户意志
                if (isUserOpenedAutoRotate()) {
                    if (sUserIntent != 1) {
                        sUserIntent = 1;
                        persistInnerState();
                    }
                    LogUtil.logAlways("[内屏联动] 解锁：旋转锁本来就是用户打开的，联动让位");
                    return;
                }
                LogUtil.logAlways("[内屏联动] 解锁：开关为 1 但是模块刚写的（非用户意志），继续");

            }
            // 模块上次跟随的遗留占用：先收回，再开新窗口
            healInnerLockIfStale("解锁触发");

            sFollowing = true;
            callWindowManagerRotation(false);
            setInnerAutoRotate(cr, true);
            sLockSnapshot = 1;
            markOccupy();
            persistInnerState();
            LogUtil.logAlways("[内屏联动] 解锁：已解除方向锁定，按传感器自由旋转，"
                    + INNER_FOLLOW_DURATION_MS + "ms 后恢复锁定");
            RotationHint.reconcileRotationHint("内屏联动：解锁后临时放开锁定");
            scheduleRelock(cr, INNER_FOLLOW_DURATION_MS);
        } catch (Throwable th) {
            LogUtil.logAlways("[内屏联动] 解锁处理异常: " + th);
        }
    }

    private static void pinInnerRotation(int rotation) {
        Object wm = getWindowManagerService();
        if (wm == null) {
            LogUtil.logAlways("[内屏固定] 取不到 IWindowManager，钉 " + rotation + "° 失败");
            return;
        }
        try {
            java.lang.reflect.Method m = wm.getClass()
                    .getMethod("setRotationLockForFold", boolean.class, int.class, boolean.class);
            m.setAccessible(true);
            m.invoke(wm, true, rotation, false);
            LogUtil.logAlways("[内屏固定] setRotationLockForFold(true, " + rotation
                    + ", false) 已钉住内屏");
        } catch (Throwable th) {
            LogUtil.logAlways("[内屏固定] 钉住内屏失败: " + th);
        }
    }

    /**
     * 当前的「自动旋转」开关是不是**用户亲手打开**的（而非模块为了跟随临时写的）。
     *
     * 🔴 2026-11-22 修复（用户：「不会自动解开，等会又自动恢复锁定，之前要求是
     *   能自动解开旋转锁定，数秒后恢复锁定」）：
     *
     * 原实现是「模块是否在过去 1500ms 内写过」：
     * <pre>
     * return !(sSelfWrittenCr == 1 &amp;&amp; now - sSelfWriteAt &lt; 1500L);
     * </pre>
     * 但放锁与回锁之间隔着 {@code INNER_FOLLOW_DURATION_MS = 5000ms} ——
     * 到点回锁时，距那次写入早已超过 1500ms，判定**必然**为 true
     * ⇒ 每次都被当成「用户自己开的自由旋转」⇒ 永远不回锁（锁被永久解除）。
     *
     * 正确判据是模块自己维护的 {@link #sUserIntent}：
     *   · {@code sUserIntent == 1} → 用户确实亲手打开过 → 让位，不回锁；
     *   · 其它值 → 这个 1 是模块为了跟随写的 → 到点必须回锁。
     * 1500ms 窗口只用于「观察者回声」过滤（见 {@link #isSelfWriteEcho}），
     * 不该拿来当用户意志的判据。
     */
    private static boolean isUserOpenedAutoRotate() {
        return sUserIntent == 1;
    }

    /**
     * 设置观察者的**回声过滤**：模块刚写过，随之而来的 onChange 不算用户操作。
     * 这个才该用 1500ms 窗口。
     */
    private static boolean isSelfWriteEcho() {
        return sSelfWrittenCr == 1
                && android.os.SystemClock.uptimeMillis() - sSelfWriteAt < 1500L;
    }

    private static void markOccupy() {
        sOccupyUntil = System.currentTimeMillis()
                + INNER_FOLLOW_DURATION_MS + 3000L;
    }

    private static void applyInnerFixedLandscapeDeferred() {
        try {
            sInnerHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        applyInnerFixedLandscape();
                    } catch (Throwable unused) {
                    }
                }
            }, 400L);
        } catch (Throwable unused) {
        }
    }

    private static Object currentApplication() {
        try {
            return Class.forName("android.app.ActivityThread").getMethod("currentApplication", new Class[0]).invoke(null, new Object[0]);
        } catch (Throwable unused) {
            return null;
        }
    }
    // ======================================================================
    //  时间窗判定 / 本地状态
    // ======================================================================

    /** 取 {@code IWindowManager} 的 public 代理（与原版同名方法的实现一致）。 */
    private static Object getWindowManagerService() {
        return RotationHost.getWindowManagerServicePublic();
    }

    /** 最近一次 ACTION_USER_PRESENT 的时刻（uptimeMillis），0 = 从未。 */
    private static volatile long sLastUserPresentAt = 0L;

    /** 解锁后多久之内允许「视为刚解锁」。 */
    private static final long USER_PRESENT_RECENT_MS = 8000L;

    private static boolean userPresentJustHappened() {
        long t = sLastUserPresentAt;
        return t > 0L && android.os.SystemClock.uptimeMillis() - t < USER_PRESENT_RECENT_MS;
    }

    private static boolean unfoldJustHappened() {
        long t = sLastUnfoldAt;
        return t > 0L && android.os.SystemClock.uptimeMillis() - t < UNFOLD_RECENT_MS;
    }

    private static boolean inScreenOnWindow() {
        long until = sScreenOnUntil;
        // 用 uptimeMillis（不受 NTP 对时 / 改系统时间影响），与 sLastUnfoldAt 一致
        return until > 0L && android.os.SystemClock.uptimeMillis() < until;
    }

    private static void loadInnerState(Context ctx) {
        if (sUserIntent != -2) {
            return;
        }
        int intent = -1;
        long until = 0L;
        try {
            SharedPreferences sp = ctx.getSharedPreferences(INNER_STATE_PREFS, Context.MODE_PRIVATE);
            intent = sp.getInt(KEY_USER_INTENT, -1);
            until = sp.getLong(KEY_OCCUPY_UNTIL, 0L);
        } catch (Throwable th) {
            LogUtil.logAlways("[用户意志] 读取本地状态失败: " + th);
        }
        sUserIntent = intent;
        sOccupyUntil = until;
        LogUtil.logAlways("[用户意志] 已加载：userIntent=" + intent + " occupyUntil=" + until);
    }

    private static void markSelfWrite(int crValue, int innerValue) {
        sSelfWrittenCr = crValue;
        sSelfWrittenInner = innerValue;
        sSelfWriteAt = android.os.SystemClock.uptimeMillis();
    }

    private static void onInnerSwitchMaybeChanged() {
        Context ctx = sInnerCtx;
        if (ctx == null) {
            return;
        }
        android.content.ContentResolver cr = ctx.getContentResolver();
        int crV = readSysInt(cr, "accelerometer_rotation", 0);
        int inV = readSysInt(cr, "accelerometer_rotation_inner", 0);
        // ① 用户面开关没变（可能只是 _inner 被系统改写）→ 不记用户意志
        if (crV == sLastSeenCr) {
            return;
        }
        long dt = android.os.SystemClock.uptimeMillis() - sSelfWriteAt;
        // ② 这是模块刚刚自己写出来的值 → 回声，忽略
        if (dt >= 0 && dt < 1500L && sSelfWrittenCr >= 0 && crV == sSelfWrittenCr) {
            sLastSeenCr = crV;
            return;
        }
        // ②' 模块正占用着旋转锁（跟随窗口内）→ 此刻的写入大概率是 MIUI 旋转服务
        //     对刚才解锁动作的同步回推，不是用户在操作。宁可放过，不能误判：
        //     一旦误判成「用户要锁定」，用户自己打开的自动旋转就被永久记住了。
        // 🔴 v3.295.8（用户 2026-11-12「手动设置的自由旋转被覆盖」）：
        //   跟随窗口内**不能一律**把设置变化当系统回推。
        //
        //   原来只要 occupying 就忽略，于是用户在 5 秒窗口期内
        //   亲手打开「自由旋转」，这个动作被当成噪声丢掉，
        //   5 秒一到 relockInner 又把开关写回 0 —— 用户的选择被覆盖。
        //
        //   现在区分方向：模块「占用」的是「这把 1 由我写」这件事，
        //   所以只有「变成 0」（回锁方向的噪声）才忽略；
        //   **变成 1 一定是用户亲手开的**（模块不会在窗口内自己写 1），必须认。
        boolean occupying = sFollowing
                || (sOccupyUntil > 0L && System.currentTimeMillis() < sOccupyUntil);
        if (occupying && crV == 0) {
            sLastSeenCr = crV;
            LogUtil.logAlways("[用户意志] 跟随窗口内的设置变化（" + crV
                    + "）视为系统回推，不记为用户选择");
            return;
        }
        sLastSeenCr = crV;
        // ③ 外部改动 —— 用户亲手做的决定
        sUserIntent = crV != 0 ? 1 : 0;
        sOccupyUntil = 0L;
        persistInnerState();
        LogUtil.logAlways("[用户意志] 检测到手动改动：accelerometer_rotation=" + crV
                + "，accelerometer_rotation_inner=" + inV
                + " → 记为用户选择（" + (sUserIntent == 1 ? "自由旋转" : "锁定旋转") + "）");
        if (sFollowing) {
            cancelFollowTimer();
            LogUtil.logAlways("[用户意志] 已放弃本次跟随与回锁，不覆盖用户选择");
        }
        // 让 WMS 的锁定态立刻跟着用户的开关走，避免「开关显示已锁定、
        // 屏幕其实还能自由转」这种表里不一
        try {
            callWindowManagerRotation(sUserIntent == 0);
        } catch (Throwable th) {
            LogUtil.logAlways("[用户意志] 对齐 WMS 锁定态失败: " + th);
        }
        RotationHint.reconcileRotationHint("用户手动改旋转开关");
    }

    /**
     * 安装入口：由 Kotlin 侧在 installSystemUi 时调用。
     *
     * <p>原版把 {@code ensureScreenWatchers} / {@code ensureInnerBootstrap}
     * 分散在 onModuleLoaded、hook 回调等多个地方（"启动兜底"）。
     * 这里统一成一个入口，逻辑完全一致：拿得到 Context 就装，
     * 拿不到就按原版的 {@code postBootstrapRetry} 轮询。
     */
    public static void install() {
        ensureInnerBootstrap();
    }

    /** 触发一次完整判定（由折叠状态监听 / 亮屏 / 解锁调用）。 */
    public static void reevaluate(String reason) {
        LogUtil.logAlwaysOnce("inner-reeval-" + reason, "[内屏联动] 触发：" + reason);
        applyInnerFixedLandscape();
        applyInnerFixedLandscapeDeferred();
    }

    /** 解锁触发点（ACTION_USER_PRESENT）。 */
    public static void onUserPresent() {
        onInnerUnlocked();
    }

    /**
     * 供 RotationHint / 解锁路径判断「此刻是否应当放开旋转锁」。
     *
     * 🔴 2026-11-21 修复（用户：「展开内屏时不自动解除旋转锁定」）：
     *
     *   原实现是 `sUnfoldSettling || (sFollowing && 联动开关)`。
     *   但 `sUnfoldSettling` 在整个类里**只被赋过 false**（合上 / 熄屏 / 作废各一处），
     *   从来没有任何一处置 true —— 也就是说这一半条件恒为假。
     *
     *   而另一半 `sFollowing` 必须先通过本方法判断才会在展开/解锁路径里被置 true
     *   （见 onInnerUnlocked 的入口 `if (!isUnfoldWindowActive()) return;`）……
     *   于是形成循环依赖：**条件永远不成立 ⇒ 解锁路径永远提前 return ⇒
     *   解锁时的临时放开锁定从来没有生效过**。
     *
     *   正确语义不是「是否处于某个内部标志窗口」，而是
     *   **「现在是不是应当让内屏按传感器自由旋转」**，判据是：
     *     · 联动开关打开（用户同意模块接管），且
     *     · 当前确实是内屏，且
     *     · 亮屏可用（熄屏时放开锁定没有意义）。
     *   这与 onInnerUnlocked / 展开路径自身的其余守卫（用户意志、冷却、占用标记）
     *   互不冲突 —— 它们仍然各自把关。
     */
    public static boolean isUnfoldWindowActive() {
        if (!RotationHost.sInnerFollowRotation.get()) {
            return false;
        }
        if (sUnfoldSettling || sFollowing) {
            return true;
        }
        // ★ 只有「刚刚发生过展开/亮屏/解锁」才算窗口期，否则会把
        //   长时间正常使用内屏也当成窗口期（旋转旋钮会被无谓抑制）。
        boolean u = unfoldJustHappened();
        boolean so = inScreenOnWindow();
        boolean up = userPresentJustHappened();
        if (!u && !so && !up) {
            LogUtil.logAlwaysOnce("iw-none", "[内屏联动诊断] 窗口期不成立："
                    + "展开=" + u + " 亮屏=" + so + " 解锁=" + up
                    + "（展开距=" + age(sLastUnfoldAt) + "ms 亮屏剩="
                    + (sScreenOnUntil - android.os.SystemClock.uptimeMillis()) + "ms 解锁距="
                    + age(sLastUserPresentAt) + "ms）");
            return false;
        }
        Context ctx = sInnerCtx;
        if (ctx == null || !isInteractiveNow(ctx)) {
            LogUtil.logAlwaysOnce("iw-inactive", "[内屏联动诊断] 窗口期成立但 ctx=" + ctx
                    + " interactive=" + (ctx != null && isInteractiveNow(ctx)));
            return false;
        }
        boolean innerNow = isInnerDisplayActiveNow(ctx);
        LogUtil.logAlwaysOnce("iw-pass", "[内屏联动诊断] 窗口期成立 展开=" + u + " 亮屏=" + so
                + " 解锁=" + up + " 内屏=" + innerNow);
        return innerNow;
    }

    /** 距某时刻过了多少毫秒（0 表示未记录）。 */
    private static long age(long t) {
        return t <= 0L ? -1L : android.os.SystemClock.uptimeMillis() - t;
    }

    public static boolean isUnfoldSettling() {
        return sUnfoldSettling;
    }

    public static boolean isInnerScreenRemembered(Context ctx) {
        if (sInnerStateAt > 0L) {
            return sInnerActive;
        }
        boolean now = isInnerDisplayActiveNow(ctx);
        sInnerActive = now;
        sInnerStateAt = SystemClock.uptimeMillis();
        return now;
    }
}
