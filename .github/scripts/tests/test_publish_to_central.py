"""Tests for `.github/scripts/publish-to-central.sh`, release.yml's upload step.

A fake Gradle launcher answers `printPublishTasks` with canned rows and records every invocation,
so each branch — the planned empty set's `exit 0`, the unplanned run that resolves nothing, and the
upload itself — runs here instead of first running in a real release. What `printPublishTasks`
itself returns for each publish set is checked against the real build by
`print-publish-tasks-test.sh`.
"""

from __future__ import annotations

import os
import pathlib
import subprocess
import tempfile
import textwrap
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "publish-to-central.sh"

FAKE_GRADLEW = textwrap.dedent(
    """\
    #!/usr/bin/env bash
    printf '%s\\n' "$*" >> "$FAKE_LOG"
    if [ "$2" = "printPublishTasks" ]; then
      printf '%s' "$FAKE_ROWS"
      exit "${FAKE_PRINT_EXIT:-0}"
    fi
    """
)


class PublishToCentralTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        d = pathlib.Path(self.tmp.name)
        self.gradlew = d / "gradlew"
        self.gradlew.write_text(FAKE_GRADLEW, encoding="utf-8")
        self.gradlew.chmod(0o755)
        self.log = d / "log"

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def run_step(self, planned: str, publish_set: str, rows: str, **extra: str):
        env = {
            **os.environ,
            "GRADLEW": str(self.gradlew),
            "FAKE_LOG": str(self.log),
            "FAKE_ROWS": rows,
            "TAG_NAME": "v0.2.0",
            "PUBLISH_PLANNED": planned,
            "PUBLISH_SET": publish_set,
            **extra,
        }
        proc = subprocess.run([str(SCRIPT)], env=env, capture_output=True, text=True)
        calls = self.log.read_text(encoding="utf-8").splitlines() if self.log.exists() else []
        return proc, calls

    def test_planned_empty_set_uploads_nothing_and_succeeds(self) -> None:
        proc, calls = self.run_step("true", "", rows="")
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertIn("nothing to upload", proc.stdout)
        # Exactly one Gradle call — the task listing, with the EMPTY property passed through.
        self.assertEqual(calls[0], "-q printPublishTasks -Pwatchface.publishSet=")
        self.assertEqual(len([c for c in calls if True]), 1)

    def test_unplanned_run_with_no_tasks_fails(self) -> None:
        proc, calls = self.run_step("false", "", rows="")
        self.assertEqual(proc.returncode, 1)
        self.assertIn("no publish tasks resolved", proc.stdout)
        # No property at all: a recovery run means "publish everything", not "publish nothing".
        self.assertEqual(calls[0], "-q printPublishTasks")

    def test_planned_set_uploads_its_tasks_at_the_tag_version(self) -> None:
        rows = (
            ":bom:publishAndReleaseToMavenCentral\tbom\n"
            ":wff-core:publishAndReleaseToMavenCentral\twff-core\n"
        )
        proc, calls = self.run_step("true", "wff-core", rows=rows)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertEqual(
            calls[1],
            "--no-daemon :bom:publishAndReleaseToMavenCentral "
            ":wff-core:publishAndReleaseToMavenCentral "
            "-Pwatchface.publishSet=wff-core -Prelease",
        )

    def test_unplanned_run_uploads_without_a_publish_set(self) -> None:
        rows = ":bom:publishAndReleaseToMavenCentral\tbom\n"
        proc, calls = self.run_step("false", "", rows=rows)
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertEqual(calls[1], "--no-daemon :bom:publishAndReleaseToMavenCentral -Prelease")

    def test_a_failing_task_listing_fails_the_step(self) -> None:
        proc, calls = self.run_step("true", "", rows="", FAKE_PRINT_EXIT="1")
        self.assertNotEqual(proc.returncode, 0)
        self.assertEqual(len([c for c in calls if True]), 1)


if __name__ == "__main__":
    unittest.main()
