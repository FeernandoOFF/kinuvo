package kinuvo.protocol

import kotlin.test.*

class ApiCatalogTest {
    @Test fun everyApiDescriptorIsInTheCatalog() {
        val declared = Api::class.java.methods.filter { it.parameterCount == 0 && it.returnType == Method::class.java }
            .map { it.invoke(Api) }.toSet()
        assertEquals(declared, Api.methods.toSet())
    }
}
