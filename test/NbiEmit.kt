package com.abel.os4freeformx

import java.io.File
import java.util.Base64

/**
 * 主机侧发射器：用**产品代码本体**（Nbi.kt）算出"合并后的名单 + 要执行的 su 脚本"，
 * 好让设备侧跑的就是将要真实落地的那一段，而不是手抄的副本。
 *
 * 用法： NbiEmitKt <现有名单文件> <上次已写入(逗号串)> <本次选中(逗号串)> <脚本输出文件>
 */
fun main(args: Array<String>) {
    val existingPath = args[0]
    val applied = Nbi.parseSet(args.getOrNull(1))
    val desired = Nbi.parseSet(args[2])
    val outPath = args[3]

    val existing = runCatching { File(existingPath).readText() }.getOrNull()
    val existingKeys = if (existing == null) emptySet() else Nbi.extractKeys(existing)
    val keys = Nbi.mergeRules(existingKeys, applied, desired)
    val json = Nbi.buildJson(keys)
    val b64 = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
    val script = Nbi.buildScript(b64, desired.toList(), (applied - desired).toList())

    File(outPath).writeText(script)
    println("EXISTING_KEYS = ${Nbi.join(existingKeys)}")
    println("APPLIED       = ${Nbi.join(applied)}")
    println("DESIRED       = ${Nbi.join(desired)}")
    println("NEW_KEYS      = ${Nbi.join(keys)}")
    println("JSON----------")
    println(json)
    println("SCRIPT_WRITTEN_TO = $outPath")
}
