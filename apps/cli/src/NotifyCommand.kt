package kinuvo.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import kinuvo.protocol.Api
import kinuvo.protocol.ServiceKind
import kinuvo.protocol.TestServiceParams

internal class NotifyCommand : ServerCommand("notify", "Send a test notification with the Server's notification settings (Settings → Notifications).") {
    private val message by argument("MESSAGE").optional()

    override suspend fun run() {
        val result = session.call { client.call(Api.TestService, TestServiceParams(ServiceKind.Notifications, input = message)) }
        echo(result.summary ?: throw CliktError(result.error ?: "Failed"))
    }
}
