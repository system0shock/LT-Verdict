"""Pilot harness around `codex exec` (ADR 0024 D10, plan slice AGP).

Experiment tooling, not product code. Hard limits enforced here:
read-only sandbox, a fresh empty working directory outside the repository and the product
data directory, ASCII-only stdin, a child environment built from an allowlist (no ModelStudio
or other credentials), and a ledger that reserves a call before the process is spawned.
Results and the ledger live in the harness directory outside git.
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path

RUNNER = 'codex-headless'
DATA_CLASS = 'test_stand'
PILOT_CAP = 210
EFFORT_KEY = 'model_reasoning_effort'
NAME_RE = re.compile(r'^[A-Za-z0-9._-]+$')
ENV_ALLOWLIST = (
    'PATH', 'PATHEXT', 'SystemRoot', 'SystemDrive', 'WINDIR', 'COMSPEC', 'USERPROFILE', 'HOME',
    'HOMEDRIVE', 'HOMEPATH', 'APPDATA', 'LOCALAPPDATA', 'CODEX_HOME', 'TEMP', 'TMP', 'TMPDIR',
    'SSL_CERT_FILE', 'SSL_CERT_DIR',
)
MANDATORY_FLAGS = ('--ignore-user-config', '--ignore-rules', '--skip-git-repo-check', '--json')
VALUE_FLAGS = ('-s', '-m', '-c', '-C', '-o', '--output-schema')
MAX_EVENT_BYTES = 8_000_000
RESERVE_FIELDS = {'event', 'seq', 'call_id', 'runner', 'data_class', 'pilot_id', 'stage', 'stage_cap', 'total_cap',
                  'case_id', 'model', 'effort', 'ts'}
FINISH_FIELDS = {'event', 'call_id', 'seq', 'runner', 'data_class', 'pilot_id', 'status'}


class ForbiddenFlag(Exception):
    """A command, path or environment rule of the pilot was violated."""


class LedgerExhausted(Exception):
    """The pilot, stage or STOP limit was reached; no call may be made."""


@dataclass
class Config:
    pilot_id: str
    harness_root: Path
    forbidden_roots: list
    executable: list
    ancestor_markers: tuple = ('.git', 'AGENTS.md', '.codex')
    extra: dict = field(default_factory=dict)


def is_ascii_text(text):
    return text.isascii()


def encode_ascii_payload(text):
    """Return (ASCII JSON string, SHA-256 of the UTF-8 original) for delivering non-ASCII data."""
    return json.dumps(text, ensure_ascii=True), hashlib.sha256(text.encode('utf-8')).hexdigest()


def check_round_trip(encoded, expected_sha256):
    if not encoded.isascii():
        raise ValueError('payload is not ASCII')
    decoded = json.loads(encoded)
    if hashlib.sha256(decoded.encode('utf-8')).hexdigest() != expected_sha256:
        raise ValueError('round-trip hash mismatch')
    return decoded


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


class Ledger:
    """Append-only JSONL counter of `codex exec` calls (unit: one call), starting from zero."""

    def __init__(self, path, total_cap=PILOT_CAP, stop_file=None):
        self.path = Path(path)
        self.total_cap = total_cap
        self.stop_file = Path(stop_file) if stop_file else None
        self.path.parent.mkdir(parents=True, exist_ok=True)

    def _events(self):
        if not self.path.exists():
            return []
        return [json.loads(x) for x in self.path.read_text(encoding='ascii').splitlines() if x.strip()]

    def _append(self, record):
        with open(self.path, 'a', encoding='ascii', newline='\n') as handle:
            handle.write(json.dumps(record, sort_keys=True) + '\n')
            handle.flush()
            os.fsync(handle.fileno())

    def _locked(self):
        lock = self.path.with_name(self.path.name + '.lock')
        deadline = time.time() + 10
        while True:
            try:
                fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
                os.close(fd)
                return lock
            except FileExistsError:
                if time.time() > deadline:
                    raise LedgerExhausted('ledger lock is held')
                time.sleep(0.05)

    def count(self):
        return sum(1 for e in self._events() if e['event'] == 'reserve')

    def incomplete(self):
        events = self._events()
        done = {e['call_id'] for e in events if e['event'] == 'finish'}
        return [e['call_id'] for e in events if e['event'] == 'reserve' and e['call_id'] not in done]

    def reserve(self, pilot_id, stage, stage_cap, case_id, model, effort, data_class=DATA_CLASS, call_id=None, **extra):
        if not case_id:
            raise ValueError('case_id is required')
        if data_class != DATA_CLASS:
            raise ValueError('only test_stand data may enter the pilot')
        if set(extra) & RESERVE_FIELDS:
            raise ValueError('extra fields may not use protected ledger names')
        lock = self._locked()
        try:
            if self.stop_file is not None and self.stop_file.exists():
                raise LedgerExhausted('STOP file present')
            reserved = [e for e in self._events() if e['event'] == 'reserve']
            if len(reserved) >= self.total_cap:
                raise LedgerExhausted('pilot cap reached')
            if sum(1 for e in reserved if e['stage'] == stage) >= stage_cap:
                raise LedgerExhausted('stage cap reached: ' + stage)
            seq = len(reserved) + 1
            record = {
                'event': 'reserve', 'seq': seq, 'call_id': call_id or f'c{seq:04d}-{uuid.uuid4().hex[:8]}',
                'runner': RUNNER, 'data_class': data_class, 'pilot_id': pilot_id, 'stage': stage,
                'stage_cap': stage_cap, 'total_cap': self.total_cap, 'case_id': case_id, 'model': model,
                'effort': effort, 'ts': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()), **extra,
            }
            self._append(record)
            return record
        finally:
            os.remove(lock)

    def finish(self, call_id, status, **facts):
        if set(facts) & FINISH_FIELDS:
            raise ValueError('facts may not use protected ledger names')
        lock = self._locked()
        try:
            reserve = next((e for e in self._events() if e['event'] == 'reserve' and e['call_id'] == call_id), None)
            if reserve is None:
                raise ValueError('unknown call_id')
            record = {
                'event': 'finish', 'call_id': call_id, 'seq': reserve['seq'], 'runner': RUNNER,
                'data_class': reserve['data_class'], 'pilot_id': reserve['pilot_id'], 'status': status, **facts,
            }
            self._append(record)
        finally:
            os.remove(lock)
        return record


def _inside(path, root):
    path, root = Path(path).resolve(), Path(root).resolve()
    return path == root or root in path.parents


def check_workdir(workdir, cfg):
    workdir = Path(workdir)
    if not workdir.is_dir():
        raise ForbiddenFlag('workdir does not exist')
    if any(workdir.iterdir()):
        raise ForbiddenFlag('workdir is not empty')
    _check_containment(workdir, cfg)
    for directory in [workdir.resolve(), *workdir.resolve().parents]:
        for marker in cfg.ancestor_markers:
            if (directory / marker).exists():
                raise ForbiddenFlag(f'{marker} found at {directory}')


def _check_containment(workdir, cfg):
    for root in cfg.forbidden_roots:
        if _inside(workdir, root) or _inside(root, workdir):
            raise ForbiddenFlag('workdir overlaps a forbidden root')


def workdir_leftovers(root):
    root = Path(root)
    if not root.exists():
        return []
    return sorted(p.relative_to(root).as_posix() for p in root.rglob('*') if p.is_file())


def child_env(parent):
    allowed = {name.lower() for name in ENV_ALLOWLIST}
    return {k: v for k, v in parent.items() if k.lower() in allowed}


def build_argv(cfg, *, model, effort, workdir, out_path, sandbox='read-only', schema_path=None, persist_session=False):
    if sandbox != 'read-only':
        raise ForbiddenFlag('only the read-only sandbox is allowed')
    if not NAME_RE.match(model or '') or not NAME_RE.match(effort or ''):
        raise ForbiddenFlag('model and effort must be plain names')
    argv = [*cfg.executable, 'exec', '--ignore-user-config', '--ignore-rules', '--skip-git-repo-check', '--json']
    if not persist_session:
        argv.append('--ephemeral')
    argv += ['-s', sandbox, '-m', model, '-c', f'{EFFORT_KEY}={effort}', '-C', str(workdir), '-o', str(out_path)]
    if schema_path is not None:
        argv += ['--output-schema', str(schema_path)]
    argv.append('-')
    return argv


def validate_argv(argv, cfg, allow_session=False):
    if list(argv[:len(cfg.executable)]) != list(cfg.executable):
        raise ForbiddenFlag('executable prefix does not match the configuration')
    tail = list(argv[len(cfg.executable):])
    if not tail or tail[0] != 'exec' or tail[-1] != '-':
        raise ForbiddenFlag('command must be `exec ... -`')
    seen, values = [], {}
    i = 1
    while i < len(tail) - 1:
        flag = tail[i]
        if flag in VALUE_FLAGS:
            if i + 1 >= len(tail) - 1:
                raise ForbiddenFlag('missing value for ' + flag)
            if flag in values:
                raise ForbiddenFlag('repeated flag ' + flag)
            values[flag] = tail[i + 1]
            i += 2
        elif flag in MANDATORY_FLAGS or flag == '--ephemeral':
            if flag in seen:
                raise ForbiddenFlag('repeated flag ' + flag)
            seen.append(flag)
            i += 1
        else:
            raise ForbiddenFlag('flag is not allowed: ' + flag)
    for flag in MANDATORY_FLAGS:
        if flag not in seen:
            raise ForbiddenFlag('missing mandatory flag ' + flag)
    if '--ephemeral' not in seen and not allow_session:
        raise ForbiddenFlag('missing mandatory flag --ephemeral')
    for flag in ('-s', '-m', '-c', '-C', '-o'):
        if flag not in values:
            raise ForbiddenFlag('missing ' + flag)
    if values['-s'] != 'read-only':
        raise ForbiddenFlag('sandbox must be read-only')
    key, _, effort = values['-c'].partition('=')
    if key != EFFORT_KEY or not NAME_RE.match(effort):
        raise ForbiddenFlag('only the reasoning effort may be configured')
    if not NAME_RE.match(values['-m']):
        raise ForbiddenFlag('model must be a plain name')
    _check_containment(values['-C'], cfg)
    handed = [values['-o']] + ([values['--output-schema']] if '--output-schema' in values else [])
    for path in handed:
        if _inside(path, values['-C']):
            raise ForbiddenFlag('files handed to codex must live outside the workdir')
        if not _inside(path, cfg.harness_root) or any(_inside(path, r) for r in cfg.forbidden_roots):
            raise ForbiddenFlag('files handed to codex must live under the harness root')


def summarize_events(data, needles=None, max_bytes=MAX_EVENT_BYTES):
    """Parse the JSONL stream in memory. Keeps counts, exit codes and usage; never stores text."""
    summary = {'types': {}, 'item_types': {}, 'usage': {}, 'command_exit_codes': [], 'bad_lines': 0,
               'truncated': len(data) > max_bytes}
    for raw in data[:max_bytes].decode('utf-8', 'replace').splitlines():
        if not raw.strip():
            continue
        try:
            event = json.loads(raw)
        except ValueError:
            summary['bad_lines'] += 1
            continue
        if not isinstance(event, dict):
            summary['bad_lines'] += 1
            continue
        kind = str(event.get('type'))
        summary['types'][kind] = summary['types'].get(kind, 0) + 1
        item = event.get('item')
        if isinstance(item, dict):
            item_kind = str(item.get('type'))
            summary['item_types'][item_kind] = summary['item_types'].get(item_kind, 0) + 1
            if item_kind == 'command_execution' and item.get('exit_code') is not None:
                summary['command_exit_codes'].append(item['exit_code'])
        usage = event.get('usage')
        if isinstance(usage, dict):
            for name, value in usage.items():
                if isinstance(value, (int, float)) and not isinstance(value, bool):
                    summary['usage'][name] = summary['usage'].get(name, 0) + value
    return summary


def _kill_tree(proc):
    if os.name == 'nt':
        subprocess.run(['taskkill', '/F', '/T', '/PID', str(proc.pid)], capture_output=True, check=False)
    else:
        import signal
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    proc.kill()


def run_call(cfg, ledger, prompt, *, model, effort, stage, stage_cap, case_id, timeout_s,
             needles=None, schema_path=None, persist_session=False, seed_files=None, raw_utf8_probe=False):
    """One reserved `codex exec` call. `seed_files` and `raw_utf8_probe` are probe-only exceptions
    (a positive-control file in the workdir; raw UTF-8 stdin to test the channel) and are marked in the ledger."""
    if not is_ascii_text(prompt) and not raw_utf8_probe:
        raise ValueError('prompt must be ASCII; deliver non-ASCII data with encode_ascii_payload')
    prompt_bytes = prompt.encode('utf-8')
    if any(not NAME_RE.match(name) for name in (needles or {})):
        raise ValueError('needle names must be plain names')
    root = Path(cfg.harness_root)
    call_id = f'c-{uuid.uuid4().hex[:12]}'
    workdir, out_path = root / 'work' / call_id, root / 'out' / f'{call_id}.txt'
    workdir.mkdir(parents=True)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    try:
        check_workdir(workdir, cfg)
        seeds = {name: bytes(data) for name, data in (seed_files or {}).items()}
        for name, data in seeds.items():
            if not NAME_RE.match(name) or name.startswith('.'):
                raise ForbiddenFlag('seed names must be plain file names')
            (workdir / name).write_bytes(data)
        argv = build_argv(cfg, model=model, effort=effort, workdir=workdir, out_path=out_path,
                          schema_path=schema_path, persist_session=persist_session)
        validate_argv(argv, cfg, allow_session=persist_session)
        entry = ledger.reserve(
            cfg.pilot_id, stage, stage_cap, case_id, model, effort, call_id=call_id,
            prompt_sha256=sha256_bytes(prompt_bytes), prompt_bytes=len(prompt_bytes),
            stdin_encoding='ascii' if prompt.isascii() else 'utf-8-raw-probe', **cfg.extra,
            argv_sha256=sha256_bytes(json.dumps(argv[len(cfg.executable):]).encode('ascii')),
            persist_session=persist_session, timeout_s=timeout_s, seed_files=sorted(seeds))
    except BaseException:
        try:
            workdir.rmdir()
        except OSError:
            pass
        raise
    started = time.time()
    status, exit_code, problems = 'failed', None, []
    kwargs = {'start_new_session': True} if os.name != 'nt' else {}
    stdout = stderr = b''
    try:
        proc = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                env=child_env(os.environ), **kwargs)
        try:
            stdout, stderr = proc.communicate(prompt_bytes, timeout=timeout_s)
            exit_code = proc.returncode
            status = 'ok' if exit_code == 0 else 'failed'
        except subprocess.TimeoutExpired:
            _kill_tree(proc)
            stdout, stderr = proc.communicate()
            exit_code, status = proc.returncode, 'timeout'
    except OSError as error:
        problems.append('spawn_failed:' + type(error).__name__)
    out_bytes = out_path.read_bytes() if out_path.exists() else b''
    events = summarize_events(stdout)
    if status == 'ok' and not out_bytes:
        status = 'failed'
        problems.append('no_output')
    found = {name: any(value.encode('utf-8') in blob for blob in (stdout, stderr, out_bytes))
             for name, value in (needles or {}).items()}
    leftovers = [f for f in workdir_leftovers(workdir) if f not in seeds]
    leftovers += [name for name, data in seeds.items()
                  if not (workdir / name).is_file() or (workdir / name).read_bytes() != data]
    if leftovers:
        status = 'violation'
        problems.append('workdir_not_empty')
    raw_dir = root / 'raw'
    raw_dir.mkdir(exist_ok=True)
    (raw_dir / f'{call_id}.stdout').write_bytes(stdout)
    (raw_dir / f'{call_id}.stderr').write_bytes(stderr)
    facts = {
        'exit_code': exit_code, 'duration_s': round(time.time() - started, 3), 'problems': problems,
        'stdout_sha256': sha256_bytes(stdout), 'stdout_bytes': len(stdout),
        'stderr_sha256': sha256_bytes(stderr), 'stderr_bytes': len(stderr),
        'out_sha256': sha256_bytes(out_bytes) if out_bytes else None, 'out_bytes': len(out_bytes),
        'events': events, 'needles': found,
    }
    ledger.finish(call_id, status, **facts)
    return {'call_id': call_id, 'seq': entry['seq'], 'status': status, 'workdir': str(workdir),
            'out_path': str(out_path), 'seeded': sorted(seeds), **facts}
