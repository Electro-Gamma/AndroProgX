#!/usr/bin/env python3
# Copyright (c) 2026 Yassine Moukil ELECTRO-GAMMA. All rights reserved.
# Author: Yassine Moukil ELECTRO-GAMMA
# Third-party / ported code attributions: see NOTICE.md
"""
github_sync.py - universal git push / pull / release / topics / pull-request helper (Termux).

Standard library only. The only things it needs are `git` and `python`:

    pkg update && pkg install git python

Works with ANY project, not just AndroProgX. Point it at a repo folder with
--dir (or run it from inside the repo):

    python github_sync.py --dir ~/AndroProgX push
    python github_sync.py --dir ~/MyBot      topics eeprom i2c spi
    cd ~/AndroProgX && python github_sync.py topics --list

Commands:
    init          create the GitHub repo (uses REPO_NAME) and do the first push
    push          ↑  upload local commits to GitHub
    pull          ↓  download new commits from GitHub into this folder
    clone         fresh copy of the repo on a new device
    release       create a GitHub Release and attach a file (APK by default)
    status        show what is configured / changed
    topics        read / set / add / delete / clear the About tags (GitHub topics)
                  and set the About description (max 349 characters)

    pr-create     branch + commit + push + open a pull request
    pr-status     list pull requests, or show one in detail (reviews, checks, merge gate)
    pr-review     inspect a pull request's changes; optionally submit a review
    pr-merge      merge a pull request (explicit confirmation required)
    achievements  merged-PR / co-author / review statistics, labelled
                  [verified] / [estimate] / [unknown]

Pull request examples:
    python github_sync.py --dir ~/MyBot pr-create -t "Fix I2C timeout handling" --body "Retries 3x"
    python github_sync.py --dir ~/MyBot pr-create -t "Add SPI flash probe" --reviewer friend-login
    python github_sync.py --dir ~/MyBot pr-create -t "Pair-fix CRC check" \\
            --co-author "Real Person <12345+their-login@users.noreply.github.com>"
    python github_sync.py --dir ~/MyBot pr-status                  # open PRs
    python github_sync.py --dir ~/MyBot pr-status 7                # one PR in detail
    python github_sync.py --dir ~/MyBot pr-review 7 --files        # see what changed
    python github_sync.py --dir ~/MyBot pr-review 7 --submit comment --body "Looks fine"
    python github_sync.py --dir ~/MyBot pr-merge 7                 # asks you to type 'merge'
    python github_sync.py --dir ~/MyBot pr-merge 7 --yes --delete-branch   # automation
    python github_sync.py achievements --achievement pull-shark --tier bronze

Topics / description examples:
    python github_sync.py --dir ~/AndroProgX topics eeprom i2c spi          # replace tags
    python github_sync.py --dir ~/AndroProgX topics --add esp32             # append tags
    python github_sync.py --dir ~/AndroProgX topics --delete eeprom spi     # remove tags
    python github_sync.py --dir ~/AndroProgX topics --clear                 # remove all tags
    python github_sync.py --dir ~/AndroProgX topics --list                  # show tags
    python github_sync.py --dir ~/AndroProgX topics --desc "Android CH341A EEPROM programmer"
    python github_sync.py --dir ~/AndroProgX topics --show-desc             # show description
    python github_sync.py --dir ~/AndroProgX topics --desc ""               # clear description

Direction quick reminder:
    push  ↑  local  ──►  GitHub   (send your changes up)
    pull  ↓  GitHub ──►  local    (bring their changes down)

SECURITY - read this once:
  * GITHUB_TOKEN is a password. Anyone who has it can write to your GitHub.
  * The token is NOT stored in this script. Provide it with either:
        export GITHUB_TOKEN=github_pat_xxx            (current shell / ~/.bashrc)
    or a file only you can read (override the path with GITHUB_TOKEN_FILE):
        mkdir -p ~/.config/github_sync
        printf '%s' 'github_pat_xxx' > ~/.config/github_sync/token
        chmod 600 ~/.config/github_sync/token
  * As a safety net, commits abort if the token text is found in any staged file.
  * The token is sent to GitHub through a one-off HTTP header; it is never
    written into .git/config or the remote URL, and it is never printed.

Token permissions (use a FINE-GRAINED token limited to the repos you need):
  * Metadata: Read                  (always granted automatically)
  * Contents: Read and write        (push / pull / release / pr-create)
  * Pull requests: Read and write   (pr-create / pr-status / pr-review / pr-merge)
  * Administration: Read            (optional - lets pr-status show branch-protection detail)
  * Commit statuses + Checks: Read  (optional - lets pr-status show CI results)
  * Administration: Read and write  (ONLY for `init` and `topics --desc`)
  `achievements` needs no extra permission for public data.
"""

import argparse
import base64
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

# =========================== EDIT THESE VALUES ============================
GITHUB_USERNAME = "Electro-Gamma"        # e.g. "electro-gamma"
GITHUB_EMAIL    = "yassinmoukil7@gmail.com"         # your Gmail, used as the git commit e-mail
AUTHOR_NAME     = "Yassine Moukil ELECTRO-GAMMA"  # shown as the commit author
# NOTE: the token is deliberately NOT here any more - see SECURITY above.


# Optional settings (only used by `init` / `clone` / `release` / PR commands)
REPO_NAME    = ""              # empty = auto-detect from the repo folder / git remote
BRANCH       = "main"          # also the default base branch for pull requests
PRIVATE_REPO = False
REPO_DESC    = ""              # empty = "Created by github_sync.py" (used only at `init`)
APK_FILE     = "app.apk"       # default file for `release` (override with --apk)
APK_ASSET    = "{tag}-{name}"  # {tag} and {name} are substituted
DESC_MAX     = 349             # hard limit for the About description

# Pull-request guard rails (anti-spam / anti-"meaningless PR")
MIN_TITLE_LEN = 10             # PR titles shorter than this are refused
PR_OPEN_LIMIT = 5              # refuse to open another PR while you already have this many open
# ==========================================================================

# Environment variables win over the values above.
GITHUB_USERNAME = os.environ.get("GITHUB_USERNAME", GITHUB_USERNAME)
GITHUB_EMAIL = os.environ.get("GITHUB_EMAIL", GITHUB_EMAIL)


def load_token():
    """Token from $GITHUB_TOKEN, else from a private file. Never from the script itself."""
    tok = os.environ.get("GITHUB_TOKEN", "").strip()
    if tok:
        return tok, "environment variable GITHUB_TOKEN"
    path = os.environ.get("GITHUB_TOKEN_FILE") or os.path.join(
        os.path.expanduser("~"), ".config", "github_sync", "token")
    try:
        st = os.stat(path)
    except OSError:
        return "", "(not found)"
    if os.name == "posix" and st.st_mode & 0o077:
        print(f"[!] {path} can be read by other users - run: chmod 600 {path}",
              file=sys.stderr)
    try:
        with open(path, encoding="utf-8") as f:
            return f.read().strip(), f"file {path}"
    except OSError:
        return "", "(unreadable)"


GITHUB_TOKEN, TOKEN_SOURCE = load_token()

API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"

# Community-documented achievement tiers. GitHub does NOT publish official
# criteria, so these may be out of date - see `achievements` output.
PULL_SHARK_TIERS = [("default", 2), ("bronze", 16), ("silver", 128), ("gold", 1024)]
PAIR_TIERS = [("default", 1), ("bronze", 10), ("silver", 24), ("gold", 48)]

