package kinuvo.app

import kinuvo.app.session.*
import kinuvo.protocol.*
import kotlin.test.*

class SessionGroupsTest {
    private val daily = RoutineRef("daily", "Daily digest")
    private val weekly = RoutineRef("weekly", "Weekly review")
    private fun s(id: String, title: String = id, routine: RoutineRef? = null) = SessionInfo(id, "a", title, routine = routine)

    @Test fun groupingKeepsOrderAndFoldsEarlierRunsUnderTheLatest() {
        val sessions = listOf(s("d3", routine = daily), s("p1"), s("w2", routine = weekly), s("d2", routine = daily), s("p2"), s("d1", routine = daily), s("w1", routine = weekly))
        val groups = sessions.groupRoutineRuns()
        assertEquals(listOf("d3", "p1", "w2", "p2"), groups.map { it.session.id })
        assertEquals(listOf("d2", "d1"), groups[0].earlier.map { it.id })
        assertEquals(emptyList(), groups[1].earlier)
        assertEquals(listOf("w1"), groups[2].earlier.map { it.id })
        assertEquals(emptyList(), groups[3].earlier)
    }

    @Test fun aSingleRunAndAnEmptyListNeedNoFolding() {
        assertEquals(emptyList(), emptyList<SessionInfo>().groupRoutineRuns())
        assertEquals(listOf(SessionGroup(s("d1", routine = daily))), listOf(s("d1", routine = daily)).groupRoutineRuns())
    }

    @Test fun searchNeedsEveryWordIgnoringCase() {
        val sessions = listOf(s("1", "Plan the Berlin trip"), s("2", "Berlin budget"), s("3", "Trip to Paris"))
        assertEquals(listOf("1", "2"), sessions.search("berlin").map { it.id })
        assertEquals(listOf("1"), sessions.search("TRIP  berlin").map { it.id })
        assertEquals(listOf("1", "3"), sessions.search(" trip ").map { it.id })
        assertEquals(emptyList(), sessions.search("berlin paris"))
    }

    @Test fun blankSearchKeepsEverything() {
        val sessions = listOf(s("1", "One"), s("2", "Two"))
        assertEquals(sessions, sessions.search(""))
        assertEquals(sessions, sessions.search("   "))
    }
}
