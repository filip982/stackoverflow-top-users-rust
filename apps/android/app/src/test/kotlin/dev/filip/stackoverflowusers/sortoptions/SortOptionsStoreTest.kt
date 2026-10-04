package dev.filip.stackoverflowusers.sortoptions

import app.cash.turbine.test
import dev.filip.stackoverflowusers.core.SortDirection
import dev.filip.stackoverflowusers.core.SortField
import dev.filip.stackoverflowusers.core.SortSpec
import dev.filip.stackoverflowusers.sortoptions.SortOptionsIntent.Apply
import dev.filip.stackoverflowusers.sortoptions.SortOptionsIntent.Cancel
import dev.filip.stackoverflowusers.sortoptions.SortOptionsIntent.SelectDirection
import dev.filip.stackoverflowusers.sortoptions.SortOptionsIntent.SelectField
import dev.filip.stackoverflowusers.sortoptions.SortOptionsState.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SortOptionsStoreTest {
    private val byNameAsc = SortSpec(SortField.NAME, SortDirection.ASCENDING)

    @Test
    fun `default sort is reputation descending`() {
        assertEquals(SortSpec(SortField.REPUTATION, SortDirection.DESCENDING), SortSpec())
    }

    @Test
    fun `draft starts from the applied sort`() {
        val state = SortOptionsStore(byNameAsc).state.value
        assertEquals(byNameAsc, state.draft)
        assertNull(state.outcome)
    }

    @Test
    fun `selections change only the draft until apply`() = runTest {
        val store = SortOptionsStore(SortSpec())
        store.state.test {
            awaitItem()
            store.send(SelectField(SortField.CREATION_DATE))
            store.send(SelectDirection(SortDirection.ASCENDING))
            awaitItem()
            val edited = awaitItem()
            assertEquals(SortSpec(SortField.CREATION_DATE, SortDirection.ASCENDING), edited.draft)
            assertEquals(SortSpec(), edited.applied)
            assertNull(edited.outcome)

            store.send(Apply)
            assertEquals(
                Outcome.Applied(SortSpec(SortField.CREATION_DATE, SortDirection.ASCENDING)),
                awaitItem().outcome,
            )
        }
    }

    @Test
    fun `cancel discards the draft`() {
        val store = SortOptionsStore(SortSpec())
        store.send(SelectField(SortField.MODIFIED_DATE))
        store.send(Cancel)

        with(store.state.value) {
            assertEquals(Outcome.Cancelled, outcome)
            assertEquals(SortSpec(), draft)
        }
    }

    @Test
    fun `intents after an outcome are ignored`() {
        val store = SortOptionsStore(SortSpec())
        store.send(Apply)
        store.send(SelectField(SortField.NAME))
        store.send(Cancel)

        assertEquals(SortOptionsState(SortSpec(), outcome = Outcome.Applied(SortSpec())), store.state.value)
    }
}
