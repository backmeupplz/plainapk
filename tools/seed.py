#!/usr/bin/env python3
"""Builds docs/index.json, the catalogue the app downloads, from catalogue.txt.

For every repository it downloads the APK a phone would pick from the latest release and records the package name, the
app's name and the SHA-256 of its signing certificate as they really are, so the app can tell whether an installed app
can be updated without downloading anything. Entries that fail (no release, no APK) are left out and reported.

  python3 tools/seed.py   # needs build-tools (aapt2, apksigner) under $ANDROID_HOME and `gh` logged in for GitHub
"""
import concurrent.futures, glob, json, os, re, subprocess, sys, tempfile, urllib.parse, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = sorted(glob.glob(os.path.join(os.environ.get("ANDROID_HOME", "/opt/homebrew/share/android-commandlinetools"), "build-tools", "*")))[-1]
UNWANTED = ("debug", "fdroid", "unsigned", "wear", "-tv", "_tv")
ABIS = {"arm64-v8a": ("arm64", "aarch64", "armv8"), "armeabi-v7a": ("armeabi", "armv7", "arm32"),
        "x86_64": ("x86_64", "x86-64", "amd64", "x64"), "x86": ("x86", "i686")}


def get(url, headers=None):
    req = urllib.request.Request(url, headers={"User-Agent": "PlainAPK-seed", **(headers or {})})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def abi_of(name):
    s = name.lower()
    for abi, words in ABIS.items():  # same order as Forge.abiOf in the app
        if any(w in s for w in words):
            return abi
    return None


def pick(assets, glob_pattern=None, repo=""):
    """Forge.pick for an arm64 phone (what nearly every phone is)."""
    squash = lambda s: "".join(c for c in s.lower() if c.isalnum())
    repo_name = squash(repo.rsplit("/", 1)[-1])
    best = None
    for name, url in assets:
        if not name.lower().endswith(".apk") or not url.startswith("https://"):
            continue
        if glob_pattern and not re.fullmatch(".*".join(map(re.escape, glob_pattern.split("*"))), name, re.I):
            continue
        abi = abi_of(name)
        rank = {None: 1, "arm64-v8a": 0, "armeabi-v7a": 2}.get(abi)
        if rank is None:
            continue
        named = bool(repo_name) and repo_name in squash(name)
        key = (rank + 10 * sum(w in name.lower() for w in UNWANTED), (0 if named else 1000) + len(name))
        if best is None or key < best[0]:
            best = (key, name, url)
    return best and (best[1], best[2])


def release(repo):
    """(description, [(asset name, url)]) of the latest full release."""
    u = urllib.parse.urlparse(repo)
    path = u.path.strip("/")
    if u.netloc == "github.com":
        meta = json.loads(subprocess.check_output(["gh", "api", f"repos/{path}"]))
        rel = json.loads(subprocess.check_output(["gh", "api", f"repos/{path}/releases/latest"]))
        return meta.get("description") or "", [(a["name"], a["browser_download_url"]) for a in rel["assets"]]
    if u.netloc == "gitlab.com" or "gitlab" in u.netloc:
        enc = urllib.parse.quote(path, safe="")
        meta = json.loads(get(f"https://{u.netloc}/api/v4/projects/{enc}"))
        rel = json.loads(get(f"https://{u.netloc}/api/v4/projects/{enc}/releases/permalink/latest"))
        return meta.get("description") or "", [(l["name"], l.get("direct_asset_url") or l["url"]) for l in rel["assets"]["links"]]
    meta = json.loads(get(f"https://{u.netloc}/api/v1/repos/{path}"))
    rel = json.loads(get(f"https://{u.netloc}/api/v1/repos/{path}/releases/latest"))
    return meta.get("description") or "", [(a["name"], a["browser_download_url"]) for a in rel["assets"]]


def inspect(apk):
    badging = subprocess.check_output([f"{TOOLS}/aapt2", "dump", "badging", apk], text=True, stderr=subprocess.DEVNULL)
    pkg = re.search(r"package: name='([^']+)'", badging).group(1)
    label = re.search(r"application-label:'([^']*)'", badging)
    certs = subprocess.check_output([f"{TOOLS}/apksigner", "verify", "--print-certs", apk], text=True, stderr=subprocess.DEVNULL)
    signer = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})", certs).group(1)
    return pkg, (label.group(1) if label else pkg).replace("­", ""), signer


def entry(line):
    repo, *rest = line.split()
    asset_glob = rest[0] if rest else None
    summary, assets = release(repo)
    chosen = pick(assets, asset_glob, repo)
    if not chosen:
        raise RuntimeError("no APK for arm64 in the latest release")
    with tempfile.NamedTemporaryFile(suffix=".apk") as f:
        f.write(get(chosen[1]))
        f.flush()
        pkg, name, signer = inspect(f.name)
    e = {"id": pkg, "name": name, "repo": repo, "summary": summary.strip(), "signer": signer}
    if asset_glob:
        e["asset"] = asset_glob
    return e


def main():
    lines = [l.strip() for l in open(os.path.join(ROOT, "catalogue.txt")) if l.strip() and not l.startswith("#")]
    apps, failed = [], []
    with concurrent.futures.ThreadPoolExecutor(6) as pool:
        for line, fut in zip(lines, [pool.submit(entry, l) for l in lines]):
            try:
                apps.append(fut.result())
                print("ok  ", line, "→", apps[-1]["id"], file=sys.stderr)
            except Exception as e:
                failed.append(line)
                print("FAIL", line, "→", e, file=sys.stderr)
    seen = set()
    apps = [a for a in sorted(apps, key=lambda a: a["name"].lower()) if not (a["id"] in seen or seen.add(a["id"]))]
    with open(os.path.join(ROOT, "docs", "index.json"), "w") as f:
        json.dump({"version": 1, "apps": apps}, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print(f"{len(apps)} apps, {len(failed)} failed", file=sys.stderr)


if __name__ == "__main__":
    main()
