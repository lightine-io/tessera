package io.lightine.tessera.emrtd.internal.ca

import io.lightine.tessera.emrtd.ApduTransport
import io.lightine.tessera.emrtd.fakechip.FakeCa
import io.lightine.tessera.emrtd.fakechip.FakeCaKey
import io.lightine.tessera.emrtd.fakechip.FakeCaKeyEncoding
import io.lightine.tessera.emrtd.fakechip.FakeCaOffer
import io.lightine.tessera.emrtd.fakechip.FakeChip
import io.lightine.tessera.emrtd.fakechip.FakeChipData
import io.lightine.tessera.emrtd.fakechip.FakeFileSystem
import io.lightine.tessera.emrtd.fakechip.FakeSm
import io.lightine.tessera.emrtd.internal.apdu.ApduSender
import io.lightine.tessera.emrtd.internal.apdu.ChipCommands
import io.lightine.tessera.emrtd.internal.apdu.CommandApdu
import io.lightine.tessera.emrtd.internal.apdu.LdsIds
import io.lightine.tessera.emrtd.internal.apdu.PlainApduSender
import io.lightine.tessera.emrtd.internal.apdu.StatusWord
import io.lightine.tessera.emrtd.internal.crypto.ChipCrypto
import io.lightine.tessera.emrtd.internal.crypto.ChipCryptoException
import io.lightine.tessera.emrtd.internal.crypto.CryptoBytes
import io.lightine.tessera.emrtd.internal.crypto.DhDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.EcDomainParameters
import io.lightine.tessera.emrtd.internal.crypto.EcPointCodec
import io.lightine.tessera.emrtd.internal.crypto.StandardDomainParameterValues
import io.lightine.tessera.emrtd.internal.crypto.withCrypto
import io.lightine.tessera.emrtd.internal.securityinfo.ChipSecurityFiles
import io.lightine.tessera.emrtd.internal.securityinfo.SecurityInfoParser
import io.lightine.tessera.emrtd.internal.securityinfo.SecurityInfos
import io.lightine.tessera.emrtd.internal.sm.SecureChannel
import io.lightine.tessera.emrtd.internal.sm.SecureMessaging
import io.lightine.tessera.emrtd.internal.sm.SessionKeys
import io.lightine.tessera.emrtd.internal.sm.SmCipher
import io.lightine.tessera.emrtd.internal.tlv.Tlv
import io.lightine.tessera.emrtd.internal.tlv.TlvParser
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Chip authentication (Doc 9303 Part 11, sections 6.2, 8.5 and 9.8.2) against the fake chip
 * ([FakeCa] in a [FakeSm] session). The fake chip is personalized from the project specimen and
 * its keys are made at test time. Part 11 prints no chip-authentication example, so these tests
 * check behaviour; the byte-level cross-check of every suite is the JVM reference test.
 */
class ChipAuthenticationFakeChipTest {
    private val dg1 = FakeChipData.dg1(FakeChipData.specimenTd3Lines())
    private val aid = LdsIds.LDS1_AID.toList()

    private class Setup(
        val chip: FakeChip,
        val sm: FakeSm,
        val ca: FakeCa,
        val channel: SecureChannel,
        val infos: SecurityInfos,
        val oldKeys: Pair<ByteArray, ByteArray>,
    )

