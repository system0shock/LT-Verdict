"""Shared resource-snapshot hash vectors: the Python oracle must equal the Kotlin core.

Each vector is the raw text of a resource-snapshot.v1 file. EXPECTED holds the
semantic SHA-256 computed by the Kotlin core (validateResourceSnapshot().semanticSha256)
through a temporary test that was not committed, on the exact texts below.
Non-ASCII characters are written as JSON escapes so that the file stays ASCII.
"""

import json
import sys
import unittest
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import stats_validation as oracle  # noqa: E402

LOAD = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"


def _snapshot(series, windows=None, rules=None, count=5):
    parts = ['"schema_version":"resource-snapshot.v1"', f'"load_input_sha256":"{LOAD}"',
             '"start_epoch_ms":1767225600000', '"step_ms":1000', f'"point_count":{count}',
             f'"series":[{series}]']
    if windows is not None:
        parts.append(f'"windows":[{windows}]')
    if rules is not None:
        parts.append(f'"rules":[{rules}]')
    return '{' + ','.join(parts) + '}'


def _series(sid, values, labels=None, metric='cpu_used'):
    label_text = '' if labels is None else f',"labels":{labels}'
    return (f'{{"id":"{sid}","metric":"{metric}","unit":"ratio","entity":"h","role":"system",'
            f'"aggregation":"interval_mean","values":[{values}]{label_text}}}')


def _rule(threshold):
    return ('{"id":"r1","series_id":"s1","unit":"ratio","operator":"gt","threshold":' + threshold +
            ',"min_consecutive_cells":1,"effect":"diagnostic"}')


def _window(wid, start=1767225601000, end=1767225603000):
    return f'{{"id":"{wid}","from_epoch_ms":{start},"to_epoch_ms":{end}}}'


VECTORS = {
    # -0.0, scientific notation, trailing zeros, exponent spelling of an integer, threshold spelling.
    'numbers_basic': _snapshot(_series('s1', '-0.0,5e-05,100.0,1E+3,0.10'), '', _rule('1E+2')),
    'zeros_and_signs': _snapshot(_series('s1', '-0,0.0,-0.000,0,-1.50'), '', _rule('-0.0')),
    'small_and_large': _snapshot(_series('s1', '1e-7,-5e-05,1e-12,1e16,1.5e17'), '', _rule('1E+18')),
    'null_values': _snapshot(_series('s1', 'null,0.5,1,2.0,null')),
    # 18+ significant digits: only exact when the file is read with parse_float=Decimal.
    'long_digits': _snapshot(_series('s1', '123456789.123456789,0.123456789012,12345678901234567.5,1,2')),
    # Cyrillic and CJK label keys, a value with quote and backslash.
    'non_ascii_label_keys': _snapshot(_series(
        's1', '1,2,3,4,5',
        '{"\\u0437\\u043e\\u043d\\u0430":"\\u0442\\u0435\\u0441\\u0442","zone":"a\\"b\\\\c","\\u65e5\\u672c":"x"}')),
    # U+1F600 (surrogate pair) versus U+FF5E: JVM orders by UTF-16 code units, emoji first.
    'astral_series_ids': _snapshot(_series('\\uff5e', '1,2,3,4,5') + ',' +
                                   _series('\\ud83d\\ude00', '1,2,3,4,5') + ',' + _series('a', '1,2,3,4,5')),
    'astral_label_keys': _snapshot(_series('s1', '1,2,3,4,5', '{"\\uff5e":"1","\\ud83d\\ude00":"2","z":"3"}')),
    # Label value with U+2028, U+00A0 and a slash: raw UTF-8, only quote and backslash are escaped.
    'label_value_specials': _snapshot(_series('s1', '1,2,3,4,5', '{"k":"a\\u2028b\\u00a0c/d"}')),
    # Windows sort by (from, to, id); rules keep the input order.
    'windows_and_rule_order': _snapshot(
        _series('s1', '1,2,3,4,5'),
        _window('w2', 1767225603000, 1767225604000) + ',' + _window('w1', 1767225601000, 1767225602000),
        _rule('0.5').replace('"r1"', '"r2"') + ',' + _rule('0.7')),
    # windows and rules are optional in the contract.
    'no_windows_no_rules': _snapshot(_series('s1', '1,2,3,4,5')),
    'no_rules': _snapshot(_series('s1', '1,2,3,4,5'), _window('w1')),
    'no_windows': _snapshot(_series('s1', '1,2,3,4,5'), None, _rule('0.5')),
    'provenance_ignored': _snapshot(_series('s1', '1,2,3,4,5'))[:-1] +
                          ',"provenance":{"source_kind":"fixture","query_semantics":"q","clock_alignment":"declared_aligned"}}',
}

