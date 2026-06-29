package io.lightine.tessera.mrz.camera

/**
 * The consumer's acknowledgement that enables saved-image MRZ reading.
 *
 * Reading an MRZ from a **saved image** (a photo file, a scan, any pre-existing image) is **disabled by
 * default**, because a saved image carries meaningfully more risk than a live capture: the application
 * loses the implicit guarantee that the data was captured "just now" through hardware it controls — the
 * image can be old, replayed, edited, or sourced from anywhere
 * ([reading-risks](https://lightine.youtrack.cloud/articles/TES-A-11)).
 *
 * Every saved-image entry point (the [SavedImageMrzReader] and the platform recognizer factories) takes
 * one of these as a **required argument with no default**: the safe default is "cannot read saved images,"
 * so there is none. Constructing and passing it is the consumer's explicit acknowledgement that they have
 * considered those risks. It is **not** an SDK judgement about any image — passing it confers nothing about
 * a given image's authenticity, source, or freshness; the SDK remains a reader, not an oracle (Principle 1).
 * See [ADR-023](https://lightine.youtrack.cloud/articles/TES-A-63).
 */
public class SavedImageReadingAcknowledgement
