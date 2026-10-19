import { createHandler } from "../lib/handler.mjs";
import { storeFromEnv } from "../lib/stores.mjs";
import { blobFromEnv } from "../lib/blob.mjs";

/**
 * `POST /api/report` - the only route, and the only thing in this service that writes.
 *
 * Web-standard handlers: Vercel's Node runtime calls the export named after the method. Every
 * other method reaches the same pipeline through the other exports and is refused there with a
 * structured 405, rather than Vercel's bare one.
 *
 * The client IP is `x-real-ip`, which Vercel sets itself and does not take from the client.
 */
let handle;
function handler() {
  // Built on first use rather than at import, so the environment is read when a request exists.
  handle ??= createHandler({ env: process.env, store: storeFromEnv(process.env), blob: blobFromEnv(process.env) });
  return handle;
}

const ipOf = (request) =>
  request.headers.get("x-real-ip") || (request.headers.get("x-forwarded-for") || "").split(",")[0].trim() || "unknown";

export const POST = (request) => handler()(request, ipOf(request));
export const GET = POST;
export const PUT = POST;
export const DELETE = POST;
export const PATCH = POST;
