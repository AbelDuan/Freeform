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

static uintptr_t base_of(struct link_map* lm) { return lm ? (uintptr_t)lm->l_addr : 0; }

static void install_match_hook(void* handle) {
    if (!handle) return;
    if (g_installed.exchange(true)) return;

    struct link_map* lm = nullptr;
    if (dlinfo(handle, RTLD_DI_LINKMAP, &lm) != 0 || !lm) {
        LOGE("dlinfo 失败: %s", dlerror());
        g_installed.store(false);
        return;
    }
    uintptr_t base = base_of(lm);
    void* target = (void*)(base + OFF_MATCH_REGION_CHECK);
    LOGI("libapp_launcher.so base=0x%lx target=0x%lx", (unsigned long)base, (unsigned long)target);

    // 取密度与屏幕高度
    char v[PROP_VALUE_MAX] = {0};
    int dens = prop_int("ro.sf.lcd_density", 0);
    if (dens > 0) g_density = dens;
    char hbuf[PROP_VALUE_MAX] = {0};
    if (__system_property_get("persist.os4rusttouch.screen_h", hbuf) > 0) g_screen_h = atoi(hbuf);
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
