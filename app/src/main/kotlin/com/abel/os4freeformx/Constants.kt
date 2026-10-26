package com.abel.os4freeformx

/** 模块常量：包名、prefs 名、配置键、目标类名。 */
object Constants {
    const val VERSION = "0.7.1"
    const val PREFS_CFG = "os4freeformx_cfg"
    const val PREFS_BOUNDS = "os4freeformx_bounds"
    const val AUTHORITY = "com.abel.os4freeformx.store"
    const val ACTION_RELOAD = "com.abel.os4freeformx.action.RELOAD_PREFS"
    const val TAG = "OS4FreeFromX"

    const val PKG_SYSTEM = "system"
    const val PKG_SYSTEMUI = "com.android.systemui"

    // ---- 配置键 ----
    const val K_ENABLE_LOG = "enable_log"
    const val K_IMMERSIVE = "immersive"              // 2.4 沉浸式底栏
    const val K_REMEMBER_BOUNDS = "remember_bounds"  // 2.2 分应用记忆
    const val K_REMEMBER_FOLD = "remember_fold"      // 折叠态分别记忆
    // 小窗比例调节（三点菜单那排比例按钮 + 放大可调尺寸范围）。
    // 关掉后模块完全不碰 MIUI 的菜单与缩放上限 —— 避免"背景框大于应用可操作区、底部一片白"。
    const val K_RATIO_MENU = "ratio_menu"
    // 注：原「小窗无极调节」（K_FREE_RATIO / freeform_free_ratio）已于 v0.7.0 移除。
    //     实测四角拖动自由宽高比会与系统的比例吸附 / destination 几何互相覆盖，
    //     内容层与背景层难以始终自洽，稳定性不足以进正式版，故整体下线。
    // ── 分屏四项（移植自 HyperRefine）──
    /** 解除分屏方向限制：竖屏也能左右分屏、横屏也能上下分屏。实现见 SplitDirection.kt。 */
    const val K_SPLIT_UNLOCK_DIR = "split_unlock_direction"
    /** 多分屏（**3 个及以上**，不含 2 分屏）无级调节各应用宽度。实现见 MultiSplitRatio.kt。 */
    const val K_MULTI_SPLIT_FREE = "multi_split_free_ratio"
    /** 切换分屏方向（左右 ↔ 上下）时保持每个应用的占比。实现见 SplitRatioKeep.kt。 */
    const val K_SPLIT_RATIO_KEEP = "split_ratio_keep"
    /** 把手（···）菜单同时显示左右与上下两对分屏按钮。实现见 CaptionSplitBoth.kt。 */
    const val K_CAPTION_SPLIT_BOTH = "caption_split_both"
    /**
     * **全屏应用的把手菜单**（顶部悬浮「···」）里补出四个方向的分屏图标（左/右/上/下）。
     *
     * 系统在全屏（windowingMode=1）菜单里本来不放分屏按钮，而且本机 `lhasa` 展开态下
     * `MiuiDecorationDot#isHideSplitButtons()` 还额外把分屏按钮藏掉，于是全屏时只能进小窗、
     * 不能直接进分屏。实现见 CaptionSplitBoth.kt（`hookAddFreeformStateButton`）。
     */
    const val K_FULLSCREEN_SPLIT_ICON = "fullscreen_split_icon"
    // 注：原 K_RESIZE（"允许拖动调整尺寸"）是死开关：值存了、也读了，但全库没有任何代码用它，
    //     已移除（角柄缩放本来就是 MIUI 原生行为，模块不接管）。
    // 手势总开关已移除（用户 2026-10-02）：只保留「左右下角内滑→小窗」这一个手势，常开。
    // 仍读该键是为了兼容老配置，但设置页不再提供开关。
    const val K_GESTURES = "gestures"
    const val K_CORNER_FREEFORM = "corner_freeform"  // 角落斜滑 → 前台应用转小窗

    // ---- 小白条（手势导航条）----
    const val K_GESTURE_HANDLE = "gesture_handle"                // 总开关
    const val K_GESTURE_HANDLE_FOLLOW = "gesture_handle_follow"  // 小白条跟随手指滑动
    const val K_GESTURE_HANDLE_TOUCH = "gesture_handle_touch"    // 触摸小白条区域时显隐
    const val K_GESTURE_HANDLE_IDLE = "gesture_handle_idle"      // 空闲自动隐藏（沉浸）
    const val K_GESTURE_HANDLE_AREA = "gesture_handle_area"      // 底部命中带距离(dp，float)
    // ── 导航栏（NBI）：一份名单，每个应用只归属一个功能（避免两边都选）──
    const val K_NBI_ASSIGN = "nbi_assign"   // 逐行 "包名=模式"：1=隐藏导航栏 2=导航栏取色
    const val NBI_NONE = 0
    const val NBI_HIDE = 1
    const val NBI_SAMPLE = 2

