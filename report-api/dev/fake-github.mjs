import { createServer } from "node:http";

/**
 * A stand-in for `api.github.com`, so the success path runs end to end with a fake token.
 *
 * Answers `POST /repos/<owner>/<repo>/issues` with 201 and prints the issue it was given, which
 * is how the title prefix, the label and the fenced body are checked by eye. `FAKE_GITHUB_STATUS`
 * makes it answer something else instead (401, 403, 500) to drive the upstream-failure paths.
 * The server only honours `REPORT_GITHUB_API` outside production.
 */
const port = Number(process.env.FAKE_GITHUB_PORT || 3999);
let n = 0;

createServer((req, res) => {
  let body = "";
  req.on("data", (c) => (body += c));
  req.on("end", () => {
    const forced = Number(process.env.FAKE_GITHUB_STATUS || 0);
    const auth = req.headers.authorization || "";
    console.log(`${req.method} ${req.url}  auth=${auth.startsWith("Bearer ") ? "Bearer <present>" : "<missing>"}`);
    if (forced) {
      res.writeHead(forced, { "content-type": "application/json", "retry-after": "30" });
      res.end(JSON.stringify({ message: "forced failure", documentation_url: "https://docs.github.com" }));
      return;
    }
    const issue = JSON.parse(body || "{}");
    n += 1;
    console.log("---- title:  " + issue.title);
    console.log("---- labels: " + JSON.stringify(issue.labels));
    console.log(issue.body + "\n----");
    res.writeHead(201, { "content-type": "application/json" });
    res.end(JSON.stringify({ number: n, html_url: `https://github.com/uukjtisa/filet/issues/${n}` }));
  });
}).listen(port, "127.0.0.1", () => console.log(`fake GitHub on http://127.0.0.1:${port}`));
