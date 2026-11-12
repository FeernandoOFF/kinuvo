package kinuvo.plugins.apple

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

const val UNAVAILABLE = "Apple Reminders and Calendar need the Kinuvo desktop app on macOS or the iPhone app"

/**
 * Apple's frameworks behind one JSON call, so a single Swift core serves macOS (a library the Server loads) and the iPhone
 * (the app hands one in). [call] runs [method] with [params], a JSON object, and answers `{"result":…}` or
 * `{"error":{"code":…,"message":…}}`; it blocks while the user answers a permission prompt.
 */
interface AppleSystem {
    fun call(method: String, params: String): String
}

/** An [AppleSystem] that can't reach Apple's frameworks, e.g. a Server run from the command line or on another OS. */
class UnavailableAppleSystem(private val reason: String = UNAVAILABLE) : AppleSystem {
    override fun call(method: String, params: String) = buildJsonObject {
        putJsonObject("error") {
            put("code", "unavailable")
            put("message", reason)
        }
    }.toString()
}

class AppleException(val code: String, message: String) : IllegalStateException(message)

@Serializable
data class AppleList(val id: String, val title: String, val writable: Boolean = true, val source: String = "")

@Serializable
data class AppleReminder(
    val id: String,
    val list_id: String,
    val list: String = "",
    val title: String,
    val notes: String = "",
    val due: String? = null,
    val done: Boolean = false,
)

@Serializable
data class AppleEvent(
    val id: String,
    val calendar_id: String = "",
    val calendar: String = "",
    val title: String,
    val start: String,
    val end: String,
    val all_day: Boolean = false,
    val location: String = "",
    val notes: String = "",
    val recurring: Boolean = false,
)

@Serializable
data class AppleAuthorization(val status: String, val message: String? = null)

/** Where a call that can block, e.g. on a permission prompt, runs. */
internal expect val blocking: CoroutineDispatcher

internal val AppleJson = Json { ignoreUnknownKeys = true }

/** The typed face of an [AppleSystem]: each method name is a Swift core method, its parameters that method's. */
class Apple(private val system: AppleSystem) {
    suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap())): JsonElement = withContext(blocking) {
        val response = AppleJson.parseToJsonElement(system.call(method, params.toString())).jsonObject
        response["error"]?.jsonObject?.let {
            throw AppleException(it["code"]?.jsonPrimitive?.contentOrNull ?: "failed", it["message"]?.jsonPrimitive?.contentOrNull ?: "Apple's frameworks failed")
        }
        requireNotNull(response["result"]) { "Apple's frameworks returned no result" }
    }

    /** Each framework's permission: fullAccess, notDetermined, denied, restricted, writeOnly, or unavailable when this host can't reach Apple's frameworks. */
    suspend fun status(): Map<String, String> = get("status")

    /** Asks the user for access to [framework] (reminders or calendar) if they haven't been asked, and gives where that leaves it. */
    suspend fun authorize(framework: String): AppleAuthorization = get("authorize", buildJsonObject { put("framework", framework) })
}

internal suspend inline fun <reified T> Apple.get(method: String, params: JsonObject = JsonObject(emptyMap())): T =
    AppleJson.decodeFromJsonElement(call(method, params))