V, E, U = "[verified]", "[estimate]", "[unknown] "


# ------------------------------------------------------------------ helpers
def die(msg, code=1):
    print(f"\n[x] {msg}", file=sys.stderr)
    sys.exit(code)


def info(msg):
    print(f"[+] {msg}")


def redact(text):
    if GITHUB_TOKEN and text:
        return text.replace(GITHUB_TOKEN, "***")
    return text


def check_config(need_token=True):
    problems = []
    if not GITHUB_USERNAME or GITHUB_USERNAME.startswith("YOUR_"):
        problems.append("GITHUB_USERNAME")
    if not GITHUB_EMAIL or GITHUB_EMAIL.startswith("YOUR_"):
        problems.append("GITHUB_EMAIL")
    if problems:
        die("Edit the top of github_sync.py first - still missing: " + ", ".join(problems))
    if need_token and not GITHUB_TOKEN:
        die("No GitHub token found. Provide it WITHOUT writing it into this script:\n"
            "    export GITHUB_TOKEN=github_pat_xxx\n"
            "  or put it in ~/.config/github_sync/token (chmod 600), or point\n"
            "  GITHUB_TOKEN_FILE at a file that contains it.")


def git(repo, *args, auth=False, check=True, capture=False):
    cmd = ["git"]
    if auth:
        raw = f"{GITHUB_USERNAME}:{GITHUB_TOKEN}".encode()
        cmd += ["-c", "http.extraheader=Authorization: Basic " + base64.b64encode(raw).decode()]
    cmd += list(args)
    res = subprocess.run(cmd, cwd=repo, text=True, capture_output=capture)
    if check and res.returncode != 0:
        err = redact(res.stderr or "") if capture else ""
        die(f"git {' '.join(args)} failed (exit {res.returncode}). {err}".strip())
    return res


def api(method, url, payload=None, raw=None, content_type=None, ok_404=False, soft=False):
    """Call the GitHub API. soft=True returns None on ANY failure instead of exiting."""
    headers = {
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "universal-github-sync",
    }
    if GITHUB_TOKEN:
        headers["Authorization"] = f"Bearer {GITHUB_TOKEN}"
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
        if soft or (e.code == 404 and ok_404):
            return None
        detail = redact(e.read().decode(errors="replace"))
        die(f"GitHub API {method} {url} -> HTTP {e.code}\n{detail}")
    except urllib.error.URLError as e:
        if soft:
            return None
        die(f"Network error talking to GitHub: {e.reason}")


def api_pages(url, max_pages=5, soft=False):
    """GET a list endpoint across pages (100 per page)."""
    items = []
    sep = "&" if "?" in url else "?"
    for page in range(1, max_pages + 1):
        chunk = api("GET", f"{url}{sep}per_page=100&page={page}", soft=soft)
        if not chunk or not isinstance(chunk, list):
            break
        items.extend(chunk)
        if len(chunk) < 100:
            break
    return items


_ME = None


def whoami():
    """The login the token actually belongs to (verified by GitHub)."""
    global _ME
    if _ME is None:
        u = api("GET", f"{API}/user")
        _ME = (u or {}).get("login") or GITHUB_USERNAME
    return _ME


def confirm(prompt, word="yes"):
    """Explicit typed confirmation. Returns False when there is no terminal to ask."""
    if not sys.stdin.isatty():
        return False
    try:
        ans = input(f"{prompt}\n    Type '{word}' to continue: ").strip().lower()
    except EOFError:
        return False
    return ans == word


# ---------- universal repo / owner resolution ----------------------------
def resolve_repo_dir(arg_dir):
    if arg_dir:
        return os.path.abspath(os.path.expanduser(arg_dir))
    return os.getcwd()


def repo_dir_or_git(arg_dir):
    p = resolve_repo_dir(arg_dir)
    if os.path.isdir(os.path.join(p, ".git")):
        return p
    parent = os.path.dirname(p)
    if os.path.isdir(os.path.join(parent, ".git")):
        return parent
    return p


def git_remote_owner_repo(repo):
    res = git(repo, "remote", "get-url", "origin", check=False, capture=True)
    url = (res.stdout or "").strip()
    if not url:
        return None, None
    m = re.search(r"github\.com[:/](?P<owner>[^/]+)/(?P<name>[^/]+?)(?:\.git)?$", url)
    if not m:
        return None, None
    return m.group("owner"), m.group("name")


def resolve_owner_repo(arg_dir, required=True):
    repo = repo_dir_or_git(arg_dir)
    owner, name = git_remote_owner_repo(repo)
    if owner and name:
        return owner, name, repo
    folder = os.path.basename(repo.rstrip(os.sep)) or "repo"
    name = REPO_NAME or folder
    owner = GITHUB_USERNAME
    if required and (not owner or not name):
        die("Cannot determine GitHub owner/repo.\n"
            "Either set GITHUB_USERNAME + REPO_NAME at the top of the script, "
            "or run this inside a folder whose `origin` remote is a GitHub URL.")
    return owner, name, repo


def repo_url(owner, name):
    return f"https://github.com/{owner}/{name}.git"


def current_branch(repo):
    res = git(repo, "symbolic-ref", "--short", "-q", "HEAD", check=False, capture=True)
    return (res.stdout or "").strip()


def local_branch_exists(repo, branch):
    return git(repo, "show-ref", "--verify", "--quiet", f"refs/heads/{branch}",
               check=False).returncode == 0


def ensure_git_repo(repo):
    if not os.path.isdir(os.path.join(repo, ".git")):
        info("Initialising a new git repository")
        git(repo, "init", "-q")
    configure_git(repo)


def configure_git(repo, rename_branch=True):
    git(repo, "config", "user.name", AUTHOR_NAME or GITHUB_USERNAME)
    git(repo, "config", "user.email", GITHUB_EMAIL)
    git(repo, "config", "--local", "credential.helper", "")
    if rename_branch:
        cur = current_branch(repo)
        if cur and cur != BRANCH and local_branch_exists(repo, BRANCH):
            # `git branch -M` would overwrite the existing local branch - never do that.
            info(f"Staying on '{cur}' (a local '{BRANCH}' already exists) - not renaming branches")
        else:
            git(repo, "branch", "-M", BRANCH, check=False)
    owner, name, _ = resolve_owner_repo(repo, required=False)
    if not (owner and name):
        return
    url = repo_url(owner, name)
    remotes = git(repo, "remote", capture=True).stdout.split()
    if "origin" in remotes:
        git(repo, "remote", "set-url", "origin", url)
    else:
        git(repo, "remote", "add", "origin", url)


def abort_if_token_staged(repo):
    if len(GITHUB_TOKEN) < 12:
        return
    names = git(repo, "diff", "--cached", "--name-only", "-z", capture=True).stdout.split("\0")
    for name in filter(None, names):
        p = os.path.join(repo, name)
        try:
            if os.path.getsize(p) > 5_000_000:
                continue
            with open(p, encoding="utf-8", errors="ignore") as fh:
                if GITHUB_TOKEN in fh.read():
                    git(repo, "reset", "-q", check=False)
                    die(f"Your token text was found inside '{name}'. Nothing was committed.\n"
                        "Remove it from that file (tokens belong in the environment) and run again.")
        except OSError:
            continue


