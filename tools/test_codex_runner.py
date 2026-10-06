"""Offline checks for the pilot Codex headless runner harness (ADR 0024 D10, slice AGP).

No real `codex` process and no network: the subprocess wrapper is exercised with a stub
executable built from `sys.executable`.
"""
import hashlib
import json
import os
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

from tools import codex_runner as cr

STUB = textwrap.dedent('''
    import json, pathlib, sys, time
    here = pathlib.Path(__file__).parent
    argv = sys.argv[1:]
    prompt = sys.stdin.buffer.read()
    text = prompt.decode('utf-8', 'replace')
    record = {'argv': argv, 'stdin_hex_sha': __import__('hashlib').sha256(prompt).hexdigest()}
    for line in text.splitlines():
        if line.startswith('LEDGER='):
            record['ledger_at_spawn'] = pathlib.Path(line[7:]).read_text(encoding='utf-8')
    (here / 'record.json').write_text(json.dumps(record), encoding='utf-8')
    out = argv[argv.index('-o') + 1]
    if 'MODE:sleep' in text:
        time.sleep(30)
    if 'MODE:fail' in text:
        sys.stderr.write('boom')
        sys.exit(3)
    if 'MODE:garbage' in text:
        print('not json at all')
        print('{"type": "turn.completed"')
        sys.exit(0)
    if 'MODE:write' in text:
        (pathlib.Path(argv[argv.index('-C') + 1]) / 'leak.txt').write_text('x', encoding='utf-8')
    if 'MODE:seed' in text:
        record['seed'] = (pathlib.Path(argv[argv.index('-C') + 1]) / 'control.txt').read_text(encoding='utf-8')
        (here / 'record.json').write_text(json.dumps(record), encoding='utf-8')
    if 'MODE:tamper' in text:
        (pathlib.Path(argv[argv.index('-C') + 1]) / 'control.txt').write_text('changed', encoding='utf-8')
    if 'MODE:empty' in text:
        sys.exit(0)
    if 'MODE:nonce' in text:
        print(json.dumps({'type': 'item.completed', 'item': {'type': 'agent_message', 'text': 'saw NONCE-abc123'}}))
    print(json.dumps({'type': 'thread.started', 'thread_id': 't1'}))
    print(json.dumps({'type': 'item.completed', 'item': {'type': 'command_execution', 'exit_code': 1, 'aggregated_output': 'x'}}))
    print(json.dumps({'type': 'turn.completed', 'usage': {'input_tokens': 10, 'cached_input_tokens': 4, 'output_tokens': 5}}))
    pathlib.Path(out).write_text('PONG', encoding='utf-8')
''')


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        self.harness = self.root / 'harness'
        self.harness.mkdir()
        self.stub = self.root / 'stub.py'
        self.stub.write_text(STUB, encoding='utf-8')
        self.repo = self.root / 'repo'
        self.repo.mkdir()
        self.ledger = cr.Ledger(self.harness / 'ledger.jsonl', total_cap=210)
        self.cfg = cr.Config(
            pilot_id='pilot-test',
            harness_root=self.harness,
            forbidden_roots=[self.repo],
            executable=[sys.executable, str(self.stub)],
            ancestor_markers=(),
        )

    def run_stub(self, prompt, **kw):
        kw.setdefault('stage', 'probe')
        kw.setdefault('stage_cap', 10)
        kw.setdefault('case_id', 'probe-1')
        kw.setdefault('timeout_s', 20)
        return cr.run_call(self.cfg, self.ledger, prompt, model='model-x', effort='low', **kw)

    def ledger_events(self):
        return [json.loads(x) for x in (self.harness / 'ledger.jsonl').read_text(encoding='ascii').splitlines()]

    def record(self):
        return json.loads((self.root / 'record.json').read_text(encoding='utf-8'))


