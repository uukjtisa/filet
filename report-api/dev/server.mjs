import { createServer } from "node:http";
import { Readable } from "node:stream";
import { POST } from "../api/report.mjs";

/**
 * The function on a laptop: `node dev/server.mjs`, then POST to http://127.0.0.1:3000/api/report.
 *
 * Mirrors what Vercel does in front of the function and no more: a Web `Request` is built from
 * the Node request, and `x-real-ip` is OVERWRITTEN with the socket's address, the way the platform
 * overwrites it, so a client cannot pick its own rate-limit key here either. Setting
 * `REPORT_TRUST_TEST_IP=1` lets an `x-test-ip` header choose the address instead, which is the
 * only way to show two IPs being counted separately from one machine.
 */
const port = Number(process.env.PORT || 3000);

createServer(async (req, res) => {
  if (!req.url.startsWith("/api/report")) {
    res.writeHead(404).end();
    return;
  }
  const headers = new Headers();
  for (const [k, v] of Object.entries(req.headers)) if (typeof v === "string") headers.set(k, v);
  const ip = process.env.REPORT_TRUST_TEST_IP === "1" && req.headers["x-test-ip"] ? req.headers["x-test-ip"] : req.socket.remoteAddress;
  headers.set("x-real-ip", String(ip));
  const hasBody = req.method !== "GET" && req.method !== "HEAD";
  const request = new Request(`http://127.0.0.1:${port}${req.url}`, {
    method: req.method,
    headers,
    body: hasBody ? Readable.toWeb(req) : undefined,
    duplex: "half",
  });
  const response = await POST(request);
  res.writeHead(response.status, Object.fromEntries(response.headers));
  res.end(Buffer.from(await response.arrayBuffer()));
}).listen(port, "127.0.0.1", () => console.log(`report-api on http://127.0.0.1:${port}/api/report`));
