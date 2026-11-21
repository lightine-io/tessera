// Renders the OCR benchmark images (TES-149) from the generator's manifest: each MRZ in OCR-B,
// once per condition (clean, blur, glare, skew, combined). Deterministic: the same manifest and
// seed give the same pixels.
//
// Usage: swift render.swift <manifest.tsv> <ocrb-font-file> <out-dir> [seed]
//
// The OCR-B font is not in this repo and must never be copied into it. run.sh finds the one that
// ships inside the iOS Simulator runtime and passes its path.

import CoreGraphics
import CoreImage
import CoreText
import Foundation
import ImageIO
import UniformTypeIdentifiers

let args = CommandLine.arguments
guard args.count >= 4 else {
    FileHandle.standardError.write("usage: render.swift <manifest.tsv> <ocrb-font> <out-dir> [seed]\n".data(using: .utf8)!)
    exit(2)
}
let manifestPath = args[1], fontPath = args[2], outDir = args[3]
let baseSeed = args.count > 4 ? UInt64(args[4])! : 149

// SplitMix64: a small, stable PRNG so the images do not depend on the platform's random source.
struct Rng {
    var state: UInt64
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
    mutating func unit() -> Double { Double(next() >> 11) / Double(1 << 53) }
    mutating func range(_ lo: Double, _ hi: Double) -> Double { lo + (hi - lo) * unit() }
}

func seed(for id: String, _ condition: String) -> UInt64 {
    var h: UInt64 = baseSeed
    for b in (id + "/" + condition).utf8 { h = (h ^ UInt64(b)) &* 0x100_0000_01B3 }
    return h
}

guard let descriptors = CTFontManagerCreateFontDescriptorsFromURL(URL(fileURLWithPath: fontPath) as CFURL) as? [CTFontDescriptor],
      let descriptor = descriptors.first
else {
    FileHandle.standardError.write("cannot load font at \(fontPath)\n".data(using: .utf8)!)
    exit(2)
}

// Geometry. A character pitch of 22 px is about what the MRZ band gets from a 1080p frame with a
// passport page filling the width (44 characters over about 1000 px).
let pitch: CGFloat = 22
let font = CTFontCreateWithFontDescriptor(descriptor, 1, nil)
var glyph = CGGlyph()
var zero: UniChar = 0x30
CTFontGetGlyphsForCharacters(font, &zero, &glyph, 1)
var advance = CGSize.zero
CTFontGetAdvancesForGlyphs(font, .horizontal, &glyph, &advance, 1)
let fontSize = pitch / advance.width
let sizedFont = CTFontCreateWithFontDescriptor(descriptor, fontSize, nil)
let capHeight = CTFontGetCapHeight(sizedFont)
let linePitch = pitch * 1.67 // Doc 9303: 4.23 mm line pitch for a 2.54 mm character pitch
let margin: CGFloat = 3 * pitch

let ciContext = CIContext(options: [.workingColorSpace: CGColorSpace(name: CGColorSpace.sRGB)!])
let srgb = CGColorSpace(name: CGColorSpace.sRGB)!

func renderClean(_ lines: [String], _ rng: inout Rng) -> CGImage {
    let columns = lines.map(\.count).max()!
    let width = Int(CGFloat(columns) * pitch + 2 * margin)
    let height = Int(CGFloat(lines.count - 1) * linePitch + capHeight + 2 * margin)
    let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                        space: srgb, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    // Paper: off-white with fine per-pixel noise.
    let buffer = ctx.data!.bindMemory(to: UInt8.self, capacity: ctx.bytesPerRow * height)
    for y in 0 ..< height {
        for x in 0 ..< width {
            let n = rng.range(-6, 6)
            let o = y * ctx.bytesPerRow + x * 4
            buffer[o] = UInt8(max(0, min(255, 236 + n)))
            buffer[o + 1] = UInt8(max(0, min(255, 234 + n)))
            buffer[o + 2] = UInt8(max(0, min(255, 226 + n)))
            buffer[o + 3] = 255
        }
    }
    let ink = CGColor(srgbRed: 0.09, green: 0.09, blue: 0.11, alpha: 1)
    for (i, line) in lines.enumerated() {
        let attributed = NSAttributedString(string: line, attributes: [
            NSAttributedString.Key(kCTFontAttributeName as String): sizedFont,
            NSAttributedString.Key(kCTForegroundColorAttributeName as String): ink,
        ])
        let ctLine = CTLineCreateWithAttributedString(attributed)
        let baseline = CGFloat(height) - margin - capHeight - CGFloat(i) * linePitch
        ctx.textPosition = CGPoint(x: margin, y: baseline)
        CTLineDraw(ctLine, ctx)
    }
    return ctx.makeImage()!
}