class AsciiTests(unittest.TestCase):
    def test_ascii_text_is_accepted_and_non_ascii_rejected(self):
        self.assertTrue(cr.is_ascii_text('plain text'))
        self.assertFalse(cr.is_ascii_text('caf\u00e9'))

    def test_payload_encoding_is_ascii_and_round_trips_with_hash(self):
        sample = '\u041f\u0440\u0438\u0432\u0435\u0442 \u00e9 \u2192 ok'
        encoded, digest = cr.encode_ascii_payload(sample)
        self.assertTrue(encoded.isascii())
        self.assertEqual(hashlib.sha256(sample.encode('utf-8')).hexdigest(), digest)
        self.assertEqual(sample, json.loads(encoded))

    def test_round_trip_check_fails_when_decoded_text_differs(self):
        encoded, digest = cr.encode_ascii_payload('\u00e9')
        with self.assertRaises(ValueError):
            cr.check_round_trip(encoded, '0' * 64)
        cr.check_round_trip(encoded, digest)


class LedgerTests(Base):
    def test_reserve_counts_from_zero_and_marks_runner_and_data_class(self):
        first = self.ledger.reserve('pilot-test', 'probe', 10, 'probe-1', 'm', 'low')
        second = self.ledger.reserve('pilot-test', 'probe', 10, 'probe-2', 'm', 'low')
        self.assertEqual([1, 2], [first['seq'], second['seq']])
        events = self.ledger_events()
        self.assertEqual({'reserve'}, {e['event'] for e in events})
        for e in events:
            self.assertEqual('codex-headless', e['runner'])
            self.assertEqual('test_stand', e['data_class'])
            self.assertEqual('pilot-test', e['pilot_id'])

    def test_stage_cap_stops_before_total_cap(self):
        for n in range(3):
            self.ledger.reserve('p', 'probe', 3, f'c{n}', 'm', 'low')
        with self.assertRaises(cr.LedgerExhausted):
            self.ledger.reserve('p', 'probe', 3, 'c3', 'm', 'low')
        self.assertEqual(3, self.ledger.count())

    def test_total_cap_counts_every_stage(self):
        small = cr.Ledger(self.harness / 'small.jsonl', total_cap=2)
        small.reserve('p', 'probe', 10, 'a', 'm', 'low')
        small.reserve('p', 'smoke', 10, 'b', 'm', 'low')
        with self.assertRaises(cr.LedgerExhausted):
            small.reserve('p', 'smoke', 10, 'c', 'm', 'low')

    def test_stop_file_refuses_reservation(self):
        (self.harness / 'STOP').write_text('', encoding='ascii')
        ledger = cr.Ledger(self.harness / 'ledger.jsonl', total_cap=210, stop_file=self.harness / 'STOP')
        with self.assertRaises(cr.LedgerExhausted):
            ledger.reserve('p', 'probe', 10, 'a', 'm', 'low')
        self.assertEqual(0, ledger.count())

    def test_reservation_without_finish_is_reported_incomplete(self):
        entry = self.ledger.reserve('p', 'probe', 10, 'a', 'm', 'low')
        self.assertEqual([entry['call_id']], self.ledger.incomplete())
        self.ledger.finish(entry['call_id'], 'ok')
        self.assertEqual([], self.ledger.incomplete())

    def test_blank_case_or_wrong_data_class_is_refused(self):
        with self.assertRaises(ValueError):
            self.ledger.reserve('p', 'probe', 10, '', 'm', 'low')
        with self.assertRaises(ValueError):
            self.ledger.reserve('p', 'probe', 10, 'a', 'm', 'low', data_class='client')
        self.assertEqual(0, self.ledger.count())


