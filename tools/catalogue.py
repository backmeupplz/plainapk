#!/usr/bin/env python3
"""Builds the Plain APK catalogue: <out>/index.json plus <out>/icons/<package>.webp.

Candidates are the apps in the IzzyOnDroid and F-Droid indexes whose source lives on GitHub, GitLab or Codeberg, plus the
repositories listed in catalogue.txt. An app is listed only when the latest full release of its repository carries an APK
a phone would pick, and that APK really is the app: its package name and signing certificate are read from the file
itself, fetching just its zip directory, signing block and manifest with HTTP range requests instead of the whole APK.
Results are cached per download URL, so nightly runs only look at releases that changed.

  python3 tools/catalogue.py OUT_DIR [--cache cache.json] [--limit N]
Needs a GitHub token (GITHUB_TOKEN, or `gh auth token`) for one GraphQL query per 50 repositories, and Pillow for icons.
"""
import concurrent.futures, hashlib, io, json, os, re, struct, subprocess, sys, time, urllib.parse, urllib.request, zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UA = {"User-Agent": "PlainAPK-catalogue"}
INDEXES = [("izzy", "https://apt.izzysoft.de/fdroid/repo"), ("fdroid", "https://f-droid.org/repo")]
HOSTS = ("github.com", "gitlab.com", "codeberg.org")
UNWANTED = ("debug", "fdroid", "unsigned", "wear", "-tv", "_tv")
ABIS = {"arm64-v8a": ("arm64", "aarch64", "armv8"), "armeabi-v7a": ("armeabi", "armv7", "arm32"),
        "x86_64": ("x86_64", "x86-64", "amd64", "x64"), "x86": ("x86", "i686")}


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def http(url, headers=None, data=None, timeout=60):
    req = urllib.request.Request(url, data=data, headers={**UA, **(headers or {})})
    for attempt in range(5):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.status, dict(r.headers), r.read()
        except urllib.error.HTTPError as e:
            if e.code in (429, 502, 503) and attempt < 4:
                wait = e.headers.get("Retry-After", "")
                time.sleep(min(90, int(wait)) if wait.isdigit() else 10 * (attempt + 1))
                continue
            raise


# ---- picking the APK: the same rules as Forge.pick in the app, for an arm64 phone --------------------------------------

def abi_of(name):
    s = name.lower()
    for abi, words in ABIS.items():
        if any(w in s for w in words):
            return abi
    return None


def pick(assets, glob_pattern=None, repo=""):
    squash = lambda s: "".join(c for c in s.lower() if c.isalnum())
    repo_name = squash(repo.rsplit("/", 1)[-1])
    best = None
    for name, url, size in assets:
        if not name.lower().endswith(".apk") or not url.startswith("https://"):
            continue
        if glob_pattern and not re.fullmatch(".*".join(map(re.escape, glob_pattern.split("*"))), name, re.I):
            continue
        rank = {None: 1, "arm64-v8a": 0, "armeabi-v7a": 2}.get(abi_of(name))
        if rank is None:
            continue
        named = bool(repo_name) and repo_name in squash(name)
        key = (rank + 10 * sum(w in name.lower() for w in UNWANTED), (0 if named else 1000) + len(name))
        if best is None or key < best[0]:
            best = (key, (name, url, size))
    return best and best[1]


# ---- reading package name and signer from a remote APK ---------------------------------------------------------------

def ranged(url, start, end):
    status, headers, body = http(url, {"Range": f"bytes={start}-{end}"})
    if status != 206:
        raise IOError("server ignores Range")
    return body


def remote_size(url):
    status, headers, body = http(url, {"Range": "bytes=0-0"})
    m = re.search(r"/(\d+)$", headers.get("Content-Range", ""))
    if status != 206 or not m:
        raise IOError("server ignores Range")
    return int(m.group(1))


def lp(buf, off):
    """A uint32-length-prefixed block at off: (its bytes, offset after it)."""
    n = struct.unpack_from("<I", buf, off)[0]
    return buf[off + 4:off + 4 + n], off + 4 + n


def signer_from_block(block):
    """SHA-256 of the first certificate of the v3 (else v2) signer in an APK Signing Block."""
    pairs, off, end = {}, 8, len(block) - 24
    while off < end:
        n, pid = struct.unpack_from("<QI", block, off)
        pairs[pid] = block[off + 12:off + 8 + n]
        off += 8 + n
    value = pairs.get(0xF05368C0) or pairs.get(0x7109871A)
    if value is None:
        return None
    signers, _ = lp(value, 0)
    signer, _ = lp(signers, 0)
    signed, _ = lp(signer, 0)
    _, o = lp(signed, 0)  # digests
    certs, _ = lp(signed, o)
    cert, _ = lp(certs, 0)
    return hashlib.sha256(cert).hexdigest()


