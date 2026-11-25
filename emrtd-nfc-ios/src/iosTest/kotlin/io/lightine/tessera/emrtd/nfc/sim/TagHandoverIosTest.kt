package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadEvent
import io.lightine.tessera.emrtd.ChipReadState
import io.lightine.tessera.emrtd.ChipReadStep
import io.lightine.tessera.emrtd.ChipReadStepOutcome
import io.lightine.tessera.emrtd.ChipResumeOutcome
import io.lightine.tessera.emrtd.LinkLossPolicy
import io.lightine.tessera.emrtd.fakechip.ChipCommand
import io.lightine.tessera.emrtd.fakechip.ChipFaultAction
import io.lightine.tessera.emrtd.fakechip.ChipReply
import io.lightine.tessera.emrtd.fakechip.DeviceChipProfile
import io.lightine.tessera.emrtd.fakechip.FlowChip
import io.lightine.tessera.emrtd.fakechip.ScriptedChip
import io.lightine.tessera.emrtd.fakechip.SimRecorder
import io.lightine.tessera.emrtd.internal.crypto.chipCrypto
import io.lightine.tessera.emrtd.internal.pa.TestKeys
import io.lightine.tessera.telemetry.TelemetryEvent
import io.lightine.tessera.telemetry.TelemetrySink
import io.lightine.tessera.types.errors.NfcTagLost
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The planned handover of the tag before the system's limit on one tag connection (TES-346),
 * through the iPhone entry point in virtual time: the device-shaped chip, with a photo that takes
 * about 30 s over a slow link, and the platform's limit modelled as stated by the vendor's
 * engineers (20 s per tag connection; the system then ends the session, code 202).
 */
class TagHandoverIosTest {
    private val events = mutableListOf<ChipReadEvent>()
    private val sink =
        object : TelemetrySink {
            override fun record(event: TelemetryEvent) {
                if (event is ChipReadEvent) events += event
            }
        }

    private val handovers: List<ChipReadEvent> get() = events.filter { it.step == ChipReadStep.TAG_HANDOVER }

    /** DG2's protected READ BINARY commands, as (link, offset), recorded when each was sent. */
    private val dg2Reads = mutableListOf<Pair<Int, Int>>()

    private fun simTest(
        photoChunkMillis: Long = SLOW_PHOTO_CHUNK_MILLIS,
        slowLinks: (Int) -> Boolean = { true },
        block: suspend IosSim.(FlowChip) -> Unit,
    ) = runTest {
        val flow = DeviceChipProfile.build(chipCrypto(), TestKeys.get(), startInApplication = true)
        val script =
            ScriptedChip(flow.chip) { c ->
                if (isPhotoRead(c) && slowLinks(c.link)) photoChunkMillis else DeviceChipProfile.latency(c)
            }
        script.fault(ChipFaultAction.Silence, times = Int.MAX_VALUE) { c ->
            if (isPhotoRead(c)) dg2Reads += c.link to offsetOf(c)
            false
        }
        val sim = IosSim(this, script)
        sim.connectedLimitMillis = PLATFORM_TAG_LIMIT_MILLIS
        sim.connectedLimitEndsSession = true
        try {
            sim.block(flow)
        } finally {
            sim.close()
        }
    }

    private fun IosSim.read(
        flow: FlowChip,
        settings: CoreNfcReadSettings = CoreNfcReadSettings(),
    ): SimRecorder {
        val (_, recorder) = start(deviceOptions(flow), settings, telemetry = sink)
        runUntilEnded(recorder)
        assertNull(recorder.checkPromises())
        assertFilesNeverGoBack(recorder)
        return recorder
    }

    /** When the system reported the first tag: the tag's time counts from there. */
    private fun SimRecorder.tagReportedAt(): Long = firstAt<ChipReadState.DocumentFound>()!!

