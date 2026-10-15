package kinuvo.harness.core

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.utils.io.readBuffer
import kinuvo.harness.core.store.createDirectories
import kinuvo.harness.core.store.createPrivateFile
import kinuvo.harness.core.store.isFile
import kinuvo.harness.core.store.isInside
import kinuvo.protocol.*
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlin.uuid.Uuid

interface PluginHost {
    val instance: PluginInstanceId
    suspend fun http(request: PluginHttpRequest): PluginHttpResponse
    suspend fun readFile(root: String, path: String): ByteArray
    suspend fun writeFile(root: String, path: String, bytes: ByteArray)
    /** Reads a host-bound value or reference by name, never an expression supplied by the plugin. */
    suspend fun secret(name: String): String
}

/** Host-only bindings; these may contain credentials and must never be sent as a manifest. */
data class PluginGrants(
    val instance: PluginInstanceId,
    val network: Map<String, String> = emptyMap(),
    val files: Map<String, PluginFileGrant> = emptyMap(),
    val secrets: Map<String, String> = emptyMap(),
)

data class PluginFileGrant(val root: Path, val access: PluginFileAccess = PluginFileAccess.Read)

class PluginAccessDenied(instance: PluginInstanceId, resource: String) : IllegalStateException("${instance.pluginId}/${instance.instanceId} has no access to $resource")

/** Enforces grants on host calls. Native code outside this interface still has the process's access. */
class PluginHosts(http: HttpClient, private val expand: suspend (PluginInstanceId, String) -> String = { _, value -> value }) {
    private val client = http.config {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    }

    fun bind(manifest: PluginManifest, instance: PluginInstanceId, grants: () -> PluginGrants = { PluginGrants(instance) }): PluginHost {
        manifest.checkSupported()
        require(manifest.plugin.id == instance.pluginId) { "Manifest belongs to another plugin" }
        return object : PluginHost {
            override val instance = instance

            private fun current(): PluginGrants = grants().also {
                if (it.instance != instance) throw PluginAccessDenied(instance, "another instance's grants")
            }

            override suspend fun http(request: PluginHttpRequest): PluginHttpResponse {
                val base = current().network[request.network]?.takeIf { request.network in manifest.access.network }
                    ?: throw PluginAccessDenied(instance, "network ${request.network}")
                val url = Url(httpServiceUrl(expand(instance, base)))
                require(url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.fragment.isEmpty()) { "Network endpoints cannot contain credentials or fragments" }
                require(request.path.none { it == "." || it == ".." }) { "Use relative path segments" }
                val method = request.method.uppercase()
                require(method in METHODS) { "Unsupported HTTP method" }
                require(request.headers.all { (key, value) ->
                    key.lowercase() !in FORBIDDEN_HEADERS && !key.startsWith("proxy-", true) &&
                        key.isNotBlank() && key.none { it.isISOControl() } && value.none { it == '\r' || it == '\n' }
                }) { "Unsupported HTTP header" }
                require((request.body?.encodeToByteArray()?.size ?: 0) <= MAX_BYTES) { "Plugin request is too large" }
                val target = URLBuilder(url).apply {
                    if (request.path.isNotEmpty()) appendPathSegments(request.path, encodeSlash = true)
                    request.query.forEach { (key, value) -> parameters.append(key, value) }
                }.build()
                if (current().network[request.network] != base) throw PluginAccessDenied(instance, "network ${request.network}")
                return client.prepareRequest(target) {
                    this.method = HttpMethod(method)
                    request.headers.forEach { (key, value) -> headers.append(key, value) }
                    request.body?.let { setBody(it) }
                }.execute { response ->
                    val channel = response.bodyAsChannel()
                    val bytes = try { channel.readBuffer(MAX_BYTES.toLong() + 1).readByteArray() } finally { channel.cancel(null) }
                    check(bytes.size <= MAX_BYTES) { "Plugin response is too large" }
                    PluginHttpResponse(response.status.value, response.headers.entries().associate { it.key to it.value }, bytes.decodeToString())
                }
            }

            private fun file(root: String, path: String, write: Boolean): Path {
                val grant = current().files[root] ?: throw PluginAccessDenied(instance, "file root $root")
                val declared = manifest.access.files[root] ?: throw PluginAccessDenied(instance, "file root $root")
                if (write && (grant.access != PluginFileAccess.ReadWrite || declared != PluginFileAccess.ReadWrite))
                    throw PluginAccessDenied(instance, "writing file root $root")
                require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && ':' !in path && '\u0000' !in path &&
                    path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Use a relative file path inside its root" }
                val target = Path(grant.root, path)
                if (!isInside(grant.root, target)) throw PluginAccessDenied(instance, "a path outside file root $root")
                return target
            }

            override suspend fun readFile(root: String, path: String): ByteArray {
                val target = file(root, path, false)
                require(target.isFile()) { "Not a regular file" }
                return SystemFileSystem.source(target).buffered().use { source ->
                    val bytes = ByteArray(MAX_BYTES + 1)
                    var size = 0
                    while (size < bytes.size) {
                        val count = source.readAtMostTo(bytes, size, bytes.size)
                        if (count == -1) break
                        size += count
                    }
                    check(size <= MAX_BYTES) { "Plugin file is too large" }
                    bytes.copyOf(size)
                }
            }

            override suspend fun writeFile(root: String, path: String, bytes: ByteArray) {
                require(bytes.size <= MAX_BYTES) { "Plugin file is too large" }
                val target = file(root, path, true)
                val parent = requireNotNull(target.parent)
                parent.createDirectories()
                file(root, path, true)
                val temp = Path(parent, ".plugin-${Uuid.random()}.tmp")
                try {
                    createPrivateFile(temp)
                    SystemFileSystem.sink(temp).buffered().use { it.write(bytes) }
                    SystemFileSystem.atomicMove(temp, target)
                } finally { SystemFileSystem.delete(temp, mustExist = false) }
            }

            override suspend fun secret(name: String): String {
                val value = current().secrets[name]?.takeIf { name in manifest.access.secrets }
                    ?: throw PluginAccessDenied(instance, "secret $name")
                val resolved = expand(instance, value)
                if (current().secrets[name] != value) throw PluginAccessDenied(instance, "secret $name")
                return resolved
            }
        }
    }

    fun close() = client.close()

    companion object {
        const val MAX_BYTES = 10 * 1024 * 1024
        private val METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        private val FORBIDDEN_HEADERS = setOf("host", "connection", "content-length", "transfer-encoding", "upgrade")
    }
}
