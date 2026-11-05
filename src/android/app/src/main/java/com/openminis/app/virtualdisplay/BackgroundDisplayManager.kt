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

    @Volatile
    var autoRouteA11y: Boolean = false

    @Volatile
    private var displayService: IBackgroundDisplayService? = null
    private var imageReader: ImageReader? = null
    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null

    private val frameLock = Any()
    private var latestBitmap: Bitmap? = null

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

        if (svc != null) {
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
                }
                displayService?.releaseAll()
            }
        }

        activeDisplayId = -1
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

    fun status(): JSONObject {
        val svc = displayService
        val bound = svc != null && svc.asBinder()?.isBinderAlive == true
        return JSONObject().apply {
            put("active", activeDisplayId >= 0)
            put("activeDisplayId", activeDisplayId)
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
