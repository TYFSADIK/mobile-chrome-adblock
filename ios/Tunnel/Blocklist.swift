import Foundation

/// Domain matcher over sorted 64-bit FNV-1a hashes built by scripts/build-blocklist.py.
/// A name is blocked when it or a parent domain is listed, unless it or a parent is allowed.
struct Blocklist {
    private let block: [UInt64]
    private let allow: [UInt64]

    init(bundle: Bundle = .main) {
        block = Self.load(bundle, "blocklist")
        allow = Self.load(bundle, "allowlist")
    }

    var count: Int { block.count }

    func isBlocked(_ name: String) -> Bool {
        var d = Substring(name.lowercased())
        if d.hasSuffix(".") { d = d.dropLast() }
        var hit = false
        while !d.isEmpty {
            let h = Self.hash(d)
            if Self.contains(allow, h) { return false }
            if !hit && Self.contains(block, h) { hit = true }
            guard let dot = d.firstIndex(of: ".") else { break }
            d = d[d.index(after: dot)...]
        }
        return hit
    }

    static func hash<S: StringProtocol>(_ s: S) -> UInt64 {
        var h: UInt64 = 0xcbf2_9ce4_8422_2325
        for unit in s.utf16 {
            h ^= UInt64(unit)
            h = h &* 0x100_0000_01b3
        }
        return h
    }

    private static func contains(_ a: [UInt64], _ v: UInt64) -> Bool {
        var lo = 0, hi = a.count - 1
        while lo <= hi {
            let mid = (lo + hi) / 2
            if a[mid] == v { return true }
            if a[mid] < v { lo = mid + 1 } else { hi = mid - 1 }
        }
        return false
    }

    private static func load(_ bundle: Bundle, _ name: String) -> [UInt64] {
        guard let url = bundle.url(forResource: name, withExtension: "bin"),
              let data = try? Data(contentsOf: url, options: .alwaysMapped)
        else { return [] }
        return data.withUnsafeBytes { raw in
            raw.bindMemory(to: UInt64.self).map { UInt64(littleEndian: $0) }
        }
    }
}
