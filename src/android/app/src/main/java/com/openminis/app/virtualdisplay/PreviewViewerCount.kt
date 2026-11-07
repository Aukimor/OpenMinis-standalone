package com.openminis.app.virtualdisplay

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 后台屏预览的观看者计数：每次 [acquire] 返回一个只生效一次的释放函数，计数不会小于 0。 */
internal class PreviewViewerCount {
    private val count = AtomicInteger(0)

    val isWatched: Boolean get() = count.get() > 0

    val viewers: Int get() = count.get()

    fun acquire(): () -> Unit {
        count.incrementAndGet()
        val released = AtomicBoolean(false)
        return {
            if (released.compareAndSet(false, true)) {
                count.updateAndGet { (it - 1).coerceAtLeast(0) }
            }
        }
    }
}
