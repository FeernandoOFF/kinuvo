package kinuvo.ui.screen

import android.annotation.SuppressLint
import android.R
import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kinuvo.harness.browser.browserDeadline
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.io.encoding.Base64

@SuppressLint("SetJavaScriptEnabled")
internal class AndroidBrowserSurface(context: Context, profile: String) {
    val view = WebView(context.applicationContext)
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var latest: Bitmap? = null
    private var frame = CompletableDeferred<Unit>()
    private var failed: String? = null
    private val captureLock = Mutex()
    var closed = false
        private set

    init {
        try {
            WebView.setWebContentsDebuggingEnabled(false)
            WebViewCompat.setProfile(view, profile)
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.allowFileAccess = false
            view.settings.allowContentAccess = false
            view.settings.setSupportMultipleWindows(false)
            view.settings.setSupportZoom(false)
            view.setInitialScale(100)
            val images = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2)
            reader = images
            images.setOnImageAvailableListener({ source ->
                source.acquireLatestImage()?.use { image ->
                    val plane = image.planes[0]
                    val padded = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
                    padded.copyPixelsFromBuffer(plane.buffer)
                    val bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                    if (bitmap !== padded) padded.recycle()
                    latest?.recycle(); latest = bitmap
                    frame.complete(Unit)
                }
            }, Handler(Looper.getMainLooper()))
            display = checkNotNull(context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
                "Kinuvo Browser", WIDTH, HEIGHT, 160, images.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION))
            presentation = Presentation(context.applicationContext, display!!.display, R.style.Theme_Material_Light_NoActionBar).apply {
                window?.setType(WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION)
                window?.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
                setContentView(view)
                show()
                window?.setLayout(WIDTH, HEIGHT)
            }
        } catch (e: Exception) { close(); throw e }
    }
    fun checkReady() { check(!closed && failed == null) { failed ?: "Browser tab is closed" } }
    fun rendererGone() { failed = "Browser renderer exited. Close this tab and open a new one."; close() }
    suspend fun evaluate(script: String): String = browserDeadline(5000) {
        checkReady()
        suspendCancellableCoroutine { continuation ->
            val wrapped = """(() => {try {return JSON.stringify({ok:true,value:($script)});} catch(e) {return JSON.stringify({ok:false,error:String(e.message||e)});}})()"""
            view.evaluateJavascript(wrapped) { raw ->
                if (continuation.isActive) continuation.resumeWith(runCatching {
                    checkReady()
                    val result = JSONObject(JSONArray("[$raw]").getString(0))
                    check(result.getBoolean("ok")) { result.optString("error", "Browser script failed") }
                    result.getString("value")
                })
            }
        }
    }
    suspend fun synchronize() = browserDeadline(5000) {
        checkReady()
        suspendCancellableCoroutine { continuation ->
            view.postVisualStateCallback(SystemClock.uptimeMillis(), object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) { if (continuation.isActive) continuation.resume(Unit) }
            })
        }
        checkReady()
    }
    suspend fun image(): String {
        checkReady()
        if (latest == null) browserDeadline(5000) { frame.await() }
        val bytes = ByteArrayOutputStream()
        for (quality in listOf(65, 45, 25)) {
            bytes.reset()
            checkNotNull(latest) { "No Browser frame available" }.compress(Bitmap.CompressFormat.JPEG, quality, bytes)
            if (bytes.size() <= 700_000) break
        }
        check(bytes.size() <= 700_000) { "Browser frame exceeds delivery budget" }
        return Base64.encode(bytes.toByteArray())
    }
    suspend fun freshImage(): String = captureLock.withLock {
        synchronize()
        // Draw credits and viewer acknowledgements are independent; production never waits for a viewer.
        frame = CompletableDeferred()
        view.invalidate()
        browserDeadline(5000) { frame.await() }
        image()
    }
    fun close() {
        if (closed) return
        closed = true
        frame.completeExceptionally(IllegalStateException("Browser tab closed"))
        view.stopLoading()
        presentation?.dismiss(); presentation = null
        view.destroy()
        display?.release(); display = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close(); reader = null
        latest?.recycle(); latest = null
    }
    companion object { const val WIDTH = 1280; const val HEIGHT = 800 }
}