    // ── 小窗增强（移植自 HyperCeiler，AGPL-3.0；出处见文件末尾）──
    /** 强制允许应用以小窗打开（不再受 MIUI 支持名单限制） */
    const val K_FORCE_FREEFORM = "force_freeform"
    /**
     * 通知栏下滑开小窗：解除 `AppMiniWindowManagerImpl#canNotificationSlide` 的应用限制，
     * 让任意应用的通知都能在通知栏下拉后滑出小窗入口（移植自 HyperFreeformX 1.3.0）。
     */
    const val K_NOTIFICATION_SLIDE = "notification_slide"
    /** 不限制小窗数量（多小窗） */
    const val K_MULTI_FREEFORM = "multi_freeform"
    /** 贴边小窗强制前台 */
    const val K_EDGE_FREEFORM_FRONT = "edge_freeform_front"
    // ── 魔法窗口（magic-windows）：宽屏把应用收成固定比例 + 两侧模糊 ──
    /** 总开关 */
    const val K_MAGIC_WINDOW = "magic_window"
    /** 逐行 "包名=比例"，比例取值同 [MagicWindow.RATIOS]（如 "16:9"） */
    const val K_MAGIC_RATIO_MAP = "magic_ratio_map"

    /** 调试开关：system_server 侧几何探针（198 个钩子，日志量大，默认关） */
    const val DBG_GEO_PROBE = false

    /** 多小窗上限：256 = HyperCeiler 用值，等价于"实际不限制" */
    const val FREEFORM_MAX_COUNT = 256

    const val DEF_ENABLE_LOG = false
    const val DEF_IMMERSIVE = true
    const val DEF_REMEMBER_BOUNDS = true
    const val DEF_REMEMBER_FOLD = true
    const val DEF_RATIO_MENU = true
    // 分屏四项默认开（移植即生效；均为热开关，关掉立刻退回系统原行为，不用重启 SystemUI）
    const val DEF_SPLIT_UNLOCK_DIR = true
    const val DEF_MULTI_SPLIT_FREE = true
    const val DEF_SPLIT_RATIO_KEEP = true
    const val DEF_CAPTION_SPLIT_BOTH = true
    const val DEF_FULLSCREEN_SPLIT_ICON = true
    const val DEF_GESTURES = true
    const val DEF_CORNER_FREEFORM = true
    const val DEF_GESTURE_HANDLE = true
    const val DEF_GESTURE_HANDLE_FOLLOW = true
    const val DEF_GESTURE_HANDLE_TOUCH = true
    const val DEF_GESTURE_HANDLE_IDLE = true
    const val DEF_GESTURE_HANDLE_AREA = 24f
        const val DEF_NBI_ASSIGN = ""
    // 魔法窗口：默认关闭（零行为变化，用户在设置里逐个应用打开）
    const val DEF_MAGIC_WINDOW = false
    const val DEF_MAGIC_RATIO_MAP = ""

    // 小窗增强默认值（用户 2026-10-15 要求移植，默认开）
    const val DEF_FORCE_FREEFORM = true
    const val DEF_MULTI_FREEFORM = true
    const val DEF_EDGE_FREEFORM_FRONT = true
    const val DEF_NOTIFICATION_SLIDE = true

