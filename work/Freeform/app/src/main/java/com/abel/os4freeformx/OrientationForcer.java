package com.abel.os4freeformx;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;

import java.lang.reflect.Method;

/**
 * 「点击旋钮 = 强制旋转」的执行器（v3.292.0，用户 2026-10-29 要求）。
 *
 * <h2>为什么原来点了没反应</h2>
 * 旋钮点击（{@code RotationHint#applyRotationSuggestion}）原来只调
 * {@code IWindowManager#freezeRotation(rotation, pkg)}。这条路径改的是
 * <b>「用户旋转」</b>（{@code mUserRotation}），而 WMS 决定屏幕最终朝哪边时
 * 优先级是：
 * <pre>
 *   应用请求的固定方向（ActivityInfo.screenOrientation）
 *     &gt; 用户旋转 / 旋转锁
 * </pre>
 * 外屏上大量应用在清单里把方向写死成 {@code portrait}，于是「用户旋转」
 * 永远被应用盖掉 —— 点击旋钮看起来完全没反应。
 * 这不是落点算错，也不是 freezeRotation 调用失败（日志里它会打印成功，特别迷惑）。
 *
 * <h2>怎么强制（本机逐条实测过的结论，2026-10-29，lhasa / OS4.0 / Android 17）</h2>
 * 系统里有两条「忽略应用方向请求」的开关，但只有第二条真管用：
 * <ol>
 *   <li>{@code wm set-ignore-orientation-request true}
 *       → {@code IWindowManager#setIgnoreOrientationRequest}。
 *       <b>在本机是空转</b>：命令返回成功、{@code get} 也读得到 true，
 *       但 WMS 里被 {@code config_ignoreOrientationRequest} 挡着不生效。
 *       实测「强制竖屏窗口 + 用户旋转锁到 1」仍然是 0 —— 所以只能当兜底。</li>
 *   <li>{@code wm fixed-to-user-rotation enabled}
 *       → {@code IWindowManager#setFixedToUserRotation(displayId, ENABLED)}。
 *       <b>本机实测有效</b>：同样的强制竖屏窗口，打开后立刻从 0 转到 1；
 *       关掉又回到 0。它改的是 {@code DisplayContent#mFixedToUserRotation}，
 *       打开期间 WMS 计算方向时只认用户旋转/传感器，不看应用请求。</li>
 * </ol>
 *
 * <p>所以本类是三级自适应，<b>只有前一级验证失败才升级</b>，
 * 尽量少动全局状态：
 * <ol>
 *   <li>先按老路径锁方向（{@code setRotationLockForFold} + {@code freezeRotation}）——
 *       对本身支持该方向的应用，一步就够，全局开关都不用碰；</li>
 *   <li>{@value #VERIFY_DELAY_MS}ms 后校验；还没转过去 → 打开
 *       {@code fixed-to-user-rotation}，再锁一次；</li>
 *   <li>仍不行 → 再打开 {@code ignore-orientation-request}，并用
 *       {@code thawRotation + freezeRotation} 顶一下
 *       （有些 ROM 只在「用户旋转值变化」时才走 updateOrientation）。</li>
 * </ol>
 *
 * <h2>什么时候放开</h2>
 * 全局开关不能常开，否则所有应用（相机这类自己管方向的）都会跟着转。
 * 所以只在「点旋钮」到「离开这个应用」之间保持，释放条件：
 * <ul>
 *   <li>前台包名连续两次（≈2.4s）不是强制时的那个包 → 用户已经走了；</li>
 *   <li>熄屏；</li>
 *   <li>设置页把「强制旋转」或「旋转提示」关掉；</li>
 *   <li>兜底超时 10 分钟。</li>
 * </ul>
 * 另外模块进程每次启动都会先清一次残留开关：这两个标志在 <b>system_server</b> 里，
 * SystemUI 被杀（或模块被停用）时不会跟着复位，不清就会「所有应用忽然都能转」。
 */
final class OrientationForcer {

    private static final String TAG = "[强制旋转]";