func blur(_ image: CIImage, _ sigma: Double) -> CIImage {
    image.clampedToExtent().applyingGaussianBlur(sigma: sigma).cropped(to: image.extent)
}

func glare(_ image: CIImage, _ rng: inout Rng, strength: Double) -> CIImage {
    let e = image.extent
    let center = CIVector(x: e.minX + e.width * rng.range(0.25, 0.75), y: e.minY + e.height * rng.range(0.3, 0.7))
    let radius = e.width * rng.range(0.12, 0.2)
    let gradient = CIFilter(name: "CIRadialGradient", parameters: [
        "inputCenter": center,
        "inputRadius0": radius * 0.35,
        "inputRadius1": radius,
        "inputColor0": CIColor(red: 1, green: 1, blue: 1, alpha: strength),
        "inputColor1": CIColor(red: 1, green: 1, blue: 1, alpha: 0),
    ])!.outputImage!.cropped(to: e)
    return gradient.composited(over: image)
}

func skew(_ image: CIImage, _ rng: inout Rng, degrees: Double, keystone: Double) -> CIImage {
    let e = image.extent
    let paper = CIImage(color: CIColor(red: 0.5, green: 0.5, blue: 0.52)).cropped(to: e)
    let angle = (rng.unit() < 0.5 ? -1.0 : 1.0) * degrees * .pi / 180
    let rotated = image.transformed(by: CGAffineTransform(translationX: e.midX, y: e.midY)
        .rotated(by: angle).translatedBy(x: -e.midX, y: -e.midY))
    // A mild keystone: one end of the band nearer the lens than the other.
    let k = e.height * keystone
    let perspective = rotated.applyingFilter("CIPerspectiveTransform", parameters: [
        "inputTopLeft": CIVector(x: e.minX, y: e.maxY + k),
        "inputTopRight": CIVector(x: e.maxX, y: e.maxY - k),
        "inputBottomLeft": CIVector(x: e.minX, y: e.minY - k),
        "inputBottomRight": CIVector(x: e.maxX, y: e.minY + k),
    ])
    return perspective.composited(over: paper).cropped(to: e)
}

func write(_ image: CIImage, _ path: String) {
    let cg = ciContext.createCGImage(image, from: image.extent, format: .RGBA8, colorSpace: srgb)!
    let dest = CGImageDestinationCreateWithURL(URL(fileURLWithPath: path) as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(dest, cg, nil)
    CGImageDestinationFinalize(dest)
}

let conditions = ["clean", "blur", "glare", "skew", "combined"]
try FileManager.default.createDirectory(atPath: outDir, withIntermediateDirectories: true)
let manifest = try String(contentsOfFile: manifestPath, encoding: .utf8)
var count = 0
for row in manifest.split(separator: "\n") {
    let cols = row.split(separator: "\t").map(String.init)
    let id = cols[0], lines = cols[2].split(separator: "|").map(String.init)
    var paperRng = Rng(state: seed(for: id, "paper"))
    let clean = CIImage(cgImage: renderClean(lines, &paperRng))
    for condition in conditions {
        var rng = Rng(state: seed(for: id, condition))
        let image: CIImage
        switch condition {
        case "blur": image = blur(clean, rng.range(1.6, 2.4))
        case "glare": image = glare(clean, &rng, strength: rng.range(0.75, 0.9))
        case "skew": image = skew(clean, &rng, degrees: rng.range(3, 6), keystone: rng.range(0.03, 0.06))
        case "combined":
            image = blur(glare(skew(clean, &rng, degrees: rng.range(2, 4), keystone: 0.03), &rng, strength: 0.7), 1.4)
        default: image = clean
        }
        write(image, "\(outDir)/\(id)__\(condition).png")
        count += 1
    }
}
print("rendered \(count) images to \(outDir)")
