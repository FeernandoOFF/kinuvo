package kinuvo.ui.connection

import kinuvo.app.connection.*
import kinuvo.app.session.*
import kinuvo.ui.agent.*
import kinuvo.ui.routine.*
import kinuvo.ui.navigation.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.CircleCheck
import com.composables.icons.lucide.CircleX
import com.composables.icons.lucide.RotateCcw
import com.composables.icons.lucide.PlugZap
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Lucide
import com.composables.ui.components.*
import com.composables.ui.theme.mutedColor
import kinuvo.protocol.*
import kinuvo.ui.design.*

/** Where Tasks come from, which lists the Board shows and each list's Agent. Saved to config.yaml. */
@Composable
internal fun TasksSettingsPane(p: Pane) {
    val setup = p.ui.state.tasks ?: return ServerLoading(p, "Tasks")
    key(setup) { TasksForm(setup, p) }
}

/** How a list appears on the Board: hidden, shown without an Agent, or assigned to one. */
private sealed interface ListUse {
    data object Hidden : ListUse
    data object Unassigned : ListUse
    data class Agent(val agent: AgentInfo) : ListUse
}

@Composable
private fun TasksForm(setup: TasksSetup, p: Pane) {
    val s = setup.settings
    var provider by remember { mutableStateOf(s.provider) }
    LaunchedEffect(provider) { p.actions.onLoadLists(provider) }
    val lists = p.ui.state.providerLists[provider]
    // An empty map shows every list, so start them all as shown.
    var uses by remember(provider, lists) {
        mutableStateOf(lists.orEmpty().associate { l ->
            val agent = if (provider == s.provider) s.lists[l.name] ?: s.lists[l.id] else null
            l.name to when {
                provider == s.provider && s.lists.isEmpty() -> ListUse.Unassigned
                agent == null -> if (provider == s.provider) ListUse.Hidden else ListUse.Unassigned
                agent.isEmpty() -> ListUse.Unassigned
                else -> p.ui.agents.firstOrNull { it.id == agent }?.let { ListUse.Agent(it) } ?: ListUse.Unassigned
            }
        })
    }
    var defaultList by remember(provider) { mutableStateOf(s.defaultList.takeIf { provider == s.provider }) }
    val startBefore = rememberTextFieldState(s.startBeforeDays.toString())
    val poll = rememberTextFieldState(s.pollMinutes.toString())
    val options = listOf(ListUse.Hidden, ListUse.Unassigned) + p.ui.agents.map { ListUse.Agent(it) }
    val shown = lists.orEmpty().filter { uses[it.name] != ListUse.Hidden }
    val ready = lists != null && startBefore.value().toIntOrNull() != null && poll.value().toIntOrNull() != null
    val save = {
        p.actions.onSaveTasks(TasksSettings(
            provider = provider,
            lists = uses.mapNotNull { (name, use) ->
                when (use) {
                    ListUse.Hidden -> null
                    ListUse.Unassigned -> name to ""
                    is ListUse.Agent -> name to use.agent.id
                }
            }.toMap(),
            defaultList = defaultList,
            startBeforeDays = startBefore.value().toInt(),
            pollMinutes = poll.value().toInt(),
        ))
    }
    val info = "The Board shows your tasks from the app you pick. Agents start on a task assigned to them when it's due within " +
        "the days below, or right away when it has no date. Write @agent in a task to assign it."
    DetailPane(p.back, "Tasks", error = p.ui.error, info = info, trailing = { FormActions("Save", ready, p.ui.state.saved, onSave = save) }) {
        val reminders = "On the Server, install with brew install steipete/tap/remindctl. Request access below; macOS may ask you to allow the app that launched the Server."
        FormField("Task app", reminders.takeIf { provider == "reminders" }) { Picker(setup.providers, provider, ::providerName, { provider = it }) }
        if (provider == "reminders") {
            ActionButton(Lucide.CircleCheck, if (p.ui.state.testing) "Checking access…" else "Request access / Retry", { p.actions.onAuthorizeTasks(provider) }, enabled = !p.ui.state.testing)
        }
        DetailGroup("Lists") {
            when {
                lists == null && p.ui.error == null -> Spinner()
                lists == null -> Text("Couldn’t load lists. Request access or retry.", color = color(mutedColor))
                lists.isEmpty() -> Text("No lists found.", color = color(mutedColor))
                else -> lists.forEach { list ->
                    DetailRow(list.name) {
                        Picker(options, uses[list.name] ?: ListUse.Unassigned, ::useLabel, { uses = uses + (list.name to it) })
                    }
                }
            }
        }
        DetailGroup("Defaults") {
            DetailRow("New tasks go to") {
                Picker(listOf<String?>(null) + shown.map { it.name }, defaultList?.takeIf { d -> shown.any { it.name == d } }, { it ?: "The first list" }, { defaultList = it })
            }
            DetailRow("Start before due") { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.width(80.dp)) { Input(startBefore, "3") }; Text("  days") } }
            DetailRow("Check for tasks") { Row(verticalAlignment = Alignment.CenterVertically) { Text("every  "); Box(Modifier.width(80.dp)) { Input(poll, "5") }; Text("  minutes") } }
        }
    }
}

private fun providerName(id: String) = when (id) {
    "local" -> "Kinuvo (built in)"
    "reminders" -> "Apple Reminders"
    else -> id
}

private fun useLabel(use: ListUse) = when (use) {
    ListUse.Hidden -> "Not on the Board"
    ListUse.Unassigned -> "On the Board, no agent"
    is ListUse.Agent -> use.agent.name
}

