"""
A fake Filet host: a WebDAV share that announces itself the way the app does.

    python filet-sim.py                     serve, seed content, announce
    python filet-sim.py --port 18080        a different port
    python filet-sim.py --no-announce       serve only, add it by hand on the phone

## Why this exists

Reproducing anything about a mounted share meant having a second phone awake, on the right
network, hosting, with the right build on it. That is three things that can be wrong before the
bug is even reached, and none of them is the bug. This is a share on the development machine
instead: it is on the LAN, it appears in *Phones on this network* like any other Filet host, and
its contents are seeded and known - an archive and an APK, because those are the two that have to
be opened without being downloaded first.

It is a FIXTURE, not a product. No locking, no HTTPS, no properties beyond the four the client
reads. What it does implement properly is the part under test:

  * **`Range` requests answered with `206`** and a correct `Content-Range`, plus `Accept-Ranges`
    on HEAD and GET. Reading part of a file is what lets an archive's index be read without
    fetching the archive, and the existing gate fixture does not implement it at all - so a
    client that silently fell back to downloading whole files would have passed against it.
  * **The `/a/<code>` path shape** the app's own host serves on, so the client is exercised
    through the same addressing it uses in the field rather than a tidier one.

## The announcement

mDNS, by hand, because the stdlib has none and a fixture that needs a pip install is a fixture
that does not get run. It answers PTR, SRV, TXT and A for `_filet-dav._tcp.local.` and sends an
unsolicited announcement at startup, which is enough for Android's NSD to find and resolve it.

The TXT record carries `path`, `scope`, `w` and `id` - the same four the app reads. `id` is a
stable device id generated once and kept beside this script, so the share is recognised as the
same device across restarts, which is the behaviour being tested.

**Windows will ask about the firewall the first time.** Allow it on private networks, or the
phone will discover the service and then fail to connect to it, which looks like a client bug.
"""
import argparse
import base64
import os
import random
import socket
import struct
import sys
import threading
import time
import urllib.parse
import uuid
import zipfile
from email.utils import formatdate
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
SERVICE = "_filet-dav._tcp.local."
MDNS_ADDR, MDNS_PORT = "224.0.0.251", 5353


# ────────────────────────────── the share ──────────────────────────────


class Share(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "filet-sim/1"

    def log_message(self, fmt, *args):
        sys.stderr.write("  %-8s %s\n" % (self.command, self.path))

    # ── addressing ──

    def _rel(self, url_path=None):
        """The path inside the share, or None when the request is not for this share."""
        raw = url_path if url_path is not None else self.path
        path = urllib.parse.unquote(urllib.parse.urlsplit(raw).path)
        prefix = "/a/" + CODE
        if path == prefix:
            return ""
        if not path.startswith(prefix + "/"):
            return None
        return path[len(prefix) + 1:]

    def _local(self, url_path=None):
        rel = self._rel(url_path)
        if rel is None:
            return None
        target = os.path.normpath(os.path.join(ROOT, rel))
        if os.path.commonpath([os.path.abspath(target), ROOT]) != ROOT:
            return None
        return target

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    def _send(self, code, body=b"", ctype=None, extra=None):
        self.send_response(code)
        if ctype:
            self.send_header("Content-Type", ctype)
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    # ── verbs ──

    def do_OPTIONS(self):
        self.send_response(200)
        # Class 2 so a client that looks for locking does not rule the server out; the locks
        # themselves are not implemented and nothing under test takes one.
        self.send_header("DAV", "1,2")
        self.send_header("Allow", "OPTIONS,GET,HEAD,PUT,DELETE,PROPFIND,MKCOL,MOVE")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_PROPFIND(self):
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
        # RFC 4331, so a mounted drive can report a size rather than "size unknown".
        if os.path.isdir(target) and depth == "0":
            try:
                usage = __import__("shutil").disk_usage(ROOT)
                out.append(
                    "<D:response><D:href>%s</D:href><D:propstat><D:prop>"
                    "<D:quota-available-bytes>%d</D:quota-available-bytes>"
                    "<D:quota-used-bytes>%d</D:quota-used-bytes>"
                    "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
                    % (urllib.parse.quote(base), usage.free, usage.used)
                )
            except Exception:
                pass
        out.append("</D:multistatus>")
        self._send(207, "".join(out).encode("utf-8"), 'application/xml; charset="utf-8"')

    def do_HEAD(self):
        target = self._local()
        if target is None or not os.path.isfile(target):
            return self._send(404)
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(os.path.getsize(target)))
        # The header the whole native-feel path turns on. Without it a client is right to
        # assume every read is a download.
        self.send_header("Accept-Ranges", "bytes")
        self.end_headers()

    def do_GET(self):
        target = self._local()
        if target is None or not os.path.isfile(target):
            return self._send(404)
        size = os.path.getsize(target)
        span = parse_range(self.headers.get("Range"), size)
        if span is None:
            with open(target, "rb") as fh:
                return self._send(
                    200, fh.read(), "application/octet-stream", {"Accept-Ranges": "bytes"},
                )
        start, end = span
        if start >= size:
            self.send_response(416)
            self.send_header("Content-Range", "bytes */%d" % size)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        with open(target, "rb") as fh:
            fh.seek(start)
            chunk = fh.read(end - start + 1)
        self.send_response(206)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(len(chunk)))
        self.end_headers()
        self.wfile.write(chunk)

    def do_PUT(self):
        target = self._local()
        if target is None:
            return self._send(403)
        if not WRITABLE:
            return self._send(403)
        data = self._body()
        os.makedirs(os.path.dirname(target), exist_ok=True)
        with open(target, "wb") as fh:
            fh.write(data)
        self._send(201)

    def do_MKCOL(self):
        target = self._local()
        if target is None or not WRITABLE:
            return self._send(403)
        if os.path.exists(target):
            return self._send(405)
        os.makedirs(target)
        self._send(201)

    def do_DELETE(self):
        target = self._local()
        if target is None or not WRITABLE:
            return self._send(403)
        if not os.path.exists(target):
            return self._send(404)
        if os.path.isdir(target):
            __import__("shutil").rmtree(target)
        else:
            os.remove(target)
        self._send(204)


