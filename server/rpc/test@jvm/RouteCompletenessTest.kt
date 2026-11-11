package kinuvo.server.rpc

import kinuvo.protocol.Api
import kinuvo.protocol.Method
import kinuvo.server.ServerRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RouteCompletenessTest {
    private val declared = Api::class.java.declaredFields
        .filter { Method::class.java.isAssignableFrom(it.type) }
        .map { field -> field.isAccessible = true; (field.get(Api) as Method<*, *>).name }

    /** Requests the Server sends to a Device, which answers them. */
    private val sentToDevices = setOf(Api.DeviceRead.name, Api.ComputerAct.name)

    @Test fun everyMethodHasOneRouteOrIsHandledByTheRuntime() {
        assertTrue(declared.size > 100, "Found only ${declared.size} methods in Api")
        assertEquals(emptyList(), declared.groupBy { it }.filterValues { it.size > 1 }.keys.toList(), "Method names declared twice")
        val routed = serverRoutes().methods
        val handled = ServerRuntime.handledMethods
        assertEquals(emptyList(), declared.filter { it !in routed && it !in handled && it !in sentToDevices }, "Methods with no route")
        assertEquals(emptySet(), routed.intersect(handled), "Methods both routed and handled by the runtime")
        assertEquals(emptySet(), routed.intersect(sentToDevices), "Methods the Server sends, routed as requests to it")
        assertEquals(emptySet(), (routed + handled) - declared.toSet(), "Routes for methods Api doesn't declare")
    }

    @Test fun registeringAMethodTwiceFails() {
        assertFailsWith<IllegalArgumentException> {
            routes {
                device(Api.Stop) { }
                handle(Api.Stop) { }
            }
        }
    }
}