class CommandTests(Base):
    def argv(self, **kw):
        return cr.build_argv(self.cfg, model='model-x', effort='low', workdir=self.harness / 'work' / 'w1',
                             out_path=self.harness / 'out' / 'o1.txt', **kw)

    def test_mandatory_flags_are_present_and_prompt_comes_from_stdin(self):
        argv = self.argv()
        tail = argv[len(self.cfg.executable):]
        self.assertEqual('exec', tail[0])
        for flag in ('--ignore-user-config', '--ignore-rules', '--ephemeral', '--skip-git-repo-check', '--json'):
            self.assertIn(flag, tail)
        self.assertEqual('read-only', tail[tail.index('-s') + 1])
        self.assertEqual('model-x', tail[tail.index('-m') + 1])
        self.assertEqual('model_reasoning_effort=low', tail[tail.index('-c') + 1])
        self.assertEqual('-', tail[-1])
        self.assertEqual(str(self.harness / 'work' / 'w1'), tail[tail.index('-C') + 1])

    def test_ephemeral_can_be_dropped_only_for_the_session_check(self):
        tail = self.argv(persist_session=True)[len(self.cfg.executable):]
        self.assertNotIn('--ephemeral', tail)
        cr.validate_argv(self.argv(persist_session=True), self.cfg, allow_session=True)
        with self.assertRaises(cr.ForbiddenFlag):
            cr.validate_argv(self.argv(persist_session=True), self.cfg)

    def test_forbidden_flags_are_refused(self):
        base = self.argv()
        bad = [
            ['-s', 'workspace-write'], ['--sandbox', 'danger-full-access'], ['--sandbox=workspace-write'],
            ['--dangerously-bypass-approvals-and-sandbox'], ['--dangerously-bypass-hook-trust'],
            ['--add-dir', str(self.root)], ['--worktree'], ['--enable', 'x'], ['--disable', 'x'], ['-p', 'prof'],
            ['--profile', 'prof'], ['--oss'], ['--local-provider', 'ollama'], ['--approve-for-me'],
            ['-i', 'a.png'], ['-c', 'sandbox_mode="danger-full-access"'], ['-c', 'mcp_servers.x.command="y"'],
            ['-c', 'model_reasoning_effort=high'],
        ]
        for extra in bad:
            argv = base[:-1] + extra + ['-']
            with self.subTest(extra=extra), self.assertRaises(cr.ForbiddenFlag):
                cr.validate_argv(argv, self.cfg)

    def test_missing_mandatory_flags_are_refused(self):
        base = self.argv()
        for flag in ('--ignore-user-config', '--ephemeral', '--ignore-rules', '--skip-git-repo-check', '--json'):
            argv = [a for a in base if a != flag]
            with self.subTest(flag=flag), self.assertRaises(cr.ForbiddenFlag):
                cr.validate_argv(argv, self.cfg)

    def test_non_read_only_sandbox_cannot_be_built(self):
        with self.assertRaises(cr.ForbiddenFlag):
            cr.build_argv(self.cfg, model='m', effort='low', workdir=self.harness / 'w', out_path=self.harness / 'o',
                          sandbox='workspace-write')

    def test_workdir_inside_forbidden_root_is_refused(self):
        inside = self.repo / 'sub'
        inside.mkdir()
        argv = cr.build_argv(self.cfg, model='m', effort='low', workdir=inside, out_path=self.harness / 'o')
        with self.assertRaises(cr.ForbiddenFlag):
            cr.validate_argv(argv, self.cfg)

    def test_output_file_inside_workdir_is_refused(self):
        w = self.harness / 'work' / 'w'
        argv = cr.build_argv(self.cfg, model='m', effort='low', workdir=w, out_path=w / 'o')
        with self.assertRaises(cr.ForbiddenFlag):
            cr.validate_argv(argv, self.cfg)

    def test_model_and_effort_must_be_plain_names(self):
        for model, effort in (('m x', 'low'), ('m', 'low;high'), ('', 'low'), ('m', '')):
            with self.subTest(model=model, effort=effort), self.assertRaises(cr.ForbiddenFlag):
                cr.build_argv(self.cfg, model=model, effort=effort, workdir=self.harness / 'w', out_path=self.harness / 'o')

    def test_output_schema_is_allowed_as_a_file_outside_workdir(self):
        schema = self.harness / 'schema.json'
        argv = self.argv(schema_path=schema)
        cr.validate_argv(argv, self.cfg)
        self.assertEqual(str(schema), argv[argv.index('--output-schema') + 1])


