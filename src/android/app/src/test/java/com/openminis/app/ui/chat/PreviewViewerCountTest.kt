package com.openminis.app.ui.chat

import com.openminis.app.virtualdisplay.PreviewViewerCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 后台屏预览只在有界面在看时缩放：观看者计数要准，重复释放不能把别人的观看算掉。 */
class PreviewViewerCountTest {
    @Test fun nobodyWatchingByDefault() {
        assertFalse(PreviewViewerCount().isWatched)
    }

    @Test fun cardAndDialogTogether() {
        val c = PreviewViewerCount()
        val card = c.acquire()
        val dialog = c.acquire()
        assertEquals(2, c.viewers)
        dialog()
        assertTrue("预览卡还在看", c.isWatched)
        card()
        assertFalse(c.isWatched)
    }

    @Test fun doubleReleaseCountsOnce() {
        val c = PreviewViewerCount()
        val a = c.acquire()
        val b = c.acquire()
        a(); a(); a()
        assertEquals(1, c.viewers)
        b()
        assertEquals(0, c.viewers)
        b()
        assertEquals(0, c.viewers)
    }

    @Test fun onlyCardsThatShowThePreviewRegister() {
        val card = com.openminis.app.ProductionSources.read("ui/chat/ChatComposerWidgets.kt")
        assertTrue(card.contains("rememberBackgroundDisplayLiveSnapshot(watching = wantsBgLivePreview)"))
        assertFalse("工具卡不能再无条件登记", card.contains("rememberBackgroundDisplayLiveSnapshot()"))
        val snap = com.openminis.app.ProductionSources.read("ui/chat/ChatMiscViews.kt")
            .substringAfter("internal fun rememberBackgroundDisplayLiveSnapshot(").substringBefore("\n}\n")
        assertTrue("前台才算在看", snap.contains("watching && rememberLifecycleAtLeastStarted()"))
        assertTrue("登记与释放成对", snap.contains("DisposableEffect(viewing)") && snap.contains("onDispose { release?.invoke() }"))
    }
}
