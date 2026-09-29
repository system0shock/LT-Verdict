"""Boundary checks for the offline onboarding preparation wrapper."""
import hashlib
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from tools import onboard_test as onboard


class OnboardingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / 'repo'
        self.root.mkdir()
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True)
        self.out = Path(self.tmp.name) / 'output'
        (self.root / 'test.jmx').write_text('<jmeterTestPlan/>', encoding='utf-8')

    def test_view_excludes_secrets_and_ignored_files_without_changing_repository(self):
        (self.root / '.gitignore').write_text('ignored.jmx\n', encoding='utf-8')
        (self.root / 'ignored.jmx').write_text('<secret/>', encoding='utf-8')
        (self.root / 'private.jmx').write_text('password="example-password"', encoding='utf-8')
        report = onboard.prepare(self.root, self.out, ['test.jmx', 'ignored.jmx', 'private.jmx'], {})
        self.assertEqual(['test.jmx'], [f['path'] for f in report['files']])
        self.assertNotIn('example-password', json.dumps(report))
        self.assertFalse((self.root / 'ltv-run.yaml').exists())

    def test_apply_requires_exact_confirmation_hash_and_unchanged_inputs(self):
        onboard.prepare(self.root, self.out, ['test.jmx'], {'scenario': 'smoke'})
        proposal = self.out / 'proposal.json'
        digest = hashlib.sha256(proposal.read_bytes()).hexdigest()
        with self.assertRaises(ValueError):
            onboard.apply(self.root, proposal, digest, False)
        with self.assertRaises(ValueError):
            onboard.apply(self.root, proposal, '0' * 64, True)
        (self.root / 'test.jmx').write_text('<changed/>', encoding='utf-8')
        with self.assertRaises(ValueError):
            onboard.apply(self.root, proposal, digest, True)

    def test_apply_adds_only_external_manifest_and_rejects_replay(self):
        before = (self.root / 'test.jmx').read_bytes()
        onboard.prepare(self.root, self.out, ['test.jmx'], {'scenario': 'smoke'})
        proposal = self.out / 'proposal.json'
        digest = hashlib.sha256(proposal.read_bytes()).hexdigest()
        onboard.apply(self.root, proposal, digest, True)
        self.assertEqual(before, (self.root / 'test.jmx').read_bytes())
        self.assertEqual('smoke', json.loads((self.root / 'ltv-run.yaml').read_text())['scenario'])
        with self.assertRaises(ValueError):
            onboard.apply(self.root, proposal, digest, True)

    def test_jmeter_password_and_extra_ignore_are_excluded(self):
        (self.root / 'auth.jmx').write_text('<stringProp name="password">example-password</stringProp>', encoding='utf-8')
        (self.root / '.ltverdictignore').write_text('local.jmx\n', encoding='utf-8')
        (self.root / 'local.jmx').write_text('<local/>', encoding='utf-8')
        report = onboard.prepare(self.root, self.out, ['test.jmx', 'auth.jmx', 'local.jmx'], {})
        self.assertEqual(['test.jmx'], [f['path'] for f in report['files']])

    def test_rejects_traversal_and_output_inside_repository(self):
        with self.assertRaises(ValueError):
            onboard.prepare(self.root, self.root / 'output', ['test.jmx'], {})
        with self.assertRaises(ValueError):
            onboard.prepare(self.root, self.out, ['../outside.jmx'], {})


if __name__ == '__main__':
    unittest.main()