def axml_package(data):
    """The package attribute of <manifest> in a binary AndroidManifest.xml."""
    hsize = struct.unpack_from("<H", data, 2)[0]
    off, strings = hsize, []
    while off < len(data):
        ctype, chsize, csize = struct.unpack_from("<HHI", data, off)
        if ctype == 0x0001:  # string pool
            count, _, flags, sstart = struct.unpack_from("<IIII", data, off + 8)
            utf8 = flags & 0x100
            for i in range(count):
                p = off + sstart + struct.unpack_from("<I", data, off + chsize + 4 * i)[0]
                if utf8:
                    p += 2 if data[p] & 0x80 else 1
                    n = data[p] if not data[p] & 0x80 else ((data[p] & 0x7F) << 8) | data[p + 1]
                    p += 2 if data[p] & 0x80 else 1
                    strings.append(data[p:p + n].decode("utf-8", "replace"))
                else:
                    n = struct.unpack_from("<H", data, p)[0]
                    strings.append(data[p + 2:p + 2 + 2 * n].decode("utf-16-le", "replace"))
        elif ctype == 0x0102:  # first start tag: <manifest>
            ext = off + 16
            astart, asize, acount = struct.unpack_from("<HHH", data, ext + 8)
            for i in range(acount):
                a = ext + astart + i * asize
                name, raw = struct.unpack_from("<II", data, a + 4)
                if strings[name] == "package" and raw != 0xFFFFFFFF:
                    return strings[raw]
            return None
        off += csize
    return None


def inspect(url, size=None):
    """(package, signer sha256) of the APK at url, reading only the parts that matter."""
    size = size or remote_size(url)
    tail_start = max(0, size - 65557)
    tail = ranged(url, tail_start, size - 1)
    e = tail.rfind(b"PK\x05\x06")
    if e < 0:
        raise IOError("not a zip")
    cd_size, cd_off = struct.unpack_from("<II", tail, e + 12)
    footer = ranged(url, cd_off - 24, cd_off - 1)
    if footer[8:] != b"APK Sig Block 42":
        raise IOError("no v2/v3 signature")
    block_size = struct.unpack_from("<Q", footer, 0)[0]
    signer = signer_from_block(ranged(url, cd_off - block_size - 8, cd_off - 1))
    cd = ranged(url, cd_off, cd_off + cd_size - 1)
    p = 0
    while p < len(cd) and cd[p:p + 4] == b"PK\x01\x02":
        method, = struct.unpack_from("<H", cd, p + 10)
        csize, _, nlen, xlen, clen = struct.unpack_from("<IIHHH", cd, p + 20)
        local, = struct.unpack_from("<I", cd, p + 42)
        name = cd[p + 46:p + 46 + nlen]
        if name == b"AndroidManifest.xml":
            head = ranged(url, local, local + 29)
            ln, lx = struct.unpack_from("<HH", head, 26)
            start = local + 30 + ln + lx
            raw = ranged(url, start, start + csize - 1)
            data = zlib.decompress(raw, -15) if method == 8 else raw
            return axml_package(data), signer
        p += 46 + nlen + xlen + clen
    raise IOError("no manifest")


# ---- releases -------------------------------------------------------------------------------------------------------

def gh_token():
    return os.environ.get("GITHUB_TOKEN") or subprocess.check_output(["gh", "auth", "token"], text=True).strip()


def github_releases(paths):
    """owner/name → (canonical owner/name, tag, [(asset, url, size)]) for the latest full release, via GraphQL."""
    token, out = gh_token(), {}
    paths = sorted(set(paths))
    for i in range(0, len(paths), 50):
        chunk = paths[i:i + 50]
        q = "query{" + "".join(
            f'r{j}:repository(owner:{json.dumps(p.split("/")[0])},name:{json.dumps(p.split("/")[1])})'
            "{nameWithOwner isArchived latestRelease{tagName releaseAssets(first:60){nodes{name downloadUrl size}}}}"
            for j, p in enumerate(chunk)) + "}"
        _, _, body = http("https://api.github.com/graphql", {"Authorization": f"bearer {token}", "Content-Type": "application/json"},
                          json.dumps({"query": q}).encode())
        data = json.loads(body).get("data") or {}
        for j, p in enumerate(chunk):
            r = data.get(f"r{j}")
            if r and r["latestRelease"] and not r["isArchived"]:
                rel = r["latestRelease"]
                out[p] = (r["nameWithOwner"], rel["tagName"], [(a["name"], a["downloadUrl"], a["size"]) for a in rel["releaseAssets"]["nodes"]])
        log(f"github {min(i + 50, len(paths))}/{len(paths)}")
    return out


