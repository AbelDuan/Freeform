package com.abel.os4freeformx

/**
 * NBI（沉浸导航栏）规则合并 / JSON 生成 / 回读 自检。纯逻辑，不需要设备。
 * 运行： ./check.sh
 */
object NbiCheck {

    private var failed = 0

    private fun eq(name: String, got: Any?, want: Any?) {
        if (got != want) { println("FAIL $name\n     got=$got\n     want=$want"); failed++ }
        else println("ok   $name")
    }

    private fun true_(name: String, got: Boolean) = eq(name, got, true)

    @JvmStatic
    fun main(args: Array<String>) {
        // 1) 两种规则形状（反编译取证 + 真机实测）
        // 隐藏 = mode:2（整个底栏消失、内容铺到最底，与小鹏原版一致）
        true_("隐藏规则 mode:2", Nbi.RULE_HIDE.contains("\"mode\": 2"))
        eq("隐藏规则【不带】color（不是仅透明）", Nbi.RULE_HIDE.contains("\"color\""), false)
        true_("隐藏规则 enable:true", Nbi.RULE_HIDE.contains("\"enable\": true"))
        true_("隐藏规则全匹配 activity", Nbi.RULE_HIDE.contains("\"*\": {"))
        true_("取色规则 mode:1", Nbi.RULE_SAMPLE.contains("\"mode\": 1"))
        eq("取色规则【不带】color（不带才走采样）", Nbi.RULE_SAMPLE.contains("\"color\""), false)

        // 2) 合并：按规则值合并（支持同一应用从取色改成隐藏）
        eq("merge 保留外部 + 加选中",
            Nbi.mergeRules(
                mapOf("com.keep.me" to Nbi.RULE_HIDE, "com.drop.me" to Nbi.RULE_HIDE),
                setOf("com.drop.me"),
                mapOf("com.new.one" to Nbi.RULE_SAMPLE)),
            mapOf("com.keep.me" to Nbi.RULE_HIDE, "com.new.one" to Nbi.RULE_SAMPLE))
        eq("merge 换规则（取色→隐藏）",
            Nbi.mergeRules(mapOf("a.b" to Nbi.RULE_SAMPLE), setOf("a.b"), mapOf("a.b" to Nbi.RULE_HIDE)),
            mapOf("a.b" to Nbi.RULE_HIDE))
        eq("merge applied 为空不删外部",
            Nbi.mergeRules(mapOf("com.keep.me" to Nbi.RULE_HIDE), emptySet(), emptyMap()),
            mapOf("com.keep.me" to Nbi.RULE_HIDE))
        eq("merge 全取消只剩外部",
            Nbi.mergeRules(
                mapOf("com.keep.me" to Nbi.RULE_HIDE, "com.mine" to Nbi.RULE_HIDE),
                setOf("com.mine"), emptyMap()),
            mapOf("com.keep.me" to Nbi.RULE_HIDE))

        // 3) JSON 生成 + 回读（两种规则都要能原样取回）
        val j = Nbi.buildJson(mapOf("com.foo.bar" to Nbi.RULE_HIDE, "com.zzz" to Nbi.RULE_SAMPLE))
        true_("json 含包名", j.contains("\"com.foo.bar\""))
        true_("json 钉住 dataVersion", j.contains("\"999999\""))
        true_("json 声明 modules", j.contains("navigation_bar_immersive_application_config_new"))
        eq("extract 回读包名", Nbi.extractRules(j).keys, setOf("com.foo.bar", "com.zzz"))
        eq("extract 回读规则体（隐藏）", Nbi.extractRules(j)["com.foo.bar"], Nbi.RULE_HIDE)
        eq("extract 回读规则体（取色）", Nbi.extractRules(j)["com.zzz"], Nbi.RULE_SAMPLE)
        eq("extract 不抓顶层键", Nbi.extractKeys(j).contains("modules"), false)
        eq("空规则回读", Nbi.extractKeys(Nbi.buildJson(emptyMap())), emptySet<String>())

        // 4) 真实文件排版：外部条目（小鹏 mode:2）必须一字不改地保留
        val real = """
            {
              "dataVersion": "999999",
              "modules": "navigation_bar_immersive_application_config_new",
              "modifyApps": "modifyApps",
              "NBIRules": {
                "com.xiaopeng.mycarinfo": {
                  "enable": true,
                  "activityRules": {
                    "*": {
                      "mode": 2
                    }
                  }
                }
              }
            }
        """.trimIndent()
        eq("extract 真实排版包名", Nbi.extractKeys(real), setOf("com.xiaopeng.mycarinfo"))
        true_("extract 真实排版规则体含 mode:2", Nbi.extractRules(real)["com.xiaopeng.mycarinfo"]!!.contains("\"mode\": 2"))
        val merged = Nbi.mergeRules(Nbi.extractRules(real), emptySet(), mapOf("demigos.com.mobilism" to Nbi.RULE_HIDE))
        eq("合并保留外部规则原文", merged["com.xiaopeng.mycarinfo"], Nbi.extractRules(real)["com.xiaopeng.mycarinfo"])
        eq("合并加入新条目", merged["demigos.com.mobilism"], Nbi.RULE_HIDE)
        eq("extract 多包", Nbi.extractKeys(Nbi.buildJson(mapOf("a.b" to Nbi.RULE_HIDE, "c.d" to Nbi.RULE_SAMPLE))), setOf("a.b", "c.d"))
        eq("extract 空串", Nbi.extractKeys(""), emptySet<String>())
        eq("extract 无 NBIRules", Nbi.extractKeys("""{"dataVersion":"1"}"""), emptySet<String>())

        // 5) 集合序列化往返
        eq("parseSet 去空白", Nbi.parseSet(" a.b , c.d ,, "), setOf("a.b", "c.d"))
        eq("parseSet 空/未设置", Nbi.parseSet(null), emptySet<String>())
        eq("join 往返", Nbi.parseSet(Nbi.join(setOf("b.b", "a.a"))), setOf("a.a", "b.b"))

        // 6) su 脚本（真正会在设备上执行的东西，必须可测）
        val sc = Nbi.buildScript("QUJD", listOf("a.b"), listOf("c.d"))
        true_("script 用 base64 落盘", sc.contains("base64 -d > '"))
        true_("script 含 base64 内容", sc.contains("QUJD"))
        true_("script 调 update（让 system_server 重读名单，缺它文件写了不生效）", sc.contains(" update"))
        true_("script enable 选中的包", sc.contains("cmd miui_navigation_bar_immersive enable a.b"))
        true_("script disable 取消的包", sc.contains("cmd miui_navigation_bar_immersive disable c.d"))
        true_("script 末尾回执 NBI_OK", sc.contains("NBI_OK"))
        eq("script 有序（可复现）", Nbi.buildScript("QQ==", listOf("b.b", "a.a"), emptyList()), Nbi.buildScript("QQ==", listOf("a.a", "b.b"), emptyList()))

        // 7) 重启目标应用脚本（NBI 要求"restart the application to take effect"）
        val rs = Nbi.buildRestartScript(listOf("b.b", "a.a", "b.b", ""))
        true_("restart 脚本 force-stop 每个包", rs.contains("am force-stop a.a") && rs.contains("am force-stop b.b"))
        eq("restart 脚本去重且有序", rs, "am force-stop a.a; am force-stop b.b")
        eq("restart 空列表为空串", Nbi.buildRestartScript(emptyList()), "")

        println(if (failed == 0) "NbiCheck: ALL PASS" else "NbiCheck: $failed FAILED")
        if (failed != 0) throw AssertionError("NbiCheck: $failed failed")
    }
}
