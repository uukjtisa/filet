# Filet report API

Lets the Filet app file an issue on `uukjtisa/filet` for somebody who has no GitHub account.
The app sends a report here, and this service creates the issue using a token that lives only in
Vercel's environment. No GitHub credential is ever in the APK.

One route, `POST /api/report`. Nothing else in the service writes anything.

## What it needs

### Environment variables

| Variable | Required | What it is |
|---|---|---|
| `REPORT_GITHUB_TOKEN` | yes | The fine-grained token described below. |
| `REPORT_KEY_SALT` | yes | Any long random string, e.g. `openssl rand -hex 32`. IPs and install ids are hashed with it before they are stored, so the store never holds a raw IP. If you change it, all rate-limit counters reset. |
| `KV_REST_API_URL` + `KV_REST_API_TOKEN` | yes | The Redis store holding the rate-limit counters. The Upstash integration sets these for you; older installs of it name them `UPSTASH_REDIS_REST_URL` / `UPSTASH_REDIS_REST_TOKEN`, and both pairs work. |
| `BLOB_READ_WRITE_TOKEN` | no | Vercel Blob, for photos. Without it, reports are still filed, and the issue says that a photo was sent but not stored. |

Local testing only - never set these on a deployment:

| Variable | Effect |
|---|---|
| `REPORT_DEV_MEMORY_LIMITER=1` | Keeps counters in memory instead of Redis. Ignored on a production deployment. |
| `REPORT_GITHUB_API` | Sends issues to a different API base, such as the fake GitHub in `dev/`. Ignored on a production deployment. |
| `REPORT_TRUST_TEST_IP=1` | `dev/server.mjs` only: lets an `x-test-ip` header choose the client IP. |

**Without a store, it refuses every report** with `503 limiter_unavailable`. That is deliberate.
A serverless function's memory belongs to one short-lived instance, so a counter kept there is not
a rate limit. An endpoint that holds a write token and has no limit is the one thing this service
must never be.

### The token: fine-grained, one repository, Issues only

Create it at GitHub → Settings → Developer settings → **Fine-grained tokens** → Generate new token:

- **Resource owner:** `uukjtisa`
- **Repository access:** *Only select repositories* → `uukjtisa/filet`
- **Repository permissions:** **Issues: Read and write**. Leave everything else at *No access*.
  GitHub adds *Metadata: Read-only* automatically, and that one is required.
- **Expiration:** a year at most. Put a reminder in the calendar to rotate it.

Not a classic token, and never one with `repo` scope. `repo` can read and push code, edit
workflows and change settings on every repository the account owns. If this endpoint, or Vercel's
environment, ever leaks, that is what the leak would be worth.

**What the narrow token can do if it leaks:** open, edit, comment on, label, close and lock issues
on `uukjtisa/filet`, and read its metadata. **What it cannot do:** read or push code, open or
merge pull requests, touch workflows, releases, settings or secrets, or reach any other repository.
The worst case is spam on one issue tracker, which you can clean up, plus revoking one token.

The code itself only ever calls `POST /repos/uukjtisa/filet/issues`. The repository is fixed in
`lib/config.mjs`, not read from the environment, so a mistyped variable cannot point the token at
something else.

## Deploy

1. Create the label: on the repository go to Issues → Labels → New label, named **`app-report`**.
   (If it is missing the report is still filed, just without the label.)
2. Vercel → Add New → Project → import the `filet` repository. Set **Root Directory** to
   `report-api`. Framework preset **Other**. No build command.
3. In the project go to Storage → Create → **Upstash for Redis** (free plan) → connect it to this project.
   This sets `KV_REST_API_URL` and `KV_REST_API_TOKEN`.
4. Optional: Storage → Create → **Blob** → connect it to this project. This sets `BLOB_READ_WRITE_TOKEN`.
5. Settings → Environment Variables: add `REPORT_GITHUB_TOKEN` and `REPORT_KEY_SALT` for
   *Production*. Mark both as **Sensitive**.
6. Deploy. The endpoint is `https://<project>.vercel.app/api/report`. That URL goes into the app.
7. Check it: `curl -i https://<project>.vercel.app/api/report` should answer
   `405 method_not_allowed` with a JSON body. Then file one real report from the app and confirm the
   issue appears with the `[app]` prefix and the `app-report` label.

## Limits

| What | Limit | Why |
|---|---|---|
| Per install id | 3 per hour, 10 per day | A genuine reporter sends a handful at a time; more than that is a retry loop or a script. The id is sent by the app and anyone can forge it, so this limit only meters honest clients. |
| Per IP (IPv6 per /64) | 10 per hour, 30 per day | A client cannot choose its IP, but a carrier NAT puts many phones behind one address, so this is looser than the install limit. IPv6 is counted per /64 because one phone can rotate through a whole /64. |
| Everyone | 100 per day | Caps what a distributed attack can do: at most 100 issues, which can be bulk-closed. Only reports that passed validation count here, so junk requests cannot lock everybody out. |
| Same draft (`submissionId`) | once per 24 h | A retry after a lost response gets the issue already filed (`200`, `replayed: true`) instead of a second one, and does not spend the install limit. |
| Same title and text, no `submissionId` | once per 24 h | The fallback for a client that sends no draft id. |
| Request | 4.3 MB | Just under Vercel's 4.5 MB ceiling, so the app gets this service's structured refusal and not the platform's blank one. |
| Photos | 3 per report, 1 MB each, PNG or JPEG | Checked from the bytes, not the declared type. EXIF/text metadata is stripped before upload. |
| Title / description | 3-120 / 10-10,000 characters | |
| Log | 30,000 characters | Longer logs are trimmed from the front, because the end is where the failure is. |

