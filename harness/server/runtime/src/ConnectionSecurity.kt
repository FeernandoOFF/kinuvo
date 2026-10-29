package kinuvo.harness.server

import kinuvo.protocol.Api

data class ConnectionSecurity(
    val authenticationTimeoutMillis: Long = 10_000,
    val validationIntervalMillis: Long = 30_000,
    val concurrentRequests: Int = 8,
    val messagesPerMinute: Int = 600,
)

internal val DEVICE_METHODS = setOf(
    Api.ExecutionSnapshot.name, Api.ExecutionAnswer.name, Api.ResumeExecution.name, Api.CloseExecution.name,
    Api.Capabilities.name, Api.Models.name, Api.Agents.name, Api.Agent.name, Api.Sessions.name, Api.Session.name,
    Api.Skills.name, Api.Skill.name, Api.CatchUp.name, Api.Unsubscribe.name, Api.History.name, Api.CreateSession.name, Api.UpdateSession.name,
    Api.UploadAttachment.name, Api.Attachment.name, Api.Send.name, Api.Stop.name, Api.Answer.name,
    Api.Artifacts.name, Api.Artifact.name, Api.KnowledgeSearch.name, Api.KnowledgeNote.name,
    Api.KnowledgeChanges.name, Api.TaskLists.name, Api.Tasks.name, Api.CreateTask.name, Api.UpdateTask.name,
    Api.Presence.name, Api.DeviceTools.name, Api.PairedDevices.name, Api.RevokeDevice.name, Api.RotateToken.name,
)
