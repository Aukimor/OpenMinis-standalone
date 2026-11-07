package com.openminis.app.ui.chat

import com.openminis.app.virtualdisplay.BackgroundDisplayKill
import org.junit.Assert.assertEquals
import org.junit.Test

/** bg stop --kill：只结束虚拟屏上的普通 App，不碰 OpenMinis 自己和系统包。 */
class BackgroundDisplayKillTest {
    private val system = setOf("com.android.chrome", "com.miui.home")

    private fun pick(vararg c: String?) =
        BackgroundDisplayKill.packagesToForceStop(c.toList(), selfPackage = "com.openminis.app") { it in system }

    @Test fun takesLaunchedAndTopActivityPackages() {
        assertEquals(
            listOf("com.tencent.mm", "com.ss.android.ugc.aweme"),
            pick("com.tencent.mm", "com.ss.android.ugc.aweme/.splash.SplashActivity"),
        )
    }

    @Test fun dedupesAndIgnoresBlank() {
        assertEquals(listOf("com.tencent.mm"), pick("com.tencent.mm", "", null, "com.tencent.mm/.ui.LauncherUI", "  "))
    }

    @Test fun neverSelfOrSystem() {
        assertEquals(
            listOf("com.example.shop"),
            pick("com.openminis.app", "com.openminis.app:bg_display", "android", "com.android.systemui", "com.android.chrome", "com.miui.home", "com.example.shop"),
        )
    }

    @Test fun rejectsGarbage() {
        assertEquals(emptyList<String>(), pick("not a package", "-p", "rm -rf"))
    }

    @Test fun parsesPmList() {
        assertEquals(setOf("android", "com.android.chrome"), BackgroundDisplayKill.parsePmList("package:android\npackage:com.android.chrome\n\nWARNING: x\n"))
    }

    @Test fun onlySuccessfulLaunchesAreRemembered() {
        val src = com.openminis.app.ProductionSources.read("virtualdisplay/BackgroundDisplayManager.kt")
        val launch = src.substringAfter("    fun launchApp(").substringBefore("    @Synchronized")
        val adds = Regex("launchedPackages \\+= packageName").findAll(launch).map { it.range.first }.toList()
        assertEquals("Binder 成功和 am start 成功两处", 2, adds.size)
        val binderOk = launch.indexOf("if (code == 0) {")
        val shellOk = launch.indexOf("return if (res.exitCode == 0) {")
        org.junit.Assert.assertTrue(binderOk in 0 until adds[0] && adds[0] < launch.indexOf("if (force)"))
        org.junit.Assert.assertTrue(shellOk in 0 until adds[1])
        // 候选里不能有 currentPackageName（启动前就赋值）
        val kill = src.substringAfter("    private fun forceStopDisplayPackages(").substringBefore("    private fun systemPackages(")
        org.junit.Assert.assertFalse(kill.contains("currentPackageName"))
    }
}
