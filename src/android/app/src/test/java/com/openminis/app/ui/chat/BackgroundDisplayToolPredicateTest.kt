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

    @Test fun latestBackgroundDisplayCardIsTheNewestBgCard() {
        val a11yOld = block("shell_execute", """{"command":"android-a11y-cli bg start x"}""", id = "1")
        val ls = block("shell_execute", """{"command":"ls"}""", id = "2")
        val a11yNew = block("shell_execute", """{"command":"android-a11y-cli tap 1 2"}""", id = "3")
        val browser = block("browser_use", """{"action":"screenshot"}""", id = "4")
        // 已经结束的 a11y 卡只要是最新一张后台屏卡就继续选中，后面的普通卡不影响
        assertEquals("3", latestBackgroundDisplayCardId(listOf(a11yOld, ls, a11yNew, browser)))
        assertNull(latestBackgroundDisplayCardId(listOf(ls, browser)))
    }

    @Test fun a11yCardFollowedByUnrelatedHelperSelectsTheA11yCard() {
        // a11y 卡后跟一张无关 Helper（如网页调研）：仍选 a11y 卡，Helper 不按工具名入选。
        // （Helper 只在子会话当前工具是后台屏工具时由缩略图自己判定显示预览。）
        val a11y = block("shell_execute", """{"command":"android-a11y-cli tap 1 2"}""", id = "1")
        val helper = block(com.openminis.app.agent.jobs.HelperRunner.TOOL_NAME, """{"title":"网页调研"}""", id = "2")
        assertEquals("1", latestBackgroundDisplayCardId(listOf(a11y, helper)))
        assertNull(latestBackgroundDisplayCardId(listOf(helper)))
    }

    @Test fun ordinaryToolsAreNot() {
        assertFalse(isBackgroundDisplayTool(block("shell_execute", """{"command":"ls -la"}""")))
        // 旧判断里的 "bg " 子串会误中普通命令
        assertFalse(isBackgroundDisplayTool(block("shell_execute", """{"command":"cat bg notes.txt"}""")))
        // 读文档不是操作后台屏：按命令词边界匹配，带后缀的名字不命中（旧的子串 "android-a11y" 会误中）
        assertFalse(isBackgroundDisplayTool(block("shell_execute", """{"command":"cat android-a11y-cli.md"}""")))
        assertFalse(isBackgroundDisplayTool(block("shell_execute", """{"command":"cat android-a11y.md"}""")))
        assertFalse(isBackgroundDisplayTool(block("browser_use", """{"action":"screenshot"}""")))
        assertFalse(isBackgroundDisplayTool(block("read_image", """{"path":"/tmp/a.png"}""")))
        assertFalse(isBackgroundDisplayTool(block("file_read", """{"path":"android-a11y-cli.md"}""")))
    }

    @Test fun latestCardIsChosenWithinTheCurrentTurnOnly() {
        // 上一轮有 a11y 卡，本轮只有普通命令：本轮里没有后台屏卡，不跨轮选中历史卡。
        fun tool(id: String, cmd: String) = AssistantBlock(id = id, kind = "tool_use", toolName = "shell_execute",
            toolArgs = """{"command":"$cmd"}""", toolStatus = ToolBlockStatus.SUCCESS)
        val messages = listOf(
            ChatMessage(id = "u1", role = "user", content = "打开 App"),
            ChatMessage(id = "a1", role = "assistant", content = "", toolBlocks = listOf(tool("1", "android-a11y-cli tap 1 2"))),
            ChatMessage(id = "u2", role = "user", content = "看看目录"),
            ChatMessage(id = "a2", role = "assistant", content = "", toolBlocks = listOf(tool("2", "ls"))),
        )
        assertEquals(listOf("2"), currentTurnToolBarBlocks(messages).map { it.id })
        assertNull(latestBackgroundDisplayCardId(currentTurnToolBarBlocks(messages)))
        assertEquals("1", latestBackgroundDisplayCardId(floatingToolBarBlocks(messages)))
    }

    @Test fun commandByPathStillCounts() {
        assertTrue(isBackgroundDisplayTool(block("shell_execute", """{"command":"/usr/local/bin/android-a11y-cli bg status"}""")))
    }
}