def gitlab_releases(paths):
    """group/project → (path, tag, assets) for the newest release, 50 projects per GraphQL query (anonymous REST is rate-limited hard)."""
    out, paths = {}, sorted(set(paths))
    for i in range(0, len(paths), 50):
        chunk = paths[i:i + 50]
        q = ("query($p:[String!]){projects(fullPaths:$p,first:50){nodes{fullPath archived "
             "releases(first:1,sort:RELEASED_AT_DESC){nodes{tagName assets{links{nodes{name directAssetUrl url}}}}}}}}")
        try:
            _, _, body = http("https://gitlab.com/api/graphql", {"Content-Type": "application/json"}, json.dumps({"query": q, "variables": {"p": chunk}}).encode())
        except Exception as e:
            log(f"  gitlab batch {i}: {e}")
            continue
        for node in (json.loads(body).get("data") or {}).get("projects", {}).get("nodes", []):
            rels = node["releases"]["nodes"]
            if rels and not node["archived"]:
                links = rels[0]["assets"]["links"]["nodes"]
                out[node["fullPath"].lower()] = (node["fullPath"], rels[0]["tagName"], [(l["name"], l.get("directAssetUrl") or l["url"], None) for l in links])
        log(f"gitlab {min(i + 50, len(paths))}/{len(paths)}")
        time.sleep(2)
    return out


def forge_release(repo):
    u = urllib.parse.urlparse(repo)
    path = u.path.strip("/")
    if u.netloc == "gitlab.com":
        _, _, body = http(f"https://gitlab.com/api/v4/projects/{urllib.parse.quote(path, safe='')}/releases/permalink/latest")
        rel = json.loads(body)
        return path, rel["tag_name"], [(l["name"], l.get("direct_asset_url") or l["url"], None) for l in rel["assets"]["links"]]
    _, _, body = http(f"https://{u.netloc}/api/v1/repos/{path}/releases/latest")
    rel = json.loads(body)
    return path, rel["tag_name"], [(a["name"], a["browser_download_url"], a.get("size")) for a in rel["assets"]]


def repo_url(source):
    """https://host/owner/name for a source URL on a supported host, else None."""
    u = urllib.parse.urlparse((source or "").strip())
    if u.netloc.lower() not in HOSTS:
        return None
    parts = [p for p in u.path.split("/") if p]
    if "-" in parts:
        parts = parts[:parts.index("-")]
    parts = parts[:2] if u.netloc.lower() != "gitlab.com" else parts
    if len(parts) < 2:
        return None
    return f"https://{u.netloc.lower()}/" + "/".join(parts).removesuffix(".git")


def text(field):
    if isinstance(field, dict):
        return field.get("en-US") or next(iter(field.values()), "")
    return field or ""


# ---- main ----------------------------------------------------------------------------------------------------------

