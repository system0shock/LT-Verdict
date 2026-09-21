"""Offline, manifest-only preparation for lt-verdict-onboard-test. No model calls."""
import argparse
import hashlib
import json
import os
import re
import subprocess
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

MAX_FILE_BYTES = 1024 * 1024
MAX_FILES = 64
DENIED = {'.git', '.env', '.ssh', '.aws', '.azure', '.qwen', '.codex', 'credentials', 'node_modules'}
ALLOWED_NAMES = {'Jenkinsfile', 'pom.xml', 'build.gradle', 'build.gradle.kts', 'package.json', 'simulation.log'}
ALLOWED_SUFFIXES = {'.jmx', '.jtl', '.scala', '.java', '.kt', '.yaml', '.yml'}
SECRET = re.compile(r'(?i)(?:password|passwd|secret|token|api[_-]?key)\s*[=:]\s*["\x27]?[^\s"\x27<>]{4,}|BEGIN [A-Z ]*PRIVATE KEY|AKIA[0-9A-Z]{16}|https?://[^/\s]+:[^/\s]+@')
META_KEYS = {'run_id', 'scenario', 'stand', 'dataset', 'artifact_path'}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def git(root, *args, allowed=(0,)):
    completed = subprocess.run(['git', '-c', 'core.fsmonitor=false', '-c', 'core.hooksPath=', '-C', str(root), *args], capture_output=True, check=False)
    if completed.returncode not in allowed:
        raise ValueError('Git repository inspection failed')
    return completed.stdout.decode('utf-8').strip()


def revision(root):
    return git(root, 'rev-parse', '--verify', 'HEAD', allowed=(0, 128)) or 'UNBORN'


def safe_path(root, relative):
    if not isinstance(relative, str) or not re.fullmatch(r'[A-Za-z0-9_./-]+', relative):
        raise ValueError('Only portable relative paths are supported')
    parts = relative.split('/')
    if any(part in ('', '.', '..') for part in parts):
        raise ValueError('Invalid path')
    path = root
    for part in parts:
        path = path / part
        if path.is_symlink() or (hasattr(path, 'is_junction') and path.is_junction()):
            raise ValueError('Linked paths are not allowed')
    if not path.resolve().is_relative_to(root):
        raise ValueError('Path escapes repository')
    return path


def secret_suspected(text, relative):
    if SECRET.search(text):
        return True
    if relative.endswith('.jmx'):
        if '<!DOCTYPE' in text or '<!ENTITY' in text:
            return True
        try:
            for element in ET.fromstring(text).iter():
                name = element.attrib.get('name', '').lower()
                if any(word in name for word in ('password', 'passwd', 'token', 'secret', 'authorization', 'api_key')) and (element.text or '').strip():
                    return True
        except ET.ParseError:
            return True
    return False


def write_json(path, value):
    with path.open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
        stream.flush()
        os.fsync(stream.fileno())


def prepare(repository, output, paths, metadata):
    root = Path(repository).resolve(strict=True)
    output = Path(output).resolve()
    if output.is_relative_to(root) or root.is_relative_to(output):
        raise ValueError('Output must be outside repository and its ancestors')
    if git(root, 'rev-parse', '--show-toplevel').replace('\\', '/').casefold() != root.as_posix().casefold():
        raise ValueError('Use repository root')
    if not 1 <= len(paths) <= MAX_FILES or len(set(paths)) != len(paths):
        raise ValueError('Choose 1..64 unique confirmed paths')
    if not isinstance(metadata, dict) or set(metadata) - META_KEYS:
        raise ValueError('Unknown metadata fields')
    if any(not isinstance(v, str) or not 1 <= len(v) <= 256 or SECRET.search(v) or any(ord(c) < 32 for c in v) for v in metadata.values()):
        raise ValueError('Invalid or secret-like metadata')
    if 'artifact_path' in metadata:
        safe_path(root, metadata['artifact_path'])
    files, excluded, contents = [], [], {}
    for relative in paths:
        path = safe_path(root, relative)
        if any(part.lower() in DENIED or part.lower().startswith('.env') for part in path.relative_to(root).parts):
            excluded.append({'path': relative, 'reason': 'DENIED'})
            continue
        if path.name not in ALLOWED_NAMES and path.suffix not in ALLOWED_SUFFIXES:
            excluded.append({'path': relative, 'reason': 'NOT_ALLOWLISTED'})
            continue
        ignored = git(root, 'check-ignore', '--no-index', '--', relative, allowed=(0, 1))
        extra_ignore = root / '.ltverdictignore'
        if extra_ignore.exists():
            safe_path(root, '.ltverdictignore')
            ignored = ignored or git(root, '-c', 'core.excludesFile=' + str(extra_ignore), 'check-ignore', '--no-index', '--', relative, allowed=(0, 1))
        if ignored:
            excluded.append({'path': relative, 'reason': 'IGNORED'})
            continue
        if not path.is_file() or path.stat().st_size > MAX_FILE_BYTES:
            raise ValueError('Input missing or too large')
        with path.open('rb') as stream:
            data = stream.read(MAX_FILE_BYTES + 1)
        if len(data) > MAX_FILE_BYTES:
            raise ValueError('Input too large')
        sha = digest(data)
        text = data.decode('utf-8-sig')
        if secret_suspected(text, relative):
            excluded.append({'path': relative, 'sha256': sha, 'reason': 'SECRET_SUSPECTED'})
            continue
        files.append({'path': relative, 'sha256': sha})
        contents[relative] = text
    if not files:
        raise ValueError('No safe input files')
    report = {'schema_version': 'onboarding-preparation.v1', 'base_revision': revision(root), 'files': files, 'excluded': excluded,
              'detected': {'jmeter': any(p.endswith(('.jmx', '.jtl')) for p in contents),
                           'gatling': any('gatling' in v.lower() or p.endswith('simulation.log') for p, v in contents.items()),
                           'jenkins': any(Path(p).name == 'Jenkinsfile' for p in contents)},
              'compatibility': {
                  'L0': {'status': 'REVIEW_REQUIRED' if any(p.endswith(('.jtl', 'simulation.log')) for p in contents) else 'UNKNOWN',
                         'missing_effect': 'No verified load artifact means no canonical load analysis.'},
                  'L1': {'status': 'REVIEW_REQUIRED' if any(Path(p).name == 'Jenkinsfile' for p in contents) else 'UNKNOWN',
                         'missing_effect': 'Job tracking and automatic artifact archive are not established.'},
                  'L2': {'status': 'PARTIAL' if metadata else 'UNKNOWN',
                         'missing_effect': 'Run comparability and planned stages are not established.'},
                  'L3': {'status': 'UNKNOWN', 'missing_effect': 'Optional markers and comparability tags are not verified.'}},
              'limitations': ['Static preparation only; artifact availability and L0-L3 must be reviewed.',
                              'No repository code executed. No model invoked. No test-code edits supported.']}
    output.mkdir(parents=True, exist_ok=False)
    view = output / 'view'
    view.mkdir()
    for relative, text in contents.items():
        target = view / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open('x', encoding='utf-8') as stream:
            stream.write(text)
        target.chmod(0o444)
    write_json(output / 'audit.json', report)
    manifest = {'schema_version': 'ltv-onboarding-metadata.v1', **metadata}
    proposal = {'schema_version': 'onboarding-proposal.v1', 'base_revision': report['base_revision'], 'files': files,
                'target': 'ltv-run.yaml', 'content': manifest}
    write_json(output / 'proposal.json', proposal)
    return report


