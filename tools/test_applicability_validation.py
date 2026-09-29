"""Independent literals for the system-trace acceptance oracle."""
import unittest
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path
from fractions import Fraction

import applicability_validation as study


def toy_worker(value):
    return value * 2


class TraceOracleTest(unittest.TestCase):
    def test_process_pool_consumes_more_tasks_than_buffer_in_order(self):
        try:
            with ProcessPoolExecutor(max_workers=2) as pool:
                result = list(pool.map(toy_worker, range(5), buffersize=2))
        except PermissionError as error:
            self.skipTest(f'process workers unavailable: {error}')
        self.assertEqual([0, 2, 4, 6, 8], result)

    def test_prepare_rejects_worker_counts_outside_one_to_four(self):
        for workers in (0, 5):
            with self.assertRaisesRegex(ValueError, 'workers'):
                study.prepare(Path('unused') / str(workers), workers=workers)

    def test_load_binding_rejects_newline_normalization_before_analysis(self):
        original = 'a,b\r\n1,2\r\n'
        snapshot = {'load_input_sha256':study.oracle._sha(original.encode())}
        with self.assertRaisesRegex(ValueError,'load bytes'):
            study.run_input({},snapshot,original.replace('\r\n','\n'))

    def test_coarse_duration_is_the_smallest_representable_interval_above_three_seconds(self):
        trace = {'parameters':{'start_epoch_ms':0,'stages':[{'duration_us':600000000}]},
                 'requests':[{'start_us':0,'end_us':100000,'status':'completed'},
                             {'start_us':599000000,'end_us':599100000,'status':'completed'}]}
        snapshot = {'schema_version':'resource-snapshot.v1','load_input_sha256':study.oracle._sha(b'load'),
                    'start_epoch_ms':0,'step_ms':10000,'point_count':60,'series':[],
                    'provenance':{'clock_alignment':'declared_aligned'}}
        result = study.run_input(trace,snapshot,'load')
        self.assertEqual({10000},{a['min_duration_ms'] for a in result['diagnostics']['anomalies']})

    def test_comparison_covers_resource_rows_identity_and_unconfirmed_status(self):
        series = {'id':'cpu','metric':'utilization','unit':'ratio','entity':'vm',
                  'role':'resource','aggregation':'interval_mean','values':[.2,.4]}
        def run(values):
            return {'resources':{'windows':[{'id':'w','from_epoch_ms':0,'to_epoch_ms':2000}],
                    'start_epoch_ms':0,'step_ms':1000,'series':[series | {'values':values}]}}
        trace = {'parameters':{'start_epoch_ms':0},'requests':[
            {'start_us':0,'end_us':100000,'status':'completed'},
            {'start_us':1000000,'end_us':1100000,'status':'completed'}]}
        result = study.comparison_expected(trace,trace,run([.2,.4]),run([.4,.8]),False)
        path = '/comparison/window_comparison/'
        self.assertEqual('w',result['exact'][path+'baseline_window'])
        self.assertEqual(2000,result['exact'][path+'current_duration_ms'])
        self.assertEqual('utilization_median',result['exact'][path+'metrics/5/metric'])
        self.assertEqual('cpu',result['exact'][path+'metrics/6/resource_series_id'])
        self.assertEqual('0.300000',result['numeric'][path+'metrics/5/delta'])
        self.assertEqual('100.000000',result['numeric'][path+'metrics/6/delta_percent'])
        self.assertEqual('DESCRIPTIVE',result['exact'][path+'status'])
        self.assertEqual('CONDITIONS_UNCONFIRMED',result['exact'][path+'metrics/5/reason'])
        self.assertEqual('NO_MATERIAL_CHANGE',result['exact'][path+'metrics/4/status'])
        self.assertEqual('ZERO_BASELINE',result['exact'][path+'metrics/4/percent_reason'])

    def test_comparison_rounds_half_up_not_half_even(self):
        self.assertEqual('0.000001', study.rounded(Fraction(1,2000000)))
        self.assertEqual('-0.000001', study.rounded(Fraction(-1,2000000)))

    def test_workload_windows_clip_clock_domain_without_fitting_shift(self):
        trace = {'parameters':{'start_epoch_ms':0,'stages':[{'duration_us':600000000}]},
                 'requests':[{'start_us':0,'end_us':100000,'status':'completed'},
                             {'start_us':599000000,'end_us':599100000,'status':'completed'}]}
        snapshot = {'start_epoch_ms':5000,'step_ms':1000,'point_count':600}
        self.assertEqual([{'id':'reference','from_epoch_ms':5000,'to_epoch_ms':125000},
                          {'id':'workload-01','from_epoch_ms':125000,'to_epoch_ms':599000}],
                         study.measurement_windows(trace,snapshot))

    def test_request_percentile_uses_union_and_hdr_equivalence(self):
        # HdrHistogram Java 2.2.2 uses ceil, unlike older/C-port rank rounding.
        self.assertEqual(2, study.latency_percentile([1]*29+[2]*2,95))
        self.assertEqual(100, study.latency_percentile([100]*400 + [1000]*20, 95))
        self.assertEqual(4095, study.latency_percentile([4094], 95))
        self.assertIsNone(study.latency_percentile([], 95))

    def test_request_window_uses_start_not_completion_and_timeout_is_error(self):
        requests = [
            {'start_us':0, 'end_us':100000, 'status':'completed'},
            {'start_us':900000, 'end_us':2100000, 'status':'timed_out'},
            {'start_us':1000000, 'end_us':1100000, 'status':'completed'},
            {'start_us':None, 'end_us':None, 'status':'generator_wait'},
        ]
        facts = study.load_facts(requests, 0, 1000000)
        self.assertEqual(2, facts['sample_count'])
        self.assertEqual(1, facts['error_count'])
        self.assertEqual(1200, facts['p95'])
        self.assertEqual(2, facts['throughput_rps'])
        self.assertEqual(.5, facts['error_rate_ratio'])

    def test_episode_thresholds_gaps_and_direction_are_literal(self):
        ref = [99,100,101]*10
        self.assertEqual([(2,5,'increase'),(5,8,'decrease')],
                         study.episode_intervals(ref, [160,None,160,160,160,40,40,40], 20, 3))


if __name__ == '__main__':
    unittest.main()