    @Test
    fun a_slowPhoto_oneHandoverAt18s_noRestartSpent_continuesAtItsOffset_andALaterRealLossHasTheFullBudget() =
        simTest { flow ->
            // A real loss on the second link, well after the handover: it must get both restarts.
            script.fault(ChipFaultAction.LoseLink(Shapes.LOSS_AFTER_MILLIS)) { c -> c.link == 2 && isPhotoRead(c) && c.indexInLink > 60 }
            val r = read(flow)

            val ending = assertIs<ChipReadEnding.Complete>(r.ending)
            assertDeviceChecksPass(ending)
            // The handover: once, between 18 s and one command later on the tag, in the same session.
            val handover = handovers.single()
            assertEquals(ChipReadStepOutcome.SUCCEEDED, handover.outcome)
            assertTrue(handover.durationMillis in 18_000..18_000 + SLOW_PHOTO_CHUNK_MILLIS, "tag time ${handover.durationMillis}")
            assertEquals(1, ports.size, "the same system session")
            assertEquals(3, script.links, "the first link, the handover's, the one after the real loss")
            // No restart spent and no LinkLost for the handover: the real loss later shows both restarts left.
            val lost = r.states.filterIsInstance<ChipReadState.LinkLost>().single()
            assertEquals(2, lost.restartsLeft)
            assertEquals(2, ending.attempts, "the handover is no new attempt; the real loss is")
            // The photo continued from its byte offset on the second link: nothing read twice.
            val first = dg2Reads.filter { it.first == 1 }.map { it.second }
            val second = dg2Reads.filter { it.first == 2 }.map { it.second }
            assertTrue(second.min() > first.max(), "link 2 starts after link 1's last chunk: ${first.max()} / ${second.min()}")
            assertEquals(first.max() + CHUNK, second.min(), "no byte read again")
            assertTrue(
                ending.read.files
                    .single { it.dataGroup == 2 }
                    .continuedAt != null,
            )
            // The access protocol ran again on the new link, with the same key.
            assertEquals(1, script.exchangesOf(2).count { it.command.ins == 0x82 })
        }

    @Test
    fun a_off_theSystemEndsTheSessionAt20sOfTagTime_andTheReadEndsAsBefore() =
        simTest { flow ->
            val r = read(flow, CoreNfcReadSettings(tagTimeLimitMillis = CoreNfcReadSettings.TAG_TIME_LIMIT_OFF, telemetry = sink))

            val ending = assertIs<ChipReadEnding.EndedBySystem>(r.ending)
            assertTrue(handovers.isEmpty())
            assertEquals(1, script.links)
            assertTrue(r.endings.single().atMillis - r.tagReportedAt() in 20_000..21_000, "ended at ${r.endings.single().atMillis}")
            assertTrue(ending.progress.percent < 99)
        }

    @Test
    fun b_aCommandInFlightAt18sFinishesFirst_theHandoverComesAfterItsAnswer() =
        simTest { flow ->
            var tagAt = Long.MAX_VALUE
            var slowSentAt = 0L
            var slowOffset = -1
            script.fault(ChipFaultAction.Delay(1_200)) { c ->
                // The photo chunk that goes out just before 18 s on the tag: out until 19 s.
                (c.link == 1 && isPhotoRead(c) && now() - tagAt >= 18_000 - SLOW_PHOTO_CHUNK_MILLIS).also {
                    if (it) {
                        slowSentAt = now()
                        slowOffset = offsetOf(c)
                    }
                }
            }
            val (_, recorder) = start(deviceOptions(flow), telemetry = sink)
            while (recorder.updates.none { it.value.state is ChipReadState.DocumentFound }) step(IosSim.STEP_MILLIS)
            tagAt = recorder.tagReportedAt()
            runUntilEnded(recorder)
            assertNull(recorder.checkPromises())

            assertIs<ChipReadEnding.Complete>(recorder.ending)
            assertTrue(slowOffset >= 0, "the slow chunk went out")
            val handover = handovers.single()
            // The handover waited for the answer of the command that was out at 18 s.
            assertTrue(tagAt + handover.durationMillis >= slowSentAt + 1_200, "handover at ${handover.durationMillis} on the tag")
            val link1 = script.exchangesOf(1)
            assertEquals(slowOffset, offsetOf(link1.last().command), "the slow chunk is link 1's last command")
            assertIs<ChipReply.Answer>(link1.last().reply)
            // Its answer was used: the second link starts right after it.
            assertEquals(slowOffset + CHUNK, dg2Reads.filter { it.first == 2 }.minOf { it.second })
        }

    @Test
    fun c_littleTagTimeLeftBeforeChipAuthentication_handsOverBeforeItStarts() =
        simTest { flow ->
            // The files before chip authentication come slowly on the first link: it would start
            // with less than 5 s of the 18 left.
            script.latencyMillis = { c ->
                if (c.link == 1 && c.secured && c.ins == 0xB0) SLOW_EARLY_READ_MILLIS else DeviceChipProfile.latency(c)
            }
            val r = read(flow)

            assertDeviceChecksPass(r.ending)
            val handover = handovers.single()
            assertTrue(handover.durationMillis in 13_000 until 16_000, "tag time ${handover.durationMillis}")
            assertTrue(
                script.exchangesOf(1).none(
                    Shapes.mseSetKat.let { m ->
                        { e -> m(e.command) }
                    },
                ),
                "no chip authentication began on link 1",
            )
            assertEquals(1, script.exchangesOf(2).count { Shapes.mseSetKat(it.command) })
            assertTrue(r.states.none { it is ChipReadState.LinkLost })
        }

