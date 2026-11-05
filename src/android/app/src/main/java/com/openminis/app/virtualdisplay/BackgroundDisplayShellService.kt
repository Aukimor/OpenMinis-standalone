package com.openminis.app.virtualdisplay

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Application
import android.app.Instrumentation
import android.content.AttributionSource
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/**
 * Privileged Shizuku UserService (UID 2000 / shell) ported from Mika's ShellService & ShellContext.
 * Creates an isolated background VirtualDisplay, keeps its DisplayGroup awake,
 * launches target apps onto it without stealing top focus on Android 14+,
 * injects touch/key events directed at that displayId, and mutes target app audio.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class BackgroundDisplayShellService : IBackgroundDisplayService.Stub {

    private val context: Context
    private val displays = HashMap<Int, VirtualDisplay>()
    private val mutedPackages = ConcurrentHashMap<String, String>()

    constructor() {
        this.context = createShellContext()
    }

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") hostContext: Context) {
        // Always wrap the system context as com.android.shell (UID 2000) so
        // DisplayManager.createVirtualDisplay passes the "packageName must
        // match the calling uid" check inside IDisplayManager.
        this.context = createShellContext()
    }

    private val displayManager: DisplayManager by lazy {
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)
    }

    private val taskManager: Any by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    }

    private val userActivity: Pair<Any, Method>? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "power")
            val power = Class.forName("android.os.IPowerManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
            val method = power.javaClass.methods.firstOrNull {
                it.name == "userActivity" && it.parameterTypes.contentEquals(
                    arrayOf(
                        Int::class.javaPrimitiveType,
                        Long::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    )
                )
            } ?: error("no per-display userActivity")
            power to method
        }.onFailure { Log.w(TAG, "display keep-awake unavailable: ${it.message}") }.getOrNull()
    }

    private val awakeExecutor = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "minis-display-awake").apply { isDaemon = true }
    }
    private val awakeTasks = HashMap<Int, ScheduledFuture<*>>()

    private fun ownDisplay(displayId: Int): Boolean = synchronized(displays) { displayId in displays }

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int = try {
        createVirtualDisplay(surface, width, height, dpi, trusted = true)
    } catch (t: Throwable) {
        if (t is SecurityException) {
            Log.w(TAG, "createDisplay without trusted flags, retrying: ${t.message}")
            try {
                createVirtualDisplay(surface, width, height, dpi, trusted = false)
            } catch (t2: Throwable) {
                Log.e(TAG, "createDisplay failed on fallback", t2)
                -1
            }
        } else {
            Log.e(TAG, "createDisplay failed", t)
            -1
        }
    }

    private fun createVirtualDisplay(
        surface: Surface,
        width: Int,
        height: Int,
        dpi: Int,
        trusted: Boolean,
    ): Int {
        val flags = displayFlags(Build.VERSION.SDK_INT, trusted)
        val vd = displayManager.createVirtualDisplay("minis-bg-display", width, height, dpi, surface, flags)
        val id = vd.display.displayId
        synchronized(displays) { displays[id] = vd }
        setFullscreenWindowingMode(id)
        setDisplayImePolicy(id, 0)

        if (flags and FLAG_OWN_DISPLAY_GROUP != 0) {
            startAwake(id)
        }
        return id
    }

    private fun startAwake(displayId: Int) {
        synchronized(awakeTasks) {
            awakeTasks[displayId]?.cancel(false)
            val future = awakeExecutor.scheduleWithFixedDelay({
                pokeDisplay(displayId)
            }, 0, 4_000L, TimeUnit.MILLISECONDS)
            awakeTasks[displayId] = future
        }
    }

    private fun stopAwake(displayId: Int) {
        synchronized(awakeTasks) {
            awakeTasks.remove(displayId)?.cancel(false)
        }
    }

    private fun pokeDisplay(displayId: Int): Boolean {
        val (power, method) = userActivity ?: return false
        return try {
            method.invoke(power, displayId, SystemClock.uptimeMillis(), 2, 0)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "pokeDisplay failed on $displayId", t)
            false
        }
    }

    override fun releaseDisplay(displayId: Int) {
        stopAwake(displayId)
        val vd = synchronized(displays) { displays.remove(displayId) }
        vd?.release()
    }

    override fun startApp(displayId: Int, packageName: String, userId: Int, force: Boolean): Int {
        if (!ownDisplay(displayId)) return 3
        if (force) {
            val user = if (userId < 0) "current" else "$userId"
            exec("am", "force-stop", "--user", user, packageName)
        }

        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName) ?: run {
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
            val list = pm.queryIntentActivities(query, 0)
            list.firstOrNull()?.let {
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(ComponentName(it.activityInfo.packageName, it.activityInfo.name))
            }
        } ?: return 2

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()

        val ok = try {
            val start = taskManager.javaClass.methods.first {
                it.name == "startActivityAsUser" && it.parameterTypes.size == 12
            }
            val result = start.invoke(
                taskManager, null, SHELL_PACKAGE, null, intent, null, null, null,
                0, 0, null, options, if (userId < 0) -2 else userId,
            ) as Int
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser failed, falling back to am start", t)
            val comp = intent.component?.flattenToShortString() ?: return 2
            val userArgs = if (userId >= 0) arrayOf("--user", "$userId") else emptyArray()
            exec("am", "start", *userArgs, "--display", "$displayId", "-n", comp) == 0
        }

        return if (ok) 0 else 3
    }

    override fun topTaskId(displayId: Int): Int = try {
        tasksOn(displayId).firstOrNull()?.let { TASK_INFO.getField("taskId").getInt(it) } ?: -1
    } catch (_: Throwable) {
        -1
    }

    override fun topActivity(displayId: Int): String? = try {
        if (!ownDisplay(displayId)) null else tasksOn(displayId).firstOrNull()?.let {
            (TASK_INFO.getField("topActivity").get(it) as? ComponentName)?.flattenToShortString()
        }
    } catch (_: Throwable) {
        null
    }

    override fun moveTaskToDisplay(taskId: Int, displayId: Int): Boolean = try {
        taskManager.javaClass.getMethod(
            "moveRootTaskToDisplay",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).invoke(taskManager, taskId, displayId)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "moveRootTaskToDisplay failed, falling back to am", t)
        exec("am", "display", "move-stack", "$taskId", "$displayId") == 0
    }

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        val up = MotionEvent.obtain(now, now + 50L, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        return try {
            if (!inject(down, displayId)) {
                exec("input", "-d", "$displayId", "tap", "$x", "$y") == 0
            } else {
                inject(up, displayId) || inject(up, displayId)
            }
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    override fun injectSwipe(
        displayId: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
    ): Boolean {
        val duration = durationMs.coerceAtLeast(100).toLong()
        val steps = (duration / 16).toInt().coerceIn(5, 100)
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x1.toFloat(), y1.toFloat(), 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        if (!inject(down, displayId)) {
            down.recycle()
            return exec("input", "-d", "$displayId", "swipe", "$x1", "$y1", "$x2", "$y2", "$durationMs") == 0
        }
        down.recycle()

        for (i in 1..steps) {
            val stepTime = now + (duration * i / steps)
            val wait = stepTime - SystemClock.uptimeMillis()
            if (wait > 0) SystemClock.sleep(wait)
            val currX = x1 + (x2 - x1) * i / steps.toFloat()
            val currY = y1 + (y2 - y1) * i / steps.toFloat()
            val move = MotionEvent.obtain(now, stepTime, MotionEvent.ACTION_MOVE, currX, currY, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
            inject(move, displayId)
            move.recycle()
        }

        val up = MotionEvent.obtain(now, now + duration, MotionEvent.ACTION_UP, x2.toFloat(), y2.toFloat(), 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        val ok = inject(up, displayId) || inject(up, displayId)
        up.recycle()
        return ok
    }

    override fun injectKey(displayId: Int, keyCode: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        return if (!inject(down, displayId)) {
            exec("input", "-d", "$displayId", "keyevent", "$keyCode") == 0
        } else {
            inject(up, displayId) || inject(up, displayId)
        }
    }

    override fun setAudioMuted(packageName: String, muted: Boolean): Boolean = synchronized(mutedPackages) {
        if (muted) {
            if (!mutedPackages.containsKey(packageName)) {
                val (_, out) = sh("appops", "get", packageName, "PLAY_AUDIO")
                val mode = Regex("""PLAY_AUDIO:\s*([a-z_]+)""").find(out)?.groupValues?.get(1) ?: "allow"
                mutedPackages[packageName] = mode
            }
            sh("appops", "set", packageName, "PLAY_AUDIO", "ignore").first == 0
        } else {
            val original = mutedPackages.remove(packageName) ?: "allow"
            val restoreMode = if (original == "default") "allow" else original
            sh("appops", "set", packageName, "PLAY_AUDIO", restoreMode).first == 0
        }
    }

    override fun settleBehind(displayId: Int) {
        val rows = tasksOn(displayId)
        for (row in rows) {
            val taskId = runCatching { TASK_INFO.getField("taskId").getInt(row) }.getOrDefault(-1)
            if (taskId >= 0) {
                moveTaskToDisplay(taskId, Display.DEFAULT_DISPLAY)
            }
        }
    }

    override fun releaseAll() {
        synchronized(mutedPackages) {
            for ((pkg, mode) in mutedPackages) {
                val restoreMode = if (mode == "default") "allow" else mode
                sh("appops", "set", pkg, "PLAY_AUDIO", restoreMode)
            }
            mutedPackages.clear()
        }
        synchronized(awakeTasks) {
            awakeTasks.values.forEach { it.cancel(false) }
            awakeTasks.clear()
        }
        val allDisplays = synchronized(displays) {
            val copy = displays.values.toList()
            displays.clear()
            copy
        }
        allDisplays.forEach { runCatching { it.release() } }
    }

    override fun destroy() {
        releaseAll()
        exitProcess(0)
    }

    @SuppressLint("PrivateApi")
    private fun setFullscreenWindowingMode(displayId: Int) {
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getWindowManagerService").invoke(null)!!
            wm.javaClass.getMethod(
                "setWindowingMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).invoke(wm, displayId, 1)
        }.onFailure {
            exec("wm", "set-display-windowing-mode", "-d", "$displayId", "1")
        }
    }

    @SuppressLint("PrivateApi")
    private fun setDisplayImePolicy(displayId: Int, policy: Int) {
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getWindowManagerService").invoke(null)!!
            wm.javaClass.getMethod(
                "setDisplayImePolicy",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).invoke(wm, displayId, policy)
        }
    }

    @SuppressLint("PrivateApi")
    private fun inject(event: InputEvent, displayId: Int): Boolean = try {
        event.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(event, displayId)
        val manager = inputManager()
        val mode = if (event is KeyEvent) 1 else 0
        manager.javaClass.getMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Int::class.javaPrimitiveType,
        ).invoke(manager, event, mode) as Boolean
    } catch (_: Throwable) {
        false
    }

    @SuppressLint("PrivateApi")
    private fun inputManager(): Any = runCatching {
        Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
    }.getOrNull() ?: Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!!

    private fun tasksOn(displayId: Int): List<Any> {
        return try {
            val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
            val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
            val tasks = if (withDisplay != null) {
                withDisplay.invoke(taskManager, 100, false, false, displayId)
            } else {
                methods.first { it.parameterTypes.size == 3 }.invoke(taskManager, 100, false, false)
            } as? List<*> ?: emptyList<Any>()
            val displayField = TASK_INFO.getField("displayId")
            tasks.filterNotNull().filter { displayField.getInt(it) == displayId }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun exec(vararg cmd: String): Int = sh(*cmd).first

    private fun sh(vararg cmd: String): Pair<Int, String> {
        val p = try {
            ProcessBuilder(*cmd).redirectErrorStream(true).start()
        } catch (_: Throwable) {
            return -1 to ""
        }
        val output = AtomicReference("")
        val reader = Thread {
            output.set(runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault(""))
        }.apply { isDaemon = true; start() }
        if (!p.waitFor(10L, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return -1 to ""
        }
        reader.join(1000L)
        return p.exitValue() to output.get()
    }

    companion object {
        private const val TAG = "MinisBgShell"
        const val SHELL_PACKAGE = "com.android.shell"

        private val TASK_INFO: Class<*> by lazy { Class.forName("android.app.TaskInfo") }

        private const val FLAG_PUBLIC = 1 shl 0
        private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val FLAG_TRUSTED = 1 shl 10
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val FLAG_OWN_FOCUS = 1 shl 14
        private const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

        internal fun displayFlags(sdk: Int, trusted: Boolean): Int {
            var flags = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
                FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL
            if (sdk >= Build.VERSION_CODES.TIRAMISU) {
                flags = flags or FLAG_TOUCH_FEEDBACK_DISABLED
                if (trusted) flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED
            }
            if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && trusted) {
                flags = flags or FLAG_OWN_FOCUS or FLAG_STEAL_TOP_FOCUS_DISABLED
            }
            return flags
        }

        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        private fun createShellContext(): Context {
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("currentActivityThread").invoke(null) ?: newActivityThread(at)
            val system = at.getDeclaredMethod("getSystemContext").invoke(thread) as Context
            val ctx = object : ContextWrapper(system) {
                override fun getPackageName(): String = SHELL_PACKAGE
                override fun getOpPackageName(): String = SHELL_PACKAGE
                @RequiresApi(Build.VERSION_CODES.S)
                override fun getAttributionSource(): AttributionSource =
                    AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()
                override fun getApplicationContext(): Context = this
            }
            runCatching {
                val field = at.getDeclaredField("mInitialApplication").apply { isAccessible = true }
                if (field.get(thread) == null) {
                    val app = Instrumentation.newApplication(Application::class.java, ctx)
                    field.set(thread, app)
                }
            }
            return ctx
        }

        @SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi")
        private fun newActivityThread(at: Class<*>): Any {
            val thread = at.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, thread)
            at.getDeclaredField("mSystemThread").apply { isAccessible = true }.setBoolean(thread, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching {
                val cc = Class.forName("android.app.ConfigurationController")
                val internal = Class.forName("android.app.ActivityThreadInternal")
                val controller = cc.getDeclaredConstructor(internal).apply { isAccessible = true }.newInstance(thread)
                at.getDeclaredField("mConfigurationController").apply { isAccessible = true }.set(thread, controller)
            }
            runCatching {
                val abd = Class.forName("android.app.ActivityThread\$AppBindData")
                val data = abd.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                abd.getDeclaredField("appInfo").apply { isAccessible = true }
                    .set(data, ApplicationInfo().apply { packageName = SHELL_PACKAGE })
                at.getDeclaredField("mBoundApplication").apply { isAccessible = true }.set(thread, data)
            }
            return thread
        }
    }
}
