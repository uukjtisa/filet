"""
This PC's drives, offered to Filet over WebDAV, until the desktop tool exists.

    python pc-drives.py                      C: read-only and D: writable, one port
    python pc-drives.py --share e=E:\\        add another drive
    python pc-drives.py --port 11180         pick the starting port
    python pc-drives.py --writable c         let C: be written to as well
    python pc-drives.py --no-announce        serve only, add the address by hand

## What this is, and what it is not

A stopgap. `D:\\CPP_programs\\filet-desktop\\SPEC.md` describes the real thing - a window, a
folder picker, a generated password, autostart, and mounting the phone back as a drive letter -
and none of that is here. This is the half that is needed first: the drives appear in Filet's
network list by themselves, and they are fast because they are on the wire rather than on a
cable.

It deliberately does NOT reimplement the protocol. The announcement is loaded out of
`filet-sim.py` beside it, because the service type, the record layout and the TXT keys are a
contract shared with the phone - two copies of those would be two things to keep in step, and
the one that drifts is the one nobody runs.

## Several drives, one port

The same shape the real server uses: `/a/<code>` names the share, so one socket, one accept
loop, one announcement thread, and a share per code. A port per drive would mean a firewall
rule each and an announcement lifecycle each, and buy nothing - the code already distinguishes
them.

## A drive ROOT is the awkward case, and this is where it bites

`C:\\` is not an ordinary folder. Listing it returns `$Recycle.Bin`, `System Volume
Information`, `pagefile.sys` and `hiberfil.sys`, and a plain `os.stat` on several of those
raises `PermissionError` even from an administrator shell. The naive loop therefore dies
halfway through building the XML and the client sees a 500 on the drive root while every folder
inside it lists perfectly - which reads as "the share is broken" rather than "three entries are
not mine to describe".

So every entry is statted inside a guard and an entry that cannot be described is **left out of
the listing**. Leaving it out is the right answer rather than a compromise: a row the client
cannot stat, open or copy is a row that only produces an error when it is touched.

## Writing

C: is read-only and D: is writable, which is a decision rather than an oversight. This serves
plain HTTP on a LAN with the access code as the only credential, and the system drive's root is
not somewhere to hand that. `--writable c` overrides it.

## Windows will ask about the firewall

Allow it on private networks. Refused, the phone discovers the service and then cannot connect
to it, which looks exactly like a bug in the app.
"""
import argparse
import importlib.util
import os
import socket
import struct
import sys
import threading
import time
import urllib.parse
from email.utils import formatdate
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))