FLOAT_UNSAFE = {'long_digits'}

EXPECTED = {
    'astral_label_keys': 'e5bd7881be75c892a0f356a73c94b9fcb29e65f3a255e49ddc8c848889d64687',
    'astral_series_ids': '71a404c0e738b271f768576715632269df4bd6d7697e0b10eb4704c8f1aa358e',
    'label_value_specials': 'dd4d8b321ef4e4a07f29a34d1089966cabeec76cce2a48bd4b3adaf68c327436',
    'long_digits': '5703b1c2d5e86ed1404cc88ed2aaef65769443bf46c4b2026354412e3f2cec6a',
    'no_rules': 'ac6d0359483757871ec29fe0946f0f8351f6fc1b81c5da6f32f505654201dfbd',
    'no_windows': 'b16f4de7bce9463f1901bd36bfef757934629a261c775f322020f50f02ca833c',
    'no_windows_no_rules': '6306e3505974d66d757d25c145780d94987b55b6861e94a231350ed391b77284',
    'non_ascii_label_keys': '267d480f8ba2cda37cf7e34e96b8d1c1bc9b8049466558d18b8bc65161644503',
    'null_values': '9420227c18b67d629caf833ce7be544ed6a9c02cf5f1a590e8e564104c149c61',
    'numbers_basic': '7502594fad41440fbf30f6f18b17dfd9fc4c9b7d38320ec272ebfa6063dceb8d',
    'provenance_ignored': '6306e3505974d66d757d25c145780d94987b55b6861e94a231350ed391b77284',
    'small_and_large': '3cff4b927568d930c601dec3778d2910862388c885676ede590de333c470b306',
    'windows_and_rule_order': '529d866a5abf8cda803c02e14a30243d85c1b16f69c68f9ad2530dffde115e9a',
    'zeros_and_signs': 'ae0bd8a3ea931fa2fc6e1285f0f501fccb81d6ea03810c1ab14a2e0b87b669ce',
}


class SnapshotHashVectorsTest(unittest.TestCase):
    def test_every_vector_has_a_kotlin_hash(self):
        self.assertEqual(set(VECTORS), set(EXPECTED))

    def test_matches_kotlin_when_read_as_decimal(self):
        for name, text in VECTORS.items():
            with self.subTest(name):
                snapshot = json.loads(text, parse_float=Decimal)
                self.assertEqual(oracle.snapshot_hash(snapshot), EXPECTED[name])

    def test_nonfinite_numbers_are_rejected_and_bool_is_not_a_number(self):
        for value in (float('nan'), float('inf'), Decimal('NaN')):
            with self.assertRaises(ValueError):
                oracle._canonical([value])
        self.assertEqual(oracle._canonical([True, False, None]), '[true,false,null]')

    def test_matches_kotlin_when_read_as_float(self):
        for name, text in VECTORS.items():
            if name in FLOAT_UNSAFE:
                continue
            with self.subTest(name):
                self.assertEqual(oracle.snapshot_hash(json.loads(text)), EXPECTED[name])


if __name__ == '__main__':
    unittest.main()