def apply(repository, proposal_path, expected_hash, confirmed):
    root = Path(repository).resolve(strict=True)
    source = Path(proposal_path).resolve(strict=True)
    if source.is_relative_to(root) or source.stat().st_size > MAX_FILE_BYTES:
        raise ValueError('Proposal must be bounded and outside repository')
    with source.open('rb') as stream:
        data = stream.read(MAX_FILE_BYTES + 1)
    if len(data) > MAX_FILE_BYTES or not confirmed or digest(data) != expected_hash:
        raise ValueError('Exact proposal hash and explicit confirmation required')
    proposal = json.loads(data)
    if set(proposal) != {'schema_version', 'base_revision', 'files', 'target', 'content'} or proposal['schema_version'] != 'onboarding-proposal.v1':
        raise ValueError('Invalid proposal')
    if proposal['target'] != 'ltv-run.yaml' or proposal['base_revision'] != revision(root):
        raise ValueError('Target or base revision mismatch')
    content = proposal['content']
    if not isinstance(content, dict) or content.get('schema_version') != 'ltv-onboarding-metadata.v1' or set(content) - META_KEYS - {'schema_version'}:
        raise ValueError('Invalid manifest')
    for key, value in content.items():
        if not isinstance(value, str) or not 1 <= len(value) <= 256 or SECRET.search(value) or any(ord(c) < 32 for c in value):
            raise ValueError('Invalid manifest value')
    if 'artifact_path' in content:
        safe_path(root, content['artifact_path'])
    files = proposal['files']
    if not isinstance(files, list) or not 1 <= len(files) <= MAX_FILES:
        raise ValueError('Invalid input bindings')
    for item in files:
        if not isinstance(item, dict) or set(item) != {'path', 'sha256'}:
            raise ValueError('Invalid input binding')
        path = safe_path(root, item['path'])
        if not path.is_file() or path.stat().st_size > MAX_FILE_BYTES:
            raise ValueError('Input changed')
        with path.open('rb') as stream:
            if digest(stream.read(MAX_FILE_BYTES + 1)) != item['sha256']:
                raise ValueError('Input changed')
    target = safe_path(root, 'ltv-run.yaml')
    if target.exists():
        raise ValueError('Existing manifest is never overwritten')
    # Publish a complete new file without ever replacing an existing manifest.
    staged = root / ('.ltv-onboard-' + uuid.uuid4().hex + '.tmp')
    try:
        write_json(staged, content)
        os.link(staged, target)
    finally:
        staged.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    prep = sub.add_parser('prepare')
    prep.add_argument('--repo', type=Path, required=True)
    prep.add_argument('--output', type=Path, required=True)
    prep.add_argument('--path', action='append', required=True)
    for key in sorted(META_KEYS):
        prep.add_argument('--' + key.replace('_', '-'))
    use = sub.add_parser('apply')
    use.add_argument('--repo', type=Path, required=True)
    use.add_argument('--proposal', type=Path, required=True)
    use.add_argument('--sha256', required=True)
    use.add_argument('--confirm', action='store_true')
    args = parser.parse_args()
    try:
        if args.command == 'prepare':
            metadata = {k: getattr(args, k) for k in META_KEYS if getattr(args, k) is not None}
            prepare(args.repo, args.output, args.path, metadata)
        else:
            apply(args.repo, args.proposal, args.sha256, args.confirm)
    except (ValueError, OSError, UnicodeError) as error:
        parser.exit(2, f'Onboarding rejected: {type(error).__name__}\n')


if __name__ == '__main__':
    main()