def commit_all(repo, message, trailers=None, paths=None):
    """Stage (everything, or only `paths`) and commit. `trailers` are git trailer lines."""
    if paths:
        git(repo, "add", "--", *paths)
    else:
        git(repo, "add", "-A")
    abort_if_token_staged(repo)
    if git(repo, "diff", "--cached", "--quiet", check=False).returncode == 0:
        info("Nothing new to commit")
        return False
    cmd = ["commit", "-q", "-m", message]
    if trailers:
        cmd += ["-m", "\n".join(trailers)]   # own paragraph => recognised as trailers
    git(repo, *cmd)
    info(f"Committed: {message}")
    return True


def normalize_topics(names):
    out = []
    for n in names:
        n = n.strip().lower().replace(" ", "-")
        if n and n not in out:
            out.append(n[:50])
    return out[:20]


def get_topics(owner, name):
    endpoint = f"{API}/repos/{owner}/{name}/topics"
    result = api("GET", endpoint)
    return (result or {}).get("names", [])


def set_topics(owner, name, names):
    endpoint = f"{API}/repos/{owner}/{name}/topics"
    result = api("PUT", endpoint, {"names": names})
    final = result.get("names", names) if result else names
    info(f"Topics on {owner}/{name}: {', '.join(final) if final else '(none)'}")
    return final


def get_repo(owner, name):
    return api("GET", f"{API}/repos/{owner}/{name}")


def set_repo_description(owner, name, description):
    """Set the About description. Empty string clears it."""
    if description is None:
        description = ""
    if len(description) > DESC_MAX:
        die(f"Description is {len(description)} characters - the limit is {DESC_MAX}.\n"
            f"Trim {len(description) - DESC_MAX} character(s) and try again.")
    result = api("PATCH", f"{API}/repos/{owner}/{name}", {"description": description})
    new = (result or {}).get("description") or ""
    if new:
        info(f"Description set on {owner}/{name} ({len(new)}/{DESC_MAX} chars):\n"
             f"    {new}")
    else:
        info(f"Description cleared on {owner}/{name}")
    return new


# ------------------------------------------------- pull-request helpers
COAUTHOR_RE = re.compile(r"^\s*(?P<name>[^<>]+?)\s*<(?P<email>[^<>@\s]+@[^<>@\s]+\.[^<>@\s]+)>\s*$")
NOREPLY_RE = re.compile(r"^(?:\d+\+)?(?P<login>[A-Za-z0-9-]+)@users\.noreply\.github\.com$", re.I)
TRAILER_RE = re.compile(r"^Co-authored-by:\s*(?P<name>.+?)\s*<(?P<email>[^>]+)>\s*$",
                        re.I | re.M)
PLACEHOLDER_DOMAINS = ("example.com", "example.org", "example.net", "localhost", "invalid", "test")


def slugify(text, maxlen=40):
    s = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")
    return s[:maxlen].strip("-") or "change"


def parse_coauthor(spec):
    """Parse "Real Name <email>". Refuses obvious fakes; never invents anything."""
    m = COAUTHOR_RE.match(spec or "")
    if not m:
        die(f"--co-author must look like:  \"Real Name <their-email@domain>\"   (got: {spec!r})")
    name, email = m.group("name").strip(), m.group("email").strip()
    low = email.lower()
    if low == (GITHUB_EMAIL or "").lower():
        die("A co-author must be a DIFFERENT person - that is your own e-mail.")
    if low.rsplit("@", 1)[-1] in PLACEHOLDER_DOMAINS:
        die(f"'{email}' looks like a placeholder address. Use the real collaborator's "
            "GitHub-associated e-mail.")
    nr = NOREPLY_RE.match(email)
    if nr:
        login = nr.group("login")
        if login.lower() == (GITHUB_USERNAME or "").lower():
            die("A co-author must be a DIFFERENT person - that is your own GitHub login.")
        if api("GET", f"{API}/users/{urllib.parse.quote(login)}", soft=True):
            info(f"{V} GitHub user '{login}' exists")
        else:
            die(f"GitHub user '{login}' (from the noreply address) could not be found.")
    else:
        print(f"    {U} cannot verify that {email} is linked to a GitHub account; "
              "GitHub only credits the co-author if it is.")
    return name, email


def confirm_coauthors(coauthors, pre_confirmed):
    print("Co-author check - this must reflect REAL collaboration:")
    for n, e in coauthors:
        print(f"    {n} <{e}>")
    print("    Only list someone who genuinely contributed to THIS change.")
    if pre_confirmed:
        return
    if not confirm("Did each person above genuinely contribute to these changes?", "yes"):
        die("Co-author not confirmed - nothing was committed.\n"
            "(In scripts, pass --confirm-coauthor to state this explicitly.)")


def substantive_diff(repo, base_ref):
    """True if base...HEAD changes more than whitespace / blank lines."""
    out = git(repo, "diff", "-w", "--ignore-blank-lines", "--numstat",
              f"{base_ref}...HEAD", capture=True).stdout
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        if parts[0] == "-" or parts[1] == "-":      # binary file
            return True
        try:
            if int(parts[0]) + int(parts[1]) > 0:
                return True
        except ValueError:
            return True
    return False


def get_pr_settled(owner, name, number, tries=6):
    """GitHub computes `mergeable` lazily - poll briefly until it is known."""
    pr = None
    for _ in range(tries):
        pr = api("GET", f"{API}/repos/{owner}/{name}/pulls/{number}")
        if pr.get("state") != "open" or pr.get("mergeable") is not None:
            return pr
        time.sleep(2)
    return pr


def review_summary(owner, name, number):
    """(number of submitted reviews, {reviewer: latest decisive state})."""
    reviews = api_pages(f"{API}/repos/{owner}/{name}/pulls/{number}/reviews", soft=True)
    total, latest = 0, {}
    for r in reviews:
        st = r.get("state")
        user = (r.get("user") or {}).get("login", "?")
        if st == "PENDING":
            continue
        total += 1
        if st in ("APPROVED", "CHANGES_REQUESTED", "DISMISSED"):
            latest[user] = st
        else:
            latest.setdefault(user, "COMMENTED")
    return total, latest


def protection_info(owner, name, branch):
    """Branch protection / ruleset facts that this token is allowed to see."""
    b = urllib.parse.quote(branch, safe="")
    out = {"protected": None, "required_reviews": None, "lines": []}
    br = api("GET", f"{API}/repos/{owner}/{name}/branches/{b}", soft=True)
    if br is not None:
        out["protected"] = bool(br.get("protected"))
    prot = api("GET", f"{API}/repos/{owner}/{name}/branches/{b}/protection", soft=True)
    if prot:
        rv = prot.get("required_pull_request_reviews")
        if rv:
            n = rv.get("required_approving_review_count", 1)
            out["required_reviews"] = n
            out["lines"].append(f"requires {n} approving review(s)")
        else:
            out["required_reviews"] = 0
        ctx = (prot.get("required_status_checks") or {}).get("contexts") or []
        if ctx:
            out["lines"].append("required status checks: " + ", ".join(ctx))
        if (prot.get("enforce_admins") or {}).get("enabled"):
            out["lines"].append("rules apply to administrators too")
        else:
            out["lines"].append("administrators may bypass (this script still refuses to)")
    rules = api("GET", f"{API}/repos/{owner}/{name}/rules/branches/{b}", soft=True)
    for rule in rules or []:
        t = rule.get("type")
        if t == "pull_request":
            n = (rule.get("parameters") or {}).get("required_approving_review_count", 0)
            out["lines"].append(f"ruleset: pull request required, {n} approval(s)")
            if out["required_reviews"] is None or n > out["required_reviews"]:
                out["required_reviews"] = n
        elif t == "required_status_checks":
            out["lines"].append("ruleset: required status checks")
    if out["protected"] and not out["lines"]:
        out["lines"].append("branch is protected; details not visible with this token "
                            "(grant Administration: Read to see them)")
    return out


