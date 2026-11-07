package com.openminis.app.ui.chat

/**
 * 后台屏接管弹窗当前处于哪种状态，决定标题、画面区提示和底部按钮是否可用。纯函数，便于单测。
 *
 * - [WAITING]：后台屏在运行，但还没拿到第一帧画面（刚启动）。
 * - [LIVE]：后台屏在运行且有画面，用户的点击 / 滑动会直接作用到后台屏。
 * - [STOPPED]：后台屏已经停止（AI 结束、用户停止、或 Shizuku 断开），没有可接管的画面。
 */
internal enum class BackgroundDisplayTakeoverState { WAITING, LIVE, STOPPED }

internal fun backgroundDisplayTakeoverState(isLive: Boolean, hasFrame: Boolean): BackgroundDisplayTakeoverState =
    when {
        !isLive -> BackgroundDisplayTakeoverState.STOPPED
        !hasFrame -> BackgroundDisplayTakeoverState.WAITING
        else -> BackgroundDisplayTakeoverState.LIVE
    }

/** 弹窗标题。 */
internal fun backgroundDisplayTakeoverTitle(state: BackgroundDisplayTakeoverState, displayId: Int): String =
    when (state) {
        BackgroundDisplayTakeoverState.LIVE -> "📱 你正在接管后台屏 (Display #$displayId)"
        BackgroundDisplayTakeoverState.WAITING -> "📱 后台屏启动中 (Display #$displayId)"
        BackgroundDisplayTakeoverState.STOPPED -> "📱 后台屏已停止"
    }

/** 标题下的一行说明：让用户知道点击会作用到哪里、AI 是否还在动、怎么退出。 */
internal fun backgroundDisplayTakeoverHint(state: BackgroundDisplayTakeoverState): String =
    when (state) {
        BackgroundDisplayTakeoverState.LIVE -> "点击 / 滑动画面直接操作后台屏；AI 也可能同时在操作。关闭窗口即退出接管，AI 继续在后台运行。"
        BackgroundDisplayTakeoverState.WAITING -> "正在等待后台屏的第一帧画面…"
        BackgroundDisplayTakeoverState.STOPPED -> "后台屏已经结束（AI 完成、被停止或 Shizuku 断开），没有可接管的画面。可以关闭窗口。"
    }

/** 画面区没有图像时的占位文字（[LIVE] 时有画面，不显示占位）。 */
internal fun backgroundDisplayTakeoverPlaceholder(state: BackgroundDisplayTakeoverState): String? =
    when (state) {
        BackgroundDisplayTakeoverState.LIVE -> null
        BackgroundDisplayTakeoverState.WAITING -> "正在等待后台画面…"
        BackgroundDisplayTakeoverState.STOPPED -> "后台屏已停止"
    }

/** 底部按钮（返回 / 上滑 / 下滑 / 停止）只在后台屏运行时可用。 */
internal fun backgroundDisplayControlsEnabled(state: BackgroundDisplayTakeoverState): Boolean =
    state != BackgroundDisplayTakeoverState.STOPPED

/**
 * 接管时一次点击 / 滑动 / 按键没送达后台屏，给用户看的提示；送达了返回 null。
 * [stillLive] 是失败那一刻后台屏是否还在运行：已停止和「在运行但没送达」是两种不同的情况。
 */
internal fun backgroundDisplayActionFailureMessage(delivered: Boolean, stillLive: Boolean): String? =
    when {
        delivered -> null
        !stillLive -> "后台屏已停止，这次操作没有发出"
        else -> "操作没送达后台屏（Shizuku 可能断开，或后台 App 无响应），请稍后再试"
    }

/** 失败提示限流：距上次提示不足 [minIntervalMs] 就不再弹，避免连续滑动时刷屏。 */
internal fun shouldShowBackgroundDisplayFailure(lastShownAtMs: Long, nowMs: Long, minIntervalMs: Long = 2_000L): Boolean =
    lastShownAtMs <= 0L || nowMs - lastShownAtMs >= minIntervalMs

/**
 * 接管画面多久没有新帧了（秒），未超过 [thresholdMs] 时返回 null。
 * 计时起点取「最后一帧时间」和「本次开始看画面的时间」中较晚的那个，刚打开弹窗不会立刻提示。
 * 虚拟屏只有内容变化才出新帧，所以这只是一个中性提示（页面静止或后台 App 卡住），不当作错误。
 */
internal fun backgroundDisplayFrameStaleSeconds(
    lastFrameAtMs: Long,
    watchingSinceMs: Long,
    nowMs: Long,
    thresholdMs: Long = 10_000L,
): Int? {
    val since = maxOf(lastFrameAtMs, watchingSinceMs)
    val age = nowMs - since
    return if (since > 0L && age >= thresholdMs) (age / 1000L).toInt() else null
}