    private static final Handler sMain = new Handler(Looper.getMainLooper());

    /** fixed-to-user-rotation 的取值（与 WMS 的 FIXED_TO_USER_ROTATION_* 一致）。 */
    private static final int FTR_DEFAULT = 0;
    private static final int FTR_ENABLED = 2;

    /** 是否已打开 fixed-to-user-rotation。 */
    private static volatile boolean sFixedOn = false;
    /** 是否已打开 ignore-orientation-request（本机空转，仅兜底）。 */
    private static volatile boolean sIgnoreOn = false;
    /** 强制中的目标方向（0/1/2/3）；-1 = 当前没有强制。 */
    private static volatile int sTarget = -1;
    /** 强制开始时的前台包名，用于判断「用户已离开该应用」。 */
    private static volatile String sForcedPkg;
    /** 强制开始时刻（uptimeMillis）。 */
    private static volatile long sStartAt = 0L;
    /** 上一次轮询看到的不同包名（连续两次才认定真的切换了）。 */
    private static volatile String sOtherPkg;
    /** 看门狗是否已在跑（避免重复 post）。 */
    private static volatile boolean sWatchdogRunning = false;
    /** SystemUI 的 Context（只在 force() 时更新，用于轮询熄屏/前台包名）。 */
    private static volatile Context sCtx;
    /** 残留开关是否已经清理过（每次进程注入只清一次）。 */
    private static volatile boolean sStaleCleared = false;
    /** 上一次「第一级就成功」的校验流水号，用来合并重复请求。 */
    private static volatile int sVerifySeq = 0;
    /** 已确认转到目标方向（下次同方向点击直接返回，不再走 700ms 校验）。 */
    private static volatile boolean sReached = false;

    /** 校验延时：锁完方向后等这么久再看屏幕有没有转过去。 */
    private static final long VERIFY_DELAY_MS = 700L;
    /** 看门狗轮询间隔。 */
    private static final long WATCHDOG_INTERVAL_MS = 1200L;
    /**
     * 强制状态的兜底超时。
     *
     * <p>🔴 v3.300.9（用户 2026-11-17）：原来是 <b>10 分钟</b>，这是
     * 「旋转锁关闭后应用都不自动转」「视频回到强制旋转」「桌面图标错位」
     * 的直接原因 —— 点过一次旋钮后，{@code ignore-orientation-request} 与
     * {@code fixed-to-user-rotation} 会常驻开启长达 10 分钟，期间**所有**应用
     * 的方向请求都被压掉，系统自己的旋转流程完全失效。
     *
     * <p>用户定的规则是「能转就别管，转不动才介入」，介入必须是<b>短时</b>的。
     * 12 秒足够覆盖一次旋钮操作的校验（700ms）+ 系统转屏过渡，
     * 之后立即交还控制权给系统。
     */
    private static final long FORCE_MAX_MS = 12L * 1000L;

    private OrientationForcer() {
    }

    /** 当前是否处于强制状态。 */
    static boolean isActive() {
        return sTarget >= 0;
    }

    /** 强制中的目标方向；未强制返回 -1。 */
    static int activeTarget() {
        return sTarget;
    }

