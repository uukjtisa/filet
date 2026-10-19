/**
 * The one write this service makes: create an issue.
 *
 * The token goes into one header of one request and nowhere else. A failure is reduced to its
 * status code and GitHub's Retry-After before it leaves this function; GitHub's response body is
 * read only to release the connection and is never returned, logged or echoed, so nothing it
 * contains can reach a client.
 */
export async function createIssue({ fetchImpl = fetch, api, repo, token, issue }) {
  const post = (payload) =>
    fetchImpl(`${api}/repos/${repo}/issues`, {
      method: "POST",
      headers: {
        authorization: `Bearer ${token}`,
        accept: "application/vnd.github+json",
        "x-github-api-version": "2022-11-28",
        "user-agent": "filet-report-api",
        "content-type": "application/json",
      },
      body: JSON.stringify(payload),
    });

  let res = await post(issue);
  // A 422 is most often the label: the token can file issues but the label is missing or cannot
  // be applied. Losing the label is better than losing the report, so it is filed once more bare.
  if (res.status === 422 && issue.labels?.length) {
    await drain(res);
    res = await post({ title: issue.title, body: issue.body });
  }
  if (res.status === 201) {
    const j = await res.json();
    return { ok: true, number: j.number, url: j.html_url };
  }
  await drain(res);
  return { ok: false, status: res.status, retryAfter: Number(res.headers.get("retry-after")) || null };
}

async function drain(res) {
  try { await res.arrayBuffer(); } catch { /* the body is not wanted */ }
}