    /**
     * A chip with a session already open (as after BAC or PACE) with random keys and counter, the
     * eMRTD application selected unless [inApplication] is false, and chip authentication [offers]
     * with [keys]. The reader's infos come from the chip's DG14, read through the session.
     */
    private suspend fun setup(
        c: ChipCrypto,
        keys: List<FakeCaKey>,
        offers: List<FakeCaOffer>,
        oldCipher: SmCipher = SmCipher.DESEDE,
        setKatSupported: Boolean = true,
        contexts: Set<CaContext> = setOf(CaContext.MASTER_FILE, CaContext.EMRTD_APPLICATION),
        inApplication: Boolean = true,
        extended: Boolean = false,
        wrap: (ApduTransport) -> ApduTransport = { it },
    ): Setup {
        val dg14 = FakeCa.dg14(offers, keys)
        val files = FakeFileSystem(applications = mapOf(aid to mapOf(LdsIds.EF_DG1 to dg1, DG14 to dg14)))
        val sm = FakeSm(c)
        val ca = FakeCa(sm, c, keys, offers, setKatSupported, contexts)
        val chip =
            FakeChip(
                files,
                maxCommandLength = if (extended) FakeChip.EXTENDED_MAX_COMMAND else FakeChip.SHORT_MAX_COMMAND,
                supportsExtendedLength = extended,
                handlers = listOf(sm, ca),
            )
        val kEnc = Random.nextBytes(oldCipher.keyType.length)
        val kMac = Random.nextBytes(oldCipher.keyType.length)
        val ssc = Random.nextBytes(oldCipher.blockSize)
        sm.start(oldCipher, kEnc, kMac, ssc)
        val channel = SecureChannel(PlainApduSender(wrap(chip)), c, SessionKeys(oldCipher, kEnc, kMac), ssc)
        assertEquals(StatusWord.SUCCESS, channel.send(ChipCommands.selectApplication(LdsIds.LDS1_AID)).sw)
        val infos = ChipSecurityFiles.parseDg14(readFile(channel, DG14))
        if (!inApplication) assertEquals(StatusWord.SUCCESS, channel.send(ChipCommands.selectMasterFile()).sw)
        return Setup(chip, sm, ca, channel, infos, kEnc to kMac)
    }

    private suspend fun readFile(
        channel: ApduSender,
        fid: Int,
    ): ByteArray {
        assertEquals(StatusWord.SUCCESS, channel.send(ChipCommands.selectFile(fid)).sw)
        var content = channel.send(ChipCommands.readBinary(0, 4)).data
        val total = TlvParser().declaredTotalLength(content)
        while (content.size < total) {
            val r = channel.send(ChipCommands.readBinary(content.size, minOf(0xDF, total - content.size)))
            assertTrue(StatusWord.isSuccess(r.sw))
            content += r.data
        }
        return content
    }

    private suspend fun assertSessionWorks(s: Setup) {
        assertContentEquals(dg1, readFile(s.channel, LdsIds.EF_DG1))
        assertTrue(s.channel.isOpen)
    }

    @Test
    fun ecdhWithAesRestartsSecureMessagingAndTheChipAnswersUnderTheNewKeys() =
        withCrypto { c ->
            runTest {
                for ((cipherCode, cipher) in listOf(2 to SmCipher.AES128, 3 to SmCipher.AES192, 4 to SmCipher.AES256)) {
                    val key = FakeCaKey.generate(c, 13)
                    val s = setup(c, listOf(key), listOf(FakeCaOffer(dh = false, cipherCode = cipherCode)))
                    val outcome =
                        assertIs<CaOutcome.RestartedWithUnverifiedKey>(
                            ChipAuthentication.run(s.channel, c, s.infos, CaContext.EMRTD_APPLICATION),
                        )
                    assertEquals(CaCommands.SET_AT_GENERAL_AUTHENTICATE, outcome.commands)
                    assertEquals(cipher, outcome.cipher)
                    assertEquals(cipher, s.channel.cipher)
                    assertContentEquals(ByteArray(16), s.channel.sscCopy(), "the counter starts at zero (section 9.8.7.3)")
                    assertEquals(listOf(0x22 to 0xA4, 0x86 to 0x00), s.ca.commands)
                    assertEquals(1, s.ca.restarts)
                    assertSessionWorks(s)
                }
            }
        }

