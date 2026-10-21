#!/usr/bin/env python3
# Copyright (c) 2026 Yassine Moukil ELECTRO-GAMMA. All rights reserved.
# Author: Yassine Moukil ELECTRO-GAMMA
# Third-party / ported code attributions: see NOTICE.md
"""
github_sync.py - push / pull / release helper for AndroProgX, made for Termux.

Standard library only. The only things it needs are `git` and `python`:

    pkg update && pkg install git python

----------------------------------------------------------------------------
1) FILL IN THE 4 VALUES BELOW (your key + your Gmail go here)
2) Run it from Termux:

    python github_sync.py init            # first time: create the GitHub repo + first push
    python github_sync.py push            # commit everything and push
    python github_sync.py push -m "msg"   # same, with your own commit message
    python github_sync.py pull            # download the latest changes
    python github_sync.py clone           # fresh copy of the repo on a new device
    python github_sync.py release         # create a GitHub Release and attach the APK
    python github_sync.py status          # show what is configured / changed

Typical first run:
    cp AndroProgX.apk <repo folder>/        (the .apk is git-ignored on purpose)
    python github_sync.py init
    python github_sync.py release --tag v1.0
----------------------------------------------------------------------------

SECURITY - read this once:
  * GITHUB_TOKEN is a password. Anyone who has it can write to your GitHub.
  * Keep the filled-in copy of this script OUTSIDE the repo folder (or don't
    push it). As a safety net, push/init abort if the token text is found in
    any file about to be committed.
  * The token is sent to GitHub through a one-off HTTP header; it is never
    written into .git/config or the remote URL.

Create the token (GitHub -> Settings -> Developer settings -> Personal access
tokens -> Fine-grained tokens): give it access to the repository (or "All
repositories" if the repo does not exist yet) with
"Contents: Read and write" + "Administration: Read and write" (only needed
for `init` to create the repo). A classic token with the `repo` scope also
works.
"""

import argparse
import base64
import json
import mimetypes
import os
import subprocess
import sys
import urllib.error
import urllib.request

# =========================== EDIT THESE VALUES ============================
GITHUB_USERNAME = "YOUR_GITHUB_USERNAME"        # e.g. "electro-gamma"
GITHUB_EMAIL    = "YOUR_NAME@gmail.com"         # your Gmail, used as the git commit e-mail
GITHUB_TOKEN    = "PASTE_YOUR_GITHUB_TOKEN"     # the key: ghp_xxx... or github_pat_xxx...
AUTHOR_NAME     = "Yassine Moukil ELECTRO-GAMMA"  # shown as the commit author

# Optional settings
REPO_NAME    = "AndroProgX"
BRANCH       = "main"
PRIVATE_REPO = False                            # True = create it as a private repo
REPO_DESC    = "AndroProgX - Android CH341A USB EEPROM / SPI flash programmer, SSD1306 OLED and I2C/SPI-over-TCP bridge"
APK_FILE     = "AndroProgX.apk"                 # path relative to the repo folder (or absolute)
APK_ASSET    = "AndroProgX-{tag}.apk"           # file name shown in the release
# ==========================================================================

# Environment variables win over the values above (handy if you do not want
# the token inside the file):  export GITHUB_TOKEN=ghp_xxx
GITHUB_USERNAME = os.environ.get("GITHUB_USERNAME", GITHUB_USERNAME)
GITHUB_EMAIL = os.environ.get("GITHUB_EMAIL", GITHUB_EMAIL)
GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN", GITHUB_TOKEN)

API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"
PLACEHOLDER_USER = "YOUR_GITHUB_USERNAME"      # text replaced inside README / notebook
REPO_URL = None  # set in main()


# ------------------------------------------------------------------ helpers
def die(msg, code=1):
    print(f"\n[x] {msg}", file=sys.stderr)
    sys.exit(code)


def info(msg):
    print(f"[+] {msg}")


