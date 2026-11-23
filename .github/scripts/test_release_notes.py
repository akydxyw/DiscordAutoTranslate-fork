"""Run in GitHub Actions; fixtures never create tags, releases or APKs."""

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from release_notes import UPDATE_LOG, read_update_log, render_notes


class ReleaseNotesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "Update-Log.md"
        self.metadata = dict(mode="stable", version="1.2.3", version_code=5,
                             source_ref="main", source_sha="a" * 40,
                             repository="owner/project", run_id=42)

    def test_missing_file_fails(self):
        with self.assertRaises(ValueError):
            read_update_log(self.path)

    def test_symlink_fails(self):
        target = self.path.with_name("target.md")
        target.write_text("- 修复错误", encoding="utf-8")
        self.path.symlink_to(target)
        with self.assertRaises(ValueError):
            read_update_log(self.path)

    def test_empty_and_placeholder_logs_fail(self):
        for content in ["", " ", "## 修复\n<!-- hidden -->", "## 修复\n- TODO",
                        "- TBD", "- 待补充", "# 1.2.3\n- 修复错误",
                        "  # 1.2.3\n- 修复错误", "修复\n====", "修复\n----"]:
            with self.subTest(content=content):
                self.path.write_text(content, encoding="utf-8")
                with self.assertRaises(ValueError):
                    read_update_log(self.path)

    def test_invalid_utf8_fails(self):
        self.path.write_bytes(b"\xff")
        with self.assertRaises(UnicodeError):
            read_update_log(self.path)

    def test_user_markdown_is_preserved(self):
        content = "## 修复\n\n- 修复 **测试**。\n- [详情](https://example.com)"
        self.path.write_text(content + "\n", encoding="utf-8")
        self.assertEqual(content, read_update_log(self.path))

    def test_stable_and_prerelease_share_the_update_log(self):
        content = "## 修复\n\n- 修复错误。"
        stable = render_notes(content, **self.metadata)
        rc_metadata = dict(self.metadata, mode="prerelease", version="1.2.3-rc.1",
                           source_ref="fix/example")
        prerelease = render_notes(content, **rc_metadata)
        for notes in [stable, prerelease]:
            self.assertEqual(1, notes.count(content))
            self.assertIn("versionCode：5", notes)
            self.assertIn("https://github.com/owner/project/actions/runs/42", notes)
            self.assertIn("https://github.com/owner/project/commit/" + "a" * 40, notes)
        self.assertNotIn("预发布", stable)
        self.assertIn("（预发布）", prerelease)
        self.assertIn("`fix/example`", prerelease)
        self.assertIn("不代表已完成设备回归", prerelease)

    def test_invalid_metadata_fails(self):
        cases = [dict(mode="unknown"), dict(version="1.2.3-rc.1"),
                 dict(mode="prerelease"), dict(mode="prerelease", version="1.2.3-rc.0"),
                 dict(version="../1.2.3"), dict(version_code=0), dict(run_id=0),
                 dict(source_ref=""), dict(source_ref="bad`ref"), dict(source_ref="bad\nref"),
                 dict(source_sha="abc123"), dict(repository="https://github.com/a/b")]
        for changes in cases:
            with self.subTest(changes=changes):
                with self.assertRaises(ValueError):
                    render_notes("- 修复错误", **dict(self.metadata, **changes))

    def test_cli_validates_arguments_and_writes_only_complete_notes(self):
        # Recreate the fixed production layout; never depend on the repository log's contents.
        root = Path(self.temp.name)
        script = root / ".github/scripts/release_notes.py"
        script.parent.mkdir(parents=True)
        script.write_bytes((UPDATE_LOG.parents[2] / ".github/scripts/release_notes.py").read_bytes())
        log = root / "docs/releases/Update-Log.md"
        log.parent.mkdir(parents=True)
        log.write_text("- 修复错误", encoding="utf-8")
        output = root / "release-notes.md"
        command = [sys.executable, str(script)]
        failures = [(["--mode", "stable", "--output", str(output)], "需要全部构建信息参数"),
                    (["--check", "--mode", "stable"], "不能与生成参数同时使用")]
        for arguments, message in failures:
            with self.subTest(arguments=arguments):
                result = subprocess.run(command + arguments, capture_output=True, text=True)
                self.assertEqual(2, result.returncode)
                self.assertIn(message, result.stderr)
                self.assertFalse(output.exists())
        arguments = ["--mode", "stable", "--version", "1.2.3", "--version-code", "5",
                     "--source-ref", "main", "--source-sha", "a" * 40,
                     "--repository", "owner/project", "--run-id", "42", "--output", str(output)]
        result = subprocess.run(command + arguments, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(render_notes("- 修复错误", **self.metadata), output.read_text(encoding="utf-8"))
        output.unlink()
        log.unlink()
        result = subprocess.run(command + arguments, capture_output=True, text=True)
        self.assertEqual(1, result.returncode)
        self.assertIn("更新日志必须是普通文件", result.stderr)
        self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
