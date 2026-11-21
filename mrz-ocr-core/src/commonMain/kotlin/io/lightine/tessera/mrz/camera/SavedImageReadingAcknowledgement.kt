package io.lightine.tessera.mrz.camera

/**
 * The caller's acknowledgement that enables saved-image MRZ reading.
 *
 * Every saved-image entry point takes one as a required argument with no default. Passing it records
 * that you considered the extra risk of saved images. It says nothing about any one image.
 *
 * @since 0.3.0
 */
public class SavedImageReadingAcknowledgement
