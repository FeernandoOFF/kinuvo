package kinuvo.ui

import kinuvo.app.computer.ComputerDriver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.annotation.RequiresApi
import kinuvo.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.io.encoding.Base64
import kotlin.math.max
import kotlin.math.roundToInt

/** Kinuvo's Accessibility service: present while the user has it turned on in Android's settings. */
class ComputerService : AccessibilityService() {
    @Volatile internal var lastEvent = 0L
    /** The user took over from the overlay; Agents wait until they hand back. */
    @Volatile internal var userControl = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var overlay: android.widget.LinearLayout? = null
    private val hide = Runnable { if (!userControl) removeOverlay() }

    /** Shows that an Agent is acting, with Take over; it hides a few seconds after the Agent's last action. */
    internal fun showAgent() = main.post {
        if (userControl) return@post
        showOverlay("Kinuvo is using this phone", "Take over") { userControl = true; showUser() }
        main.removeCallbacks(hide)
        main.postDelayed(hide, 8_000)
    }

    /** Ends a Takeover the user started here, e.g. when they hand back from Live view. */
    internal fun handBack() { userControl = false; main.post { removeOverlay() } }

    private fun showUser() = main.post {
        main.removeCallbacks(hide)
        showOverlay("You're in control", "Hand back") { userControl = false; removeOverlay() }
    }

    private fun showOverlay(text: String, action: String, onAction: () -> Unit) {
        val density = resources.displayMetrics.density
        val bar = overlay ?: android.widget.LinearLayout(this).also { layout ->
            layout.orientation = android.widget.LinearLayout.HORIZONTAL
            layout.gravity = android.view.Gravity.CENTER_VERTICAL
            layout.setPadding((16 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
            layout.background = android.graphics.drawable.GradientDrawable().apply { setColor(0xEE1F1D1A.toInt()); cornerRadius = 24 * density }
            layout.addView(android.widget.TextView(this).apply { setTextColor(android.graphics.Color.WHITE); textSize = 14f })
            layout.addView(android.widget.Button(this).apply { isAllCaps = false; textSize = 14f })
            val params = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.WRAP_CONTENT, android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL; y = (40 * density).toInt() }
            getSystemService(android.view.WindowManager::class.java).addView(layout, params)
            overlay = layout
        }
        (bar.getChildAt(0) as android.widget.TextView).text = text
        (bar.getChildAt(1) as android.widget.Button).apply { this.text = action; setOnClickListener { onAction() } }
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { getSystemService(android.view.WindowManager::class.java).removeView(it) } }
        overlay = null
    }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { lastEvent = System.currentTimeMillis() }
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { if (instance === this) instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { removeOverlay(); if (instance === this) instance = null; super.onDestroy() }

    internal companion object {
        @Volatile var instance: ComputerService? = null
    }
}

/** Sees the screen through [ComputerService]: elements from the accessibility tree, screenshots on Android 11+. */
internal class AndroidComputer(private val context: Context) : ComputerDriver {
    private class Capture(val id: String, val scale: Float, val nodes: Map<Int, AccessibilityNodeInfo>)
    private val lock = Mutex()
    private var last: Capture? = null
    private var captures = 0
    private var lastShot = 0L

    override val ready get() = ComputerService.instance != null
    override val requirement = "Needs Kinuvo computer use turned on in Android's Accessibility settings."

    override suspend fun perform(request: ComputerRequest): ComputerResult = lock.withLock {
        val service = ComputerService.instance ?: return ComputerResult(DeviceToolStatus.Unavailable, "Turn on Kinuvo in this Device's Accessibility settings")
        request.action?.let {
            if (it.kind !in ComputerActionKind.android) return ComputerResult(DeviceToolStatus.Denied, "${it.kind.action} is not supported on Android")
        }
        if (request.release) service.handBack()
        val agent = !request.user && (request.action != null || request.elements)
        if (agent && service.userControl) return ComputerResult(DeviceToolStatus.Unavailable, "The user took over this phone", userControl = true)
        if (agent) service.showAgent()
        withContext(Dispatchers.Default) {
            if (request.action?.kind == ComputerActionKind.ListApps) {
                val front = service.rootInActiveWindow?.packageName?.toString()
                val pm = context.packageManager
                val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .distinctBy { it.activityInfo.packageName }.map {
                        val id = it.activityInfo.packageName
                        ComputerApp(it.loadLabel(pm).toString(), id, running = true.takeIf { id == front }, frontmost = id == front)
                    }.sortedBy { it.name.lowercase() }
                return@withContext ComputerResult(DeviceToolStatus.Ready, apps = apps)
            }
            val message = request.action?.let { action ->
                val error = try { act(service, action, request) } catch (e: CancellationException) { throw e } catch (e: Exception) { e.message ?: "That didn't work" }
                if (error != null) return@withContext ComputerResult(DeviceToolStatus.Denied, error, capture(service, request.screenshot, request.elements), userControl = service.userControl)
                settle(service)
                null
            }
            ComputerResult(DeviceToolStatus.Ready, message, capture(service, request.screenshot, request.elements), userControl = service.userControl)
        }
    }

