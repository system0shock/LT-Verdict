import unittest
from copy import deepcopy
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch
import zipfile
import usefulness_validation as study


class ReportScoringTests(unittest.TestCase):
    def test_manifest_binds_source_gate_and_truth_hashes(self):
        with TemporaryDirectory() as directory,patch.object(study.generator,'configurations',return_value=[]):
            root = Path(directory)
            payloads = {'source.diff':b'diff','inventory.json':b'[]',
                        'applicability-report.json':b'{"status":"PASS"}',
                        'applicability-freeze.json':b'{}','parity/manifest.json':b'{}',
                        'prior-actual.jsonl':b'prior'}
            for name,raw in payloads.items():
                path = root/name
                path.parent.mkdir(exist_ok=True)
                path.write_bytes(raw)
            with zipfile.ZipFile(root/'source.zip','w') as archive:
                archive.writestr('toy.py',b'source')
            metadata = {'debug':False,'source_files':{'toy.py':study.wire._sha(b'source')},
                        'applicability_actual_path':str(root/'prior-actual.jsonl'),
                        'applicability_actual_sha256':study.wire._sha(b'prior')}
            for path,key in [('source.zip','source_zip_sha256'),('source.diff','source_diff_sha256'),
                             ('inventory.json','inventory_sha256'),('applicability-report.json','applicability_report_sha256'),
                             ('applicability-freeze.json','applicability_freeze_sha256'),('parity/manifest.json','parity_manifest_sha256')]:
                metadata[key] = study.wire._sha((root/path).read_bytes())
            # Input/truth bytes are bound by the same externally retained manifest digest.
            manifest = {'freeze_context':metadata,'cases':[{'expected':{'sha256':'original-truth'}}]}
            raw = study.wire._json_bytes(manifest)
            (root/'manifest.json').write_bytes(raw)
            digest = study.wire._sha(raw)
            (root/'freeze.json').write_bytes(study.wire._json_bytes(dict(metadata,manifest_sha256=digest)))
            study.verify_freeze(root,digest)
            for name in ['source.zip','applicability-report.json','prior-actual.jsonl']:
                path = root/name
                original = path.read_bytes()
                path.write_bytes(b'swapped')
                with self.subTest(name=name),self.assertRaises(ValueError):
                    study.verify_freeze(root,digest)
                path.write_bytes(original)
            manifest['cases'][0]['expected']['sha256'] = 'swapped-truth'
            (root/'manifest.json').write_bytes(study.wire._json_bytes(manifest))
            with self.assertRaisesRegex(ValueError,'manifest'):
                study.verify_freeze(root,digest)

    def test_empty_malformed_and_nonfinite_outputs_cannot_count_as_null_success(self):
        config = {'config':{'kind':'correlation','pairs':1}}
        for output in [{},None,{'resource_summaries':{}},{'value':float('inf')}]:
            with self.subTest(output=output),self.assertRaises((ValueError,TypeError)):
                study.validate_output(config,output)

    def test_comparison_requires_unique_metrics_and_finite_values(self):
        member = {'resource_summaries':{'evaluation':{'resource':{}}},'correlation_pairs':{},
                  'anomaly_checks':{},'findings':[]}
        rows = [{'metric':metric,'status':'CANDIDATE','baseline':'100','current':'140',
                 'delta':'40','delta_percent':'40','reason':None,'percent_reason':None}
                for metric in ['response_time_p50_ms','response_time_p95_ms','response_time_p99_ms',
                               'throughput_rps','error_rate_ratio','resource_median','resource_q95']]
        output = {'baseline':member,'current':member,'comparison':{'comparability':'USER_CONFIRMED',
                  'window_comparison':{'status':'CANDIDATE','metrics':rows}}}
        config = {'config':{'kind':'comparison'}}
        study.validate_output(config,output)
        for value in ['NaN','Infinity',float('inf'),'not a number']:
            broken = deepcopy(output)
            broken['comparison']['window_comparison']['metrics'][0]['delta'] = value
            with self.assertRaises((ValueError,ArithmeticError)):
                study.validate_output(config,broken)
        broken = deepcopy(output)
        broken['comparison']['window_comparison']['metrics'][1] = rows[0]
        with self.assertRaisesRegex(ValueError,'duplicate'):
            study.validate_output(config,broken)
        truth = {'kind':'positive','family':'P06'}
        self.assertFalse(study.measure(truth,broken)['detected'])

    def test_group_error_is_incomplete_and_not_removed_from_planned_count(self):
        record = {'unexpected_report':False,'detected':False,'headlines':0,'descriptive_rows':0,'unevaluable_rows':0}
        result = study.aggregate([record]*999,False,errors=1)
        self.assertEqual(('INCOMPLETE',1000,999,1,0),
                         tuple(result[key] for key in ['status','planned','completed','errors','missing']))

    def test_parity_rejects_missing_and_mismatched_actual_artifacts(self):
        # Toy metadata is not generated acceptance input or product execution.
        configs = [{'id':'toy','seed':1000}]
        output = {'resource_summaries':{},'correlation_pairs':{},'anomaly_checks':{},'findings':[]}
        with TemporaryDirectory() as directory,patch.object(study.generator,'configurations',return_value=configs):
            core,real = Path(directory)/'core.jsonl',Path(directory)/'real.jsonl'
            core.write_bytes(study.wire._json_bytes({'id':'toy','output':output}))
            real.write_bytes(study.wire._json_bytes({'id':'toy','output':dict(output,persisted_equal=True)}))
            self.assertEqual('PASS',study.parity(directory,core,real)['status'])
            changed = dict(output,findings=[{'type':'changed'}],persisted_equal=True)
            real.write_bytes(study.wire._json_bytes({'id':'toy','output':changed}))
            self.assertEqual('FAIL',study.parity(directory,core,real)['status'])
            real.write_bytes(b'')
            with self.assertRaisesRegex(ValueError,'84'):
                study.parity(directory,core,real)

    def test_wilson_and_report_level_noise_are_literal(self):
        low, high = study.wilson(0,1000)
        self.assertAlmostEqual(0,low)
        self.assertAlmostEqual(.0038267585,high,places=9)
        output = {'findings':[{'type':'correlation_candidate','pair_id':'p'},
                              {'type':'correlation_candidate','pair_id':'q'}],
                  'correlation_pairs':{'w':{'p':{'status':'CANDIDATE'},'q':{'status':'CANDIDATE'}}}}
        result = study.measure({'kind':'null','family':'N01'},output)
        self.assertEqual(2,result['headlines'])
        self.assertTrue(result['unexpected_report'])

    def test_positive_episode_requires_overlap_not_merely_rule_name(self):
        truth = {'kind':'positive','family':'P04','injected_rule':'a',
                 'sign':'increase','interval':{'from_epoch_ms':100,'to_epoch_ms':120}}
        output = {'findings':[{'type':'anomaly_episode','rule_id':'a','direction':'increase',
                              'from_epoch_ms':110,'to_epoch_ms':130}]}
        measured = study.measure(truth,output)
        self.assertFalse(measured['detected'])
        self.assertAlmostEqual(1/3,measured['iou'])
        self.assertEqual(10,measured['start_error_ms'])
        output['findings'][0]['from_epoch_ms'] = 100
        self.assertTrue(study.measure(truth,output)['detected'])

    def test_positive_lag_checks_signed_direction_and_unrelated_findings(self):
        truth = {'kind':'positive','family':'P03','injected_pair':'p','sign':'negative','lag_cells':-3}
        output = {'findings':[{'type':'correlation_candidate','pair_id':'p'},
                              {'type':'correlation_candidate','pair_id':'q'}],
                  'correlation_pairs':{'evaluation':{'p':{'best_lag_ms':3000,'best_lag_rho':'-.9'}}}}
        result = study.measure(truth,output)
        self.assertFalse(result['detected'])
        self.assertEqual(6,result['lag_error_cells'])
        self.assertEqual(1,result['unrelated_headlines'])
        output['correlation_pairs']['evaluation']['p']['best_lag_ms'] = -3000
        self.assertTrue(study.measure(truth,output)['detected'])


if __name__ == '__main__':
    unittest.main()
