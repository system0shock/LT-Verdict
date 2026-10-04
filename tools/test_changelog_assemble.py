"""Behavior tests for changelog fragment assembly."""

import contextlib
import io
import tempfile
import unittest
from unittest import mock
from pathlib import Path

import changelog_assemble


class ChangelogAssembleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.fragments = self.root / "changelog.d"
        self.fragments.mkdir()
        self.changelog = self.root / "CHANGELOG.md"
        self.changelog.write_bytes(b"# Changelog\n\n## [Unreleased]\n\n## [1.0.0] - 2026-01-01\n")

    def fragment(self, name, content):
        path = self.fragments / name
        path.write_bytes(content.encode("utf-8") if isinstance(content, str) else content)
        return path

    def run_cli(self, *args):
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = changelog_assemble.main(["--root", str(self.root), *args])
        return result, stdout.getvalue(), stderr.getvalue()

    def test_preview_natural_order_and_no_changes(self):
        original = self.changelog.read_bytes()
        self.fragment("100.added.md", "- hundred\n")
        self.fragment("slug.added.md", "- slug\n")
        self.fragment("87.Added.md", "- eighty-seven\n")
        result, out, err = self.run_cli()
        self.assertEqual((0, "### Added\n\n- eighty-seven\n- hundred\n- slug\n\n", ""), (result, out, err))
        self.assertEqual(original, self.changelog.read_bytes())
        self.assertEqual(3, len(list(self.fragments.iterdir())))

    def test_apply_appends_and_preserves_unrelated_bytes(self):
        original = (b"# Changelog\n\n## [Unreleased]\n\n### Added\n\n- old\n\n"
                    b"### Security\n\n- secure\n\n## [1.0.0] - 2026-01-01\n\n- released\n")
        self.changelog.write_bytes(original)
        self.fragment("12.added.md", "\n- new   \n  more  \n\n")
        self.assertEqual(0, self.run_cli("--apply")[0])
        expected = original.replace(b"- old\n\n### Security", b"- old\n- new\n  more\n\n### Security")
        self.assertEqual(expected, self.changelog.read_bytes())

    def test_missing_sections_created_before_unknown_heading(self):
        self.changelog.write_bytes(b"## [Unreleased]\n\n### Added\n\n- existing\n\n### Security\n\n- keep\n\n## [1.0.0]\n")
        self.fragment("1.changed.md", "- changed\n")
        self.fragment("2.fixed.md", "- fixed\n")
        self.assertEqual(0, self.run_cli("--apply")[0])
        text = self.changelog.read_text(encoding="utf-8")
        self.assertLess(text.index("### Added"), text.index("### Changed"))
        self.assertLess(text.index("### Changed"), text.index("### Fixed"))
        self.assertLess(text.index("### Fixed"), text.index("### Security"))
        self.assertIn("### Changed\n\n- changed\n\n", text)
        self.assertIn("### Fixed\n\n- fixed\n\n", text)

    def test_unknown_heading_before_added_keeps_canonical_order(self):
        self.changelog.write_bytes(b"## [Unreleased]\n\n### Security\n\n- keep\n\n### Added\n\n- old\n\n## [1.0.0]\n")
        self.fragment("1.changed.md", "- changed\n")
        self.fragment("2.fixed.md", "- fixed\n")
        self.assertEqual(0, self.run_cli("--apply")[0])
        self.assertEqual(
            b"## [Unreleased]\n\n### Security\n\n- keep\n\n### Added\n\n- old\n\n"
            b"### Changed\n\n- changed\n\n### Fixed\n\n- fixed\n\n## [1.0.0]\n",
            self.changelog.read_bytes())

    def test_no_subsections_and_no_trailing_newline(self):
        self.changelog.write_bytes(b"## [Unreleased]")
        self.fragment("1.fixed.md", "- fixed\n")
        self.fragment("2.changed.md", "- changed\n")
        self.assertEqual(0, self.run_cli("--apply")[0])
        self.assertEqual(b"## [Unreleased]\n\n### Changed\n\n- changed\n\n### Fixed\n\n- fixed", self.changelog.read_bytes())

    def test_created_subsection_at_eof_has_trailing_blank_line(self):
        self.changelog.write_bytes(b"## [Unreleased]\n")
        self.fragment("1.added.md", b"- new\n")
        self.assertEqual(0, self.run_cli("--apply")[0])
        self.assertEqual(b"## [Unreleased]\n\n### Added\n\n- new\n\n", self.changelog.read_bytes())

    def test_newline_convention_and_fragment_newlines(self):
        for changelog_eol in (b"\n", b"\r\n"):
            for fragment_eol in (b"\n", b"\r\n", b"\r"):
                with self.subTest(changelog_eol=changelog_eol, fragment_eol=fragment_eol):
                    self.changelog.write_bytes(changelog_eol.join((b"## [Unreleased]", b"", b"### Added", b"", b"- old", b"", b"## [1.0.0]", b"")))
                    self.fragment("1.added.md", fragment_eol.join((b"- new", b"  line", b"")))
                    self.assertEqual(0, self.run_cli("--apply")[0])
                    expected = changelog_eol.join((b"## [Unreleased]", b"", b"### Added", b"", b"- old", b"- new", b"  line", b"", b"## [1.0.0]", b""))
                    self.assertEqual(expected, self.changelog.read_bytes())

    def test_cyrillic_round_trip(self):
        word = "\u041f\u0440\u0438\u0432\u0435\u0442"
        self.changelog.write_bytes(("## [Unreleased]\n\n### Added\n\n- " + word + "\n").encode("utf-8"))
        self.fragment("1.added.md", ("- " + word + "\n").encode("utf-8"))
        self.assertEqual(0, self.run_cli("--apply")[0])
        self.assertEqual("## [Unreleased]\n\n### Added\n\n- " + word + "\n- " + word + "\n", self.changelog.read_bytes().decode("utf-8"))

    def test_check_empty_and_missing_directory(self):
        before = self.changelog.read_bytes()
        self.assertEqual((0, "changelog: 0 fragment(s) OK\n", ""), self.run_cli("--check"))
        self.fragments.rmdir()
        self.assertEqual((0, "changelog: 0 fragment(s) OK\n", ""), self.run_cli("--check"))
        self.assertEqual(before, self.changelog.read_bytes())

    def test_check_skips_readme_dotfile_and_subdirectory(self):
        self.fragment("README.md", b"not a fragment")
        self.fragment(".markdownlint.yaml", b"not a fragment")
        (self.fragments / "nested").mkdir()
        self.assertEqual((0, "changelog: 0 fragment(s) OK\n", ""), self.run_cli("--check"))

    def test_check_rejects_symlink_named_like_a_fragment(self):
        target = self.root / "target-dir"
        target.mkdir()
        try:
            (self.fragments / "42.added.md").symlink_to(target, target_is_directory=True)
        except (OSError, NotImplementedError):
            self.skipTest("symbolic links are not available")
        code, _, err = self.run_cli("--check")
        self.assertEqual(1, code)
        self.assertIn("changelog: 42.added.md: symbolic links are not allowed", err)

    def test_check_rejects_invalid_fragments_without_writes(self):
        cases = {
            "bad_name": ("foo.md", b"- entry\n"),
            "extra_dot": ("foo.bar.md", b"- entry\n"),
            "wrong_extension": ("foo.added.txt", b"- entry\n"),
            "uppercase_extension": ("foo.added.MD", b"- entry\n"),
            "unknown_type": ("foo.other.md", b"- entry\n"),
            "non_ascii_id": ("\u212a.added.md", b"- entry\n"),
            "empty": ("foo.added.md", b" \n\t"),
            "heading": ("foo.added.md", b"- entry\n# heading\n"),
            "first_not_bullet": ("foo.added.md", b"words\n- entry\n"),
            "bad_indent": ("foo.added.md", b"- entry\n one space\n"),
            "bom": ("foo.added.md", b"\xef\xbb\xbf- entry\n"),
            "invalid_utf8": ("foo.added.md", b"- \xff\n"),
        }
        for label, (name, content) in cases.items():
            with self.subTest(label=label):
                path = self.fragment(name, content)
                before = self.changelog.read_bytes()
                self.assertEqual(1, self.run_cli("--check")[0])
                self.assertIn("changelog:", self.run_cli("--check")[2])
                self.assertEqual(before, self.changelog.read_bytes())
                self.assertEqual(content, path.read_bytes())
                path.unlink()

    def test_check_reports_multiple_errors_and_missing_unreleased(self):
        self.changelog.write_bytes(b"## [1.0.0]\n")
        self.fragment("bad.md", b"- entry\n")
        self.fragment("1.added.md", b"")
        result, out, err = self.run_cli("--check")
        self.assertEqual((1, ""), (result, out))
        self.assertEqual(3, err.count("changelog:"))
        self.assertIn("changelog: CHANGELOG.md:", err)

    def test_check_rejects_missing_or_bad_utf8_changelog(self):
        for data in (None, b"\xff", b"## [1.0.0]\n"):
            with self.subTest(data=data):
                if data is None:
                    self.changelog.unlink(missing_ok=True)
                else:
                    self.changelog.write_bytes(data)
                result, out, err = self.run_cli("--check")
                self.assertEqual((1, ""), (result, out))
                self.assertIn("changelog: CHANGELOG.md:", err)

    def test_apply_deletes_only_inserted_fragments_and_is_idempotent(self):
        inserted = self.fragment("1.Fixed.md", b"- fix\n")
        readme = self.fragment("README.md", b"keep")
        dotfile = self.fragment(".markdownlint.yaml", b"keep")
        self.assertEqual(0, self.run_cli("--apply")[0])
        after = self.changelog.read_bytes()
        self.assertFalse(inserted.exists())
        self.assertTrue(readme.exists())
        self.assertTrue(dotfile.exists())
        self.assertEqual((0, "", ""), self.run_cli("--apply"))
        self.assertEqual(after, self.changelog.read_bytes())

    def test_apply_reports_fragment_that_could_not_be_deleted(self):
        self.fragment("1.added.md", "- new\n")
        original_unlink = Path.unlink

        def failing_unlink(path, *args, **kwargs):
            if path.name == "1.added.md":
                raise OSError("locked")
            return original_unlink(path, *args, **kwargs)

        with mock.patch.object(Path, "unlink", failing_unlink):
            code, _, err = self.run_cli("--apply")
        self.assertEqual(1, code)
        self.assertIn("changelog: 1.added.md: inserted but not deleted", err)
        self.assertIn(b"- new", self.changelog.read_bytes())

    def test_apply_invalid_fragment_is_all_or_nothing(self):
        good = self.fragment("1.added.md", b"- good\n")
        bad = self.fragment("2.fixed.md", b"bad\n")
        before = self.changelog.read_bytes()
        result, out, err = self.run_cli("--apply")
        self.assertEqual((1, ""), (result, out))
        self.assertIn("changelog:", err)
        self.assertEqual(before, self.changelog.read_bytes())
        self.assertTrue(good.exists())
        self.assertTrue(bad.exists())

    def test_default_validation_and_mutually_exclusive_flags(self):
        self.fragment("bad.md", b"- entry\n")
        self.assertEqual(1, self.run_cli()[0])
        with contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as raised:
                self.run_cli("--check", "--apply")
        self.assertEqual(2, raised.exception.code)

    def test_diagnostics_escape_non_ascii_system_text(self):
        self.assertTrue(changelog_assemble.error("file.md", "\u043e\u0448\u0438\u0431\u043a\u0430").isascii())


if __name__ == "__main__":
    unittest.main()
