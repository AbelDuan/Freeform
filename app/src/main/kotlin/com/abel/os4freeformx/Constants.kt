package com.abel.os4freeformx

/** 模块常量：包名、prefs 名、配置键、目标类名。 */
object Constants {
    const val VERSION = "0.4.0"
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

    const val DEF_ENABLE_LOG = false
    const val DEF_IMMERSIVE = true
    const val DEF_REMEMBER_BOUNDS = true
    const val DEF_REMEMBER_FOLD = true
    const val DEF_RATIO_MENU = true
    const val DEF_GESTURES = true
    const val DEF_CORNER_FREEFORM = true
    const val DEF_GESTURE_HANDLE = true
    const val DEF_GESTURE_HANDLE_FOLLOW = true
    const val DEF_GESTURE_HANDLE_TOUCH = true
    const val DEF_GESTURE_HANDLE_IDLE = true
    const val DEF_GESTURE_HANDLE_AREA = 24f
        const val DEF_NBI_ASSIGN = ""

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
    /** 分屏布局：setDivideRatio(id) 按吸附目标的 snapPosition 定位 */
    const val CLS_SPLIT_LAYOUT = "com.android.wm.shell.common.split.SplitLayout"
    /** 小窗位置/尺寸计算（boot classpath: /system_ext/framework/miui-framework.jar） */
    const val CLS_MULTIWINDOW_UTILS = "android.util.MiuiMultiWindowUtils"
}
