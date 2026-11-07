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
        assertEquals(BackgroundDisplayTakeoverState.WAITING, backgroundDisplayTakeoverState(isLive = true, hasFrame = false))
        // 停止后即使还残留一帧也算已停止（不展示残留画面，见第 3 条）
        assertEquals(BackgroundDisplayTakeoverState.STOPPED, backgroundDisplayTakeoverState(isLive = false, hasFrame = true))
        assertEquals(BackgroundDisplayTakeoverState.STOPPED, backgroundDisplayTakeoverState(isLive = false, hasFrame = false))
    }

    @Test fun controlsOnlyWhileRunning() {
        assertTrue(backgroundDisplayControlsEnabled(BackgroundDisplayTakeoverState.LIVE))
        assertTrue(backgroundDisplayControlsEnabled(BackgroundDisplayTakeoverState.WAITING))
        assertFalse(backgroundDisplayControlsEnabled(BackgroundDisplayTakeoverState.STOPPED))
    }

    @Test fun textsSayWhoIsInControl() {
        assertTrue(backgroundDisplayTakeoverTitle(BackgroundDisplayTakeoverState.LIVE, 7).contains("接管"))
        assertTrue(backgroundDisplayTakeoverTitle(BackgroundDisplayTakeoverState.LIVE, 7).contains("#7"))
        assertTrue(backgroundDisplayTakeoverTitle(BackgroundDisplayTakeoverState.STOPPED, -1).contains("已停止"))
        assertTrue(backgroundDisplayTakeoverHint(BackgroundDisplayTakeoverState.LIVE).contains("关闭窗口即退出接管"))
        assertNull(backgroundDisplayTakeoverPlaceholder(BackgroundDisplayTakeoverState.LIVE))
        assertNotNull(backgroundDisplayTakeoverPlaceholder(BackgroundDisplayTakeoverState.WAITING))
        assertEquals("后台屏已停止", backgroundDisplayTakeoverPlaceholder(BackgroundDisplayTakeoverState.STOPPED))
    }
}
