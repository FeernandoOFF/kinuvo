package kinuvo.harness.core.store

import kinuvo.harness.core.parseDotEnv

/** Variables for `${VAR}` expansion: `<home>/.env`, then the Agent's `.env`, then the process environment (strongest). */
fun AgentFiles.loadEnv(agentId: String? = null, process: Map<String, String> = processEnv()): Map<String, String> =
    dotEnv(sharedEnv) + agentId?.let { dotEnv(env(it)) }.orEmpty() + process

private fun dotEnv(file: kotlinx.io.files.Path) = file.readTextOrNull()?.let(::parseDotEnv).orEmpty()
