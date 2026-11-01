// OS4 Rust 桌面 — 手势条分屏/小窗判定放宽
// 目标进程: com.miui.home  (libapp_launcher.so)
// 加载感知: inline hook libdl.so::android_dlopen_ext (事件驱动, 无轮询)
// 判定放宽: inline hook GestureTouchableRegion::match_region_check (VA 0x61690c)
#include <jni.h>
#include <android/log.h>
#include <sys/system_properties.h>
#include <dlfcn.h>
#include <link.h>
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <unistd.h>
#include <atomic>
#include "zygisk.hpp"
#include "And64InlineHook.hpp"

#define TAG "Os4RustTouch"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using zygisk::Api;
using zygisk::AppSpecializeArgs;

// match_region_check 在 libapp_launcher.so 中的固定 VA（来自 .gnu_debugdata 符号表）
static constexpr uintptr_t OFF_MATCH_REGION_CHECK = 0x61690c;

// ---------- 配置 ----------
static std::atomic<int>   g_extra_dp{0};     // 左右各放宽的 dp
static std::atomic<int>   g_top_permille{0}; // 顶部下探千分比 (0..150 = 0%..15%)
static std::atomic<int>   g_enabled{1};
static int  g_density  = 420;
static int  g_screen_h = 2400;

static int prop_int(const char* k, int def) {
    char v[PROP_VALUE_MAX] = {0};
    if (__system_property_get(k, v) <= 0) return def;
    return atoi(v);
}
static void load_config() {
    g_enabled.store(prop_int("persist.os4rusttouch.enable", 1));
    int extra = prop_int("persist.os4rusttouch.extra_dp", 0);
    if (extra < 0) extra = 0;
    if (extra > 200) extra = 200;
    g_extra_dp.store(extra);
    int top = prop_int("persist.os4rusttouch.top_permille", 0);
    if (top < 0) top = 0;
    if (top > 150) top = 150;          // 上限 15%
    g_top_permille.store(top);
    LOGI("config: enable=%d extra_dp=%d top_permille=%d", g_enabled.load(), extra, top);
}

// ---------- 判定放宽 ----------
typedef uint32_t (*match_fn_t)(void* self, uint32_t flag, float x, float y);
static match_fn_t g_orig_match = nullptr;

static uint32_t my_match(void* self, uint32_t flag, float fx, float fy) {
    if (!g_orig_match) return 3;
    uint32_t r = g_orig_match(self, flag, fx, fy);
    if (r != 3) return r;              // 原生命中 → 放行
    if (!g_enabled.load()) return 3;

    int extraDp = g_extra_dp.load();
    int topPm   = g_top_permille.load();
    if (extraDp == 0 && topPm == 0) return 3;

    int extraPx = (int)((float)extraDp * (float)g_density / 160.0f);
    int topPx   = (int)((float)topPm * (float)g_screen_h / 1000.0f);

    const int32_t* R = (const int32_t*)self;
    int xi = (int)fx, yi = (int)fy;
    for (int i = 0; i < 4; ++i) {
        int32_t l = R[i*4+0], t = R[i*4+1], rr = R[i*4+2], b = R[i*4+3];
        if (rr <= l || b <= t) continue;       // 空矩形
        if (xi >= l - extraPx && xi < rr + extraPx &&
            yi >= t - topPx   && yi < b  + topPx) {
            LOGI("relaxed rect%d hit (%d,%d) base[%d,%d,%d,%d] +%dpx/+%dpx",
                 i, xi, yi, l, t, rr, b, extraPx, topPx);
            if (i == 3) return 2;
            if (i == 1) return 0;
            return 1;
        }
    }
    return 3;
}

// ---------- 挂载判定 hook ----------
static std::atomic<bool> g_installed{false};

// 一次性取模块基址（dl_iterate_phdr 遍历，非轮询）
struct BaseProbe { uintptr_t base; bool found; };

static int phdr_cb(struct dl_phdr_info* info, size_t, void* data) {
    if (!info->dlpi_name || !*info->dlpi_name) return 0;
    if (strstr(info->dlpi_name, "libapp_launcher.so")) {
        BaseProbe* bp = (BaseProbe*)data;
        bp->base  = (uintptr_t)info->dlpi_addr;
        bp->found = true;
        return 1;   // 停止遍历
    }
    return 0;
}

static void install_match_hook(void*) {
    if (g_installed.exchange(true)) return;

    BaseProbe bp{0, false};
    dl_iterate_phdr(phdr_cb, &bp);
    if (!bp.found) {
        LOGE("未在已加载映像中找到 libapp_launcher.so");
        g_installed.store(false);
        return;
    }
    void* target = (void*)(bp.base + OFF_MATCH_REGION_CHECK);
    LOGI("libapp_launcher.so base=0x%lx target=0x%lx",
         (unsigned long)bp.base, (unsigned long)target);

    int dens = prop_int("ro.sf.lcd_density", 0);
    if (dens > 0) g_density = dens;
    int sh = prop_int("persist.os4rusttouch.screen_h", 0);
    if (sh > 0) g_screen_h = sh;
    LOGI("density=%d screen_h=%d", g_density, g_screen_h);

    void* orig = nullptr;
    A64HookFunction(target, (void*)my_match, &orig);
    if (!orig) {
        LOGE("A64HookFunction 失败");
        g_installed.store(false);
        return;
    }
    g_orig_match = (match_fn_t)orig;
    LOGI("match_region_check hook 成功, orig=%p", orig);
}

