# site

The two public files Filet's links depend on. Neither holds anything secret.

| File | Served at | What it does |
|---|---|---|
| `l/index.html` | `https://uukjtisa.github.io/filet/l` | The page a Filet link lands on when a phone did not hand it to Filet: Open in Filet, or Get Filet. It reads the link's `#` part in the browser and shows only the drive's name. |
| `.well-known/assetlinks.json` | `https://uukjtisa.github.io/.well-known/assetlinks.json` | Android's check before it opens `https://uukjtisa.github.io/filet/l` links straight in Filet instead of the browser. It names the app and the certificates allowed to. |

The second one has to be at the root of the `uukjtisa.github.io` host, which GitHub Pages serves
from a repository of that exact name, not from this one. The copy here is the source; the
repository `uukjtisa.github.io` holds only that file.

The fingerprints are the release key's (`dev.niccc2007.filet`) and the debug key's
(`dev.niccc2007.filet.debug`). A new signing key means a new line here before its first release,
or links stop opening in Filet on phones that install that build.
