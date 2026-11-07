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
 * 接管里「操作送达后迟迟没有新画面」时才提示。虚拟屏只在内容变化时出帧，静止页面（设置页、文章、
 * AI 思考间隙）本来就长时间没有新帧，所以不能按「多久没新帧」判断，否则正常情况也会误报。
 *
 * 条件：用户发出的点击 / 滑动 / 按键已送达（[lastActionAtMs] > 0），之后还没有新帧
 * （[lastActionAtMs] > [lastFrameAtMs]），并且已过去 [thresholdMs]。有新帧就自然不满足，提示消失。
 */
internal fun backgroundDisplayNoFrameAfterAction(
    lastActionAtMs: Long,
    lastFrameAtMs: Long,
    nowMs: Long,
    thresholdMs: Long = 4_000L,
): Boolean =
    lastActionAtMs > 0L && lastActionAtMs > lastFrameAtMs && nowMs - lastActionAtMs >= thresholdMs

/** [backgroundDisplayNoFrameAfterAction] 成立时显示的文案（不带递增秒数）。 */
internal const val BACKGROUND_DISPLAY_NO_FRAME_AFTER_ACTION_HINT =
    "操作已发出，但画面一直没有变化：后台 App 可能卡住，或这次操作没有效果"
