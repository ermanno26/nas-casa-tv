import sys, base64
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import unquote, quote
TREE = {"/Pubblico/TV Sfondi": ["a.png", "b.JPG", "note.txt", ("sub", ["c.jpeg"])]}
OK = "Basic " + base64.b64encode(b"tv:segreto").decode()
class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def do_PROPFIND(self):
        if self.headers.get("Authorization") != OK:
            self.send_response(401); self.send_header("Content-Length","0"); self.end_headers(); return
        p = unquote(self.path).rstrip("/")
        node = None
        if p == "/Pubblico/TV Sfondi": node = TREE[p]
        elif p == "/Pubblico/TV Sfondi/sub": node = ["c.jpeg"]
        if node is None:
            self.send_response(404); self.send_header("Content-Length","0"); self.end_headers(); return
        def resp(href, col):
            rt = "<D:resourcetype><D:collection/></D:resourcetype>" if col else "<D:resourcetype/>"
            return f"<D:response><D:href>{quote(href)}{'/' if col else ''}</D:href><D:propstat><D:prop>{rt}</D:prop></D:propstat></D:response>"
        x = '<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">' + resp(p, True)
        for n in node:
            if isinstance(n, tuple): x += resp(p + "/" + n[0], True)
            else: x += resp(p + "/" + n, False)
        x += "</D:multistatus>"
        b = x.encode()
        self.send_response(207); self.send_header("Transfer-Encoding", "chunked"); self.end_headers()
        for i in range(0, len(b), 100):
            c = b[i:i+100]; self.wfile.write(b"%x\r\n" % len(c) + c + b"\r\n")
        self.wfile.write(b"0\r\n\r\n")
    def log_message(self,*a): pass
HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
