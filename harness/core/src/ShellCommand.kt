package kinuvo.harness.core

import kinuvo.harness.core.tools.Action
import kinuvo.harness.core.tools.Assessment
import kinuvo.protocol.Risk

/**
 * A bash command split into its simple commands at `&& || ; | &` and newlines, with quotes resolved. Anything it
 * can't follow (command substitution, subshells, heredocs, control flow, `eval`) leaves it [unparseable], which is
 * High risk and never trusted.
 */
class ShellCommand private constructor(val text: String, val parts: List<Part>, val unparseable: String?) {
    /**
     * One simple command: its [words] without leading `VAR=` [assignments], and where it redirects output to.
     * [expanded] when a word holds a variable or brace expansion other than `$HOME`, which is read as `~`.
     */
    data class Part(
        val words: List<String>,
        val assignments: Boolean = false,
        val outputs: List<String> = emptyList(),
        val inputs: List<String> = emptyList(),
        val expanded: Boolean = false,
    )

    /** The command's risk and what Trust rules would match, given the Agent's workspace [roots]. */
    fun assess(roots: List<String>): Assessment {
        if (unparseable != null) return Assessment(listOf(Action(Risk.High, listOf(text), "Can't be checked: $unparseable", trustable = false)))
        if (parts.isEmpty()) return Assessment(listOf(Action(Risk.Low, emptyList())))
        return Assessment(parts.map { ShellRisk.classify(it, roots) })
    }

    /** Why a built-in rule refuses the command outright, e.g. deleting the home folder. */
    fun blocked(): String? {
        val lines = listOf(text) + parts.map { (it.words + it.outputs.flatMap { o -> listOf(">", o) }).joinToString(" ") }
        return BLOCKED.firstOrNull { (re, _) -> lines.any(re::containsMatchIn) }?.second
    }

    /** Whether a Deny rule glob matches one of the commands, with or without wrappers like `env`, or the whole line. */
    fun matches(glob: String): Boolean {
        val re = globRegex(glob)
        if (re.matches(text.trim())) return true
        if (unparseable != null) return Regex("""(^|[\s;&|(`{])""" + re.pattern).containsMatchIn(text)
        return parts.any { p -> re.matches(p.words.joinToString(" ")) || ShellRisk.unwrap(p.words)?.let { re.matches(it.joinToString(" ")) } == true }
    }

