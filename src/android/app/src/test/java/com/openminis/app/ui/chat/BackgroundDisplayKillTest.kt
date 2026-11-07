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
}
