#!/usr/bin/env python3
"""Refuse a release version that Maven Central already has.

Central never accepts a second upload of a version, and dev.jamjet modules
here have been released at different versions (the parent and the Engram
modules reached 0.2.0 while the JamJet starter stopped at 0.1.0), so a
version that is free for one module can be taken for another.

Checks, from the repository root:

1. Every module's <parent><version> equals the root version.
2. Unless the version is a -SNAPSHOT, no deployable artifact (the parent
   and every module without maven.deploy.skip) has that version on Central.
3. Every dev.jamjet coordinate in a tracked Markdown file either exists on
   Central or is a deployable artifact at the version being released, so the
   docs never point at something nobody can download.
4. Version strings written into source (the runtime client's User-Agent)
   match the project version.

Exit 0 when all pass, 1 when a check fails, 2 when Central could not be
asked (the check never passes on a network error).

--allow-released-tag (CI only): a version already on Central passes check 2
when the tag v<version> exists and is an ancestor of HEAD, i.e. this branch is
the line that released it and has not bumped yet. Without the flag (the
deploy-phase run in the parent POM) a released version always fails.

Usage: python3 scripts/check_release_version.py [--root DIR] [--central URL] [--allow-released-tag]
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
GROUP = "dev.jamjet"
DEFAULT_CENTRAL = "https://repo1.maven.org/maven2"

XML_COORD = re.compile(
    r"<groupId>\s*dev\.jamjet\s*</groupId>\s*"
    r"<artifactId>\s*([\w.-]+)\s*</artifactId>\s*"
    r"<version>\s*([^<\s]+)\s*</version>"
)
GAV_COORD = re.compile(r"dev\.jamjet:([\w.-]+):(\d[\w.-]*)")

# (file, pattern whose group 1 must equal the project version)
VERSION_LITERALS = [
    (
        "jamjet-spring-boot-autoconfigure/src/main/java/dev/jamjet/spring/client/JamjetRuntimeClient.java",
        re.compile(r'"jamjet-spring-boot-starter/([^"]+)"'),
    ),
]


class CentralUnavailable(Exception):
    pass


def text(node: ET.Element, path: str) -> str | None:
    found = node.find(path, NS)
    return found.text.strip() if found is not None and found.text else None


def read_project(root: Path) -> tuple[str, list[str], list[str]]:
    """Return (version, deployable artifactIds, problems)."""
    pom = ET.parse(root / "pom.xml").getroot()
    version = text(pom, "m:version")
    if version is None:
        sys.exit(f"{root / 'pom.xml'}: no <version>")
    deployable = [text(pom, "m:artifactId")]
    problems: list[str] = []
    for module in pom.findall("m:modules/m:module", NS):
        module_pom = ET.parse(root / module.text.strip() / "pom.xml").getroot()
        artifact = text(module_pom, "m:artifactId")
        parent_version = text(module_pom, "m:parent/m:version")
        if parent_version != version:
            problems.append(
                f"{module.text}/pom.xml: parent version {parent_version}, root is {version}"
            )
        own_version = text(module_pom, "m:version")
        if own_version is not None and own_version != version:
            problems.append(f"{module.text}/pom.xml: own version {own_version}, root is {version}")
        if text(module_pom, "m:properties/m:maven.deploy.skip") != "true":
            deployable.append(artifact)
    return version, deployable, problems


def on_central(central: str, artifact: str, version: str) -> bool:
    url = f"{central}/{GROUP.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.pom"
    last_error: Exception | None = None
    for attempt in range(3):
        request = urllib.request.Request(url, method="HEAD")
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                return response.status == 200
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return False
            last_error = e
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            last_error = e
        time.sleep(2 * (attempt + 1))
    raise CentralUnavailable(f"{url}: {last_error}")


def markdown_files(root: Path) -> list[Path]:
    try:
        listed = subprocess.run(
            ["git", "ls-files", "*.md", "**/*.md"],
            cwd=root, check=True, capture_output=True, text=True,
        ).stdout.split()
        return sorted({root / p for p in listed})
    except (OSError, subprocess.CalledProcessError):
        return sorted(p for p in root.rglob("*.md") if "target" not in p.parts)


def doc_coordinates(path: Path) -> list[tuple[int, str, str]]:
    content = path.read_text(encoding="utf-8")
    found = []
    for pattern in (XML_COORD, GAV_COORD):
        for match in pattern.finditer(content):
            line = content.count("\n", 0, match.start()) + 1
            found.append((line, match.group(1), match.group(2)))
    return found


def released_from_this_line(root: Path, version: str) -> bool:
    """True if tag v<version> exists and is an ancestor of HEAD."""
    tag = f"v{version}"
    try:
        subprocess.run(["git", "rev-parse", "--verify", "--quiet", f"refs/tags/{tag}"],
                       cwd=root, check=True, capture_output=True)
        subprocess.run(["git", "merge-base", "--is-ancestor", tag, "HEAD"],
                       cwd=root, check=True, capture_output=True)
        return True
    except (OSError, subprocess.CalledProcessError):
        return False


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", type=Path, default=Path.cwd())
    parser.add_argument("--central", default=DEFAULT_CENTRAL)
    parser.add_argument("--allow-released-tag", action="store_true",
                        help="CI only: accept a version released from an ancestor tag v<version>")
    args = parser.parse_args()
    root = args.root.resolve()

    version, deployable, problems = read_project(root)
    snapshot = version.endswith("-SNAPSHOT")
    print(f"project version {version}; deployable: {', '.join(deployable)}")

    accepted = {version, version.removesuffix("-SNAPSHOT")}
    for relative, pattern in VERSION_LITERALS:
        source = root / relative
        match = pattern.search(source.read_text(encoding="utf-8")) if source.exists() else None
        if match is None:
            problems.append(f"{relative}: version literal not found")
        elif match.group(1) not in accepted:
            problems.append(f"{relative}: says {match.group(1)}, project version is {version}")

    try:
        if snapshot:
            print(f"{version} is a snapshot: Central release check skipped")
        else:
            released_here = args.allow_released_tag and released_from_this_line(root, version)
            for artifact in deployable:
                if on_central(args.central, artifact, version):
                    if released_here:
                        print(f"{GROUP}:{artifact}:{version} is on Central, released from tag v{version} "
                              f"on this line; bump the version before the next release")
                        continue
                    problems.append(
                        f"{GROUP}:{artifact}:{version} is already on Maven Central; "
                        f"pick a version no module has released"
                    )

        checked: dict[tuple[str, str], bool] = {}
        for path in markdown_files(root):
            for line, artifact, doc_version in doc_coordinates(path):
                if "${" in doc_version:
                    continue
                if not snapshot and doc_version == version and artifact in deployable:
                    continue  # published by this release
                key = (artifact, doc_version)
                if key not in checked:
                    checked[key] = on_central(args.central, artifact, doc_version)
                if not checked[key]:
                    problems.append(
                        f"{path.relative_to(root)}:{line}: {GROUP}:{artifact}:{doc_version} "
                        f"is not on Maven Central"
                        + ("" if snapshot or artifact not in deployable
                           else f" and is not what this build ({version}) releases")
                    )
    except CentralUnavailable as e:
        print(f"could not reach Maven Central, nothing was verified: {e}", file=sys.stderr)
        return 2

    if problems:
        print("release version check failed:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    print("release version check passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