def parse_range(header, size):
    """`bytes=start-end` as an inclusive pair, or None when there is no usable range."""
    if not header or not header.strip().lower().startswith("bytes="):
        return None
    spec = header.split("=", 1)[1].strip()
    # One range only. A multipart response is not something any client here asks for, and
    # answering a multi-range request with the first range would be a lie.
    if "," in spec:
        return None
    first, _, last = spec.partition("-")
    try:
        if first == "":
            # A suffix range: the last N bytes. This is the shape an archive reader uses to find
            # the central directory, so it is the one that must not be got wrong.
            n = int(last)
            if n <= 0:
                return None
            return max(0, size - n), size - 1
        start = int(first)
        end = int(last) if last else size - 1
        return start, min(end, size - 1)
    except ValueError:
        return None


# ────────────────────────────── announcing ──────────────────────────────


def encode_name(name):
    out = b""
    for label in name.rstrip(".").split("."):
        raw = label.encode("utf-8")
        out += bytes([len(raw)]) + raw
    return out + b"\x00"


def record(name, rtype, data, ttl=120):
    return encode_name(name) + struct.pack("!HHIH", rtype, 0x8001, ttl, len(data)) + data


def announcement(instance, host, port, ip, txt):
    """One mDNS response carrying PTR, SRV, TXT and A - everything a resolve needs."""
    answers = b""
    answers += record(SERVICE, 12, encode_name(instance))
    srv = struct.pack("!HHH", 0, 0, port) + encode_name(host)
    answers += record(instance, 33, srv)
    blob = b""
    for k, v in txt.items():
        pair = ("%s=%s" % (k, v)).encode("utf-8")
        blob += bytes([len(pair)]) + pair
    answers += record(instance, 16, blob)
    answers += record(host, 1, socket.inet_aton(ip))
    header = struct.pack("!HHHHHH", 0, 0x8400, 0, 4, 0, 0)
    return header + answers


