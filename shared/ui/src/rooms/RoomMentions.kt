package kinuvo.ui.rooms

import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.document.RichTextSpanMark
import com.mohamedrejeb.richeditor.model.RichTextState
import kinuvo.app.rooms.RoomDraft
import kinuvo.app.session.Completion
import kinuvo.app.session.CompletionSource
import kinuvo.protocol.AgentInfo

internal const val RoomMemberTrigger = "room-member"

internal fun roomCompletions(members: List<String>, agents: List<AgentInfo>) = CompletionSource('@', members.map { id ->
    val name = agents.firstOrNull { it.id == id }?.name ?: id
    Completion(name, if (members.count { member -> (agents.firstOrNull { it.id == member }?.name ?: member) == name } > 1) id else "Room member", id)
}, tokenTriggerId = RoomMemberTrigger)

@OptIn(ExperimentalRichTextApi::class)
internal fun RichTextState.roomDraft(members: List<String>): RoomDraft {
    val recipients = toRichTextDocument().blocks.flatMap { block ->
        block.spans.filterIsInstance<RichTextSpanMark.Token>().sortedBy { it.range.first }
            .filter { it.trigger == RoomMemberTrigger && it.id in members }.map { it.id }
    }.distinct()
    return RoomDraft(toMarkdown().trimEnd(), recipients)
}
