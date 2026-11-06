package kinuvo.harness.server

import kinuvo.protocol.UiCatalog
import kotlin.test.*

class SurfaceCatalogsTest {
    @Test fun requestingConnectionNeverBorrowsAnotherDevicesCatalogs() {
        val catalogs = SurfaceCatalogs()
        val rendered = listOf(UiCatalog("basic"))
        catalogs.connect("app", rendered)
        catalogs.connect("cli", emptyList())
        assertEquals(rendered, catalogs.forRun("app"))
        assertEquals(emptyList(), catalogs.forRun("cli"))
        assertEquals(rendered, catalogs.forRun())
        catalogs.disconnect("app")
        assertEquals(emptyList(), catalogs.forRun())
        assertEquals(emptyList(), catalogs.forRun("app"))
    }

    @Test fun unattachedRunsUseMostRecentlyConnectedActiveAdvertiser() {
        val catalogs = SurfaceCatalogs()
        val first = listOf(UiCatalog("first"))
        val second = listOf(UiCatalog("second"))
        catalogs.connect("first", first)
        catalogs.connect("second", second)
        catalogs.connect("cli", emptyList())
        assertEquals(second, catalogs.forRun())
        catalogs.disconnect("second")
        assertEquals(first, catalogs.forRun())
        catalogs.disconnect("first")
        assertEquals(emptyList(), catalogs.forRun())
    }
}
