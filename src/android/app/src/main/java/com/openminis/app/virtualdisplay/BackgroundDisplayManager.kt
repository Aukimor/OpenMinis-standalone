package com.openminis.app.virtualdisplay

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Host-side manager for Mika-style background VirtualDisplay app operation.
 * Binds [BackgroundDisplayShellService] via Shizuku UserService (UID 2000),
 * owns the [ImageReader] surface for fast offscreen frame capture, and
 * coordinates automatic displayId routing for `android-a11y-cli`.
 */
object BackgroundDisplayManager {
    private const val TAG = "BackgroundDisplayManager"

    @Volatile
    var activeDisplayId: Int = -1
        private set

    val activeDisplayIdFlow = kotlinx.coroutines.flow.MutableStateFlow(-1)

    val frameVersionFlow = kotlinx.coroutines.flow.MutableStateFlow(0L)

    @Volatile
    var currentPackageName: String = ""

    @Volatile
    var activeDisplayWidth: Int = 1080

    @Volatile
    var activeDisplayHeight: Int = 2400

    @Volatile
    var autoRouteA11y: Boolean = false

    val showTakeoverDialogFlow = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun openTakeoverDialog() {
        showTakeoverDialogFlow.value = true
    }

    fun closeTakeoverDialog() {
        showTakeoverDialogFlow.value = false
    }

    @Volatile
    private var displayService: IBackgroundDisplayService? = null
    private var imageReader: ImageReader? = null
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null

    /** 本 App 的包名与 PackageManager 来源（startSession 时记下），bg stop --kill 用来排除自己和系统包。 */
    @Volatile
    private var appContext: Context? = null

    /** 本次后台屏会话里启动过的包（bg start / bg launch），bg stop --kill 时逐个 force-stop。 */
    private val launchedPackages = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    /** 正在看预览的界面数（聊天里的实时预览卡、接管弹窗）。为 0 时新帧只更新帧版本，不缩放生成缩略图。 */
    private val previewViewers = PreviewViewerCount()

    /**
     * 界面开始显示后台屏预览时调用，离开时调用返回的释放函数（可重复调用，只生效一次）。
     * 没人看时跳过缩放；有人开始看时，[getUiPreviewBitmap] 会从最新一帧立即生成一张，不会空白。
     */
    fun acquirePreviewViewer(): () -> Unit = previewViewers.acquire()

    private val frameLock = Any()
    private var latestBitmap: Bitmap? = null
    private var lastUiPreviewBitmap: Bitmap? = null
    private var lastPreviewUpdateTs: Long = 0L

    data class SessionResult(
        val ok: Boolean,
        val displayId: Int = -1,
        val message: String = "",
    )

