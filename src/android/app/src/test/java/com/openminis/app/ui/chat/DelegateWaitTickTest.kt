package com.openminis.app.ui.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** delegate_task 等待循环的一拍：子代理结束或用户分离时立即返回，否则仍是 1 秒一拍。 */
@OptIn(ExperimentalCoroutinesApi::class)
class DelegateWaitTickTest {
    @Test fun fullTickWhenNothingHappens() = runTest {
        val streaming = MutableStateFlow(true)
        val detach = MutableStateFlow(0)
        val woke = awaitDelegateWaitTick(1_000L, streaming, detach, detachSeen = 0)
        assertFalse(woke)
        assertEquals(1_000L, currentTime)
    }

    @Test fun returnsAsSoonAsChildFinishes() = runTest {
        val streaming = MutableStateFlow(true)
        val detach = MutableStateFlow(0)
        val r = async { awaitDelegateWaitTick(1_000L, streaming, detach, detachSeen = 0) }
        advanceTimeBy(120L)
        runCurrent()
        streaming.value = false
        runCurrent()
        assertTrue(r.isCompleted)
        assertTrue(r.await())
        assertEquals(120L, currentTime)
    }

    @Test fun returnsImmediatelyIfChildAlreadyDone() = runTest {
        val woke = awaitDelegateWaitTick(1_000L, MutableStateFlow(false), MutableStateFlow(0), detachSeen = 0)
        assertTrue(woke)
        assertEquals(0L, currentTime)
    }

    @Test fun returnsAsSoonAsUserDetaches() = runTest {
        val streaming = MutableStateFlow(true)
        val detach = MutableStateFlow(3)
        val r = async { awaitDelegateWaitTick(1_000L, streaming, detach, detachSeen = 3) }
        advanceTimeBy(300L)
        runCurrent()
        detach.value = 4
        runCurrent()
        assertTrue(r.await())
        assertEquals(300L, currentTime)
    }

    @Test fun detachAlreadyDifferentReturnsAtOnce() = runTest {
        // 与原逻辑一致：一拍开始前已分离（值已不同）也立即返回
        assertTrue(awaitDelegateWaitTick(1_000L, MutableStateFlow(true), MutableStateFlow(2), detachSeen = 1))
        assertEquals(0L, currentTime)
    }
}
