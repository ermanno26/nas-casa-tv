#!/usr/bin/env python3
"""Finto adbd per provare AdbClient: controlla firma e formato della chiave come farebbe la TV."""
import base64, os, socketserver, struct, sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 5555
CMD = {b"CNXN": 0x4e584e43, b"AUTH": 0x48545541, b"OPEN": 0x4e45504f,
       b"OKAY": 0x59414b4f, b"CLSE": 0x45534c43, b"WRTE": 0x45545257}
PREFIX = bytes.fromhex("3021300906052b0e03021a05000414")
STATE = {"n": None, "e": None, "keys_received": 0}


def pack(cmd, a0, a1, data=b""):
    return struct.pack("<6I", cmd, a0, a1, len(data), sum(data) & 0xffffffff, cmd ^ 0xffffffff) + data


def readn(f, n):
    b = b""
    while len(b) < n:
        c = f.read(n - len(b))
        if not c:
            raise EOFError
        b += c
    return b


def recv(f):
    cmd, a0, a1, ln, chk, magic = struct.unpack("<6I", readn(f, 24))
    assert magic == cmd ^ 0xffffffff, "magic errato"
    data = readn(f, ln)
    assert chk == sum(data) & 0xffffffff, "checksum errato"
    return cmd, a0, a1, data


def parse_pubkey(data):
    text = data.split(b"\0")[0].split(b" ")[0]
    raw = base64.b64decode(text)
    assert len(raw) == 524, len(raw)
    words, n0inv = struct.unpack("<II", raw[:8])
    n = int.from_bytes(raw[8:264], "little")
    rr = int.from_bytes(raw[264:520], "little")
    e = struct.unpack("<I", raw[520:524])[0]
    assert words == 64
    assert (n * n0inv + 1) % (1 << 32) == 0, "n0inv sbagliato"
    assert rr == pow(1 << 2048, 2, n), "rr sbagliato"
    return n, e


def verify(sig, token):
    n, e = STATE["n"], STATE["e"]
    em = pow(int.from_bytes(sig, "big"), e, n).to_bytes(256, "big")
    exp = b"\x00\x01" + b"\xff" * (256 - 3 - len(PREFIX) - 20) + b"\x00" + PREFIX + token
    return em == exp


class H(socketserver.StreamRequestHandler):
    def handle(self):
        f = self.rfile
        try:
            cmd, a0, a1, d = recv(f)
            assert cmd == CMD[b"CNXN"]
            authed = False
            while not authed:
                token = os.urandom(20)
                self.wfile.write(pack(CMD[b"AUTH"], 1, 0, token))
                cmd, a0, a1, d = recv(f)
                assert cmd == CMD[b"AUTH"]
                if a0 == 2 and STATE["n"] and verify(d, token):
                    authed = True
                elif a0 == 3:
                    STATE["n"], STATE["e"] = parse_pubkey(d)
                    STATE["keys_received"] += 1
                    authed = True  # "l'utente ha premuto Consenti"
            self.wfile.write(pack(CMD[b"CNXN"], 0x01000000, 4096, b"device::ro.product.name=mock\0"))
            while True:
                cmd, a0, a1, d = recv(f)
                if cmd == CMD[b"OPEN"]:
                    c = d.rstrip(b"\0").decode()
                    assert c.startswith("shell:")
                    c = c[6:]
                    if c == "stato":
                        out = "chiavi=%d\n" % STATE["keys_received"]
                    elif c == "grande":
                        out = "".join("riga %d\n" % i for i in range(3000))
                    else:
                        out = "eco: %s\n" % c
                    out = out.replace("\n", "\r\n").encode()
                    self.wfile.write(pack(CMD[b"OKAY"], 77, a0))
                    for i in range(0, len(out), 4000):
                        self.wfile.write(pack(CMD[b"WRTE"], 77, a0, out[i:i + 4000]))
                        r = recv(f)
                        assert r[0] == CMD[b"OKAY"]
                    self.wfile.write(pack(CMD[b"CLSE"], 77, a0))
                elif cmd == CMD[b"CLSE"]:
                    pass
        except (EOFError, ConnectionError):
            pass


class S(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    S(("127.0.0.1", PORT), H).serve_forever()
