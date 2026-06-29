package io.lightine.tessera.mrz.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Simulator tests for [assembleCaptureMetadata] — the pure metadata-assembly logic the iOS
 * [ImageIoCaptureMetadataReader] runs on a `CIImage.properties` dictionary. Driven by synthetic property
 * maps (mirroring the documented `kCGImageProperty…` structure: nested `{Exif}` / `{TIFF}` / `{GPS}`
 * dictionaries, GPS as a positive magnitude + a N/S, E/W reference, numbers as `NSNumber`), so the
 * dictionary navigation, GPS sign-from-reference, and read-suppression are verified without needing a real
 * image. (That the platform actually presents this structure is a device-test concern.)
 */
class ImageIoCaptureMetadataReaderTest {
    private val propsWithGps: Map<Any?, Any?> =
        mapOf(
            "{Exif}" to mapOf<Any?, Any?>("DateTimeOriginal" to "2026:05:04 12:00:00"),
            "{TIFF}" to mapOf<Any?, Any?>("Make" to "ACME", "Model" to "Phone X", "Software" to "Cam 1.0"),
            "{GPS}" to
                mapOf<Any?, Any?>(
                    // Kotlin Double here; real CIImage returns NSNumber, which the reader's asDouble also accepts.
                    "Latitude" to 40.0,
                    "LatitudeRef" to "S",
                    "Longitude" to 3.0,
                    "LongitudeRef" to "W",
                ),
        )

    @Test
    fun excluding_location_reads_fields_but_never_gps() {
        val metadata = assembleCaptureMetadata(propsWithGps, CaptureMetadataPolicy.EXCLUDING_LOCATION)

        assertEquals("2026:05:04 12:00:00", metadata.reportedCaptureTime)
        assertEquals("ACME", metadata.deviceMake)
        assertEquals("Phone X", metadata.deviceModel)
        assertEquals("Cam 1.0", metadata.software)
        assertNull(metadata.location, "EXCLUDING_LOCATION must not read GPS")
        // The raw-tag map carries the non-GPS fields and never GPS.
        val raw = assertNotNull(metadata.rawTags)
        assertTrue(raw.keys.none { it == "Latitude" || it == "Longitude" }, "raw tags must not include GPS")
    }

    @Test
    fun raw_tags_are_a_curated_allowlist_excluding_arbitrary_identifying_fields() {
        val props =
            mapOf<Any?, Any?>(
                "{Exif}" to
                    mapOf<Any?, Any?>(
                        "DateTimeOriginal" to "2026:05:04 12:00:00",
                        "PixelXDimension" to 16,
                        // Identifying fields Apple may include in the Exif dictionary — must NOT be surfaced.
                        "LensModel" to "ACME 50mm f/1.8",
                        "BodySerialNumber" to "SN-0001",
                    ),
                "{TIFF}" to mapOf<Any?, Any?>("Make" to "ACME", "CameraOwnerName" to "A. Person"),
            )

        val raw = assertNotNull(assembleCaptureMetadata(props, CaptureMetadataPolicy.EXCLUDING_LOCATION).rawTags)

        // The curated descriptive tags are kept (the same non-GPS set Android collects)...
        assertTrue("DateTimeOriginal" in raw)
        assertTrue("Make" in raw)
        assertTrue("PixelXDimension" in raw)
        // ...but arbitrary identifying tags are not (smallest-PII-surface allowlist; rawTags is not an open dump).
        assertTrue(
            raw.keys.none { it == "LensModel" || it == "BodySerialNumber" || it == "CameraOwnerName" },
            "rawTags must exclude non-allowlisted identifying EXIF/TIFF fields",
        )
    }

    @Test
    fun including_location_reads_gps_with_signed_hemisphere_refs() {
        val metadata = assembleCaptureMetadata(propsWithGps, CaptureMetadataPolicy.INCLUDING_LOCATION)

        val location = assertNotNull(metadata.location)
        assertEquals(-40.0, location.latitude) // S reference → negative latitude
        assertEquals(-3.0, location.longitude) // W reference → negative longitude
    }

    @Test
    fun missing_dictionaries_yield_null_fields() {
        val metadata = assembleCaptureMetadata(emptyMap<Any?, Any?>(), CaptureMetadataPolicy.INCLUDING_LOCATION)

        assertNull(metadata.reportedCaptureTime)
        assertNull(metadata.deviceMake)
        assertNull(metadata.location)
        assertNull(metadata.rawTags)
    }

    @Test
    fun null_properties_yield_empty_metadata() {
        val metadata = assembleCaptureMetadata(null, CaptureMetadataPolicy.INCLUDING_LOCATION)

        assertNull(metadata.reportedCaptureTime)
        assertNull(metadata.location)
        assertNull(metadata.rawTags)
    }
}
