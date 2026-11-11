package kinuvo.server.jvm

import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_LONG

private val log = KotlinLogging.logger("kinuvo.server")

/**
 * Keeps macOS from napping this process while held: App Nap throttles the timers and I/O of apps in the background,
 * which a live call cannot absorb. Uses NSProcessInfo's activity API through the Objective-C runtime; elsewhere it does nothing.
 */
class ProcessActivity(private val reason: String) {
    private var holders = 0
    private var activity: MemorySegment? = null

    fun hold(): AutoCloseable {
        update { holders++ == 0 }
        var open = true
        return AutoCloseable { synchronized(this) { if (open) { open = false; update { --holders == 0 } } } }
    }

    @Synchronized private fun update(changed: () -> Boolean) {
        if (!changed() || !macOS) return
        runCatching {
            val current = activity
            if (current == null) activity = ObjC.begin(reason)
            else { ObjC.end(current); activity = null }
        }.onFailure { log.warn(it) { "Could not change the process activity" } }
    }

    private object ObjC {
        private val arena = Arena.global()
        private val linker = Linker.nativeLinker()
        private val objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", arena)
            .or(SymbolLookup.libraryLookup("/System/Library/Frameworks/Foundation.framework/Foundation", arena))
        private fun function(name: String, descriptor: FunctionDescriptor) = linker.downcallHandle(objc.find(name).orElseThrow(), descriptor)
        private val getClass = function("objc_getClass", FunctionDescriptor.of(ADDRESS, ADDRESS))
        private val selector = function("sel_registerName", FunctionDescriptor.of(ADDRESS, ADDRESS))
        private val send = function("objc_msgSend", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS))
        private val sendObject = function("objc_msgSend", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS))
        private val sendVoid = function("objc_msgSend", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS))
        private val sendBegin = function("objc_msgSend", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS))
        // NSActivityUserInitiated: no App Nap or timer coalescing, and no idle system sleep mid-call.
        private const val USER_INITIATED = 0x00FFFFFFL

        private fun cls(name: String) = Arena.ofConfined().use { getClass.invokeWithArguments(it.allocateFrom(name)) as MemorySegment }
        private fun sel(name: String) = Arena.ofConfined().use { selector.invokeWithArguments(it.allocateFrom(name)) as MemorySegment }

        fun begin(reason: String): MemorySegment {
            val info = send.invokeWithArguments(cls("NSProcessInfo"), sel("processInfo")) as MemorySegment
            val text = Arena.ofConfined().use { sendObject.invokeWithArguments(cls("NSString"), sel("stringWithUTF8String:"), it.allocateFrom(reason)) as MemorySegment }
            val activity = sendBegin.invokeWithArguments(info, sel("beginActivityWithOptions:reason:"), USER_INITIATED, text) as MemorySegment
            check(activity != MemorySegment.NULL) { "No activity" }
            return send.invokeWithArguments(activity, sel("retain")) as MemorySegment
        }

        fun end(activity: MemorySegment) {
            val info = send.invokeWithArguments(cls("NSProcessInfo"), sel("processInfo")) as MemorySegment
            sendVoid.invokeWithArguments(info, sel("endActivity:"), activity)
            send.invokeWithArguments(activity, sel("release"))
        }
    }

    private companion object {
        val macOS = System.getProperty("os.name").orEmpty().startsWith("Mac")
    }
}
