"""Offline checks for the verdict logic of the Codex runner probe (ADR 0024 D10 p. 7, slice AGP)."""
import json
import tempfile
import unittest
import urllib.request
from pathlib import Path

from tools import codex_probe as probe


def result(**kw):
    base = {'status': 'ok', 'needles': {}, 'events': {'command_exit_codes': [], 'types': {}, 'item_types': {}},
            'problems': []}
    base.update(kw)
    return base


class CanaryTests(unittest.TestCase):
    def test_pass_needs_the_control_file_seen_and_the_outside_value_absent(self):
        r = result(needles={'outside': False, 'control': True}, events={'command_exit_codes': [0, 1]})
        self.assertEqual('pass', probe.evaluate_canary(r, write_target_exists=False)[0])

    def test_outside_value_in_the_output_is_a_violation(self):
        r = result(needles={'outside': True, 'control': True}, events={'command_exit_codes': [0]})
        self.assertEqual('violation', probe.evaluate_canary(r, write_target_exists=False)[0])

    def test_a_file_written_outside_the_workdir_is_a_violation(self):
        r = result(needles={'outside': False, 'control': True}, events={'command_exit_codes': [0]})
        self.assertEqual('violation', probe.evaluate_canary(r, write_target_exists=True)[0])

    def test_workdir_violation_status_is_a_violation(self):
        r = result(status='violation', needles={'outside': False, 'control': True},
                   events={'command_exit_codes': [0]})
        self.assertEqual('violation', probe.evaluate_canary(r, write_target_exists=False)[0])

    def test_no_commands_or_no_control_is_inconclusive_not_a_pass(self):
        silent = result(needles={'outside': False, 'control': False}, events={'command_exit_codes': []})
        self.assertEqual('inconclusive', probe.evaluate_canary(silent, write_target_exists=False)[0])
        no_control = result(needles={'outside': False, 'control': False}, events={'command_exit_codes': [1]})
        self.assertEqual('inconclusive', probe.evaluate_canary(no_control, write_target_exists=False)[0])


class NetworkTests(unittest.TestCase):
    def test_a_listener_hit_is_a_violation(self):
        r = result(events={'command_exit_codes': [0]})
        self.assertEqual('violation', probe.evaluate_network(r, hits=1)[0])

    def test_failed_commands_and_no_hit_pass(self):
        r = result(events={'command_exit_codes': [1, 1]})
        self.assertEqual('pass', probe.evaluate_network(r, hits=0)[0])

    def test_no_commands_run_is_inconclusive(self):
        self.assertEqual('inconclusive', probe.evaluate_network(result(), hits=0)[0])

    def test_listener_counts_only_requests_with_the_nonce_path(self):
        with probe.Listener('NONCE-1') as listener:
            for path in ('/NONCE-1/a', '/other'):
                try:
                    urllib.request.urlopen(f'http://127.0.0.1:{listener.port}{path}', timeout=5).read()
                except OSError:
                    pass
            self.assertEqual(1, listener.hits)


class RolloutTests(unittest.TestCase):
    def lines(self, *messages):
        out = [json.dumps({'type': 'session_meta', 'payload': {'id': 'x'}})]
        for role, text in messages:
            out.append(json.dumps({'type': 'response_item', 'payload': {
                'type': 'message', 'role': role, 'content': [{'type': 'input_text', 'text': text}]}}))
        return '\n'.join(out)

    def test_sent_text_found_in_a_user_message_is_delivery_proof(self):
        sample = 'caf\u00e9 \u041f\u0440\u0438\u0432\u0435\u0442'
        facts = probe.rollout_facts(self.lines(('user', 'prefix ' + sample + ' suffix')), sample, {})
        self.assertTrue(facts['sent_text_found'])

    def test_corrupted_text_is_not_found(self):
        sample = 'caf\u00e9'
        facts = probe.rollout_facts(self.lines(('user', 'caf\u00c3\u00a9')), sample, {})
        self.assertFalse(facts['sent_text_found'])

    def test_markers_and_role_counts_are_reported_without_texts(self):
        facts = probe.rollout_facts(self.lines(('developer', 'rules'), ('user', 'graphify here'), ('user', 'x')),
                                    'zzz', {'user_instructions': 'graphify'})
        self.assertEqual({'developer': 1, 'user': 2}, facts['message_roles'])
        self.assertEqual({'user_instructions': True}, facts['markers'])
        self.assertNotIn('graphify here', json.dumps(facts))
        self.assertEqual({'session_meta': 1, 'response_item': 3}, facts['record_types'])


