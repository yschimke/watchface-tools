"""Tests for `.github/scripts/expected-poms.py`.

Each test writes a settings file and module build scripts shaped like this repository's into a
throwaway directory and asks which POMs a release must have produced.

Run: python3 -m unittest discover -s .github/scripts/tests -v
"""

from __future__ import annotations

import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "expected-poms.py"

SETTINGS = """\
include(":wff-core")
include(":wff-packager")
include(":unpublished")
project(":wff-core").projectDir = file("api/wff-core")
"""

JVM = """\
plugins {
  id("watchface.maven-publishing")
  alias(libs.plugins.kotlin.jvm)
}
"""

KMP = """\
plugins {
  id("watchface.maven-publishing")
  alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
  jvm()
  @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class) wasmJs { browser() }
}
"""


class ExpectedPomsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.dir = pathlib.Path(tempfile.mkdtemp())
        (self.dir / "settings.gradle.kts").write_text(SETTINGS)
        for path, text in [
            ("wff-packager", JVM),
            ("api/wff-core", KMP),
            ("unpublished", "plugins { alias(libs.plugins.kotlin.jvm) }\n"),
        ]:
            (self.dir / path).mkdir(parents=True)
            (self.dir / path / "build.gradle.kts").write_text(text)

    def run_script(self, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--version", "2.0.0", *args],
            cwd=self.dir, capture_output=True, text=True,
        )

    def lines(self, result: subprocess.CompletedProcess[str]) -> list[str]:
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.splitlines()

    def test_recovery_run_needs_no_manifest(self) -> None:
        # A `workflow_dispatch` run computes no plan, so no manifest is written.
        self.assertEqual(
            self.lines(self.run_script()),
            [
                "wff-core 2.0.0",
                "wff-core-jvm 2.0.0",
                "wff-core-wasm-js 2.0.0",
                "wff-packager 2.0.0",
                "watchface-tools-bom 2.0.0",
            ],
        )

    def test_new_module_in_publish_set_is_checked_without_a_manifest_entry(self) -> None:
        (self.dir / "publishing-manifest.json").write_text(
            json.dumps({"modules": {"wff-packager": "1.0.0"}})
        )
        self.assertEqual(
            self.lines(self.run_script("--publish-set", "wff-core")),
            [
                "wff-core 2.0.0",
                "wff-core-jvm 2.0.0",
                "wff-core-wasm-js 2.0.0",
                "wff-packager 1.0.0",
                "watchface-tools-bom 2.0.0",
            ],
        )

    def test_skipped_module_without_a_manifest_entry_fails(self) -> None:
        (self.dir / "publishing-manifest.json").write_text(json.dumps({"modules": {}}))
        result = self.run_script("--publish-set", "")
        self.assertEqual(result.returncode, 1)
        self.assertIn("wff-core is skipped by the plan", result.stderr)


if __name__ == "__main__":
    unittest.main()