    // ---- 目标类（HyperOS 4 / Android 17 实测确认）----
    /** wm shell 小窗装饰（systemui 进程，来自 /system_ext/framework/Miui-WindowManager-Shell.jar） */
    const val CLS_DECOR_INFO = "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationInfo"
    const val CLS_DECOR_BASE = "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationBase"
    const val CLS_DECOR_DOT = "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationDot"
    const val CLS_DECOR_BOTTOM = "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationBottom"
    const val CLS_DECOR_CONTROLLER = "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationController"
    const val CLS_DECOR_DOT_VIEW = "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationDotView"
    const val CLS_DECOR_BOTTOM_VIEW = "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationBottomView"
    const val CLS_DECOR_VIEW_MODEL = "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.MulWinSwitchDecorViewModel"
    const val CLS_DECOR_IMMERSIVE = "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationImmersiveHelper"
    const val CLS_BOTTOM_VIEW_HOST = "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationBottomViewHost"
    const val CLS_DOT_VIEW_HOST = "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationDotViewHost"
    /** ★ v0.4.43：旋转时"算旋转后的 bounds 与 scale"的那个类（用户点破的正解入口） */
    const val CLS_FREEFORM_MODE_UTILS = "com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeUtils"
    /**
     * SystemUI 通知行的小窗闸门（本机 MiuiSystemUI.apk classes2.dex 实测确认）。
     * `canNotificationSlide(String, PendingIntent): boolean` 的两个调用方都在
     * `ExpandableNotificationRowInjector#updateFreeformTipView / #updateMiniWindowBar`，
     * 即「通知栏里这条通知能不能滑出小窗入口」。
     */
    const val CLS_APP_MINI_WINDOW =
        "com.android.systemui.statusbar.notification.policy.AppMiniWindowManagerImpl"