def ci_summary(owner, name, sha):
    parts = []
    st = api("GET", f"{API}/repos/{owner}/{name}/commits/{sha}/status", soft=True)
    if st is not None and st.get("total_count"):
        parts.append(f"commit statuses: {st.get('state')} ({st['total_count']})")
    cr = api("GET", f"{API}/repos/{owner}/{name}/commits/{sha}/check-runs", soft=True)
    if cr is not None and cr.get("total_count"):
        runs = cr.get("check_runs", [])
        bad = [r["name"] for r in runs if r.get("conclusion") in ("failure", "timed_out", "cancelled")]
        pend = [r["name"] for r in runs if r.get("status") != "completed"]
        parts.append(f"check runs: {len(runs)} total, {len(bad)} failing, {len(pend)} running")
    if not parts:
        return "none reported (or this token cannot read them)"
    return "; ".join(parts)


def pr_overview(owner, name, number):
    """Collect everything needed to display a PR and decide whether merging is permitted."""
    pr = get_pr_settled(owner, name, number)
    total, latest = review_summary(owner, name, number)
    prot = protection_info(owner, name, pr["base"]["ref"])
    ci = ci_summary(owner, name, pr["head"]["sha"])

    reasons, warnings = [], []
    ms = pr.get("mergeable_state")
    if pr.get("merged"):
        reasons.append("already merged")
    elif pr.get("state") != "open":
        reasons.append("pull request is closed")
    if pr.get("draft"):
        reasons.append("pull request is a draft")
    if ms == "blocked":
        reasons.append("GitHub reports it BLOCKED (required reviews or checks not satisfied)")
    elif ms == "dirty":
        reasons.append("merge conflicts with the base branch")
    elif ms == "behind":
        reasons.append("head branch is behind the base branch and the rules require it up to date")
    elif ms in (None, "unknown"):
        reasons.append("GitHub has not finished computing mergeability - retry in a minute")
    elif ms == "unstable":
        warnings.append("some non-required checks are failing or pending")
    if "CHANGES_REQUESTED" in latest.values():
        who = ", ".join(u for u, s in latest.items() if s == "CHANGES_REQUESTED")
        reasons.append(f"changes requested by: {who}")
    approvals = sum(1 for s in latest.values() if s == "APPROVED")
    req = prot["required_reviews"]
    if req and approvals < req:
        reasons.append(f"{approvals}/{req} required approving reviews")
    return {"pr": pr, "reviews_total": total, "reviews": latest, "prot": prot,
            "ci": ci, "ok": not reasons, "reasons": reasons, "warnings": warnings}


def print_overview(ov):
    pr = ov["pr"]
    state = "MERGED" if pr.get("merged") else pr["state"].upper()
    print(f"PR #{pr['number']}  [{state}{' / draft' if pr.get('draft') else ''}]  {pr['title']}")
    print(f"    URL       : {pr['html_url']}")
    print(f"    Author    : {(pr.get('user') or {}).get('login')}")
    print(f"    Branches  : {pr['head']['ref']}  ->  {pr['base']['ref']}")
    print(f"    Changes   : {pr.get('commits', '?')} commit(s), {pr.get('changed_files', '?')} file(s), "
          f"+{pr.get('additions', '?')} / -{pr.get('deletions', '?')}")
    if pr.get("merged"):
        mb = (pr.get("merged_by") or {}).get("login", "?")
        print(f"    Merged    : {pr.get('merged_at')} by {mb}")
    rr = [u["login"] for u in pr.get("requested_reviewers", [])]
    print(f"    Reviewers : requested: {', '.join(rr) if rr else '(none)'}")
    if ov["reviews"]:
        dec = ", ".join(f"{u}={s.lower()}" for u, s in ov["reviews"].items())
        print(f"    Reviews   : {ov['reviews_total']} submitted ({dec})")
    else:
        print("    Reviews   : none submitted")
    print(f"    CI        : {ov['ci']}")
    prot = ov["prot"]
    if prot["protected"] is None:
        print("    Protection: unknown (token cannot read it)")
    elif not prot["protected"] and not prot["lines"]:
        print("    Protection: base branch is not protected")
    else:
        print("    Protection: " + ("; ".join(prot["lines"]) if prot["lines"] else "protected"))
    print(f"    mergeable_state (from GitHub): {pr.get('mergeable_state')}")
    if pr["state"] == "open" and not pr.get("merged"):
        print("    Merge gate: " + ("PERMITTED" if ov["ok"] else "NOT PERMITTED"))
        for r in ov["reasons"]:
            print(f"       - {r}")
        for w in ov["warnings"]:
            print(f"       ! {w}")


# ----------------------------------------------------------------- commands
def cmd_init(a):
    check_config()
    repo = repo_dir_or_git(a.dir)
    owner = GITHUB_USERNAME
    name = REPO_NAME or os.path.basename(repo.rstrip(os.sep)) or "repo"
    if not owner or not name:
        die("Set GITHUB_USERNAME and REPO_NAME at the top of the script (or name the --dir folder after the repo).")
    exists = api("GET", f"{API}/repos/{owner}/{name}", ok_404=True)
    if exists:
        info(f"GitHub repo {owner}/{name} already exists - reusing it")
    else:
        desc = REPO_DESC or "Created by github_sync.py"
        api("POST", f"{API}/user/repos", {
            "name": name, "description": desc[:DESC_MAX],
            "private": PRIVATE_REPO, "auto_init": False,
        })
        info(f"Created GitHub repo {owner}/{name} ({'private' if PRIVATE_REPO else 'public'})")
    ensure_git_repo(repo)
    commit_all(repo, a.message or "Initial commit")
    git(repo, "push", "-u", "origin", BRANCH, auth=True)
    info(f"Pushed. Open: https://github.com/{owner}/{name}")
    print(f"    Next: tag the About section, e.g.:")
    print(f"      python github_sync.py --dir {repo} topics eeprom i2c spi")
    print(f"      python github_sync.py --dir {repo} topics --desc \"Short project description\"")


def cmd_push(a):
    check_config()
    repo = repo_dir_or_git(a.dir)
    ensure_git_repo(repo)
    commit_all(repo, a.message or "Update")
    git(repo, "push", "-u", "origin", BRANCH, auth=True)
    info("Push complete  ↑  local → GitHub")


def cmd_pull(a):
    check_config()
    repo = repo_dir_or_git(a.dir)
    ensure_git_repo(repo)
    dirty = git(repo, "status", "--porcelain", capture=True).stdout.strip()
    if dirty:
        info("Committing local changes first so the pull cannot overwrite them")
        commit_all(repo, "Local changes before pull")
    git(repo, "pull", "--rebase", "origin", BRANCH, auth=True)
    info("Pull complete  ↓  GitHub → local - you are up to date")