class EnvAndWorkdirTests(Base):
    def test_child_env_is_an_allowlist_and_drops_credentials(self):
        parent = {
            'PATH': '/bin', 'SystemRoot': 'C:/Windows', 'USERPROFILE': 'u', 'CODEX_HOME': 'h',
            'SSL_CERT_FILE': 'bundle.crt', 'OPENAI_API_KEY': 'dummy', 'OPENAI_BASE_URL': 'http://x', 'DASHSCOPE_API_KEY': 'dummy',
            'MODELSTUDIO_API_KEY': 'dummy', 'RANDOM_API_KEY': 'dummy', 'SOMETHING_ELSE': 'v',
        }
        env = cr.child_env(parent)
        self.assertEqual({'PATH', 'SystemRoot', 'USERPROFILE', 'CODEX_HOME', 'SSL_CERT_FILE'}, set(env))
        self.assertNotIn('dummy', json.dumps(env))

    def test_workdir_must_be_empty_and_outside_forbidden_roots(self):
        good = self.harness / 'work' / 'good'
        good.mkdir(parents=True)
        cr.check_workdir(good, self.cfg)
        (good / 'f.txt').write_text('x', encoding='ascii')
        with self.assertRaises(cr.ForbiddenFlag):
            cr.check_workdir(good, self.cfg)
        inside = self.repo / 'w'
        inside.mkdir()
        with self.assertRaises(cr.ForbiddenFlag):
            cr.check_workdir(inside, self.cfg)

    def test_workdir_with_instruction_or_git_marker_in_an_ancestor_is_refused(self):
        cfg = cr.Config('p', self.harness, [self.repo], [sys.executable], ancestor_markers=('.git', 'AGENTS.md'))
        parent = self.root / 'marked'
        (parent / 'child').mkdir(parents=True)
        (parent / 'AGENTS.md').write_text('x', encoding='ascii')
        with self.assertRaises(cr.ForbiddenFlag):
            cr.check_workdir(parent / 'child', cfg)

    def test_symlink_into_forbidden_root_is_refused(self):
        link = self.harness / 'link'
        try:
            os.symlink(self.repo, link, target_is_directory=True)
        except (OSError, NotImplementedError):
            self.skipTest('symlinks unavailable')
        with self.assertRaises(cr.ForbiddenFlag):
            cr.check_workdir(link, self.cfg)


