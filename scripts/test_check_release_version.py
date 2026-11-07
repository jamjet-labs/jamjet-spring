"""Tests for check_release_version.py against a fake Central and a scratch git repo.

Run: python3 -m unittest discover -s scripts -p 'test_*.py'
"""

import http.server
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("check_release_version.py")
REPO = Path(__file__).resolve().parent.parent


class FakeCentral(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


def git(cwd, *args):
    subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True,
                   env={"GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t", "GIT_COMMITTER_NAME": "t",
                        "GIT_COMMITTER_EMAIL": "t@t", "PATH": "/usr/bin:/bin:/usr/local/bin"})


class CheckReleaseVersionTest(unittest.TestCase):

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        # The project: this repo's POMs and the one source file the script reads.
        self.root = self.tmp / "repo"
        for pom in [REPO / "pom.xml", *REPO.glob("*/pom.xml")]:
            target = self.root / pom.relative_to(REPO)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(pom, target)
        client = "jamjet-spring-boot-autoconfigure/src/main/java/dev/jamjet/spring/client/JamjetRuntimeClient.java"
        (self.root / client).parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(REPO / client, self.root / client)
        # A fixed release version, so the tests do not depend on the repo's own
        # version (a -SNAPSHOT skips the Central check).
        self.version = "9.9.9"
        self.set_version(self.version)
        git(self.root, "init", "-q")
        git(self.root, "add", "-A")
        git(self.root, "commit", "-q", "-m", "release")
        # A fake Central that has nothing yet.
        self.central = self.tmp / "central"
        self.central.mkdir()
        handler = lambda *a, **k: FakeCentral(*a, directory=str(self.central), **k)
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def tearDown(self):
        self.server.shutdown()
        shutil.rmtree(self.tmp)

    def publish(self, artifact, version):
        path = self.central / "dev/jamjet" / artifact / version
        path.mkdir(parents=True, exist_ok=True)
        (path / f"{artifact}-{version}.pom").write_text("<project/>")

    def run_check(self, *extra):
        url = f"http://127.0.0.1:{self.server.server_address[1]}"
        return subprocess.run([sys.executable, str(SCRIPT), "--root", str(self.root), "--central", url, *extra],
                              capture_output=True, text=True)

    def test_passes_when_nothing_is_published(self):
        self.assertEqual(self.run_check().returncode, 0)

    def test_fails_when_one_module_already_has_the_version(self):
        self.publish("engram-spring-boot-starter", self.version)
        result = self.run_check()
        self.assertEqual(result.returncode, 1)
        self.assertIn("engram-spring-boot-starter", result.stderr)

    def test_released_tag_on_this_line_is_allowed_only_with_the_flag(self):
        self.publish("jamjet-spring-boot-parent", self.version)
        git(self.root, "tag", f"v{self.version}")
        git(self.root, "commit", "-q", "--allow-empty", "-m", "after the release")
        self.assertEqual(self.run_check().returncode, 1, "the deploy-time check must stay strict")
        self.assertEqual(self.run_check("--allow-released-tag").returncode, 0)

    def test_the_flag_does_not_excuse_a_version_without_its_tag(self):
        self.publish("jamjet-spring-boot-parent", self.version)
        self.assertEqual(self.run_check("--allow-released-tag").returncode, 1)

    def test_the_flag_does_not_excuse_a_tag_on_another_line(self):
        self.publish("jamjet-spring-boot-parent", self.version)
        git(self.root, "branch", "-m", "work")
        git(self.root, "checkout", "-q", "--orphan", "other")
        git(self.root, "commit", "-q", "-m", "other line")
        git(self.root, "tag", f"v{self.version}")
        git(self.root, "checkout", "-q", "work")
        self.assertEqual(self.run_check("--allow-released-tag").returncode, 1)

    def set_version(self, version):
        for pom in [self.root / "pom.xml", *self.root.glob("*/pom.xml")]:
            text = pom.read_text()
            text = re.sub(r"(<artifactId>jamjet-spring-boot-parent</artifactId>\s*<version>)[^<]+(</version>)",
                          rf"\g<1>{version}\g<2>", text, count=1)
            pom.write_text(text)
        client = next(self.root.rglob("JamjetRuntimeClient.java"))
        client.write_text(re.sub(r'"jamjet-spring-boot-starter/[^"]+"', f'"jamjet-spring-boot-starter/{version}"',
                                 client.read_text()))

    def commit_version(self, version, readme=None):
        self.set_version(version)
        if readme is not None:
            (self.root / "README.md").write_text(readme)
        git(self.root, "add", "-A")
        git(self.root, "commit", "-q", "-m", version)

    def test_a_snapshot_line_may_show_the_release_it_prepared(self):
        self.commit_version("0.4.0")
        self.commit_version("0.5.0-SNAPSHOT", "dev.jamjet:jamjet-spring-boot-starter:0.4.0\n")
        self.assertEqual(self.run_check().returncode, 1, "0.4.0 is not on Central yet")
        self.assertEqual(self.run_check("--allow-pending-release").returncode, 0)

    def test_the_pending_release_is_only_the_latest_one(self):
        self.commit_version("0.3.9")
        self.commit_version("0.4.0")
        self.commit_version("0.5.0-SNAPSHOT", "dev.jamjet:jamjet-spring-boot-starter:0.3.9\n")
        self.assertEqual(self.run_check("--allow-pending-release").returncode, 1)

    def test_the_pending_release_covers_only_published_modules(self):
        self.commit_version("0.4.0")
        self.commit_version("0.5.0-SNAPSHOT", "dev.jamjet:jamjet-spring-boot-starter-example:0.4.0\n")
        self.assertEqual(self.run_check("--allow-pending-release").returncode, 1)

    def test_network_error_never_passes(self):
        result = subprocess.run([sys.executable, str(SCRIPT), "--root", str(self.root),
                                 "--central", "http://127.0.0.1:9"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)


if __name__ == "__main__":
    unittest.main()
