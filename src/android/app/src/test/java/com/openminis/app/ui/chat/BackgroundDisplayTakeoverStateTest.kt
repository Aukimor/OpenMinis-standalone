package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 后台屏接管弹窗的状态（第 3 轮：接管 / 启动中 / 已停止要说清楚，停止后按钮不可点）。 */
class BackgroundDisplayTakeoverStateTest {
    @Test fun stateFollowsLiveAndFrame() {
        assertEquals(BackgroundDisplayTakeoverState.LIVE, backgroundDisplayTakeoverState(isLive = true, hasFrame = true))
        assertEquals(BackgroundDisplayTakeoverState.PAUSED, backgroundDisplayTakeoverState(isLive = true, hasFrame = true, isPaused = true))
        assertEquals(BackgroundDisplayTakeoverState.WAITING, backgroundDisplayTakeoverState(isLive = true, hasFrame = false))
        assertEquals(BackgroundDisplayTakeoverState.WAITING, backgroundDisplayTakeoverState(isLive = true, hasFrame = false, isPaused = true))
        // 停止后即使还残留一帧也算已停止（不展示残留画面，见第 3 条）
        assertEquals(BackgroundDisplayTakeoverState.STOPPED, backgroundDisplayTakeoverState(isLive = false, hasFrame = true))
        assertEquals(BackgroundDisplayTakeoverState.STOPPED, backgroundDisplayTakeoverState(isLive = false, hasFrame = true, isPaused = true))
        assertEquals(BackgroundDisplayTakeoverState.STOPPED, backgroundDisplayTakeoverState(isLive = false, hasFrame = false))
    }

    @Test fun controlsOnlyWhileRunning() {
        assertTrue(backgroundDisplayControlsEnabled(BackgroundDisplayTakeoverState.LIVE))
        assertTrue(backgroundDisplayControlsEnabled(BackgroundDisplayTakeoverState.PAUSED))
        assertTrue(backgroundDisplayControlsEnabled(BackgroundDisplayTakeoverState.WAITING))
        assertFalse(backgroundDisplayControlsEnabled(BackgroundDisplayTakeoverState.STOPPED))
    }

    @Test fun textsSayWhoIsInControl() {
        assertTrue(backgroundDisplayTakeoverTitle(BackgroundDisplayTakeoverState.LIVE, 7).contains("接管"))
        assertTrue(backgroundDisplayTakeoverTitle(BackgroundDisplayTakeoverState.LIVE, 7).contains("#7"))
        assertTrue(backgroundDisplayTakeoverTitle(BackgroundDisplayTakeoverState.PAUSED, 7).contains("已暂停"))
        assertTrue(backgroundDisplayTakeoverTitle(BackgroundDisplayTakeoverState.PAUSED, 7).contains("#7"))
        assertTrue(backgroundDisplayTakeoverTitle(BackgroundDisplayTakeoverState.STOPPED, -1).contains("已停止"))
        assertTrue(backgroundDisplayTakeoverHint(BackgroundDisplayTakeoverState.LIVE).contains("关闭窗口即退出接管"))
        assertTrue(backgroundDisplayTakeoverHint(BackgroundDisplayTakeoverState.PAUSED).contains("已暂停"))
        assertTrue(backgroundDisplayTakeoverHint(BackgroundDisplayTakeoverState.PAUSED).contains("继续"))
        assertNull(backgroundDisplayTakeoverPlaceholder(BackgroundDisplayTakeoverState.LIVE))
        assertNull(backgroundDisplayTakeoverPlaceholder(BackgroundDisplayTakeoverState.PAUSED))
        assertNotNull(backgroundDisplayTakeoverPlaceholder(BackgroundDisplayTakeoverState.WAITING))
        assertEquals("后台屏已停止", backgroundDisplayTakeoverPlaceholder(BackgroundDisplayTakeoverState.STOPPED))
    }

    @Test fun buttonEnablementRules() {
        // 暂停按钮：运行中且未暂停时可用
        assertTrue(isPauseButtonEnabled(isLive = true, isPaused = false))
        assertFalse(isPauseButtonEnabled(isLive = true, isPaused = true))
        assertFalse(isPauseButtonEnabled(isLive = false, isPaused = false))

        // 继续按钮：运行中且已暂停时可用
        assertTrue(isResumeButtonEnabled(isLive = true, isPaused = true))
        assertFalse(isResumeButtonEnabled(isLive = true, isPaused = false))
        assertFalse(isResumeButtonEnabled(isLive = false, isPaused = true))

        // 前台与全屏按钮：运行中可用
        assertTrue(isForegroundButtonEnabled(isLive = true))
        assertFalse(isForegroundButtonEnabled(isLive = false))
        assertTrue(isFullscreenButtonEnabled(isLive = true))
        assertFalse(isFullscreenButtonEnabled(isLive = false))
    }

    @Test fun failedActionsAreReportedAndThrottled() {
        assertNull(backgroundDisplayActionFailureMessage(delivered = true, stillLive = true))
        assertNull(backgroundDisplayActionFailureMessage(delivered = true, stillLive = false))
        assertTrue(backgroundDisplayActionFailureMessage(delivered = false, stillLive = false)!!.contains("已停止"))
        assertTrue(backgroundDisplayActionFailureMessage(delivered = false, stillLive = true)!!.contains("没送达"))
        assertTrue(shouldShowBackgroundDisplayFailure(lastShownAtMs = 0L, nowMs = 1_000L))
        assertFalse(shouldShowBackgroundDisplayFailure(lastShownAtMs = 10_000L, nowMs = 11_000L))
        assertTrue(shouldShowBackgroundDisplayFailure(lastShownAtMs = 10_000L, nowMs = 12_000L))
    }

    @Test fun staticPageWithoutActionNeverHints() {
        // 静止页面：很久没有新帧，但用户没有操作 → 不提示
        assertFalse(backgroundDisplayNoFrameAfterAction(lastActionAtMs = 0L, lastFrameAtMs = 1_000L, nowMs = 600_000L))
        // 操作发生在最后一帧之前（后来已出过新帧），之后静止很久 → 不提示
        assertFalse(backgroundDisplayNoFrameAfterAction(lastActionAtMs = 5_000L, lastFrameAtMs = 5_200L, nowMs = 600_000L))
    }

    @Test fun actionWithoutNewFrameHintsAfterThreshold() {
        // 操作送达后没有新帧：不到 4 秒不提示，满 4 秒提示
        assertFalse(backgroundDisplayNoFrameAfterAction(lastActionAtMs = 10_000L, lastFrameAtMs = 9_000L, nowMs = 13_999L))
        assertTrue(backgroundDisplayNoFrameAfterAction(lastActionAtMs = 10_000L, lastFrameAtMs = 9_000L, nowMs = 14_000L))
        assertTrue(backgroundDisplayNoFrameAfterAction(lastActionAtMs = 10_000L, lastFrameAtMs = 9_000L, nowMs = 60_000L))
        // 文案不带递增秒数
        assertFalse(BACKGROUND_DISPLAY_NO_FRAME_AFTER_ACTION_HINT.any { it.isDigit() })
    }

    @Test fun actionFollowedByNewFrameDoesNotHint() {
        assertFalse(backgroundDisplayNoFrameAfterAction(lastActionAtMs = 10_000L, lastFrameAtMs = 10_300L, nowMs = 20_000L))
    }
}
