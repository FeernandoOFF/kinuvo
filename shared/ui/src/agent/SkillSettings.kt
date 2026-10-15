package kinuvo.ui.agent

import kinuvo.app.connection.*
import kinuvo.app.agent.*
import kinuvo.ui.board.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import com.composables.ui.components.*
import com.composables.ui.theme.mutedColor
import kinuvo.protocol.ConfigScope
import kinuvo.protocol.SkillDetails
import kinuvo.protocol.SkillInfo
import kinuvo.ui.design.*

@Composable
internal fun SkillList(p: Pane) {
    val skills = p.ui.config.skills
    ItemList(
        p, "Skills",
        "Instructions ${p.ui.agent?.name ?: "the agent"} loads for a kind of task, by itself or when you type /name. " +
            "Its own Skills win over shared ones of the same name.",
        "New skill", skills == null,
    ) {
        items(skills.orEmpty(), key = { itemKey(it.scope, it.name) }) { s ->
            ItemRow(itemKey(s.scope, s.name) == p.ui.route.item, Lucide.BookOpen, s.name, s.description, { p.go(p.ui.route.copy(item = itemKey(s.scope, s.name), editing = false)) }) {
                if (!s.active) Pill("Shadowed")
                if (s.scope != ConfigScope.Agent) Pill(s.scope.label())
            }
        }
    }
}

@Composable
internal fun SkillPane(p: Pane) {
    val route = p.ui.route
    val skill = p.ui.state.skill
    when {
        route.editing && route.item == null -> SkillForm(null, p)
        skill == null -> if (p.ui.config.skills?.none { itemKey(it.scope, it.name) == route.item } == true) DetailPane(p.back, "Not found") {} else PaneLoading()
        route.editing -> SkillForm(skill, p)
        else -> SkillDetail(skill, p)
    }
}

@Composable
private fun SkillDetail(s: SkillDetails, p: Pane) {
    val info = s.info
    DetailPane(p.back, info.name) {
        if (info.scope != ConfigScope.External) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(Lucide.Pencil, "Edit", { p.go(p.ui.route.copy(editing = true)) })
            DeleteButton("skill", itemKey(info.scope, info.name)) { p.actions.onDeleteSkill(info.scope, info.name) }
        }
        p.ui.error?.let { Pill(it, tone = Tone.Danger, icon = Lucide.CircleX) }
        DetailGroup("About") {
            DetailRow("Description") { Text(info.description) }
            DetailRow("Where") { Text(info.where() + if (info.active) "" else " · shadowed by another skill of the same name") }
            DetailRow("Used by") { Text(info.invocation()) }
        }
        if (s.files.isNotEmpty()) DetailGroup("Files") { s.files.forEach { Text(it, style = MonoStyle) } }
        DetailGroup("SKILL.md") { CodeBlock(s.content, language = "markdown") }
    }
}

/** A new Skill when [s] is null; otherwise edits its SKILL.md. */
@Composable
private fun SkillForm(s: SkillDetails?, p: Pane) {
    val name = rememberTextFieldState(s?.info?.name.orEmpty())
    var scope by remember { mutableStateOf(s?.info?.scope ?: ConfigScope.Agent) }
    val content = rememberTextFieldState(s?.content ?: TEMPLATE)
    val skillName = s?.info?.name ?: name.value()
    val save = {
        val text = content.text.toString().let { if (s == null) it.replaceFirst("name: my-skill", "name: $skillName") else it }
        p.actions.onSaveSkill(scope, skillName, text)
    }
    DetailPane(p.back, s?.let { "Edit ${it.info.name}" } ?: "New skill", error = p.ui.error, trailing = { FormActions(if (s == null) "Create skill" else "Save", skillName.isNotEmpty(), p.ui.state.saved, onSave = save, onCancel = p.cancel) }) {
        if (s == null) {
            FormField("Name", "Lowercase letters, digits and hyphens; also the /command.") { Input(name, "weekly-review") }
            FormField("For") { Picker(listOf(ConfigScope.Agent, ConfigScope.Shared), scope, { if (it == ConfigScope.Shared) "Every agent" else "This agent" }, { scope = it }) }
        }
        FormField("SKILL.md", "Frontmatter with the same name and a description of when to use it, then the instructions.") {
            TextArea(content, lines = 14, mono = true)
        }
    }
}

private const val TEMPLATE = "---\nname: my-skill\ndescription: What it does and when to use it.\n---\n\nInstructions for the agent.\n"

private fun SkillInfo.where() = when (scope) {
    ConfigScope.Agent -> "This agent's skills"
    ConfigScope.Shared -> "Shared with every agent"
    ConfigScope.External -> "An extra skill folder (read-only)"
}

private fun SkillInfo.invocation() = listOfNotNull("the agent".takeIf { modelInvocable }, "you, with /$name".takeIf { userInvocable })
    .joinToString(" and ").ifEmpty { "no one" }