    fun ensureServiceBound(context: Context, timeoutMs: Long = 5_000L): Boolean {
        val current = displayService
        if (current != null && current.asBinder()?.isBinderAlive == true) {
            return true
        }
        ShizukuManager.refresh()
        if (!ShizukuManager.isReady()) {
            AppLogger.warning(TAG, "Shizuku is not ready (${ShizukuManager.snapshot.value.state})")
            return false
        }

        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service != null && service.pingBinder()) {
                    displayService = IBackgroundDisplayService.Stub.asInterface(service)
                    AppLogger.info(TAG, "IBackgroundDisplayService connected")
                }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                AppLogger.warning(TAG, "IBackgroundDisplayService disconnected")
                displayService = null
                // 在主线程回调里不去抢 stopSession/startSession 的锁（startSession 持锁时正等主线程上的
                // onServiceConnected），放到后台线程做本地清理。
                Thread({ onServiceLost() }, "minis-bg-display-lost").apply { isDaemon = true }.start()
            }
        }

        return try {
            val componentName = ComponentName(context.packageName, BackgroundDisplayShellService::class.java.name)
            val args = Shizuku.UserServiceArgs(componentName)
                .daemon(false)
                .processNameSuffix("bg_display")
                .debuggable(false)
                .version(1)

            Handler( android.os.Looper.getMainLooper() ).post {
                try {
                    Shizuku.bindUserService(args, conn)
                } catch (t: Throwable) {
                    AppLogger.error(TAG, "bindUserService post failed: ${t.message}")
                    latch.countDown()
                }
            }
            val connected = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!connected) {
                AppLogger.warning(TAG, "bindUserService timed out after ${timeoutMs}ms")
            }
            displayService != null && displayService?.asBinder()?.isBinderAlive == true
        } catch (t: Throwable) {
            AppLogger.error(TAG, "bindUserService failed: ${t.message}")
            false
        }
    }

    @Synchronized
    fun startSession(
        context: Context,
        packageName: String,
        width: Int = 1080,
        height: Int = 2400,
        dpi: Int = 420,
        muteAudio: Boolean = true,
        force: Boolean = false,
    ): SessionResult {
        stopSession(killPackages = false)
        appContext = context.applicationContext

        val serviceReady = ensureServiceBound(context)
        if (!serviceReady || displayService == null) {
            return SessionResult(
                ok = false,
                displayId = -1,
                message = "Could not bind Shizuku background display service. Ensure Shizuku is running and authorized.",
            )
        }

        val thread = HandlerThread("minis-bg-render").apply { start() }
        renderThread = thread
        val handler = Handler(thread.looper)
        renderHandler = handler

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ ir ->
            val image = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val strideWidth = width + rowPadding / pixelStride

                synchronized(frameLock) {
                    var bmp = latestBitmap
                    if (bmp == null || bmp.width != strideWidth || bmp.height != height) {
                        bmp?.recycle()
                        bmp = Bitmap.createBitmap(strideWidth, height, Bitmap.Config.ARGB_8888)
                        latestBitmap = bmp
                    }
                    plane.buffer.rewind()
                    bmp.copyPixelsFromBuffer(plane.buffer)

                    val now = System.currentTimeMillis()
                    if (now - lastPreviewUpdateTs >= 150L && !previewViewers.isWatched) {
                        // 没有界面在看预览：不缩放，只照常更新帧版本（接管弹窗「操作后无新帧」提示等依赖它）。
                        // 丢掉旧缩略图的引用（不 recycle，界面可能还拿着），下次有人看时从最新一帧现生成。
                        lastPreviewUpdateTs = now
                        lastUiPreviewBitmap = null
                        frameVersionFlow.value = now
                    } else if (now - lastPreviewUpdateTs >= 150L) {
                        lastPreviewUpdateTs = now
                        val targetW = (imageReader?.width ?: bmp.width).coerceAtMost(bmp.width)
                        val targetH = (imageReader?.height ?: bmp.height).coerceAtMost(bmp.height)
                        try {
                            val previewWidth = 540
                            val previewHeight = ((targetH.toLong() * previewWidth) / targetW.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                            val cropped = if (targetW != bmp.width || targetH != bmp.height) {
                                Bitmap.createBitmap(bmp, 0, 0, targetW, targetH)
                            } else {
                                bmp
                            }
                            val scaled = Bitmap.createScaledBitmap(cropped, previewWidth, previewHeight, true)
                            if (cropped != bmp && cropped != scaled) {
                                cropped.recycle()
                            }
                            lastUiPreviewBitmap = scaled
                            frameVersionFlow.value = now
                        } catch (_: Throwable) {
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                image.close()
            }
        }, handler)

        val dId = runCatching {
            displayService!!.createDisplay(reader.surface, width, height, dpi)
        }.getOrDefault(-1)

        if (dId < 0) {
            stopSession(killPackages = false)
            return SessionResult(false, -1, "Failed to create background virtual display")
        }

        activeDisplayId = dId
        activeDisplayIdFlow.value = dId
        currentPackageName = packageName
        activeDisplayWidth = width
        activeDisplayHeight = height
        autoRouteA11y = true

        if (muteAudio) {
            runCatching { displayService?.setAudioMuted(packageName, true) }
        }

        val launchRes = launchApp(context, packageName, muteAudio = false, force = force)
        if (!launchRes.ok) {
            stopSession(killPackages = false)
            return launchRes
        }

        return SessionResult(true, dId, "Background display $dId started with $packageName")
    }

    fun launchApp(
        context: Context,
        packageName: String,
        muteAudio: Boolean = false,
        force: Boolean = false,
    ): SessionResult {
        val dId = activeDisplayId
        if (dId < 0) return SessionResult(false, -1, "No active background display")

        val svc = displayService
        if (muteAudio && svc != null) {
            runCatching { svc.setAudioMuted(packageName, true) }
        }

        launchedPackages += packageName
        if (svc != null) {
            currentPackageName = packageName
            activeDisplayIdFlow.value = dId
            val code = runCatching { svc.startApp(dId, packageName, -1, force) }.getOrDefault(3)
            if (code == 0) return SessionResult(true, dId, "Launched $packageName on display $dId")
        }

        if (force) {
            ShizukuManager.runProcess(arrayOf("am", "force-stop", packageName))
        }
        val comp = context.packageManager.getLaunchIntentForPackage(packageName)?.component?.flattenToShortString()
        val cmd = if (comp != null) {
            arrayOf("am", "start", "--display", "$dId", "-n", comp)
        } else {
            arrayOf("monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1")
        }
        val res = ShizukuManager.runProcess(cmd)
        return if (res.exitCode == 0) {
            SessionResult(true, dId, "Launched $packageName on display $dId")
        } else {
            SessionResult(false, dId, "Failed to launch $packageName: ${res.combined}")
        }
    }

    @Synchronized
    fun stopSession(killPackages: Boolean = false) {
        val dId = activeDisplayId
        if (dId >= 0) {
            runCatching {
                if (!killPackages) {
                    displayService?.settleBehind(dId)
                } else {
                    // 失败也不能挡住下面的 releaseAll
                    runCatching { forceStopDisplayPackages(dId) }
                }
                displayService?.releaseAll()
            }
        }
        launchedPackages.clear()

        releaseLocalState()
    }

    /**
     * bg stop --kill：在释放虚拟屏之前，结束这块屏上运行过的 App（本次会话启动过的包 + 当前顶层 Activity 的包）。
     * 用已有的 `am force-stop`（Shizuku，UID 2000），不碰 OpenMinis 自己和系统包。
     */
    private fun forceStopDisplayPackages(displayId: Int) {
        val top = runCatching { displayService?.topActivity(displayId) }.getOrNull()
        val candidates = synchronized(launchedPackages) { launchedPackages.toList() } + listOfNotNull(currentPackageName, top)
        val ctx = appContext
        // 系统包清单用 Shizuku（UID 2000）查：本 App 受 Android 11+ 包可见性限制，PackageManager 看不到多数第三方 App。
        val systemPkgs = systemPackages()
        val targets = BackgroundDisplayKill.packagesToForceStop(
            candidates = candidates,
            selfPackage = ctx?.packageName ?: "com.openminis.app",
            isSystemPackage = { pkg -> systemPkgs?.contains(pkg) ?: true }, // 查不到清单时宁可不杀
        )
        for (pkg in targets) {
            val res = runCatching { ShizukuManager.runProcess(arrayOf("am", "force-stop", pkg)) }.getOrNull()
            if (res == null || res.exitCode != 0) {
                AppLogger.warning(TAG, "force-stop $pkg failed: ${res?.combined ?: "no result"}")
            } else {
                AppLogger.info(TAG, "force-stopped $pkg (bg stop --kill, display $displayId)")
            }
        }
    }

    /** `pm list packages -s` 的结果；失败时返回 null。 */
    private fun systemPackages(): Set<String>? {
        val res = runCatching { ShizukuManager.runProcess(arrayOf("pm", "list", "packages", "-s")) }.getOrNull() ?: return null
        if (res.exitCode != 0) return null
        return BackgroundDisplayKill.parsePmList(res.combined).takeIf { it.isNotEmpty() }
    }

    /**
     * 只清本进程这边的状态（不碰 Shizuku 服务）：停止自动路由、关掉 ImageReader 和渲染线程、丢弃画面。
     * stopSession 与「服务意外断开」共用。
     */
    private fun releaseLocalState() {
        activeDisplayId = -1
        activeDisplayIdFlow.value = -1
        autoRouteA11y = false

        renderHandler?.removeCallbacksAndMessages(null)
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { renderThread?.quitSafely() }
        renderThread = null
        renderHandler = null

        synchronized(frameLock) {
            latestBitmap?.recycle()
            latestBitmap = null
            // 会话结束后不再保留最后一帧给界面显示（否则停止后旧画面一直残留）。
            // 这里只丢引用、不 recycle：界面可能还拿着这张位图在画，recycle 会抛异常；交给 GC 回收。
            lastUiPreviewBitmap = null
        }
        frameVersionFlow.value = System.currentTimeMillis()
    }

    /** 服务进程意外断开：服务那边的虚拟屏已随之消失，本地也要一起清掉，否则会一直以为后台屏还在。 */
    @Synchronized
    private fun onServiceLost() {
        // 断开后如果已经重新绑定并开始了新的会话，就不要误清
        if (displayService != null) return
        if (activeDisplayId >= 0 || imageReader != null) {
            AppLogger.warning(TAG, "Background display service lost; clearing local session state")
            releaseLocalState()
        }
    }

    fun getUiPreviewBitmap(): Bitmap? {
        // 没有正在运行的后台屏就没有预览（不再返回会话结束前的最后一帧）
        if (activeDisplayId < 0) return null
        synchronized(frameLock) {
            val preview = lastUiPreviewBitmap
            if (preview != null && !preview.isRecycled) {
                return preview
            }
            val src = latestBitmap ?: return null
            if (src.isRecycled) return null
            val targetW = (imageReader?.width ?: src.width).coerceAtMost(src.width)
            val targetH = (imageReader?.height ?: src.height).coerceAtMost(src.height)
            return try {
                val previewWidth = 540
                val previewHeight = ((targetH.toLong() * previewWidth) / targetW.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                val cropped = if (targetW != src.width || targetH != src.height) {
                    Bitmap.createBitmap(src, 0, 0, targetW, targetH)
                } else {
                    src
                }
                val scaled = Bitmap.createScaledBitmap(cropped, previewWidth, previewHeight, true)
                if (cropped != src && cropped != scaled) {
                    cropped.recycle()
                }
                lastUiPreviewBitmap = scaled
                scaled
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun captureBitmap(displayId: Int = activeDisplayId): Bitmap? {
        if (displayId != activeDisplayId || activeDisplayId < 0) return null
        synchronized(frameLock) {
            val src = latestBitmap ?: return null
            val targetW = (imageReader?.width ?: src.width).coerceAtMost(src.width)
            val targetH = (imageReader?.height ?: src.height).coerceAtMost(src.height)
            return runCatching {
                Bitmap.createBitmap(src, 0, 0, targetW, targetH)
            }.getOrNull()
        }
    }

    fun tap(displayId: Int = activeDisplayId, x: Int, y: Int): Boolean {
        if (displayId < 0) return false
        val svc = displayService
        if (svc != null) {
            if (runCatching { svc.injectTap(displayId, x, y) }.getOrDefault(false)) return true
        }
        val res = ShizukuManager.runProcess(arrayOf("input", "-d", "$displayId", "tap", "$x", "$y"))
        return res.exitCode == 0
    }

    fun swipe(
        displayId: Int = activeDisplayId,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int = 300,
    ): Boolean {
        if (displayId < 0) return false
        val svc = displayService
        if (svc != null) {
            if (runCatching { svc.injectSwipe(displayId, x1, y1, x2, y2, durationMs) }.getOrDefault(false)) return true
        }
        val res = ShizukuManager.runProcess(
            arrayOf("input", "-d", "$displayId", "swipe", "$x1", "$y1", "$x2", "$y2", "$durationMs")
        )
        return res.exitCode == 0
    }

    fun key(displayId: Int = activeDisplayId, keyCode: Int): Boolean {
        if (displayId < 0) return false
        val svc = displayService
        if (svc != null) {
            if (runCatching { svc.injectKey(displayId, keyCode) }.getOrDefault(false)) return true
        }
        val res = ShizukuManager.runProcess(arrayOf("input", "-d", "$displayId", "keyevent", "$keyCode"))
        return res.exitCode == 0
    }

    fun pasteText(displayId: Int = activeDisplayId, text: String): Boolean {
        if (displayId < 0 || text.isEmpty()) return false
        val svc = displayService ?: return false
        return runCatching { svc.pasteText(displayId, text) }.getOrDefault(false)
    }

    fun pullBackSpilledTasks(displayId: Int = activeDisplayId): Int {
        if (displayId < 0 || currentPackageName.isBlank()) return 0
        val svc = displayService ?: return 0
        return runCatching { svc.pullBackSpilledTasks(displayId, currentPackageName) }.getOrDefault(0)
    }

    fun status(): JSONObject {
        val svc = displayService
        val bound = svc != null && svc.asBinder()?.isBinderAlive == true
        return JSONObject().apply {
            put("active", activeDisplayId >= 0)
            put("activeDisplayId", activeDisplayId)
            put("packageName", currentPackageName)
            put("autoRouteA11y", autoRouteA11y)
            put("serviceBound", bound)
            put("hasFrame", latestBitmap != null)
            if (activeDisplayId >= 0 && bound) {
                put("topTaskId", runCatching { svc!!.topTaskId(activeDisplayId) }.getOrDefault(-1))
                put("topActivity", runCatching { svc!!.topActivity(activeDisplayId) }.getOrNull() ?: "")
            }
        }
    }
}
