package com.openminis.app.ui.chat

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull

/**
 * delegate_task（wait=true）等待循环的一拍：最多等 [tickMs]，
 * 「子代理结束（[childStreaming] 变 false）」或「用户分离（[detach] 不再等于 [detachSeen]）」谁先到就立即返回。
 *
 * 只缩短等待，不改变循环的判断：循环本身仍按原逻辑检查 isStreaming、预算、用户追加消息和分离。
 * 之前是固定等 1 秒（只被分离打断），子代理结束后平均多等 0.5 秒、最多 1 秒。
 *
 * @return 提前被信号唤醒时为 true，等满 [tickMs] 为 false（仅供测试与日志）。
 */
internal suspend fun awaitDelegateWaitTick(
    tickMs: Long,
    childStreaming: StateFlow<Boolean>,
    detach: StateFlow<Int>,
    detachSeen: Int,
): Boolean = withTimeoutOrNull(tickMs) {
    merge(
        childStreaming.filter { !it }.map { },
        detach.filter { it != detachSeen }.map { },
    ).first()
    true
} ?: false
