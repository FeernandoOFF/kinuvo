package kinuvo.ui.surface

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kinuvo.ui.session.speechResources
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

internal class MacHtmlView : AutoCloseable {
    private val symbols = SymbolLookup.libraryLookup(File(speechResources(), "libKinuvoWeb.dylib").toPath(), Arena.global())
    private fun function(name: String, descriptor: FunctionDescriptor) = Linker.nativeLinker().downcallHandle(symbols.find("kinuvo_web_$name").orElseThrow(), descriptor)
    private val create = function("create", FunctionDescriptor.of(ADDRESS))
    private val send = function("send", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
    private val poll = function("poll", FunctionDescriptor.of(ADDRESS, ADDRESS))
    private val frame = function("frame", FunctionDescriptor.of(ADDRESS, ADDRESS))
    private val free = function("free", FunctionDescriptor.ofVoid(ADDRESS))
    private val close = function("close", FunctionDescriptor.ofVoid(ADDRESS))
    private var handle = create.invokeWithArguments() as MemorySegment

    @Synchronized fun send(command: JsonObject) {
        if (handle == MemorySegment.NULL) return
        Arena.ofConfined().use { send.invokeWithArguments(handle, it.allocateFrom(command.toString())) }
    }

    @Synchronized fun poll(): String? {
        if (handle == MemorySegment.NULL) return null
        val event = poll.invokeWithArguments(handle) as MemorySegment
        if (event == MemorySegment.NULL) return null
        return try { event.reinterpret(1024L * 1024).getString(0) } finally { free.invokeWithArguments(event) }
    }

    @Synchronized fun pixels(): Frame? {
        if (handle == MemorySegment.NULL) return null
        val pointer = frame.invokeWithArguments(handle) as MemorySegment
        if (pointer == MemorySegment.NULL) return null
        return try {
            val header = pointer.reinterpret(8)
            val width = header.get(JAVA_INT, 0)
            val height = header.get(JAVA_INT, 4)
            require(width in 1..8192 && height in 1..4096)
            val size = width * height * 4
            Frame(width, height, pointer.reinterpret(size.toLong() + 8).asSlice(8, size.toLong()).toArray(JAVA_BYTE))
        } finally { free.invokeWithArguments(pointer) }
    }

    @Synchronized override fun close() {
        if (handle == MemorySegment.NULL) return
        close.invokeWithArguments(handle)
        handle = MemorySegment.NULL
    }

    data class Frame(val width: Int, val height: Int, val bgra: ByteArray) {
        private fun raster() = Image.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL), bgra, width * 4)
        fun bitmap() = Bitmap().apply {
            check(allocPixels(ImageInfo(this@Frame.width, this@Frame.height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)))
            raster().use { check(it.readPixels(this)) }
        }
        fun image(): ImageBitmap = raster().toComposeImageBitmap()
    }
}