    /** MIUI 小窗装饰的触摸监听（探针：确认装饰层是否收得到触摸） */
    const val CLS_DECOR_TOUCH = "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationTouchListener"
    /** MIUI 自带的小窗角标/缩放描边视觉（角柄已交回原生，不再 hook） */
    const val CLS_CORNER_TIP = "com.android.wm.shell.multitasking.common.corner.MultiTaskingCornerTipAndStrokeController"
    /** 三点菜单：容器（纯代码 LinearLayout）与它的高度来源 */
    const val CLS_CAPTION_CONTAINER =
        "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.handlemenu.MiuiCaptionContainerView"
    const val CLS_EXTEND_MODE_INFO =
        "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.handlemenu.MiuiExtendModeInfo"
    const val CLS_RES_MANAGER = "com.android.wm.shell.multitasking.miuimultiwinswitch.MulWinSwitchResourceManager"
    /** 缩放动画目标：`setAnimParam(bounds, scaleX, scaleY, anchorY)`，也是 MIUI 自己提交 bounds 的收尾路径 */
    const val CLS_ANIM_TARGET = "com.android.wm.shell.multitasking.common.animation.MultiTaskingAnimTarget"
    const val CLS_MULTITASKING_CTL = "com.android.wm.shell.dagger.MultiTaskingControllerImpl"
    const val CLS_SHELL_TASK_ORG = "com.android.wm.shell.ShellTaskOrganizer"
    /** 角柄缩放：MIUI 的上下限公式从这里取参数 */
    const val CLS_FF_TASK_INFO = "com.android.wm.shell.multitasking.common.taskmanager.MiuiFreeformModeTaskInfo"
    /** 装饰的阴影/底色层（用户说的“背景”就是它） */
    const val CLS_DECOR_SHADOW =
        "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationShadow"
    /** 迷你/贴边态的点击恢复（getRestoredBounds 给出恢复目标） */
    const val CLS_MINI_HANDLER =
        "com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeMiniStateHandler"
    const val CLS_FF_RESIZE_HANDLER = "com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeResizeHandler"
    /** 双应用分屏的把手吸附算法（HyperOS 4 的 AOSP 分屏路径） */
    const val CLS_DIVIDER_SNAP = "com.android.wm.shell.common.split.DividerSnapAlgorithm"
    /** 折叠屏的分隔条视图（拖动入口 onTouch） */
    const val CLS_SOSC_DIVIDER_VIEW = "com.android.wm.shell.sosc.common.split.DividerView"
    /** 折叠屏走的那套（SoSc）分屏布局 */
    const val CLS_SOSC_SPLIT_LAYOUT = "com.android.wm.shell.sosc.common.split.SplitLayout"
    /** 折叠屏走的那套（SoSc）分屏吸附算法 */
    const val CLS_SOSC_DIVIDER_SNAP = "com.android.wm.shell.sosc.common.split.DividerSnapAlgorithm"
    // ── 分屏四项（移植自 HyperRefine，类名均与本机 _recon/out_jadx 逐条核对）──
    /** 分屏能力总闸：`allowLeftRightSplitInPortrait(Context)` / `isLeftRightSplit()` */
    const val CLS_SPLIT_UTILS_IMPL = "com.android.wm.shell.common.split.SplitUtilsImpl"
    /** `SplitUtilsImpl` 的单例入口（`getInstance()`） */
    const val CLS_SPLIT_UTILS_STUB = "com.android.wm.shell.common.split.SplitUtilsStub"
    /** `allowLeftRightSplitInPortrait(Resources)`（静态） */
    const val CLS_SPLIT_SCREEN_UTILS = "com.android.wm.shell.common.split.SplitScreenUtils"
    /** SoSc 分屏总服务：`supportTopBottomSplitFeature/Device()`、`switchSplitDirection()` 等 */
    const val CLS_SOSC_UTILS_IMPL = "com.android.wm.shell.sosc.SoScUtilsImpl"
    /** SoSc 分屏协调器：`switchSplitDirection(String)`、`updateDividerFixedState(boolean)` */
    const val CLS_SOSC_STAGE_COORD = "com.android.wm.shell.sosc.SoScStageCoordinator"
    /** 把手菜单未进分屏时的方向来源（`isLeftRightSplit()` 静态） */
    const val CLS_MULWIN_SPLIT_UTILS =
        "com.android.wm.shell.multitasking.miuimultiwinswitch.MulWinSwitchSplitUtils"
    /** 多分屏（3 个及以上）吸附算法：`calculateSnapTarget/snap/isValidWidth` */
    const val CLS_MULTI_SPLIT_SNAP = "com.android.wm.shell.multiplesplit.MultipleSplitSnapAlgorithm"
    /** 多分屏吸附目标（非静态内部类，构造需外部实例） */
    const val CLS_MULTI_SPLIT_SNAP_TARGET =
        "com.android.wm.shell.multiplesplit.MultipleSplitSnapAlgorithm\$SnapTarget"
    /** 多分屏布局：`getSnapPosition(int)`、`mCurrentDividerPosition`（size+1 = 当前分屏数量） */
    const val CLS_MULTI_SPLIT_LAYOUT = "com.android.wm.shell.multiplesplit.MultipleSplitLayout"
    /** 把手菜单圆点的缩放/枢轴动画：`updateDotPositionInfo(5 参)` */
    const val CLS_DECOR_ANIM =
        "com.android.wm.shell.multitasking.miuimultiwinswitch.miuiwindowdecor.decoration.MiuiDecorationAnim"
    /** 显示尺寸（补宽后的菜单按屏幕边界避让） */
    const val CLS_MULTITASKING_DISPLAY_INFO = "com.android.wm.shell.multitasking.common.MultiTaskingDisplayInfo"
    // ── 魔法窗口（magic-windows）—— 来自 /system_ext/framework/miui-embedding-window.jar ──
    /** 嵌入窗口总服务：isEmbeddingEnabledForPackage(String) 是"该包是否走 magic-windows"的总闸（纯函数） */
    const val CLS_EMBED_SVC = "com.android.server.wm.MiuiEmbeddingWindowService"
    /** 规则表：isEmbeddingEnabledForPackage 的真实实现类 */
    const val CLS_EMBED_RULE = "com.android.server.wm.MiuiSystemEmbeddedRule"
    /** 固定宽高比服务（决定 16:9 等比例，cmd MiuiSizeCompat 的实现） */
    const val CLS_SIZE_COMPAT = "com.android.server.wm.MiuiSizeCompatInternal"

    /** 分屏布局：setDivideRatio(id) 按吸附目标的 snapPosition 定位 */
    const val CLS_SPLIT_LAYOUT = "com.android.wm.shell.common.split.SplitLayout"

    /**
     * 分屏「16:9 磁吸档」的小边像素（用户 2026-10-18 实测定稿，2026-10-24 要求恢复）。
     *
     * 物理含义：小窗的短边 = 940px 时，恰好是「16:9 视频旋转后的竖条」——
     *   左窗 940 ÷ 屏高 1672 = 0.5622，而 9 ÷ 16 = 0.5625（只差分割线取整）。
     *   左窗放横屏视频：左右满铺、上下无黑边。
     *
     * 吸在固定像素而不是固定百分比，是为了内屏/外屏、横屏/竖屏都得到同样大小的"小边"；
     * 磁吸半径 5% 跨度（用户指定，2% 实测太窄划不过去）。
     */
    const val SPLIT_SMALL_SIDE_PX = 940
    /** 小窗位置/尺寸计算（boot classpath: /system_ext/framework/miui-framework.jar） */
    const val CLS_MULTIWINDOW_UTILS = "android.util.MiuiMultiWindowUtils"
}
