// Renders the OCR benchmark images (TES-149) from the generator's manifest: each MRZ in OCR-B,
// once per condition (clean, blur, glare, skew, combined). Deterministic: the same manifest and
// seed give the same pixels.
//
// Usage: swift render.swift <manifest.tsv> <ocrb-font-file> <out-dir> [seed [lines-dir]]
//
// With lines-dir it also writes one crop per MRZ line (`<id>__<condition>__<n>.png`), cut by the
// geometry the renderer knows (the line box carried through the skew and keystone), for engines
// that read single text lines (the ONNX baseline).
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
let linesDir: String? = args.count > 5 ? args[5] : nil

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

typealias PointMap = (CGPoint) -> CGPoint

/// Solves the homography that sends four points to four points (8x8 Gauss elimination).
func homography(_ src: [CGPoint], _ dst: [CGPoint]) -> PointMap {
    var m = [[Double]](repeating: [Double](repeating: 0, count: 9), count: 8)
    for i in 0 ..< 4 {
        let x = Double(src[i].x), y = Double(src[i].y), u = Double(dst[i].x), v = Double(dst[i].y)
        m[2 * i] = [x, y, 1, 0, 0, 0, -u * x, -u * y, u]
        m[2 * i + 1] = [0, 0, 0, x, y, 1, -v * x, -v * y, v]
    }
    for c in 0 ..< 8 {
        let p = (c ..< 8).max { abs(m[$0][c]) < abs(m[$1][c]) }!
        m.swapAt(c, p)
        for r in 0 ..< 8 where r != c {
            let f = m[r][c] / m[c][c]
            for k in c ... 8 { m[r][k] -= f * m[c][k] }
        }
    }
    let h = (0 ..< 8).map { m[$0][8] / m[$0][$0] } + [1]
    return { p in
        let x = Double(p.x), y = Double(p.y)
        let w = h[6] * x + h[7] * y + h[8]
        return CGPoint(x: (h[0] * x + h[1] * y + h[2]) / w, y: (h[3] * x + h[4] * y + h[5]) / w)
    }
}

func skew(_ image: CIImage, _ rng: inout Rng, degrees: Double, keystone: Double) -> (CIImage, PointMap) {
    let e = image.extent
    let paper = CIImage(color: CIColor(red: 0.5, green: 0.5, blue: 0.52)).cropped(to: e)
    let angle = (rng.unit() < 0.5 ? -1.0 : 1.0) * degrees * .pi / 180
    let rotated = image.transformed(by: CGAffineTransform(translationX: e.midX, y: e.midY)
        .rotated(by: angle).translatedBy(x: -e.midX, y: -e.midY))
    // A mild keystone: one end of the band nearer the lens than the other.
    let k = e.height * keystone
    let tl = CGPoint(x: e.minX, y: e.maxY + k), tr = CGPoint(x: e.maxX, y: e.maxY - k)
    let bl = CGPoint(x: e.minX, y: e.minY - k), br = CGPoint(x: e.maxX, y: e.minY + k)
    let perspective = rotated.applyingFilter("CIPerspectiveTransform", parameters: [
        "inputTopLeft": CIVector(cgPoint: tl),
        "inputTopRight": CIVector(cgPoint: tr),
        "inputBottomLeft": CIVector(cgPoint: bl),
        "inputBottomRight": CIVector(cgPoint: br),
    ])
    // Where a point of the clean image ends up: the rotation, then the perspective, which sends
    // the rotated image's own corners to the four points above.
    let r = rotated.extent
    let toRotated = CGAffineTransform(translationX: e.midX, y: e.midY)
        .rotated(by: angle).translatedBy(x: -e.midX, y: -e.midY)
    let persp = homography(
        [CGPoint(x: r.minX, y: r.maxY), CGPoint(x: r.maxX, y: r.maxY), CGPoint(x: r.minX, y: r.minY), CGPoint(x: r.maxX, y: r.minY)],
        [tl, tr, bl, br])
    return (perspective.composited(over: paper).cropped(to: e), { persp($0.applying(toRotated)) })
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
    let cleanHeight = clean.extent.height
    for condition in conditions {
        var rng = Rng(state: seed(for: id, condition))
        let image: CIImage
        var map: PointMap = { $0 }
        switch condition {
        case "blur": image = blur(clean, rng.range(1.6, 2.4))
        case "glare": image = glare(clean, &rng, strength: rng.range(0.75, 0.9))
        case "skew":
            (image, map) = skew(clean, &rng, degrees: rng.range(3, 6), keystone: rng.range(0.03, 0.06))
        case "combined":
            let (skewed, skewMap) = skew(clean, &rng, degrees: rng.range(2, 4), keystone: 0.03)
            map = skewMap
            image = blur(glare(skewed, &rng, strength: 0.7), 1.4)
        default: image = clean
        }
        write(image, "\(outDir)/\(id)__\(condition).png")
        if let linesDir {
            try FileManager.default.createDirectory(atPath: linesDir, withIntermediateDirectories: true)
            for (n, line) in lines.enumerated() {
                // The line box in the clean image: its characters plus half a pitch each side,
                // and a quarter of the cap height above and below.
                let baseline = cleanHeight - margin - capHeight - CGFloat(n) * linePitch
                let x0 = margin - pitch / 2, x1 = margin + CGFloat(line.count) * pitch + pitch / 2
                let y0 = baseline - capHeight * 0.25, y1 = baseline + capHeight * 1.25
                let q = [CGPoint(x: x0, y: y1), CGPoint(x: x1, y: y1), CGPoint(x: x1, y: y0), CGPoint(x: x0, y: y0)].map(map)
                let crop = image.applyingFilter("CIPerspectiveCorrection", parameters: [
                    "inputTopLeft": CIVector(cgPoint: q[0]), "inputTopRight": CIVector(cgPoint: q[1]),
                    "inputBottomRight": CIVector(cgPoint: q[2]), "inputBottomLeft": CIVector(cgPoint: q[3]),
                ])
                write(crop, "\(linesDir)/\(id)__\(condition)__\(n).png")
            }
        }
        count += 1
    }
}
print("rendered \(count) images to \(outDir)")