def _load_sim():
    """
    `filet-sim.py` as a module, for the parts that are a wire contract.

    Imported by path because the file name has a hyphen in it and cannot be imported by name.
    What is taken: the mDNS record builders, the service type, the group address, the outbound
    address probe and the range parser. All of those have to match the phone exactly, and the
    phone's copy of them is what that file was verified against.
    """
    path = os.path.join(HERE, "filet-sim.py")
    spec = importlib.util.spec_from_file_location("filet_sim", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


SIM = _load_sim()
SERVICE = SIM.SERVICE
MDNS_ADDR, MDNS_PORT = SIM.MDNS_ADDR, SIM.MDNS_PORT

#: code -> {"root": absolute path, "label": display name, "writable": bool}
SHARES = {}


# ────────────────────────────── the shares ──────────────────────────────


class Drives(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "filet-pc-drives/1"

    def log_message(self, fmt, *args):
        sys.stderr.write("  %-8s %s\n" % (self.command, self.path))

    # ── addressing ──

    def _share(self):
        """
        The share this request names, and the path inside it.

        Returns `(share, rel)` or `(None, None)`. `DavWWWRoot` is stripped because Windows
        inserts it as a marker and it is not a folder - letting it reach a path turns every
        request from Explorer into a 404 one level too deep.
        """
        path = urllib.parse.unquote(urllib.parse.urlsplit(self.path).path)
        parts = [p for p in path.split("/") if p and p != "DavWWWRoot"]
        if len(parts) < 2 or parts[0] != "a":
            return None, None
        share = SHARES.get(parts[1].lower())
        if share is None:
            return None, None
        return share, "/".join(parts[2:])

    def _local(self):
        share, rel = self._share()
        if share is None:
            return None, None
        root = share["root"]
        target = os.path.normpath(os.path.join(root, rel)) if rel else root
        # Traversal guard. `commonpath` raises on paths from two different drives, which is
        # itself a refusal, so the exception is the answer rather than something to report.
        try:
            if os.path.commonpath([os.path.abspath(target), root]) != root:
                return None, None
        except ValueError:
            return None, None
        return share, target

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
        self.send_header("DAV", "1,2")
        self.send_header("Allow", "OPTIONS,GET,HEAD,PUT,DELETE,PROPFIND,MKCOL,MOVE")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_PROPFIND(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n:
            self.rfile.read(n)
        share, target = self._local()
        if target is None or not os.path.exists(target):
            return self._send(404)
        depth = self.headers.get("Depth", "1")
        base = urllib.parse.unquote(urllib.parse.urlsplit(self.path).path)

        out = ['<?xml version="1.0" encoding="utf-8"?>', '<D:multistatus xmlns:D="DAV:">']
        self._describe(out, base, target, force_dir=os.path.isdir(target))
        if depth == "1" and os.path.isdir(target):
            prefix = base if base.endswith("/") else base + "/"
            try:
                names = sorted(os.listdir(target))
            except OSError:
                names = []
            for name in names:
                self._describe(out, prefix + name, os.path.join(target, name))

        if os.path.isdir(target):
            # RFC 4331, so a mounted drive reports a size instead of "size unknown". An
            # unknown figure sends no property at all rather than zero - zero free is a
            # meaningful and alarming answer that makes a client refuse to copy.
            try:
                usage = __import__("shutil").disk_usage(target)
                out.append(
                    "<D:response><D:href>%s</D:href><D:propstat><D:prop>"
                    "<D:quota-available-bytes>%d</D:quota-available-bytes>"
                    "<D:quota-used-bytes>%d</D:quota-used-bytes>"
                    "</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>"
                    % (urllib.parse.quote(base), usage.free, usage.used)
                )
            except OSError:
                pass
        out.append("</D:multistatus>")
        self._send(207, "".join(out).encode("utf-8"), 'application/xml; charset="utf-8"')

    def _describe(self, out, href, path, force_dir=None):
        """
        One `<D:response>`, or nothing at all when the entry cannot be described.

        The guard is the whole point of this function. See the module note: a drive root holds
        entries that raise `PermissionError` on `stat`, and an unguarded loop turns three
        undescribable entries into a 500 for the entire listing.
        """
        try:
            is_dir = os.path.isdir(path) if force_dir is None else force_dir
            st = os.stat(path)
        except OSError:
            return
        quoted = urllib.parse.quote(href)
        # A collection's href ends in a slash, or Explorer treats it as a file and will not
        # open it.
        if is_dir and not quoted.endswith("/"):
            quoted += "/"
        name = os.path.basename(path.rstrip("/\\")) or path
        out.append("<D:response><D:href>%s</D:href><D:propstat><D:prop>" % quoted)
        out.append("<D:displayname>%s</D:displayname>" % _xml(name))
        out.append("<D:getlastmodified>%s</D:getlastmodified>" % formatdate(st.st_mtime, usegmt=True))
        if is_dir:
            out.append("<D:resourcetype><D:collection/></D:resourcetype>")
        else:
            out.append("<D:resourcetype/>")
            out.append("<D:getcontentlength>%d</D:getcontentlength>" % st.st_size)
        out.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")

    def do_HEAD(self):
        share, target = self._local()
        if target is None or not os.path.isfile(target):
            return self._send(404)
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(os.path.getsize(target)))
        self.send_header("Accept-Ranges", "bytes")
        self.end_headers()

    def do_GET(self):
        share, target = self._local()
        if target is None or not os.path.isfile(target):
            return self._send(404)
        try:
            size = os.path.getsize(target)
        except OSError:
            return self._send(403)
        span = SIM.parse_range(self.headers.get("Range"), size)
        try:
            if span is None:
                return self._stream(target, 0, size - 1, size, partial=False)
            start, end = span
            if start >= size:
                self.send_response(416)
                self.send_header("Content-Range", "bytes */%d" % size)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            return self._stream(target, start, end, size, partial=True)
        except PermissionError:
            # A file the share can see and cannot read. 403 is the honest answer; a 500 would
            # make it look like the server fell over.
            return self._send(403)

    def _stream(self, target, start, end, size, partial):
        """
        Send bytes in chunks rather than reading the file into memory.

        `filet-sim.py` reads whole files, which is fine for a fixture holding a 400 KB archive.
        A real drive holds video: reading one into a buffer before answering would spend a
        gigabyte of RAM per request and stall every other connection while it did it.
        """
        length = end - start + 1
        self.send_response(206 if partial else 200)
        self.send_header("Content-Type", "application/octet-stream")
        if partial:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        self.end_headers()
        left = length
        with open(target, "rb") as fh:
            fh.seek(start)
            while left > 0:
                chunk = fh.read(min(CHUNK, left))
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except (ConnectionResetError, BrokenPipeError):
                    # A client that stopped reading - a seek in a video player, a cancelled
                    # copy. Routine, and not worth a stack trace in the log.
                    return
                left -= len(chunk)

    def do_PUT(self):
        share, target = self._local()
        if target is None or not share["writable"]:
            return self._send(403)
        n = int(self.headers.get("Content-Length") or 0)
        if self.headers.get("Expect", "").lower() == "100-continue":
            self.wfile.write(b"HTTP/1.1 100 Continue\r\n\r\n")
        # Written to a temporary sibling and moved into place once every declared byte has
        # arrived. A short body is a failed transfer, not a small file - truncating the target
        # first is how a dropped connection destroys the copy that was already there.
        os.makedirs(os.path.dirname(target), exist_ok=True)
        tmp = target + ".filet-part"
        got = 0
        try:
            with open(tmp, "wb") as fh:
                while got < n:
                    chunk = self.rfile.read(min(CHUNK, n - got))
                    if not chunk:
                        break
                    fh.write(chunk)
                    got += len(chunk)
            if got != n:
                os.remove(tmp)
                return self._send(409)
            os.replace(tmp, target)
        except OSError:
            if os.path.exists(tmp):
                try:
                    os.remove(tmp)
                except OSError:
                    pass
            return self._send(403)
        self._send(201)

    def do_MKCOL(self):
        share, target = self._local()
        if target is None or not share["writable"]:
            return self._send(403)
        if os.path.exists(target):
            return self._send(405)
        try:
            os.makedirs(target)
        except OSError:
            return self._send(403)
        self._send(201)

    def do_DELETE(self):
        share, target = self._local()
        if target is None or not share["writable"]:
            return self._send(403)
        if not os.path.exists(target):
            return self._send(404)
        try:
            if os.path.isdir(target):
                __import__("shutil").rmtree(target)
            else:
                os.remove(target)
        except OSError:
            return self._send(403)
        self._send(204)

    def do_MOVE(self):
        share, target = self._local()
        if target is None or not share["writable"]:
            return self._send(403)
        dest_header = self.headers.get("Destination")
        if not dest_header:
            return self._send(400)
        saved, self.path = self.path, urllib.parse.urlsplit(dest_header).path
        dshare, dest = self._local()
        self.path = saved
        if dest is None or dshare is not share or not share["writable"]:
            return self._send(403)
        try:
            os.replace(target, dest)
        except OSError:
            return self._send(403)
        self._send(204)


def _xml(text):
    return (
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    )


#: Read and write in 256 KB pieces. Large enough that the syscall overhead stops mattering on
#: a gigabit LAN, small enough that one request never holds much memory.
CHUNK = 256 * 1024


# ────────────────────────────── announcing ──────────────────────────────


def local_ips():
    """
    Every address this machine can be reached on, not just the one with the default route.

    **This is the bug that would have made the whole thing look broken, so it gets the note.**
    `filet-sim.py` picks one address by opening a UDP socket towards 8.8.8.8 and reading back
    whichever interface the OS chose. That is the right answer for a machine with one network
    and the wrong one for a machine with two: this box has Ethernet on 192.168.5.x carrying the
    default route and Wi-Fi on 192.168.100.x, and the phone is on the Wi-Fi. The probe returns
    the Ethernet address every time, so the announcement named a host the phone cannot route to
    and joined the multicast group on an interface the phone is not on. The share would have
    been announced into an empty room, and the symptom - discovered, then unreachable, or never
    discovered at all - says nothing about the cause.

    So every interface is announced on, each with its own address in its own A record. The HTTP
    server is already bound to `0.0.0.0` and needed no change.

    `169.254.x` is dropped: that is an interface that asked for a lease and never got one, so
    nothing on the network shares it. Loopback is dropped for the obvious reason.
    """
    found = []
    for probe in (
        lambda: socket.gethostbyname_ex(socket.gethostname())[2],
        lambda: [a[4][0] for a in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)],
    ):
        try:
            found.extend(probe())
        except OSError:
            continue
    # The default-route probe as well, because a machine whose hostname does not resolve to its
    # own addresses still answers this one.
    try:
        found.append(SIM.local_ip())
    except OSError:
        pass
    out = []
    for ip in found:
        if ip in out or ip.startswith("127.") or ip.startswith("169.254."):
            continue
        out.append(ip)
    return out


def announce_forever(by_ip, stop, changed):
    """
    One socket, every share, every interface.

    A socket per share was the first shape and it is wrong on Windows: two threads binding UDP
    5353 with `SO_REUSEADDR` sometimes works, sometimes gives one of them nothing, and which
    happens is not something a run tells you. One socket has no such question in it - group
    membership can be added once per interface, and `IP_MULTICAST_IF` set per send decides which
    one a packet actually leaves by.

    That last part is load-bearing. Without setting it, every packet goes out of whichever
    interface the routing table prefers, so the Wi-Fi's A record would be broadcast on the
    Ethernet and never reach the Wi-Fi at all.

    @param by_ip address -> the packets whose A record names that address.

    Returns when [stop] or [changed] is set, so the caller can rebuild it on a new address.
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(("", MDNS_PORT))
    except OSError as e:
        print("  ! could not bind mDNS port 5353 (%s)" % e)
        print("    serving anyway - add the address by hand on the phone")
        sock.close()
        return
    joined = []
    for ip in by_ip:
        try:
            sock.setsockopt(
                socket.IPPROTO_IP,
                socket.IP_ADD_MEMBERSHIP,
                socket.inet_aton(MDNS_ADDR) + socket.inet_aton(ip),
            )
            joined.append(ip)
        except OSError as e:
            print("  ! no mDNS group on %s (%s)" % (ip, e))
    if not joined:
        print("    serving anyway - add the address by hand on the phone")
        sock.close()
        return
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
    sock.settimeout(1.0)

    #: Every packet this thread sends, so one that comes back is recognised rather than
    #: answered.
    #:
    #: **This is the fix for a real packet storm, measured rather than imagined.** The
    #: responder answers anything mentioning the service type, and a multicast sender receives
    #: its own traffic - so its own announcement arrived, matched, and provoked another. With
    #: several copies of this script left running at once it reached 150,000 packets in 26
    #: seconds, and even one copy managed 2,108.
    #:
    #: `IP_MULTICAST_LOOP = 0` also stops it and was the first attempt. It is the wrong tool:
    #: it is a blunt switch that silences the records for every program on this machine, so
    #: nothing local can confirm the share is being announced at all - which is exactly how
    #: this was being verified. Recognising our own bytes fixes the actual fault and leaves
    #: the announcement observable from the box it comes from.
    mine = {p for ps in by_ip.values() for p in ps}

    def blast():
        for ip in joined:
            try:
                sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(ip))
            except OSError:
                continue
            for p in by_ip[ip]:
                try:
                    sock.sendto(p, (MDNS_ADDR, MDNS_PORT))
                except OSError:
                    pass

    needle = SERVICE.rstrip(".").encode("utf-8").split(b".")[0]
    last = 0.0
    answered = 0.0
    try:
        while not stop.is_set() and not changed.is_set():
            now = time.time()
            if now - last > 20:
                blast()
                last = now
            try:
                data, _ = sock.recvfrom(4096)
            except (socket.timeout, OSError):
                continue
            # Answer anything that mentions the service type. Parsing the question section
            # properly is not worth it: the cost of replying to a query that was not ours is
            # one packet.
            #
            # Two guards on that, both of which exist because the cost turned out not to be
            # one packet. Our own records are never answered, and however many queries arrive
            # the answer goes out at most once a second - so a burst of them, or another
            # responder on the LAN echoing, costs a bounded amount rather than a storm.
            if needle in data and data not in mine and now - answered > 1.0:
                blast()
                answered = now
    finally:
        sock.close()


def watch_network(port, name, stop):
    """
    Re-announce when this machine's address changes, and keep the port where it was.

    The announcement is the thing that goes stale, not the socket. The HTTP server is bound to
    `0.0.0.0` and keeps answering on whatever address the machine now holds; what breaks is the
    mDNS record, which still names the old one and is a membership on an interface that has
    gone away. The phone then finds the share and cannot reach it, which is indistinguishable
    from the share being off.

    **The port is deliberately NOT changed.** A new port on every network change would make the
    address a desktop stores - `http://host:port/a/code` - wrong exactly when the drive is being
    reconnected to, which is the one moment it has to be right. A port is only chosen once, at
    startup, and only because the preferred one may already be taken.
    """
    current = []
    announcer = None
    changed = threading.Event()
    while not stop.is_set():
        ips = local_ips()
        # Compared as a SET, the same rule `NetIdentity.changed` uses in the app. The OS hands
        # these back in whatever order it holds them, and an interface re-describing itself
        # would otherwise read as a network change on every poll - which means tearing the
        # announcement down and rebuilding it every five seconds, and a share that flickers in
        # and out of somebody else's list.
        if ips and set(ips) != set(current):
            if announcer is not None:
                changed.set()
                announcer.join(timeout=3)
                changed = threading.Event()
                print("  network moved: %s -> %s, re-announcing"
                      % (", ".join(current) or "-", ", ".join(ips)))
            current = ips
            by_ip = {
                ip: [
                    SIM.announcement(
                        "Filet-%s-%s.%s" % (name, s["label"], SERVICE),
                        "filet-pc-%s.local." % code,
                        port,
                        ip,
                        {
                            "path": "/a/",
                            "scope": s["label"],
                            "w": "1" if s["writable"] else "0",
                            "id": SIM.device_id(),
                        },
                    )
                    for code, s in SHARES.items()
                ]
                for ip in ips
            }
            announcer = threading.Thread(
                target=announce_forever, args=(by_ip, stop, changed), daemon=True,
            )
            announcer.start()
        stop.wait(5)
    changed.set()


def free_port(preferred, tries=20):
    """
    The preferred port, or the next free one above it.

    Chosen once. A port already in use is a stopgap started twice or a leftover process, and
    failing outright there would be correct but unhelpful - the alternative is being told the
    number that did work.
    """
    for n in range(tries):
        port = preferred + n
        probe = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            probe.bind(("0.0.0.0", port))
            return port
        except OSError:
            continue
        finally:
            probe.close()
    raise SystemExit("no free port in %d..%d" % (preferred, preferred + tries - 1))


# ────────────────────────────── running ──────────────────────────────


def drive_label(root):
    """`C:\\` as "C: drive", and a folder as its own name."""
    stripped = root.rstrip("\\/")
    if len(stripped) == 2 and stripped[1] == ":":
        return "%s drive" % stripped
    return os.path.basename(stripped) or stripped


if __name__ == "__main__":
    ap = argparse.ArgumentParser(
        description="This PC's drives, offered to Filet over WebDAV.",
    )
    ap.add_argument(
        "--share", action="append", default=[], metavar="CODE=PATH",
        help="a share, e.g. e=E:\\ . Repeatable. Replaces the C:/D: default when given.",
    )
    ap.add_argument("--port", type=int, default=11180)
    ap.add_argument("--name", default=socket.gethostname()[:20] or "PC")
    ap.add_argument(
        "--writable", action="append", default=["ddrive"], metavar="CODE",
        help="codes that accept writes. Default: ddrive.",
    )
    ap.add_argument("--no-announce", action="store_true")
    args = ap.parse_args()

    requested = args.share or ["cdrive=C:\\", "ddrive=D:\\"]
    for spec in requested:
        if "=" not in spec:
            raise SystemExit("--share wants CODE=PATH, got %r" % spec)
        code, _, path = spec.partition("=")
        code = code.strip().lower()
        root = os.path.abspath(path.strip())
        if not os.path.isdir(root):
            print("  ! skipping %s - %s is not a directory" % (code, root), flush=True)
            continue
        SHARES[code] = {
            "root": root,
            "label": drive_label(root),
            "writable": code in [w.lower() for w in args.writable],
        }
    if not SHARES:
        raise SystemExit("nothing to serve")

    port = free_port(args.port)
    ips = local_ips() or ["127.0.0.1"]

    # Unbuffered from here on. Python buffers stdout whenever it is not a terminal, so piping
    # this to a file or a log window showed nothing at all until the buffer filled - and the
    # first thing printed is the address, which is the one line somebody is waiting for.
    try:
        sys.stdout.reconfigure(line_buffering=True)
    except AttributeError:
        pass

    print("filet pc-drives")
    for code, s in SHARES.items():
        print("  %-8s %-28s %s" % (code, s["root"], "read+write" if s["writable"] else "read only"))
        for ip in ips:
            print("           http://%s:%d/a/%s" % (ip, port, code))
    if port != args.port:
        print("  port     %d was taken, using %d" % (args.port, port))
    print("  code      the last path segment above - type it into Filet once, per drive")
    if len(ips) > 1:
        print("  note     %d networks here, so each drive has an address per network." % len(ips))
        print("           Use the one on the same network as the phone; both are announced.")

    stop = threading.Event()
    if not args.no_announce:
        threading.Thread(target=watch_network, args=(port, args.name, stop), daemon=True).start()
        print("  announcing %d share(s) on %d network(s), re-announced when an address changes"
              % (len(SHARES), len(ips)))

    print("  ready - Filet, Remotes, under Phones on this network")
    server = ThreadingHTTPServer(("0.0.0.0", port), Drives)
    # A thread per connection, created on demand. Windows' redirector opens a new connection
    # per request and leaves the old ones open, so anything with a fixed worker count queues
    # the fourth request behind a socket timeout - which is the single worst bug hosting has
    # had, and it presented as the network being slow.
    server.daemon_threads = True
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        stop.set()
        print("\n  stopped")
