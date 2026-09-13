"""
A minimal WebDAV server, for verifying Filet's hand-written WebDAV client against something
that is not itself.

Not a product: no locking, no properties beyond the four the client reads, no HTTPS. It exists
so the M8 network gate can be closed with a real socket, a real 207 Multi-Status body and real
Basic auth instead of a mock. Run it on the host and point the device at it through
`adb reverse tcp:18080 tcp:18080`.

    python webdav.py <root-dir> [port]
"""
import base64
import os
import shutil
import sys
import urllib.parse
from email.utils import formatdate
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

USER, PASSWORD = "filet", "s3cret"


class Dav(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "tiny-dav/1"

    # ── plumbing ──

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.command, self.path))

    def _authed(self):
        header = self.headers.get("Authorization", "")
        if not header.startswith("Basic "):
            return False
        try:
            user, _, pwd = base64.b64decode(header[6:]).decode("utf-8").partition(":")
        except Exception:
            return False
        return user == USER and pwd == PASSWORD

    def _deny(self):
        self.send_response(401)
        self.send_header("WWW-Authenticate", 'Basic realm="filet"')
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _local(self, url_path=None):
        raw = url_path if url_path is not None else self.path
        rel = urllib.parse.unquote(urllib.parse.urlsplit(raw).path).lstrip("/")
        target = os.path.normpath(os.path.join(ROOT, rel))
        # The server is a test fixture, but it still refuses to escape its own root: a client
        # bug must fail loudly here rather than reach into the host filesystem.
        if os.path.commonpath([os.path.abspath(target), ROOT]) != ROOT:
            return None
        return target

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    def _send(self, code, body=b"", ctype=None):
        self.send_response(code)
        if ctype:
            self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    # ── verbs ──

    def do_PROPFIND(self):
        if not self._authed():
            return self._deny()
        self._body()
        target = self._local()
        if target is None or not os.path.exists(target):
            return self._send(404)

        depth = self.headers.get("Depth", "1")
        base = urllib.parse.unquote(urllib.parse.urlsplit(self.path).path)
        entries = [(base, target)]
        if depth == "1" and os.path.isdir(target):
            prefix = base if base.endswith("/") else base + "/"
            for name in sorted(os.listdir(target)):
                entries.append((prefix + name, os.path.join(target, name)))

        out = ['<?xml version="1.0" encoding="utf-8"?>', '<D:multistatus xmlns:D="DAV:">']
        for href, path in entries:
            is_dir = os.path.isdir(path)
            quoted = urllib.parse.quote(href)
            if is_dir and not quoted.endswith("/"):
                quoted += "/"
            st = os.stat(path)
            out.append("<D:response><D:href>%s</D:href><D:propstat><D:prop>" % quoted)
            out.append("<D:displayname>%s</D:displayname>" % os.path.basename(path.rstrip("/\\")))
            out.append("<D:getlastmodified>%s</D:getlastmodified>" % formatdate(st.st_mtime, usegmt=True))
            if is_dir:
                out.append("<D:resourcetype><D:collection/></D:resourcetype>")
            else:
                out.append("<D:resourcetype/>")
                out.append("<D:getcontentlength>%d</D:getcontentlength>" % st.st_size)
            out.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
        out.append("</D:multistatus>")
        self._send(207, "".join(out).encode("utf-8"), 'application/xml; charset="utf-8"')

    def do_GET(self):
        if not self._authed():
            return self._deny()
        target = self._local()
        if target is None or not os.path.isfile(target):
            return self._send(404)
        with open(target, "rb") as fh:
            self._send(200, fh.read(), "application/octet-stream")

    def do_HEAD(self):
        self.do_GET()

    def do_PUT(self):
        if not self._authed():
            return self._deny()
        target = self._local()
        if target is None:
            return self._send(403)
        data = self._body()
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "wb") as fh:
            fh.write(data)
        self._send(201)

    def do_MKCOL(self):
        if not self._authed():
            return self._deny()
        target = self._local()
        if target is None:
            return self._send(403)
        if os.path.exists(target):
            return self._send(405)
        os.makedirs(target)
        self._send(201)

    def do_DELETE(self):
        if not self._authed():
            return self._deny()
        target = self._local()
        if target is None or not os.path.exists(target):
            return self._send(404)
        shutil.rmtree(target) if os.path.isdir(target) else os.remove(target)
        self._send(204)

    def do_MOVE(self):
        if not self._authed():
            return self._deny()
        src = self._local()
        dest = self._local(self.headers.get("Destination", ""))
        if src is None or dest is None or not os.path.exists(src):
            return self._send(404)
        if os.path.exists(dest) and self.headers.get("Overwrite", "T") == "F":
            return self._send(412)
        os.replace(src, dest)
        self._send(201)

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("DAV", "1")
        self.send_header("Allow", "OPTIONS,GET,HEAD,PUT,DELETE,PROPFIND,MKCOL,MOVE")
        self.send_header("Content-Length", "0")
        self.end_headers()


if __name__ == "__main__":
    ROOT = os.path.abspath(sys.argv[1])
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 18080
    os.makedirs(ROOT, exist_ok=True)
    print("tiny-dav serving %s on 0.0.0.0:%d as %s/%s" % (ROOT, port, USER, PASSWORD), flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Dav).serve_forever()