    @Test
    fun dhWithTripleDesUsesSetKatAndRestartsWithAZeroCounter() =
        withCrypto { c ->
            runTest {
                for (encoding in listOf(FakeCaKeyEncoding.EXPLICIT, FakeCaKeyEncoding.PKCS3, FakeCaKeyEncoding.STANDARDIZED)) {
                    val key = FakeCaKey.generate(c, 0, encoding = encoding)
                    val s = setup(c, listOf(key), listOf(FakeCaOffer(dh = true, cipherCode = 1)), oldCipher = SmCipher.AES128)
                    val outcome =
                        assertIs<CaOutcome.RestartedWithUnverifiedKey>(
                            ChipAuthentication.run(s.channel, c, s.infos, CaContext.EMRTD_APPLICATION),
                        )
                    assertEquals(CaCommands.SET_KAT, outcome.commands)
                    assertFalse(outcome.usedSetAtFallback)
                    assertEquals(SmCipher.DESEDE, s.channel.cipher)
                    assertContentEquals(ByteArray(8), s.channel.sscCopy(), "the counter starts at zero (section 9.8.6.3)")
                    assertEquals(listOf(0x22 to 0xA6), s.ca.commands)
                    assertSessionWorks(s)
                }
            }
        }

    @Test
    fun aesIsPreferredWhenTheChipOffersBothAndTheKeyReferenceIsSentForTwoKeys() =
        withCrypto { c ->
            runTest {
                val tdesKey = FakeCaKey.generate(c, 0, keyId = 1)
                val aesKey = FakeCaKey.generate(c, 12, keyId = 2)
                val offers = listOf(FakeCaOffer(dh = true, cipherCode = 1, keyId = 1), FakeCaOffer(dh = false, cipherCode = 2, keyId = 2))
                val s = setup(c, listOf(tdesKey, aesKey), offers)
                val outcome =
                    assertIs<CaOutcome.RestartedWithUnverifiedKey>(
                        ChipAuthentication.run(s.channel, c, s.infos, CaContext.EMRTD_APPLICATION),
                    )
                assertEquals(SmCipher.AES128, outcome.cipher)
                assertEquals(2, outcome.keyId)
                // MSE:Set AT carries 80 (the OID value) and 84 02 (the key reference, section 6.2.4.2 Note).
                val setAt = TlvParser().parseAll(s.ca.commandData.first())
                assertContentEquals(FakeCa.oidValue(offers[1].protocol), setAt[0].value())
                assertEquals(0x84, setAt[1].tag)
                assertContentEquals(byteArrayOf(2), setAt[1].value())
                assertSessionWorks(s)
            }
        }

    @Test
    fun aChipWithoutSetKatGetsTripleDesThroughSetAtInTheSameSession() =
        withCrypto { c ->
            runTest {
                val key = FakeCaKey.generate(c, 13)
                val s = setup(c, listOf(key), listOf(FakeCaOffer(dh = false, cipherCode = 1)), setKatSupported = false)
                val outcome =
                    assertIs<CaOutcome.RestartedWithUnverifiedKey>(
                        ChipAuthentication.run(s.channel, c, s.infos, CaContext.EMRTD_APPLICATION),
                    )
                assertTrue(outcome.usedSetAtFallback)
                assertEquals(SmCipher.DESEDE, outcome.cipher)
                val refusal = outcome.refusals.single()
                assertEquals(CaStep.SET_KAT, refusal.step)
                assertEquals(StatusWord.INCORRECT_P1_P2, refusal.sw)
                assertEquals(listOf(0x22 to 0xA6, 0x22 to 0xA4, 0x86 to 0x00), s.ca.commands)
                assertSessionWorks(s)
            }
        }

