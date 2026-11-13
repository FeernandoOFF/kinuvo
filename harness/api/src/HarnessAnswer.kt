package kinuvo.harness

import kinuvo.protocol.approval.InteractionAnswer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject

/** What an external harness's request receives; Kinuvo's own Approvals never go to a harness. */
fun InteractionAnswer.toHarnessJson(): JsonElement = when (this) {
    is InteractionAnswer.Harness -> answer
    is InteractionAnswer.Questions -> answers?.let { values ->
        buildJsonObject { putJsonObject("answers") { values.forEach { (id, list) -> put(id, JsonArray(list.map(::JsonPrimitive))) } } }
    } ?: JsonNull
    is InteractionAnswer.Approval -> throw IllegalArgumentException("A harness request can't take a Kinuvo Approval decision")
    is InteractionAnswer.Secret -> throw IllegalArgumentException("A harness request can't take a Secret answer")
}
