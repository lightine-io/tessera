package io.lightine.tessera.emrtd.internal.crypto

// The values below were copied by script from the RFC texts (RFC 5114 section 2, RFC 5639 section 3)
// and are cross-checked against BouncyCastle's own tables in jvmTest (StandardDomainParametersBcTest).

/**
 * The VALUES of the standardized domain parameters of Doc 9303 Part 11, section 9.5.1, Table 12,
 * keyed by the table's IDs. The table's metadata (names, DH or ECDH, sizes, which IDs are reserved)
 * is the security-info parser's (`StandardizedParameters`); this object only turns an ID into
 * numbers the crypto can use.
 */
internal object StandardDomainParameterValues {
    /** The parameters for standardized [id], or null for an RFU or unknown ID. */
    fun forId(id: Int): DomainParameters? = cache[id]

    // Built once, on first use; `lazy` is thread-safe on every target.
    private val cache: Map<Int, DomainParameters> by lazy {
        (0..31).mapNotNull { id -> build(id)?.let { id to it } }.toMap()
    }

    private fun hex(vararg parts: String): ByteArray = parts.joinToString("").hexToByteArray()

    @Suppress("LongMethod")
    private fun build(id: Int): DomainParameters? =
        when (id) {
            // 1024-bit MODP Group with 160-bit Prime Order Subgroup: RFC 5114, section 2.1
            0 -> {
                DhDomainParameters(
                    p =
                        hex(
                            "B10B8F96A080E01DDE92DE5EAE5D54EC52C99FBCFB06A3C69A6A9DCA52D23B61",
                            "6073E28675A23D189838EF1E2EE652C013ECB4AEA906112324975C3CD49B83BF",
                            "ACCBDD7D90C4BD7098488E9C219A73724EFFD6FAE5644738FAA31A4FF55BCCC0",
                            "A151AF5F0DC8B4BD45BF37DF365C1A65E68CFDA76D4DA708DF1FB2BC2E4A4371",
                        ),
                    g =
                        hex(
                            "A4D1CBD5C3FD34126765A442EFB99905F8104DD258AC507FD6406CFF14266D31",
                            "266FEA1E5C41564B777E690F5504F213160217B4B01B886A5E91547F9E2749F4",
                            "D7FBD7D3B9A92EE1909D0D2263F80A76A6A24C087A091F531DBF0A0169B6A28A",
                            "D662A4D18E73AFA32D779D5918D08BC8858F4DCEF97C2A24855E6EEB22B3B2E5",
                        ),
                    q = hex("F518AA8781A8DF278ABA4E7D64B7CB9D49462353"),
                )
            }

            // 2048-bit MODP Group with 224-bit Prime Order Subgroup: RFC 5114, section 2.2
            1 -> {
                DhDomainParameters(
                    p =
                        hex(
                            "AD107E1E9123A9D0D660FAA79559C51FA20D64E5683B9FD1B54B1597B61D0A75",
                            "E6FA141DF95A56DBAF9A3C407BA1DF15EB3D688A309C180E1DE6B85A1274A0A6",
                            "6D3F8152AD6AC2129037C9EDEFDA4DF8D91E8FEF55B7394B7AD5B7D0B6C12207",
                            "C9F98D11ED34DBF6C6BA0B2C8BBC27BE6A00E0A0B9C49708B3BF8A3170918836",
                            "81286130BC8985DB1602E714415D9330278273C7DE31EFDC7310F7121FD5A074",
                            "15987D9ADC0A486DCDF93ACC44328387315D75E198C641A480CD86A1B9E587E8",
                            "BE60E69CC928B2B9C52172E413042E9B23F10B0E16E79763C9B53DCF4BA80A29",
                            "E3FB73C16B8E75B97EF363E2FFA31F71CF9DE5384E71B81C0AC4DFFE0C10E64F",
                        ),
                    g =
                        hex(
                            "AC4032EF4F2D9AE39DF30B5C8FFDAC506CDEBE7B89998CAF74866A08CFE4FFE3",
                            "A6824A4E10B9A6F0DD921F01A70C4AFAAB739D7700C29F52C57DB17C620A8652",
                            "BE5E9001A8D66AD7C17669101999024AF4D027275AC1348BB8A762D0521BC98A",
                            "E247150422EA1ED409939D54DA7460CDB5F6C6B250717CBEF180EB34118E98D1",
                            "19529A45D6F834566E3025E316A330EFBB77A86F0C1AB15B051AE3D428C8F8AC",
                            "B70A8137150B8EEB10E183EDD19963DDD9E263E4770589EF6AA21E7F5F2FF381",
                            "B539CCE3409D13CD566AFBB48D6C019181E1BCFE94B30269EDFE72FE9B6AA4BD",
                            "7B5A0F1C71CFFF4C19C418E1F6EC017981BC087F2A7065B384B890D3191F2BFA",
                        ),
                    q = hex("801C0D34C58D93FE997177101F80535A4738CEBCBF389A99B36371EB"),
                )
            }

            // 2048-bit MODP Group with 256-bit Prime Order Subgroup: RFC 5114, section 2.3
            2 -> {
                DhDomainParameters(
                    p =
                        hex(
                            "87A8E61DB4B6663CFFBBD19C651959998CEEF608660DD0F25D2CEED4435E3B00",
                            "E00DF8F1D61957D4FAF7DF4561B2AA3016C3D91134096FAA3BF4296D830E9A7C",
                            "209E0C6497517ABD5A8A9D306BCF67ED91F9E6725B4758C022E0B1EF4275BF7B",
                            "6C5BFC11D45F9088B941F54EB1E59BB8BC39A0BF12307F5C4FDB70C581B23F76",
                            "B63ACAE1CAA6B7902D52526735488A0EF13C6D9A51BFA4AB3AD8347796524D8E",
                            "F6A167B5A41825D967E144E5140564251CCACB83E6B486F6B3CA3F7971506026",
                            "C0B857F689962856DED4010ABD0BE621C3A3960A54E710C375F26375D7014103",
                            "A4B54330C198AF126116D2276E11715F693877FAD7EF09CADB094AE91E1A1597",
                        ),
                    g =
                        hex(
                            "3FB32C9B73134D0B2E77506660EDBD484CA7B18F21EF205407F4793A1A0BA125",
                            "10DBC15077BE463FFF4FED4AAC0BB555BE3A6C1B0C6B47B1BC3773BF7E8C6F62",
                            "901228F8C28CBB18A55AE31341000A650196F931C77A57F2DDF463E5E9EC144B",
                            "777DE62AAAB8A8628AC376D282D6ED3864E67982428EBC831D14348F6F2F9193",
                            "B5045AF2767164E1DFC967C1FB3F2E55A4BD1BFFE83B9C80D052B985D182EA0A",
                            "DB2A3B7313D3FE14C8484B1E052588B9B7D2BBD2DF016199ECD06E1557CD0915",
                            "B3353BBB64E0EC377FD028370DF92B52C7891428CDC67EB6184B523D1DB246C3",
                            "2F63078490F00EF8D647D148D47954515E2327CFEF98C582664B4C0F6CC41659",
                        ),
                    q = hex("8CF83642A709A097B447997640129DA299B1A47D1EB3750BA308B0FE64F5FBD3"),
                )
            }

            // NIST P-192 (secp192r1): RFC 5114, section 2.4
            8 -> {
                EcDomainParameters(
                    p = hex("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFFFFFFFFFFFF"),
                    a = hex("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFFFFFFFFFFFC"),
                    b = hex("64210519E59C80E70FA7E9AB72243049FEB8DEECC146B9B1"),
                    generator =
                        EcPoint(
                            x = hex("188DA80EB03090F67CBF20EB43A18800F4FF0AFD82FF1012"),
                            y = hex("07192B95FFC8DA78631011ED6B24CDD573F977A11E794811"),
                        ),
                    n = hex("FFFFFFFFFFFFFFFFFFFFFFFF99DEF836146BC9B1B4D22831"),
                    cofactor = hex("01"),
                )
            }

            // BrainpoolP192r1: RFC 5639, section 3.2
            9 -> {
                EcDomainParameters(
                    p = hex("C302F41D932A36CDA7A3463093D18DB78FCE476DE1A86297"),
                    a = hex("6A91174076B1E0E19C39C031FE8685C1CAE040E5C69A28EF"),
                    b = hex("469A28EF7C28CCA3DC721D044F4496BCCA7EF4146FBF25C9"),
                    generator =
                        EcPoint(
                            x = hex("C0A0647EAAB6A48753B033C56CB0F0900A2F5C4853375FD6"),
                            y = hex("14B690866ABD5BB88B5F4828C1490002E6773FA2FA299B8F"),
                        ),
                    n = hex("C302F41D932A36CDA7A3462F9E9E916B5BE8F1029AC4ACC1"),
                    cofactor = hex("01"),
                )
            }

            // NIST P-224 (secp224r1): RFC 5114, section 2.5
            10 -> {
                EcDomainParameters(
                    p = hex("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF000000000000000000000001"),
                    a = hex("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFE"),
                    b = hex("B4050A850C04B3ABF54132565044B0B7D7BFD8BA270B39432355FFB4"),
                    generator =
                        EcPoint(
                            x = hex("B70E0CBD6BB4BF7F321390B94A03C1D356C21122343280D6115C1D21"),
                            y = hex("BD376388B5F723FB4C22DFE6CD4375A05A07476444D5819985007E34"),
                        ),
                    n = hex("FFFFFFFFFFFFFFFFFFFFFFFFFFFF16A2E0B8F03E13DD29455C5C2A3D"),
                    cofactor = hex("01"),
                )
            }

            // BrainpoolP224r1: RFC 5639, section 3.3
            11 -> {
                EcDomainParameters(
                    p = hex("D7C134AA264366862A18302575D1D787B09F075797DA89F57EC8C0FF"),
                    a = hex("68A5E62CA9CE6C1C299803A6C1530B514E182AD8B0042A59CAD29F43"),
                    b = hex("2580F63CCFE44138870713B1A92369E33E2135D266DBB372386C400B"),
                    generator =
                        EcPoint(
                            x = hex("0D9029AD2C7E5CF4340823B2A87DC68C9E4CE3174C1E6EFDEE12C07D"),
                            y = hex("58AA56F772C0726F24C6B89E4ECDAC24354B9E99CAA3F6D3761402CD"),
                        ),
                    n = hex("D7C134AA264366862A18302575D0FB98D116BC4B6DDEBCA3A5A7939F"),
                    cofactor = hex("01"),
                )
            }

            // NIST P-256 (secp256r1): RFC 5114, section 2.6
            12 -> {
                EcDomainParameters(
                    p = hex("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF"),
                    a = hex("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC"),
                    b = hex("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B"),
                    generator =
                        EcPoint(
                            x = hex("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296"),
                            y = hex("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5"),
                        ),
                    n = hex("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551"),
                    cofactor = hex("01"),
                )
            }

            // BrainpoolP256r1: RFC 5639, section 3.4
            13 -> {
                EcDomainParameters(
                    p = hex("A9FB57DBA1EEA9BC3E660A909D838D726E3BF623D52620282013481D1F6E5377"),
                    a = hex("7D5A0975FC2C3057EEF67530417AFFE7FB8055C126DC5C6CE94A4B44F330B5D9"),
                    b = hex("26DC5C6CE94A4B44F330B5D9BBD77CBF958416295CF7E1CE6BCCDC18FF8C07B6"),
                    generator =
                        EcPoint(
                            x = hex("8BD2AEB9CB7E57CB2C4B482FFC81B7AFB9DE27E1E3BD23C23A4453BD9ACE3262"),
                            y = hex("547EF835C3DAC4FD97F8461A14611DC9C27745132DED8E545C1D54C72F046997"),
                        ),
                    n = hex("A9FB57DBA1EEA9BC3E660A909D838D718C397AA3B561A6F7901E0E82974856A7"),
                    cofactor = hex("01"),
                )
            }

            // BrainpoolP320r1: RFC 5639, section 3.5
            14 -> {
                EcDomainParameters(
                    p =
                        hex(
                            "D35E472036BC4FB7E13C785ED201E065F98FCFA6F6F40DEF4F92B9EC7893EC28",
                            "FCD412B1F1B32E27",
                        ),
                    a =
                        hex(
                            "3EE30B568FBAB0F883CCEBD46D3F3BB8A2A73513F5EB79DA66190EB085FFA9F4",
                            "92F375A97D860EB4",
                        ),
                    b =
                        hex(
                            "520883949DFDBC42D3AD198640688A6FE13F41349554B49ACC31DCCD88453981",
                            "6F5EB4AC8FB1F1A6",
                        ),
                    generator =
                        EcPoint(
                            x =
                                hex(
                                    "43BD7E9AFB53D8B85289BCC48EE5BFE6F20137D10A087EB6E7871E2A10A599C7",
                                    "10AF8D0D39E20611",
                                ),
                            y =
                                hex(
                                    "14FDD05545EC1CC8AB4093247F77275E0743FFED117182EAA9C77877AAAC6AC7",
                                    "D35245D1692E8EE1",
                                ),
                        ),
                    n =
                        hex(
                            "D35E472036BC4FB7E13C785ED201E065F98FCFA5B68F12A32D482EC7EE8658E9",
                            "8691555B44C59311",
                        ),
                    cofactor = hex("01"),
                )
            }

            // NIST P-384 (secp384r1): RFC 5114, section 2.7
            15 -> {
                EcDomainParameters(
                    p =
                        hex(
                            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFE",
                            "FFFFFFFF0000000000000000FFFFFFFF",
                        ),
                    a =
                        hex(
                            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFE",
                            "FFFFFFFF0000000000000000FFFFFFFC",
                        ),
                    b =
                        hex(
                            "B3312FA7E23EE7E4988E056BE3F82D19181D9C6EFE8141120314088F5013875A",
                            "C656398D8A2ED19D2A85C8EDD3EC2AEF",
                        ),
                    generator =
                        EcPoint(
                            x =
                                hex(
                                    "AA87CA22BE8B05378EB1C71EF320AD746E1D3B628BA79B9859F741E082542A38",
                                    "5502F25DBF55296C3A545E3872760AB7",
                                ),
                            y =
                                hex(
                                    "3617DE4A96262C6F5D9E98BF9292DC29F8F41DBD289A147CE9DA3113B5F0B8C0",
                                    "0A60B1CE1D7E819D7A431D7C90EA0E5F",
                                ),
                        ),
                    n =
                        hex(
                            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFC7634D81F4372DDF",
                            "581A0DB248B0A77AECEC196ACCC52973",
                        ),
                    cofactor = hex("01"),
                )
            }

            // BrainpoolP384r1: RFC 5639, section 3.6
            16 -> {
                EcDomainParameters(
                    p =
                        hex(
                            "8CB91E82A3386D280F5D6F7E50E641DF152F7109ED5456B412B1DA197FB71123",
                            "ACD3A729901D1A71874700133107EC53",
                        ),
                    a =
                        hex(
                            "7BC382C63D8C150C3C72080ACE05AFA0C2BEA28E4FB22787139165EFBA91F90F",
                            "8AA5814A503AD4EB04A8C7DD22CE2826",
                        ),
                    b =
                        hex(
                            "04A8C7DD22CE28268B39B55416F0447C2FB77DE107DCD2A62E880EA53EEB62D5",
                            "7CB4390295DBC9943AB78696FA504C11",
                        ),
                    generator =
                        EcPoint(
                            x =
                                hex(
                                    "1D1C64F068CF45FFA2A63A81B7C13F6B8847A3E77EF14FE3DB7FCAFE0CBD10E8",
                                    "E826E03436D646AAEF87B2E247D4AF1E",
                                ),
                            y =
                                hex(
                                    "8ABE1D7520F9C2A45CB1EB8E95CFD55262B70B29FEEC5864E19C054FF9912928",
                                    "0E4646217791811142820341263C5315",
                                ),
                        ),
                    n =
                        hex(
                            "8CB91E82A3386D280F5D6F7E50E641DF152F7109ED5456B31F166E6CAC0425A7",
                            "CF3AB6AF6B7FC3103B883202E9046565",
                        ),
                    cofactor = hex("01"),
                )
            }

            // BrainpoolP512r1: RFC 5639, section 3.7
            17 -> {
                EcDomainParameters(
                    p =
                        hex(
                            "AADD9DB8DBE9C48B3FD4E6AE33C9FC07CB308DB3B3C9D20ED6639CCA70330871",
                            "7D4D9B009BC66842AECDA12AE6A380E62881FF2F2D82C68528AA6056583A48F3",
                        ),
                    a =
                        hex(
                            "7830A3318B603B89E2327145AC234CC594CBDD8D3DF91610A83441CAEA9863BC",
                            "2DED5D5AA8253AA10A2EF1C98B9AC8B57F1117A72BF2C7B9E7C1AC4D77FC94CA",
                        ),
                    b =
                        hex(
                            "3DF91610A83441CAEA9863BC2DED5D5AA8253AA10A2EF1C98B9AC8B57F1117A7",
                            "2BF2C7B9E7C1AC4D77FC94CADC083E67984050B75EBAE5DD2809BD638016F723",
                        ),
                    generator =
                        EcPoint(
                            x =
                                hex(
                                    "81AEE4BDD82ED9645A21322E9C4C6A9385ED9F70B5D916C1B43B62EEF4D0098E",
                                    "FF3B1F78E2D0D48D50D1687B93B97D5F7C6D5047406A5E688B352209BCB9F822",
                                ),
                            y =
                                hex(
                                    "7DDE385D566332ECC0EABFA9CF7822FDF209F70024A57B1AA000C55B881F8111",
                                    "B2DCDE494A5F485E5BCA4BD88A2763AED1CA2B2FA8F0540678CD1E0F3AD80892",
                                ),
                        ),
                    n =
                        hex(
                            "AADD9DB8DBE9C48B3FD4E6AE33C9FC07CB308DB3B3C9D20ED6639CCA70330870",
                            "553E5C414CA92619418661197FAC10471DB1D381085DDADDB58796829CA90069",
                        ),
                    cofactor = hex("01"),
                )
            }

            // NIST P-521 (secp521r1): RFC 5114, section 2.8
            18 -> {
                EcDomainParameters(
                    p =
                        hex(
                            "01FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
                            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
                            "FFFF",
                        ),
                    a =
                        hex(
                            "01FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
                            "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
                            "FFFC",
                        ),
                    b =
                        hex(
                            "51953EB9618E1C9A1F929A21A0B68540EEA2DA725B99B315F3B8B489918EF109",
                            "E156193951EC7E937B1652C0BD3BB1BF073573DF883D2C34F1EF451FD46B503F",
                            "00",
                        ),
                    generator =
                        EcPoint(
                            x =
                                hex(
                                    "C6858E06B70404E9CD9E3ECB662395B4429C648139053FB521F828AF606B4D3D",
                                    "BAA14B5E77EFE75928FE1DC127A2FFA8DE3348B3C1856A429BF97E7E31C2E5BD",
                                    "66",
                                ),
                            y =
                                hex(
                                    "011839296A789A3BC0045C8A5FB42C7D1BD998F54449579B446817AFBD17273E",
                                    "662C97EE72995EF42640C550B9013FAD0761353C7086A272C24088BE94769FD1",
                                    "6650",
                                ),
                        ),
                    n =
                        hex(
                            "01FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
                            "FFFA51868783BF2F966B7FCC0148F709A5D03BB5C9B8899C47AEBB6FB71E9138",
                            "6409",
                        ),
                    cofactor = hex("01"),
                )
            }

            else -> {
                null
            }
        }
}
