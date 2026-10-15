package kinuvo.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class ComputerActionKind { Tap, DoubleTap, LongPress, Type, Swipe, Scroll, Key, OpenApp, Wait }

/**
 * One input on a Computer. [element] is a number from the latest [ComputerScreen]; [x]/[y] (and [toX]/[toY] for a
 * swipe) are in that screen's pixels. [key] names a key or combination, e.g. `back`, `home`, `enter` or `cmd+c`.
 */
@Serializable
data class ComputerAction(
    val kind: ComputerActionKind,
    val element: Int? = null,
    val x: Int? = null,
    val y: Int? = null,
    val toX: Int? = null,
    val toY: Int? = null,
    val text: String? = null,
    val key: String? = null,
    /** up, down, left or right, for [ComputerActionKind.Scroll]. */
    val direction: String? = null,
    val app: String? = null,
    val durationMs: Int? = null,
)

/**
 * Captures the screen, after performing [action] when set; [capture] is the screen [action]'s element refers to.
 * Without [elements] it's a Live view frame, which doesn't replace the Agent's capture. [user] marks the user's own input.
 * An Agent's [action] was approved for [app] (and, with [password], for typing into a password field): the Computer
 * refuses it anywhere else. [release] hands back a Takeover the user started on the Computer itself.
 * [keepAwake] renews a short display wake lease while the user controls the Computer.
 */
@Serializable
data class ComputerRequest(val action: ComputerAction? = null, val capture: String? = null, val screenshot: Boolean = true,
    val elements: Boolean = true, val user: Boolean = false, val app: String? = null, val password: Boolean = false, val release: Boolean = false,
    val keepAwake: Boolean = false)

/** A visible element, in screen pixels. */
@Serializable
data class ComputerElement(
    val id: Int,
    val role: String,
    val label: String = "",
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val password: Boolean = false,
    val focused: Boolean = false,
    val checked: Boolean? = null,
)

/** What a Computer shows: its foreground [app], elements and an optional JPEG [screenshot] (base64) of [width]×[height]. */
@Serializable
data class ComputerScreen(
    val capture: String,
    val width: Int,
    val height: Int,
    val app: String? = null,
    val title: String? = null,
    val elements: List<ComputerElement> = emptyList(),
    val screenshot: String? = null,
    /** Android, macOS, Windows or Linux: which keys and controls apply. */
    val platform: String? = null,
)

/** [userControl] when the user took over on the Computer itself, so Agents wait. */
@Serializable
data class ComputerResult(val status: DeviceToolStatus, val message: String? = null, val screen: ComputerScreen? = null, val userControl: Boolean = false)

/** Who is using a Computer: nobody, an Agent, or the user during a Takeover. */
@Serializable
enum class ComputerControl { Idle, Agent, User }

/** A screen an Agent can control: a Device that allowed it. [takeoverRequest] is why an Agent asked the user to take over. */
@Serializable
data class ComputerInfo(val id: String, val name: String, val platform: String, val available: Boolean,
    val control: ComputerControl = ComputerControl.Idle, val takeoverRequest: String? = null)

@Serializable
data class WatchComputerParams(val computerId: String, val watch: Boolean)

/** What a Live view shows: the Computer's state and, when captured, its [screen] without elements. */
@Serializable
data class ComputerFrame(val computer: ComputerInfo, val screen: ComputerScreen? = null, val message: String? = null)

@Serializable
data class ComputerInputParams(val computerId: String, val action: ComputerAction)

/** Starts a Takeover ([take]) or hands the Computer back to the Agent. */
@Serializable
data class TakeoverParams(val computerId: String, val take: Boolean)