    companion object {
        private val BLOCKED = listOf(
            Regex("""\brm\s+(-\S+\s+)*(/|/\*|~/?|~/\*|\${'$'}HOME/?|\${'$'}\{HOME\}/?)(\s|$|[;&|])""") to "deletes the root or home folder",
            Regex(""":\(\)\s*\{.*:\s*\|\s*:.*\}""") to "is a fork bomb",
            Regex("""\bmkfs(\.\w+)?\b""") to "formats a disk",
            Regex("""\bdd\b.*\bof=/dev/""") to "writes to a device",
            Regex(""">\s*/dev/(sd|disk|nvme|hd)""") to "writes to a device",
            Regex("""\b(curl|wget)\b[^|;&]*\|\s*(sudo\s+)?(ba|z|da)?sh\b""") to "runs a downloaded script",
        )

        fun globRegex(glob: String) = Regex(glob.trim().split('*').joinToString(".*") { seg -> seg.split('?').joinToString(".") { Regex.escape(it) } })

        /** [words] as one line that [words] reads back the same, quoting where needed. */
        fun quote(words: List<String>) = words.joinToString(" ") { w ->
            if (w.isNotEmpty() && w.all { it.isLetterOrDigit() || it in "_./=:@%+,-" }) w else "'" + w.replace("'", "'\\''") + "'"
        }

        /** The words of a saved Trust rule. */
        fun words(rule: String): List<String> =
            parse(rule).takeIf { it.unparseable == null }?.parts?.singleOrNull()?.words ?: rule.split(' ').filter(String::isNotEmpty)

        fun parse(command: String): ShellCommand {
            val parts = mutableListOf<Part>()
            var words = mutableListOf<String>()
            var outputs = mutableListOf<String>()
            var inputs = mutableListOf<String>()
            val expandedWords = mutableSetOf<Int>()
            var redirectExpanded = false
            val word = StringBuilder()
            var inWord = false
            var expanded = false
            var braced = false
            var redirect: String? = null
            var i = 0
            fun fail(why: String) = ShellCommand(command, emptyList(), why)

            fun endWord() {
                if (!inWord) return
                val w = word.toString()
                val exp = expanded || (braced && (',' in w || ".." in w))
                when (redirect) {
                    null -> { if (exp) expandedWords += words.size; words += w }
                    ">" -> if (!(w.startsWith("&") && w.drop(1).let { it == "-" || it.all(Char::isDigit) })) { outputs += w.removePrefix("&"); redirectExpanded = redirectExpanded || exp }
                    "<" -> if (!w.startsWith("&")) { inputs += w; redirectExpanded = redirectExpanded || exp }
                }
                redirect = null
                word.clear()
                inWord = false
                expanded = false
                braced = false
            }
            fun endPart() {
                endWord()
                val assignments = words.takeWhile { ASSIGNMENT.matches(it) }
                val rest = words.drop(assignments.size)
                if (rest.isNotEmpty() || outputs.isNotEmpty()) {
                    parts += Part(rest, assignments.isNotEmpty(), outputs, inputs, redirectExpanded || expandedWords.any { it >= assignments.size })
                }
                words = mutableListOf(); outputs = mutableListOf(); inputs = mutableListOf()
                expandedWords.clear(); redirectExpanded = false
            }
            /** Reads the `$` at [at] and returns the index of its last character. */
            fun dollar(at: Int): Int {
                inWord = true
                val m = VARIABLE.matchAt(command, at)
                if (m == null) {
                    if (command.getOrNull(at + 1) in setOf('\'', '"', '{')) expanded = true
                    word.append('$')
                    return at
                }
                if ((m.groups[1] ?: m.groups[2])?.value == "HOME") word.append('~') else { word.append(m.value); expanded = true }
                return at + m.value.length - 1
            }

            while (i < command.length) {
                val c = command[i]
                val next = command.getOrNull(i + 1)
                when {
                    c == '\\' -> { if (next == '\n') i++ else if (next != null) { word.append(next); inWord = true; i++ } }
                    c == '\'' -> {
                        val end = command.indexOf('\'', i + 1).takeIf { it >= 0 } ?: return fail("unclosed quote")
                        word.append(command, i + 1, end); inWord = true; i = end
                    }
                    c == '"' -> {
                        inWord = true
                        i++
                        while (i < command.length && command[i] != '"') {
                            val d = command[i]
                            if (d == '`' || (d == '$' && command.getOrNull(i + 1) == '(')) return fail("command substitution")
                            when {
                                d == '\\' && command.getOrNull(i + 1) in setOf('"', '\\', '$', '`') -> { i++; word.append(command[i]) }
                                d == '$' -> i = dollar(i)
                                else -> word.append(d)
                            }
                            i++
                        }
                        if (i >= command.length) return fail("unclosed quote")
                    }
                    c == '`' || (c == '$' && next == '(') -> return fail("command substitution")
                    (c == '<' || c == '>') && next == '(' -> return fail("process substitution")
                    c == '<' && next == '<' -> return fail("heredoc")
                    c == '(' || c == ')' || c == '{' && (next == null || next.isWhitespace()) -> return fail("subshell or group")
                    c == '#' && !inWord -> while (i + 1 < command.length && command[i + 1] != '\n') i++
                    c == '$' -> i = dollar(i)
                    c == '>' || c == '<' || (c == '&' && next == '>') -> {
                        if (inWord && word.all { it.isDigit() }) { word.clear(); inWord = false } else endWord()
                        if (c == '&') i++
                        redirect = if (c == '<' && command.getOrNull(i + 1) != '>') "<" else ">"
                        if (command.getOrNull(i + 1) == '>' || command.getOrNull(i + 1) == '|') i++
                        if (command.getOrNull(i + 1) == '&') { word.append('&'); inWord = true; i++ }
                    }
                    c == ';' || c == '\n' || c == '|' || c == '&' -> {
                        endPart()
                        if ((c == '|' || c == '&') && (next == c || (c == '|' && next == '&'))) i++
                    }
                    c.isWhitespace() -> endWord()
                    else -> { if (c == '{') braced = true; word.append(c); inWord = true }
                }
                i++
            }
            if (redirect != null && !inWord) return fail("redirect without a target")
            endPart()
            parts.firstOrNull { it.words.firstOrNull() in KEYWORDS }?.let { return fail("shell control flow (${it.words.first()})") }
            parts.firstOrNull { it.words.firstOrNull() in EVAL }?.let { return fail("${it.words.first()} runs code it builds") }
            return ShellCommand(command, parts, null)
        }

        private val ASSIGNMENT = Regex("[A-Za-z_][A-Za-z0-9_]*=.*")
        private val VARIABLE = Regex("""\$(?:([A-Za-z_][A-Za-z0-9_]*)|\{([^}]*)\}|[0-9@*#?$!-])""")
        internal val EVAL = setOf("eval", "source", ".")
        private val KEYWORDS = setOf("if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac", "select", "function", "coproc", "[[")
    }
}

