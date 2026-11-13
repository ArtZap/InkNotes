package dev.inknotes.data

import dev.inknotes.data.SyncPlan.Action
import dev.inknotes.data.SyncPlan.Local
import dev.inknotes.data.SyncPlan.Remote
import dev.inknotes.data.SyncPlan.Synced
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPlanTest {
    private fun plan(local: List<Local> = emptyList(), remote: List<Remote> = emptyList(), synced: Map<String, Synced> = emptyMap(), busy: Set<String> = emptySet()) =
        SyncPlan.plan(local, remote, synced, busy)

    @Test
    fun newOnOneSideGoesToTheOther() {
        assertEquals(listOf(Action.Upload("a", null)), plan(local = listOf(Local("a", "A", 10))))
        assertEquals(listOf(Action.Download("b", "f")), plan(remote = listOf(Remote("f", "b", 1, 10))))
    }

    @Test
    fun unchangedDoesNothing() {
        assertEquals(emptyList<Action>(), plan(listOf(Local("a", "A", 10)), listOf(Remote("f", "a", 3, 10)), mapOf("a" to Synced("f", 10, 3))))
    }

    @Test
    fun changedOnOneSideFlowsThatWay() {
        val s = mapOf("a" to Synced("f", 10, 3))
        assertEquals(listOf(Action.Upload("a", "f")), plan(listOf(Local("a", "A", 20)), listOf(Remote("f", "a", 3, 10)), s))
        assertEquals(listOf(Action.Download("a", "f")), plan(listOf(Local("a", "A", 10)), listOf(Remote("f", "a", 4, 30)), s))
    }

    @Test
    fun changedOnBothSidesKeepsBoth() {
        val s = mapOf("a" to Synced("f", 10, 3))
        assertEquals(listOf(Action.Conflict("a", "f")), plan(listOf(Local("a", "A", 20)), listOf(Remote("f", "a", 4, 30)), s))
    }

    @Test
    fun deletionsGoToTheOtherSidesTrash() {
        val s = mapOf("a" to Synced("f", 10, 3))
        assertEquals(listOf(Action.TrashRemote("a", "f")), plan(remote = listOf(Remote("f", "a", 3, 10)), synced = s))
        assertEquals(listOf(Action.TrashLocal("a")), plan(local = listOf(Local("a", "A", 10)), synced = s))
        // Gone on both sides: nothing.
        assertEquals(emptyList<Action>(), plan(synced = s))
    }

    @Test
    fun openNotebooksAreNotReplacedButStillGoUp() {
        val s = mapOf("a" to Synced("f", 10, 3))
        assertEquals(emptyList<Action>(), plan(listOf(Local("a", "A", 10)), listOf(Remote("f", "a", 4, 30)), s, busy = setOf("a")))
        assertEquals(emptyList<Action>(), plan(local = listOf(Local("a", "A", 10)), synced = s, busy = setOf("a")))
        assertEquals(listOf(Action.Upload("a", "f")), plan(listOf(Local("a", "A", 20)), listOf(Remote("f", "a", 3, 10)), s, busy = setOf("a")))
    }

    @Test
    fun aSecondDeviceRemembersWhatIsAlreadyTheSame() {
        assertEquals(listOf(Action.Remember("a", "f", 10, 3)), plan(listOf(Local("a", "A", 10)), listOf(Remote("f", "a", 3, 10))))
        assertEquals(listOf(Action.Conflict("a", "f")), plan(listOf(Local("a", "A", 11)), listOf(Remote("f", "a", 3, 10))))
    }

    @Test
    fun twoFilesOfOneNotebookTheLatestCounts() {
        val r = listOf(Remote("old", "a", 2, 5), Remote("new", "a", 7, 9))
        assertEquals(listOf(Action.Download("a", "new")), plan(remote = r))
    }

    @Test
    fun manyDeletionsAtOnceLookLikeAMistake() {
        val trash = { n: Int -> List(n) { Action.TrashLocal("n$it") } }
        // One notebook deleted on the other device: as before.
        assertEquals(false, SyncPlan.massTrash(trash(1), 1))
        // Two of three, or every one: asked first.
        assertEquals(true, SyncPlan.massTrash(trash(2), 3))
        assertEquals(true, SyncPlan.massTrash(trash(5), 5))
        // A few of a big library: as before; ten or more at once: asked.
        assertEquals(false, SyncPlan.massTrash(trash(3), 40))
        assertEquals(true, SyncPlan.massTrash(trash(10), 400))
        // Other actions don't count.
        assertEquals(false, SyncPlan.massTrash(listOf(Action.Upload("a", null), Action.TrashRemote("b", "f")), 2))
    }
}