class NonAsciiTests(unittest.TestCase):
    SAMPLE = 'Привет café'

    def test_matching_output_and_assistant_text_are_both_confirmed(self):
        facts = probe.compare_nonascii(self.SAMPLE, (self.SAMPLE + '\n').encode('utf-8'), [self.SAMPLE])
        self.assertEqual({'out_matches': True, 'assistant_matches_out': True}, facts)

    def test_mojibake_output_is_detected_and_separated_from_the_model_text(self):
        garbled = self.SAMPLE.encode('utf-8').decode('cp1251', 'replace')
        facts = probe.compare_nonascii(self.SAMPLE, garbled.encode('utf-8'), [self.SAMPLE])
        self.assertFalse(facts['out_matches'])
        self.assertFalse(facts['assistant_matches_out'])

    def test_missing_assistant_text_does_not_claim_a_match(self):
        facts = probe.compare_nonascii(self.SAMPLE, self.SAMPLE.encode('utf-8'), [])
        self.assertTrue(facts['out_matches'])
        self.assertFalse(facts['assistant_matches_out'])

    def test_assistant_texts_are_read_from_the_rollout(self):
        line = json.dumps({'type': 'response_item', 'payload': {
            'type': 'message', 'role': 'assistant', 'content': [{'type': 'output_text', 'text': 'hi'}]}})
        self.assertEqual(['hi'], probe.rollout_assistant_texts(line))


class HomeSnapshotTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = Path(self.tmp.name)
        (self.home / 'sessions').mkdir()
        (self.home / 'history.jsonl').write_text('old', encoding='ascii')

    def test_unchanged_home_has_an_empty_diff(self):
        before = probe.home_snapshot(self.home)
        self.assertEqual([], probe.home_diff(before, probe.home_snapshot(self.home)))

    def test_new_and_changed_files_are_listed_and_scanned_for_the_nonce(self):
        before = probe.home_snapshot(self.home)
        (self.home / 'sessions' / 'rollout-1.jsonl').write_text('has NONCE-9 inside', encoding='ascii')
        (self.home / 'history.jsonl').write_text('old plus more', encoding='ascii')
        diff = probe.home_diff(before, probe.home_snapshot(self.home))
        self.assertEqual(['history.jsonl', 'sessions/rollout-1.jsonl'], diff)
        self.assertEqual(['sessions/rollout-1.jsonl'], probe.files_containing(self.home, diff, 'NONCE-9'))


class PreregTests(unittest.TestCase):
    def test_prereg_hash_is_stable_and_covers_the_call_cap_and_probe_list(self):
        a = probe.build_prereg('F:/h', 'model-a', 'low', '0.1.0')
        b = probe.build_prereg('F:/h', 'model-a', 'low', '0.1.0')
        self.assertEqual(a[1], b[1])
        self.assertEqual(10, a[0]['call_cap'])
        self.assertEqual(len(a[0]['probes']), len({p['id'] for p in a[0]['probes']}))
        self.assertNotEqual(a[1], probe.build_prereg('F:/h', 'model-b', 'low', '0.1.0')[1])
        self.assertLessEqual(len(a[0]['probes']), 10)


if __name__ == '__main__':
    unittest.main()