def main():
    out_dir = sys.argv[1]
    cache_path = sys.argv[sys.argv.index("--cache") + 1] if "--cache" in sys.argv else os.path.join(out_dir, "cache.json")
    limit = int(sys.argv[sys.argv.index("--limit") + 1]) if "--limit" in sys.argv else None
    os.makedirs(os.path.join(out_dir, "icons"), exist_ok=True)
    cache = json.load(open(cache_path)) if os.path.exists(cache_path) else {}

    # Candidates: id → metadata, first source wins (IzzyOnDroid ships the developers' own APKs, so its data goes first)
    apps = {}
    for source, base in INDEXES:
        log(f"index {source}")
        _, _, body = http(f"{base}/index-v2.json", timeout=300)
        for pid, pkg in json.loads(body)["packages"].items():
            m = pkg["metadata"]
            repo = repo_url(m.get("sourceCode"))
            if not repo or pid in apps:
                continue
            icon = text(m.get("icon"))
            apps[pid] = {"id": pid, "repo": repo, "name": text(m.get("name")) or pid, "summary": text(m.get("summary")).strip(),
                         "categories": m.get("categories", []), "updated": m.get("lastUpdated", 0),
                         "icon": f"{base}{icon['name']}" if isinstance(icon, dict) and icon.get("name") else None}
    manual = [l.split() for l in open(os.path.join(ROOT, "catalogue.txt")) if l.strip() and not l.startswith("#")]
    if limit:
        apps = dict(list(apps.items())[:limit])
    log(f"{len(apps)} candidates from indexes, {len(manual)} listed by hand")

    # Latest releases
    candidates = [(a["repo"], None, a) for a in apps.values()] + [(m[0], m[1] if len(m) > 1 else None, None) for m in manual]
    gh = github_releases([urllib.parse.urlparse(r).path.strip("/") for r, _, _ in candidates if "github.com/" in r])
    others = [r for r, _, _ in candidates if "github.com/" not in r]
    gitlab = [r for r in others if "gitlab.com/" in r]
    forgejo = [r for r in others if r not in gitlab]
    with concurrent.futures.ThreadPoolExecutor(6) as pool:
        fr = dict(zip(forgejo, pool.map(lambda r: _try(forge_release, r), forgejo)))
    if "--skip-gitlab" not in sys.argv:
        gl = gitlab_releases([urllib.parse.urlparse(r).path.strip("/") for r in gitlab])
        for r in gitlab:
            fr[r] = gl.get(urllib.parse.urlparse(r).path.strip("/").lower())

    def resolve(item):
        repo, glob_pattern, meta = item
        u = urllib.parse.urlparse(repo)
        rel = gh.get(u.path.strip("/")) if u.netloc == "github.com" else fr.get(repo)
        if not rel:
            return None
        path, tag, assets = rel
        canonical = f"https://{u.netloc}/{path}"
        chosen = pick(assets, glob_pattern, canonical)
        if not chosen:
            return None
        name, url, size = chosen
        if url not in cache:
            got = _try(inspect, url, size)
            cache[url] = list(got) if got else None
        if not cache[url]:
            return None
        pkg, signer = cache[url]
        if not signer or (meta and pkg != meta["id"]):
            return None  # the release's APK isn't this app (another flavor or package), or it isn't v2/v3 signed
        entry = dict(meta) if meta else {"id": pkg, "name": None, "summary": "", "categories": [], "updated": 0, "icon": None}
        entry.update(repo=canonical, signer=signer)
        if glob_pattern:
            entry["asset"] = glob_pattern
        return entry

    with concurrent.futures.ThreadPoolExecutor(16) as pool:
        results = [r for r in pool.map(resolve, candidates) if r]
    json.dump(cache, open(cache_path, "w"))

    listed = {}
    for e in results:  # hand-listed entries come last and override
        listed[e["id"]] = {**listed.get(e["id"], {}), **{k: v for k, v in e.items() if v not in (None, "", [], 0) or k not in listed.get(e["id"], {})}}
    _icons(listed.values(), os.path.join(out_dir, "icons"))
    final = sorted(listed.values(), key=lambda a: -a.get("updated", 0))
    for e in final:
        e["icon"] = f"icons/{e['id']}.webp" if os.path.exists(os.path.join(out_dir, "icons", e["id"] + ".webp")) else None
        e["name"] = e.get("name") or e["repo"].rsplit("/", 1)[-1]  # listed by hand and in no index
    with open(os.path.join(out_dir, "index.json"), "w") as f:
        json.dump({"version": 2, "generated": int(time.time() * 1000),
                   "apps": [{k: v for k, v in e.items() if v not in (None, "", [])} for e in final]}, f, ensure_ascii=False, separators=(",", ":"))
    log(f"listed {len(final)} apps")


def _try(fn, *a):
    try:
        return fn(*a)
    except Exception as e:
        log(f"  {fn.__name__} {a[0]}: {e}")
        return None


def _icons(entries, folder):
    """Mirrors missing icons. A host that keeps failing (the index servers block bursts) is left for the next night."""
    from PIL import Image
    failures = {}
    def one(e):
        target = os.path.join(folder, e["id"] + ".webp")
        host = urllib.parse.urlparse(e.get("icon") or "").netloc
        if os.path.exists(target) or not host or failures.get(host, 0) >= 20:
            return
        for attempt in range(3):
            try:
                _, _, body = http(e["icon"], timeout=20)
                Image.open(io.BytesIO(body)).convert("RGBA").resize((96, 96), Image.LANCZOS).save(target, "WEBP", quality=82)
                failures[host] = 0
                return
            except (urllib.error.URLError, TimeoutError) as ex:
                if attempt == 2:
                    failures[host] = failures.get(host, 0) + 1
                    log(f"  icon {e['id']}: {ex}")
                time.sleep(3 * (attempt + 1))
            except Exception as ex:
                log(f"  icon {e['id']}: {ex}")
                return
    with concurrent.futures.ThreadPoolExecutor(4) as pool:
        list(pool.map(one, list(entries)))


if __name__ == "__main__":
    main()
