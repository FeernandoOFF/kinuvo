package kinuvo.app

import kinuvo.app.screen.RemotePointer
import kotlin.test.*

class RemotePointerTest {
    @Test fun relativeMovementClampsAndAccountsForZoom() {
        assertEquals(RemotePointer(0.6f, 0.4f), RemotePointer().move(20f, -20f, 200f, 200f))
        assertEquals(RemotePointer(0.55f, 0.45f), RemotePointer().move(20f, -20f, 200f, 200f, 2f))
        val edge = RemotePointer().move(10000f, -10000f, 200f, 200f).tap(1280, 800)
        assertEquals(1279, edge.x); assertEquals(0, edge.y)
    }
    @Test fun zoomedViewportFollowsPointerAndClampsAtEdges() {
        assertEquals(-120f, RemotePointer(0.7f, 0.5f).followingPan(200f, 200f, 3f, true), 0.001f)
        assertEquals(-200f, RemotePointer(1f, 0.5f).followingPan(200f, 200f, 3f, true))
        assertEquals(0f, RemotePointer(1f, 0.5f).followingPan(200f, 400f, 1f, true), 0.001f)
    }
    @Test fun touchMappingIncludesLetterboxingZoomAndPan() {
        assertNull(RemotePointer.at(50f, 10f, 200f, 400f, 200f, 100f, 1f, 0f, 0f))
        assertEquals(RemotePointer(), RemotePointer.at(100f, 200f, 200f, 400f, 200f, 100f, 1f, 0f, 0f))
        assertEquals(RemotePointer(0.25f, 0.5f), RemotePointer.at(100f, 200f, 200f, 400f, 200f, 100f, 2f, 100f, 0f))
        assertEquals(RemotePointer(0.25f, 0.5f), RemotePointer.centered(200f, 100f, 2f, 100f, 0f))
    }
}