def cmd_clone(a):
    check_config()
    owner = GITHUB_USERNAME
    name = REPO_NAME or (a.dir and os.path.basename(os.path.abspath(a.dir))) or ""
    if not owner or not name:
        die("Set GITHUB_USERNAME and REPO_NAME (or pass --dir <target-folder-name>) before clone.")
    target = os.path.abspath(a.dir) if a.dir else os.path.join(os.getcwd(), name)
    if os.path.exists(target) and os.listdir(target):
        die(f"'{target}' already exists and is not empty")
    url = repo_url(owner, name)
    raw = f"{GITHUB_USERNAME}:{GITHUB_TOKEN}".encode()
    subprocess.run(["git", "-c", "http.extraheader=Authorization: Basic " +
                    base64.b64encode(raw).decode(), "clone", url, target], check=True)
    git(target, "config", "user.name", AUTHOR_NAME or GITHUB_USERNAME)
    git(target, "config", "user.email", GITHUB_EMAIL)
    info(f"Cloned into {target}")


def cmd_release(a):
    check_config()
    repo = repo_dir_or_git(a.dir)
    owner, name, _ = resolve_owner_repo(repo)
    tag = a.tag
    apk = a.apk or APK_FILE
    if not os.path.isabs(apk):
        apk = os.path.join(repo, apk)
    if not os.path.isfile(apk):
        die(f"File not found: {apk}\nPass --apk /path/to/file to point at it.")
    if not os.path.isdir(os.path.join(repo, ".git")):
        die("This folder is not a git repo yet - run `init` first.")

    if a.push:
        ensure_git_repo(repo)
        commit_all(repo, f"Release {tag}")
        git(repo, "push", "-u", "origin", BRANCH, auth=True)

    notes = a.notes or f"## {name} {tag}\n\nRelease built from {BRANCH}."

    rel = api("GET", f"{API}/repos/{owner}/{name}/releases/tags/{tag}", ok_404=True)
    if rel:
        info(f"Release {tag} already exists - updating it")
        rel = api("PATCH", rel["url"], {"name": a.title or f"{name} {tag}",
                                         "body": notes, "prerelease": a.prerelease})
    else:
        rel = api("POST", f"{API}/repos/{owner}/{name}/releases", {
            "tag_name": tag, "target_commitish": BRANCH,
            "name": a.title or f"{name} {tag}", "body": notes,
            "draft": False, "prerelease": a.prerelease,
        })
        info(f"Created release {tag}")

    asset_name = APK_ASSET.format(tag=tag, name=os.path.basename(apk))
    for old in rel.get("assets", []):
        if old["name"] == asset_name:
            api("DELETE", old["url"])
            info(f"Replaced the existing asset {asset_name}")

    size_mb = os.path.getsize(apk) / 1e6
    info(f"Uploading {asset_name} ({size_mb:.1f} MB) ...")
    with open(apk, "rb") as f:
        data = f.read()
    up = api("POST", f"{UPLOADS}/repos/{owner}/{name}/releases/{rel['id']}/assets?name={asset_name}",
             raw=data, content_type="application/octet-stream")
    info("Done!")
    print(f"    Release page : {rel['html_url']}")
    print(f"    Download     : {up.get('browser_download_url')}")


def cmd_status(a):
    repo = repo_dir_or_git(a.dir)
    owner, name, _ = resolve_owner_repo(repo, required=False)
    print(f"Repo folder : {repo}")
    print(f"GitHub user : {GITHUB_USERNAME or '(unset)'}")
    print(f"Owner/repo  : {owner}/{name}" if owner and name else "Owner/repo  : (unknown)")
    print(f"E-mail      : {GITHUB_EMAIL or '(unset)'}")
    if GITHUB_TOKEN:
        print(f"Token set   : yes (from {TOKEN_SOURCE})")
    else:
        print("Token set   : NO - export GITHUB_TOKEN or create ~/.config/github_sync/token")
    if owner and name:
        print(f"Remote      : {repo_url(owner, name)}")
    if os.path.isdir(os.path.join(repo, ".git")):
        print("\n" + git(repo, "status", "-sb", capture=True).stdout)
    else:
        print("\nNot a git repo yet - run `init`.")


def cmd_topics(a):
    """Read / set / add / delete / clear the About topics, and set / show the description."""
    check_config()
    owner, name, _ = resolve_repo_dir(a.dir) and resolve_owner_repo(repo_dir_or_git(a.dir)) or (None, None, None)
    if not owner:
        owner, name, _ = resolve_owner_repo(repo_dir_or_git(a.dir))

    # --show-desc : print description only
    if a.show_desc:
        repo_info = get_repo(owner, name)
        desc = (repo_info or {}).get("description") or ""
        if desc:
            print(f"Description on {owner}/{name} ({len(desc)}/{DESC_MAX} chars):\n    {desc}")
        else:
            print(f"Description on {owner}/{name}: (none)")
        return

    # --desc TEXT : set or clear description
    if a.desc is not None:
        set_repo_description(owner, name, a.desc)
        return

    current = get_topics(owner, name)

    if a.list:
        repo_info = get_repo(owner, name)
        desc = (repo_info or {}).get("description") or ""
        print(f"Topics on {owner}/{name}: {', '.join(current) if current else '(none)'}")
        print(f"Description on {owner}/{name}: {desc if desc else '(none)'}")
        return

    if a.clear:
        if not current:
            info(f"Topics on {owner}/{name}: (none) - nothing to clear")
            return
        if a.names:
            die("--clear takes no tag names (use --delete to remove specific ones)")
        set_topics(owner, name, [])
        print(f"    Cleared all topics: https://github.com/{owner}/{name}")
        return

    if a.delete:
        if not a.names:
            die("--delete needs at least one tag name.\n"
                "Usage:  python github_sync.py --dir <repo> topics --delete eeprom spi")
        to_remove = set(normalize_topics(a.names))
        missing = to_remove - set(current)
        if missing:
            info(f"Not present, ignoring: {', '.join(sorted(missing))}")
        remaining = [t for t in current if t not in to_remove]
        if remaining == current:
            info("Nothing to delete - none of those tags were present")
            return
        set_topics(owner, name, remaining)
        print(f"    Removed {len(current) - len(remaining)} topic(s): "
              f"https://github.com/{owner}/{name}")
        return

    if a.add:
        if not a.names:
            die("--add needs at least one tag name.")
        merged = normalize_topics(current + normalize_topics(a.names))
        if merged == current:
            info("Nothing to add - those tags are already present")
            return
        set_topics(owner, name, merged)
        print(f"    See the tags in the About panel: https://github.com/{owner}/{name}")
        return

    if not a.names:
        die("No topics given.\n"
            "Usage:  python github_sync.py --dir <repo> topics eeprom i2c spi\n"
            "        python github_sync.py --dir <repo> topics --add esp32\n"
            "        python github_sync.py --dir <repo> topics --delete eeprom spi\n"
            "        python github_sync.py --dir <repo> topics --clear\n"
            "        python github_sync.py --dir <repo> topics --list\n"
            "        python github_sync.py --dir <repo> topics --desc \"short description\"\n"
            "        python github_sync.py --dir <repo> topics --show-desc\n"
            "        python github_sync.py --dir <repo> topics --desc \"\"    # clear")
    set_topics(owner, name, normalize_topics(a.names))
    print(f"    See the tags in the About panel: https://github.com/{owner}/{name}")


