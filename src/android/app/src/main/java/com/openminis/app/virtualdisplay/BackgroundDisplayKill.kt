package com.openminis.app.virtualdisplay

/** bg stop --kill 要结束哪些包（纯函数，便于单测）。 */
internal object BackgroundDisplayKill {
    /** 无论如何都不结束的包（系统界面、桌面、Shizuku 本身）。 */
    private val NEVER = setOf(
        "android",
        "com.android.systemui",
        "com.android.shell",
        "com.android.settings",
        "moe.shizuku.privileged.api",
    )

    /**
     * @param candidates 包名，或 `topActivity` 返回的 `包名/Activity`；空串与 null 忽略
     * @param selfPackage OpenMinis 自己的包名（及其 `:进程` 变体）永不结束
     * @param isSystemPackage 系统包永不结束（查不到时调用方应返回 true）
     */
    fun packagesToForceStop(
        candidates: List<String?>,
        selfPackage: String,
        isSystemPackage: (String) -> Boolean,
    ): List<String> = candidates.asSequence()
        .mapNotNull { it?.substringBefore('/')?.substringBefore(':')?.trim() }
        .filter { it.isNotEmpty() && PKG.matches(it) }
        .distinct()
        .filter { it != selfPackage && it !in NEVER }
        .filter { !isSystemPackage(it) }
        .toList()

    /** 解析 `pm list packages` 的输出（每行 `package:包名`）。 */
    fun parsePmList(output: String): Set<String> = output.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith("package:") }
        .map { it.removePrefix("package:").trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
}
