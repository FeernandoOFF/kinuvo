package kinuvo.plugins.mcp

import kinuvo.server.plugin.Keychain
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Every sign-in as one entry in [keychain]; those [legacy] holds are moved into it when first read. */
internal class KeychainSignIns(private val keychain: Keychain, private val legacy: McpSignInStore) : McpSignInStore {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), McpSignIn.serializer())

    override suspend fun get(id: String): McpSignIn? = mutex.withLock {
        checkSignInId(id.split('/'))
        load()[id] ?: legacy.get(id)?.also { save(load() + (id to it)); legacy.remove(id) }
    }

    override suspend fun put(id: String, signIn: McpSignIn): Unit = mutex.withLock {
        checkSignInId(id.split('/'))
        save(load() + (id to signIn))
        legacy.remove(id)
    }

    override suspend fun remove(id: String): Unit = mutex.withLock {
        checkSignInId(id.split('/'))
        save(load() - id)
        legacy.remove(id)
    }

    override suspend fun removeAll(prefix: String): Unit = mutex.withLock {
        checkSignInId(prefix.split('/'))
        save(load().filterKeys { it != prefix && !it.startsWith("$prefix/") })
        legacy.removeAll(prefix)
    }

    private suspend fun load(): Map<String, McpSignIn> =
        keychain.read()?.let { json.decodeFromString(serializer, it) }.orEmpty()

    private suspend fun save(signIns: Map<String, McpSignIn>) =
        if (signIns.isEmpty()) keychain.delete() else keychain.write(json.encodeToString(serializer, signIns))
}
