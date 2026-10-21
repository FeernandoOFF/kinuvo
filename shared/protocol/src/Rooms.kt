package kinuvo.protocol

import kotlinx.serialization.Serializable

@Serializable
data class Room(val id: String, val name: String, val members: List<String>, val coordinator: String, val direct: Boolean = false)
@Serializable
data class RoomMessage(val id: String, val sender: String? = null, val senderName: String = "You", val text: String, val recipients: List<String> = emptyList(), val replyTo: String? = null, val rootId: String? = null)
@Serializable
data class RoomSession(val id: String, val roomId: String, val messages: List<RoomMessage> = emptyList(), val createdAt: Long = 0)
@Serializable
data class RoomRef(val roomId: String)
@Serializable
data class RoomSessionRef(val roomId: String, val sessionId: String)
@Serializable
data class CreateRoomParams(val name: String, val members: List<String>, val coordinator: String)
@Serializable
data class SendRoomParams(val roomId: String, val sessionId: String, val text: String, val recipients: List<String> = emptyList())
@Serializable
data class RoomProgress(val agentId: String, val sessionId: String, val live: LiveState = LiveState())
@Serializable
data class RoomView(val session: RoomSession, val running: Boolean = false, val progress: RoomProgress? = null, val error: String? = null)