class RunCallTests(Base):
    def test_reservation_is_on_disk_before_the_process_starts(self):
        result = self.run_stub(f'LEDGER={self.harness / "ledger.jsonl"}\nsay PONG')
        seen = [json.loads(x) for x in self.record()['ledger_at_spawn'].splitlines()]
        self.assertEqual(['reserve'], [e['event'] for e in seen])
        self.assertEqual('ok', result['status'])
        self.assertEqual(['reserve', 'finish'], [e['event'] for e in self.ledger_events()])

    def test_successful_call_reports_events_usage_and_output_hash_without_texts(self):
        result = self.run_stub('say PONG')
        self.assertEqual('ok', result['status'])
        self.assertEqual(0, result['exit_code'])
        self.assertEqual(1, result['events']['types']['turn.completed'])
        self.assertEqual(10, result['events']['usage']['input_tokens'])
        self.assertEqual(hashlib.sha256(b'PONG').hexdigest(), result['out_sha256'])
        self.assertNotIn('PONG', json.dumps(self.ledger_events()))
        self.assertTrue(list((self.harness / 'work').glob('*')))
        self.assertEqual([], [p for p in (self.harness / 'work').glob('*/*')])

    def test_prompt_is_sent_to_stdin_unchanged(self):
        self.run_stub('say PONG')
        self.assertEqual(hashlib.sha256(b'say PONG').hexdigest(), self.record()['stdin_hex_sha'])

    def test_non_ascii_prompt_is_refused_before_reserving(self):
        with self.assertRaises(ValueError):
            self.run_stub('caf\u00e9')
        self.assertEqual(0, self.ledger.count())

    def test_stage_cap_refuses_the_call_without_spawning(self):
        for n in range(2):
            self.run_stub('say PONG', stage_cap=2)
        (self.root / 'record.json').unlink()
        with self.assertRaises(cr.LedgerExhausted):
            self.run_stub('say PONG', stage_cap=2)
        self.assertFalse((self.root / 'record.json').exists())

    def test_failure_exit_code_is_a_failed_call_that_stays_counted(self):
        result = self.run_stub('MODE:fail')
        self.assertEqual('failed', result['status'])
        self.assertEqual(3, result['exit_code'])
        self.assertEqual(1, self.ledger.count())
        self.assertEqual([], self.ledger.incomplete())

    def test_timeout_kills_the_process_and_marks_the_call_timeout(self):
        result = self.run_stub('MODE:sleep', timeout_s=1)
        self.assertEqual('timeout', result['status'])
        self.assertEqual(1, self.ledger.count())
        self.assertEqual([], self.ledger.incomplete())

    def test_empty_output_file_is_an_incomplete_call(self):
        result = self.run_stub('MODE:empty')
        self.assertEqual('failed', result['status'])
        self.assertIn('no_output', result['problems'])

    def test_corrupt_event_lines_are_counted_not_fatal(self):
        result = self.run_stub('MODE:garbage')
        self.assertEqual(2, result['events']['bad_lines'])
        self.assertEqual('failed', result['status'])

    def test_needles_are_reported_as_booleans_and_never_stored(self):
        result = self.run_stub('MODE:nonce', needles={'canary': 'NONCE-abc123', 'other': 'ZZZ'})
        self.assertEqual({'canary': True, 'other': False}, result['needles'])
        self.assertNotIn('NONCE-abc123', json.dumps(self.ledger_events()))

    def test_command_exit_codes_are_collected_from_the_stream(self):
        result = self.run_stub('say PONG')
        self.assertEqual([1], result['events']['command_exit_codes'])

    def test_ledger_entries_carry_hashes_and_marks(self):
        self.run_stub('say PONG')
        reserve, finish = self.ledger_events()
        self.assertEqual(hashlib.sha256(b'say PONG').hexdigest(), reserve['prompt_sha256'])
        self.assertEqual('codex-headless', finish['runner'])
        self.assertEqual('test_stand', finish['data_class'])
        self.assertEqual(reserve['call_id'], finish['call_id'])

    def test_each_call_gets_a_fresh_workdir_and_no_workdir_is_reused(self):
        a = self.run_stub('say PONG')
        b = self.run_stub('say PONG')
        self.assertNotEqual(a['workdir'], b['workdir'])

    def test_files_created_in_the_workdir_are_a_violation(self):
        result = self.run_stub('MODE:write')
        self.assertEqual('violation', result['status'])
        self.assertIn('workdir_not_empty', result['problems'])
        self.assertEqual(['leak.txt'], cr.workdir_leftovers(result['workdir']))


class ProbeExceptionTests(Base):
    def test_seed_file_is_visible_to_the_process_and_is_not_a_violation(self):
        result = self.run_stub('MODE:seed', seed_files={'control.txt': b'CONTROL-1'})
        self.assertEqual('ok', result['status'])
        self.assertEqual('CONTROL-1', self.record()['seed'])
        self.assertEqual(['control.txt'], result['seeded'])

    def test_modified_or_extra_file_next_to_the_seed_is_a_violation(self):
        result = self.run_stub('MODE:tamper', seed_files={'control.txt': b'CONTROL-1'})
        self.assertEqual('violation', result['status'])

    def test_raw_utf8_prompt_needs_the_explicit_probe_switch_and_is_marked_in_the_ledger(self):
        sample = 'café Привет'
        with self.assertRaises(ValueError):
            self.run_stub(sample)
        result = self.run_stub(sample, raw_utf8_probe=True)
        self.assertEqual('ok', result['status'])
        self.assertEqual(hashlib.sha256(sample.encode('utf-8')).hexdigest(), self.record()['stdin_hex_sha'])
        reserve = self.ledger_events()[0]
        self.assertEqual('utf-8-raw-probe', reserve['stdin_encoding'])

    def test_ascii_prompts_are_marked_ascii_in_the_ledger(self):
        self.run_stub('say PONG')
        self.assertEqual('ascii', self.ledger_events()[0]['stdin_encoding'])

    def test_config_extra_fields_are_written_to_every_reservation(self):
        self.cfg.extra['prereg_sha256'] = 'a' * 64
        self.run_stub('say PONG')
        self.assertEqual('a' * 64, self.ledger_events()[0]['prereg_sha256'])


if __name__ == '__main__':
    unittest.main()
