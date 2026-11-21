// Renders the full-page photo benchmark images (TES-131) from the generator's manifest: a whole
// passport data page (TD3) or ID card (TD1) lying on a desk, photographed at phone size (3024 x 4032),
// with the MRZ in its real position on the document. Everything printed is invented: a grey
// placeholder where the portrait goes and generic labels. Deterministic: the same manifest, font and
// seed give the same pixels.
//
// Usage: swift render.swift <manifest.tsv> <ocrb-font-file> <out-dir> [seed]
//
// The OCR-B font is not in this repo and must never be copied into it. run.sh finds the one that
// ships inside the iOS Simulator runtime and passes its path (the same font the TES-149 bench uses).
//
// Per document it writes one image per variant (see `variants`) plus `<id>__mrzcrop.jpg`, a tight
// crop of the flat page around the MRZ, as the control.

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
let baseSeed = args.count > 4 ? UInt64(args[4])! : 131

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

func seed(for id: String, _ tag: String) -> UInt64 {
    var h: UInt64 = baseSeed
    for b in (id + "/" + tag).utf8 { h = (h ^ UInt64(b)) &* 0x100_0000_01B3 }
    return h
}

guard let descriptors = CTFontManagerCreateFontDescriptorsFromURL(URL(fileURLWithPath: fontPath) as CFURL) as? [CTFontDescriptor],
      let descriptor = descriptors.first
else {
    FileHandle.standardError.write("cannot load font at \(fontPath)\n".data(using: .utf8)!)
    exit(2)
}

let srgb = CGColorSpace(name: CGColorSpace.sRGB)!
let ciContext = CIContext(options: [.workingColorSpace: srgb])

// ---- The flat document, drawn at 20 px per millimetre ----------------------------------------

let mm: CGFloat = 20
struct Layout {
    let widthMM: CGFloat, heightMM: CGFloat, mrzLeftMM: CGFloat, lastBaselineFromBottomMM: CGFloat
}
let td3 = Layout(widthMM: 125, heightMM: 88, mrzLeftMM: 7.5, lastBaselineFromBottomMM: 8)
let td1 = Layout(widthMM: 85.6, heightMM: 54, mrzLeftMM: 5, lastBaselineFromBottomMM: 5)

let charPitchMM: CGFloat = 2.54 // Doc 9303 OCR-B: 10 characters per 25.4 mm
let linePitchMM: CGFloat = 4.23

func drawText(_ ctx: CGContext, _ text: String, _ x: CGFloat, _ y: CGFloat, size: CGFloat, bold: Bool = false, gray: CGFloat = 0.15) {
    let font = CTFontCreateWithName((bold ? "Helvetica-Bold" : "Helvetica") as CFString, size, nil)
    let attributed = NSAttributedString(string: text, attributes: [
        NSAttributedString.Key(kCTFontAttributeName as String): font,
        NSAttributedString.Key(kCTForegroundColorAttributeName as String): CGColor(gray: gray, alpha: 1),
    ])
    ctx.textPosition = CGPoint(x: x, y: y)
    CTLineDraw(CTLineCreateWithAttributedString(attributed), ctx)
}

