# Plain APK

A plain Android app store that installs and silently updates apps straight from their GitHub, Forgejo and GitLab releases, with no backend, no account and no trackers. Site: https://plainapk.com.

It's being built now; [SPEC.md](SPEC.md) describes the design.

The site lives in `docs/` and is served by GitHub Pages from the GitHub mirror, behind Cloudflare. The share image is rendered from `src/og.html` with headless Chrome:

```sh
"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --hide-scrollbars \
  --window-size=1200,630 --screenshot="$PWD/docs/og.png" "file://$PWD/src/og.html"
```

Bump `og.png?v=N` in `docs/index.html` after re-rendering.

Source of truth: https://git.borodutch.com/backmeupplz/plainapk, mirrored to https://github.com/backmeupplz/plainapk.

MIT license.
