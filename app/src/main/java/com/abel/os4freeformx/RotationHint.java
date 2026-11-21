package com.abel.os4freeformx;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Surface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 锁定方向时的旋转提示（v3.45.0）。
 *
 * <h2>需求</h2>
 * 手机锁定了屏幕方向（关闭「自动旋转」）时，如果**重力传感器感受到的方向**
 * 与**当前屏幕方向**不一致，就弹出安卓原生的手动旋转按钮，
 * 让用户一键把界面转过去。
 *
 * <h2>为什么是「原生按钮」而不是自己画一个</h2>
 * AOSP 本来就有这个功能，小米也**完整保留**了实现（REA 反编译 SystemUI 确认）：
 * <pre>
 *   com.android.systemui.shared.rotation.FloatingRotationButton          ← 按钮视图
 *   com.android.systemui.shared.rotation.RotationButtonController        ← 控制器
 *   com.android.systemui.shared.rotation.FloatingRotationButtonPositionCalculator
 * </pre>
 * {@code FloatingRotationButton} 自己用 {@code WindowManager} 悬浮显示，
 * 位置、图标（含四个方向）、点击旋转、超时隐藏全部由系统完成。
 * 所以这里**不重复造轮子**，只在合适的时机把它叫出来。
 *
 * <h2>系统为什么不自己弹</h2>
 * 原生逻辑是「系统建议你旋转时才弹」，而它判断的依据是
 * {@code RotationPolicyWrapper} 里的各种策略（OEM 开关、无障碍、车载模式等）。
 * 小米把这套策略调成了很保守 —— 实测锁屏方向后怎么转都不弹。
 * 用户要的就是把这个行为补回来：**方向真的不一致就弹**。
 *
 * <h2>实现链路</h2>
 * <pre>
 *   1) hook NavigationBarView.onFinishInflate()  → 拿到导航栏实例
 *   2) 调它的 public getRotationButtonController() → 拿到控制器
 *   3) 注册方向传感器（TYPE_DEVICE_ORIENTATION）
 *   4) 传感器方向 ≠ 屏幕方向 且 自动旋转已关闭 → 调 showAndLogRotationSuggestion()
 * </pre>
 *
 * <h2>安全性</h2>
 * <ul>
 *   <li>总开关默认关闭，用户开启后才工作</li>
 *   <li>只在「自动旋转已关闭」时生效 —— 自动旋转开着时系统自己会转，不必弹</li>
 *   <li>同一目标方向只提示一次，避免传感器抖动导致反复弹</li>
 *   <li>全部 try/catch，出错只记日志，绝不影响 SystemUI</li>
 * </ul>
 */
public final class RotationHint {

    /**
     * 设备方向传感器类型。
     *
     * <p>{@code Sensor.TYPE_DEVICE_ORIENTATION} 是 API 36 新增的 {@code @SystemApi}，
     * 编译用的 android.jar 里没有这个常量，所以直接写实测数值 ——
     * 本机 {@code dumpsys sensorservice} 确认它的 type 就是 27。
     * 这个值是设备侧的事实，不随 ROM 版本漂移。
     */
    private static final int SENSOR_TYPE_DEVICE_ORIENTATION = 27;

    /** 导航栏视图类（提供 getRotationButtonController） */
    private static final String NAV_BAR_CLASS =
            "com.android.systemui.navigationbar.views.NavigationBarView";

    /** 传感器回调到实际弹按钮之间的稳定延时（ms），过滤转动过程中的抖动 */
    /**
     * 方向「停稳」确认延时。
     *
     * <p>⚠️ 原来是 350ms —— 用户反馈「转动后要好几秒才生效」，
     * 这个等待本身就是主要延迟来源之一。转动时手会抖，需要一点去抖，
     * 但 350ms 太长；120ms（约 2 个 15Hz 采样周期）足够滤掉抖动，
     * 又不会让用户感到迟滞。
     */
    // 📌 已取消「停稳确认」等待（原 120ms）—— 用户要求取消所有延迟
    private static final long STABLE_DELAY_MS = 0L;
    /**
     * 「收→显」之间留的间隔（毫秒）。
     *
     * <p>同步连调会让位置瞬间跳变（生硬）；留出收起动画的时间后，
     * 位置切换会跟随系统自己的淡入淡出，视觉上平滑。
     * 90ms 是实测比较自然的取值 —— 再大会觉得卡顿，再小仍然生硬。
     */
    // 📌 收→显之间不再留间隔（原 90ms）—— 用户要求取消所有延迟
    private static final long RELOCATE_SHOW_DELAY_MS = 0L;

    /** 拿到的 RotationButtonController 实例（反射调用，避免编译期依赖） */
    private static volatile Object sController;
    /**
     * 导航栏 View 的 Context。
     *
     * <p>存下来是因为 {@code RotationHost.currentApplication()} 是私有方法用不了，
     * 而传感器注册与 Settings 读取都需要一个可靠 Context。
     * 导航栏自身的 Context 正好合适（它就是 SystemUI 的 Context）。
     */
    private static volatile Context sContext;
    /** 反射缓存：showAndLogRotationSuggestion() */
    private static volatile Method sShowMethod;
    /** 反射缓存：canShowRotationButton() */
    private static volatile Method sCanShowMethod;

    /** 传感器是否已注册 */
    private static volatile boolean sSensorRegistered;
    /**
     * 最近一次检测到「设备在运动」的时刻。
     *
     * <p>2026-10-22 用户建议：
     * <b>「如果是因为传感器效率的问题，给传感器增加一个短时间的轮询也可以，
     * 比如我们是 500ms 去决定方向，那么就进行环境内的轮询」</b>。
     *
     * <p>为什么需要它：{@code TYPE_DEVICE_ORIENTATION} 是 <b>on-change</b> 传感器，
     * 只在方向跨过边界时上报一次。实测高强度快速旋转时，两次上报间隔能到 3 秒以上，
     * 中间无论怎么转都不会有事件 —— 按钮就停在旧角度不动。
     *
     * <p>所以额外挂一路 <b>加速度计</b>（连续型，10Hz）只做一件事：
     * 判断设备是不是还在动。只要在动，按钮就收起、并推迟显示判定。
     */
    /** 上次安排显示判定的时刻，避免高频重排导致「永远不显示」。 */
    private static volatile long sLastScheduleAt = 0L;

    // ── 重力轮询（持续型，10Hz）──────────────────────────────────────
    //  2026-10-22 用户实测：TYPE_DEVICE_ORIENTATION 是 on-change 类型，
    //  实测会出现「卡在某个方向、转到其他三个方向都没反应，
    //  必须再路过那个方向才解锁」—— 因为期间传感器一个事件都不发。
    //
    //  所以额外挂一路持续型的重力传感器，用标准公式自己算设备朝向：
    //      roll = atan2(gx, gy)          // 设备绕屏幕法线的转角
    //      rotation = (round(roll/90) + 4) % 4
    //  验证：roll 0→0、-90→3(270)、180→2、+90→1(90)，与 Android 定义一致。
    //
    //  为避免抖动，要求**连续 3 次（约 300ms）算出同一方向**才采纳。
    private static volatile float sLastGx = 0f;
    private static volatile float sLastGy = 0f;
    /** 方向传感器最后一次上报的时刻。轮询只在它沉默时兜底。 */
    private static volatile long sLastSensorReportAt = 0L;
    private static volatile int sPendingAccelRotation = -1;
    private static volatile int sAccelSameCount = 0;
    private static volatile SensorEventListener sAccelListener;
    /** 轮询的兜底停止任务（单例，避免累积）。 */
    private static final Runnable sPollStopTask = new Runnable() {
        @Override
        public void run() {
            stopGravityPolling();
        }
    };
    private static volatile float sLastGz = 0f;
    /** 最近一次检测到运动的时刻（用于判断「这一轮旋转是否结束」）。 */
    private static volatile long sLastMotionAt = 0L;
    /** 运动判定阈值（三轴变化量之和）。取值教训见下：太小会被噪声一直触发。 */
    private static final float MOTION_THRESHOLD = 3.5f;



    /** 已注册的传感器监听器与 SensorManager，用于注销（省电）。 */
    private static volatile SensorEventListener sListener;
    private static volatile SensorManager sSensorMgr;
    /** 系统同款的 OrientationEventListener（主路径）。 */
    private static volatile android.view.OrientationEventListener sOel;
    /** OEL 回调计数（排查上报频率用）。 */
    private static volatile long sOelTicks = 0L;
    /** 上一次打印的落点摘要，用于热路径日志去重。 */
    private static volatile String sLastPositionLog = "";
    /** 主线程 Handler，用于延时去抖 */
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    /** 最近一次已提示过的目标方向（Surface.ROTATION_*），-1 表示未提示 */
    private static volatile int sLastHintedRotation = -1;
    /**
     * 目标方向要连续出现多少次才算「真的转了」。
     *
     * <p>2026-10-21 用户反馈：旋转锁定开启时，转到别的方向再立即旋回来，
     * 在当前方向上也会闪一下旋转按钮。
     *
     * <p>根因：传感器读数在临界角度附近会短暂跳变 —— 明明已经旋回竖屏，
     * 中间却有一两帧被算成横屏，于是「目标方向 ≠ 当前方向」成立、弹了按钮，
     * 下一帧又恢复一致、按钮收回。
     *
     * <p>3 次 ≈ 200ms（采样 66.7ms），足以滤掉抖动，用户也感知不到延迟。
     */
    /**
     * 方向稳定多久之后才认为「用户确实停在这个方向」。
     *
     * <p>2026-10-21 用户要求：「增加一个延迟，使得其稳定，判断稳定之后再出现旋钮。
     * 因为正常需要旋转到对应方向，一定会在对应方向多做一些停留」。
     *
     * <p>⚠️ 必须按**时间**判断，不能按「连续 N 次相同」——
     * 快速转动时传感器会途经中间方向（0→90→180），
     * 计数会被反复重置，导致按钮停在旧位置不动。
     */
    /**
     * 稳态时长。
     *
     * <p>2026-10-22 实测（baselines/rotation-timing-20261022.log）：
     * 取 800ms 时，从「传感器方向变化」到「旋钮真正出现」的实测延迟是
     * <b>中位数 862ms、全部样本落在 855–874ms</b> —— 完全是这个常量决定的，
     * 没有方向差异（270° 的中位数 844ms 反而更快，只是有两次异常值拉高了均值）。
     *
     * <p>用户要的原始值是 500ms，延迟会降到约 560ms。
     */
    /**
     * 稳态时长。
     *
     * <p>2026-10-22 用户实测反馈「多转几次后延迟比较高，跟不上传感器」。
     *
     * <p>关键认识：{@code TYPE_DEVICE_ORIENTATION} 是 on-change 传感器，
     * <b>它上报一次就代表方向已经跨过边界、进入新的一格</b>，
     * 连续上报说明人还在转。所以这里的等待只需要覆盖「连续上报之间的间隔」，
     * 用来防止转动过程中按钮乱闪，不需要等很久。
     *
     * <p>取值过程：800 → 500 → 380 → <b>180</b>。
     * 180ms 足以滤掉转动过程中的连续上报，又不至于让人觉得「跟不上」。
     */
    private static final long STABLE_MS = 180L;
    /** 当前候选方向。 */
    private static volatile int sStableTarget = -1;
    /**
     * 当前按钮是为哪个方向显示的；-1 表示当前没显示。
     *
     * <p>2026-10-21 用户反馈「按钮不停地刷新动画」：因为 maybeShowHint 会被
     * 高频调用（传感器每次回调 + 多次 postDelayed 复查），每次都调
     * {@code showAndLogRotationSuggestion()}，系统就会反复播放显示动画。
     * 用这个字段去重 —— 同一方向只显示一次。
     */
    private static volatile int sShownFor = -1;
    /**
     * 上一次真正播放显示动画的时间戳。
     *
     * <p>2026-10-21 用户反馈「动画有时候会重复，显示了两次」：
     * 传感器在临界角度抖动时，方向会「变走 → 又变回」，
     * 每次都触发一次「隐藏 → 显示」，动画就重复播。
     * 用最小间隔挡住这种连播。
     */
    private static volatile long sLastShowAt = 0L;
    /** 该候选方向是从什么时候开始持续的（uptimeMillis）。 */
    private static volatile long sStableSince = 0L;
    /**
     * 挂起中的「停稳后显示」任务。
     *
     * <p>2026-10-21 用户反馈「旋转时的延迟要优化」——
     * 原来每次方向变化都 postDelayed 一个新任务，快速旋转时任务会堆积几十个，
     * 每个到点都去调一次显示，既延迟又让动画反复播。
     * 这里保存引用，变化时先撤掉旧的，保证任何时刻只有一个是活的。
     */
    private static volatile Runnable sPendingShow = null;
    /**
     * 「这次显示请求是我们自己发起的」标志。
     *
     * <p>2026-10-21 用户实测「按钮该出现时不出现」——因为 {@code maybeShowHint}
     * 调系统显示接口时，请求同样会穿过我们自己的显隐 hook，
     * 而那一刻判据可能还没满足（时间差一点、方向值刚更新等），
     * 于是被自己拦掉。
     *
     * <p>加这个标志后：**我们主动请求的显示一律放行**，
     * hook 只拦「系统自发」的显示。
     */
    private static volatile boolean sOurShowRequest = false;
    /** 传感器此刻感知到的手机物理姿态（Surface.ROTATION_*），落点与显示判断都用它。 */
    private static volatile int sSensorTargetRotation = -1;
    /**
     * 这个时刻之前，忽略「方向变化 → 收起旋钮」。
     *
     * <p>只由「用户点击了旋钮」置位：点击代表意图已经明确，接下来屏幕自己会转，
     * 期间任何传感器尾帧都不该再把按钮拉走。
     */
    private static volatile long sSuppressedUntil = 0L;

