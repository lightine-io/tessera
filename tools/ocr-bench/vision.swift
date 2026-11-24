// Reads every benchmark image with Apple Vision (TES-149) and prints one row per recognized
// text fragment: image <TAB> level <TAB> centre-x <TAB> centre-y <TAB> width <TAB> height <TAB> angle <TAB> text,
// in pixels with y growing downwards and the baseline angle in radians. score.py joins fragments
// into lines.
// The requests match VisionMrzTextRecognizer in mrz-camera-ios: no language correction, accurate
// for the live path and fast for the saved-image rescue pass.
//
// Usage: vision <image-dir>. Built for the iOS Simulator by run.sh; also runs on macOS as a script.

import CoreGraphics
import Foundation
import ImageIO
import Vision

let dir = CommandLine.arguments[1]
// Optional second argument: a text recognition revision (1, 2 or 3). Without it each request uses
// the default revision, as the SDK does (TES-343).
let revision: Int? = CommandLine.arguments.count > 2 ? Int(CommandLine.arguments[2]) : nil
let files = try FileManager.default.contentsOfDirectory(atPath: dir).filter { $0.hasSuffix(".png") }.sorted()
let levels: [(String, VNRequestTextRecognitionLevel)] = [("accurate", .accurate), ("fast", .fast)]

for file in files {
    let url = URL(fileURLWithPath: dir).appendingPathComponent(file)
    guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
          let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else { continue }
    for (name, level) in levels {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = level
        request.usesLanguageCorrection = false
        if let revision { request.revision = revision }
        try VNImageRequestHandler(cgImage: image, options: [:]).perform([request])
        for observation in request.results ?? [] {
            guard let text = observation.topCandidates(1).first?.string else { continue }
            let w = Double(image.width), h = Double(image.height)
            // Vision's corners are normalized with the origin at the bottom left.
            func px(_ p: CGPoint) -> (Double, Double) { (Double(p.x) * w, (1 - Double(p.y)) * h) }
            let (tlx, tly) = px(observation.topLeft), (trx, try_) = px(observation.topRight)
            let (blx, bly) = px(observation.bottomLeft), (brx, bry) = px(observation.bottomRight)
            let cx = (tlx + trx + blx + brx) / 4, cy = (tly + try_ + bly + bry) / 4
            let height = ((blx - tlx) * (blx - tlx) + (bly - tly) * (bly - tly)).squareRoot()
            let width = ((trx - tlx) * (trx - tlx) + (try_ - tly) * (try_ - tly)).squareRoot()
            let angle = atan2(try_ - tly, trx - tlx)
            print("\(file)\t\(name)\t\(cx)\t\(cy)\t\(width)\t\(height)\t\(angle)\t\(text)")
        }
    }
}