def check_config(need_token=True):
    problems = []
    if GITHUB_USERNAME.startswith("YOUR_"):
        problems.append("GITHUB_USERNAME")
    if GITHUB_EMAIL.startswith("YOUR_"):
        problems.append("GITHUB_EMAIL")
    if need_token and GITHUB_TOKEN.startswith("PASTE_"):
        problems.append("GITHUB_TOKEN")
    if problems:
        die("Edit the top of github_sync.py first - still placeholders: " + ", ".join(problems))


def default_repo_dir():
    here = os.path.dirname(os.path.abspath(__file__))
    # script lives in <repo>/tools/ -> repo is the parent; otherwise the current folder
    if os.path.basename(here) == "tools" and os.path.isdir(os.path.join(os.path.dirname(here), "app")):
        return os.path.dirname(here)
    return os.getcwd()


def auth_header():
    raw = f"{GITHUB_USERNAME}:{GITHUB_TOKEN}".encode()
    return "Authorization: Basic " + base64.b64encode(raw).decode()


def git(repo, *args, auth=False, check=True, capture=False):
    cmd = ["git"]
    if auth:
        cmd += ["-c", f"http.extraheader={auth_header()}"]
    cmd += list(args)
    res = subprocess.run(cmd, cwd=repo, text=True, capture_output=capture)
    if check and res.returncode != 0:
        err = (res.stderr or "").replace(GITHUB_TOKEN, "***") if capture else ""
        die(f"git {' '.join(args)} failed (exit {res.returncode}). {err}".strip())
    return res


def api(method, url, payload=None, raw=None, content_type=None, ok_404=False):
    headers = {
        "Authorization": f"Bearer {GITHUB_TOKEN}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "androprogx-github-sync",
    }
    data = None
    if payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"
    elif raw is not None:
        data = raw
        headers["Content-Type"] = content_type or "application/octet-stream"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            body = r.read()
            return json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        if e.code == 404 and ok_404:
            return None
        detail = e.read().decode(errors="replace")
        die(f"GitHub API {method} {url} -> HTTP {e.code}\n{detail}")
    except urllib.error.URLError as e:
        die(f"Network error talking to GitHub: {e.reason}")


def personalize(repo):
    """Replace YOUR_GITHUB_USERNAME in README.md / notebook with the real username
    so the 'Open in Colab' badge points at your repository."""
    n = 0
    for rel in ("README.md", os.path.join("colab", "Build_AndroProgX.ipynb")):
        p = os.path.join(repo, rel)
        if not os.path.isfile(p):
            continue
        txt = open(p, encoding="utf-8").read()
        if PLACEHOLDER_USER in txt:
            open(p, "w", encoding="utf-8").write(txt.replace(PLACEHOLDER_USER, GITHUB_USERNAME))
            n += 1
    if n:
        info(f"Filled your GitHub username into {n} file(s) (Colab badge / clone URL)")


def configure_git(repo):
    git(repo, "config", "user.name", AUTHOR_NAME)
    git(repo, "config", "user.email", GITHUB_EMAIL)
    git(repo, "config", "--local", "credential.helper", "")
    git(repo, "branch", "-M", BRANCH, check=False)
    remotes = git(repo, "remote", capture=True).stdout.split()
    if "origin" in remotes:
        git(repo, "remote", "set-url", "origin", REPO_URL)
    else:
        git(repo, "remote", "add", "origin", REPO_URL)


def ensure_git_repo(repo):
    if not os.path.isdir(os.path.join(repo, ".git")):
        info("Initialising a new git repository")
        git(repo, "init", "-q")
    configure_git(repo)


def abort_if_token_staged(repo):
    if GITHUB_TOKEN.startswith("PASTE_") or len(GITHUB_TOKEN) < 12:
        return
    names = git(repo, "diff", "--cached", "--name-only", "-z", capture=True).stdout.split("\0")
    for name in filter(None, names):
        p = os.path.join(repo, name)
        try:
            if os.path.getsize(p) > 5_000_000:
                continue
            if GITHUB_TOKEN in open(p, encoding="utf-8", errors="ignore").read():
                git(repo, "reset", "-q", check=False)
                die(f"Your token text was found inside '{name}'. Nothing was committed.\n"
                    "Keep the filled-in github_sync.py outside the repo (or remove the token from that file) "
                    "and run again.")
        except OSError:
            continue