func renderDocument(format: String, lines: [String], rng: inout Rng) -> CGImage {
    let layout = format == "TD3" ? td3 : td1
    let width = Int(layout.widthMM * mm), height = Int(layout.heightMM * mm)
    let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                        space: srgb, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    let H = CGFloat(height), W = CGFloat(width)
    // Paper: a pale tint with fine guilloche-like wavy lines.
    ctx.setFillColor(CGColor(srgbRed: 0.90, green: 0.93, blue: 0.95, alpha: 1))
    ctx.fill(CGRect(x: 0, y: 0, width: W, height: H))
    ctx.setStrokeColor(CGColor(srgbRed: 0.72, green: 0.80, blue: 0.86, alpha: 1))
    ctx.setLineWidth(1.5)
    for i in 0 ..< 60 {
        let y0 = CGFloat(i) * H / 60
        ctx.move(to: CGPoint(x: 0, y: y0))
        var x: CGFloat = 0
        while x < W {
            ctx.addLine(to: CGPoint(x: x, y: y0 + 18 * sin(x / 70 + CGFloat(i) * 0.4)))
            x += 12
        }
        ctx.strokePath()
    }
    // Border.
    ctx.setStrokeColor(CGColor(gray: 0.55, alpha: 1))
    ctx.setLineWidth(6)
    ctx.stroke(CGRect(x: 3, y: 3, width: W - 6, height: H - 6))

    let topY = H - 8 * mm
    if format == "TD3" {
        drawText(ctx, "PASSPORT / SPECIMEN", 8 * mm, topY, size: 3.4 * mm, bold: true)
        // Portrait placeholder: a grey block with a head and shoulders silhouette.
        let photo = CGRect(x: 8 * mm, y: H - 62 * mm, width: 35 * mm, height: 45 * mm)
        ctx.setFillColor(CGColor(gray: 0.72, alpha: 1)); ctx.fill(photo)
        ctx.setFillColor(CGColor(gray: 0.45, alpha: 1))
        ctx.fillEllipse(in: CGRect(x: photo.midX - 8 * mm, y: photo.midY, width: 16 * mm, height: 19 * mm))
        ctx.fillEllipse(in: CGRect(x: photo.minX + 3 * mm, y: photo.minY - 12 * mm, width: 29 * mm, height: 26 * mm))
        let labels = ["Type", "Code", "Surname", "Given names", "Nationality", "Date of birth", "Sex", "Date of expiry"]
        var y = topY - 9 * mm
        for (i, label) in labels.enumerated() {
            let col: CGFloat = i == 1 ? 80 : 50 // "Code" sits beside "Type"
            if i == 1 { y += 8.2 * mm }
            drawText(ctx, label, col * mm, y, size: 1.7 * mm, gray: 0.4)
            drawText(ctx, i == 2 ? "SPECIMEN" : (i == 3 ? "ALEX MORGAN" : "UTO / invented"), col * mm, y - 3.6 * mm, size: 2.8 * mm, bold: true)
            y -= 8.2 * mm
        }
    } else {
        drawText(ctx, "IDENTITY CARD / SPECIMEN", 5 * mm, H - 6 * mm, size: 2.8 * mm, bold: true)
        let photo = CGRect(x: 5 * mm, y: H - 38 * mm, width: 22 * mm, height: 27 * mm)
        ctx.setFillColor(CGColor(gray: 0.72, alpha: 1)); ctx.fill(photo)
        ctx.setFillColor(CGColor(gray: 0.45, alpha: 1))
        ctx.fillEllipse(in: CGRect(x: photo.midX - 5 * mm, y: photo.midY, width: 10 * mm, height: 12 * mm))
        ctx.fillEllipse(in: CGRect(x: photo.minX + 2 * mm, y: photo.minY - 8 * mm, width: 18 * mm, height: 16 * mm))
        var y = H - 13 * mm
        for label in ["Surname", "Given names", "Nationality", "Date of birth"] {
            drawText(ctx, label, 32 * mm, y, size: 1.5 * mm, gray: 0.4)
            drawText(ctx, label == "Surname" ? "SPECIMEN" : "invented", 32 * mm, y - 3 * mm, size: 2.4 * mm, bold: true)
            y -= 7.4 * mm
        }
    }

    // MRZ in OCR-B at the standard character pitch, lines placed from the bottom of the document.
    let pitch = charPitchMM * mm
    let probe = CTFontCreateWithFontDescriptor(descriptor, 1, nil)
    var glyph = CGGlyph(), zero: UniChar = 0x30, advance = CGSize.zero
    CTFontGetGlyphsForCharacters(probe, &zero, &glyph, 1)
    CTFontGetAdvancesForGlyphs(probe, .horizontal, &glyph, &advance, 1)
    let sized = CTFontCreateWithFontDescriptor(descriptor, pitch / advance.width, nil)
    // The MRZ zone is a plain light band, as on real documents.
    let mrzHeight = CGFloat(lines.count) * linePitchMM * mm + 2 * mm
    ctx.setFillColor(CGColor(srgbRed: 0.96, green: 0.96, blue: 0.94, alpha: 1))
    ctx.fill(CGRect(x: 4, y: layout.lastBaselineFromBottomMM * mm - 2.5 * mm - 4, width: W - 8, height: mrzHeight + 4))
    let ink = CGColor(gray: 0.1, alpha: 1)
    for (i, line) in lines.enumerated() {
        let attributed = NSAttributedString(string: line, attributes: [
            NSAttributedString.Key(kCTFontAttributeName as String): sized,
            NSAttributedString.Key(kCTForegroundColorAttributeName as String): ink,
        ])
        let baseline = (layout.lastBaselineFromBottomMM + CGFloat(lines.count - 1 - i) * linePitchMM) * mm
        ctx.textPosition = CGPoint(x: layout.mrzLeftMM * mm, y: baseline)
        CTLineDraw(CTLineCreateWithAttributedString(attributed), ctx)
    }
    return ctx.makeImage()!
}