# ------------------------------------------------------------ PR commands
def cmd_pr_create(a):
    """Branch -> commit -> push -> open a pull request. Never force-pushes or overwrites."""
    check_config()
    repo = repo_dir_or_git(a.dir)
    if not os.path.isdir(os.path.join(repo, ".git")):
        die("This folder is not a git repo yet - run `init` first.")
    owner, name, _ = resolve_owner_repo(repo)
    base = a.base or BRANCH
    title = a.title.strip()
    if len(title) < MIN_TITLE_LEN:
        die(f"PR title is too short ({len(title)} chars, minimum {MIN_TITLE_LEN}). "
            "Describe the actual change - this tool does not open placeholder PRs.")

    coauthors = [parse_coauthor(c) for c in a.co_author]
    trailers = [f"Co-authored-by: {n} <{e}>" for n, e in coauthors]
    if coauthors:
        confirm_coauthors(coauthors, a.confirm_coauthor)

    ri = get_repo(owner, name) or {}
    perms = ri.get("permissions") or {}
    if perms and not perms.get("push"):
        die(f"You do not have push access to {owner}/{name}.\n"
            "To contribute to someone else's repository you must fork it first and open the PR "
            "from your fork - this script only opens PRs from branches inside the target repo.")
    me = whoami()
    if owner.lower() != me.lower():
        info(f"Note: {owner}/{name} is not owned by {me} (you have push access as a collaborator).")

    mine_open = [p for p in api_pages(f"{API}/repos/{owner}/{name}/pulls?state=open", max_pages=2)
                 if ((p.get("user") or {}).get("login") or "").lower() == me.lower()]
    if len(mine_open) >= PR_OPEN_LIMIT:
        die(f"You already have {len(mine_open)} open PRs on {owner}/{name} (limit {PR_OPEN_LIMIT}). "
            "Review/merge or close those first.")

    configure_git(repo, rename_branch=False)       # never rename the branch we are on
    git(repo, "fetch", "origin", base, auth=True)
    base_ref = f"origin/{base}"
    if git(repo, "rev-parse", "--verify", "--quiet", f"refs/remotes/{base_ref}",
           check=False).returncode != 0:
        die(f"Base branch '{base}' was not found on origin. Push it first (`push`) or use --base.")

    cur = current_branch(repo)
    dirty = bool(git(repo, "status", "--porcelain", capture=True).stdout.strip())

    if cur and cur != base:
        if a.branch and a.branch != cur:
            die(f"You are on '{cur}' but --branch says '{a.branch}'. "
                "Omit --branch to use the current branch, or switch first.")
        head = cur
        info(f"Using the current branch '{head}'")
    else:
        head = a.branch or f"feature/{slugify(title)}"
        if git(repo, "check-ref-format", "--branch", head, check=False).returncode != 0:
            die(f"'{head}' is not a valid branch name.")
        if head == base:
            die("The PR branch must differ from the base branch.")
        if local_branch_exists(repo, head):
            die(f"Local branch '{head}' already exists - not touching it. Pick another with --branch.")
        if api("GET", f"{API}/repos/{owner}/{name}/branches/{urllib.parse.quote(head, safe='')}",
               ok_404=True):
            die(f"Branch '{head}' already exists on GitHub - not overwriting it. Pick another with --branch.")
        ahead = int(git(repo, "rev-list", "--count", f"{base_ref}..HEAD",
                        capture=True).stdout.strip() or 0)
        if not dirty and ahead == 0:
            die("Nothing to put in a pull request: no uncommitted changes and no local commits "
                f"ahead of {base_ref}.")
        git(repo, "checkout", "-q", "-b", head)
        info(f"Created branch '{head}'")

    if dirty:
        commit_all(repo, a.message or title, trailers=trailers, paths=a.paths)
    elif trailers:
        print("    ! Nothing to commit - Co-authored-by trailers only apply to commits this command "
              "creates. Your existing commits are unchanged.")

    if git(repo, "diff", "--quiet", f"{base_ref}...HEAD", check=False).returncode == 0:
        die(f"'{head}' has no changes compared with {base_ref}. No PR was opened.")
    if not substantive_diff(repo, base_ref):
        die("The only differences are whitespace/blank lines. No PR was opened.")
    print(git(repo, "diff", "--shortstat", f"{base_ref}...HEAD", capture=True).stdout.rstrip())

    git(repo, "push", "-u", "origin", head, auth=True)      # plain push: never forced
    info(f"Pushed branch '{head}'")

    q = urllib.parse.quote(f"{owner}:{head}", safe=":")
    existing = api("GET", f"{API}/repos/{owner}/{name}/pulls?state=open&head={q}", soft=True) or []
    if existing:
        pr = existing[0]
        info(f"A pull request for '{head}' is already open - new commits were added to it.")
    else:
        pr = api("POST", f"{API}/repos/{owner}/{name}/pulls", {
            "title": title, "head": head, "base": base,
            "body": a.body or "", "draft": bool(a.draft),
        })
        info(f"Opened pull request #{pr['number']}")

    revs = [r for r in a.reviewer if r.lower() != me.lower()]
    if len(revs) != len(a.reviewer):
        info("GitHub does not allow requesting your own review - skipped that name.")
    if revs:
        ok = api("POST", f"{API}/repos/{owner}/{name}/pulls/{pr['number']}/requested_reviewers",
                 {"reviewers": revs}, soft=True)
        if ok is None:
            print("    ! Could not request reviewers (they must be collaborators with access).")
        else:
            info(f"Requested review from: {', '.join(revs)}")

    print(f"    PR URL : {pr['html_url']}")
    print(f"    Status : python github_sync.py --dir {repo} pr-status {pr['number']}")
    print(f"    Merge  : python github_sync.py --dir {repo} pr-merge {pr['number']}")


def cmd_pr_status(a):
    check_config()
    owner, name, _ = resolve_owner_repo(repo_dir_or_git(a.dir))
    if a.number is None:
        prs = api_pages(f"{API}/repos/{owner}/{name}/pulls?state={a.state}"
                        "&sort=updated&direction=desc", max_pages=1)
        if not prs:
            info(f"No {a.state} pull requests on {owner}/{name}")
            return
        print(f"{a.state.capitalize()} pull requests on {owner}/{name}:")
        for p in prs:
            state = "merged" if p.get("merged_at") else p["state"]
            if p.get("draft"):
                state += "/draft"
            print(f"  #{p['number']:<4} [{state}] {p['title']}  ({p['head']['ref']} -> {p['base']['ref']})")
            print(f"        {p['html_url']}")
        return
    print_overview(pr_overview(owner, name, a.number))


def cmd_pr_review(a):
    check_config()
    owner, name, _ = resolve_owner_repo(repo_dir_or_git(a.dir))
    pr = get_pr_settled(owner, name, a.number)
    me = whoami()
    print(f"PR #{pr['number']}  {pr['title']}")
    print(f"    {pr['html_url']}")
    print(f"    by {(pr.get('user') or {}).get('login')}: {pr.get('commits')} commit(s), "
          f"{pr.get('changed_files')} file(s), +{pr.get('additions')} / -{pr.get('deletions')}")
    if a.files or not a.submit:
        files = api_pages(f"{API}/repos/{owner}/{name}/pulls/{a.number}/files", max_pages=3)
        print("    Files changed:")
        for f in files:
            print(f"      {f.get('status', '?'):<9} +{f.get('additions', 0):<4} -{f.get('deletions', 0):<4} {f['filename']}")
    if not a.submit:
        print("\n(To submit a review: pr-review N --submit approve|comment|request-changes --body \"...\")")
        return
    event = {"approve": "APPROVE", "comment": "COMMENT",
             "request-changes": "REQUEST_CHANGES"}[a.submit]
    if (pr.get("user") or {}).get("login", "").lower() == me.lower() and event == "APPROVE":
        die("GitHub does not allow you to approve your own pull request. "
            "A review has to come from a different person.")
    if event != "APPROVE" and not a.body:
        die(f"--submit {a.submit} needs --body \"your comment\".")
    payload = {"event": event}
    if a.body:
        payload["body"] = a.body
    api("POST", f"{API}/repos/{owner}/{name}/pulls/{a.number}/reviews", payload)
    info(f"Review submitted ({a.submit}) on PR #{a.number}")


