package kinuvo.protocol

import kotlinx.serialization.Serializable

@Serializable
data class ScreenRef(val source: String, val id: String, val owner: BrowserOwner? = null)

@Serializable
enum class ScreenDelivery { Continuous, CaptureRefresh }

@Serializable
data class ScreenCapabilities(val delivery: ScreenDelivery, val inputs: Set<String>, val navigation: Boolean = false,
    val tabs: Boolean = false, val windows: Boolean = false)

@Serializable
enum class ScreenStatus { Provisioning, Ready, Unavailable, Failed }

@Serializable
data class ScreenControl(val generation: Long = 0, val holder: String? = null, val suspended: Boolean = false,
    val draining: Boolean = false)

@Serializable
data class ScreenInfo(val ref: ScreenRef, val title: String, val capabilities: ScreenCapabilities,
    val status: ScreenStatus, val control: ScreenControl = ScreenControl(), val active: Boolean = false)

/** Pixels and coordinate metadata are one indivisible source sample. Scroll deltas use CSS/display pixels. */
@Serializable
data class ScreenSample(val image: String, val width: Int, val height: Int, val geometry: String)

/** Epoch is fresh for each subscription, including reconnects. */
@Serializable
data class ScreenFrame(val screen: ScreenRef, val subscription: String, val epoch: String, val sequence: Long,
    val sample: ScreenSample)

@Serializable
data class ScreenFrameAck(val subscription: String, val epoch: String, val sequence: Long)

@Serializable
data class ScreenInput(val frame: ScreenFrameAck, val geometry: String, val generation: Long, val sequence: Long,
    val actions: List<ComputerAction>)

@Serializable
data class ScreenInputResult(val applied: Int, val message: String? = null)