    /**
     * 把屏幕强制转到 {@code targetRotation}。
     *
     * @param ctx            SystemUI 的 Context
     * @param targetRotation 目标方向（与 Surface.ROTATION_* 同义：0/1/2/3）
     * @param reason         日志用的原因
     * @return 是否成功发起了强制（最终是否转过去看日志）
     */
    static boolean force(final Context ctx, final int targetRotation, final String reason) {
        if (ctx == null || targetRotation < 0 || targetRotation > 3) {
            LogUtil.logAlways(TAG + " 参数非法：ctx=" + ctx + " target=" + targetRotation);
            return false;
        }
        // 🔴 v3.300.9（用户 2026-11-17 规则）：桌面上永远不介入。
        //
        //   用户反馈「有时候桌面也被旋转了，图标都是错位的」——
        //   桌面本身会按系统旋转自行重排，模块一旦对它打开
        //   ignore-orientation-request，桌面的方向请求被压掉，
        //   图标网格就会错位。桌面属于"系统自己能转"的典型，
        //   按用户规则（能转就别管）必须完全排除。
        String topNow = normalizePkg(topPackage(ctx));
        if (topNow != null && topNow.startsWith("com.miui.home")) {
            LogUtil.logAlways(TAG + " 当前在桌面（" + topNow + "），不介入旋转");
            return false;
        }
        // 同一方向、上一次已经转到位、且还在强制期内 → 立即成功返回，
        // 不再走一级锁 + 700ms 校验（用户重复点同一个方向时零等待）
        if (targetRotation == sTarget && sReached && isActive()) {
            LogUtil.logAlways(TAG + " 已处于 rotation=" + targetRotation + "，无需重复强制");
            startWatchdog();
            return true;
        }
        // 🔑 v3.294.0：锁定态下的点击不做任何状态重置。
        //   原来这里每次都重设 sStartAt / sOtherPkg（看门狗的「已切走应用」判据），
        //   连着点两次就等于把「这个应用已经被强制」这件事忘掉，
        //   第一次点击会在 700ms 校验窗口里被第二次点击打断 —— 表现为「第一次很慢」。
        sCtx = ctx;
        final int previous = sTarget;
        sTarget = targetRotation;
        if (previous != targetRotation) {
            // 换成另一个方向才算「新的一次强制」：重新计超时与应用基线
            sStartAt = SystemClock.uptimeMillis();
            sOtherPkg = null;
            sForcedPkg = normalizePkg(topPackage(ctx));
        } else {
            sForcedPkg = null;      // 同一方向重复点击：不重置基线
        }
        // ═══ 临时诊断 ═══
        final boolean diagOuter = isOuterScreen(ctx);
        final int diagBefore = currentRotation(ctx);
        LogUtil.logAlways("[诊断] 下发 isFold=" + diagOuter + "（true=外屏/false=内屏）"
                + " target=" + targetRotation
                + " 下发前屏幕rotation=" + diagBefore
                + " 内屏记忆=" + InnerRotationHost.isInnerScreenRemembered(ctx));
        // ═══ 诊断结束 ═══
        LogUtil.logAlways(TAG + " 开始强制 → rotation=" + targetRotation
                + "（" + reason + "，前台=" + sForcedPkg
                + "，外屏=" + diagOuter + "）");

        // ① 一级：只锁方向（支持该方向的应用到这一步就转过去了）
        final boolean locked = lockRotation(ctx, targetRotation);

        // ② 校验：**只记录，不追加任何改写**。
        //
        //   🔑 v3.294.6（用户 2026-11-12）："根据旋钮方向进行屏幕旋转就可以了，
        //   不用再去读取什么东西"。方向已经由 ① 精确下发到对应屏，
        //   剩下的交给系统完成过渡。校验存在的唯一意义是写日志 / 兜底告警，
        //   它绝不能再去改 fixed-to-user-rotation / ignore-orientation-request /
        //   thaw+freeze —— 那些会互相覆盖，实测正是"转了之后被顶回去"的成因。
        final int seq = ++sVerifySeq;
        sReached = false;
        sMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (seq != sVerifySeq) {
                    return;
                }
                verify(ctx, targetRotation, 1);
            }
        }, locked ? VERIFY_DELAY_MS : 0L);

        startWatchdog();
        return true;
    }

    /**
     * 逐级校验 / 升级。
     *
     * @param level 已经用过的最高级别：1=只锁方向 2=已开 fixed-to-user-rotation
     *              3=已开 ignore-orientation-request
     */
    private static void verify(final Context ctx, final int targetRotation, final int level) {
        if (sTarget != targetRotation) {
            return;      // 期间又被点了别的方向 / 已经释放
        }
        int now = currentRotation(ctx);
        // ═══ 临时诊断 ═══
        LogUtil.logAlways("[诊断] 校验 期望=" + targetRotation + " 实际=" + now
                + (now == targetRotation ? " ✔一致" : " ✘不一致"));
        // ═══ 诊断结束 ═══
        if (now == targetRotation) {
            sReached = true;
            LogUtil.logAlways(TAG + " ✅ 已转到 " + targetRotation + "（" + levelName(level) + "）");
            return;
        }
        switch (level) {
            case 1:
                // 🔑 v3.294.6 / v3.300.5：只记录，不做追加改写。
                //
                //   方向已经由 setRotationLockForFold 按 isFold 精确下发到对应那块屏
                //   （外屏 isFold=true，内屏 isFold=false），剩下的交给系统过渡。
                //   校验只用于日志诊断。
                //
                //   ⚠️ 别再在这里补 setIgnoreOrientationRequest：实测本机该接口
                //   对**外屏 (Display 0) 完全不响应**（-d 0 恒为 false），
                //   对内屏 (Display 1) 才有效。加了只会制造"日志成功但没效果"的假象。
                LogUtil.logAlways(TAG + " 校验：期望=" + targetRotation + " 当前=" + now
                        + (now == targetRotation ? " ✔一致" : " ✘不一致（仅供诊断）"));
                sReached = (now == targetRotation);
                return;
            default:
                LogUtil.logAlways(TAG + " ❌ 仍然没转过去（当前=" + now + "，目标="
                        + targetRotation + "），该 ROM 可能还有别的方向来源");
                break;
        }
    }

    /** 第三级：ignore-orientation-request + thaw/freeze 兜底。 */
    private static void step3(final Context ctx, final int targetRotation) {
        setIgnoreOrientation(ctx, true);
        nudge(ctx, targetRotation);
        sMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                verify(ctx, targetRotation, 3);
            }
        }, 700L);
    }

    private static String levelName(int level) {
        switch (level) {
            case 1:
                return "应用本身支持该方向";
            case 2:
                return "fixed-to-user-rotation";
            default:
                return "ignore-orientation-request + thaw/freeze";
        }
    }

    /**
     * 释放强制状态：把两条全局开关都还回去。
     *
     * <p>不改变当前的方向锁定状态：锁在哪个角度就留在哪个角度 ——
     * 这和系统「接受旋转建议」的语义一致。
     */
    static void release(final String reason) {
        boolean had = sTarget >= 0 || sFixedOn || sIgnoreOn;
        sTarget = -1;
        sReached = false;
        sVerifySeq++;
        sForcedPkg = null;
        sOtherPkg = null;
        stopWatchdog();
        Context ctx = sCtx;
        if (ctx != null) {
            if (sFixedOn) {
                setFixedToUserRotation(ctx, false);
            }
            if (sIgnoreOn) {
                setIgnoreOrientation(ctx, false);
            }
        }
        if (had) {
            LogUtil.logAlways(TAG + " 已退出强制状态（" + reason + "）");
        }
    }

    /**
     * 进程启动时清理残留的全局开关。
     *
     * <p>这两个标志都在 system_server 里，SystemUI 被杀 / 模块被停用时不会复位，
     * 表现就是「所有应用忽然都能转了」。所以每次注入都先清一次。
     */
    static void clearStale(final Context ctx) {
        if (ctx == null) {
            return;
        }
        sCtx = ctx;
        if (sStaleCleared) {
            return;      // 每次注入只清一次，别反复打扰 WMS
        }
        sStaleCleared = true;
        sMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isActive()) {
                    return;      // 本次进程里已经在强制中，别自打自脸
                }
                boolean a = setFixedToUserRotation(ctx, false);
                boolean b = setIgnoreOrientation(ctx, false);
                LogUtil.logAlways(TAG + " 启动清理：fixed-to-user-rotation="
                        + (a ? "已置 default" : "失败")
                        + "，ignore-orientation-request="
                        + (b ? "已置 false" : "失败")
                        + "，前台探测=" + topPackage(ctx));
            }
        }, 3000L);
    }

    // ── 底层调用 ─────────────────────────────────────────────────────────

    /**
     * 把屏幕锁到指定方向。
     *
     * <p>两条路都走，谁通用谁：
     * <ol>
     *   <li>折叠屏专用 {@code setRotationLockForFold(locked, rotation, isFold)}
     *       —— 本机实测第三个参数 true=外屏、false=内屏；</li>
     *   <li>通用 {@code freezeRotation(rotation, pkg)}。</li>
     * </ol>
     */
    private static boolean lockRotation(Context ctx, int targetRotation) {
        Object wm = RotationHost.getWindowManagerServicePublic();
        if (wm == null) {
            LogUtil.logAlways(TAG + " 拿不到 IWindowManager，锁方向失败");
            return false;
        }
        // 🔴 v3.301.1（用户 2026-11-17 决定）：**外屏旋转完全交还系统**。
        //
        //   用户原话：「先把外屏所有关于旋转的功能都移除掉，包括旋钮、强制旋转
        //   等等，全部移除掉，全部交还给系统。」
        //
        //   外屏这块此前反复出问题（点了旋钮只有单个应用转、切应用变横屏、
        //   桌面图标错位、旋转锁关了还不转），根因是外屏的旋转策略
        //   （fixed-to-user-rotation / ignore-orientation-request）会压掉
        //   系统自身的旋转流程，而那个流程本来是好的。
        //
        //   所以这里直接放弃：外屏不锁方向、不开任何策略，
        //   完全由系统 + 应用自己决定。内屏逻辑不受影响。
        if (isOuterScreen(ctx)) {
            LogUtil.logAlways(TAG + " 当前是外屏，旋转完全交还系统，模块不介入");
            return false;
        }
        boolean any = false;
        boolean isOuter = isOuterScreen(ctx);
        try {
            Method m = wm.getClass().getMethod("setRotationLockForFold",
                    boolean.class, int.class, boolean.class);
            m.setAccessible(true);
            m.invoke(wm, true, targetRotation, isOuter);
            LogUtil.logAlways(TAG + " setRotationLockForFold(true, " + targetRotation
                    + ", isFold=" + isOuter + ") 成功");
            any = true;
        } catch (Throwable th) {
            LogUtil.logAlways(TAG + " setRotationLockForFold 不可用: " + th);
        }
        // ⚠️ v3.294.3：freezeRotation 作用在「当前活动显示」上，折叠屏切换的
        //   过渡期可能落到另一块屏，甚至把刚设好的内屏方向又改回去。
        //   setRotationLockForFold 已经按 isFold 精确指定了目标屏 —— 它成功时
        //   **不再补 freezeRotation**，避免两条路径互相覆盖（这是「有时能转、
        //   有时转不动」的第三个成因）。
        if (!any) {
            try {
                Method m = wm.getClass().getMethod("freezeRotation", int.class, String.class);
                m.setAccessible(true);
                m.invoke(wm, targetRotation, "HyperOSGlass");
                LogUtil.logAlways(TAG + " freezeRotation(" + targetRotation + ") 成功（回退路径）");
                any = true;
            } catch (Throwable th) {
                LogUtil.logAlways(TAG + " freezeRotation 失败: " + th);
            }
        }
        return any;
    }

    /** 先解冻再锁回：逼 WMS 重新算一次方向（有些 ROM 只在「用户旋转变化」时才更新）。 */
    private static void nudge(Context ctx, int targetRotation) {
        Object wm = RotationHost.getWindowManagerServicePublic();
        if (wm == null) {
            return;
        }
        try {
            Method m = wm.getClass().getMethod("thawRotation", String.class);
            m.setAccessible(true);
            m.invoke(wm, "HyperOSGlass-force");
            LogUtil.logAlways(TAG + " thawRotation 完成");
        } catch (Throwable th) {
            LogUtil.logAlways(TAG + " thawRotation 失败: " + th);
        }
        lockRotation(ctx, targetRotation);
    }

    /**
     * 打开 / 关闭「强制跟随用户旋转」——本机真正生效的那条路。
     *
     * <p>签名有历史版本差异：新的是 {@code (int displayId, int value)}
     * （0=default / 1=disabled / 2=enabled / 3=enabled_if_no_auto_rotation），
     * 老的是 {@code (int displayId, boolean)}，这里都试一遍。
     */
    private static boolean setFixedToUserRotation(Context ctx, boolean enabled) {
        Object wm = RotationHost.getWindowManagerServicePublic();
        if (wm == null) {
            return false;
        }
        int displayId = currentDisplayId(ctx);
        Throwable last = null;
        try {
            Method m = wm.getClass().getMethod("setFixedToUserRotation",
                    int.class, int.class);
            m.setAccessible(true);
            m.invoke(wm, displayId, enabled ? FTR_ENABLED : FTR_DEFAULT);
            sFixedOn = enabled;
            LogUtil.logAlways(TAG + " setFixedToUserRotation(" + displayId + ", "
                    + (enabled ? "enabled" : "default") + ") 成功");
            return true;
        } catch (Throwable th) {
            last = th;
        }
        try {
            Method m = wm.getClass().getMethod("setFixedToUserRotation",
                    int.class, boolean.class);
            m.setAccessible(true);
            m.invoke(wm, displayId, enabled);
            sFixedOn = enabled;
            LogUtil.logAlways(TAG + " setFixedToUserRotation(" + displayId + ", "
                    + enabled + ") 成功（boolean 版本）");
            return true;
        } catch (Throwable th) {
            last = th;
        }
        LogUtil.logAlways(TAG + " setFixedToUserRotation(" + enabled + ") 失败: " + last);
        return false;
    }

    /**
     * 打开 / 关闭「忽略应用方向请求」（本机空转，仅兜底）。
     *
     * <p>签名同样有两个版本，先试带 displayId 的。
     */
    private static boolean setIgnoreOrientation(Context ctx, boolean ignore) {
        Object wm = RotationHost.getWindowManagerServicePublic();
        if (wm == null) {
            return false;
        }
        int displayId = currentDisplayId(ctx);
        try {
            Method m = wm.getClass().getMethod("setIgnoreOrientationRequest",
                    int.class, boolean.class);
            m.setAccessible(true);
            m.invoke(wm, displayId, ignore);
            sIgnoreOn = ignore;
            return true;
        } catch (Throwable ignored) {
        }
        try {
            Method m = wm.getClass().getMethod("setIgnoreOrientationRequest", boolean.class);
            m.setAccessible(true);
            m.invoke(wm, ignore);
            sIgnoreOn = ignore;
            return true;
        } catch (Throwable th) {
            LogUtil.logAlways(TAG + " setIgnoreOrientationRequest(" + ignore + ") 失败: " + th);
            return false;
        }
    }

    // ── 看门狗 ────────────────────────────────────────────────────────────

    private static final Runnable sWatchdog = new Runnable() {
        @Override
        public void run() {
            if (sTarget < 0) {
                sWatchdogRunning = false;
                return;
            }
            try {
                if (!RotationHost.sForceRotateEnable.get()
                        || !RotationHost.sRotationHintEnable.get()) {
                    release("开关已关闭");
                    return;
                }
                Context ctx = sCtx;
                if (ctx == null) {
                    release("Context 已失效");
                    return;
                }
                if (!isInteractive(ctx)) {
                    release("熄屏");
                    return;
                }
                if (SystemClock.uptimeMillis() - sStartAt > FORCE_MAX_MS) {
                    release("兜底超时");
                    return;
                }
                String top = normalizePkg(topPackage(ctx));
                if (top != null) {
                    if (sForcedPkg == null) {
                        // 点击那一刻没探到真正的前台（过渡态 / 探到自己）：
                        // 第一次探到真实应用时把它当基线，别把「首次探到」当成切换。
                        sForcedPkg = top;
                        sOtherPkg = null;
                    } else if (!top.equals(sForcedPkg)) {
                        // 连续两次看到「不是同一个应用」才释放：
                        // getRunningTasks 偶尔会返回 SystemUI 自己 / 空结果，
                        // 一次就放会导致刚点完旋钮立刻失效。
                        if (top.equals(sOtherPkg)) {
                            release("前台已切到 " + top);
                            return;
                        }
                        sOtherPkg = top;
                    } else {
                        sOtherPkg = null;
                    }
                }
            } catch (Throwable th) {
                LogUtil.logAlways(TAG + " 看门狗异常: " + th);
            }
            sMain.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    private static void startWatchdog() {
        if (sWatchdogRunning) {
            return;
        }
        sWatchdogRunning = true;
        sMain.removeCallbacks(sWatchdog);
        sMain.postDelayed(sWatchdog, WATCHDOG_INTERVAL_MS);
    }

    private static void stopWatchdog() {
        sWatchdogRunning = false;
        sMain.removeCallbacks(sWatchdog);
    }

    // ── 系统状态读取 ──────────────────────────────────────────────────────

    /** 当前内容屏的方向。 */
    /**
     * 当前<b>用户正在看的那块屏</b>的方向。
     *
     * <p>🔴 v3.294.2 关键修正：原来用 {@code ctx.getDisplay()} / {@code DEFAULT_DISPLAY}，
     * 而 {@link RotationHint#currentSurfaceRotation} 用的是「STATE_ON 的活跃屏」——
     * 折叠屏上这两者**不是同一块**：内屏展开时 DEFAULT_DISPLAY 可能仍是外屏状态，
     * 于是出现「校验读到 270 说成功了，用户看到的竖屏那块却纹丝不动」
     * （日志实测：{@code ✅ 已转到 3} 但屏幕没转）。
     *
     * <p>现在与显示判定使用同一套规则：优先 STATE_ON 的屏，取不到才回退，
     * 保证「校验的屏」就是「用户看的屏」。
     */
    static int currentRotation(Context ctx) {
        Display d = activeDisplay(ctx);
        return d == null ? -1 : d.getRotation();
    }

    /**
     * 供 {@link RotationHint} 复用的同一取屏口径：按折叠状态取内/外屏的方向。
     * 取不到返回 -1（调用方自行回退）。
     */
    static int rotationByFoldState(Context ctx) {
        Display d = innerFolded(ctx);
        return d == null ? -1 : d.getRotation();
    }

    /**
     * 按 {@code current_device_state} **明确指认**内/外屏。
     *
     * <p>🔴 v3.294.3：不要再靠「STATE_ON 的屏」去猜。折叠屏切换的过渡期里，
     * 内屏刚亮、外屏还没灭（或反过来），遍历 {@code getDisplays()} 会<b>先碰到
     * 另一半屏</b>，读到它的 0°，于是 {@code verify()} 认为「已经转到位」 ——
     * 这就是「日志说成功、屏幕却没转」的第二种成因（实测：内屏明明已是
     * {@code mDisplayRotation=ROTATION_270}，却读到了外屏的 0）。
     *
     * <p>规则与 {@link #isOuterScreen} 一致：{@code current_device_state == 3}
     * 表示内屏（Display 0 是内屏；外屏为 Display 1）。读不到状态时才退回默认屏。
     */
    private static Display activeDisplay(Context ctx) {
        Display active = innerFolded(ctx);
        if (active != null) {
            return active;
        }
        try {
            if (ctx != null) {
                return ctx.getDisplay();
            }
        } catch (Throwable unused) {
        }
        return null;
    }

    /**
     * 当前活跃屏的 DisplayId。
     *
     * <p>v3.294.2：与 {@link #currentRotation} 保持同一口径（STATE_ON 优先）。
     * 折叠屏上 {@code ctx.getDisplay()} 与「用户正看的屏」可能不一致，
     * 用错会把 fixed-to-user-rotation 设到另一块屏上。
     */
    private static int currentDisplayId(Context ctx) {
        Display d = activeDisplay(ctx);
        return d == null ? Display.DEFAULT_DISPLAY : d.getDisplayId();
    }

    /**
     * 当前是不是外屏。
     *
     * <p>{@code current_device_state}：3 = 展开（内屏），其它（0/1/2）= 折叠（外屏）。
     * 与 {@code RotationHint#isRotationLocked} 用的是同一套判定。
     */
    /**
     * 按 {@code current_device_state} 取内屏/外屏实例。
     *
     * <p>state == 3 → 内屏（Display 0）；其它（0 合上 / 其它过渡态）→ 外屏（Display 1，
     * 取不到时退回默认屏）。这是本机实测的端口映射：内屏 port 0，外屏 port 1。
     */
    private static Display innerFolded(Context ctx) {
        if (ctx == null) {
            return null;
        }
        // 🔴 v3.300.2：不再按 DisplayId 猜内/外屏。
        //   本机实测（2026-11-17）：展开时 Display 0 = 内屏、Display 1 = 外屏；
        //   **合上后两者互换**（Display 0 = 外屏 1168×1712，Display 1 = 内屏 1672×2364）。
        //   原实现写死 getDisplay(0/1)，外屏场景下会把方向下发到另一块屏 ——
        //   接口成功、日志成功、用户屏幕纹丝不动。
        //   改按物理面积认屏：面积大的是内屏（1672×2364 > 1168×1712），与 ID 无关。
        // 🔴 v3.301.1：外屏旋转已整体交还系统，
        //   取屏逻辑内联到这里。按用户指出的方式：先看折叠状态
        //   （current_device_state==3 内屏，其它外屏），再取 STATE_ON 的那块 ——
        //   用户正看着的屏必然点亮，与折叠状态天然一致，不需要猜尺寸。
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager)
                            ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                for (Display d : dm.getDisplays()) {
                    if (d != null && d.isValid() && d.getState() == Display.STATE_ON) {
                        return d;
                    }
                }
            }
        } catch (Throwable unused) {
        }
        return ctx.getDisplay();
    }

    /** 当前是否是外屏（current_device_state != 3）。 */
    /**
     * 当前是否外屏。
     *
     * <p>🔑 v3.294.4：改读 {@link RotationHost#isInnerScreenRemembered}（展开/合上边沿
     * 写下的状态值），不再实时查 {@code current_device_state} —— 过渡期它会短暂
     * 停留旧值，导致 isFold 传错、方向设到另一块屏上。
     */
    private static boolean isOuterScreen(Context ctx) {
        return !InnerRotationHost.isInnerScreenRemembered(ctx);
    }

    static boolean isInteractive(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm == null || pm.isInteractive();
        } catch (Throwable unused) {
            return true;
        }
    }

    /**
     * 包名归一化：把「我们自己」和「系统壳」当成无效值。
     *
     * <p>{@code getRunningTasks} 在过渡动画、下拉通知栏、锁屏这些时刻
     * 可能返回 {@code com.android.systemui} 或 {@code android}；
     * 这些都不代表用户真正在用哪个应用，参与「应用是否切换」的判定会误释放。
     */
    private static String normalizePkg(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return null;
        }
        if ("com.android.systemui".equals(pkg) || "android".equals(pkg)) {
            return null;
        }
        return pkg;
    }

    /** 前台应用包名（拿不到返回 null，此时不做「应用切换」判定）。 */
    @SuppressWarnings("deprecation")
    static String topPackage(Context ctx) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return null;
            }
            java.util.List<android.app.ActivityManager.RunningTaskInfo> tasks =
                    am.getRunningTasks(1);
            if (tasks == null || tasks.isEmpty()) {
                return null;
            }
            android.app.ActivityManager.RunningTaskInfo t = tasks.get(0);
            if (t.topActivity != null) {
                return t.topActivity.getPackageName();
            }
            if (t.baseActivity != null) {
                return t.baseActivity.getPackageName();
            }
        } catch (Throwable unused) {
        }
        return null;
    }
}