/** Deterministic risk of one simple command: read-only programs with known flags are Low, destructive ones High. */
internal object ShellRisk {
    fun classify(part: ShellCommand.Part, roots: List<String>): Action {
        val words = unwrap(part.words) ?: return Action(Risk.High, part.words, "Wrapper options can't be checked", trustable = false)
        val program = words.firstOrNull() ?: return Action(Risk.Medium, part.words, "Only redirects output", trustable = false)
        val name = program.substringAfterLast('/')
        fun untrusted(reason: String) = Action(Risk.High, words, reason, trustable = false)
        if (part.expanded) return untrusted("Uses variables or expansions, which can't be checked")
        if (name in ShellCommand.EVAL) return untrusted("$name runs code it builds")
        if (name == "git" && gitSubcommand(words) == null) return untrusted("git's global options can change execution or can't be checked")
        if (name in SHELLS || name in INTERPRETERS) inline(words, name)?.let { return untrusted(it) }
        if (name in AWK && words.drop(1).any { "system" in it || "getline" in it || '|' in it }) return untrusted("awk runs commands")

        val assigned = part.assignments || (part.words.first().substringAfterLast('/') == "env" && part.words.size > 1)
        val writes = part.outputs.any { it != "/dev/null" }
        val outside = (words.drop(1) + part.inputs).any { outside(it, roots) }
        val floor = if (assigned || writes || outside) Risk.Medium else Risk.Low
        high(words, name)?.let { return Action(Risk.High, words, it, exact = true, floor = floor) }
        val xargs = part.words.take(part.words.size - words.size).any { it.substringAfterLast('/') == "xargs" }
        val low = '/' !in program && low(words, name)
        val risk = if (low && !xargs) floor else Risk.Medium
        val reason = when {
            risk == Risk.Low -> null
            !low -> if (name in KNOWN_CHANGES) "$name can change things" else "$name isn't known to be read-only"
            xargs -> "xargs passes it unknown arguments"
            assigned -> "Sets environment variables"
            writes -> "Writes to ${part.outputs.first { it != "/dev/null" }}"
            else -> "Reads outside the workspace"
        }
        return Action(risk, words, reason, key = key(words), floor = floor)
    }