def cmd_pr_merge(a):
    """Merge a PR - only when GitHub says it is mergeable under the repo's own rules."""
    check_config()
    owner, name, repo = resolve_owner_repo(repo_dir_or_git(a.dir))
    ov = pr_overview(owner, name, a.number)
    print_overview(ov)
    pr = ov["pr"]
    if not ov["ok"]:
        die("Not merging - " + "; ".join(ov["reasons"]) + ".\n"
            "This script never bypasses branch protection, required reviews, or required checks.")

    unreviewed = ov["reviews_total"] == 0
    method = a.method
    if a.yes:
        if unreviewed and not a.allow_unreviewed:
            die("This pull request has NO review. With --yes you must also pass --allow-unreviewed "
                "to merge it anyway (only possible where the repo rules do not require reviews).")
    else:
        if unreviewed:
            ok = confirm(f"PR #{a.number} has NOT been reviewed. Merge it ({method}) into "
                         f"{pr['base']['ref']} anyway?", "merge-without-review")
        else:
            ok = confirm(f"Merge PR #{a.number} ({method}) into {pr['base']['ref']}?", "merge")
        if not ok:
            info("Merge cancelled - no explicit confirmation given.")
            return

    res = api("PUT", f"{API}/repos/{owner}/{name}/pulls/{a.number}/merge",
              {"merge_method": method, "sha": pr["head"]["sha"]})
    info(f"Merged PR #{a.number}: {(res or {}).get('message', 'Pull Request successfully merged')}")
    print(f"    {pr['html_url']}")

    if a.delete_branch:
        head = pr["head"]
        same_repo = (head.get("repo") or {}).get("full_name") == pr["base"]["repo"]["full_name"]
        if not same_repo:
            info("Head branch lives in a fork - not deleting it.")
        elif head["ref"] in (pr["base"]["ref"], BRANCH):
            info("Refusing to delete the base/default branch.")
        else:
            api("DELETE", f"{API}/repos/{owner}/{name}/git/refs/heads/"
                + urllib.parse.quote(head["ref"], safe="/"))
            info(f"Deleted remote branch '{head['ref']}'")
    print(f"    Sync local copy: git -C {repo} checkout {pr['base']['ref']} && "
          f"python github_sync.py --dir {repo} pull")


def print_tier_progress(tiers, n, want=None):
    for tier, need in tiers:
        state = "reached" if n >= need else f"{need - n} to go"
        star = "   <-- requested" if want == tier else ""
        print(f"        {tier:<8} {need:>5}   {state}{star}")


def search_count(query):
    r = api("GET", f"{API}/search/issues?per_page=1&q={urllib.parse.quote(query)}", soft=True)
    return None if r is None else r.get("total_count")


def cmd_achievements(a):
    check_config()
    login = whoami()
    want = a.achievement
    print(f"Achievement report for @{login}")
    print("-" * 60)
    print("About these numbers:")
    print("  * GitHub does not expose your earned badges through its API, and it has not")
    print("    published official criteria. Tier thresholds below come from community")
    print("    trackers and may be outdated. Your real status:")
    print(f"        https://github.com/{login}?tab=achievements")
    print(f"  * {V} = counted directly from GitHub's API   {E} = derived/partial   "
          f"{U.strip()} = not exposed")
    print("  * Nothing here claims a badge was earned - only that you have (or lack) the activity.\n")

    total = search_count(f"author:{login} type:pr is:merged is:public")
    own = search_count(f"author:{login} type:pr is:merged is:public user:{login}")
    other = search_count(f"author:{login} type:pr is:merged is:public -user:{login}")
    private = search_count(f"author:{login} type:pr is:merged is:private")

    if want in ("all", "pull-shark"):
        print("Pull Shark  (merged pull requests you opened)")
        if total is None:
            print(f"    {U} GitHub search was unavailable - try again later.")
        else:
            print(f"    {V} merged PRs you opened, public repos : {total}")
            if own is not None:
                print(f"    {V}   in repos owned by @{login}          : {own}")
            if other is not None:
                print(f"    {V}   in repos owned by others/orgs        : {other}")
            if private:
                print(f"    {V} merged PRs in private repos (not counted above): {private} "
                      "- these count only if 'private contributions' is enabled in your profile settings")
            print("    Tier progress (community-documented thresholds, public count):")
            print_tier_progress(PULL_SHARK_TIERS, total, a.tier)
            print(f"    {U} whether GitHub excludes or discounts PRs in your own repositories is not "
                  "officially documented; community reports differ. The split above lets you judge.")
            print("    Each PR must be a real, reviewable change - open them as the work happens.\n")

    scan = []
    if want != "pull-shark" and a.scan > 0:
        r = api("GET", f"{API}/search/issues?per_page={min(a.scan, 50)}&sort=updated&order=desc&q="
                + urllib.parse.quote(f"author:{login} type:pr is:merged is:public"), soft=True)
        scan = (r or {}).get("items", [])

    details = []
    for it in scan:
        m = re.search(r"/repos/([^/]+)/([^/]+)$", it.get("repository_url", ""))
        if not m:
            continue
        o, n = m.group(1), m.group(2)
        num = it["number"]
        pr = api("GET", f"{API}/repos/{o}/{n}/pulls/{num}", soft=True)
        if not pr or not pr.get("merged"):
            continue
        reviews, _ = review_summary(o, n, num)
        commits = api_pages(f"{API}/repos/{o}/{n}/pulls/{num}/commits", max_pages=2, soft=True)
        co = set()
        for c in commits:
            for t in TRAILER_RE.finditer((c.get("commit") or {}).get("message", "")):
                em = t.group("email").lower()
                if em == (GITHUB_EMAIL or "").lower():
                    continue
                nr = NOREPLY_RE.match(em)
                if nr and nr.group("login").lower() == login.lower():
                    continue
                co.add(f"{t.group('name')} <{t.group('email')}>")
        details.append({"repo": f"{o}/{n}", "num": num, "reviews": reviews,
                        "merged_by": (pr.get("merged_by") or {}).get("login", ""),
                        "coauthors": co, "own": o.lower() == login.lower()})

    if want in ("all", "pair-extraordinaire"):
        print("Pair Extraordinaire  (co-authored commits in a merged PR)")
        if a.scan <= 0:
            print(f"    {U} skipped (--scan 0)")
        else:
            with_co = [d for d in details if d["coauthors"]]
            print(f"    {E} scanned your {len(details)} most recently updated merged PRs (public repos)")
            print(f"    {E} {len(with_co)} of them contain a Co-authored-by trailer for someone other than you")
            for d in with_co[:10]:
                print(f"        {d['repo']}#{d['num']}: {'; '.join(sorted(d['coauthors']))}")
            people = set().union(*[d["coauthors"] for d in with_co]) if with_co else set()
            print(f"    {E} distinct co-authors seen: {len(people)}")
            print("    Tier progress by PR count (lower bound - only the scanned window):")
            print_tier_progress(PAIR_TIERS, len(with_co), a.tier)
            print(f"    {U} GitHub only credits a co-author whose e-mail is linked to their account, "
                  "and which side of the pair receives the badge is not exposed by the API.")
            print("    The co-author must be a real person who really worked on the commit.\n")

    if want in ("all", "yolo"):
        print("YOLO  (merging a pull request without a review)")
        if a.scan <= 0:
            print(f"    {U} skipped (--scan 0)")
        else:
            mine = [d for d in details if d["merged_by"].lower() == login.lower()]
            unrev = [d for d in mine if d["reviews"] == 0]
            print(f"    {E} of {len(details)} scanned PRs, you merged {len(mine)} yourself; "
                  f"{len(unrev)} of those had no submitted review")
            for d in unrev[:10]:
                print(f"        {d['repo']}#{d['num']}")
            print(f"    {U} GitHub has not published exact YOLO rules (e.g. whether a reviewer must "
                  "have been requested). This is a single-tier achievement - check your profile.")
            print("    `pr-merge` only allows an unreviewed merge when the repo's own rules permit it.\n")

    print("Reminder: badges are a by-product of real work. Don't create trivial PRs to chase tiers.")


