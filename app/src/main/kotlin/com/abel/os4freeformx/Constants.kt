package com.abel.os4freeformx

/** 模块常量：包名、prefs 名、配置键、目标类名。 */
object Constants {
    const val VERSION = "0.1.1"
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
    const val K_RESIZE = "resize_enabled"            // 2.3 拖动调整
    const val K_DEFAULT_W = "default_w"              // 默认小窗宽（px，0=系统默认）
    const val K_DEFAULT_H = "default_h"
    const val K_GESTURES = "gestures"                // 手势总开关（全局输入挂钩）
    const val K_CORNER_FREEFORM = "corner_freeform"  // 角落斜滑 → 前台应用转小窗
    const val K_FOUR_FINGER_SPLIT = "four_finger_split" // 四指上滑 → 增加分屏
    /** 选择器回传：`<一次性 token>|<选中的包名>` */
    const val K_PICK = "pending_pick"
    /** 调试用：打开 adb 测试轮询（默认关，会周期性跨进程 call provider） */
    const val K_TEST_HOOK = "test_hook"
    /** 分屏内也用四指加分屏（灰度开关，默认关：SoSc 上加 stage 风险高） */
    const val K_FOUR_FINGER_INDOOR = "four_finger_split_indoor"
    /** 测试入口：`<pkg>|<taskId>` —— 写进 CFG 后由 SystemUI 侧轮询取走并直接执行"加分屏" */
    const val K_TEST_ADDSPLIT = "pending_test_addsplit"

    const val DEF_ENABLE_LOG = false
    const val DEF_IMMERSIVE = true
    const val DEF_REMEMBER_BOUNDS = true
    const val DEF_REMEMBER_FOLD = true
    const val DEF_RESIZE = true
    const val DEF_GESTURES = true
    const val DEF_TEST_HOOK = false
    const val DEF_FOUR_FINGER_INDOOR = false
    const val DEF_CORNER_FREEFORM = true
    const val DEF_FOUR_FINGER_SPLIT = true

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