    /** What Trust rules are saved as: the program, plus the subcommand or script for tools that have them. */
    private fun key(words: List<String>): List<String> {
        val program = words.first()
        val name = program.substringAfterLast('/')
        if (name == "git") return listOfNotNull(program, gitSubcommand(words)?.first)
        val sub = words.drop(1).firstOrNull { !it.startsWith("-") }
        val keyed = program in SUBCOMMANDS || program.startsWith("./") || name in SHELLS || name in INTERPRETERS
        return if (sub != null && keyed) listOf(program, sub) else listOf(program)
    }

    /** Drops wrappers that run the rest, e.g. `time make`, `timeout 5 ls` or `env X=1 make`; null when one hides it. */
    fun unwrap(words: List<String>): List<String>? {
        val rest = words.drop(1)
        return when (words.firstOrNull()?.substringAfterLast('/')) {
            "time" -> {
                val command = rest.dropWhile { it in setOf("-p", "--portability", "-v", "--verbose", "--quiet") }
                when {
                    command.firstOrNull() == "--" -> unwrap(command.drop(1))
                    command.firstOrNull()?.startsWith("-") == true -> null
                    else -> unwrap(command)
                }
            }
            "nohup", "builtin", "!" -> unwrap(options(rest, emptySet()))
            "nice" -> unwrap(options(rest, setOf("-n", "--adjustment")))
            "exec" -> unwrap(options(rest, setOf("-a")))
            "timeout" -> unwrap(options(rest, setOf("-s", "--signal", "-k", "--kill-after")).drop(1))
            "env" -> when {
                rest.isEmpty() -> words
                rest.takeWhile { it.startsWith("-") }.any { it.startsWith("--split") || (!it.startsWith("--") && 'S' in it) } -> null
                else -> unwrap(options(rest, setOf("-u", "--unset", "-C", "--chdir")).dropWhile { '=' in it }.ifEmpty { listOf("env") })
            }
            "command" -> if (rest.firstOrNull() == "-v" || rest.firstOrNull() == "-V") listOf("which") + rest.drop(1) else unwrap(options(rest, emptySet()))
            "xargs" -> options(rest, XARGS_VALUES).let { if (it.isEmpty()) listOf("echo") else unwrap(it) }
            else -> words
        }
    }

    /** [args] after the leading options, skipping the values of those in [withValue]. */
    private fun options(args: List<String>, withValue: Set<String>): List<String> {
        var i = 0
        while (i < args.size && args[i].startsWith("-") && args[i] != "-") {
            if (args[i] == "--") return args.drop(i + 1)
            if (args[i] in withValue) i++
            i++
        }
        return args.drop(i)
    }

    /** Whether [a] is a short option bundle like `-vf` holding any of [letters]. */
    private fun short(a: String, letters: String) = a.startsWith("-") && !a.startsWith("--") && a.drop(1).any { it in letters }

    private fun inline(words: List<String>, name: String): String? {
        val args = words.drop(1)
        if (args.isNotEmpty() && args.all { it == "--version" || it == "--help" }) return null
        val letters = if (name in SHELLS) "cs" else "ceEprx"
        return when {
            args.any { a -> a == "-" || a.startsWith("--eval") || a.startsWith("--print") || a.startsWith("--command") || short(a, letters) } -> "$name runs inline code"
            name == "deno" && args.firstOrNull() in setOf("eval", "repl") -> "deno runs inline code"
            args.none { !it.startsWith("-") } -> "$name runs code from its input"
            else -> null
        }
    }