Windows are fixed (they reset on the hour or the day), so across a window boundary a client can
send up to twice a limit. That is accepted: the burst is bounded and short.

## Request and response

`POST /api/report` with `content-type: application/json`. A JSON content type is required: it
forces a browser to send a CORS preflight, which this service never answers, so no website can make
its visitors' browsers file reports.

```json
{
  "installId": "8 to 64 of A-Z a-z 0-9 _ -",
  "submissionId": "optional, same shape; one per draft, kept across retries",
  "type": "bug | feature | question | other",
  "title": "3-120 characters",
  "body": "10-10000 characters",
  "log": "optional text",
  "device": { "model": "Pixel 8", "android": "15", "app": "0.1.20" },
  "images": [{ "mime": "image/png", "data": "<base64, a data: prefix is allowed>" }]
}
```

`device` may also be a plain string; the Filet app sends a string, the exact text the reporter
reviewed. The app sends `type` as `bug`, `feature` or `other` (`question` is accepted and unused)
and JPEG photos only, re-encoded to at most 1600 px, which also strips EXIF. Unknown fields
are ignored.

A retry of a draft already filed answers `200` with the same body as the original `201`, plus
`"replayed": true`. A retry that arrives while the first attempt is still being filed answers
`409 in_progress` with `retryAfter: 5`.

Success, `201`:

```json
{ "ok": true, "issue": { "number": 42, "url": "https://github.com/uukjtisa/filet/issues/42" },
  "images": { "stored": 1, "dropped": [{ "index": 1, "reason": "upload failed" }] } }
```

Every refusal has the same envelope. Where retrying later will actually work, it also carries
`retryAfter` (seconds) and a `Retry-After` header:

```json
{ "ok": false, "error": { "code": "rate_limited", "message": "...", "scope": "install", "retryAfter": 1800 } }
```

| Status | `code` | Extra fields | Retry? |
|---|---|---|---|
| 400 | `bad_json` | | no |
| 400 | `invalid_field` | `field` | after fixing that field |
| 405 | `method_not_allowed` | | no |
| 409 | `duplicate` | | no, it was already filed (only without a `submissionId`) |
| 409 | `in_progress` | `retryAfter` | yes, in a few seconds: the same draft is mid-filing |
| 413 | `request_too_large` | `limit` | no |
| 413 | `too_many_images` | `field`, `limit` | after removing images |
| 413 | `image_too_large` | `field`, `index`, `limit` | after shrinking that image |
| 415 | `unsupported_media_type` | | no |
| 415 | `image_type` / `image_corrupt` | `field`, `index` | after replacing that image |
| 429 | `rate_limited` | `scope` (`install`, `ip` or `global`), `retryAfter` | yes, after `retryAfter` |
| 503 | `limiter_unavailable` | `retryAfter` | yes |
| 503 | `upstream_busy` | `retryAfter` | yes |
| 502 | `upstream_failed` | `retryAfter` | yes |
| 503 | `not_configured` | | no, the server needs fixing |
| 500 | `internal` | | maybe |

## What lands on GitHub

- **Title:** `[app] ` + the reporter's title. A zero-width space is placed after every `@` and `#`
  and inside `GH-` and `://`, and backticks and angle brackets are replaced, so the title mentions
  nobody, references nothing and links nowhere.
- **Body:** opens with one line marking it as a third-party report that has not been verified. After
  that, **every piece of reporter text sits inside a fenced code block**: description, device,
  and log (the log inside a collapsed `<details>`). GitHub processes no mentions, issue references,
  closing keywords, links or HTML inside a code block. Each fence is one backtick longer than the
  longest backtick run in its content, so nothing in the content can close it early.
- **Redaction**, applied to the description, device and log before filing: GitHub, AWS and Google
  key shapes, `Bearer`/`Basic` credentials, `password=`/`token:`-style values, email addresses,
  IPv4/IPv6/MAC addresses, and Filet's own `/a/<code>` WebDAV access codes. It catches what has a
  recognisable shape and nothing else, which is why the app warns that a report is public.
- **Photos** are uploaded to Vercel Blob and linked from the issue. If an upload fails, the
  report is filed anyway and the issue says which photo is missing.

## Run it locally

Node 22 or later. Nothing to install unless you want photo upload.

```sh
cd report-api
npm test                                   # the unit tests

# terminal 1: a fake GitHub that prints every issue it receives
node dev/fake-github.mjs

# terminal 2: the function, using the fake token and in-memory counters
REPORT_GITHUB_TOKEN=github_pat_FAKE_local_only REPORT_KEY_SALT=dev-salt \
REPORT_DEV_MEMORY_LIMITER=1 REPORT_GITHUB_API=http://127.0.0.1:3999 REPORT_TRUST_TEST_IP=1 \
node dev/server.mjs

# terminal 3
curl -i -X POST http://127.0.0.1:3000/api/report -H 'content-type: application/json' \
  -d '{"installId":"local-install-01","type":"bug","title":"Copy stalls","body":"It stops at 99 percent."}'
```

In PowerShell, set the variables with `$env:NAME = "value"` first, then run `node dev/server.mjs`.