    /**
     * 按钮当前**视觉上代表的方向**快照（v3.294.7）。
     *
     * <p>只在 {@link #showNow} 真正把按钮画出来时写入，之后传感器怎么抖都不改它。
     * 点击时读这个值，保证「看到哪个方向就转到哪个方向」。
     */
    private static volatile int sShownForAt = -1;
    /** 点击后的静默时长：够系统转完 + 校验一次，又不至于把后续真实旋转吞掉。 */
    private static final long CLICK_QUIET_MS = 700L;
    /**
     * 显示旋钮前要求方向稳定的时长（v3.294.1，用户 2026-11-12 要求）。
     *
     * <p>太短会被传感器抖动和「拿手机时的过渡姿态」骗到；太长用户会觉得慢。
     * 180ms 与原来 STABLE_MS 一致，是实测过「能滤掉转动过程、又不嫌迟滞」的值，
     * 这里把它推广到所有显示入口（不再只用于传感器上报路径）。
     */
    private static final long HINT_STABLE_MS = 180L;
    /** 稳态复检任务（单例，避免堆积）。 */
    private static volatile Runnable sStableRecheck;
    /** 屏幕锁定方向（Surface.ROTATION_*），0/1/2/3。用于把角落顺时针补转。 */
    private static volatile int sScreenRotation = -1;
    /** 系统 FloatingRotationButton 实例 */
    private static volatile Object sRotationButton;
    /** 去抖：待确认方向 */
    private static volatile int sPendingRotation = -1;
    /** 去抖 token 与等待时长 */
    private static final Object STABLE_TOKEN = new Object();

    private RotationHint() {
    }