    private fun high(words: List<String>, name: String): String? {
        val args = words.drop(1)
        return when {
            name in DESTRUCTIVE -> DESTRUCTIVE[name]
            name == "chmod" && args.any { it == "--recursive" || short(it, "R") } -> "chmod -R changes permissions recursively"
            name == "find" && args.any { it == "-delete" || it.startsWith("-exec") || it.startsWith("-ok") } -> "find runs commands or deletes files"
            name == "fd" && args.any { it.startsWith("--exec") || short(it, "xX") } -> "fd runs commands"
            name == "rg" && args.any { it.startsWith("--pre") } -> "rg --pre runs commands"
            name == "sort" && args.any { it.startsWith("--compress") } -> "sort --compress-program runs commands"
            name == "git" -> gitSubcommand(words)?.let { (sub, rest) ->
                fun has(long: String, letters: String) = rest.any { it == long || short(it, letters) }
                when {
                    sub == "push" && (has("--delete", "fd") || rest.any { it.startsWith("--force") || it == "--mirror" || it == "--prune" || it.startsWith("+") || it.startsWith(":") }) ->
                        "Rewrites or deletes remote history"
                    sub == "reset" && "--hard" in rest -> "Discards uncommitted changes"
                    sub == "clean" -> "Deletes untracked files"
                    sub == "branch" && (rest.any { short(it, "D") } || has("--delete", "d") && has("--force", "f")) -> "Deletes a branch"
                    sub == "stash" && rest.firstOrNull() in setOf("drop", "clear") -> "Deletes stashed changes"
                    sub == "checkout" && ("--" in rest || "." in rest || has("--force", "f")) || sub == "restore" -> "Discards changes to files"
                    sub == "switch" && (has("--force", "f") || "--discard-changes" in rest) -> "Discards changes to files"
                    sub == "filter-branch" -> "Rewrites history"
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun low(words: List<String>, name: String): Boolean {
        val args = words.drop(1)
        if (args.isNotEmpty() && args.all { it == "--version" || it == "--help" }) return true
        if (name == "git") return gitSubcommand(words)?.let { (sub, rest) -> gitReadOnly(sub, rest) } == true
        if (name !in READ_ONLY) return false
        if (args.isNotEmpty() && name in NO_ARGS) return false
        val unsafe = UNSAFE_FLAGS[name].orEmpty()
        return args.none { a -> unsafe.any { u -> a == u || abbreviates(a, u) || (u.length == 2 && short(a, u.substring(1))) } }
    }

    /** Whether [a] is [long] or an abbreviation of it, which getopt accepts, e.g. `--out=x` for `--output`. */
    private fun abbreviates(a: String, long: String) = long.startsWith("--") && a.substringBefore('=').let { it.length > 3 && long.startsWith(it) }

    private fun gitReadOnly(sub: String, rest: List<String>): Boolean = when (sub) {
        "status", "log", "diff", "show", "blame", "rev-parse", "ls-files", "describe", "shortlog", "show-ref", "cat-file", "merge-base", "grep", "whatchanged" ->
            rest.none { abbreviates(it, "--output") || abbreviates(it, "--ext-diff") || it.startsWith("-O") || it.startsWith("--open-files") }
        "branch" -> rest.all { it in setOf("-a", "-r", "-v", "-vv", "--all", "--remotes", "--list", "--show-current", "-l") }
        "remote" -> rest.all { it == "-v" || it == "--verbose" }
        "tag" -> rest.all { it == "-l" || it == "--list" }
        "stash" -> rest.firstOrNull() == "list" || rest.firstOrNull() == "show"
        "config" -> rest.any { it == "--get" || it == "--list" || it == "-l" || it == "--get-all" }
        "reflog" -> rest.isEmpty() || rest.first() == "show"
        else -> false
    }

    /** The subcommand after known global options; null if configuration or an unknown option can change execution. */
    private fun gitSubcommand(words: List<String>): Pair<String, List<String>>? {
        var i = 1
        while (i < words.size && words[i].startsWith("-")) {
            val option = words[i]
            when {
                option in setOf("--version", "--help", "-v", "-h") -> return option to words.drop(i + 1)
                option in setOf("-C", "--git-dir", "--work-tree", "--namespace") -> {
                    if (words.getOrNull(++i) == null) return null
                }
                option.startsWith("-C") || option.startsWith("--git-dir=") || option.startsWith("--work-tree=") || option.startsWith("--namespace=") -> Unit
                option in setOf("-P", "--no-pager", "--bare", "--no-optional-locks", "--no-replace-objects",
                    "--literal-pathspecs", "--glob-pathspecs", "--noglob-pathspecs", "--icase-pathspecs", "--no-lazy-fetch", "--no-advice") -> Unit
                else -> return null
            }
            i++
        }
        return words.getOrNull(i)?.let { it to words.drop(i + 1) }
    }

    /** A path argument that leaves the workspace: absolute outside [roots], under `~`, or climbing with `..`. */
    private fun outside(arg: String, roots: List<String>): Boolean {
        val path = if (arg.startsWith("-") && '=' in arg) arg.substringAfter('=') else arg
        return when {
            path == "/dev/null" -> false
            path.startsWith("~") -> true
            path.startsWith("/") -> roots.none { path == it || path.startsWith("$it/") } || ".." in path.split('/')
            else -> ".." in path.split('/')
        }
    }

    private val SHELLS = setOf("bash", "sh", "zsh", "dash", "fish", "ksh")
    private val INTERPRETERS = setOf("python", "python3", "node", "ruby", "perl", "php", "deno", "bun", "osascript", "lua", "Rscript")
    private val AWK = setOf("awk", "gawk", "mawk", "nawk")
    private val XARGS_VALUES = setOf(
        "-n", "-L", "-P", "-s", "-d", "-E", "-I", "-a", "--max-args", "--max-lines", "--max-procs", "--max-chars", "--delimiter",
        "--arg-file", "--process-slot-var",
    )
    private val READ_ONLY = setOf(
        "ls", "cat", "head", "tail", "wc", "grep", "egrep", "fgrep", "rg", "tree", "du", "df", "pwd", "echo", "printf", "which",
        "whereis", "type", "file", "stat", "diff", "cmp", "uniq", "cut", "tr", "date", "whoami", "hostname", "uname", "basename",
        "dirname", "realpath", "readlink", "true", "false", "test", "[", "nl", "column", "jq", "sort", "find", "fd", "ps", "uptime",
        "id", "groups", "md5", "md5sum", "shasum", "sha256sum", "sleep", "seq", "env", "printenv",
    )
    private val NO_ARGS = setOf("hostname", "env")
    private val UNSAFE_FLAGS = mapOf(
        "sort" to listOf("-o", "--output"),
        "date" to listOf("-s", "--set"),
        "tree" to listOf("-o"),
        "find" to listOf("-fprint", "-fprint0", "-fprintf", "-fls"),
    )
    private val DESTRUCTIVE = mapOf(
        "rm" to "rm deletes files", "rmdir" to "rmdir deletes folders", "shred" to "shred destroys files", "sudo" to "sudo runs as root",
        "su" to "su switches user", "doas" to "doas runs as root", "dd" to "dd writes raw data", "kill" to "kill stops processes",
        "pkill" to "pkill stops processes", "killall" to "killall stops processes", "shutdown" to "shutdown turns the machine off",
        "reboot" to "reboot restarts the machine", "halt" to "halt stops the machine", "launchctl" to "launchctl changes system services",
        "systemctl" to "systemctl changes system services", "diskutil" to "diskutil changes disks", "chown" to "chown changes file owners",
        "iptables" to "iptables changes the firewall", "crontab" to "crontab changes scheduled jobs", "truncate" to "truncate empties files",
    )
    private val KNOWN_CHANGES = setOf("sed", "awk", "chmod", "curl", "wget", "mv", "cp", "mkdir", "touch", "ln", "tee", "git", "npm", "pip", "brew")
    private val SUBCOMMANDS = setOf(
        "docker", "podman", "npm", "pnpm", "yarn", "bun", "gh", "brew", "kubectl", "cargo", "go", "pip", "pip3", "uv", "deno",
        "kotlin", "gradle", "swift", "xcrun", "make", "just", "terraform", "aws", "gcloud", "fly", "remindctl", "kinuvo",
    )
}
