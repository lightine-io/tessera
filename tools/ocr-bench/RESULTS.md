# OCR benchmark: vendor text recognition on synthetic OCR-B MRZ (TES-149)

Measured 2026-11-21. Reproduce with `tools/ocr-bench/run.sh` (see the script header).

## Test set

- 60 synthetic MRZs from Tessera's own `MrzGenerator` (20 each of TD3, TD1, TD2), invented
  names and numbers, issuing state UTO, seed 149. No real document data.
- Each MRZ drawn in OCR-B at a 22 px character pitch (about what the MRZ band gets from a
  1080p frame), dark ink on off-white paper with fine noise. Five conditions per MRZ:
  clean, blur (Gaussian, sigma 1.6 to 2.4 px), glare (a white highlight at 75 to 90 percent
  over part of the text), skew (3 to 6 degrees plus a mild keystone), and combined (all three,
  milder). 300 images in all.
- The OCR-B font is the one inside the iOS Simulator runtime, used in place. It is never
  copied into the repo, and no image is committed.

## Engines

- ML Kit text recognition 16.0.1, bundled Latin model, default options (as `mrz-camera`
  uses it), on the Android 17 emulator (Pixel_9_API37_Play).
- Apple Vision `VNRecognizeTextRequest`, no language correction, at the accurate level (the
  live path in `mrz-camera-ios`) and the fast level (the saved-image rescue pass), in the
  iOS 27.2 Simulator. The same code on macOS 27.2 is listed for comparison.
- An open OCR-B or MRZ model through LiteRT or ONNX Runtime: not measured. No such model is
  on this machine, and getting one means downloading a model file, which needs the owner's
  approval first.

Every engine's line pieces are joined into lines by the same geometry in `score.py`, so the
numbers compare recognizers, not line grouping. Spaces are dropped; nothing else is changed.

## Results

Each cell: character error rate / share of images with every MRZ line exact.

Every difference counts:

| Engine | clean | blur | glare | skew | combined | all |
|---|---|---|---|---|---|---|
| ML Kit, Android emulator | 6.0% / 8% | 12.8% / 0% | 4.5% / 5% | 8.4% / 8% | 10.9% / 0% | 8.5% / 4% |
| Vision accurate, iOS Simulator | 11.0% / 0% | 7.2% / 0% | 14.5% / 0% | 2.9% / 2% | 1.9% / 3% | 7.5% / 1% |
| Vision fast, iOS Simulator | 1.5% / 0% | 8.4% / 0% | 8.9% / 0% | 1.6% / 0% | 4.0% / 0% | 4.9% / 0% |
| Vision accurate, macOS | 14.6% / 0% | 5.6% / 0% | 16.6% / 2% | 2.2% / 2% | 2.0% / 2% | 8.2% / 1% |
| Vision fast, macOS | 1.5% / 0% | 8.4% / 0% | 8.9% / 0% | 1.6% / 0% | 4.0% / 0% | 4.9% / 0% |

Letter O and number 0 counted as one character:

| Engine | clean | blur | glare | skew | combined | all |
|---|---|---|---|---|---|---|
| ML Kit, Android emulator | 5.5% / 12% | 12.4% / 0% | 4.0% / 7% | 7.9% / 17% | 10.6% / 0% | 8.1% / 7% |
| Vision accurate, iOS Simulator | 9.6% / 20% | 5.7% / 23% | 13.0% / 18% | 1.5% / 87% | 0.7% / 75% | 6.1% / 45% |
| Vision fast, iOS Simulator | 0.0% / 100% | 6.9% / 10% | 7.7% / 15% | 0.1% / 97% | 2.5% / 22% | 3.4% / 49% |
| Vision accurate, macOS | 13.3% / 12% | 4.1% / 50% | 15.3% / 13% | 0.8% / 92% | 0.5% / 98% | 6.8% / 53% |
| Vision fast, macOS | 0.0% / 100% | 6.9% / 10% | 7.7% / 15% | 0.1% / 97% | 2.5% / 22% | 3.4% / 49% |

## What the errors are

- O and 0: both vendors read the letter O as the number 0 (and the reverse) on most images.
  Every MRZ here holds the letter O at least twice (UTO), so no engine reads a whole MRZ
  exactly without this fold.
- Filler: most of the rest is the `<` filler. ML Kit turns `<` runs into `«` or `K`, or
  drops them. Vision at the accurate level drops whole filler runs on clean and glare images
  (its language model collapses them); at the fast level it keeps them.
- Blur: letter shapes break (fast: R as II, M as PI, B as 8; ML Kit: 5 as S).
- Vision accurate reads skewed images better than straight ones. That is how it behaved on
  this set; the cause is not known.

## Footprint

| Piece | Size |
|---|---|
| ML Kit bundled Latin recognizer | 11.1 MB native library per ABI (arm64-v8a) plus 1.5 MB model assets, uncompressed |
| Apple Vision | 0 (part of the OS) |
| LiteRT 2.4.0 runtime | 8.1 MB AAR, all ABIs (Google Maven) |
| ONNX Runtime Android 1.32.0 | 59.3 MB AAR, all ABIs (Maven Central) |
| An own OCR-B line model | not built, so not known |

## Limits

- Emulator and simulator only. Real camera frames (focus hunting, motion blur, compression,
  real glare on laminate) are not covered; only a physical device can confirm them.
- Synthetic rendering with one OCR-B font. The images are cleaner than real prints in
  some ways (no print defects, no background artwork) and harsher in others.
- 60 MRZs; differences under about one point are within noise. Vision accurate gives
  slightly different results in the Simulator and on macOS.
