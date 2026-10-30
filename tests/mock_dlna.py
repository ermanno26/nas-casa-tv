#!/usr/bin/env python3
"""Finto server DLNA per provare il client senza il NAS vero.

Risponde a SSDP (UDP 11900) e serve scheda del dispositivo + Browse (HTTP 18200).
Di proposito: pagine da 3 elementi (per provare la paginazione), un '&' non
escapato, un URL relativo, una foto solo-HEIC, una cartella vuota, un errore 500.
"""
import re
import socket
import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from xml.sax.saxutils import escape

HTTP_PORT = 18200
SSDP_PORT = 11900
PAGE_LIMIT = 3

DEVICE = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
 <device>
  <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
  <friendlyName>NAS-Casa-Test</friendlyName>
  <serviceList>
   <service>
    <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
    <controlURL>/ctl/ConnectionMgr</controlURL>
   </service>
   <service>
    <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
    <controlURL>/ctl/ContentDir</controlURL>
   </service>
  </serviceList>
 </device>
</root>"""

H = "http://127.0.0.1:%d" % HTTP_PORT
NS = ('xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" '
      'xmlns:dc="http://purl.org/dc/elements/1.1/" '
      'xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"')


def container(cid, parent, title, n):
    return ('<container id="%s" parentID="%s" childCount="%d" restricted="1">'
            '<dc:title>%s</dc:title><upnp:class>object.container.storageFolder</upnp:class></container>'
            % (cid, parent, n, escape(title)))


def photo(iid, parent, title, ress, art=""):
    r = "".join('<res protocolInfo="%s" %s>%s</res>' % (pi, attrs, url) for pi, attrs, url in ress)
    return ('<item id="%s" parentID="%s" restricted="1"><dc:title>%s</dc:title>'
            '<dc:date>2026-10-%02d</dc:date>'
            '<upnp:class>object.item.imageItem.photo</upnp:class>%s%s</item>'
            % (iid, parent, escape(title), int(iid.split("-")[-1]) if "-" in iid else 1, art, r))


JPEG = "http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG;DLNA.ORG_OP=01"
JPEG_TN = "http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN;DLNA.ORG_OP=01"
JPEG_ORIG = "http-get:*:image/jpeg:*"
HEIC = "http-get:*:image/heic:*"
PNG = "http-get:*:image/png:DLNA.ORG_PN=PNG_LRG"

TREE = {
    "0": [container("1", "0", "Pictures", 5), container("2", "0", "Video", 2),
          container("3", "0", "Vuota", 0), container("4", "0", "Foto & Co", 0)],
    "1": [
        photo("p-1", "1", "IMG_0001.jpg", [
            (JPEG_ORIG, 'size="3000000" resolution="4032x3024"', H + "/m/p1-orig.jpg"),
            (JPEG, 'size="400000" resolution="1920x1440"', H + "/m/p1-lrg.jpg"),
            (JPEG_TN, 'size="9000" resolution="160x120"', H + "/m/p1-tn.jpg")]),
        photo("p-2", "1", "IMG_0002.heic", [
            (HEIC, 'size="2500000" resolution="4032x3024"', H + "/m/p2.heic")]),
        photo("p-3", "1", "IMG_0003.jpg", [
            (JPEG_ORIG, 'size="2800000"', H + "/m/p3.jpg?a=1&b=2")]),   # '&' non escapato
        photo("p-4", "1", "IMG_0004.png", [(PNG, 'size="100000" resolution="800x600"', "/m/p4.png")]),  # relativo
        photo("p-5", "1", "IMG_0005.jpg", [(JPEG_ORIG, 'size="1000000" resolution="3000x2000"', H + "/m/p5.jpg")],
              art='<upnp:albumArtURI>%s/m/p5-art.jpg</upnp:albumArtURI>' % H),
    ],
    "2": [
        ('<item id="v-1" parentID="2" restricted="1"><dc:title>Vacanze 4K.mp4</dc:title>'
         '<upnp:class>object.item.videoItem</upnp:class>'
         '<upnp:albumArtURI>%s/m/v1-art.jpg</upnp:albumArtURI>'
         '<res protocolInfo="http-get:*:video/mp4:*" size="900000000" duration="0:02:00.000" resolution="3840x2160">%s/m/v1.mp4</res>'
         '<res protocolInfo="http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_BL_CIF15_AAC_520" size="20000000" resolution="352x288">%s/m/v1-small.mp4</res>'
         '</item>' % (H, H, H)),
        ('<item id="v-2" parentID="2" restricted="1"><dc:title>Film.mkv</dc:title>'
         '<upnp:class>object.item.videoItem</upnp:class>'
         '<res protocolInfo="http-get:*:video/x-matroska:*" size="5000000000">%s/m/film.mkv</res></item>' % H),
    ],
    "3": [],
    "4": [],
}


def didl(entries):
    return '<DIDL-Lite %s>%s</DIDL-Lite>' % (NS, "".join(entries)) if entries else ""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path == "/rootDesc.xml":
            b = DEVICE.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/xml")
            self.send_header("Content-Length", str(len(b)))
            self.end_headers()
            self.wfile.write(b)
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(n).decode()
        action = self.headers.get("SOAPACTION", "")
        if not action.strip('"').endswith("#Browse"):
            self.send_response(500)
            self.end_headers()
            return
        oid = re.search(r"<ObjectID>(.*?)</ObjectID>", body).group(1)
        start = int(re.search(r"<StartingIndex>(\d+)</StartingIndex>", body).group(1))
        count = int(re.search(r"<RequestedCount>(\d+)</RequestedCount>", body).group(1))
        if oid not in TREE:
            fault = ('<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>'
                     '<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail>'
                     '<UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>701</errorCode>'
                     '<errorDescription>No such object</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>').encode()
            self.send_response(500)
            self.send_header("Content-Length", str(len(fault)))
            self.end_headers()
            self.wfile.write(fault)
            return
        items = TREE[oid]
        page = items[start:start + min(count, PAGE_LIMIT)]
        resp = ('<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
                's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
                '<u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">'
                '<Result>%s</Result><NumberReturned>%d</NumberReturned><TotalMatches>%d</TotalMatches>'
                '<UpdateID>1</UpdateID></u:BrowseResponse></s:Body></s:Envelope>'
                % (escape(didl(page)), len(page), len(items))).encode()
        self.send_response(200)
        self.send_header("Content-Type", 'text/xml; charset="utf-8"')
        self.send_header("Content-Length", str(len(resp)))
        self.end_headers()
        self.wfile.write(resp)


def ssdp():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", SSDP_PORT))
    while True:
        data, addr = s.recvfrom(4096)
        if b"M-SEARCH" not in data:
            continue
        st = re.search(rb"ST:\s*(.*)\r", data)
        st = st.group(1).decode() if st else "upnp:rootdevice"
        reply = ("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\n"
                 "LOCATION: %s/rootDesc.xml\r\nST: %s\r\nUSN: uuid:test::%s\r\n\r\n" % (H, st, st))
        s.sendto(reply.encode(), addr)


if __name__ == "__main__":
    threading.Thread(target=ssdp, daemon=True).start()
    srv = HTTPServer(("127.0.0.1", HTTP_PORT), Handler)
    print("mock pronto", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        sys.exit(0)