    @Test
    fun d_anotherChipAfterTheHandover_theResumeRuleReadsItAfresh() =
        simTest(slowLinks = { it == 1 }) { flow ->
            val other = DeviceChipProfile.build(chipCrypto(), TestKeys.get(), startInApplication = true, dg2ImageBytes = 19_000).chip
            var tagAt = Long.MAX_VALUE
            script.fault(ChipFaultAction.Silence, times = Int.MAX_VALUE) { c ->
                if (c.link == 1 && now() - tagAt >= 17_000) script.nextChip = other
                false
            }
            val (_, recorder) = start(deviceOptions(flow), telemetry = sink)
            while (recorder.updates.none { it.value.state is ChipReadState.DocumentFound }) step(IosSim.STEP_MILLIS)
            tagAt = recorder.tagReportedAt()
            runUntilEnded(recorder)
            assertNull(recorder.checkPromises())

            val ending = assertIs<ChipReadEnding.Complete>(recorder.ending)
            assertEquals(1, handovers.size)
            assertEquals(ChipResumeOutcome.SECURITY_OBJECT_DIFFERS, ending.read.resume)
            assertNull(
                ending.read.files
                    .single { it.dataGroup == 2 }
                    .continuedAt,
                "read again in full",
            )
            assertEquals(0, dg2Reads.filter { it.first == 2 }.minOf { it.second })
            assertDeviceChecksPass(ending)
        }

    @Test
    fun e_noDocumentAfterThePlannedRestart_isALossAfter2s_andUsesARestart() =
        simTest { flow ->
            var tagAt = Long.MAX_VALUE
            script.fault(ChipFaultAction.Silence, times = Int.MAX_VALUE) { c ->
                if (c.link == 1 && now() - tagAt >= 17_500 && documentInField) {
                    documentInField = false
                    later(5_000) { documentInField = true }
                }
                false
            }
            val (_, recorder) = start(deviceOptions(flow), telemetry = sink)
            while (recorder.updates.none { it.value.state is ChipReadState.DocumentFound }) step(IosSim.STEP_MILLIS)
            tagAt = recorder.tagReportedAt()
            runUntilEnded(recorder)
            assertNull(recorder.checkPromises())

            val ending = assertIs<ChipReadEnding.Complete>(recorder.ending)
            val handoverAt = tagAt + handovers.single().durationMillis
            val lost = recorder.updates.single { it.value.state is ChipReadState.LinkLost }
            // The first of the two restarts is taken: LinkLost shows the budget it was taken from.
            assertEquals(2, (lost.value.state as ChipReadState.LinkLost).restartsLeft)
            assertTrue(lost.atMillis - handoverAt in CoreNfcChipRead.HANDOVER_WAIT_MILLIS..CoreNfcChipRead.HANDOVER_WAIT_MILLIS + 50)
            assertEquals(2, ending.attempts)
            assertTrue(
                ending.read.files
                    .single { it.dataGroup == 2 }
                    .continuedAt != null,
                "still resumed",
            )
        }

    @Test
    fun e_noDocumentAfterThePlannedRestart_noRestartLeft_endsPartialWithTheLoss() =
        simTest { flow ->
            // The only restart goes to a real loss early on the first link.
            script.fault(ChipFaultAction.LoseLink(Shapes.LOSS_AFTER_MILLIS)) { c -> c.link == 1 && isPhotoRead(c) && c.indexInLink > 40 }
            var tagAt = Long.MAX_VALUE
            script.fault(ChipFaultAction.Silence, times = Int.MAX_VALUE) { c ->
                if (c.link == 2 && now() - tagAt >= 17_500) documentInField = false
                false
            }
            val (_, recorder) = start(deviceOptions(flow), CoreNfcReadSettings(linkLoss = LinkLossPolicy.Resume(1)), telemetry = sink)
            // The second tag's time counts from its own report.
            while (recorder.updates.count { it.value.state is ChipReadState.Connecting } < 2) step(IosSim.STEP_MILLIS)
            tagAt = recorder.updates.last { it.value.state is ChipReadState.DocumentFound }.atMillis
            runUntilEnded(recorder)
            assertNull(recorder.checkPromises())

            val ending = assertIs<ChipReadEnding.Partial>(recorder.ending)
            assertIs<NfcTagLost>(ending.stoppedBy)
            assertEquals(1, handovers.size)
            assertEquals(1, recorder.states.count { it is ChipReadState.LinkLost }, "only the real loss")
        }

