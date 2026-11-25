package com.github.damontecres.wholphin.services

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class MusicReportSenderTest {
    private val a = UUID(0, 1)
    private val b = UUID(0, 2)
    private val q = ReportedQueue(null, null)

    @Test
    fun `reports go out in the order they were offered even when one call is slow`() =
        runTest {
            val sent = mutableListOf<MusicReport>()
            val sender =
                MusicReportSender(this) {
                    if (it is MusicReport.Stop) delay(1_000)
                    sent += it
                }
            sender.offer(listOf(MusicReport.Stop(a, 1, q), MusicReport.Start(b, 0, false, q)))
            sender.offer(listOf(MusicReport.Progress(b, 5, false, q)))
            sender.close()
            advanceUntilIdle()
            assertEquals(listOf(a, b, b), sent.map { it.itemId })
            assertEquals(
                listOf(
                    MusicReport.Stop::class,
                    MusicReport.Start::class,
                    MusicReport.Progress::class,
                ),
                sent.map { it::class },
            )
        }

    @Test
    fun `a failing report does not stop the ones after it`() =
        runTest {
            val sent = mutableListOf<MusicReport>()
            var first = true
            val sender =
                MusicReportSender(this) {
                    if (first) {
                        first = false
                        throw IllegalStateException("server down")
                    }
                    sent += it
                }
            val reports =
                listOf(
                    MusicReport.Start(a, 0, false, q),
                    MusicReport.Progress(a, 5, false, q),
                    MusicReport.Stop(a, 9, q),
                )
            sender.offer(reports)
            sender.close()
            advanceUntilIdle()
            assertEquals(reports.drop(1), sent)
        }

    @Test
    fun `reports offered before close are still sent`() =
        runTest {
            val sent = mutableListOf<MusicReport>()
            val sender = MusicReportSender(this) { sent += it }
            val reports = listOf(MusicReport.Start(a, 0, false, q), MusicReport.Stop(a, 9, q))
            sender.offer(reports)
            sender.close()
            sender.offer(listOf(MusicReport.Start(b, 0, false, q)))
            advanceUntilIdle()
            assertEquals(reports, sent)
        }
}