// ---- The photo: desk, clutter, perspective, optics, light -------------------------------------

let photoW: CGFloat = 3024, photoH: CGFloat = 4032

func renderDesk(clutter: Bool, rng: inout Rng) -> CIImage {
    let ctx = CGContext(data: nil, width: Int(photoW), height: Int(photoH), bitsPerComponent: 8, bytesPerRow: 0,
                        space: srgb, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    let colors = [CGColor(srgbRed: 0.45, green: 0.32, blue: 0.22, alpha: 1), CGColor(srgbRed: 0.30, green: 0.21, blue: 0.14, alpha: 1)]
    let gradient = CGGradient(colorsSpace: srgb, colors: colors as CFArray, locations: [0, 1])!
    ctx.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: photoW, y: photoH), options: [])
    // Wood grain.
    ctx.setLineWidth(3)
    for _ in 0 ..< 220 {
        let y = rng.range(0, Double(photoH))
        ctx.setStrokeColor(CGColor(srgbRed: 0.2, green: 0.13, blue: 0.08, alpha: rng.range(0.1, 0.3)))
        ctx.move(to: CGPoint(x: 0, y: y))
        ctx.addLine(to: CGPoint(x: photoW, y: y + rng.range(-40, 40)))
        ctx.strokePath()
    }
    if clutter {
        // Papers with printed-looking text rows, coins, pens, a card: the things on a real desk.
        for _ in 0 ..< 9 {
            let w = rng.range(500, 1100), h = rng.range(600, 1400)
            let x = rng.range(-200, Double(photoW) - 300), y = rng.range(-200, Double(photoH) - 300)
            ctx.saveGState()
            ctx.translateBy(x: x + w / 2, y: y + h / 2)
            ctx.rotate(by: rng.range(-0.6, 0.6))
            ctx.setFillColor(CGColor(gray: rng.range(0.82, 0.97), alpha: 1))
            ctx.fill(CGRect(x: -w / 2, y: -h / 2, width: w, height: h))
            ctx.setFillColor(CGColor(gray: 0.25, alpha: 1))
            var ly = -h / 2 + 40
            while ly < h / 2 - 40 {
                ctx.fill(CGRect(x: -w / 2 + 40, y: ly, width: rng.range(w * 0.3, w - 80), height: 14))
                ly += 38
            }
            ctx.restoreGState()
        }
        for _ in 0 ..< 6 {
            ctx.setFillColor(CGColor(srgbRed: rng.range(0.5, 0.85), green: rng.range(0.4, 0.7), blue: 0.2, alpha: 1))
            let r = rng.range(50, 110)
            ctx.fillEllipse(in: CGRect(x: rng.range(0, Double(photoW) - 200), y: rng.range(0, Double(photoH) - 200), width: r, height: r))
        }
        ctx.setLineWidth(34)
        for _ in 0 ..< 4 {
            ctx.setStrokeColor(CGColor(srgbRed: rng.range(0, 0.4), green: rng.range(0, 0.4), blue: rng.range(0.3, 0.9), alpha: 1))
            let x = rng.range(100, Double(photoW) - 700), y = rng.range(100, Double(photoH) - 700)
            ctx.move(to: CGPoint(x: x, y: y))
            ctx.addLine(to: CGPoint(x: x + rng.range(300, 600), y: y + rng.range(-300, 300)))
            ctx.strokePath()
        }
    }
    return CIImage(cgImage: ctx.makeImage()!)
}

struct Variant {
    let name: String
    let widthFraction: Double // document width as a fraction of the photo width
    let rotationDegrees: Double
    let keystone: Double // 0 = square on, 0.15 = top edge 15 % shorter than the bottom edge
    let clutter: Bool
    let blurSigma: Double // pixels at 3024 wide
    let exposure: Double // EV
    let noise: Double
    let glare: Double
    let landscape: Bool
}

