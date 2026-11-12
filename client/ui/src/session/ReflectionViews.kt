package kinuvo.ui.session

import kinuvo.app.connection.*
import kinuvo.app.session.*
import kinuvo.app.agent.*
import kinuvo.ui.settings.*
import kinuvo.app.navigation.*
import kinuvo.ui.board.*
import kinuvo.ui.routine.*
import kinuvo.ui.navigation.*
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.*
import com.composeunstyled.theme.Theme
import kinuvo.ui.design.*
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.ui.draw.alpha
import kinuvo.protocol.reflection.ReflectionProposal

@Composable
internal fun ReflectionView(item: ChatItem.Reflection, initiallyExpanded: Boolean = false, onReview: (String, Boolean, String?) -> Unit = { _, _, _ -> }) {
    val result = item.result
    val proposals = result?.proposals.orEmpty()
    val pending = proposals.count { it.id !in item.reviews }
    val knowledge = (result?.knowledge ?: 0) + proposals.count { it.tool == "knowledge" && item.reviews[it.id] == true && it.id !in item.revised }
    val skills = proposals.count { it.tool == "skill_manage" && item.reviews[it.id] == true && it.id !in item.revised }
    val conventions = proposals.count { it.tool == "conventions" && item.reviews[it.id] == true && it.id !in item.revised }
    val saved = (result?.total ?: 0) + proposals.count { item.reviews[it.id] == true && it.id !in item.revised }
    val title = when {
        result == null -> "Reflecting…"
        pending > 0 -> "Reflection needs your review"
        result.error != null -> "Reflection failed"
        result.total == 0 && result.proposals.isEmpty() -> "Nothing to save"
        else -> "Reflection complete"
    }
    val subtitle = when {
        pending > 0 -> "$pending pending"
        saved > 0 -> "$saved saved"
        else -> null
    }
    Expandable(Lucide.Sparkles, title, subtitle, initiallyExpanded = initiallyExpanded, status = {
        when {
            result == null -> Spinner(size = 13.dp)
            pending > 0 -> Icon(Lucide.Shield, Modifier.size(14.dp), tint = color(mutedColor), contentDescription = "Waiting for review")
            result.error != null -> Icon(Lucide.CircleX, Modifier.size(14.dp), tint = color(destructiveColor), contentDescription = "Failed")
            else -> Icon(Lucide.CircleCheck, Modifier.size(14.dp), tint = color(successColor), contentDescription = "Done")
        }
    }) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.space12)) {
            if (result == null) Text("Reviewing this conversation for Knowledge, Memory, User Notes and Skills.", style = TextStyles.secondary)
            else {
                Text("Saved: Knowledge $knowledge · Memory ${result.memory} · User Notes ${result.userNotes} · Skills $skills" + if (conventions > 0) " · Conventions $conventions" else "", style = TextStyles.secondary)
                result.memoryChanges.forEach { change ->
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
                        Text(if (change.target == "memory") "Memory update" else "User Notes update", style = TextStyles.secondary)
                        ReflectionDiff(remember(change) { lineDiff(change.before, change.after) })
                    }
                }
                result.proposals.forEach { proposal ->
                    ReflectionProposalView(proposal, item.reviews[proposal.id], proposal.id in item.revised, onReview)
                }
                result.error?.let { Text(it, color = color(destructiveColor), style = TextStyles.secondary) }
            }
        }
    }
}

