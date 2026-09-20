package com.abel.os4freeformx

import kotlin.math.abs

/**
 * 比例调整算法的主机侧自检。跑法：`./check.sh`（不需要设备，几秒钟）。
 *
 * 只放「一眼能看出对错」的用例；它不替代 `./verify.sh` 的真机取证。
 */
private fun checkRatio(name: String, got: Float, want: Float) {
    if (abs(got - want) > want * 0.01f) throw AssertionError("$name: 期望 $want，实际 $got")
    println("ok  $name -> ${"%.4f".format(got)}")
}

private fun checkSize(name: String, got: IntArray, want: IntArray) {
    if (!got.contentEquals(want)) throw AssertionError("$name: 期望 ${want.toList()}，实际 ${got.toList()}")
    println("ok  $name -> ${got.toList()}")
}

fun main() {
    val area = intArrayOf(0, 140, 1672, 2364)   // 状态栏 140 以下、整屏以内

    // ---- 方向是一个显式状态：横屏取按钮比例本身，竖屏取倒数 ----
    checkRatio("横屏状态 18:9", ratioFor(18f / 9f, true), 18f / 9f)
    checkRatio("竖屏状态 18:9", ratioFor(18f / 9f, false), 9f / 18f)
    checkRatio("横屏状态 4:3", ratioFor(4f / 3f, true), 4f / 3f)
    checkRatio("竖屏状态 4:3", ratioFor(4f / 3f, false), 3f / 4f)
    checkRatio("1:1 与方向无关", ratioFor(1f, false), 1f)
    if (!isLandscape(400, 275) || isLandscape(275, 400)) throw AssertionError("isLandscape 判断错")

    // ---- 尺寸：宽度不变、高度按比例；放不下就整体缩 ----
    checkSize("保长边(横)", fitRatio(1000, 600, 18f / 9f, area), intArrayOf(1000, 500))
    checkSize("保长边(竖)", fitRatio(600, 1000, 9f / 18f, area), intArrayOf(500, 1000))
    checkSize("长边超屏要缩", fitRatio(2000, 600, 2f, area), intArrayOf(1672, 836))

    // 真实 bounds 不能超屏：等比缩
    checkSize("等比缩进屏", fitInto(2224, 2224, 1103, 1468), intArrayOf(1103, 1103))
    checkSize("本来放得下就不动", fitInto(600, 800, 1103, 1468), intArrayOf(600, 800))

    println("比例算法自检全部通过")
}