// ---------- 加载感知 ----------
typedef void* (*dlopen_ext_t)(const char*, int, const void*);
static dlopen_ext_t g_orig_dlopen_ext = nullptr;

static void* my_dlopen_ext(const char* filename, int flag, const void* extinfo) {
    if (!g_orig_dlopen_ext) return nullptr;
    void* h = g_orig_dlopen_ext(filename, flag, extinfo);
    if (h && filename && strstr(filename, "libapp_launcher.so")) {
        LOGI("检测到 libapp_launcher.so 加载: %s", filename);
        install_match_hook(h);
    }
    return h;
}

static void install_dlopen_hook() {
    void* libdl = dlopen("libdl.so", RTLD_NOW | RTLD_NOLOAD);
    if (!libdl) { void* x = dlopen("libdl.so", RTLD_NOW); libdl = x; }
    if (!libdl) { LOGE("libdl.so 打不开"); return; }
    void* sym = dlsym(libdl, "android_dlopen_ext");
    if (!sym) { LOGE("android_dlopen_ext 未找到"); return; }

    void* orig = nullptr;
    A64HookFunction(sym, (void*)my_dlopen_ext, &orig);
    if (!orig) { LOGE("hook android_dlopen_ext 失败"); return; }
    g_orig_dlopen_ext = (dlopen_ext_t)orig;
    LOGI("android_dlopen_ext hook 成功 @%p", sym);
}

// ---------- Zygisk 模块 ----------
class Os4RustTouch : public zygisk::ModuleBase {
public:
    void onLoad(Api* api, JNIEnv* env) override { api_ = api; env_ = env; }

    void preAppSpecialize(AppSpecializeArgs* args) override {
        const char* nice = env_->GetStringUTFChars(args->nice_name, nullptr);
        if (nice) {
            is_target_ = (strcmp(nice, "com.miui.home") == 0);
            env_->ReleaseStringUTFChars(args->nice_name, nice);
        }
        if (!is_target_) {
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        LOGI("目标进程 com.miui.home 命中");
    }

    void postAppSpecialize(const AppSpecializeArgs*) override {
        if (!is_target_) return;
        load_config();
        install_dlopen_hook();
        // 一次性探测：已加载则立刻 hook（不是轮询）
        void* h = dlopen("libapp_launcher.so", RTLD_NOW | RTLD_NOLOAD);
        if (h) {
            LOGI("libapp_launcher.so 已加载，立即挂 hook");
            install_match_hook(h);
        } else {
            LOGI("libapp_launcher.so 未加载，等待 dlopen 事件");
        }
    }

private:
    Api*    api_ = nullptr;
    JNIEnv* env_ = nullptr;
    bool    is_target_ = false;
};

REGISTER_ZYGISK_MODULE(Os4RustTouch)

// ---------- LSPosed native_init 入口 ----------
// 由模块 APK 内 META-INF/xposed/native_init.list 声明后，LSPosed 在目标进程里 dlopen 本库，
// 动态链接器随即执行下面的构造函数。
// 触发条件只用 libapp_launcher.so：它只存在于澎湃桌面进程，天然唯一，不必判断进程名。
__attribute__((constructor)) static void os4_native_init() {
    LOGI("native_init: 已注入进程，开始安装加载感知");
    load_config();
    install_dlopen_hook();

    // 与 dlopen 事件是同一件事的两个真实时序分支：
    // 若本库晚于 libapp_launcher.so 被加载，hook 已错过加载事件，此时它必然已在映射表中。
    BaseProbe bp{0, false};
    dl_iterate_phdr(phdr_cb, &bp);
    if (bp.found) {
        LOGI("native_init: libapp_launcher.so 已映射，直接安装判定 hook");
        install_match_hook(nullptr);
    } else {
        LOGI("native_init: libapp_launcher.so 尚未加载，等待 dlopen 事件");
    }
}

// ---------- LSPosed native_init 导出符号 ----------
// LSPosed 的 do_dlopen hook 在库加载后执行 dlsym(handle, "native_init")，
// 取不到就直接放弃（见 LSPosed core/src/main/jni/src/native_api.cpp:112-117）。
// 所以这里必须有一个【导出】的 native_init 符号；真正的初始化在构造函数里已完成，
// 本函数只需存在并返回 nullptr（nullptr = 不注册 onModuleLoaded 回调）。
extern "C" __attribute__((visibility("default"))) void *native_init(const void *entries) {
    LOGI("native_init: LSPosed 已回调（entries=%p）", entries);
    return nullptr;
}
