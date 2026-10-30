import json
import os
import re
import subprocess
import urllib.parse
import urllib.request

REPO = os.environ["CI_PROJECT_PATH"]
REPO_URL = os.environ["CI_PROJECT_URL"]
API_URL = os.environ.get("CI_API_V4_URL", "https://gitlab.com/api/v4")
PROJECT_ID = os.environ["CI_PROJECT_ID"]

COLORS = {"added": 0x2EA043, "removed": 0xDA3633, "updated": 0x1F6FEB}


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, text=True, encoding="utf-8", check=True).stdout


def api(path):
    try:
        with urllib.request.urlopen(urllib.request.Request(f"{API_URL}/{path}", headers={"User-Agent": REPO}), timeout=30) as response:
            return json.load(response)
    except Exception as error:
        print(f"GitLab API {path}: {error}")
        return {}


def commits():
    base = os.environ.get("BASE", "")
    has_base = base and subprocess.run(["git", "cat-file", "-e", f"{base}^{{commit}}"], capture_output=True).returncode == 0
    out = git(
        "log", "--no-merges", "--reverse", "--format=%H%x1f%an%x1f%ae%x1f%aI%x1f%s%x1f%b%x1e",
        f"{base}..HEAD" if has_base else "-1", "--", ".", ":!.gitlab", ":!.gitlab-ci.yml", ":!*.md",
    )
    return [entry.strip("\n").split("\x1f") for entry in out.split("\x1e") if entry.strip("\n")]


def extension(revision, path):
    try:
        text = git("show", f"{revision}:{path}")
    except subprocess.CalledProcessError:
        return None
    name = re.search(r'^\s*name = "(.+)"', text, re.M)
    lib = re.search(r'^\s*libVersion = "(.+)"', text, re.M)
    code = re.search(r"^\s*versionCode = (\d+)", text, re.M)
    return {
        "name": name.group(1) if name else path.split("/")[2],
        "version": f"{lib.group(1)}.{code.group(1)}" if lib and code else None,
    }


def extension_fields(sha):
    fields, kinds = [], set()
    changes = git("show", "--no-renames", "--name-status", "--format=", sha, "--", "src/*/*/build.gradle.kts")
    for status, path in (line.split("\t") for line in changes.splitlines() if line):
        old, new = extension(f"{sha}^", path), extension(sha, path)
        if status == "A" and new:
            kinds.add("added")
            fields.append({"name": new["name"], "value": f"Nova fonte · `v{new['version']}`"})
        elif status == "D" and old:
            kinds.add("removed")
            fields.append({"name": old["name"], "value": "Removida"})
        elif old and new and (old["version"], old["name"]) != (new["version"], new["name"]):
            kinds.add("updated")
            value = f"`v{old['version']}` → `v{new['version']}`" if old["version"] != new["version"] else f"`v{new['version']}`"
            if old["name"] != new["name"]:
                value += f" · antes {old['name']}"
            fields.append({"name": new["name"], "value": value})
    kind = kinds.pop() if len(kinds) == 1 else "updated"
    return [dict(field, inline=True) for field in fields[:25]], kind


def reflow(body):
    paragraphs = [re.sub(r"\n(?![-*] )", " ", p.strip()) for p in re.split(r"\n\s*\n", body) if p.strip()]
    return "\n\n".join(paragraphs)[:4096]


def embed(sha, author_name, author_email, date, subject, body):
    avatar = api(f"avatar?email={urllib.parse.quote(author_email)}").get("avatar_url")
    fields, kind = extension_fields(sha)
    embed = {
        "author": {"name": author_name, "icon_url": avatar},
        "title": f"[{REPO}] {subject}"[:256],
        "url": f"{REPO_URL}/-/commit/{sha}",
        "description": reflow(body),
        "fields": fields,
        "color": COLORS[kind],
        "footer": {"text": sha[:7]},
        "timestamp": date,
    }
    embed["author"] = {key: value for key, value in embed["author"].items() if value}
    return {key: value for key, value in embed.items() if value}


def length(embed):
    texts = [embed["title"], embed.get("description", ""), embed["author"]["name"], embed["footer"]["text"]]
    texts += [text for field in embed.get("fields", []) for text in (field["name"], field["value"])]
    return sum(map(len, texts))


def batches(embeds):
    groups, size = [[]], 0
    for item in embeds:
        if groups[-1] and (len(groups[-1]) == 10 or size + length(item) > 6000):
            groups.append([])
            size = 0
        groups[-1].append(item)
        size += length(item)
    return groups


def send(embeds, owner):
    payload = {
        "username": REPO.split("/")[1],
        "avatar_url": owner.get("avatar_url"),
        "embeds": embeds,
        "allowed_mentions": {"parse": []},
    }
    request = urllib.request.Request(
        os.environ["DISCORD_WEBHOOK"] + "?wait=true",
        data=json.dumps({key: value for key, value in payload.items() if value}).encode(),
        headers={"Content-Type": "application/json", "User-Agent": f"DiscordBot ({REPO_URL}, 1)"},
    )
    urllib.request.urlopen(request, timeout=30).read()


def main():
    embeds = [embed(*entry) for entry in commits()]
    if not embeds:
        print("No extension commits to announce")
        return
    project = api(f"projects/{PROJECT_ID}")
    owner = project.get("owner") or project.get("namespace") or {}
    for group in batches(embeds):
        send(group, owner)
    print(f"Announced {len(embeds)} commit(s)")


if __name__ == "__main__":
    main()
