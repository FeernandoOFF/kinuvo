package kinuvo.client

import kinuvo.protocol.*
import kotlinx.coroutines.*

/** Calls are sent once. After an unknown mutation outcome, observe before acting again. */
class ScreensClient(private val client: KinuvoClient) {
    suspend fun open(request: ScreenOpen) = request(Api.ScreenOpen, request, 360_000)
    suspend fun list(owner: BrowserOwner? = null) = request(Api.Screens, owner)
    suspend fun watch(screen: ScreenRef, thumbnail: Boolean = false) = request(Api.ScreenWatch, ScreenWatch(screen, thumbnail))
    suspend fun unwatch(subscription: String) = request(Api.ScreenUnwatch, subscription)
    suspend fun ack(frame: ScreenFrameAck) = request(Api.ScreenAck, frame)
    suspend fun control(request: ScreenControlRequest) = request(Api.ScreenControl, request)
    suspend fun input(request: ScreenInputRequest) = request(Api.ScreenInput, request)
    suspend fun command(request: ScreenCommand) = request(Api.ScreenCommand, request)
    private suspend fun <P, R> request(method: Method<P, R>, params: P, timeout: Long = 60_000): R = try {
        withTimeout(timeout) { client.call(method, params) }
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw IllegalStateException("Screen request timed out; observe before sending input again", e)
    }

    val changes get() = client.events(Api.ScreensChanged)
    val frames get() = client.events(Api.ScreenFrame)
    val status get() = client.events(Api.ScreenStatus)
}
