# site

The two public files Filet's links depend on. Neither holds anything secret.

| File | Served at | What it does |
|---|---|---|
| `l/index.html` | `https://uukjtisa.github.io/filet/l` | The page a Filet link lands on when a phone did not hand it to Filet: Open in Filet, or Get Filet. It reads the link's `#` part in the browser. A drive link shows only the drive's name. A Filet Portal link is opened in the browser: the sealed list of files is fetched from its host and opened with the key in the `#`, the names, sizes and dates are shown, and each file can be downloaded and opened right there - so a browser, or a Filet too old to read the link, still gets the files. |
| `.well-known/assetlinks.json` | `https://uukjtisa.github.io/.well-known/assetlinks.json` | Android's check before it opens `https://uukjtisa.github.io/filet/l` links straight in Filet instead of the browser. It names the app and the certificates allowed to. |

`l/index.html` is published from the `gh-pages` branch, which holds only it and `.nojekyll`; the
copy here is the source, and a change to it is copied there as its own commit.

A browser can download a Portal file only from a host that allows cross-site reads. Litterbox does
(checked 2026-11-15), and it is the first host a send tries for the list of files, so the list
always opens when Litterbox is on. A file kept on a host that does not fails on the page with that
reason, and on Android the page points to Open in Filet. The page's Content-Security-Policy lets it reach
those four hosts and nothing else.

The second one has to be at the root of the `uukjtisa.github.io` host, which GitHub Pages serves
from a repository of that exact name, not from this one. The copy here is the source; the
repository `uukjtisa.github.io` holds only that file.

The fingerprints are the release key's (`dev.niccc2007.filet`) and the debug key's
(`dev.niccc2007.filet.debug`). A new signing key means a new line here before its first release,
or links stop opening in Filet on phones that install that build.