    /** Performs [action]; returns why it wasn't done, if it wasn't. */
    private suspend fun act(service: ComputerService, action: ComputerAction, request: ComputerRequest): String? {
        val capture = request.capture
        val user = request.user
        val current = last
        val node = action.element?.let { n ->
            if (current == null || capture != null && capture != current.id) return "The screen changed since that capture; capture again"
            current.nodes[n]?.takeIf { it.refresh() } ?: return "Element $n is no longer on screen; capture again"
        }
        val scale = current?.scale ?: screenScale()
        fun point(x: Int?, y: Int?) = if (x == null || y == null) null else (x / scale) to (y / scale)
        val target = node?.let { center(it) } ?: point(action.x, action.y)
        // Whatever is on top where the gesture lands receives it, e.g. the notification shade over the app.
        val app = target?.let { (x, y) -> appAt(service, x, y) } ?: node?.packageName?.toString() ?: service.rootInActiveWindow?.packageName?.toString()
        // Approved for one app: anything else came forward since, so the Approval doesn't cover it.
        if (!user && request.app != null && action.kind !in setOf(ComputerActionKind.Type, ComputerActionKind.SetValue) && app != request.app)
            return "${app ?: "Another app"} is there now, not ${request.app}; capture again"
        if (!user && action.kind !in setOf(ComputerActionKind.Key, ComputerActionKind.OpenApp, ComputerActionKind.Wait) && app in blocked(context))
            return "Kinuvo doesn't act in $app; ask the user to take over"
        when (action.kind) {
            ComputerActionKind.ClipboardWrite -> (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("", requireNotNull(action.text)))
            ComputerActionKind.Tap -> if (node == null || !click(node, AccessibilityNodeInfo.ACTION_CLICK)) tap(service, requireNotNull(target) { "Give an element or x and y" }, 50)
            ComputerActionKind.DoubleTap -> requireNotNull(target) { "Give an element or x and y" }.let { tap(service, it, 50); delay(100); tap(service, it, 50) }
            ComputerActionKind.LongPress -> if (node == null || !click(node, AccessibilityNodeInfo.ACTION_LONG_CLICK)) tap(service, requireNotNull(target) { "Give an element or x and y" }, (action.durationMs ?: 700).toLong())
            ComputerActionKind.Type, ComputerActionKind.SetValue -> {
                if (action.kind == ComputerActionKind.SetValue && node == null) return "Give an element to set"
                val field = node ?: focusedInput(service) ?: return "No text field is focused; tap one or give its element"
                if (!user && field.packageName?.toString() in blocked(context)) return "Kinuvo doesn't type into ${field.packageName}"
                if (!user && request.app != null && field.packageName?.toString() != request.app) return "${field.packageName} is in front now, not ${request.app}; capture again"
                if (!user && field.isPassword && !request.password) return "That's a password field; capture again, or ask the user to take over"
                if (!field.isFocused) field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val existing = if (Build.VERSION.SDK_INT >= 26 && field.isShowingHintText) "" else field.text?.toString().orEmpty()
                val text = if (action.kind == ComputerActionKind.SetValue) requireNotNull(action.text) else existing + requireNotNull(action.text)
                if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }))
                    return "That element doesn't take text"
            }
            ComputerActionKind.Swipe -> swipe(service, requireNotNull(point(action.x, action.y)), requireNotNull(point(action.toX, action.toY)), (action.durationMs ?: 300).toLong())
            ComputerActionKind.Scroll -> scroll(service, node, action.direction ?: "down")
            ComputerActionKind.Key -> return key(service, requireNotNull(action.key).lowercase())
            ComputerActionKind.OpenApp -> return open(requireNotNull(action.app), user)
            ComputerActionKind.Wait -> delay((action.durationMs ?: 1000).toLong())
            else -> return "${action.kind.action} is not supported on Android"
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo, action: Int): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.actionList.any { it.id == action }) return current.performAction(action)
            current = current.parent
        }
        return false
    }

    private fun center(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen).let { it.exactCenterX() to it.exactCenterY() }

    private suspend fun gesture(service: ComputerService, path: Path, duration: Long) {
        val done = suspendCancellableCoroutine { continuation ->
            val sent = service.dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceIn(1, 10_000))).build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(true) }
                    override fun onCancelled(gestureDescription: GestureDescription?) { if (continuation.isActive) continuation.resume(false) }
                }, null)
            if (!sent && continuation.isActive) continuation.resume(false)
        }
        check(done) { "Android cancelled the gesture" }
    }

    private suspend fun tap(service: ComputerService, at: Pair<Float, Float>, duration: Long) =
        gesture(service, Path().apply { moveTo(at.first, at.second) }, duration)

    private suspend fun swipe(service: ComputerService, from: Pair<Float, Float>, to: Pair<Float, Float>, duration: Long) =
        gesture(service, Path().apply { moveTo(from.first, from.second); lineTo(to.first, to.second) }, duration)

    private suspend fun scroll(service: ComputerService, node: AccessibilityNodeInfo?, direction: String) {
        val scrollable = generateSequence(node) { it.parent }.firstOrNull { it.isScrollable }
        val action = when (direction) {
            "up" -> AccessibilityAction.ACTION_SCROLL_UP
            "left" -> AccessibilityAction.ACTION_SCROLL_LEFT
            "right" -> AccessibilityAction.ACTION_SCROLL_RIGHT
            else -> AccessibilityAction.ACTION_SCROLL_DOWN
        }
        if (scrollable != null && (scrollable.performAction(action.id) ||
                scrollable.performAction(if (direction == "up" || direction == "left") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))) return
        val (w, h) = screenSize()
        val bounds = scrollable?.let { Rect().also(it::getBoundsInScreen) } ?: Rect(0, h / 5, w, h * 4 / 5)
        val cx = bounds.exactCenterX(); val cy = bounds.exactCenterY()
        val dx = bounds.width() * 0.3f; val dy = bounds.height() * 0.3f
        when (direction) {
            "up" -> swipe(service, cx to cy - dy, cx to cy + dy, 300)
            "left" -> swipe(service, cx - dx to cy, cx + dx to cy, 300)
            "right" -> swipe(service, cx + dx to cy, cx - dx to cy, 300)
            else -> swipe(service, cx to cy + dy, cx to cy - dy, 300)
        }
    }

    private fun key(service: ComputerService, key: String): String? {
        val global = when (key) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents", "app_switch" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "enter", "search", "go", "send" -> {
                if (Build.VERSION.SDK_INT < 30) return "Enter needs Android 11 or later; tap the keyboard's action key instead"
                val field = focusedInput(service) ?: return "No text field is focused"
                return if (field.performAction(AccessibilityAction.ACTION_IME_ENTER.id)) null else "The focused field has no Enter action"
            }
            else -> return "Unknown key '$key'; use back, home, recents, notifications, quick_settings or enter"
        }
        return if (service.performGlobalAction(global)) null else "Android didn't press $key"
    }

    private fun open(app: String, user: Boolean): String? {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val match = apps.firstOrNull { it.activityInfo.packageName == app }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().equals(app, ignoreCase = true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().contains(app, ignoreCase = true) }
            ?: return "No app named '$app' is installed"
        if (!user && match.activityInfo.packageName in blocked(context)) return "Kinuvo doesn't open ${match.activityInfo.packageName}"
        context.startActivity(pm.getLaunchIntentForPackage(match.activityInfo.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return null
    }

    /**
     * The app whose window is on top at [x], [y] on the screen; the keyboard counts as the app it types into, and system
     * windows such as the notification shade as the system UI.
     */
    private fun appAt(service: ComputerService, x: Float, y: Float): String? = runCatching {
        val bounds = Rect()
        val window = service.windows.filter { it.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            .sortedByDescending { it.layer }.firstOrNull { it.getBoundsInScreen(bounds); bounds.contains(x.toInt(), y.toInt()) } ?: return@runCatching null
        if (window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) focusedInput(service)?.packageName?.toString()
        else window.root?.packageName?.toString()
    }.getOrNull()

    private fun focusedInput(service: ComputerService) = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    /** Waits for the screen to stop changing, up to two seconds. */
    private suspend fun settle(service: ComputerService) {
        delay(250)
        val until = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < until && System.currentTimeMillis() - service.lastEvent < 300) delay(100)
    }

    private fun screenSize(): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        val real = android.graphics.Point().also { @Suppress("DEPRECATION") display.getRealSize(it) }
        return if (real.x > 0) real.x to real.y else metrics.widthPixels to metrics.heightPixels
    }

    /** Screenshot pixels per screen pixel: the long side is at most [MAX_SIDE]. */
    private fun screenScale() = screenSize().let { (w, h) -> minOf(1f, MAX_SIDE.toFloat() / max(w, h)) }

    /** Without [withElements], a Live view frame: a screenshot only, keeping the Agent's capture and its element numbers. */
    private suspend fun capture(service: ComputerService, screenshot: Boolean, withElements: Boolean = true): ComputerScreen {
        val (w, h) = screenSize()
        val scale = screenScale()
        val nodes = mutableMapOf<Int, AccessibilityNodeInfo>()
        val elements = mutableListOf<ComputerElement>()
        if (!withElements) {
            val image = if (screenshot && Build.VERSION.SDK_INT >= 30) screenshot(service, scale) else null
            return ComputerScreen("frame", (w * scale).roundToInt(), (h * scale).roundToInt(), service.rootInActiveWindow?.packageName?.toString(), screenshot = image, platform = PLATFORM, actions = ComputerActionKind.android)
        }
        val roots = runCatching {
            service.windows.filter { it.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
                .sortedWith(compareByDescending<android.view.accessibility.AccessibilityWindowInfo> { it.isActive }.thenByDescending { it.layer }).mapNotNull { it.root }
        }.getOrDefault(emptyList()).ifEmpty { listOfNotNull(service.rootInActiveWindow) }
        val bounds = Rect()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (elements.size >= MAX_ELEMENTS || depth > 60 || !node.isVisibleToUser) return
            node.getBoundsInScreen(bounds)
            if (!bounds.intersect(0, 0, w, h) || bounds.isEmpty) return
            val label = listOfNotNull(node.text, node.contentDescription, if (Build.VERSION.SDK_INT >= 26) node.hintText else null)
                .map { it.toString().trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
            val interesting = node.isClickable || node.isLongClickable || node.isEditable || node.isScrollable || node.isCheckable || label.isNotEmpty()
            if (interesting) {
                val id = elements.size + 1
                nodes[id] = node
                elements += ComputerElement(id, node.className?.toString()?.substringAfterLast('.')?.ifEmpty { null } ?: "View",
                    if (node.isPassword) "" else label.take(200),
                    (bounds.left * scale).roundToInt(), (bounds.top * scale).roundToInt(), (bounds.width() * scale).roundToInt(), (bounds.height() * scale).roundToInt(),
                    clickable = node.isClickable || node.isLongClickable, editable = node.isEditable, scrollable = node.isScrollable,
                    password = node.isPassword, focused = node.isFocused, checked = if (node.isCheckable) node.isChecked else null)
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        roots.forEach { visit(it, 0) }
        val active = service.rootInActiveWindow
        val id = "a${++captures}"
        last = Capture(id, scale, nodes)
        val image = if (screenshot && Build.VERSION.SDK_INT >= 30) screenshot(service, scale) else null
        return ComputerScreen(id, (w * scale).roundToInt(), (h * scale).roundToInt(), active?.packageName?.toString(),
            runCatching { service.windows.firstOrNull { it.isActive }?.title?.toString() }.getOrNull(), elements, image, PLATFORM, ComputerActionKind.android)
    }

    @RequiresApi(30)
    private suspend fun screenshot(service: ComputerService, scale: Float): String? {
        // Android allows about three screenshots a second.
        val wait = lastShot + 350 - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        lastShot = System.currentTimeMillis()
        val shot = suspendCancellableCoroutine<AccessibilityService.ScreenshotResult?> { continuation ->
            service.takeScreenshot(Display.DEFAULT_DISPLAY, context.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) { if (continuation.isActive) continuation.resume(result) else result.hardwareBuffer.close() }
                override fun onFailure(errorCode: Int) { if (continuation.isActive) continuation.resume(null) }
            })
        } ?: return null
        return shot.hardwareBuffer.use { buffer ->
            val hardware = Bitmap.wrapHardwareBuffer(buffer, shot.colorSpace) ?: return null
            val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
            hardware.recycle()
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(software, (software.width * scale).roundToInt(), (software.height * scale).roundToInt(), true) else software
            val bytes = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
            if (scaled !== software) scaled.recycle()
            software.recycle()
            Base64.encode(bytes)
        }
    }

    private companion object {
        const val PLATFORM = "Android"
        const val MAX_SIDE = 1024
        const val MAX_ELEMENTS = 400
        /** Where Agents never tap or type: Kinuvo itself, and permission and install screens. */
        fun blocked(context: Context) = setOf(context.packageName, "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller")
    }
}