let variants = [
    Variant(name: "flat", widthFraction: 0.90, rotationDegrees: 0, keystone: 0, clutter: false, blurSigma: 0, exposure: 0, noise: 0, glare: 0, landscape: false),
    Variant(name: "clutter", widthFraction: 0.72, rotationDegrees: 3, keystone: 0.02, clutter: true, blurSigma: 0, exposure: 0, noise: 0, glare: 0, landscape: false),
    Variant(name: "tilt", widthFraction: 0.78, rotationDegrees: 9, keystone: 0.08, clutter: true, blurSigma: 0, exposure: 0, noise: 0, glare: 0, landscape: false),
    Variant(name: "perspective", widthFraction: 0.78, rotationDegrees: 14, keystone: 0.18, clutter: true, blurSigma: 0, exposure: 0, noise: 0, glare: 0, landscape: false),
    Variant(name: "blur", widthFraction: 0.85, rotationDegrees: 2, keystone: 0.03, clutter: true, blurSigma: 3.5, exposure: 0, noise: 0, glare: 0, landscape: false),
    Variant(name: "lowlight", widthFraction: 0.85, rotationDegrees: 2, keystone: 0.03, clutter: true, blurSigma: 0.8, exposure: -1.6, noise: 0.10, glare: 0, landscape: false),
    Variant(name: "glare", widthFraction: 0.85, rotationDegrees: 2, keystone: 0.03, clutter: true, blurSigma: 0, exposure: 0, noise: 0, glare: 0.9, landscape: false),
    Variant(name: "far", widthFraction: 0.45, rotationDegrees: 4, keystone: 0.04, clutter: true, blurSigma: 0, exposure: 0, noise: 0, glare: 0, landscape: false),
    Variant(name: "landscape", widthFraction: 0.80, rotationDegrees: 3, keystone: 0.03, clutter: true, blurSigma: 0, exposure: 0, noise: 0, glare: 0, landscape: true),
    Variant(name: "combined", widthFraction: 0.78, rotationDegrees: 10, keystone: 0.12, clutter: true, blurSigma: 2.2, exposure: -1.0, noise: 0.07, glare: 0.6, landscape: false),
]

