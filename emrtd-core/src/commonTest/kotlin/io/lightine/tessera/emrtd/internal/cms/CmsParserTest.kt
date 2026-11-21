package io.lightine.tessera.emrtd.internal.cms

import io.lightine.tessera.emrtd.internal.asn1.DerWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CmsParserTest {
    @Test
    fun missingContentIsTyped() {
        // A SignedData whose encapContentInfo names the content type but carries no [0] content.
        val encap = DerWriter.sequence(DerWriter.oid(CmsParser.OID_LDS_SECURITY_OBJECT))
        val signedData = DerWriter.sequence(DerWriter.integer(3), DerWriter.set(), encap)
        val contentInfo = DerWriter.sequence(DerWriter.oid(CmsParser.OID_SIGNED_DATA), DerWriter.explicit(0, signedData))

        val e = assertFailsWith<CmsException> { CmsParser.parse(contentInfo) }
        assertEquals(CmsFailure.MISSING_CONTENT, e.failure)
    }
}