def local_ip():
    """The address on the network the phone is on, not 127.0.0.1."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        # Never actually sent; it just makes the OS pick the outbound interface.
        s.connect(("8.8.8.8", 53))
        return s.getsockname()[0]
    finally:
        s.close()


def device_id():
    """Stable across restarts, so the share is the same DEVICE each time it comes back."""
    path = os.path.join(HERE, ".filet-sim-id")
    if os.path.exists(path):
        return open(path).read().strip()
    made = uuid.uuid4().hex
    with open(path, "w") as fh:
        fh.write(made)
    return made


def announce_forever(instance, host, port, ip, txt, stop):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(("", MDNS_PORT))
        sock.setsockopt(
            socket.IPPROTO_IP,
            socket.IP_ADD_MEMBERSHIP,
            socket.inet_aton(MDNS_ADDR) + socket.inet_aton(ip),
        )
    except OSError as e:
        print("  ! could not join the mDNS group (%s)" % e, flush=True)
        print("    serving anyway - add the address by hand on the phone", flush=True)
        return
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
    packet = announcement(instance, host, port, ip, txt)

    # Announced up front, then repeated: a phone that was not listening when the server started
    # would otherwise never hear about it until it happened to ask.
    last = 0.0
    sock.settimeout(1.0)
    while not stop.is_set():
        now = time.time()
        if now - last > 20:
            try:
                sock.sendto(packet, (MDNS_ADDR, MDNS_PORT))
            except OSError:
                pass
            last = now
        try:
            data, addr = sock.recvfrom(4096)
        except (socket.timeout, OSError):
            continue
        # Answer anything that mentions our service type. Parsing the question section properly
        # is not worth it here: the cost of replying to a query that was not ours is one packet.
        if SERVICE.rstrip(".").encode("utf-8").split(b".")[0] in data:
            try:
                sock.sendto(packet, (MDNS_ADDR, MDNS_PORT))
            except OSError:
                pass


# ────────────────────────────── seeded content ──────────────────────────────


def seed(root):
    """Known content, created here rather than copied from anywhere real."""
    made = []
    os.makedirs(root, exist_ok=True)

    notes = os.path.join(root, "notes.txt")
    if not os.path.exists(notes):
        with open(notes, "w", encoding="utf-8") as fh:
            fh.write("A plain file on a simulated share.\n")
        made.append("notes.txt")

    archive = os.path.join(root, "demo-archive.zip")
    if not os.path.exists(archive):
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
            z.writestr("readme.txt", "Opened from a network drive, without downloading it.\n")
            z.writestr("data/numbers.csv", "n,square\n" + "".join(
                "%d,%d\n" % (n, n * n) for n in range(1, 200)
            ))
            z.writestr("data/nested/deep.txt", "Three levels down.\n")
            # Big enough that fetching the whole thing is visibly different from reading its
            # index, which is the behaviour being checked.
            z.writestr("payload.bin", bytes(random.getrandbits(8) for _ in range(400_000)))
        made.append("demo-archive.zip")

    apk = os.path.join(root, "demo-app.apk")
    if not os.path.exists(apk):
        built = os.path.join(
            HERE, "..", "..", "app", "build", "outputs", "apk", "github", "debug",
            "app-github-debug.apk",
        )
        if os.path.exists(built):
            __import__("shutil").copyfile(built, apk)
            made.append("demo-app.apk (this project's own debug build)")
        else:
            print("  ! no debug APK built yet - run ./gw.sh :app:assembleGithubDebug", flush=True)

    folder = os.path.join(root, "a folder")
    if not os.path.isdir(folder):
        os.makedirs(folder)
        with open(os.path.join(folder, "inside.txt"), "w", encoding="utf-8") as fh:
            fh.write("A file one level down, for testing navigation.\n")
        made.append("a folder/")
    return made


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.path.join(HERE, "sim-share"))
    ap.add_argument("--port", type=int, default=18080)
    ap.add_argument("--code", default="sim1")
    ap.add_argument("--name", default="PC-Sim")
    ap.add_argument("--read-only", action="store_true")
    ap.add_argument("--no-announce", action="store_true")
    args = ap.parse_args()

    ROOT = os.path.abspath(args.root)
    CODE = args.code
    WRITABLE = not args.read_only

    made = seed(ROOT)
    ip = local_ip()
    print("filet-sim")
    print("  serving  %s" % ROOT)
    print("  address  http://%s:%d/a/%s" % (ip, args.port, CODE))
    print("  code     %s" % CODE)
    print("  writable %s" % WRITABLE)
    if made:
        print("  seeded   %s" % ", ".join(made))

    stop = threading.Event()
    if not args.no_announce:
        instance = "Filet-%s-Sim share.%s" % (args.name, SERVICE)
        host = "filet-sim.local."
        txt = {"path": "/a/", "scope": "Simulated share", "w": "0" if args.read_only else "1",
               "id": device_id()}
        threading.Thread(
            target=announce_forever,
            args=(instance, host, args.port, ip, txt, stop),
            daemon=True,
        ).start()
        print("  announcing as %s" % instance.split(".")[0])

    print("  ready - open Filet, Network and root, and look under Phones on this network")
    try:
        ThreadingHTTPServer(("0.0.0.0", args.port), Share).serve_forever()
    except KeyboardInterrupt:
        stop.set()