    /** 挂载到 SystemUI 进程。 */
    public static void install(final io.github.libxposed.api.XposedModule module, ClassLoader cl) {
        try {
            Class<?> cls = Class.forName(NAV_BAR_CLASS, false, cl);
            // ⚠️ 光挂 onFinishInflate 不够：导航栏在 SystemUI **启动早期**就建好了，
            //    而模块注入发生在进程启动后 —— 实测挂在 onFinishInflate 上时，
            //    那次调用早就过去了，永远拿不到实例，日志里只剩「已挂钩」一条。
            //    所以三个生命周期都挂，任何一个先到都能拿到：
            //      · onAttachedToWindow    —— 每次加入窗口时
            //      · onFinishInflate       —— 视图重建时（换分辨率/换屏）
            //      · onConfigurationChanged—— 方向变化时必经 ⭐
            //    captureController 内部有「已拿到就不重复」的短路，多挂不会重复注册。
            int hooked = 0;
            hooked += hookNavBarMethod(module, cls, "onFinishInflate");
            hooked += hookNavBarMethod(module, cls, "onAttachedToWindow");
            hooked += hookNavBarMethod(module, cls, "onConfigurationChanged");
            LogUtil.logAlways("[旋转提示] 已挂钩 " + NAV_BAR_CLASS + " 的 " + hooked + " 个生命周期方法");

            // 只改系统旋转按钮的落点（图标/动效/时机全部保留原生）
            installPositionHook(module, cl);
            // 📌 2026-10-22 重新启用（这次已查清系统真实实现）：
            //   系统自己会监听旋转并显示按钮，绕过我们的 500ms 稳态判断
            //   （用户实测「转到另一边马上就出现」）。
            //   所以必须在系统入口处拦下来，但**不能简单丢弃**（那会让系统状态
            //   错乱、按钮再也不出现）—— 改成「吞掉这次，记下待显示，
            //   由我们自己的稳态逻辑在 500ms 后调 setState(true)」。
            installVisibilityHook(module, cl);

            // 点击旋钮 → 把屏幕转到传感器当前朝向（接受旋转建议）
            RotationButtonOverlay.get().setOnClick(new Runnable() {
                @Override
                public void run() {
                    applyRotationSuggestion();
                }
            });

            // ⚠️ 还差最后一块：上面三个回调都要等「导航栏下次动」才触发，
            //    而 SystemUI 启动后导航栏可能长时间不重建 —— 那样传感器永远注册不上。
            //    所以这里**主动去现有视图树里捞一次**（延迟几秒，等 SystemUI 建完视图）。
            sMain.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        scanExistingNavBar(cls);
                    } catch (Throwable unused2) {
                        // 扫描失败就等生命周期回调，不影响其他功能
                    }
                }
            }, 4000L);

            // 🔑 2026-11-21（用户：「锁屏解锁之后，旋钮一直都是关闭状态，没有自动打开」）：
            //
            //   锁屏时 `isKeyguardShowing` 为真 ⇒ 旋钮被 hideButtonNow() 收起（这是对的，
            //   旋钮会盖住锁屏内容）。但**解锁后没有任何事件触发重算** ——
            //   reconcileRotationHint 只挂在「方向变化 / 锁开关变化 / 屏幕移除」上，
            //   解锁这几件事一个都不沾 ⇒ 旋钮一直保持收起状态，直到下次转屏才出现。
            //
            //   这里监听解锁广播，解锁瞬间重算一次。用 ACTION_USER_PRESENT
            //   （用户真正解锁，比 SCREEN_ON 准确得多：亮屏但没解锁也会发 SCREEN_ON）。
            registerUnlockReceiver();
        } catch (Throwable th) {
            LogUtil.logAlways("[旋转提示] 挂钩失败: " + th);
        }
    }

    /** 注册解锁监听：解锁后重算一次旋钮（锁屏期间旋钮是故意收起的）。 */
    private static void registerUnlockReceiver() {
        try {
            if (sUnlockReceiver != null) return;
            final Context ctx = sContext;
            if (ctx == null) {
                // 上下文还没拿到（captureController 尚未命中）：最多重试 15 次（30 秒），
                // 避免导航栏一直没被捕获时无限排队。
                if (sUnlockRetry < 15) {
                    sUnlockRetry++;
                    sMain.postDelayed(new Runnable() {
                        @Override public void run() { registerUnlockReceiver(); }
                    }, 2000L);
                }
                return;
            }
            android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent intent) {
                    String a = intent == null ? null : intent.getAction();
                    if (Intent.ACTION_USER_PRESENT.equals(a)
                            || Intent.ACTION_SCREEN_ON.equals(a)) {
                        LogUtil.logAlways("[旋转提示] " + a + " ⇒ 重算旋钮状态");
                        reconcileRotationHint("解锁/亮屏后重算");
                    }
                }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_USER_PRESENT);
            f.addAction(Intent.ACTION_SCREEN_ON);
            ctx.registerReceiver(r, f);
            sUnlockReceiver = r;
            LogUtil.logAlways("[旋转提示] 已注册解锁监听（USER_PRESENT / SCREEN_ON）");
        } catch (Throwable th) {
            LogUtil.logAlways("[旋转提示] 注册解锁监听失败: " + th);
        }
    }

    private static android.content.BroadcastReceiver sUnlockReceiver;
    private static int sUnlockRetry = 0;

    /**
     * 在**左右两个下角**各显示一个旋转按钮。
     *
     * <h2>为什么自己画，而不是复用系统的 FloatingRotationButton</h2>
     * 一开始想 hook 系统的 {@code adjustViewPositionAndCreateLayoutParams()} 再复制一个，
     * 但实测那条路走不通：系统弹出建议时**并不经过该方法**
     * （日志里「已弹出旋转按钮」之后没有任何后续记录），
     * 而且它的 {@code FloatingRotationButtonView} 连单参构造器都没有。
     * 与其继续摸索系统内部时序，不如自己放两个普通 View ——
     * 位置、层级、点击行为全部可控，也不会被系统状态卡住。
     *
     * <h2>位置</h2>
     * 固定在**两个下角**：gravity = BOTTOM|LEFT 与 BOTTOM|RIGHT，
     * 离边留 {@code MARGIN_DP}。这样无论手机转到哪个方向，两个角各有一个，
     * 单手都能按到（用户 2026-10-19 的要求）。
     */
    /**
     * Hook 系统的按钮显隐入口 {@code RotationButtonController.setRotateSuggestionButtonState}。
     *
     * <p>2026-10-21：用户反馈「旋钮没消失时旋转屏幕，旋钮不会马上消失，后续也不跟随」。
     * 根因是我们只能通过反射去「请求」系统隐藏，而系统内部的显示路径不经过我们，
     * 状态就乱了。
     *
     * <p>改成直接拦在这个唯一入口上：
     * <ul>
     *   <li>系统想显示时，若「方向一致」或「还没停稳 STABLE_MS」→ 改成不显示；</li>
     *   <li>其它情况一律放行。</li>
     * </ul>
     * 这样系统的任何显示尝试都由同一套判据决定，不会再出现状态错乱。
     */
    private static void installVisibilityHook(
            final io.github.libxposed.api.XposedModule module, ClassLoader cl) {
        try {
            Class<?> cls = Class.forName(
                    "com.android.systemui.shared.rotation.RotationButtonController",
                    false, cl);
            Method target = null;
            for (Method cand : cls.getDeclaredMethods()) {
                if ("setRotateSuggestionButtonState".equals(cand.getName())
                        && cand.getParameterTypes().length == 2) {
                    target = cand;
                    break;
                }
            }
            if (target == null) {
                LogUtil.logAlways("[旋转提示] 未找到 setRotateSuggestionButtonState");
                return;
            }
            target.setAccessible(true);
            module.hook(target)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("rotation-hint-visibility")
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            try {
                                Object arg0 = chain.getArg(0);
                                boolean wantShow = (arg0 instanceof Boolean)
                                        && ((Boolean) arg0).booleanValue();
                                if (sOurShowRequest) {
                                    // 我们自己请求的显示，直接放行
                                    return chain.proceed();
                                }
                                // 🔑 2026-10-22 用户反馈「偶尔弹出两个按钮」：
                                //   原来是「未稳态才拦」，稳态达成时系统请求被放行，
                                //   于是系统按钮和我们的自绘按钮同时出现。
                                //   现在改为：只要模块开着，系统的按钮**一律不显示**，
                                //   旋转提示完全由自绘悬浮窗负责。
                                if (wantShow && RotationHost.sRotationHintEnable.get()) {
                                    // ⚠️ 2026-10-22 反编译确认：**绝对不能用 return null**。
                                    //
                                    //   setRotateSuggestionButtonState 里维护着
                                    //   FloatingRotationButton.mIsShowing：
                                    //     · 隐藏分支执行完 → mIsShowing = false
                                    //     · 显示分支开头有 `if (mIsShowing) return;`
                                    //
                                    //   如果我们把「隐藏」调用整个吞掉，mIsShowing 会永远
                                    //   停在 true，之后所有显示请求都被那句 return 挡住
                                    //   —— 这正是「旋转多次后按钮再也不出现」的原因。
                                    //
                                    //   所以这里改成**带着 false 参数继续执行**，
                                    //   让它走完隐藏分支、把 mIsShowing 正确清掉。
                                    return chain.proceed(new Object[]{
                                            Boolean.FALSE, Boolean.FALSE});
                                }
                            } catch (Throwable unused) {
                            }
                            return chain.proceed();
                        }
                    });
            LogUtil.logAlways("[旋转提示] 已挂钩显隐入口");
        } catch (Throwable th) {
            LogUtil.logAlways("[旋转提示] 显隐挂钩失败: " + th);
        }
    }

    /**
     * 现在允许系统显示旋转按钮吗。
     *
     * <p>⚠️ 2026-10-21 收敛判据：这里**只拦「方向一致」这一种情况**。
     *
     * <p>之前这里还额外要求「已停稳 STABLE_MS」，结果把**我们自己正常请求的显示**
     * 也一起拦掉了 —— 用户实测「旋钮消失了、该出现时不出现」。
     *
     * <p>职责划分：
     * <ul>
     *   <li>「转动中要隐藏」→ 由 {@code onOrientationDegrees} 里方向一变就调
     *       {@code hideButtonNow()} 负责，不靠这里；</li>
     *   <li>「什么时候该显示」→ 由 {@code maybeShowHint} 的时间稳定判断负责；</li>
     *   <li>这里只兜住唯一必须拦的情况：**传感器方向已经等于屏幕方向**，
     *       此时系统若还弹按钮就是多余的。</li>
     * </ul>
     */
    /**
     * 直接把 gravity/translation 写到按钮 View 的 LayoutParams 上。
     *
     * <p>改 {@code calculatePosition} 的返回值有时不落地（系统可能只用了缓存），
     * 这里在布局之后补一刀，保证位置真的变了。
     */


















    /**
     * 点击旋钮：把屏幕转到传感器当前朝向。
     *
     * <h2>v3.292.0 起：强制旋转</h2>
     * 原来这里只调 {@code freezeRotation(rotation, pkg)}。它改的是「用户旋转」，
     * 而 WMS 决定方向时的优先级是
     * <b>应用请求的固定方向 &gt; 用户旋转/旋转锁</b> —— 外屏上大量应用把
     * {@code screenOrientation} 写死成竖屏，于是「点旋钮没任何反应」。
     *
     * <p>现在改为交给 {@link OrientationForcer}：先按老办法锁方向，
     * 700ms 后如果屏幕没转过去，就让 WMS **忽略应用的固定方向请求**
     * （{@code IWindowManager#setIgnoreOrientationRequest}，即系统
     * {@code wm set-ignore-orientation-request} 背后的接口），再锁一次，
     * 真正把应用强制转过去（必要时由系统加黑边）。
     *
     * <p>开关 {@link RotationHost#sForceRotateEnable} 关掉时退回老行为。
     */
    private static void applyRotationSuggestion() {
        // 🔑 v3.294.0：点击那一刻就把目标方向「冻结」下来。
        //
        //   修的是「锁定方向后转一下，第一次点旋钮要么没反应、要么很慢」：
        //   原实现里 target 是在这里现读的 sSensorTargetRotation，
        //   而只要传感器方向一变（包括手指在按钮上按下的轻微晃动、
        //   或者稳态计时还没走完），onOrientationDegrees 就会先 hideButtonNow()
        //   把窗口 removeViewImmediate 掉 —— 这次点击落在「已摘除的窗口」上，
        //   系统根本不会派发；用户看到的就是「点了没反应」，
        //   再点一次（此时按钮已重新出现、方向也稳定了）才生效。
        //
        //   所以点击路径上**绝不使用**可能被传感器改写的实时值，
        //   只用「按钮是为哪个方向显示的」这个已冻结的意图。
        // 🔴 v3.294.7：按钮"代表的方向"必须是**用户看到那个按钮时**的方向。
        //
        //   原来取 sShownFor，但它会在传感器跳动时被改写（日志实测：按钮为 0 显示，
        //   传感器 0→2→3 连跳后 sShownFor 变成 3，用户点下去期望转 0，实际下发 3，
        //   表现就是"点了没用/转错方向"）。
        //
        //   现在取 sShownForAt 快照 —— 在 showNow 里写下、只有真正重新显示时才更新，
        //   点击瞬间的传感器抖动不会污染它。传感器值只在没有快照时兜底。
        final int shown = sShownForAt;
        final int target = (shown >= 0) ? shown : sSensorTargetRotation;
        // ═══ 临时诊断（v3.294.8-diag）═══
        // 目的：一次性确定「点了没反应」到底是哪一层错了。
        // 判定依据见输出：按钮快照 / 传感器 / 屏幕 rotation / 折叠状态 / isFold
        final int diagScreen = OrientationForcer.currentRotation(sContext);
        final boolean diagInner = InnerRotationHost.isInnerScreenRemembered(sContext);
        LogUtil.logAlways("[诊断] 点击 ===== 按钮快照=" + shown
                + " 传感器=" + sSensorTargetRotation
                + " 屏幕rotation=" + diagScreen
                + " 内屏=" + diagInner
                + " 最终target=" + target);
        // ═══ 诊断结束 ═══
        LogUtil.logAlways("[旋转提示] 点击旋钮：目标方向=" + target
                + "（按钮所代表的方向=" + shown + "，传感器=" + sSensorTargetRotation + "）");
        if (target < 0) {
            // ⚠️ 以前这里直接 return，用户看到的就是「点了没反应」且没有任何日志。
            LogUtil.logAlways("[旋转提示] 点击失败：还没有传感器方向（target=-1）");
            hideButtonNow();
            return;
        }
        final Context ctx = sContext;
        // 🔑 v3.294.0：用户已经明确点了方向，就关闭「方向变了自动收起」这条通道，
        //   直到转完 / 超时。否则点击后转屏的 300~600ms 里若有传感器尾帧进来，
        //   onOrientationDegrees 会先把窗口摘掉 —— 第一下的强制看上去「没了」，
        //   必须再点一次才生效。
        sSuppressedUntil = android.os.SystemClock.uptimeMillis() + CLICK_QUIET_MS;
        // 🔴 v3.295.0：点击后的收起**不在这里做**。
        //
        //   原来这里 post 一帧去 hideOverlayKeepIntent()，等于「一按就消失」，
        //   而屏幕真正转过去要 300~600ms —— 用户看到的就是
        //   「图标闪一下就没了」，不知道点没点上；紧接着转屏过程中
        //   传感器又报中间角度，于是再弹一个指向别处的旋钮（问题 2）。
        //
        //   现在改成：点击后按钮**保留不透明**，由 {@link #rotateSuggestionUntilIdle}
        //   接管 —— 等屏幕转到目标方向（或静默期结束）再收起。
        //   这样「点下去 → 立刻有前提反馈 → 转到位后收起」是一条连贯的动作。
        rotateSuggestionUntilIdle(target);
        // 🔴 v3.301.1（用户 2026-11-17 决定）：外屏旋转**完全交还系统**，
        //   点旋钮也不再做任何强制。旋钮在外屏本来就不会显示（见上方拦截），
        //   这里再加一道防御：即使有残留调用，也直接返回，不进任何反射路径
        //   （包括下面的 freezeRotation 兜底）。内屏行为不受影响。
        if (ctx == null || isOuterScreen(ctx)) {
            LogUtil.logAlways("[旋转提示] 外屏不参与旋转，忽略本次点击");
            return;
        }
        if (RotationHost.sForceRotateEnable.get()
                && OrientationForcer.force(ctx, target, "点击旋钮")) {
            LogUtil.logAlways("[旋转提示] 已请求强制旋转 → rotation=" + target);
            return;
        }
        // 老路径 / 兜底：只锁方向
        try {
            Object wm = RotationHost.getWindowManagerServicePublic();
            if (wm == null) {
                LogUtil.logAlways("[旋转提示] 点击失败：拿不到 WMS");
                return;
            }
            java.lang.reflect.Method m = wm.getClass()
                    .getMethod("freezeRotation", int.class, String.class);
            m.setAccessible(true);
            m.invoke(wm, target, "HyperOSGlass");
            LogUtil.logAlways("[旋转提示] 已接受旋转建议，转到 rotation=" + target);
        } catch (Throwable th) {
            LogUtil.logAlways("[旋转提示] 点击旋转失败: " + th);
        }
    }

    /** 供 RotationButtonOverlay 取系统图标用。 */
    static Object controllerForIcon() {
        return sController;
    }



    /** 供 RotationButtonOverlay 取系统图标用。 */
    static Object getFieldForIcon(Object obj, String name) {
        return getField(obj, name);
    }

    /** 读对象的字段（含父类）。 */
    private static Object getField(Object obj, String name) {
        Class<?> c = obj.getClass();
        int guard = 0;
        while (c != null && c != Object.class && guard++ < 8) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (java.lang.NoSuchFieldException unused) {
            } catch (Throwable unused) {
                return null;
            }
            c = c.getSuperclass();
        }
        return null;
    }



    /** 取消挂起中的「稳态后显示」任务。 */
    private static void cancelPendingShow() {
        Runnable prev = sPendingShow;
        if (prev != null) {
            sMain.removeCallbacks(prev);
            sPendingShow = null;
        }
    }

    /**
     * 稳态达到且方向与屏幕不同 → 在对应角落显示按钮。
     *
     * <p>2026-10-22 起改用自绘悬浮窗（{@link RotationButtonOverlay}）：
     * 位置由我们自己的公式算成绝对坐标，一次 {@code updateViewLayout} 就到位，
     * 不再和系统 FloatingRotationButton 的 mIsShowing 状态机纠缠。
     */
    /**
     * 当前屏幕的「旋转锁定」是否处于开启状态。
     *
     * <p>2026-10-22 用户要求：<b>「方向锁定按钮是关闭的、支持自动旋转的时候，
     * 旋转图标也会出现」</b> —— 这种情况不该出现提示，因为屏幕会自己转，
     * 不需要用户去点。
     *
     * <p>内屏读 {@code accelerometer_rotation_inner}，外屏读
     * {@code accelerometer_rotation}；用 {@code current_device_state}
     * （3 = 展开/内屏，0 = 折叠/外屏）判断当前在哪块屏。
     *
     * @return 锁定中返回 true；读不到时返回 true（宁可显示，不让功能整体失效）
     */
    /**
     * 锁屏是否正在显示（含亮屏未解锁）。
     *
     * <p>优先用 KeyguardManager.isKeyguardLocked()：它在「锁屏已亮屏、用户还没解锁」时
     * 返回 true，正好是旋钮不该出现的时机。拿不到时退化为 false（保持原行为）。
     */
    private static boolean isKeyguardShowing(Context ctx) {
        if (ctx == null) {
            return false;
        }
        try {
            android.app.KeyguardManager km = (android.app.KeyguardManager)
                    ctx.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable th) {
            return false;
        }
    }

    /**
     * 外屏桌面（Launcher）包名。
     *
     * <p>HyperOS 桌面就是 {@code com.miui.home}；用前缀匹配兜住个别机型的别名包
     * （{@code com.miui.home.launcher} 之类）。
     */
    private static final String LAUNCHER_PKG = "com.miui.home";

    /** 当前是不是外屏（折叠态）。与 {@link #isRotationLocked} 用同一套判定。 */
    private static boolean isOuterScreen(Context ctx) {
        try {
            int state = android.provider.Settings.Global.getInt(
                    ctx.getContentResolver(), "current_device_state", -1);
            return state != 3;
        } catch (Throwable unused) {
            return true;
        }
    }

    /**
     * 外屏桌面是否在前台。
     *
     * <p>外屏桌面不弹旋钮，理由和锁屏一样 —— 那个位置没有「转屏建议」的意义：
     * <ul>
     *   <li>外屏桌面只有一套竖屏布局，转了也没有额外内容；</li>
     *   <li>旋钮正好压在图标 / 图标长按菜单上，误触代价高；</li>
     *   <li>桌面本身会跟随自动旋转，方向不一致的时机极多，一转身就弹。</li>
     * </ul>
     * 判定失败（拿不到前台包名）时返回 false，保持原行为，不误伤应用内提示。
     */
    private static boolean isOuterLauncherForeground(Context ctx) {
        if (ctx == null || !isOuterScreen(ctx)) {
            return false;
        }
        String pkg = OrientationForcer.topPackage(ctx);
        return pkg != null && (pkg.equals(LAUNCHER_PKG) || pkg.startsWith(LAUNCHER_PKG + "."));
    }

    private static boolean isRotationLocked(Context ctx) {
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            int state = android.provider.Settings.Global.getInt(
                    cr, "current_device_state", -1);
            if (state == 3) {
                // 内屏：专用键
                int auto = android.provider.Settings.System.getInt(
                        cr, "accelerometer_rotation_inner", -1);
                return auto < 0 || auto == 0;
            }
            // 🔴 v3.300.8（用户 2026-11-17）：外屏必须读**专用键**
            //   accelerometer_rotation_outer。
            //
            //   原来外屏读的是通用键 accelerometer_rotation，两者在 MIUI 上
            //   **并不同步**（实测出现过 outer=1 而 common=0 的组合）。
            //   读错键会把「用户已取消锁定」误判成「仍锁定」，
            //   模块于是接管旋转、弹出旋钮 —— 而系统本来能自己转
            //   （哔哩哔哩等视频应用就是这种情况）。
            //   表现正是用户说的「不用模块能转，用了模块就得点旋钮」。
            int outer = android.provider.Settings.System.getInt(
                    cr, "accelerometer_rotation_outer", -1);
            if (outer >= 0) {
                return outer == 0;
            }
            // 该 ROM 没有 outer 键时才退回通用键
            int auto = android.provider.Settings.System.getInt(
                    cr, "accelerometer_rotation", -1);
            return auto < 0 || auto == 0;
        } catch (Throwable unused) {
            return true;
        }
    }

    /**
     * 「自动旋转开着，但应用拒绝跟着转」时，旋钮延迟多久才出现（毫秒）。
     *
     * <p>留这么长是为了兜住正常转屏的耗时（系统从收到方向变化到真正转完
     * 一般 200~500ms）。只有超过这个时间屏幕仍没动，才认定是应用锁死了方向。
     */
    private static final long FORCE_HINT_DELAY_MS = 800L;

    /**
     * 系统自行旋转的宽限时间（v3.300.9）。
     *
     * <p>方向稳定后先等这么久：期间只要屏幕方向对上了就完全不干预；
     * 超过这个时间仍没转，才认定「应用拒绝跟随」，提供旋钮。
     * 视频类应用转屏一般 300~900ms，取 2000ms 留足余量。
     */
    private static final long SELF_ROTATE_GRACE_MS = 2000L;

    /** 延迟判定任务（自动旋转场景专用；保持单例，避免被反复 post）。 */
    private static volatile Runnable sForceHintTask;

    /**
     * 自动旋转开着时，是否该把旋钮弹出来（= 应用拒绝旋转）。
     *
     * <p>判定依据：传感器方向已经稳定超过 {@link #FORCE_HINT_DELAY_MS}。
     * 没到点就先挂一个延时任务到点再重算，返回 false 让调用方这次先不显示。
     */
    private static boolean allowHintWhileUnlocked(Context ctx) {
        // 🔑 v3.300.5（用户 2026-11-17 定稿）：外屏与内屏旋钮逻辑完全一致。
        //
        //   用户原话：「外屏跟内屏的旋屏逻辑完全一样，除了锁屏亮屏那种解除锁定
        //   外屏不需要以外，别的功能完全同内屏一模一样。」
        //
        //   所以这里**不再给外屏任何特殊分支** —— 未锁定时的可见性判定
        //   与内屏走完全相同的规则（强制旋转开启 + 稳定 800ms 后给旋钮）。
        //   之前那套「外屏无条件下发」只会让外屏表现和内屏不一致。
        if (!RotationHost.sForceRotateEnable.get()) {
            return false;
        }
        long age = android.os.SystemClock.uptimeMillis() - sStableSince;
        if (age < FORCE_HINT_DELAY_MS) {
            scheduleForceHint(FORCE_HINT_DELAY_MS - age + 30L);
            return false;
        }
        // 🔴 v3.300.8（用户 2026-11-17）：屏幕**已经跟着转了**就别弹旋钮。
        //
        //   反应用户的原始反馈：「不用模块时，外屏看 B 站视频会跟着方向转；
        //   用了模块就要手动点旋钮」。根因就是这里 —— 只看「稳定超过 800ms」
        //   就认定"应用拒绝旋转"，可视频应用本来就会自己转，
        //   于是系统转了、旋钮还照弹，用户体感就是"模块把自动旋转弄坏了"。
        //
        //   正确的判据是「期望方向 vs 实际方向」：两者一致说明系统已经转好，
        //   此时不该再提供旋钮，更不该做任何接管。
        // 期望方向（传感器稳态目标）与当前屏幕方向一致 = 系统已经转好了。
        //
        // 🔴 v3.300.9（用户 2026-11-17 规则）：
        //   「如果软件本身支持旋转，或者它进行了旋转，那我们的模块就不要去干涉；
        //    如果软件在屏幕切换方向之后没有旋转，我们的模块才介入。」
        //
        //   所以这里要**给系统充分的自行旋转时间**：视频类应用（哔哩哔哩）
        //   转屏通常要 300~900ms，原来 800ms 就判定"转不动"过于激进 ——
        //   系统正在转的那一刻旋钮就弹出来了，用户体感是"模块在强制旋转"。
        //   现在统一等 SELF_ROTATE_GRACE_MS，期间只要方向对上了就立刻退出干预。
        int want = sStableTarget;
        int now = OrientationForcer.currentRotation(ctx);
        if (want >= 0 && now >= 0 && want == now) {
            return false;      // 已经跟随成功，无需介入
        }
        if (age < SELF_ROTATE_GRACE_MS) {
            // 还在系统自行旋转的合理时间内：继续等，先不弹
            scheduleForceHint(SELF_ROTATE_GRACE_MS - age + 40L);
            return false;
        }
        LogUtil.logAlways("[旋转提示] 自动旋转已开但方向仍为 " + now + "（期望 " + want
                + "），判定应用确实不跟随，提供旋钮");
        return true;
    }

    /**
     * 陷入稳态复检：等 {@code delayMs} 后重新走一遍完整判定。
     *
     * <p>单例任务：只有最后一个生效，避免传感器高频上报时堆积一堆到点任务
     * （那会让按钮在几毫秒内反复 show/hide）。
     */
    private static void scheduleStableRecheck(long delayMs) {
        Runnable prev = sStableRecheck;
        if (prev != null) {
            sMain.removeCallbacks(prev);
        }
        final Runnable r = new Runnable() {
            @Override
            public void run() {
                if (sStableRecheck == this) {
                    sStableRecheck = null;
                }
                reconcileRotationHint("稳态复检");
            }
        };
        sStableRecheck = r;
        sMain.postDelayed(r, Math.max(16L, delayMs));
    }

    /** 到点后重新走一遍完整判定（方向盘稳了再说）。 */
    private static void scheduleForceHint(long delay) {
        Runnable prev = sForceHintTask;
        if (prev != null) {
            sMain.removeCallbacks(prev);
            sForceHintTask = null;
        }
        Runnable r = new Runnable() {
            @Override
            public void run() {
                sForceHintTask = null;
                reconcileRotationHint("自动旋转下的延迟判定");
            }
        };
        sForceHintTask = r;
        sMain.postDelayed(r, delay);
    }

    private static void showNow(int targetRotation, int current) {
        // 🔴 v3.295.0（用户 2026-11-12 问题 2 的根因修复）：
        //   这是**所有显示路径的唯一出口**，点击后的静默期必须在这里判，
        //   而且必须判在**最前面**。
        //
        //   原实现把静默判断放在 showNow 开头是对的，但漏了同一次点击后的另外两条路：
        //     · rotateSuggestionUntilIdle 里「方向一致 → hideButtonNow()」先把按钮收了；
        //     · 屏幕转到位后 DisplayListener → reconcile → sensor==screen → 收起。
        //   于是「点了 → 转过去了 → 传感器再报一次中间角度 → 又弹一个指向别处的旋钮」，
        //   用户看到的就是「马上又转到别的方向、旋钮乱蹦」。
        //
        //   现在静默期统一在 {@link #isClickQuiet()} 里判，三个入口全走它。
        if (isClickQuiet()) {
            LogUtil.logAlwaysOnce("quiet-show",
                    "[旋转提示] 点击后静默期内，忽略显示请求（目标=" + targetRotation + "）");
            return;
        }
        Context ctx = sContext;
        if (ctx == null) {
            return;
        }
        // 🔴 v3.301.1（用户 2026-11-17 决定）：**外屏一律不显示旋钮**。
        //
        //   用户原话：「先把外屏所有关于旋转的功能都移除掉，包括旋钮、
        //   强制旋转等等，全部移除掉，全部交还给系统。」
        //
        //   放在最前面：外屏连后续的判定/调度都不进，彻底不参与。
        //   内屏旋钮行为完全不受影响。
        if (isOuterScreen(ctx)) {
            hideButtonNow();
            return;
        }
        // 外屏桌面：现在由上面的外屏总开关统一拦截，这里保留作防御。
        if (isOuterLauncherForeground(ctx)) {
            LogUtil.logAlways("[旋转提示] 外屏桌面，不显示旋钮");
            hideButtonNow();
            return;
        }
        // 支持自动旋转（锁已关闭）时一般不需要提示：屏幕会自己转。
        // 🔑 v3.292.0：但如果「强制旋转」开着，而方向早就变了屏幕却没跟着转，
        //   说明**应用把方向锁死了**（外屏上非常常见）—— 这时把旋钮弹出来，
        //   让用户有一个手动强制的入口。用 FORCE_HINT_DELAY_MS 兜住正常转屏的耗时，
        //   避免应用只是慢一点也跟着弹。
        if (!isRotationLocked(ctx)) {
            if (!allowHintWhileUnlocked(ctx)) {
                LogUtil.logAlways("[旋转提示] 旋转锁未开启（或仍在等应用自己转），不显示提示");
                return;
            }
            LogUtil.logAlways("[旋转提示] 自动旋转开着但应用没跟着转 → 弹出旋钮（可点击强制）");
        }
        // 锁屏界面不显示旋钮：用户在锁屏上不需要转屏建议，
        // 而且旋钮会盖住锁屏内容。解锁后状态重算会重新弹出来。
        if (isKeyguardShowing(ctx)) {
            LogUtil.logAlways("[旋转提示] 锁屏界面，不显示旋钮");
            hideButtonNow();
            return;
        }
        int corner = RotationButtonOverlay.cornerFor(targetRotation, current);
        RotationButtonOverlay.get().show(ctx, corner);
        sShownFor = targetRotation;
        // 每个画面都有等待时间：超时内没有新的方向变化就自动退出
        sMain.removeCallbacks(sAutoHideTask);
        sMain.postDelayed(sAutoHideTask, AUTO_HIDE_MS);
        // ⚠️ 不在这里停止轮询：按钮显示之后用户很可能继续转，
        //   而方向事件是 on-change 类型会漏报，必须有人继续盯着。
        //   轮询会在「确认静止」后自行停止（见轮询里的静止判断）。
        // 快照：按钮此刻代表的方等于 targetRotation，点击时以此为准
        sShownForAt = targetRotation;
        LogUtil.logAlways("[旋转提示] 已显示（当前=" + current
                + " 目标=" + targetRotation + " 角落=" + corner + "）");
    }

    // ── 状态重算（2026-10-24）────────────────────────────────────────────
    /** 状态监听是否已装。 */
    private static volatile boolean sWatchersInstalled;
    /** 最近一次看到的屏幕 rotation，用于过滤 DisplayListener 的重复回调。 */
    private static volatile int sWatchedScreenRotation = -1;

    /**
     * 安装「状态变化」监听，让旋钮在**任何**相关状态变化后重算一次。
     *
     * <h2>为什么必须有这一层（2026-10-24 修复「锁定旋转后转屏幕不弹旋钮」）</h2>
     * 原来整个判定只有一个触发点：<b>方向传感器上报新方向</b>
     * （{@code onOrientationDegrees} 里 {@code sSensorTargetRotation != target} 才往下走）。
     * 可「该不该显示旋钮」其实取决于**三个**变量的组合：
     * <pre>
     *   传感器方向  、  屏幕方向  、  旋转锁开关
     * </pre>
     * 只盯第一个，另外两个变了就完全不重算 —— 实测（2026-10-24，lhasa）：
     * <pre>
     *   wm user-rotation lock 0     ← 屏幕从 3 转到 0，物理仍是 3
     *   日志只有「屏幕方向=0」，没有任何判定，旋钮不出现
     * </pre>
     * 同理，联动功能那 5 秒「临时解锁 → 到点锁回」也会让锁状态反复变化，
     * 锁回之后没有任何人重算，于是「锁着、方向也不一致，就是不弹」。
     *
     * <p>所以这里补两个监听：
     * <ol>
     *   <li>{@code DisplayListener} —— 屏幕方向被系统/应用改掉时；</li>
     *   <li>{@code ContentObserver} —— 旋转锁开关、折叠状态被改掉时
     *       （我们自己回写、用户点控制中心开关都会触发）。</li>
     * </ol>
     */
    private static void ensureStateWatchers(Context ctx) {
        if (sWatchersInstalled || ctx == null) {
            return;
        }
        try {
            // ① 屏幕方向变化
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager)
                            ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                sWatchedScreenRotation = currentSurfaceRotation(ctx);
                dm.registerDisplayListener(
                        new android.hardware.display.DisplayManager.DisplayListener() {
                            @Override
                            public void onDisplayAdded(int displayId) {
                            }

                            @Override
                            public void onDisplayRemoved(int displayId) {
                                // 折叠屏切换时窗口可能留在旧屏，丢弃待重建
                                try {
                                    RotationButtonOverlay.get().reset();
                                } catch (Throwable unused) {
                                }
                                reconcileRotationHint("屏幕被移除");
                            }

                            @Override
                            public void onDisplayChanged(int displayId) {
                                try {
                                    Context c = sContext;
                                    if (c == null) {
                                        return;
                                    }
                                    int now = currentSurfaceRotation(c);
                                    if (now < 0 || now == sWatchedScreenRotation) {
                                        return;
                                    }
                                    sWatchedScreenRotation = now;
                                    reconcileRotationHint("屏幕方向变化→" + now);
                                } catch (Throwable unused) {
                                }
                            }
                        }, sMain);
            }

            // ② 旋转锁 / 折叠状态变化
            final android.content.ContentResolver cr = ctx.getContentResolver();
            android.database.ContentObserver obs =
                    new android.database.ContentObserver(sMain) {
                        @Override
                        public void onChange(boolean selfChange) {
                            reconcileRotationHint("旋转锁/设备状态变化");
                        }
                    };
            cr.registerContentObserver(
                    Settings.System.getUriFor("accelerometer_rotation"), false, obs);
            cr.registerContentObserver(
                    Settings.System.getUriFor("accelerometer_rotation_inner"), false, obs);
            cr.registerContentObserver(
                    Settings.Global.getUriFor("current_device_state"), false, obs);

            sWatchersInstalled = true;
            LogUtil.logAlways("[旋转提示] 已装状态监听（屏幕方向 / 旋转锁）");
        } catch (Throwable th) {
            LogUtil.logAlways("[旋转提示] 状态监听安装失败: " + th);
        }
    }

    /**
     * 重算「现在到底该不该显示旋钮」，是唯一的显示判据入口。
     *
     * <p>判据（与用户确认过的规则一致）：
     * <ol>
     *   <li>开关关着 → 收起；</li>
     *   <li>旋转锁没开（能自动旋转）→ 收起，屏幕会自己转；</li>
     *   <li>传感器方向 == 屏幕方向 → 收起，用户姿势已经对了；</li>
     *   <li>其余 → 显示，且同一目标方向不重复播动画、不重置超时。</li>
     * </ol>
     *
     * <p>注意这**不是**轮询：只在「屏幕方向变了 / 锁开关变了 / 亮屏解锁」
     * 这些离散事件上跑一次，静默期没有任何定时器。
     */
    static void reconcileRotationHint(final String reason) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            sMain.post(new Runnable() {
                @Override
                public void run() {
                    reconcileRotationHint(reason);
                }
            });
            return;
        }
        try {
            if (!RotationHost.sRotationHintEnable.get()) {
                if (sShownFor >= 0) {
                    hideButtonNow();
                }
                return;
            }
            Context ctx = sContext;
            if (ctx == null) {
                return;
            }
            // v3.293.0：外屏桌面（含「应用里旋钮正显示着，用户按 Home 回桌面」这种情况）
            // 一律收起且不再弹，和锁屏同样是硬屏蔽。
            if (isOuterLauncherForeground(ctx)) {
                if (sShownFor >= 0) {
                    LogUtil.logAlways("[旋转提示] " + reason + "：外屏桌面，收起旋钮");
                    hideButtonNow();
                }
                return;
            }
            // v3.292.0：拿到 Context 后清一次可能残留的「忽略应用方向请求」
            // （SystemUI 被重启时，WMS 里那个全局开关不会跟着复位）
            OrientationForcer.clearStale(ctx);
            // 🔴 v3.295.0（用户 2026-11-12 问题 2）：点击后的静默期内，
            //   「显示」和「因方向一致而收起」都交给 rotateSuggestionUntilIdle 决定。
            //
            //   原来这里（DisplayListener 触发的重算）不看静默期：
            //   点击 → 屏幕转到目标方向 → DisplayListener 立刻重算 →
            //   sensor==screen → 「收起」，看起来正常；但转屏过程中传感器报的
            //   中间角度会先让 sensor != screen，于是又弹一个指向别处的旋钮。
            //   两条路打架，用户看到的就是「刚转过去又冒出别的方向」。
            if (isClickQuiet()) {
                LogUtil.logAlwaysOnce("quiet-reconcile",
                        "[旋转提示] " + reason + "：点击后静默期内，交给点击收尾处理");
                return;
            }
            int screen = currentSurfaceRotation(ctx);
            int sensor = sSensorTargetRotation;
            if (screen < 0) {
                // 屏幕方向还没就绪，等 DisplayListener / 传感器下一次上报
                return;
            }
            if (sensor < 0) {
                // 还不知道物理姿态（进程刚起 / 刚亮屏）：用一路短时重力采样补一个值，
                // 拿到后 onOrientationDegrees 会自己回到这里重算。
                startGravityPolling();
                return;
            }
            // 🔑 v3.294.1（用户 2026-11-12 要求）：内屏联动正在「可自由旋转」的窗口期，
            //   一律不显示旋钮，并收起已显示的。
            //
            //   原因：展开内屏后前几秒系统本来允许自由旋转（等到点才回锁），
            //   这期间屏幕自己会转，弹旋钮毫无意义；而且展开动作本身（手机还在
            //   翻开的过渡姿态）就会制造「方向不一致」，于是经常出现
            //   「手机还没完全展开，旋钮就先冒出来」。
            //
            //   判据同时覆盖：① 联动窗口期 sFollowing（马上要解锁跟随）；
            //   ② 钉 270° 的防抖窗口（RotationHost.isUnfoldSettling）。
            if (InnerRotationHost.isUnfoldWindowActive()) {
                if (sShownFor >= 0) {
                    LogUtil.logAlways("[旋转提示] " + reason
                            + "：内屏正在展开/可自由旋转窗口期，收起旋钮且不显示");
                    hideButtonNow();
                } else {
                    LogUtil.logAlwaysOnce("unfold-quiet", "[旋转提示] 内屏展开窗口期，抑制旋钮");
                }
                return;
            }
            if (!isRotationLocked(ctx)) {
                if (!allowHintWhileUnlocked(ctx)) {
                    if (sShownFor >= 0) {
                        LogUtil.logAlways("[旋转提示] " + reason + "：旋转锁未开启，收起");
                        hideButtonNow();
                    }
                    return;
                }
                // 落到下面继续判定：方向确实不一致就弹旋钮（点击即强制）
            }
            if (sensor == screen) {
                if (sShownFor >= 0) {
                    LogUtil.logAlways("[旋转提示] " + reason + "：方向已一致，收起");
                    hideButtonNow();
                }
                return;
            }
            if (sShownFor == sensor) {
                return;      // 已经在为这个方向显示，别重置超时
            }
            // 🔑 v3.294.1：稳态钳制（用户 2026-11-12 要求）。
            //   原来 reconcile 一被触发就直接 showNow，于是传感器在临界角度
            //   抖一下（0↔1↔2↔3 来回跳，日志里实测出现过「目标=2 / 目标=0」交替）
            //   就会弹一个按钮，用户拿手机的过渡动作也会被当成旋屏意向。
            //   现在要求：方向至少稳定 HINT_STABLE_MS 才显示；不够就挂一个
            //   到点重算的延时任务，且**不显示**。
            // 稳态 = 「同一个方向已经持续了多久」。每次方向变化都会把
            // sStableSince 重置成当下（见 onOrientationDegrees），所以这里
            // 算出来的就是真实稳定时长。不足就挂一个「到点再算一次」的延时：
            // 到点若方向仍没变就弹出；期间又转了，sStableSince 被重置，
            // 那次复检会发现仍不足并接着等 —— 也就是「转着不弹、停下够久才弹」。
            // 🔑 v3.294.5（用户 2026-11-12 反馈「快速转到某方向不显示，要再转一下才出来」）：
            //   稳态时间戳原来只在 onOrientationDegrees 里方向变化时更新。
            //   但 reconcile 还会被 ShowTrigger 等路径触发 —— 那时 sSensorTargetRotation
            //   可能已经更新过（值相同），计时却没跟着重置/推进，就会出现
            //   「方向明明稳定了，这里却算出一段很短的时间 → 一直判定不稳定 → 不显示」
            //   （日志实测：屏幕方向=0 + 目标=2，离 180ms 就差一点点，never 弹出）。
            //
            //   修法：把「稳态基准」与传感器回调解耦 —— 这里以 sStableTarget 为准，
            //   只要当前目标方向与基准不同就重置；相同则沿用已有时间戳。
            if (sStableTarget != sensor) {
                sStableTarget = sensor;
                sStableSince = android.os.SystemClock.uptimeMillis();
            }
            long stableFor = android.os.SystemClock.uptimeMillis() - sStableSince;
            if (stableFor < HINT_STABLE_MS) {
                long remain = HINT_STABLE_MS - stableFor;
                LogUtil.logAlways("[旋转提示] " + reason + "：方向仅稳定 " + stableFor
                        + "ms（需 " + HINT_STABLE_MS + "ms），" + remain + "ms 后再判定");
                scheduleStableRecheck(remain);
                return;
            }
            LogUtil.logAlways("[旋转提示] " + reason + "：锁着且方向不一致（当前="
                    + screen + " 目标=" + sensor + "），显示旋钮");
            showNow(sensor, screen);
        } catch (Throwable unused) {
        }
    }





    /**
     * 只改**落点**，其余全部交给系统。
     *
     * <h2>为什么最终选了这条路</h2>
     * 前面试过两版自建按钮，都被用户否掉了，理由很到位：
     * <blockquote>
     *   它和安卓自带的不一样，自带的图标里面的内容会**根据实际的传感器方向旋转，
     *   去预示点完之后会到什么方向**。
     * </blockquote>
     * 也就是说系统那个按钮不只是「一个图标」，它把「点下去会转到哪个方向」
     * 直接画在图形上（有 cw/ccw × start_0/start_90 四套 {@code AnimatedVectorDrawable}）。
     * 这种语义 + 动效自己复刻不了，也不该复刻。
     *
     * <p>所以回到「用系统的按钮」，但把它的位置改掉 —— 方法就是 hook
     * {@code FloatingRotationButtonPositionCalculator.calculatePosition()}：
     * 它是系统算落点的**唯一入口**，系统算完我们只改 {@code gravity} 和位移量，
     * 其它一切都保持原样：
     * <ul>
     *   <li>图标随传感器方向旋转 ✅（系统自己调 {@code updateIcon}）</li>
     *   <li>显示 / 隐藏时机 ✅（系统 {@code setRotateSuggestionButtonState}）</li>
     *   <li>点击行为、超时收起、动画 ✅</li>
     * </ul>
     *
     * <h2>为什么这样就能「跟着屏幕方向走」</h2>
     * {@code gravity} 是相对**当前屏幕坐标系**的，所以只要固定用
     * {@code BOTTOM|LEFT} 或 {@code BOTTOM|RIGHT}，屏幕怎么转，
     * 按钮都始终在用户看到的那个下角。
     * 系统原本用 {@code floatingRotationButtonPositionLeft} 做左右二选一，
     * 这里直接指定成「右边下角」（导航栏在侧边时更不容易误触）。
     */
    private static void installPositionHook(final io.github.libxposed.api.XposedModule module,
                                            ClassLoader cl) {
        try {
            Class<?> cls = Class.forName(
                    "com.android.systemui.shared.rotation.FloatingRotationButtonPositionCalculator",
                    false, cl);
            Method m = null;
            for (Method cand : cls.getDeclaredMethods()) {
                if ("calculatePosition".equals(cand.getName())) {
                    m = cand;
                    break;
                }
            }
            if (m == null) {
                LogUtil.logAlways("[旋转提示] 未找到 calculatePosition，位置保持系统默认");
                return;
            }
            m.setAccessible(true);
            module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("rotation-hint-position")
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object pos = chain.proceed();
                            try {
                                if (RotationHost.sRotationHintEnable.get()) {
                                    // ⚠️ 2026-10-21：这里**不再强制 setVisibility(GONE)**。
                                    //   实测那样会把系统内部「已显示」的状态和真实视图
                                    //   搞不一致 —— 之后方向变了系统以为还在显示、
                                    //   不再调显示接口，按钮就再也不出现
                                    //   （用户反馈「旋钮消失了」）。
                                    //   隐藏统一交给 onOrientationDegrees 里的
                                    //   hideButtonNow()（走系统接口 + 视图兜底），
                                    //   这里只负责改落点。
                                    LogUtil.logAlways("[旋转提示] calculatePosition 被调用"
                                            + " sensor=" + sSensorTargetRotation
                                            + " shown=" + sShownFor);
                                    if (pos != null) {
                                        overridePosition(pos);
                                    }
                                }
                            } catch (Throwable unused) {
                                // 改不动就用系统原值，不影响按钮本身
                            }
                            return pos;
                        }
                    });
            LogUtil.logAlways("[旋转提示] 已挂钩 calculatePosition（只改落点）");
        } catch (Throwable th) {
            LogUtil.logAlways("[旋转提示] 位置挂钩失败: " + th);
        }
    }

    /**
     * 把系统算好的 Position 挪到**右下角**。
     *
     * <p>只动 position 里的三个字段（REA 反编译确认）：
     * <pre>
     *   gravity       窗口对齐方向 —— 用 BOTTOM|RIGHT 就永远在视觉右下角
     *   translationX  水平内缩（负数表示往屏幕中间收）
     *   translationY  垂直内缩（负数表示往上抬）
     * </pre>
     * 边距沿用系统自己的 {@code defaultMargin}，不自己造数字，
     * 这样跟系统视觉规范一致。
     */
    /**
     * 按**系统自己的左/右规则**给出落点。
     *
     * <h2>用户的要求（2026-10-19，最简洁的一次表述）</h2>
     * <blockquote>
     *   直接用系统预设的 button right、button left 之类的去定位置就好了。
     *   它把这个 button left 定义在哪儿，就出现在哪儿。
     * </blockquote>
     * 也就是说：**不要自己算坐标**，只用系统那套「左 / 右」二选一，
     * 由系统自己把它放到对应的角上。
     *
     * <h2>系统的规则</h2>
     * {@code FloatingRotationButtonPositionCalculator} 里用
     * {@code floatingRotationButtonPositionLeft} 这个布尔值决定左/右，
     * 四个方向的取值（REA 反编译 + 本机日志实测）：
     * <pre>
     *   rotation 0 → 85 = BOTTOM|RIGHT   右下
     *   rotation 1 → 53 = TOP|RIGHT      右上
     *   rotation 2 → 51 = TOP|LEFT       左上
     *   rotation 3 → 83 = BOTTOM|LEFT    左下
     * </pre>
     * 注意括号：0/1 走 RIGHT 一侧、2/3 走 LEFT 一侧。
     * 我们只要把 {@code sensor}（手机物理姿态）填进系统这个表即可 ——
     * 位置完全由系统决定，我们不改任何 View / LayoutParams，
     * 因此**不会崩溃**（前面用 updateViewLayout 的做法会让 SystemUI 强转异常）。
     *
     * <h2>四个姿态的目标角落（用户逐项确认）</h2>
     * <pre>
     *   0 竖立     → 右下   3 向左倒 → 左下
     *   2 倒置     → 左上   1 向右倒 → 右上
     * </pre>
     */
    /**
     * 在系统按钮的**对侧**再放一个同样外观的按钮，方便左右手操作。
     *
     * <p>做法：直接用系统的 {@code FloatingRotationButtonView} 类（图标、形状、点击反馈
     * 全部原生一致），加载系统资源 {@code accessibility_rotate_button} 作为图标，
     * 放进一个独立的小悬浮窗口，贴在系统按钮的对侧。
     *

    /**
     * 让系统重新算一次位置。
     *
     * <p>用系统自己的 {@code setRotateSuggestionButtonState} 先收后显，
     * 系统会重走一遍布局流程，届时 {@code calculatePosition} 被再次调用，
     * 按最新传感器方向给出落点。
     *
     * <p>⚠️ 只用这一条通道 —— 绝不调 {@code updateViewLayout} 或改 LayoutParams，
     * 那会让 SystemUI 在 {@code (FrameLayout.LayoutParams)} 强转处崩溃。
     */
    /** 上次重排的目标方向与时间，用于防抖。 */
    private static volatile int sLastRelocatedSensor = -2;
    private static volatile long sLastRelocateAt = 0L;
    /** 重排最小间隔：同一方向的重复请求在这段时间内直接忽略。 */
    private static final long RELOCATE_MIN_INTERVAL_MS = 150L;


    /** 沿继承链读一个字段（私有/父类都能读到）。 */
    private static Object readField(Object obj, String name) {
        if (obj == null) {
            return null;
        }
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable unused) {
                return null;
            }
        }
        return null;
    }

    /**
     * **不干预系统落点** —— 保持系统默认位置。
     *
     * <h2>为什么改成不动</h2>
     * 用户 2026-10-19 决定：「算了，恢复系统默认的容器和默认的位置吧，
     * 以及旋钮在快速切换位置时的一个过渡」。
     *
     * <h2>之前的尝试为什么都走不通</h2>
     * 系统把按钮放在一个 <b>192×192 的悬浮窗口</b>里，按钮本身 112×112，
     * 所以窗口内可用位移只有 {@code (192-112)/2 = 40px}。实测：
     * <ul>
     *   <li>translation 超过 ±40 → 按钮被容器裁掉，甚至完全消失（tx=133 时不可见）；</li>
     *   <li>padding 无效 —— 按钮尺寸固定，padding 只改变居中位置，仍受 40px 限制；</li>
     *   <li>改 View 的 LayoutParams / updateViewLayout → SystemUI 强转崩溃（两次）。</li>
     * </ul>
     * 想让按钮真正远离边缘，必须换掉整个容器窗口，代价是要自绘按钮、
     * 失去系统原生的方向预示图标与动画 —— 用户不采纳。
     *
     * <p>所以位置只换 gravity、不碰 translation，边距完全交给系统。
     */
    /**
     * 按传感器方向 + 屏幕锁定方向的**夹角**决定按钮落在哪个角。
     *
     * <h2>用户最终确认的行为（2026-10-19）</h2>
     * <blockquote>
     *   算了，恢复系统默认的容器和默认的位置吧，
     *   以及旋钮在快速切换位置时的一个过渡。
     * </blockquote>
     * 也就是：<b>位置只用系统自带的四个角落</b>，不做任何自定义内缩；
     * 但按钮要跟着传感器方向换角落（这正是 v3.78 用户评价「完美」的行为）。
     *
     * <h2>为什么 translation 必须为 0</h2>
     * 系统把按钮放在 <b>192×192 的悬浮窗口</b>里、按钮本身 112×112，
     * 窗口内可用位移只有 {@code (192-112)/2 = 40px}。实测超出就被裁：
     * tx=133 时按钮完全消失、tx=-93 时被边缘截掉。
     * 而 padding 无效（按钮尺寸固定），改 LayoutParams 会崩。
     * <b>所以这里把 translation 明确置 0</b> —— 只换 gravity，
     * 位置永远是系统原生的角落，绝不裁切。
     *
     * <h2>夹角换算</h2>
     * gravity 的角落是相对<b>画面</b>的（画面方向 = rotation）；
     * 我们要的「手机物理姿态对应的角落」是相对<b>手机实体</b>的。
     * 两者相差一个 rotation：
     * <pre>
     *   画面角 = (物理目标角 − rotation + 4) % 4
     * </pre>
     * rotation=0（竖屏锁定）时二者相等。
     * 角落编号按顺时针：0=左上 1=右上 3=右下 2=左下。
     */
    /**
     * 用系统自带的「左 / 右」开关决定按钮在屏幕的哪一侧 —— 不自己算任何坐标。
     *
     * <h2>用户的要求（2026-10-19，最终定稿）</h2>
     * <blockquote>
     *   你把容器和旋钮的图标位置都恢复默认啊，通过 button left、button right
     *   之类的来调用。
     * </blockquote>
     * 也就是：<b>容器保持系统默认、图标保持系统默认、边距保持系统默认</b>，
     * 我们只告诉系统「这次要用左边那个还是右边那个」。
     * 系统那四个原生落点（本机实测）就是：
     * <pre>
     *   rotation 0 → 85 = BOTTOM|RIGHT   右下
     *   rotation 1 → 53 = TOP|RIGHT      右上
     *   rotation 2 → 51 = TOP|LEFT       左上
     *   rotation 3 → 83 = BOTTOM|LEFT    左下
     * </pre>
     * 它们本来就带有系统设计好的边距（translation 由系统填，通常是 ±69），
     * 所以<b>我们绝不能再改 translation</b> —— 一改就会把按钮推出 192px 的容器窗口
     * （实测 tx=±133 消失、tx=-93 被裁、tx=0 贴边超出）。
     *
     * <h2>做法</h2>
     * 只设 {@code gravity}（选系统四个原生角落之一），translation 完全不动，
     * 保持系统通过 {@code floatingRotationButtonPositionLeft} 之类算好的原值。
     */
    /** 沿继承链读一个 int 字段，读不到返回 Integer.MIN_VALUE。 */
    private static int readIntField(Object obj, String name) {
        Object v = readField(obj, name);
        return v instanceof Integer ? (Integer) v : Integer.MIN_VALUE;
    }

    /**
     * 是否应该压掉这次按钮显示。
     *
     * <p>判据：传感器算出的目标方向 == 当前屏幕方向 —— 此时用户已经在正确姿势，
     * 没有理由提示旋转。用户 2026-10-21 明确要求「去掉传感器方向跟当前方向一致
     * 时候的按钮」。
     */
    private static boolean shouldSuppressButton() {
        Context ctx = sContext;
        if (ctx == null) {
            return false;
        }
        int sensor = sSensorTargetRotation;
        if (sensor < 0) {
            return false;   // 还不知道方向，交给系统
        }
        int cur = currentSurfaceRotation(ctx);
        if (cur < 0) {
            return false;
        }
        if (sensor == cur) {
            return true;
        }
        return false;
    }

    /**
     * 收起按钮。
     *
     * <p>2026-10-22 性能修正：这里原来还每次反射调用系统的
     * {@code setRotateSuggestionButtonState(false, true)}，但系统按钮已经被
     * 显隐 hook 彻底拦住、永远不会出现 —— 那次反射（遍历 declaredMethods +
     * setAccessible + invoke）纯属多余开销，而且会让「方向一变就收起」
     * 慢上几毫秒。现在只操作我们自己的悬浮窗。
     */
    private static void hideButtonNow() {
        sShownFor = -1;
        // 🔴 v3.295.2：按钮一收起，快照就失效。
        //   改成「按下即触发」后，第一次点击若因应用锁死方向没转过去，
        //   按钮仍在屏幕上；此时若快照还留着旧值，第二次点击就会用旧方向下发，
        //   而不是用户此刻看到的那个方向。收起时清掉，下次显示会重新写。
        sShownForAt = -1;
        sMain.removeCallbacks(sAutoHideTask);
        RotationButtonOverlay.get().hideNow();
    }


    /**
     * 是否处于「点击旋钮后的静默期」。
     *
     * <p>静默期的含义：从点击那一刻起 {@link #CLICK_QUIET_MS} 内，屏幕方向由模块
     * <b>精确下发</b>，传感器在转屏过程中报出的中间角度没有任何参考价值。
     * 这段时间内：
     * <ul>
     *   <li>不显示旋钮（否则会弹一个指向别处的按钮，用户再点就被带去别的方向）；</li>
     *   <li>不因为「方向一致了」而收起 —— 收起由 {@link #rotateSuggestionUntilIdle}
     *       在「已转到目标方向」时做。</li>
     * </ul>
     */
    private static boolean isClickQuiet() {
        return android.os.SystemClock.uptimeMillis() < sSuppressedUntil;
    }

    /** 轮询「是否已转到目标方向」，用于点击后优雅收起（单例）。 */
    private static volatile Runnable sRotateSettleTask;
    /** 轮询间隔：60ms 一次，够快能抓到位，又不至于压占主线程。 */
    private static final long ROTATE_SETTLE_STEP_MS = 60L;

    /**
     * 点击后跟随旋转的收尾：等屏幕真正转到目标方向后再收起按钮。
     *
     * <p>🔴 v3.295.0（用户 2026-11-12 问题 2 的根因修复）。
     *
     * <h2>原来错在哪</h2>
     * 点击后是立即 {@code hideOverlayKeepIntent()} —— 按钮一按就没了；
     * 屏幕转屏要 300~600ms，这期间传感器会连续报中间角度，
     * 每次方向变化都会走 {@code onOrientationDegrees → hideButtonNow()}
     * 把 {sShownFor} 清成 -1 —— 于是「用户刚点完」，按钮状态已经被清空，
     * 紧接着下一次重算又按新传感器方向弹出指向别处的旋钮。
     *
     * <h2>现在的行为</h2>
     * <ol>
     *   <li>点击后按钮保留（不透明、不消失），给用户明确的「点到了」反馈；</li>
     *   <li>每 {@link #ROTATE_SETTLE_STEP_MS} 读一次屏幕方向：
     *       等于目标方向 → 收起，这一轮旋转结束；</li>
     *   <li>静默期到点仍未转到位（应用锁死了方向）→ 也收起，并记录日志；</li>
     *   <li>收起后清掉静默期，让传感器恢复正常判据。</li>
     * </ol>
     */
    private static void rotateSuggestionUntilIdle(final int target) {
        Runnable prev = sRotateSettleTask;
        if (prev != null) {
            sMain.removeCallbacks(prev);
            sRotateSettleTask = null;
        }
        final Runnable task = new Runnable() {
            @Override
            public void run() {
                if (sRotateSettleTask != this) {
                    return;        // 被新的一次点击取代
                }
                Context ctx = sContext;
                int now = RotationHint.currentSurfaceRotation(ctx);
                boolean reached = (now == target);
                boolean timeout = android.os.SystemClock.uptimeMillis() >= sSuppressedUntil;
                if (!reached && !timeout) {
                    sMain.postDelayed(this, ROTATE_SETTLE_STEP_MS);
                    return;
                }
                sRotateSettleTask = null;
                // 收起与状态复位：这是一轮点击的终点
                LogUtil.logAlways("[旋转提示] 点击收尾：目标=" + target
                        + " 当前=" + now + (reached ? " ✔已到位" : " ⏱静默期到点（未转到位）"));
                hideButtonNow();
                // 提前结束静默期：目标已达成，让传感器的后续判断恢复正常
                sSuppressedUntil = 0L;
                // 到位后可能已经是一个新的合法不一致状态（用户继续转了手机），
                // 主动重算一次，保证旋钮按新的真实方向重新出现
                reconcileRotationHint("点击收尾后重算");
            }
        };
        sRotateSettleTask = task;
        sMain.postDelayed(task, ROTATE_SETTLE_STEP_MS);
    }





    private static void overridePosition(Object pos) {
        Context ctx = sContext;
        int sensor = sSensorTargetRotation;
        if (ctx == null || sensor < 0) {
            return;
        }

        // 物理目标角（用户逐项确认）：
        //   0 竖立 → 右下    1 → 左下    2 倒置 → 左上    3 → 右上
        int physical;
        switch (sensor) {
            case 1:
                physical = 2;   // 左下
                break;
            case 2:
                physical = 0;   // 左上
                break;
            case 3:
                physical = 1;   // 右上
                break;
            case 0:
            default:
                physical = 3;   // 右下
                break;
        }

        // 夹角换算：画面角 = (物理角 − 屏幕旋转) % 4
        //
        // ⚠️ 2026-10-21：我曾把这段「减屏幕旋转」删掉、改成直接用 physical，
        //   结果旋钮位置完全错乱（用户实测），已恢复。这段是正确的。
        final int[] CW = {0, 1, 3, 2};          // 顺时针：左上→右上→右下→左下
        // ⚠️ 2026-10-21 关键修复：不能只信 sScreenRotation 缓存 ——
        //   实测它常年是 -1（refreshScreenRotation 只在拿到 controller 时调过一次，
        //   那次失败就永远不更新），导致 rot 恒为 0，落点系统性偏移。
        //   这里**实时**读取当前屏幕 rotation。
        int rot = currentSurfaceRotation(ctx);
        if (rot < 0) {
            rot = sScreenRotation >= 0 ? sScreenRotation : 0;
        }
        int physIdx = 0;
        for (int i = 0; i < 4; i++) {
            if (CW[i] == physical) {
                physIdx = i;
                break;
            }
        }
        int screenIdx = ((physIdx - rot) % 4 + 4) % 4;
        int corner = CW[screenIdx];
        // ⚠️ 2026-10-21：曾在这里加过 `corner ^= 1`（左右镜像），
        //   用户实测「方向变成错误的，修改前是对的」，已撤销。
        //   公式本身是对的，不要动。

        int gravity;
        switch (corner) {
            case 0:
                gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
                break;
            case 1:
                gravity = android.view.Gravity.TOP | android.view.Gravity.RIGHT;
                break;
            case 2:
                gravity = android.view.Gravity.BOTTOM | android.view.Gravity.LEFT;
                break;
            case 3:
            default:
                gravity = android.view.Gravity.BOTTOM | android.view.Gravity.RIGHT;
                break;
        }

        // 边距：直接用系统实测值（本机四组：69/80/68/80，量级一致）。
        // 用 16dp ≈ 34px 会略小于系统值，但仍在 192px 容器内、不会裁切；
        // 想要和系统原生完全一致可改成 69/80，这里取一个统一的安全值。
        // ⚠️ 位移量必须**同时满足两个约束**：
        //   ① 不超出按钮所在容器的可移动空间；
        //   ② 与系统原生边距接近，视觉才协调。
        //
        //   容器实测固定 192px、按钮 112px，可移动空间只有
        //   (192 - 112) / 2 = 40px —— 超过就会被容器裁掉。
        //   之前写死 30dp：内屏 density=2.16 → 65px（已超出 40，勉强可见），
        //   外屏 density=2.75 → **82px**，超出太多 → 按钮被裁切。
        //   所以这里按 40px 硬上限夹取，并留一点余量。
        final int CONTAINER_SLACK_PX = 40;
        int margin;
        try {
            margin = (int) (20 * ctx.getResources().getDisplayMetrics().density);
            if (margin > CONTAINER_SLACK_PX) {
                margin = CONTAINER_SLACK_PX;
            }
            if (margin < 12) {
                margin = 12;
            }
        } catch (Throwable unused) {
            margin = 40;
        }

        // ⚠️ gravity 与 translation **必须成对设置**！
        //   系统原来那四组值是配好对的（本机实测，即系统自己算出来的默认边距）：
        //     BOTTOM|RIGHT (85) → tx=-69 ty=-69
        //     TOP|RIGHT    (53) → tx=-80 ty= 80
        //     TOP|LEFT     (51) → tx= 68 ty= 68
        //     BOTTOM|LEFT  (83) → tx= 80 ty=-80
        //   只换 gravity 而不动 translation，两者就会错配 ——
        //   实测：给 83(左下) 却留着 80/-80 之外的系统值，
        //   按钮被 tx=+80 往右推、ty=-80 往下推出屏幕，四个方向里三个直接看不见。
        //   所以这里按角落一并写入对应的边距（这正是系统自己的规则）。
        int tx;
        int ty;
        switch (corner) {
            case 0:   // 左上
                tx = margin;
                ty = margin;
                break;
            case 1:   // 右上
                tx = -margin;
                ty = margin;
                break;
            case 2:   // 左下
                tx = margin;
                ty = -margin;
                break;
            case 3:   // 右下
            default:
                tx = -margin;
                ty = -margin;
                break;
        }

        boolean changed = false;
        changed |= setIntField(pos, "gravity", gravity);
        changed |= setIntField(pos, "translationX", tx);
        changed |= setIntField(pos, "translationY", ty);

        if (changed) {
            // ⚡ 去重：calculatePosition 会被频繁调用，内容没变就不必重复打日志
            //   （logcat 写入本身有开销，热路径上能省则省）
            String tag = sensor + "|" + corner + "|" + gravity;
            if (!tag.equals(sLastPositionLog)) {
                sLastPositionLog = tag;
                LogUtil.logAlways("[旋转提示] 落点 传感器=" + sensor
                        + " 角落=" + corner + " gravity=" + gravity
                        + " tx=" + tx + " ty=" + ty);
            }
        }

    }

    /** 往反射出来的 Position 上写一个 int 字段。 */
    private static boolean setIntField(Object obj, String name, int value) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.setInt(obj, value);
            return true;
        } catch (Throwable unused) {
            return false;
        }
    }

    /**
     * 主动在现有窗口里找 NavigationBarView 实例。
     *
     * <p>为什么要这一步：模块注入发生在 SystemUI 进程启动之后，而导航栏
     * 在启动早期就建好了 —— 三个生命周期回调都要等「它下次动」才触发，
     * 期间传感器一直注册不上。这里用 {@code WindowManagerGlobal} 拿到
     * 所有窗口的 ViewRootImpl，从根视图递归找目标类型。
     *
     * <p>属于「尽力而为」：找不到就退回生命周期回调那条路，不影响功能。
     */
    private static void scanExistingNavBar(Class<?> target) {
        if (sController != null) {
            return;
        }
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Method getInstance = wmg.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            Object inst = getInstance.invoke(null);
            if (inst == null) {
                return;
            }
            Field rootsField = wmg.getDeclaredField("mRoots");
            rootsField.setAccessible(true);
            Object rootsObj = rootsField.get(inst);
            if (!(rootsObj instanceof java.util.List)) {
                return;
            }
            for (Object root : (java.util.List<?>) rootsObj) {
                if (root == null) {
                    continue;
                }
                Object view = null;
                for (Field f : root.getClass().getDeclaredFields()) {
                    if (android.view.View.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object v = f.get(root);
                        if (v instanceof android.view.View) {
                            // ViewRootImpl.mView 就是窗口根视图
                            view = v;
                            break;
                        }
                    }
                }
                if (view instanceof android.view.View) {
                    Object found = findNavBarInTree((android.view.View) view, target, 0);
                    if (found != null) {
                        captureController(found);
                        if (sController != null) {
                            LogUtil.logAlways("[旋转提示] 已从现有窗口主动找到导航栏");
                            return;
                        }
                    }
                }
            }
        } catch (Throwable th) {
            LogUtil.logAlwaysOnce("rh-scan-fail", "[旋转提示] 主动扫描失败: " + th);
        }
    }

    /** 在视图树里递归找目标类型的实例（限深度，避免极端情况下的性能问题）。 */
    private static Object findNavBarInTree(android.view.View v, Class<?> target, int depth) {
        if (v == null || depth > 24) {
            return null;
        }
        if (target.isInstance(v)) {
            return v;
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            int n = g.getChildCount();
            for (int i = 0; i < n; i++) {
                Object r = findNavBarInTree(g.getChildAt(i), target, depth + 1);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /**
     * 挂一个 NavigationBarView 的生命周期方法，在其后尝试取控制器。
     *
     * @return 成功挂上返回 1，否则 0（用于统计日志）
     */
    private static int hookNavBarMethod(final io.github.libxposed.api.XposedModule module,
                                        Class<?> cls, String name) {
        try {
            Method m = null;
            for (Method cand : cls.getDeclaredMethods()) {
                if (name.equals(cand.getName())
                        && !java.lang.reflect.Modifier.isAbstract(cand.getModifiers())) {
                    m = cand;
                    break;
                }
            }
            if (m == null) {
                return 0;
            }
            m.setAccessible(true);
            final String id = name;
            module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("rotation-hint-" + id)
                    .intercept(new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            try {
                                captureController(chain.getThisObject());
                            } catch (Throwable unused) {
                                // 拿不到实例不影响导航栏本身
                            }
                            return r;
                        }
                    });
            return 1;
        } catch (Throwable unused) {
            return 0;
        }
    }

    /**
     * 从导航栏视图里取出 {@code RotationButtonController} 并注册传感器。
     *
     * <p>只做一次：导航栏重建（如切换分辨率）时会再次进入，此时刷新实例即可，
     * 传感器不重复注册。
     */
    private static void captureController(Object navBar) {
        if (navBar == null) {
            return;
        }
        try {
            Method get = null;
            for (Method cand : navBar.getClass().getMethods()) {
                if ("getRotationButtonController".equals(cand.getName())
                        && cand.getParameterTypes().length == 0) {
                    get = cand;
                    break;
                }
            }
            if (get == null) {
                LogUtil.logAlwaysOnce("rh-nogetter", "[旋转提示] 导航栏没有 getRotationButtonController");
                return;
            }
            get.setAccessible(true);
            Object controller = get.invoke(navBar);
            if (controller == null) {
                return;
            }
            sController = controller;
            LogUtil.logAlways("[旋转提示] controller 类=" + controller.getClass().getName());
            try {
                refreshScreenRotation(sContext);
            } catch (Throwable unused) {
                // 拿不到就沿用缓存
            }
            sShowMethod = null;
            sCanShowMethod = null;
            if (navBar instanceof android.view.View) {
                sContext = ((android.view.View) navBar).getContext();
            }
            LogUtil.logAlwaysOnce("rh-got",
                    "[旋转提示] 已获取 RotationButtonController: " + controller.getClass().getName());
            ensureSensorRegistered(navBar);
        } catch (Throwable th) {
            LogUtil.logAlwaysOnce("rh-capture-fail", "[旋转提示] 取控制器失败: " + th);
        }
    }

    /** 从导航栏 View 取 Context 后注册传感器。 */
    private static void ensureSensorRegistered(Object navBar) {
        Context ctx = null;
        try {
            if (navBar instanceof android.view.View) {
                ctx = ((android.view.View) navBar).getContext();
            }
        } catch (Throwable unused) {
            ctx = null;
        }
        if (ctx != null) {
            sContext = ctx;
        }
        ensureSensorRegisteredWith(ctx);
    }

    /** 注册方向传感器（只注册一次）。 */
    private static void ensureSensorRegisteredWith(Context ctx) {
        if (sSensorRegistered) {
            return;
        }
        if (!RotationHost.sRotationHintEnable.get()) {
            // 开关没开就先不注册；用户开启后会通过配置重载重新走进来
            LogUtil.logAlwaysOnce("rh-off", "[旋转提示] 开关未开启，暂不注册传感器");
            return;
        }
        if (ctx == null) {
            return;
        }
        try {
            SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
            if (sm == null) {
                return;
            }
            // 🔑 2026-10-22 关键修复：SensorManager 必须在这里就存好。
            //   原来只在下面「方向传感器」分支里赋值，而本机走的是
            //   OrientationEventListener 分支（提前 return），
            //   导致 sSensorMgr 一直是 null → startGravityPolling() 一进去就返回
            //   → 重力轮询**从未真正启动过**（日志里也从来没有它的记录）。
            sSensorMgr = sm;
            // 🔑 2026-10-24：屏幕方向 / 旋转锁变化也要重算（见 ensureStateWatchers）
            ensureStateWatchers(ctx);
            // ⚠️ 实测（2026-10-19）：Wakeup 版 device_orientation 在**首次注册后
            //   只回调一次**（日志里「传感器方向=0」只出现一次，之后方向再也不更新），
            //   导致按钮位置固定在左上角。这条路不可用，回退到下面的
            //   OrientationEventListener（它能持续回调）。

            // ✅ 主路径：系统同款的 OrientationEventListener
            //   （用户 2026-10-19 决定：就用系统的方案）。
            //   它内部自带去抖，偶尔会有几秒延迟，但由系统统一管理传感器，更省心。
            android.view.OrientationEventListener oel =
                    new android.view.OrientationEventListener(ctx, 66667) {
                        @Override
                        public void onOrientationChanged(int orientation) {
                            try {
                                if (orientation == ORIENTATION_UNKNOWN) {
                                    return;
                                }
                                onOrientationDegrees(orientation);
                            } catch (Throwable unused) {
                            }
                        }
                    };
            // ⚠️ 2026-10-22 关键修复：重力轮询必须在两条分支之前注册。
            //   之前它被放在下面的「传感器」分支里，而系统只要支持
            //   OrientationEventListener 就会提前 return ——
            //   于是重力轮询根本没注册（日志里始终没有「已注册重力轮询」），
            //   表现为「按钮停在旧界面好几秒」。
            if (oel.canDetectOrientation()) {
                oel.enable();
                sOel = oel;
                sSensorRegistered = true;
                LogUtil.logAlways("[旋转提示] 已启用 OrientationEventListener（系统方案）");
                return;
            }
            LogUtil.logAlways("[旋转提示] OrientationEventListener 不可用，退回加速度计");

            // 兜底：手写加速度计注册
            Sensor sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (sensor == null) {
                LogUtil.logAlwaysOnce("rh-nosensor", "[旋转提示] 设备没有可用的方向传感器");
                return;
            }
            SensorEventListener l = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    try {
                        onOrientation(event);
                    } catch (Throwable unused) {
                    }
                }

                @Override
                public void onAccuracyChanged(Sensor sensor2, int accuracy) {
                }
            };
            sListener = l;
            sSensorMgr = sm;
            sm.registerListener(l, sensor, 66667);




            sSensorRegistered = true;
            LogUtil.logAlways("[旋转提示] 已注册方向传感器: " + sensor.getName()
                    + "（type=" + sensor.getType() + "）");
        } catch (Throwable th) {
            LogUtil.logAlwaysOnce("rh-sensor-fail", "[旋转提示] 注册传感器失败: " + th);
        }
    }

    /**
     * 传感器方向变化。
     *
     * <p>{@code TYPE_DEVICE_ORIENTATION} 的 values[0] 直接就是设备朝向角度
     * （0/90/180/270），不需要像加速度计那样自己算 atan2。
     * 若是退回加速度计的机型，则用重力分量估算。
     */
    private static void onOrientation(SensorEvent event) {
        if (!RotationHost.sRotationHintEnable.get()) {
            return;
        }
        float[] v = event.values;
        if (v == null || v.length == 0) {
            return;
        }
        int degrees;
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            // 重力分量反推设备姿态：v[0]=x、v[1]=y（竖立时 y≈+9.8）
            if (v.length < 2) {
                return;
            }
            double angle = Math.toDegrees(Math.atan2(-v[0], v[1]));
            if (angle < 0) {
                angle += 360;
            }
            degrees = normalizeDegrees((int) Math.round(angle / 90.0) * 90);
        } else {
            degrees = normalizeDegrees((int) v[0]);
        }
        onOrientationDegrees(degrees);
    }

    /**
     * 拿到「设备朝向角度（0/90/180/270）」之后的统一处理。
     *
     * <p>两条来源共用这里：系统同款的 {@code OrientationEventListener} 与
     * 手写的加速度计监听，避免逻辑重复。
     */
    /**
     * 安排「稳态后显示」的判定任务。
     *
     * <p>抽出来是为了让两处都能调用：
     * <ol>
     *   <li>方向变化时；</li>
     *   <li><b>运动检测把按钮收起之后</b> ——
     *       这是 2026-10-22 用户反馈「转着转着按钮就丢了」的真正原因：
     *       运动检测只调了 hideButtonNow()，却没安排重新显示，
     *       而方向传感器又是 on-change 类型不会再上报，按钮就永远回不来了。</li>
     * </ol>
     */


    /**
     * 用重力向量算设备朝向。
     *
     * @return rotation(0..3)；设备接近平放（重力在水平面分量太小）时返回 -1
     */
    private static int rotationFromGravity(float gx, float gy) {
        double level = Math.hypot(gx, gy);
        if (level < 5.0) {
            return -1;   // 平放，算不准，不参与
        }
        double roll = Math.toDegrees(Math.atan2(gx, gy));
        int idx = (int) Math.round(roll / 90.0);
        return ((idx % 4) + 4) % 4;
    }

    /**
     * 按需启动重力轮询 —— **只在「传感器刚旋转的那一下」工作，静默时完全关闭**。
     *
     * <p>2026-10-22 用户明确要求：
     * <b>「轮询这一块只存在于工作的那一下、传感器旋转的那一下，
     * 其他静默的时候不要使用轮询」</b>。
     *
     * <p>所以不常驻注册。生命周期：
     * <pre>
     *   方向变化      → 启动（这时才知道要盯着，看方向是否还在变）
     *   按钮显示出来  → 停止（已经判定完成）
     *   超过 3 秒     → 自动停止（兜底，避免异常路径下常驻）
     * </pre>
     */
    private static void startGravityPolling() {
        SensorManager sm = sSensorMgr;
        if (sm == null || sAccelListener != null) {
            return;
        }
        try {
            android.hardware.Sensor grav =
                    sm.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY);
            if (grav == null) {
                grav = sm.getDefaultSensor(
                        android.hardware.Sensor.TYPE_ACCELEROMETER);
            }
            if (grav == null) {
                return;
            }
            final android.hardware.SensorEventListener gl =
                    new android.hardware.SensorEventListener() {
                        @Override
                        public void onSensorChanged(android.hardware.SensorEvent e) {
                            try {
                                float gx = e.values[0];
                                float gy = e.values[1];
                                float gz = e.values[2];
                                // 运动量：三轴变化之和
                                float delta = Math.abs(gx - sLastGx)
                                        + Math.abs(gy - sLastGy)
                                        + Math.abs(gz - sLastGz);
                                sLastGx = gx;
                                sLastGy = gy;
                                sLastGz = gz;
                                long nowTick = android.os.SystemClock
                                        .uptimeMillis();
                                if (delta > MOTION_THRESHOLD) {
                                    sLastMotionAt = nowTick;
                                } else if (sShownFor >= 0
                                        && nowTick - sLastMotionAt > 3000L) {
                                    // 按钮已经显示、且确认静止 3 秒
                                    // → 这一轮旋转结束，关掉轮询。
                                    //   原来只等 1 秒：旋转间隙稍一停顿轮询就关了，
                                    //   下次方向事件漏报时又没人盯着，表现为周期性延迟。
                                    stopGravityPolling();
                                    return;
                                }
                                int rot = rotationFromGravity(gx, gy);
                                if (rot < 0) {
                                    // 设备接近平放：判断不了朝向，不参与判定。
                                    // 用户明确说这里「不用管」——
                                    // 按钮的退场交给下面的显示超时处理。
                                    sPendingAccelRotation = -1;
                                    sAccelSameCount = 0;
                                    return;
                                }
                                if (rot == sPendingAccelRotation) {
                                    sAccelSameCount++;
                                } else {
                                    sPendingAccelRotation = rot;
                                    sAccelSameCount = 1;
                                }
                                // 连续 2 次（约 200ms）同一方向即采纳
                                if (sAccelSameCount < 2
                                        || rot == sSensorTargetRotation) {
                                    return;
                                }
                                long nowPoll = android.os.SystemClock
                                        .uptimeMillis();
                                // 方向事件沉默 200ms 就交给轮询接管。
                                // 实测本机方向事件间隔常达 1~2.5 秒（几乎不上报），
                                // 轮询是主力，门槛必须低。
                                if (nowPoll - sLastSensorReportAt < 200L) {
                                    return;   // 方向事件还活跃，轮询不参与
                                }
                                LogUtil.logAlways("[旋转提示] 重力轮询兜底："
                                        + "方向事件已沉默，方向=" + rot);
                                onOrientationDegrees(((4 - rot) % 4) * 90);
                            } catch (Throwable unused) {
                            }
                        }

                        @Override
                        public void onAccuracyChanged(
                                android.hardware.Sensor sensor2, int acc) {
                        }
                    };
            long tReg = android.os.SystemClock.uptimeMillis();
            sAccelListener = gl;
            // 重置运动基准，避免上一次的残留值导致开局就误判
            sLastGx = 0f;
            sLastGy = 0f;
            sLastGz = 0f;
            sLastMotionAt = android.os.SystemClock.uptimeMillis();
            sm.registerListener(gl, grav, 100000);
            LogUtil.logAlways("[旋转提示] 重力轮询启动（旋转窗口内）");
            // 兜底：最多工作 3 秒
            // 兜底停止：只保留一个，重复启动时先取消旧的。
            // ⚠️ 原来每次都 post 一个新的 8 秒任务，连转十次就有十个任务
            //   在同一时刻集中执行 —— 表现为「转三五次就卡一下」。
            //   现在改成单例任务，并把上限放宽到 30 秒（正常由「静止」结束）。
            LogUtil.logAlways("[旋转提示] 重力轮询注册耗时 "
                    + (android.os.SystemClock.uptimeMillis() - tReg) + "ms");
            sMain.removeCallbacks(sPollStopTask);
            sMain.postDelayed(sPollStopTask, 60000L);
        } catch (Throwable th) {
            LogUtil.logAlways("[旋转提示] 重力轮询启动失败: " + th);
        }
    }

    /** 停止重力轮询（注销监听，静默期不占用传感器）。 */
    private static void stopGravityPolling() {
        SensorManager sm = sSensorMgr;
        android.hardware.SensorEventListener l = sAccelListener;
        if (sm != null && l != null) {
            try {
                sm.unregisterListener(l);
            } catch (Throwable unused) {
            }
        }
        sAccelListener = null;
        sPendingAccelRotation = -1;
        sAccelSameCount = 0;
        sLastGx = 0f;
        sLastGy = 0f;
        sLastGz = 0f;
    }


    /**
     * 显示超时任务。
     *
     * <p>2026-10-22 用户要求：
     * <b>「每一个画面都有等待时间。等待时间之后，如果它没有旋转到下一个传感器，
     * 你就可以直接计时退出了」</b>。
     *
     * <p>所以按钮出现后开始计时，这段时间内没有新的方向变化就自动收起 ——
     * 这也是系统原生旋转按钮的行为。顺带解决了「手机放桌上按钮一直不消失」。
     */
    private static final long AUTO_HIDE_MS = 3000L;
    private static final Runnable sAutoHideTask = new Runnable() {
        @Override
        public void run() {
            if (sShownFor >= 0) {
                LogUtil.logAlways("[旋转提示] 显示超时，自动收起");
                hideButtonNow();
            }
        }
    };

    private static void scheduleShow(final int stableTarget) {
        long nowSchedule = android.os.SystemClock.uptimeMillis();
        // 节流：必须明显小于 STABLE_MS，否则会把计时重置掉、按钮出不来。
        if (nowSchedule - sLastScheduleAt < 100L) {
            return;
        }
        sLastScheduleAt = nowSchedule;
        final long startedAt = nowSchedule;
        final long plannedAt = startedAt + STABLE_MS + 40L;
        Runnable showTask = new Runnable() {
            @Override
            public void run() {
                if (sPendingShow == this) {
                    sPendingShow = null;
                }
                try {
                    long nowExec = android.os.SystemClock.uptimeMillis();
                    long late = nowExec - plannedAt;
                    if (late > 100L) {
                        LogUtil.logAlways("[旋转提示] 显示任务被推迟 " + late
                                + "ms（计划=" + plannedAt + " 实际=" + nowExec + "）");
                    }
                    if (sSensorTargetRotation != stableTarget) {
                        return;
                    }
                    // 等待期间的方向确认交给重力轮询（10Hz，带连续确认）。
                    // ⚠️ 曾在这里再挂一个 40ms 的复查，结果两个方向源互相覆盖：
                    //   传感器报 A、重力算 B，两边轮流触发「方向变化」，
                    //   计时被无限重置，按钮反而更晚出现。
                    if (android.os.SystemClock.uptimeMillis() - startedAt
                            < STABLE_MS) {
                        return;
                    }
                    Context ctx = sContext;
                    if (ctx == null) {
                        LogUtil.logAlways("[旋转提示] 稳态到点但 ctx 为空");
                        return;
                    }
                    int cur = currentSurfaceRotation(ctx);
                    if (cur < 0) {
                        LogUtil.logAlways("[旋转提示] 拿不到屏幕方向，放弃本次显示");
                        return;
                    }
                    if (stableTarget == cur) {
                        LogUtil.logAlways("[旋转提示] 稳态方向与屏幕一致，隐藏旋钮");
                        hideButtonNow();
                        return;
                    }
                    showNow(stableTarget, cur);
                } catch (Throwable unused) {
                }
            }
        };
        cancelPendingShow();
        sPendingShow = showTask;
        // 先等一个稳态时间，再进入 40ms 一次的方向复查
        sMain.postDelayed(showTask, STABLE_MS + 40L);
    }

    private static void onOrientationDegrees(int degrees) {
        if (!RotationHost.sRotationHintEnable.get()) {
            return;
        }
        // 点击旋钮后的静默期：只更新方向（落点仍要正确），
        // 但不收起、不重新排队 —— 否则会把刚发起的强制旋转显示成「没反应」。
        // 🔴 v3.295.0：这里必须**更新稳态基准**（sStableTarget + sStableSince）。
        //   原来只更新 sSensorTargetRotation，而 reconcile 里以 sStableTarget 为准
        //   判断「这个方向稳定了多久」—— 静默期内传感器改过 0/2/3 之后，
        //   静默期一结束，reconcile 看到 sStableTarget 还是旧值，
        //   会拿一个很旧的 sStableSince 去算稳定时长，结论失真。
        if (isClickQuiet()) {
            int t = degreesToSurfaceRotation(degrees);
            if (t >= 0) {
                sLastSensorReportAt = android.os.SystemClock.uptimeMillis();
                sSensorTargetRotation = t;
                if (sStableTarget != t) {
                    sStableTarget = t;
                    sStableSince = android.os.SystemClock.uptimeMillis();
                }
            }
            return;
        }
        final int target = degreesToSurfaceRotation(degrees);
        if (target < 0) {
            return;
        }
        // 记录方向传感器仍然活跃（它是第一优先级）
        sLastSensorReportAt = android.os.SystemClock.uptimeMillis();
        // 去抖：传感器很灵敏，转动过程中会连续上报；延时后再判断，
        // 只按「停稳后的方向」决定弹不弹。
        // ⭐ 记录传感器方向，供落点使用（纯传感器驱动）
        if (sSensorTargetRotation != target) {
            sSensorTargetRotation = target;
            // 🔴 v3.301.2（用户 2026-11-19 反馈「第一次旋转经常没及时弹旋钮」）：
            //
            //   根因就在这一行。原来这里只更新 sStableSince、**不更新 sStableTarget**
            //   （注释说"稳态基准统一由 reconcile 维护"）。但 reconcile 一进来就发现
            //   sStableTarget != sensor，于是又把 sStableSince 重置成"现在" ——
            //   计时永远从 0 开始，stableFor 必然 < HINT_STABLE_MS，
            //   所以**每次方向刚变后的第一次判定必定不显示**，必须再等 180ms 复检。
            //   用户感知就是"第一次转没弹，要再动一下才出来"。
            //
            //   正确做法：方向变化时把「基准方向 + 计时起点」一起设好，
            //   二者始终成对更新，稳定时长才真实反映"这个方向持续了多久"。
            sStableTarget = target;
            sStableSince = android.os.SystemClock.uptimeMillis();
            LogUtil.logAlways("[旋转提示] 传感器方向=" + target);

            // 📌 用户 2026-10-21 最终逻辑：
            //   1) 方向变化 → 旋钮立即消失，并重新开始 500ms 稳态计时；
            //   2) 500ms 到点：若传感器方向 == 当前屏幕方向 → 什么都不做；否则显示旋钮。
            //   3) 方向一致时不进入计时、无视一切，直到下一次旋转。
            cancelPendingShow();

            // 📌 2026-10-22 统一成单一路径（消除分支带来的状态冲突）：
            //
            //   之前这里有个「旋钮已显示 → 平滑跟随」的分支，直接 return 了，
            //   但显隐 hook 又会把系统的显示请求改成隐藏 —— 结果按钮被收起来，
            //   却没有任何代码负责重新显示它（用户实测「转一下就再也不出现」）。
            //
            //   现在统一：方向一变 → 收起 + 重新计 500ms → 到点再决定是否显示。
            //   这也正是用户要求的「旋钮消失并重新进入稳态计时」。
            // 立即收起
            // 旋钮没显示 → 走「立即收起（保险）+ 500ms 稳态后显示」
            // 立即收起（hideNow 内部会判断线程，无需再 post 一帧）
            hideButtonNow();

            // 方向变化必须强制重新安排（清掉节流时间戳），
            // 否则会被 200ms 节流挡掉，按钮不出现。
            sLastScheduleAt = 0L;
            // 只在「刚发生旋转」这一刻启动轮询，静默期不占用传感器
            startGravityPolling();
            scheduleShow(target);

        }
        // ⚠️ 2026-10-21 删除：这里原来还有一次**无条件**的 maybeShowHint 调用，
        //   等于每次传感器回调（每 66ms）都走一遍显示判断 ——
        //   和上面 postDelayed 的稳定任务叠加，导致
        //   「按钮出现两次、动画播两遍」（用户实测反馈）。
        //   现在每次方向变化只由 postDelayed(showTask) 触发一次判断。
    }

    /** 去抖用的 token（同时充当 Runnable，removeCallbacksAndMessages 按 token 清除）。 */
    private static final Object BOUNCE_TOKEN = new Object();

    /** 当前屏幕的 Surface 旋转值；拿不到返回 -1。 */
    /** 刷新「SystemUI 所在屏」的旋转方向（用 DisplayManager，比 getDefaultDisplay 稳）。 */
    private static void refreshScreenRotation(Context ctx) {
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager)
                            ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                return;
            }
            // ⚠️ 用活跃屏（STATE_ON），不要用 DEFAULT_DISPLAY ——
            //   折叠屏上它与用户正看着的屏不一定一致（见 currentSurfaceRotation 注释）。
            int now = -1;
            for (android.view.Display d : dm.getDisplays()) {
                if (d != null && d.isValid()
                        && d.getState() == android.view.Display.STATE_ON) {
                    now = d.getRotation();
                    break;
                }
            }
            if (now < 0) {
                // 兜底：万一没有 STATE_ON 的屏（时序问题），退回默认屏
                android.view.Display dd = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
                if (dd != null) {
                    now = dd.getRotation();
                }
            }
            if (now >= 0 && sScreenRotation != now) {
                sScreenRotation = now;
                LogUtil.logAlways("[旋转提示] 屏幕方向=" + now);
            }
        } catch (Throwable unused) {
            // 拿不到就沿用缓存
        }
    }

    /**
     * 取「当前正在使用的这块屏」的 rotation。
     *
     * <p>⚠️ 2026-10-21 修复：原来用 {@code wm.getDefaultDisplay()}，
     * 在折叠屏上它返回的**不一定是用户正看着的那块屏**
     * （实测合上时 Display 0 = 外屏，展开时 Display 0 = 内屏，
     *  ID 是动态分配的）。取错屏的 rotation 会让
     * 「目标方向 vs 当前方向」的对比完全失真，表现为：
     * <ul>
     *   <li>旋转到当前方向仍弹按钮；</li>
     *   <li>锁定 270° 时按钮落位停在旧位置。</li>
     * </ul>
     *
     * <p>改为遍历 DisplayManager，取 STATE_ON 的那块屏。
     */
    private static int currentSurfaceRotation(Context ctx) {
        try {
            // 🔑 v3.294.3：与 OrientationForcer 用**同一套**取屏口径。
            //   原来这里挑「STATE_ON 的屏」，而折叠切换的过渡期里两块屏可能同时
            //   是 ON（或外屏还没灭），会读到另一半屏的 0°，导致
            //   「显示判断看到 0、实际内屏是 270」→ 旋钮位置错、该弹不弹。
            int byState = OrientationForcer.rotationByFoldState(ctx);
            if (byState >= 0) {
                return byState;
            }
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager)
                            ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) {
                for (android.view.Display d : dm.getDisplays()) {
                    if (d == null || !d.isValid()) {
                        continue;
                    }
                    if (d.getState() == android.view.Display.STATE_ON) {
                        return d.getRotation();
                    }
                }
            }
            // 兜底
            android.view.WindowManager wm =
                    (android.view.WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                android.view.Display d = wm.getDefaultDisplay();
                return d == null ? -1 : d.getRotation();
            }
            return -1;
        } catch (Throwable unused) {
            return -1;
        }
    }

    /** 角度归一到 0/90/180/270。 */
    private static int normalizeDegrees(int degrees) {
        int d = ((degrees % 360) + 360) % 360;
        return (d / 90) * 90;
    }

    /**
     * 设备角度 → 屏幕需要的 Surface 旋转。
     *
     * <p>这两个不是同一个值：Surface.ROTATION_90 表示「屏幕内容顺时针转了 90°」，
     * 对应的恰好是**设备逆时针转了 90°**。所以要把设备的物理朝向换算成
     * 「要转到哪个方向才能竖直看」。
     */
    private static int degreesToSurfaceRotation(int degrees) {
        switch (degrees) {
            case 0:
                return Surface.ROTATION_0;
            case 90:
                return Surface.ROTATION_270;
            case 180:
                return Surface.ROTATION_180;
            case 270:
                return Surface.ROTATION_90;
            default:
                return -1;
        }
    }

    /**
     * 注销传感器 —— **省电的关键**。
     *
     * <p>加速度计按 {@code SENSOR_DELAY_UI}（60Hz）上报，若常驻会持续唤醒 CPU。
     * 之前整个文件没有一处 unregister，传感器从注册起就一直跑，
     * 熄屏后也不停（实测这个姿态本身在熄屏时毫无意义，纯浪费）。
     */
    private static void unregisterSensor() {
        try {
            android.view.OrientationEventListener oel = sOel;
            if (oel != null) {
                oel.disable();
                sOel = null;
            }
        } catch (Throwable unused) {
            // 停用失败无所谓
        }
        try {
            SensorManager sm = sSensorMgr;
            SensorEventListener l = sListener;
            if (sm != null && l != null) {
                sm.unregisterListener(l);
            }
        } catch (Throwable unused) {
            // 注销失败无所谓
        }
        sSensorRegistered = false;
        sListener = null;
        LogUtil.logAlways("[旋转提示] 已注销方向传感器（省电）");
    }

    /**
     * 屏幕状态变化时调用：**只在亮屏期间注册传感器**。
     *
     * <p>旋转按钮只在亮屏时才有意义，熄屏后传感器纯属耗电。
     * 这是本模块最大的一处省电优化 —— 一天里大部分时间屏幕是灭的。
     */
    public static void onScreenStateChanged(boolean interactive) {
        if (interactive) {
            // 亮屏：若开关开着且尚未注册，则补注册
            if (RotationHost.sRotationHintEnable.get() && !sSensorRegistered && sContext != null) {
                ensureSensorRegisteredWith(sContext);
            }
            // 🔑 2026-10-24：亮屏/唤醒后重算一次。
            //   熄屏期间传感器被注销，方向可能已经变了但没人记录；
            //   而且「唤醒 → 转屏幕」是最常见的使用场景，必须在这里有入口。
            sMain.postDelayed(new Runnable() {
                @Override
                public void run() {
                    reconcileRotationHint("亮屏/唤醒");
                }
            }, 400L);
        } else {
            // 熄屏：立即注销，避免后台常驻唤醒 CPU
            unregisterSensor();
        }
    }

    /**
     * 用户改了开关后调用：把开关打开才注册传感器。
     *
     * <p>由 MainHook 在配置重载时调用，避免「开开关前已经错过注册时机」。
     */
    public static void onPrefsChanged() {
        LogUtil.logAlwaysOnce("rh-prefs", "[旋转提示] 配置变更：开关=" + RotationHost.sRotationHintEnable.get()
                + " 已注册=" + sSensorRegistered
                + " 有控制器=" + (sController != null)
                + " 有Context=" + (sContext != null));
        if (!RotationHost.sRotationHintEnable.get()) {
            // 关掉开关时重置「已提示」记录，下次打开能重新提示
            sLastHintedRotation = -1;
            // ⚡ 同时注销传感器：功能关掉后没有任何理由让它继续 60Hz 上报
            unregisterSensor();
            hideButtonNow();
            // v3.292.0：关掉旋钮就等于关掉「强制」入口，全局的
            // 「忽略应用方向请求」也必须一起还回去，否则别的应用会莫名其妙跟着转
            OrientationForcer.release("旋转提示已关闭");
            return;
        }
        // 开关刚打开时补一次判定：可能此时方向早就和屏幕不一致了
        sMain.postDelayed(new Runnable() {
            @Override
            public void run() {
                reconcileRotationHint("开关刚打开");
            }
        }, 300L);
        if (sSensorRegistered) {
            return;
        }
        // 开关刚被打开，但之前可能因为开关是关的而没注册传感器。
        // 分两种情况补：
        //   · 已经有 Context（说明导航栏出现过）→ 就地注册传感器；
        //   · 连 Context 都没有 → 说明整个进程还没走到过导航栏回调，
        //     主动扫一次现有视图树去找它。
        if (sContext != null) {
            ensureSensorRegisteredWith(sContext);
            return;
        }
        try {
            Class<?> cls = Class.forName(NAV_BAR_CLASS);
            scanExistingNavBar(cls);
        } catch (Throwable unused) {
            // 类还没加载就等生命周期回调，不影响功能
        }
    }
}