func compose(_ doc: CGImage, _ v: Variant, _ rng: inout Rng) -> CIImage {
    let W = v.landscape ? photoH : photoW, Hh = v.landscape ? photoW : photoH
    var desk = renderDesk(clutter: v.clutter, rng: &rng)
    if v.landscape {
        desk = desk.transformed(by: CGAffineTransform(rotationAngle: .pi / 2).translatedBy(x: 0, y: -photoW))
        desk = desk.transformed(by: CGAffineTransform(translationX: -desk.extent.minX, y: -desk.extent.minY))
    }
    // Where the document lands: a quad of the document's size, rotated, with the far (top) edge shorter.
    let dw = W * v.widthFraction, dh = dw * CGFloat(doc.height) / CGFloat(doc.width)
    let cx = W / 2 + CGFloat(rng.range(-0.03, 0.03)) * W, cy = Hh / 2 + CGFloat(rng.range(-0.05, 0.05)) * Hh
    let angle = v.rotationDegrees * .pi / 180
    func point(_ ux: CGFloat, _ uy: CGFloat) -> CIVector { // ux, uy in -0.5 ... 0.5, y up
        let shrink = 1 - CGFloat(v.keystone) * (uy + 0.5) // the top edge is farther away
        let x = ux * dw * shrink, y = uy * dh
        let rx = x * CGFloat(cos(angle)) - y * CGFloat(sin(angle)), ry = x * CGFloat(sin(angle)) + y * CGFloat(cos(angle))
        return CIVector(x: cx + rx, y: cy + ry)
    }
    let ciDoc = CIImage(cgImage: doc)
    let placed = ciDoc.applyingFilter("CIPerspectiveTransform", parameters: [
        "inputTopLeft": point(-0.5, 0.5), "inputTopRight": point(0.5, 0.5),
        "inputBottomLeft": point(-0.5, -0.5), "inputBottomRight": point(0.5, -0.5),
    ])
    // Soft contact shadow under the document.
    let shadow = placed.applyingFilter("CIColorMatrix", parameters: [
        "inputRVector": CIVector(x: 0, y: 0, z: 0, w: 0), "inputGVector": CIVector(x: 0, y: 0, z: 0, w: 0),
        "inputBVector": CIVector(x: 0, y: 0, z: 0, w: 0), "inputAVector": CIVector(x: 0, y: 0, z: 0, w: 0.55),
    ]).transformed(by: CGAffineTransform(translationX: 18, y: -26)).clampedToExtent().applyingGaussianBlur(sigma: 22).cropped(to: desk.extent)
    var image = placed.composited(over: shadow.composited(over: desk)).cropped(to: CGRect(x: 0, y: 0, width: W, height: Hh))

    if v.glare > 0 { // a bright patch over the lower part of the page, where the MRZ is
        let gx = CGFloat(rng.range(0.35, 0.65)) * W, gy = cy - dh * 0.3 * 1
        let gradient = CIFilter(name: "CIRadialGradient", parameters: [
            "inputCenter": CIVector(x: gx, y: gy), "inputRadius0": dw * 0.06, "inputRadius1": dw * 0.32,
            "inputColor0": CIColor(red: 1, green: 1, blue: 1, alpha: v.glare), "inputColor1": CIColor(red: 1, green: 1, blue: 1, alpha: 0),
        ])!.outputImage!.cropped(to: image.extent)
        image = gradient.composited(over: image)
    }
    if v.exposure != 0 { image = image.applyingFilter("CIExposureAdjust", parameters: ["inputEV": v.exposure]) }
    if v.blurSigma > 0 { image = image.clampedToExtent().applyingGaussianBlur(sigma: v.blurSigma).cropped(to: image.extent) }
    if v.noise > 0 {
        let n = CIFilter(name: "CIRandomGenerator")!.outputImage!.applyingFilter("CIColorMatrix", parameters: [
            "inputRVector": CIVector(x: v.noise, y: 0, z: 0, w: 0), "inputGVector": CIVector(x: 0, y: v.noise, z: 0, w: 0),
            "inputBVector": CIVector(x: 0, y: 0, z: v.noise, w: 0), "inputAVector": CIVector(x: 0, y: 0, z: 0, w: 0),
            "inputBiasVector": CIVector(x: -v.noise / 2, y: -v.noise / 2, z: -v.noise / 2, w: 1),
        ]).cropped(to: image.extent)
        image = n.applyingFilter("CIAdditionCompositing", parameters: [kCIInputBackgroundImageKey: image]).cropped(to: image.extent)
    }
    return image
}

func writeJPEG(_ image: CIImage, _ path: String) {
    let cg = ciContext.createCGImage(image, from: image.extent, format: .RGBA8, colorSpace: srgb)!
    let dest = CGImageDestinationCreateWithURL(URL(fileURLWithPath: path) as CFURL, UTType.jpeg.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(dest, cg, [kCGImageDestinationLossyCompressionQuality: 0.88] as CFDictionary)
    CGImageDestinationFinalize(dest)
}

try FileManager.default.createDirectory(atPath: outDir, withIntermediateDirectories: true)
let manifest = try String(contentsOfFile: manifestPath, encoding: .utf8)
var count = 0
for row in manifest.split(separator: "\n") {
    let cols = row.split(separator: "\t").map(String.init)
    let id = cols[0], format = cols[1], lines = cols[2].split(separator: "|").map(String.init)
    var docRng = Rng(state: seed(for: id, "doc"))
    let doc = renderDocument(format: format, lines: lines, rng: &docRng)
    for v in variants {
        var rng = Rng(state: seed(for: id, v.name))
        writeJPEG(compose(doc, v, &rng), "\(outDir)/\(id)__\(v.name).jpg")
        count += 1
    }
    // Control: a tight crop around the MRZ of the flat page (about 2 character pitches of margin).
    let layout = format == "TD3" ? td3 : td1
    let mrzH = (CGFloat(lines.count - 1) * linePitchMM + 2 * charPitchMM + 2 * 2.54) * mm
    let bottom = (layout.lastBaselineFromBottomMM - 2.54) * mm
    let rect = CGRect(x: 0, y: bottom, width: CGFloat(doc.width), height: mrzH)
    let crop = CIImage(cgImage: doc).cropped(to: rect)
    writeJPEG(crop.transformed(by: CGAffineTransform(translationX: -rect.minX, y: -rect.minY)), "\(outDir)/\(id)__mrzcrop.jpg")
    count += 1
}
print("rendered \(count) images to \(outDir)")