    @Test
    fun aRefusalKeepsTheOldSessionKeysAndCounterRunning() =
        withCrypto { c ->
            runTest {
                val key = FakeCaKey.generate(c, 13)
                val s = setup(c, listOf(key), listOf(FakeCaOffer(dh = false, cipherCode = 2)))
                s.ca.refuseGeneralAuthenticate = 0x6300
                val outcome = assertIs<CaOutcome.Refused>(ChipAuthentication.run(s.channel, c, s.infos, CaContext.EMRTD_APPLICATION))
                assertEquals(CaStep.GENERAL_AUTHENTICATE, outcome.refusals.single().step)
                assertEquals(0x6300, outcome.refusals.single().sw)
                assertEquals(0, s.ca.restarts)
                assertEquals(SmCipher.DESEDE, s.channel.cipher, "the old (BAC) session goes on (section 6.2.2)")
                assertSessionWorks(s)
            }
        }

    @Test
    fun aChipWithChipAuthenticationOnlyInTheApplicationRefusesItInTheMasterFile() =
        withCrypto { c ->
            runTest {
                val key = FakeCaKey.generate(c, 13)
                val s =
                    setup(
                        c,
                        listOf(key),
                        listOf(FakeCaOffer(dh = false, cipherCode = 2)),
                        contexts = setOf(CaContext.EMRTD_APPLICATION),
                        inApplication = false,
                    )
                val inMf = assertIs<CaOutcome.Refused>(ChipAuthentication.run(s.channel, c, s.infos, CaContext.MASTER_FILE))
                assertEquals(CaContext.MASTER_FILE, inMf.context)
                assertEquals(FakeCa.REFERENCED_DATA_NOT_FOUND, inMf.refusals.single().sw)
                assertTrue(s.channel.isOpen)
                assertEquals(StatusWord.SUCCESS, s.channel.send(ChipCommands.selectApplication(LdsIds.LDS1_AID)).sw)
                val inApp =
                    assertIs<CaOutcome.RestartedWithUnverifiedKey>(
                        ChipAuthentication.run(s.channel, c, s.infos, CaContext.EMRTD_APPLICATION),
                    )
                assertEquals(CaContext.EMRTD_APPLICATION, inApp.context)
                assertSessionWorks(s)
            }
        }

    @Test
    fun chipAuthenticationInTheMasterFileThenTheApplicationIsSelectedUnderTheNewKeys() =
        withCrypto { c ->
            runTest {
                val key = FakeCaKey.generate(c, 16)
                val s =
                    setup(
                        c,
                        listOf(key),
                        listOf(FakeCaOffer(dh = false, cipherCode = 4)),
                        contexts = setOf(CaContext.MASTER_FILE),
                        inApplication = false,
                    )
                assertIs<CaOutcome.RestartedWithUnverifiedKey>(ChipAuthentication.run(s.channel, c, s.infos, CaContext.MASTER_FILE))
                assertEquals(StatusWord.SUCCESS, s.channel.send(ChipCommands.selectApplication(LdsIds.LDS1_AID)).sw)
                assertSessionWorks(s)
            }
        }

    /**
     * Section 9.8.2: the key-agreement commands, and the answer to the last one, use the old keys
     * and counter; then the counter restarts and the new keys apply. Checked on the bytes on the
     * wire, with the MAC computed here from the keys (the old ones known to the test, the new ones
     * from the fake chip), not by the reader's code.
     */
    @Test
    fun theLastAnswerIsProtectedWithTheOldKeysAndTheNextCommandWithTheNewKeysAndCounter() =
        withCrypto { c ->
            runTest {
                for ((offer, oldCipher) in listOf(FakeCaOffer(false, 2) to SmCipher.AES128, FakeCaOffer(true, 1) to SmCipher.DESEDE)) {
                    val recorder = mutableListOf<Pair<ByteArray, ByteArray>>()
                    val key = FakeCaKey.generate(c, if (offer.dh) 0 else 13)
                    val s =
                        setup(c, listOf(key), listOf(offer), oldCipher = oldCipher) { inner ->
                            object : ApduTransport by inner {
                                override suspend fun transceive(command: ByteArray): ByteArray =
                                    inner.transceive(command).also { recorder += command.copyOf() to it.copyOf() }
                            }
                        }
                    val sscBefore = s.channel.sscCopy()
                    val sent = recorder.size
                    assertIs<CaOutcome.RestartedWithUnverifiedKey>(
                        ChipAuthentication.run(s.channel, c, s.infos, CaContext.EMRTD_APPLICATION),
                    )
                    val caExchanges = recorder.size - sent
                    assertEquals(if (offer.dh) 1 else 2, caExchanges)

                    // The last chip-authentication answer: old keys, counter = start + 2 per exchange.
                    val lastAnswer = recorder.last().second
                    val answerSsc = add(sscBefore, 2 * caExchanges)
                    assertAnswerMac(c, oldCipher, s.oldKeys.second, answerSsc, lastAnswer)

                    // The next command: new keys, counter = 1.
                    assertSessionWorks(s)
                    val next = recorder[sent + caExchanges].first
                    val newMac = assertNotNull(s.ca.lastKeys).second
                    assertCommandMac(c, offer.cipher, newMac, add(offer.cipher.zeroSsc(), 1), next)
                }
            }
        }