    @Test
    fun f_atMostThreeHandoversPerRead() =
        simTest(photoChunkMillis = 700) { flow ->
            // A 10 s limit and a photo of about a minute: the fourth handover is refused.
            val r = read(flow, CoreNfcReadSettings(tagTimeLimitMillis = 10_000, telemetry = sink))

            assertEquals(CoreNfcChipRead.MAX_TAG_HANDOVERS, handovers.size)
            // Then the read keeps the fourth connection until the system ends the session at 20 s on it.
            assertIs<ChipReadEnding.EndedBySystem>(r.ending)
            assertEquals(4, script.links)
            assertTrue(r.states.none { it is ChipReadState.LinkLost })
        }

    @Test
    fun f_noHandoverWithLessThan10sOfTheSessionLeft() =
        simTest(photoChunkMillis = 700) { flow ->
            // 18 s connections and a photo of about a minute: the third handover would come at
            // about 57 s of the session's 60.
            val r = read(flow)

            assertEquals(2, handovers.size)
            assertIs<ChipReadEnding.EndedBySystem>(r.ending)
            assertEquals(3, script.links)
            val sessionBegan = 0L
            assertTrue(r.endings.single().atMillis - sessionBegan >= 50_000)
        }

    @Test
    fun g_cancelDuringTheHandover_endsCancelledAtOnce() =
        simTest { flow ->
            val options = deviceOptions(flow)
            val (read, recorder) = start(options, telemetry = sink)
            while (handovers.isEmpty()) step(IosSim.STEP_MILLIS)
            val at = now()
            read.cancel()
            runUntilEnded(recorder)
            assertNull(recorder.checkPromises())

            val ending = assertIs<ChipReadEnding.Cancelled>(recorder.ending)
            assertEquals(ChipReadEnding.Cancelled.By.APP, ending.by)
            assertEquals(at, recorder.endings.single().atMillis)
            assertTrue(recorder.states.none { it is ChipReadState.LinkLost })
            assertTrue(port.invalidated, "the session was ended")
            assertTrue(options.accessKey.isCleared, "the key is cleared at the ending")
            assertEquals(1, script.links, "no new link after the cancel")
        }

    @Test
    fun h_settings_tagTimeLimit_defaultOffAndRange() {
        val defaults = CoreNfcReadSettings()
        assertEquals(18_000L, defaults.tagTimeLimitMillis)
        assertEquals(CoreNfcReadSettings.DEFAULT_TAG_TIME_LIMIT_MILLIS, defaults.tagTimeLimitMillis)
        assertEquals(0L, CoreNfcReadSettings(tagTimeLimitMillis = CoreNfcReadSettings.TAG_TIME_LIMIT_OFF).tagTimeLimitMillis)
        assertEquals(10_000L, CoreNfcReadSettings(tagTimeLimitMillis = CoreNfcReadSettings.MIN_TAG_TIME_LIMIT_MILLIS).tagTimeLimitMillis)
        assertEquals(60_000L, CoreNfcReadSettings(tagTimeLimitMillis = 60_000).tagTimeLimitMillis)
        for (bad in listOf(-1L, 1L, 9_999L, 60_001L)) {
            assertFailsWith<IllegalArgumentException>("$bad") { CoreNfcReadSettings(tagTimeLimitMillis = bad) }
            assertFailsWith<IllegalArgumentException>("$bad") { CoreNfcReadSettings.Builder().tagTimeLimitMillis(bad).build() }
        }
        val built = CoreNfcReadSettings.Builder().tagTimeLimitMillis(15_000).build()
        assertEquals(15_000L, built.toBuilder().build().tagTimeLimitMillis, "the builder keeps the value")
        assertTrue("tagTimeLimitMillis=15000" in built.toString())
    }

    /**
     * The file count of the updates never goes back across a handover, up to the first real loss
     * ([ChipReadState.LinkLost]): the attempt after it counts its files again, as before this change.
     */
    private fun assertFilesNeverGoBack(r: SimRecorder) {
        var last = 0
        for (u in r.updates.takeWhile { it.value.state !is ChipReadState.LinkLost }) {
            val done = u.value.progress.filesDone
            assertTrue(done >= last, "files went back at $u")
            last = done
        }
    }

    private fun isPhotoRead(c: ChipCommand): Boolean = c.secured && c.ins == 0xB0 && c.chip.currentEf == 0x0102

    private fun offsetOf(c: ChipCommand): Int = ((c.apdu!!.p1 and 0x7F) shl 8) or c.apdu!!.p2

    private companion object {
        /** The platform's limit on one tag connection, as the vendor's engineers stated it. */
        const val PLATFORM_TAG_LIMIT_MILLIS = 20_000L

        /** One protected READ BINARY of the photo over a slow link: about 30 s for the whole photo. */
        const val SLOW_PHOTO_CHUNK_MILLIS = 330L

        /** The bytes of one protected READ BINARY. */
        const val CHUNK = 0xDF

        const val SLOW_EARLY_READ_MILLIS = 900L
    }
}