@Composable
private fun ReflectionProposalView(proposal: ReflectionProposal, approved: Boolean?, revised: Boolean, onReview: (String, Boolean, String?) -> Unit) {
    val skill = proposal.tool == "skill_manage"
    val conventions = proposal.tool == "conventions"
    val title = proposal.args[if (skill) "name" else "title"]?.jsonPrimitive?.content ?: proposal.note?.title.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
        val kind = when {
            conventions -> "Conventions update"
            !skill -> "Knowledge proposal"
            proposal.skillBefore != null -> "Skill update"
            else -> "Skill draft"
        }
        Text(if (conventions) kind else "$kind: $title", fontWeight = FontWeight.Normal, style = TextStyles.secondary)
        Text(proposal.reason, style = TextStyles.secondary)
        // Evidence quotes the conversation; one line is enough to place it.
        Text("Evidence: ${proposal.evidence}", style = TextStyles.caption, color = color(mutedColor), maxLines = 1, overflow = TextOverflow.Ellipsis)
        val content = proposal.args["content"]?.jsonPrimitive?.content.orEmpty()
        val edited = if (conventions) proposal.conventionsBefore else proposal.skillBefore?.takeIf { skill }
        // An edited Skill shows only what changes; its whole file stays out of the way.
        if (edited != null) {
            ReflectionDiff(remember(edited, content) { lineDiff(edited, content) })
            ReviewButtons(proposal.id, approved, revised, onReview)
            return@Column
        }
        val before = proposal.note?.body
        if (before != null) Expandable(Lucide.FileText, "Current content") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
                proposal.note?.let { note ->
                    Text("${note.title} · ${note.type}", style = TextStyles.secondary)
                    note.properties.forEach { (key, value) -> Text("$key: $value", style = TextStyles.secondary) }
                }
                Markdown(before)
            }
        }
        Expandable(Lucide.FileText, if (skill) "New skill" else "Proposed change", if (skill) "${content.lines().size} lines" else null, initiallyExpanded = !skill) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.space6)) {
                if (skill) Markdown(content)
                else proposal.args.forEach { (key, value) ->
                    val text = value.jsonPrimitive.content
                    if (text.isNotBlank() && key != "ref") {
                        if (key == "append") Markdown(text)
                        else Text("${when (key) {
                            "action" -> "Action"
                            "type" -> "Note type"
                            "title" -> "Title"
                            "set" -> "Properties"
                            "under" -> "Under heading"
                            "link_text" -> "Link text"
                            "link_to" -> "Link target"
                            else -> key
                        }}: $text", style = TextStyles.secondary)
                    }
                }
            }
        }
        ReviewButtons(proposal.id, approved, revised, onReview)
    }
}

@Composable
private fun ReviewButtons(id: String, approved: Boolean?, revised: Boolean, onReview: (String, Boolean, String?) -> Unit) {
    if (approved == null) Column(verticalArrangement = Arrangement.spacedBy(Spacing.space8)) {
        val note = rememberTextFieldState()
        Input(note, "Note for the agent (optional)", capitalize = true)
        Text("Approving with a note asks for a revision.", style = TextStyles.caption, color = color(mutedColor))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.space8)) {
            Button(onClick = { onReview(id, true, note.text.toString()) }, style = ButtonStyle.Primary) { Text("Approve") }
            Button(onClick = { onReview(id, false, note.text.toString()) }, style = ButtonStyle.Ghost) { Text("Reject") }
        }
    } else Text(if (revised) "Revised; awaiting Approval" else if (approved) "Approved and saved" else "Rejected", style = TextStyles.caption, color = color(mutedColor))
}

/** Added and removed lines, summed up as "+3 −1". */
@Composable
private fun ReflectionDiff(diff: List<Pair<Boolean, String>>) {
    val added = diff.count { it.first }
    val summary = listOfNotNull("+$added".takeIf { added > 0 }, "−${diff.size - added}".takeIf { diff.size > added }).joinToString(" ").ifEmpty { "no changes" }
    Expandable(Lucide.FileDiff, "Changes", summary, initiallyExpanded = true) {
        Column(Modifier.fillMaxWidth().background(color(codeColor), Theme[shapes][smallShape]).padding(Spacing.space8)) {
            diff.forEach { (add, line) ->
                val tone = if (add) successColor else destructiveColor
                Text((if (add) "+ " else "− ") + line, Modifier.fillMaxWidth().background(color(tone).copy(alpha = 0.1f)).padding(horizontal = Spacing.space4),
                    style = MonoStyle, color = color(tone))
            }
        }
    }
}

/** The lines removed from [before] (false) and added in [after] (true), in order, by longest common subsequence. */
internal fun lineDiff(before: String, after: String): List<Pair<Boolean, String>> {
    val a = before.trimEnd().takeIf { it.isNotEmpty() }?.lines().orEmpty()
    val b = after.trimEnd().takeIf { it.isNotEmpty() }?.lines().orEmpty()
    val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) for (j in b.indices.reversed())
        lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
    val out = mutableListOf<Pair<Boolean, String>>()
    var i = 0
    var j = 0
    while (i < a.size || j < b.size) when {
        i < a.size && j < b.size && a[i] == b[j] -> { i++; j++ }
        i < a.size && (j == b.size || lcs[i + 1][j] >= lcs[i][j + 1]) -> out += false to a[i++]
        else -> out += true to b[j++]
    }
    return out
}