    @Test
    fun nothingIsSentWhenTheInfosOfferNoUsableChipAuthentication() =
        withCrypto { c ->
            runTest {
                val k1 = FakeCaKey.generate(c, 13, keyId = 1)
                val k2 = FakeCaKey.generate(c, 12, keyId = 2)
                // A ChipAuthenticationInfo with no key id while two keys are listed: never guess.
                val s = setup(c, listOf(k1, k2), listOf(FakeCaOffer(dh = false, cipherCode = 2)))
                val sent = s.chip.received.size
                val outcome = assertIs<CaOutcome.NotRun>(ChipAuthentication.run(s.channel, c, s.infos, CaContext.EMRTD_APPLICATION))
                assertEquals(CaSkipReason.AMBIGUOUS_PUBLIC_KEY, outcome.skipped.single().reason)
                assertEquals(sent, s.chip.received.size)

                val none =
                    ChipAuthentication.run(
                        s.channel,
                        c,
                        SecurityInfoParser.parse(byteArrayOf(0x31, 0x00)),
                        CaContext.EMRTD_APPLICATION,
                    )
                assertIs<CaOutcome.NotRun>(none)
                assertTrue(none.skipped.isEmpty())
                assertEquals(sent, s.chip.received.size)
                assertSessionWorks(s)
            }
        }

    @Test
    fun aKeyWithoutItsInfoOrOfTheWrongKindOrOffTheCurveIsSkipped() =
        withCrypto { c ->
            val ec = FakeCaKey.generate(c, 13, keyId = 5)
            val point = ec.publicValue.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }

            fun planOf(
                offers: List<FakeCaOffer>,
                keys: List<FakeCaKey>,
            ) = ChipAuthentication.plan(c, SecurityInfoParser.parse(FakeCa.securityInfos(offers, keys)))

