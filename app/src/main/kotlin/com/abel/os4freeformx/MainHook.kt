package com.abel.os4freeformx

import android.content.Context
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * OS4FreeFromX 入口（LibXposed API 102）。
 * 作用域：system(server) —— 小窗 bounds 计算与恢复；com.android.systemui —— 小窗装饰（沉浸底栏 / 尺寸拖动）。
 */
class MainHook : XposedModule() {

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        runCatching {
            Logx.attach(this)
            Cfg.attachRemote(this)
            Logx.always("模块已加载 v${Constants.VERSION}（API ${apiVersion}，进程=${param.processName}，systemServer=${param.isSystemServer}）")
        }.onFailure { android.util.Log.e(Constants.TAG, "onModuleLoaded", it) }
    }

    /** system_server 走这个回调（不会给 `system` 发 onPackageLoaded），2.2 的恢复 hook 装在这里。 */
    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        try {
            Logx.attach(this)
            Cfg.setModule(this)   // 只记引用，不碰 prefs/Context（见 Hooks.installSystemServer 注释）
            Logx.always("SystemServerStarting: 安装 system_server hooks")
            Hooks.installSystemServer(this, param.classLoader)
        } catch (t: Throwable) {
            Logx.e("onSystemServerStarting 失败", t)
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val pkg = param.packageName
        try {
            Logx.v("onPackageLoaded: $pkg")
            Cfg.reload()
            when (pkg) {
                Constants.PKG_SYSTEM -> Hooks.installSystemServer(this, param.defaultClassLoader)
                Constants.PKG_SYSTEMUI -> Hooks.installSystemUi(this, param.defaultClassLoader)
            }
        } catch (t: Throwable) {
            Logx.e("onPackageLoaded($pkg) 失败", t)
        }
    }

    /** 直接 hook 一个已拿到的方法对象。 */
    internal fun hookExecutable(m: java.lang.reflect.Method, hooker: XposedInterface.Hooker): Boolean = try {
        hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(hooker)
        true
    } catch (t: Throwable) {
        Logx.e("hook 异常 ${m.declaringClass.name}#${m.name}", t)
        false
    }

    /** 统一 hook 包装：整体 try/catch + PROTECTIVE，失败只记日志绝不影响系统。 */
    internal fun hookMethod(
        cl: ClassLoader,
        className: String,
        methodName: String,
        paramTypes: Array<Class<*>>?,
        hooker: XposedInterface.Hooker
    ): Boolean {
        return try {
            val cls = Class.forName(className, false, cl)
            val m = if (paramTypes == null)
                cls.declaredMethods.firstOrNull { it.name == methodName }
            else
                cls.getDeclaredMethod(methodName, *paramTypes)
            if (m == null) {
                Logx.e("hook 失败，找不到方法 $className#$methodName")
                false
            } else {
                hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(hooker)
                Logx.always("hook 成功 $className#$methodName")
                true
            }
        } catch (t: Throwable) {
            Logx.e("hook 异常 $className#$methodName", t)
            false
        }
    }
}
