import Foundation

/// Just enough IPv4, UDP and DNS handling to answer queries arriving on the tunnel.
/// Mirrors DnsPacket.kt in the Android app.
enum DNSPacket {
    static let typeA = 1
    static let typeCNAME = 5
    static let typeAAAA = 28

    struct Question {
        let name: String
        let type: Int
        let end: Int
    }

    /// A DNS query pulled out of an IPv4/UDP packet, with what's needed to address the reply.
    struct Query {
        let dns: [UInt8]
        private let srcIP: [UInt8]
        private let dstIP: [UInt8]
        private let srcPort: Int

        /// Returns the query if `packet` is an IPv4 UDP packet to port 53.
        init?(packet: Data) {
            let b = [UInt8](packet)
            guard b.count >= 28, b[0] >> 4 == 4, b[9] == 17 else { return nil }
            let ihl = Int(b[0] & 0x0f) * 4
            let total = u16(b, 2)
            guard total <= b.count, ihl + 8 + 12 <= total, u16(b, ihl + 2) == 53 else { return nil }
            dns = Array(b[(ihl + 8)..<total])
            srcIP = Array(b[12..<16])
            dstIP = Array(b[16..<20])
            srcPort = u16(b, ihl)
        }

        /// Wraps a DNS response in an IPv4/UDP packet back to the asking app.
        func reply(_ payload: [UInt8]) -> Data {
            let total = 28 + payload.count
            var p = [UInt8](repeating: 0, count: total)
            p[0] = 0x45
            put16(&p, 2, total)
            p[6] = 0x40 // don't fragment
            p[8] = 64 // TTL
            p[9] = 17 // UDP
            p.replaceSubrange(12..<16, with: dstIP)
            p.replaceSubrange(16..<20, with: srcIP)
            put16(&p, 10, checksum(p, 0, 20))
            put16(&p, 20, 53)
            put16(&p, 22, srcPort)
            put16(&p, 24, 8 + payload.count)
            // UDP checksum 0 means "not computed", which IPv4 allows.
            p.replaceSubrange(28..<total, with: payload)
            return Data(p)
        }
    }

    static func question(_ msg: [UInt8]) -> Question? {
        guard msg.count >= 12, u16(msg, 4) == 1, let (name, end) = readName(msg, 12), end + 4 <= msg.count else { return nil }
        return Question(name: name, type: u16(msg, end), end: end + 4)
    }

    /// Answers A with 0.0.0.0, AAAA with ::, and anything else with an empty NOERROR.
    static func blockedAnswer(_ query: [UInt8], _ q: Question) -> [UInt8] {
        let rdLen = q.type == typeA ? 4 : q.type == typeAAAA ? 16 : -1
        var out = Array(query[0..<q.end])
        out[2] = 0x80 | (query[2] & 0x79) // response, keep opcode + RD
        out[3] = 0x80 // recursion available, NOERROR
        put16(&out, 6, rdLen < 0 ? 0 : 1)
        put16(&out, 8, 0)
        put16(&out, 10, 0)
        if rdLen >= 0 {
            out += [0xc0, 0x0c] // pointer to the question name
            out += [UInt8(q.type >> 8), UInt8(q.type & 0xff), 0, 1] // type, class IN
            out += [0, 0, 1, 0x2c] // TTL 300
            out += [0, UInt8(rdLen)]
            out += [UInt8](repeating: 0, count: rdLen)
        }
        return out
    }

    /// CNAME targets in a response, used to catch trackers hiding behind first-party names.
    static func cnameTargets(_ msg: [UInt8]) -> [String] {
        guard let q = question(msg) else { return [] }
        var out: [String] = []
        var off = q.end
        for _ in 0..<u16(msg, 6) {
            guard let (_, nameEnd) = readName(msg, off), nameEnd + 10 <= msg.count else { break }
            let type = u16(msg, nameEnd)
            let rdLen = u16(msg, nameEnd + 8)
            let rdata = nameEnd + 10
            if type == typeCNAME, let (target, _) = readName(msg, rdata) { out.append(target) }
            off = rdata + rdLen
        }
        return out
    }

    /// Reads a possibly compressed name; returns it and the offset just past it.
    private static func readName(_ msg: [UInt8], _ start: Int) -> (String, Int)? {
        var labels: [String] = []
        var off = start
        var end = -1
        var jumps = 0
        while true {
            guard off < msg.count else { return nil }
            let len = Int(msg[off])
            if len == 0 {
                return (labels.joined(separator: "."), end < 0 ? off + 1 : end)
            } else if len & 0xc0 == 0xc0 {
                guard off + 1 < msg.count else { return nil }
                jumps += 1
                guard jumps <= 20 else { return nil }
                if end < 0 { end = off + 2 }
                off = ((len & 0x3f) << 8) | Int(msg[off + 1])
            } else {
                guard off + 1 + len <= msg.count else { return nil }
                labels.append(String(decoding: msg[(off + 1)...(off + len)], as: UTF8.self))
                off += 1 + len
            }
        }
    }

    private static func checksum(_ b: [UInt8], _ off: Int, _ len: Int) -> Int {
        var sum = 0
        var i = off
        while i < off + len {
            sum += u16(b, i)
            i += 2
        }
        while sum >> 16 != 0 { sum = (sum & 0xffff) + (sum >> 16) }
        return ~sum & 0xffff
    }
}

private func u16(_ b: [UInt8], _ i: Int) -> Int { Int(b[i]) << 8 | Int(b[i + 1]) }

private func put16(_ b: inout [UInt8], _ i: Int, _ v: Int) {
    b[i] = UInt8((v >> 8) & 0xff)
    b[i + 1] = UInt8(v & 0xff)
}
