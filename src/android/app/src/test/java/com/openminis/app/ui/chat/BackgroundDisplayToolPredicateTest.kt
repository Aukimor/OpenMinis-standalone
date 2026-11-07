package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台虚拟屏预览只该出现在「真正操作后台屏」的工具卡上（fix/bg-display 第 2 条）。
 * 之前后台屏运行时，几乎所有工具卡（含历史消息、普通 shell 命令）缩略图都被换成后台屏画面。
 */
class BackgroundDisplayToolPredicateTest {
    private fun block(name: String, args: String, id: String = "b") =
        AssistantBlock(id = id, kind = "tool_use", toolName = name, toolArgs = args)

    @Test fun a11yCliShellCommandIsBackgroundDisplayTool() {
        assertTrue(isBackgroundDisplayTool(block("shell_execute", """{"command":"android-a11y-cli bg start com.example"}""")))
        assertTrue(isBackgroundDisplayTool(block("shell_execute", """{"command":"android-a11y-cli tap 100 200"}""")))
        // 现有后台屏脚本也算（与工具详情页同一判断）
        assertTrue(isBackgroundDisplayTool(block("shell_execute", """{"command":"sh fast_soda.sh"}""")))
        assertTrue(isBackgroundDisplayTool(block("shell_execute", """{"command":"python3 fast_douyin.py"}""")))
    }

    @Test fun latestBackgroundDisplayCardIsTheNewestBgOrHelperCard() {
        val a11yOld = block("shell_execute", """{"command":"android-a11y-cli bg start x"}""", id = "1")
        val ls = block("shell_execute", """{"command":"ls"}""", id = "2")
        val a11yNew = block("shell_execute", """{"command":"android-a11y-cli tap 1 2"}""", id = "3")
        val browser = block("browser_use", """{"action":"screenshot"}""", id = "4")
        // 已经结束的 a11y 卡只要是最新一张后台屏卡就继续选中，后面的普通卡不影响
        assertEquals("3", latestBackgroundDisplayCardId(listOf(a11yOld, ls, a11yNew, browser)))
        val helper = block(com.openminis.app.agent.jobs.HelperRunner.TOOL_NAME, "{}", id = "5")
        assertEquals("5", latestBackgroundDisplayCardId(listOf(a11yOld, a11yNew, helper)))
        assertNull(latestBackgroundDisplayCardId(listOf(ls, browser)))
    }

    @Test fun ordinaryToolsAreNot() {
        assertFalse(isBackgroundDisplayTool(block("shell_execute", """{"command":"ls -la"}""")))
        // 旧判断里的 "bg " 子串会误中普通命令
        assertFalse(isBackgroundDisplayTool(block("shell_execute", """{"command":"cat bg notes.txt"}""")))
        assertFalse(isBackgroundDisplayTool(block("browser_use", """{"action":"screenshot"}""")))
        assertFalse(isBackgroundDisplayTool(block("read_image", """{"path":"/tmp/a.png"}""")))
        assertFalse(isBackgroundDisplayTool(block("file_read", """{"path":"android-a11y-cli.md"}""")))
    }
}
