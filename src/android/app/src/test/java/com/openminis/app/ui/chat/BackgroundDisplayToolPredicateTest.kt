package com.openminis.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台虚拟屏预览只该出现在「真正操作后台屏」的工具卡上（fix/bg-display 第 2 条）。
 * 之前后台屏运行时，几乎所有工具卡（含历史消息、普通 shell 命令）缩略图都被换成后台屏画面。
 */
class BackgroundDisplayToolPredicateTest {
    private fun block(name: String, args: String) =
        AssistantBlock(id = "b", kind = "tool_use", toolName = name, toolArgs = args)

    @Test fun a11yCliShellCommandIsBackgroundDisplayTool() {
        assertTrue(isBackgroundDisplayTool(block("shell_execute", """{"command":"android-a11y-cli bg start com.example"}""")))
        assertTrue(isBackgroundDisplayTool(block("shell_execute", """{"command":"android-a11y-cli tap 100 200"}""")))
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
