package kinuvo.server

import co.touchlab.stately.collections.ConcurrentMutableMap
import kinuvo.protocol.UiCatalog

class SurfaceCatalogs {
    private val connections = ConcurrentMutableMap<Any, List<UiCatalog>>()
    private val order = mutableListOf<Any>()

    fun connect(connection: Any, catalogs: List<UiCatalog>) = connections.block {
        order.remove(connection)
        order.add(connection)
        it[connection] = catalogs
    }

    fun disconnect(connection: Any) { connections.block { it.remove(connection); order.remove(connection) } }

    fun forRun(connection: Any? = null): List<UiCatalog> = connections.block { active ->
        if (connection != null) active[connection].orEmpty()
        else order.asReversed().firstNotNullOfOrNull { active[it]?.takeIf { catalogs -> catalogs.isNotEmpty() } }.orEmpty()
    }
}