            assertEquals(CaSkipReason.NO_PUBLIC_KEY, planOf(listOf(FakeCaOffer(false, 2, keyId = 6)), listOf(ec)).skipped.single().reason)
            assertEquals(
                CaSkipReason.KEY_AGREEMENT_MISMATCH,
                planOf(listOf(FakeCaOffer(true, 2, keyId = 5)), listOf(ec)).skipped.single().reason,
            )
            assertEquals(
                CaSkipReason.INVALID_PUBLIC_KEY,
                planOf(listOf(FakeCaOffer(false, 2, keyId = 5)), listOf(ec.withPublicValue(point))).skipped.single().reason,
            )
            val dh = FakeCaKey.generate(c, 0, keyId = 7)
            assertEquals(
                CaSkipReason.INVALID_PUBLIC_KEY,
                planOf(listOf(FakeCaOffer(true, 2, keyId = 7)), listOf(dh.withPublicValue(byteArrayOf(1)))).skipped.single().reason,
            )
            val ok = planOf(listOf(FakeCaOffer(false, 1, keyId = 5), FakeCaOffer(false, 3, keyId = 5)), listOf(ec))
            assertEquals(listOf(SmCipher.AES192, SmCipher.DESEDE), ok.suites.map { it.cipher }, "AES first (section 8.5)")
            assertFalse(ok.suites.first().sendKeyReference, "one key: no key reference")
        }

    @Test
    fun hostileExplicitParametersInDg14AreSkippedNeverThrown() =
        withCrypto { c ->
            runTest {
                fun planOf(key: FakeCaKey) =
                    ChipAuthentication.plan(
                        c,
                        SecurityInfoParser.parse(FakeCa.securityInfos(listOf(FakeCaOffer(key.isDh, 2)), listOf(key))),
                    )

                // An explicit curve with order n = 00: every point would pass the subgroup check
                // and no private key could be drawn. The chip offers its generator as its key.
                val p256 = StandardDomainParameterValues.forId(12) as EcDomainParameters
                val zeroOrder = EcDomainParameters(p256.p, p256.a, p256.b, p256.generator, byteArrayOf(0), p256.cofactor)
                val hostile = FakeCaKey(null, 12, zeroOrder, ByteArray(1), EcPointCodec.encode(zeroOrder, zeroOrder.generator))
                assertEquals(CaSkipReason.UNSUPPORTED_DOMAIN_PARAMETERS, planOf(hostile).skipped.single().reason)

                // The same through a whole run: read from DG14, nothing sent, the session goes on.
                val s = setup(c, listOf(hostile), listOf(FakeCaOffer(dh = false, cipherCode = 2)))
                val sent = s.chip.received.size
                val outcome = assertIs<CaOutcome.NotRun>(ChipAuthentication.run(s.channel, c, s.infos, CaContext.EMRTD_APPLICATION))
                assertEquals(CaSkipReason.UNSUPPORTED_DOMAIN_PARAMETERS, outcome.skipped.single().reason)
                assertEquals(sent, s.chip.received.size)
                assertSessionWorks(s)

                // An even DH prime and a DH prime over 4096 bits are refused by the size policy.
                val modp = StandardDomainParameterValues.forId(0) as DhDomainParameters
                val evenP = DhDomainParameters(modp.p.copyOf().also { it[it.size - 1] = 0 }, modp.g, modp.q)
                val hugeP =
                    DhDomainParameters(
                        ByteArray(513).also {
                            it[0] = 1
                            it[it.size - 1] = 1
                        },
                        modp.g,
                        modp.q,
                    )
                for (params in listOf(evenP, hugeP)) {
                    val key = FakeCaKey(null, 0, params, ByteArray(1), CryptoBytes.ofInt(4))
                    assertEquals(CaSkipReason.UNSUPPORTED_DOMAIN_PARAMETERS, planOf(key).skipped.single().reason)
                }
            }
        }

    @Test
    fun aCryptoFailureWhileMakingTheEphemeralKeyIsATypedSkip() =
        withCrypto { c ->
            runTest {
                val key = FakeCaKey.generate(c, 13)
                val s = setup(c, listOf(key), listOf(FakeCaOffer(dh = false, cipherCode = 2)))
                val sent = s.chip.received.size
                val failing =
                    object : ChipCrypto by c {
                        override fun randomBytes(length: Int): ByteArray = throw ChipCryptoException("no randomness")
                    }
                val outcome = assertIs<CaOutcome.NotRun>(ChipAuthentication.run(s.channel, failing, s.infos, CaContext.EMRTD_APPLICATION))
                assertEquals(CaSkipReason.KEY_AGREEMENT_FAILED, outcome.skipped.single().reason)
                assertEquals(sent, s.chip.received.size)
                assertSessionWorks(s)
            }
        }

    @Test
    fun aTwoThousandBitDhKeyNeedsExtendedLengthAndIsSkippedWithoutIt() =
        withCrypto { c ->
            runTest {
                val key = FakeCaKey.generate(c, 2)
                val offers = listOf(FakeCaOffer(dh = true, cipherCode = 2))
                val short = setup(c, listOf(key), offers)
                val sent = short.chip.received.size
                val skipped = assertIs<CaOutcome.NotRun>(ChipAuthentication.run(short.channel, c, short.infos, CaContext.EMRTD_APPLICATION))
                assertEquals(CaSkipReason.COMMAND_TOO_LONG, skipped.skipped.single().reason)
                assertEquals(sent, short.chip.received.size)
                assertSessionWorks(short)

                val extended = setup(c, listOf(key), offers, extended = true)
                val outcome =
                    ChipAuthentication.run(
                        extended.channel,
                        c,
                        extended.infos,
                        CaContext.EMRTD_APPLICATION,
                        extendedLengthAllowed = true,
                    )
                assertIs<CaOutcome.RestartedWithUnverifiedKey>(outcome)
                assertSessionWorks(extended)
            }
        }

    @Test
    fun theProtectedSizeCheckAgreesWithTheProtectedCommand() =
        withCrypto { c ->
            for (cipher in SmCipher.entries) {
                for (size in listOf(1, 100, 200, 230, 240, 250, 255, 300)) {
                    val plain = ChipAuthentication.generalAuthenticate(ByteArray(size) { 1 })
                    val keys = SessionKeys(cipher, ByteArray(cipher.keyType.length), ByteArray(cipher.keyType.length))
                    val protected = SecureMessaging(c, keys, cipher.zeroSsc()).protect(plain)
                    val shortChannel = SecureChannel(PlainApduSender(FakeChip(FakeFileSystem())), c, keys, cipher.zeroSsc())
                    assertEquals(protected.fitsShort, ChipAuthentication.protectedFits(plain, cipher, shortChannel, false), "$cipher $size")
                }
            }
        }

    private fun add(
        ssc: ByteArray,
        n: Int,
    ): ByteArray {
        val out = ssc.copyOf()
        repeat(n) { SecureMessaging.increment(out) }
        return out
    }

    /** The data objects of a protected answer, its status word removed; DO'8E' last. */
    private fun answerObjects(answer: ByteArray) = TlvParser().parseAll(answer.copyOf(answer.size - 2))

    /** The data objects of a protected command; DO'8E' last. */
    private fun commandObjects(command: ByteArray) = TlvParser().parseAll(CommandApdu.decode(command)!!.data)

    private fun assertAnswerMac(
        c: ChipCrypto,
        cipher: SmCipher,
        macKey: ByteArray,
        ssc: ByteArray,
        answer: ByteArray,
    ) {
        val objects = answerObjects(answer)
        assertMac(c, cipher, macKey, ssc, objects.dropLast(1).fold(ByteArray(0)) { acc, o -> acc + o.encoded() }, objects.last())
    }

    private fun assertCommandMac(
        c: ChipCrypto,
        cipher: SmCipher,
        macKey: ByteArray,
        ssc: ByteArray,
        command: ByteArray,
    ) {
        val objects = commandObjects(command)
        val covered =
            CryptoBytes.pad(command.copyOf(4), cipher.blockSize) + objects.dropLast(1).fold(ByteArray(0)) { acc, o -> acc + o.encoded() }
        assertMac(c, cipher, macKey, ssc, covered, objects.last())
    }

    private fun assertMac(
        c: ChipCrypto,
        cipher: SmCipher,
        macKey: ByteArray,
        ssc: ByteArray,
        covered: ByteArray,
        macObject: Tlv,
    ) {
        val padded = CryptoBytes.pad(ssc + covered, cipher.blockSize)
        val expected = if (cipher == SmCipher.DESEDE) c.retailMac(macKey, padded) else c.cmac(macKey, padded).copyOf(8)
        assertEquals(0x8E, macObject.tag)
        assertContentEquals(expected, macObject.value())
    }

    private companion object {
        const val DG14 = 0x010E
    }
}