def commit_all(repo, message):
    git(repo, "add", "-A")
    abort_if_token_staged(repo)
    if git(repo, "diff", "--cached", "--quiet", check=False).returncode == 0:
        info("Nothing new to commit")
        return False
    git(repo, "commit", "-q", "-m", message)
    info(f"Committed: {message}")
    return True


# ----------------------------------------------------------------- commands
def cmd_init(a):
    check_config()
    repo = a.dir
    exists = api("GET", f"{API}/repos/{GITHUB_USERNAME}/{REPO_NAME}", ok_404=True)
    if exists:
        info(f"GitHub repo {GITHUB_USERNAME}/{REPO_NAME} already exists - reusing it")
    else:
        api("POST", f"{API}/user/repos", {
            "name": REPO_NAME, "description": REPO_DESC,
            "private": PRIVATE_REPO, "auto_init": False,
        })
        info(f"Created GitHub repo {GITHUB_USERNAME}/{REPO_NAME}"
             f" ({'private' if PRIVATE_REPO else 'public'})")
    ensure_git_repo(repo)
    personalize(repo)
    commit_all(repo, a.message or "Initial commit: AndroProgX")
    git(repo, "push", "-u", "origin", BRANCH, auth=True)
    info(f"Pushed. Open: https://github.com/{GITHUB_USERNAME}/{REPO_NAME}")
    print("    Next: copy AndroProgX.apk into the repo folder, then run:  python github_sync.py release --tag v1.0")


def cmd_push(a):
    check_config()
    repo = a.dir
    ensure_git_repo(repo)
    personalize(repo)
    commit_all(repo, a.message or "Update AndroProgX")
    git(repo, "push", "-u", "origin", BRANCH, auth=True)
    info("Push complete")


def cmd_pull(a):
    check_config()
    repo = a.dir
    ensure_git_repo(repo)
    dirty = git(repo, "status", "--porcelain", capture=True).stdout.strip()
    if dirty:
        info("You have local changes - committing them first so the pull cannot overwrite them")
        commit_all(repo, "Local changes before pull")
    git(repo, "pull", "--rebase", "origin", BRANCH, auth=True)
    info("Pull complete - you are up to date")


def cmd_clone(a):
    check_config()
    target = a.dir if a.dir != default_repo_dir() else os.path.join(os.getcwd(), REPO_NAME)
    if os.path.exists(target) and os.listdir(target):
        die(f"'{target}' already exists and is not empty")
    subprocess.run(["git", "-c", f"http.extraheader={auth_header()}", "clone", REPO_URL, target], check=True)
    git(target, "config", "user.name", AUTHOR_NAME)
    git(target, "config", "user.email", GITHUB_EMAIL)
    info(f"Cloned into {target}")


