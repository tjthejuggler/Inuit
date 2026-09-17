package com.example.inuit

import com.example.inuit.data.Net
import com.example.inuit.data.ReviewQueue
import com.example.inuit.data.ReviewRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The persisted dispute queue: requests must survive process death, dedupe
 * per answer record, enqueue at the FRONT (the user is looking at that
 * flash), cap FIFO, and track failed attempts.
 */
class ReviewQueueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun request(
        recordId: String,
        netId: String = Net.ALL_ID,
        attempts: Int = 0
    ) = ReviewRequest(
        netId = netId,
        answerRecordId = recordId,
        questionId = "q-$recordId",
        userAnswer = "2",
        gradedCorrect = false,
        queuedAtMs = 1_000L,
        attempts = attempts
    )

    @Test
    fun `request survives a reload (process death)`() {
        val q1 = ReviewQueue(tmp.root)
        q1.enqueueFront(request("r1"))
        q1.enqueueFront(request("r2"))

        // Simulated app restart: a fresh instance over the same filesDir.
        val q2 = ReviewQueue(tmp.root)
        assertEquals(listOf("r2", "r1"), q2.all().map { it.answerRecordId })
    }

    @Test
    fun `enqueue front puts the newest dispute first`() {
        val q = ReviewQueue(tmp.root)
        q.enqueueFront(request("old"))
        q.enqueueFront(request("new"))
        assertEquals("new", q.peek()?.answerRecordId)
    }

    @Test
    fun `re-enqueueing the same answer replaces the old request`() {
        val q = ReviewQueue(tmp.root)
        q.enqueueFront(request("r1"))
        q.enqueueFront(request("r2"))
        q.enqueueFront(request("r1"))
        assertEquals(listOf("r1", "r2"), q.all().map { it.answerRecordId })
        assertEquals(2, q.size)
    }

    @Test
    fun `queue caps FIFO beyond max`() {
        val q = ReviewQueue(tmp.root)
        for (i in 0 until ReviewQueue.MAX + 5) q.enqueueFront(request("r$i"))
        assertEquals(ReviewQueue.MAX, q.size)
        // Newest survived; the oldest entries were dropped.
        assertEquals("r${ReviewQueue.MAX + 4}", q.peek()?.answerRecordId)
    }

    @Test
    fun `remove and attempts update persist`() {
        val q1 = ReviewQueue(tmp.root)
        q1.enqueueFront(request("r1"))
        q1.enqueueFront(request("r2"))
        q1.remove("r1")
        q1.updateAttempts("r2", 2)

        val q2 = ReviewQueue(tmp.root)
        assertNull(q2.peek()?.takeIf { it.answerRecordId == "r1" })
        assertEquals(2, q2.peek()?.attempts)
    }

    @Test
    fun `json round-trips all fields`() {
        val r = request("r9", netId = "net-x", attempts = 2)
        val parsed = ReviewRequest.fromJson(JSONObject(r.toJson().toString()))
        assertEquals(r, parsed)
        // Missing/blank ids must be rejected on load for robustness.
        assertTrue(
            ReviewRequest.fromJson(JSONObject("""{"netId":"n"}""")).answerRecordId.isEmpty()
        )
    }

    @Test
    fun `empty dir loads to an empty queue`() {
        val q = ReviewQueue(tmp.newFolder("fresh"))
        assertEquals(0, q.size)
        assertNull(q.peek())
    }
}
