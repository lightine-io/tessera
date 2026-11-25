package io.lightine.tessera.emrtd.nfc

import io.lightine.tessera.emrtd.ChipReadEnding
import io.lightine.tessera.emrtd.ChipReadState
import io.lightine.tessera.emrtd.ChipReadUpdate
import io.lightine.tessera.emrtd.LinkLossPolicy
import io.lightine.tessera.types.errors.NfcNoAnswer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * TES-346: the built-in English sheet texts for every state and ending a read reaches, the
 * progress bar, and an app's own texts.
 */
class CoreNfcSheetTextsTest {
    private val defaults = CoreNfcDefaultSheetTexts

    private fun filled(text: String): Int = text.count { it == '▓' }

    @Test
    fun every_state_has_a_short_text__and_the_bar_never_goes_back() =
        chipReadTest {
            val updates = mutableListOf<ChipReadUpdate>()
            // A complete read, slow enough to report the waiting time, with one lost link.
            // Command 7 is the first after the plain chip's probe: a loss there spends a restart.
            val chip = FakeChip(scope = scope, answerDelayMillis = 20).apply { loseAt += 7 }
            val (read, rec) = start()
            step(8_000)
            present(FakeChipTag(chip))
            step(400 + 200)
            present(FakeChipTag(chip))
            step(60_000)
            assertIs<ChipReadEnding.Complete>(rec.ending)
            updates += rec.updates
            // A read cancelled while reading, for Stopping.
            val (cancelled, stopped) = start()
            present(FakeChipTag(FakeChip().apply { silentFrom = 2 }))
            step(400)
            cancelled.cancel()
            updates += stopped.updates
            // A chip that does not answer its first command, for NoAnswerYet.
            val (silent, noAnswer) = start()
            present(FakeChipTag(FakeChip().apply { loseAt += 1 }))
            step(400)
            silent.cancel()
            updates += noAnswer.updates

            val names = updates.map { it.state::class.simpleName }.toSet()
            val expected =
                setOf(
                    "Starting",
                    "WaitingForDocument",
                    "DocumentFound",
                    "Connecting",
                    "OpeningAccess",
                    "ReadingFile",
                    "CheckingFiles",
                    "NoAnswerYet",
                    "LinkLost",
                    "Stopping",
                )
            assertEquals(expected, names)
            for (update in updates) {
                val text = defaults.textFor(update)
                assertTrue(text.isNotBlank(), "${update.state}")
                assertTrue('\u2014' !in text, "no em-dash")
            }
            val waitingTexts = updates.filter { it.state is ChipReadState.WaitingForDocument }.map { defaults.textFor(it) }.toSet()
            assertEquals(setOf("Hold the top of your iPhone on the document and move it slowly"), waitingTexts, "one waiting text")
            assertTrue(updates.any { defaults.textFor(it) == "Found, no answer yet. Move the phone a little." })

            var last = 0
            for (update in rec.updates) {
                val text = defaults.textFor(update)
                if ('%' !in text) continue
                val now = filled(text)
                assertTrue(now >= last, "the bar went back: $last then $now")
                last = now
            }
            assertNothingLeft(read)
        }

    @Test
    fun every_ending_has_a_short_text() =
        chipReadTest {
            val endings = mutableListOf<ChipReadEnding>()
            val chip = FakeChip()
            start(settings = oneSession()).second.also {
                present(FakeChipTag(chip))
                step(400)
                endings += it.ending
            }
            // Partial: the link drops on the last command, after access was granted.
            val last = chip.sent.size
            start(settings = oneSession(CoreNfcReadSettings(linkLoss = LinkLossPolicy.End))).second.also {
                present(FakeChipTag(FakeChip().apply { loseAt += last }))
                step(400)
                endings += it.ending
            }
            // Failed: the first command unanswered with too little of the session left to try again.
            start(settings = oneSession(CoreNfcReadSettings(linkLoss = LinkLossPolicy.End))).second.also {
                step(51_000)
                present(FakeChipTag(FakeChip().apply { loseAt += 1 }))
                step(400)
                endings += it.ending
            }
            start(options = ChipReadHarness.mrzOptions(), settings = oneSession()).second.also {
                present(FakeChipTag(FakeChip(bacRefusesKey = true)))
                step(400)
                endings += it.ending
            }
            endings += start(options = ChipReadHarness.mrzOptions(wrongDigit = true)).second.ending
            start(settings = oneSession()).also {
                it.first.cancel()
                endings += it.second.ending
            }
            start(settings = oneSession()).second.also {
                systemEnds(CoreNfcErrorCode.SESSION_TIMEOUT)
                endings += it.ending
            }
            step(2_000)
            readingAvailable = false
            endings += start(settings = oneSession()).second.ending

            val kinds = endings.map { it::class.simpleName }
            assertEquals(
                listOf("Complete", "Partial", "Failed", "KeyNotAccepted", "KeyRejected", "Cancelled", "EndedBySystem", "NotStarted"),
                kinds,
            )
            for (ending in endings) assertTrue(defaults.textForEnding(ending).isNotBlank(), "$ending")
            assertEquals("Done", defaults.textForEnding(endings.first()))
            // A first command never answered: the chip never answered, so the text says what to do.
            assertEquals(NfcNoAnswer, assertIs<ChipReadEnding.Failed>(endings[2]).error)
            assertEquals("No answer from the chip. Try another position.", defaults.textForEnding(endings[2]))
            assertEquals(endings.size, endings.map { defaults.textForEnding(it) }.toSet().size, "one text per kind")
        }

    @Test
    fun progress_bar__width_percent_and_limits() =
        chipReadTest {
            val (read, rec) = start()
            present(FakeChipTag(FakeChip()))
            step(400)
            val done = rec.ending.progress
            assertEquals("▓".repeat(10) + " 100%", defaults.progressBar(done))
            assertEquals("▓".repeat(4) + " 100%", defaults.progressBar(done, 4))
            val first = rec.updates.first().progress
            assertEquals("░".repeat(10) + " 0%", defaults.progressBar(first))
            assertFailsWith<IllegalArgumentException> { defaults.progressBar(done, 0) }
            assertFailsWith<IllegalArgumentException> { defaults.progressBar(done, 41) }
            assertNothingLeft(read)
        }

    @Test
    fun app_texts__are_used_and_a_text_function_that_throws_gets_the_default() =
        chipReadTest {
            val texts =
                object : CoreNfcSheetTexts {
                    override fun textFor(update: ChipReadUpdate): String =
                        if (update.state is io.lightine.tessera.emrtd.ChipReadState.DocumentFound) {
                            error(
                                "app bug",
                            )
                        } else {
                            "app: ${update.state::class.simpleName}"
                        }

                    override fun textForEnding(ending: ChipReadEnding): String = "app ending"
                }
            val (read, rec) = start(texts = texts, settings = oneSession(CoreNfcReadSettings(linkLoss = LinkLossPolicy.End)))
            assertEquals("app: WaitingForDocument", port.alerts.first())
            // Too little of the session left for another try: the unanswered first command ends it.
            step(51_000)
            present(FakeChipTag(FakeChip().apply { loseAt += 1 }))
            assertEquals("Found. Hold still.", port.alerts.last())
            step(400)
            assertIs<ChipReadEnding.Failed>(rec.ending)
            assertEquals(listOf<String?>("app ending"), port.endings)
            assertNothingLeft(read)
        }
}
