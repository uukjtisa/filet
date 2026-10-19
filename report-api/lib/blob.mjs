import { randomBytes } from "node:crypto";

/**
 * Where screenshots are put so an issue can link them.
 *
 * Decision: upload and link, to Vercel Blob. The two alternatives both fail:
 *
 *  - **Inline in a collapsed block.** GitHub drops `data:` image URLs, so an inlined image does
 *    not render, and an issue body is capped at 65,536 characters - a 1 MB screenshot is 1.3
 *    million characters of base64. It cannot fit, let alone be read.
 *  - **Commit it to the repository** through the contents API. That needs `contents: write`,
 *    which turns an endpoint that can only open issues into one that can push to the code if it
 *    is ever abused. The narrow token scope is the point of this design.
 *
 * Blob URLs are public and carry a random path, which matches what they are: part of a public
 * issue. The app tells the reporter so before sending.
 *
 * `@vercel/blob` is imported only when storage is configured, so the tests and a local run with
 * no storage need no `npm install`. Apache-2.0, which GPL-3 can link.
 */
export function blobFromEnv(env) {
  const token = env.BLOB_READ_WRITE_TOKEN;
  if (!token) return null;
  return {
    async put(bytes, mime) {
      const { put } = await import("@vercel/blob");
      const ext = mime === "image/png" ? "png" : "jpg";
      const name = `reports/${new Date().toISOString().slice(0, 10)}/${randomBytes(12).toString("hex")}.${ext}`;
      const r = await put(name, bytes, { access: "public", contentType: mime, token, addRandomSuffix: false });
      return r.url;
    },
  };
}

/**
 * Store every image, never failing the report because one did not store.
 *
 * A report that is refused because its screenshot could not be uploaded loses the words, which
 * are the part that matters. So each failure becomes a `dropped` entry that the issue mentions
 * and the response reports, and the issue is filed regardless.
 */
export async function storeAll(blob, images) {
  const stored = [];
  const dropped = [];
  for (let index = 0; index < images.length; index++) {
    if (!blob) { dropped.push({ index, reason: "storage not configured" }); continue; }
    try {
      stored.push({ index, url: await blob.put(images[index].bytes, images[index].mime) });
    } catch {
      dropped.push({ index, reason: "upload failed" });
    }
  }
  return { stored, dropped };
}