/** Where Knowledge is kept and whether Agents update it after conversations. Saved to config.yaml. */
@Composable
internal fun KnowledgeSettingsPane(p: Pane) {
    val setup = p.ui.state.knowledge ?: return ServerLoading(p, "Knowledge")
    key(setup) {
        val path = rememberTextFieldState(setup.settings.path.orEmpty())
        val reflectionPrompt = rememberTextFieldState(setup.settings.reflectionPrompt ?: DEFAULT_REFLECTION_PROMPT)
        var reflection by remember { mutableStateOf(setup.settings.reflection) }
        var provider by remember { mutableStateOf(setup.settings.provider) }
        val url = rememberTextFieldState(setup.settings.options["url"] ?: "http://127.0.0.1:31009")
        val apiKey = rememberTextFieldState(setup.settings.options["apiKey"].orEmpty())
        val space = rememberTextFieldState(setup.settings.options["space"].orEmpty())
        // Conventions are per provider; switching keeps what was typed for the other one.
        val drafts = remember { setup.settings.conventions.mapValues { TextFieldState(it.value) }.toMutableMap() }
        val conventions = drafts.getOrPut(provider) { TextFieldState() }
        fun current(): KnowledgeSettings {
            val options = if (provider == "anytype") mapOf("url" to url.value(), "apiKey" to apiKey.value(), "space" to space.value()) else setup.settings.options
            return KnowledgeSettings(path.value().ifEmpty { null }, reflection, provider, options, drafts.mapValues { it.value.text.toString() }, reflectionPrompt.value().takeIf { it != DEFAULT_REFLECTION_PROMPT })
        }
        val info = "Knowledge shared by every Agent: people, projects and places. " +
            "Agents with the Knowledge tool read and update it; you can undo any change from the Knowledge screen."
        DetailPane(p.back, "Knowledge", error = p.ui.error, info = info, trailing = { FormActions("Save", true, p.ui.state.saved, onSave = { p.actions.onSaveKnowledge(current()) }) }) {
            val anytype = "Keep Anytype running on the Server with its API enabled. Create an API key in Anytype's settings and give the space ID. " +
                "Agents use your own types and properties, and add or link text in your notes block by block."
            FormField("Knowledge app", anytype.takeIf { provider == "anytype" }) {
                Picker(setup.providers, provider, { if (it == "anytype") "Anytype" else "Markdown / Obsidian" }, { provider = it })
            }
            if (provider == "anytype") {
                FormField("API URL") { Input(url, "http://127.0.0.1:31009") }
                FormField("API key") {
                    SecretField(p, { apiKey.setTextAndPlaceCursorAtEnd(it) }) { trailing ->
                        Input(apiKey, "API key", secret = !isReference(apiKey.value()), trailing = trailing)
                    }
                }
                FormField("Space ID") { Input(space, "Space ID") }
            } else {
                FormField("Folder", "Blank for the default. To use Obsidian, point it at a folder in your vault, e.g. ~/Vault/Kinuvo.") {
                    Input(path, "~/.kinuvo/knowledge")
                }
                // Only on this device's own Server: a picked folder is a path on this device.
                val pickFolder = rememberFolderPicker { path.setTextAndPlaceCursorAtEnd(it) }.takeIf { p.ui.server == Server.Embedded }
                pickFolder?.let { ActionButton(Lucide.FolderOpen, "Choose folder or Obsidian vault", it) }
                Text("Now: ${setup.folder}", color = color(mutedColor), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            FormField(
                "Conventions",
                "How you organize your notes, in your words: which types hold people or companies, where facts go, how you link. " +
                    "Agents follow them when reading, adding and learning from conversations.",
            ) {
                TextArea(
                    conventions,
                    if (provider == "anytype") "People are Human (profile) objects; companies are Organization.\nAdd dated facts as bullets under a Timeline heading.\nLink people to their company in the text."
                    else "People are person notes in People/.\nAdd dated facts as bullets under ## Timeline.\nLink people to their company with [[Company]].",
                    lines = 5,
                )
            }
            Checkbox(reflection, { reflection = it }) {
                Column {
                    Text("Learn from conversations")
                    Text("After a chat goes quiet, its Agent curates Memory and User Notes, and proposes Knowledge changes and Skills for your review.", color = color(mutedColor), fontSize = 12.sp)
                }
            }
            FormField("Reflection prompt", "What Agents should remember and learn. Shared by every Agent on this Server. Knowledge changes and Skill drafts always need your Approval. Conventions and current context are added automatically. Blank uses the default.") {
                TextArea(reflectionPrompt, "Reflection instructions", lines = 10)
            }
            ActionButton(Lucide.RotateCcw, "Reset prompt to default", { reflectionPrompt.setTextAndPlaceCursorAtEnd(DEFAULT_REFLECTION_PROMPT) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionButton(Lucide.PlugZap, "Test", { p.actions.onTestKnowledge(current()) }, enabled = !p.ui.state.testing)
                if (p.ui.state.testing) Spinner()
            }
            p.ui.state.knowledgeTest?.let { t ->
                if (t.error != null) Pill("Couldn't read it: ${t.error}", tone = Tone.Danger, icon = Lucide.CircleX)
                else Pill("Works: ${t.summary}", tone = Tone.Success, icon = Lucide.CircleCheck)
            }
        }
    }
}

/** A spinner while server-wide settings load; if loading failed, the error and a way to retry. */
@Composable
internal fun ServerLoading(p: Pane, title: String) {
    val error = p.ui.error ?: return PaneLoading()
    DetailPane(p.back, title) {
        Pill(error, tone = Tone.Danger, icon = Lucide.CircleX)
        Button(onClick = p.actions.onReloadServer, style = ButtonStyle.Outlined) { Text("Retry") }
    }
}