def main():
    ap = argparse.ArgumentParser(description="Universal GitHub push/pull/release/topics/PR helper (Termux)")
    ap.add_argument("--dir", help="repo folder (default: current directory). "
                                   "Climbs one level if run from a tools/ subfolder.")
    sub = ap.add_subparsers(dest="cmd", required=True)

    for name, fn, h in (
        ("init",  cmd_init,   "create the GitHub repo and do the first push"),
        ("push",  cmd_push,   "↑  upload local commits to GitHub (local → GitHub)"),
        ("pull",  cmd_pull,   "↓  download new commits from GitHub (GitHub → local)"),
        ("clone", cmd_clone,  "fresh copy of the repo on a new device"),
        ("status", cmd_status,"show configuration and git status"),
    ):
        p = sub.add_parser(name, help=h)
        p.add_argument("-m", "--message", help="commit message")
        p.set_defaults(fn=fn)

    r = sub.add_parser("release", help="create a GitHub Release and upload a file")
    r.add_argument("--tag", default="v1.0", help="release tag, e.g. v1.0 (default: v1.0)")
    r.add_argument("--title", help="release title")
    r.add_argument("--notes", help="release notes text (default: a short note)")
    r.add_argument("--apk", help="path to the file to upload (default: app.apk in the repo folder)")
    r.add_argument("--prerelease", action="store_true")
    r.add_argument("--no-push", dest="push", action="store_false",
                   help="do not commit/push before tagging")
    r.set_defaults(fn=cmd_release, push=True)

    t = sub.add_parser("topics",
        help=f"About panel: topics (tags) and description (max {DESC_MAX} chars)")
    t.add_argument("names", nargs="*",
                   help="tag names (lowercase, letters/digits/hyphens; spaces become '-')")
    t.add_argument("--add", action="store_true",
                   help="add these tags to the existing ones")
    t.add_argument("--delete", action="store_true",
                   help="remove only the named tags, keep the rest")
    t.add_argument("--clear", action="store_true",
                   help="remove ALL tags from the About panel")
    t.add_argument("--list", action="store_true",
                   help="print current tags and description")
    t.add_argument("--desc", metavar="TEXT",
                   help=f"set the About description (max {DESC_MAX} chars); use \"\" to clear")
    t.add_argument("--show-desc", action="store_true",
                   help="print only the current About description")
    t.set_defaults(fn=cmd_topics)

    # ---- pull requests -------------------------------------------------
    pc = sub.add_parser("pr-create", help="branch + commit + push + open a pull request")
    pc.add_argument("-t", "--title", required=True, help="PR title (describe the real change)")
    pc.add_argument("--body", help="PR description")
    pc.add_argument("-m", "--message", help="commit message (default: the PR title)")
    pc.add_argument("--branch", help="new branch name (default: feature/<slug-of-title>)")
    pc.add_argument("--base", help=f"base branch to merge into (default: {BRANCH})")
    pc.add_argument("--draft", action="store_true", help="open as a draft PR")
    pc.add_argument("--reviewer", action="append", default=[], metavar="LOGIN",
                    help="request a review from this GitHub user (repeatable; must be a collaborator)")
    pc.add_argument("--co-author", dest="co_author", action="append", default=[],
                    metavar='"NAME <EMAIL>"',
                    help="add a Co-authored-by trailer for a REAL collaborator (repeatable)")
    pc.add_argument("--confirm-coauthor", action="store_true",
                    help="state non-interactively that each co-author genuinely contributed")
    pc.add_argument("--paths", nargs="+", metavar="PATH",
                    help="stage only these paths instead of everything (git add -A)")
    pc.set_defaults(fn=cmd_pr_create)

    ps = sub.add_parser("pr-status", help="list pull requests, or show one in detail")
    ps.add_argument("number", nargs="?", type=int, help="PR number (omit to list)")
    ps.add_argument("--state", choices=["open", "closed", "all"], default="open",
                    help="which PRs to list (default: open)")
    ps.set_defaults(fn=cmd_pr_status)

    pr_ = sub.add_parser("pr-review", help="inspect a PR's changes; optionally submit a review")
    pr_.add_argument("number", type=int)
    pr_.add_argument("--files", action="store_true", help="list the changed files")
    pr_.add_argument("--submit", choices=["approve", "comment", "request-changes"],
                     help="submit a review (you cannot approve your own PR)")
    pr_.add_argument("--body", help="review text (required for comment / request-changes)")
    pr_.set_defaults(fn=cmd_pr_review)

    pm = sub.add_parser("pr-merge", help="merge a PR (asks for explicit confirmation)")
    pm.add_argument("number", type=int)
    pm.add_argument("--method", choices=["merge", "squash", "rebase"], default="merge",
                    help="merge strategy (default: merge)")
    pm.add_argument("--yes", action="store_true",
                    help="automation: skip the typed confirmation")
    pm.add_argument("--allow-unreviewed", action="store_true",
                    help="with --yes: also allow merging a PR that has no review "
                         "(only works where repo rules do not require one)")
    pm.add_argument("--delete-branch", action="store_true",
                    help="delete the remote head branch after merging")
    pm.set_defaults(fn=cmd_pr_merge)

    ac = sub.add_parser("achievements",
                        help="merged-PR / co-author / review stats, labelled verified vs estimate")
    ac.add_argument("--achievement", choices=["all", "pull-shark", "pair-extraordinaire", "yolo"],
                    default="all")
    ac.add_argument("--tier", choices=["default", "bronze", "silver", "gold"],
                    help="highlight progress toward this tier")
    ac.add_argument("--scan", type=int, default=20, metavar="N",
                    help="inspect your N most recent merged PRs for co-authors / reviews "
                         "(default 20, max 50, 0 = skip)")
    ac.set_defaults(fn=cmd_achievements)

    a = ap.parse_args()
    a.fn(a)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        die("Interrupted", 130)