def cmd_release(a):
    check_config()
    repo = a.dir
    tag = a.tag
    apk = a.apk or APK_FILE
    if not os.path.isabs(apk):
        apk = os.path.join(repo, apk)
    if not os.path.isfile(apk):
        die(f"APK not found: {apk}\nCopy AndroProgX.apk there (or pass --apk /path/to/file.apk).")
    if not os.path.isdir(os.path.join(repo, ".git")):
        die("This folder is not a git repo yet - run `python github_sync.py init` first.")

    # make sure the tag will point at pushed code
    if a.push:
        ensure_git_repo(repo)
        personalize(repo)
        commit_all(repo, f"Release {tag}")
        git(repo, "push", "-u", "origin", BRANCH, auth=True)

    notes = a.notes or (
        f"## AndroProgX {tag}\n\n"
        "Android CH341A EEPROM / SPI flash programmer.\n\n"
        "**Install:** download the APK below, allow *Install unknown apps* when asked, and open it.\n\n"
        "Requires Android 8.0+ with USB-host (OTG) support and a CH341A programmer board "
        "(VID 1A86 / PID 5512).\n\n"
        "If an older build signed with a different key is installed, uninstall it first.\n\n"
        "Author: Yassine Moukil ELECTRO-GAMMA"
    )

    rel = api("GET", f"{API}/repos/{GITHUB_USERNAME}/{REPO_NAME}/releases/tags/{tag}", ok_404=True)
    if rel:
        info(f"Release {tag} already exists - updating it")
        rel = api("PATCH", rel["url"], {"name": a.title or f"AndroProgX {tag}", "body": notes,
                                         "prerelease": a.prerelease})
    else:
        rel = api("POST", f"{API}/repos/{GITHUB_USERNAME}/{REPO_NAME}/releases", {
            "tag_name": tag, "target_commitish": BRANCH,
            "name": a.title or f"AndroProgX {tag}", "body": notes,
            "draft": False, "prerelease": a.prerelease,
        })
        info(f"Created release {tag}")

    asset_name = APK_ASSET.format(tag=tag)
    for old in rel.get("assets", []):
        if old["name"] == asset_name:
            api("DELETE", old["url"])
            info(f"Replaced the existing asset {asset_name}")

    size_mb = os.path.getsize(apk) / 1e6
    info(f"Uploading {asset_name} ({size_mb:.1f} MB) ...")
    with open(apk, "rb") as f:
        data = f.read()
    up = api("POST", f"{UPLOADS}/repos/{GITHUB_USERNAME}/{REPO_NAME}/releases/{rel['id']}/assets?name={asset_name}",
             raw=data, content_type="application/vnd.android.package-archive")
    info("Done!")
    print(f"    Release page : {rel['html_url']}")
    print(f"    APK download : {up.get('browser_download_url')}")


def cmd_status(a):
    repo = a.dir
    print(f"Repo folder : {repo}")
    print(f"GitHub user : {GITHUB_USERNAME}")
    print(f"E-mail      : {GITHUB_EMAIL}")
    print(f"Token set   : {'yes' if not GITHUB_TOKEN.startswith('PASTE_') else 'NO (edit the script)'}")
    print(f"Remote      : {REPO_URL}")
    if os.path.isdir(os.path.join(repo, ".git")):
        print("\n" + git(repo, "status", "-sb", capture=True).stdout)
    else:
        print("\nNot a git repo yet - run `python github_sync.py init`.")


def main():
    global REPO_URL
    REPO_URL = f"https://github.com/{GITHUB_USERNAME}/{REPO_NAME}.git"

    ap = argparse.ArgumentParser(description="AndroProgX GitHub push/pull/release helper (Termux)")
    ap.add_argument("--dir", default=default_repo_dir(), help="repo folder (default: auto-detected)")
    sub = ap.add_subparsers(dest="cmd", required=True)

    for name, fn, h in (("init", cmd_init, "create the GitHub repo and do the first push"),
                        ("push", cmd_push, "commit all changes and push"),
                        ("pull", cmd_pull, "pull the latest changes"),
                        ("clone", cmd_clone, "clone the repo into a new folder"),
                        ("status", cmd_status, "show configuration and git status")):
        p = sub.add_parser(name, help=h)
        p.add_argument("-m", "--message", help="commit message")
        p.set_defaults(fn=fn)

    r = sub.add_parser("release", help="create a GitHub Release and upload the APK")
    r.add_argument("--tag", default="v1.0", help="release tag, e.g. v1.0 (default: v1.0)")
    r.add_argument("--title", help="release title")
    r.add_argument("--notes", help="release notes text (default: a standard install note)")
    r.add_argument("--apk", help="path to the APK (default: AndroProgX.apk in the repo folder)")
    r.add_argument("--prerelease", action="store_true")
    r.add_argument("--no-push", dest="push", action="store_false",
                   help="do not commit/push before tagging")
    r.set_defaults(fn=cmd_release, push=True)

    a = ap.parse_args()
    a.dir = os.path.abspath(a.dir)
    a.fn(a)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        die("Interrupted", 130)
