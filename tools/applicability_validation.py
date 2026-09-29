"""System acceptance: trace-derived expectations, frozen before product execution."""
from decimal import Decimal, localcontext, ROUND_HALF_UP
from fractions import Fraction
from math import ceil
from statistics import median
from pathlib import Path
from copy import deepcopy
import argparse
from concurrent.futures import ProcessPoolExecutor
import gzip
import json
import platform
import shutil
import subprocess
import zipfile

import stats_validation as oracle


def rounded(value):
    value = Fraction(value)
    with localcontext() as context:
        context.prec = 80
        return str((Decimal(value.numerator)/Decimal(value.denominator)).quantize(
            Decimal('.000001'), rounding=ROUND_HALF_UP))


def latency_percentile(values, percentile):
    if not values:
        return None
    value = sorted(values)[max(0, ceil(len(values)*percentile/100)-1)]
    # HDR, lowest discernible 1 ms / 3 significant digits: upper bucket boundary.
    width_bits = max(0, value.bit_length()-11)
    return value | ((1 << width_bits)-1)


def load_facts(requests, start_us, end_us):
    selected = [r for r in requests if r['start_us'] is not None
                and start_us <= (r['start_us']//1000)*1000 < end_us
                and r['status'] in {'completed','timed_out','rejected'}]
    latencies = [(r['end_us']-r['start_us']+999)//1000 for r in selected]
    errors = sum(r['status'] != 'completed' for r in selected)
    return {'sample_count':len(selected), 'error_count':errors,
            'throughput_rps':Fraction(len(selected)*1000000,end_us-start_us),
            'error_rate_ratio':Fraction(errors,len(selected)) if selected else None,
            **{f'p{p}':latency_percentile(latencies,p) for p in [50,95,99]}}


def episode_intervals(reference, values, delta, duration_cells, z_threshold=3.5):
    reference = [Fraction(str(x)) for x in reference if x is not None]
    if len(reference) < 30:
        return []
    center = median(reference)
    mad = median(abs(x-center) for x in reference)
    states = []
    for value in values:
        change = None if value is None else Fraction(str(value))-center
        passes = change is not None and change != 0 and abs(change) >= Fraction(str(delta))
        passes = passes and (mad == 0 or Fraction('0.6745')*abs(change) >= Fraction(str(z_threshold))*mad)
        states.append(('increase' if change > 0 else 'decrease') if passes else None)
    episodes, start = [], 0
    for end in range(1, len(states)+1):
        if end == len(states) or states[end] != states[start]:
            if states[start] is not None and end-start >= duration_cells:
                episodes.append((start,end,states[start]))
            start = end
    return episodes


def measurement_windows(trace, snapshot, temporal=False, warmup=False):
    """Workload boundaries plus a fixed reference; no output-driven segmentation."""
    epoch = trace['parameters'].get('start_epoch_ms', 1704067200000)
    start, step = snapshot['start_epoch_ms'], snapshot['step_ms']
    issued = [r for r in trace['requests'] if r['start_us'] is not None and r['end_us'] is not None
              and r['status'] in {'completed','timed_out','rejected'}]
    run_start = epoch + min(r['start_us']//1000 for r in issued)
    run_end = epoch + max(r['start_us']//1000+(r['end_us']-r['start_us']+999)//1000 for r in issued)
    first = max(0, (run_start-start+step-1)//step, 60000//step if warmup else 0)
    last = min(snapshot['point_count'], (run_end-start)//step)
    ref_end = (30000 if temporal else 120000)//step
    offsets = [0]
    for stage in trace['parameters']['stages']:
        offsets.append(offsets[-1] + stage['duration_us']//1000)
    windows = [{'id':'reference', 'from_epoch_ms':start+first*step, 'to_epoch_ms':start+ref_end*step}]
    for i, (left,right) in enumerate(zip(offsets,offsets[1:])):
        left, right = max(left//step, ref_end), min(right//step,last)
        if left < right:
            windows.append({'id':f'workload-{i+1:02d}', 'from_epoch_ms':start+left*step, 'to_epoch_ms':start+right*step})
    if first >= ref_end or len(windows)<2:
        raise ValueError('INVALID_FIXTURE: insufficient measurement domain')
    return windows


def run_input(trace, snapshot, load_jtl, temporal=False, warmup=False):
    """Identical topology-based rules for original and intervention members."""
    if oracle._sha(load_jtl.encode('utf-8')) != snapshot['load_input_sha256']:
        raise ValueError('INVALID_FIXTURE: load bytes do not match snapshot binding')
    snapshot['windows'] = measurement_windows(trace,snapshot,temporal,warmup)
    # An unavailable optional sensor is omitted, not forged as observed zeros.
    snapshot['series'] = [s for s in snapshot['series'] if s['id'] != 'runtime-heap' or trace['parameters'].get('heap_bytes') is not None]
    for series in snapshot['series']:
        if series['unit'] == 'rps':
            series['unit'] = 'requests/s'
    snapshot['rules'] = [{'id':name, 'series_id':series, 'unit':'ratio', 'operator':'gt',
                          'threshold':.9,'min_consecutive_cells':3,'effect':'sla'}
                         for name,series in [('compute-limit','system-cpu-work'),('database-limit','system-db-work')]]
    windows = [w['id'] for w in snapshot['windows'] if w['id']!='reference']
    pair_series = ['system-cpu-work','system-db-work','admission-queue','cpu-queue','db-queue',
                   'runtime-gc-pause','cpu-service-quota','downstream-wait','generator-threads']
    pairs = [{'id':f'association-{i+1:02d}','resource_series_id':series,'load_metric':'response_time_p95_ms',
              'window_ids':windows,'expected_sign':'either','max_lag_ms':10000,
              'min_abs_effect':.3,'min_resource_delta':.1,'min_load_delta':20,
              'controls':[{'meaning':'target_rps','series_id':'target-request-rate'}],
              'topology_basis':'generator, admission, compute, database, runtime and dependency',
              'clock_alignment':snapshot['provenance']['clock_alignment']} for i,series in enumerate(pair_series)]
    anomalies = []
    minimum_duration = ceil(3000/snapshot['step_ms'])*snapshot['step_ms']
    for window in windows:
        for series, delta in [('cpu-queue',1),('db-queue',1),('admission-queue',1),('runtime-gc-pause',.05)]:
            anomalies.append({'id':f'{window}-{series}', 'signal':{'series_id':series},
                              'reference_window_id':'reference','window_id':window,'direction':'either',
                              'min_abs_delta':delta,'min_duration_ms':minimum_duration,'z_threshold':3.5})
        anomalies.append({'id':f'{window}-response', 'signal':{'load_metric':'response_time_p95_ms'},
                          'reference_window_id':'reference','window_id':window,'direction':'either',
                          'min_abs_delta':20,'min_duration_ms':minimum_duration,'z_threshold':3.5})
    return {'load_jtl':load_jtl,'resources':snapshot,
            'diagnostics':{'schema_version':'correlation-plan.v1','resource_snapshot_sha256':oracle.snapshot_hash(snapshot),
                           'pairs':pairs,'anomalies':anomalies},
            'policy':{'schema_version':'policy.v1','policy_id':'acceptance-latency',
                      'rules':[{'id':'latency','metric':'response_time_p95_ms','operator':'lte',
                                'threshold':200,'scope':{'kind':'overall'}}]}}


def expected_run(trace, run, prefix=''):
    """Numerical facts from trace and declared telemetry; never read product output."""
    exact = {prefix+'/persisted_equal':True}
    numeric = {}
    epoch = trace['parameters'].get('start_epoch_ms',1704067200000)
    snapshot = run['resources']
    step = snapshot['step_ms']
    for window in snapshot['windows']:
        path = prefix+'/load_summaries/'+window['id']+'/'
        exact.update({path+key:window[key] for key in ['from_epoch_ms','to_epoch_ms']})
        exact[path+'window_id'] = window['id']
        exact[path+'resource_bindings'] = [
            {'series_id':s['id'], **{key:s[key] for key in ['metric','unit','entity','role','aggregation']},
             'labels':s.get('labels',{})} for s in sorted(snapshot['series'],key=lambda s:s['id'])]
        facts = load_facts(trace['requests'], (window['from_epoch_ms']-epoch)*1000, (window['to_epoch_ms']-epoch)*1000)
        for key in ['sample_count','error_count']:
            exact[path+key] = facts[key]
        for key in ['p50','p95','p99']:
            exact[path+'latency_ms/'+key] = facts[key]
        exact[path+'throughput_rps/numerator'] = facts['sample_count']*1000
        exact[path+'throughput_rps/denominator'] = window['to_epoch_ms']-window['from_epoch_ms']
        if facts['sample_count']:
            exact[path+'error_rate_ratio/numerator'] = facts['error_count']
            exact[path+'error_rate_ratio/denominator'] = facts['sample_count']
        else:
            exact[path+'error_rate_ratio'] = None
        left = (window['from_epoch_ms']-snapshot['start_epoch_ms'])//step
        right = (window['to_epoch_ms']-snapshot['start_epoch_ms'])//step
        for series in snapshot['series']:
            values = [Fraction(str(v)) for v in series['values'][left:right] if v is not None]
            path = prefix+'/resource_summaries/'+window['id']+'/'+series['id']+'/'
            exact.update({path+key:series[key] for key in ['metric','unit','entity','role','aggregation']})
            exact[path+'series_id'] = series['id']
            exact[path+'window_id'] = window['id']
            exact[path+'observed_cells'] = len(values)
            exact[path+'missing_cells'] = right-left-len(values)
            for key,q in [('median',Fraction(1,2)),('q95',Fraction(19,20))]:
                v = oracle.quantile(values,q)
                if v is None:
                    exact[path+'statistics/'+key] = None
                else:
                    numeric[path+'statistics/'+key] = oracle._number(v)
        business = 'NO_VERDICT' if facts['p95'] is None else 'FAIL' if facts['p95'] > 200 else 'PASS'
        statuses = []
        for rule in snapshot['rules']:
            values = next(s['values'][left:right] for s in snapshot['series'] if s['id']==rule['series_id'])
            if any(v is None for v in values):
                statuses.append('NO_VERDICT')
            else:
                streak, failed = 0, False
                for value in values:
                    streak = streak+1 if value > rule['threshold'] else 0
                    failed |= streak >= rule['min_consecutive_cells']
                statuses.append('FAIL' if failed else 'PASS')
        resource = next((s for s in ['NO_VERDICT','FAIL'] if s in statuses), 'PASS')
        joint = next((s for s in ['NO_VERDICT','FAIL'] if s in [business,resource]), 'PASS')
        p = prefix+'/window_policies/'+window['id']+'/'
        exact.update({p+'business_verdict':business,p+'resource_verdict':resource,p+'verdict':joint})
    verdicts = [v for k,v in exact.items() if k.startswith(prefix+'/window_policies/') and k.endswith('/verdict')]
    exact[prefix+'/result/policy_verdict'] = next((s for s in ['NO_VERDICT','FAIL'] if s in verdicts),'PASS')
    load_cells = {}
    for window in snapshot['windows']:
        left = (window['from_epoch_ms']-epoch)*1000
        right = (window['to_epoch_ms']-epoch)*1000
        # Membership indexed once; late completions remain attributed to request starts.
        buckets = [[] for _ in range((right-left)//(step*1000))]
        for request in trace['requests']:
            if request['start_us'] is not None and left <= (request['start_us']//1000)*1000 < right:
                if request['status'] in {'completed','timed_out','rejected'}:
                    buckets[((request['start_us']//1000)*1000-left)//(step*1000)].append(
                        (request['end_us']-request['start_us']+999)//1000)
        load_cells[window['id']] = [latency_percentile(b,95) if len(b)>=20 else None for b in buckets]
    windows = {w['id']:w for w in snapshot['windows']}
    series = {s['id']:s['values'] for s in snapshot['series']}
    total = 0
    for rule in run['diagnostics']['anomalies']:
        ref = windows[rule['reference_window_id']]
        evaluation = windows[rule['window_id']]
        def values(window):
            if 'load_metric' in rule['signal']:
                return load_cells[window['id']]
            a = (window['from_epoch_ms']-snapshot['start_epoch_ms'])//step
            b = (window['to_epoch_ms']-snapshot['start_epoch_ms'])//step
            return series[rule['signal']['series_id']][a:b]
        reference = values(ref)
        evaluation_values = values(evaluation)
        all_episodes = episode_intervals(reference,evaluation_values,rule['min_abs_delta'],1)
        duration = ceil(rule['min_duration_ms']/step)
        episodes = [e for e in all_episodes if e[1]-e[0]>=duration]
        total += len(episodes)
        path = prefix+'/anomaly_checks/'+rule['id']+'/'
        exact[path+'episodes_reported'] = len(episodes)
        observed_reference = [Fraction(str(x)) for x in reference if x is not None]
        observed = sum(x is not None for x in evaluation_values)
        reasons = []
        if len(observed_reference)<len(reference):
            reasons.append('REFERENCE_GAPS')
        if observed<len(evaluation_values):
            reasons.append('EVALUATION_GAPS')
        center = mad = None
        if len(observed_reference)<30:
            reasons.append('INSUFFICIENT_REFERENCE_CELLS')
        else:
            center = median(observed_reference)
            mad = median(abs(x-center) for x in observed_reference)
            if mad == 0:
                reasons.append('ZERO_MAD')
            if observed == 0:
                reasons.append('NO_EVALUATION_OBSERVATIONS')
        exact.update({path+'status':'INSUFFICIENT_DATA' if len(observed_reference)<30 or observed==0
                      else 'CANDIDATE' if episodes else 'NO_MATERIAL_CHANGE',
                      path+'reference_observed_cells':len(observed_reference),
                      path+'reference_expected_cells':len(reference),path+'observed_cells':observed,
                      path+'expected_cells':len(evaluation_values),path+'reasons':reasons,
                      path+'suppressed_short_episodes':len(all_episodes)-len(episodes),
                      path+'reference_window_id':ref['id'],path+'window_id':evaluation['id']})
        for key,value in [('reference_median',center),('reference_mad',mad)]:
            if value is None:
                exact[path+key] = None
            else:
                numeric[path+key] = oracle._number(value)
        for i,(a,b,direction) in enumerate(episodes):
            p = prefix+'/anomaly_episodes/'+rule['id']+f'/{i}/'
            exact.update({p+'from_epoch_ms':evaluation['from_epoch_ms']+a*step,
                          p+'to_epoch_ms':evaluation['from_epoch_ms']+b*step,p+'direction':direction})
    if total>1000:
        raise ValueError('PREDECLARED_CAPABILITY_GAP: more than 1000 diagnostic episodes')
    # Temporal challenge: fixed anchors and every lag, not a selected best match.
    if trace['parameters']['scenario']=='TEMPORAL':
        w = snapshot['windows'][-1]
        a = (w['from_epoch_ms']-snapshot['start_epoch_ms'])//step
        b = (w['to_epoch_ms']-snapshot['start_epoch_ms'])//step
        x,y = series['system-cpu-work'][a:b],load_cells[w['id']]
        path = prefix+'/correlation_pairs/'+w['id']+'/association-01/'
        if step==1000:
            profile = oracle.rank_correlation(x,y,max_lag=10)['profile']
            for i,point in enumerate(profile):
                exact[path+f'lag_profile/{i}/lag_ms'] = point['lag_cells']*step
                numeric[path+f'lag_profile/{i}/rho'] = str(point['coefficient'])
            best = min(profile,key=lambda p:(-abs(p['coefficient']),abs(p['lag_cells']),p['lag_cells']))
            exact[path+'best_lag_ms'] = best['lag_cells']*step
            exact[path+'status'] = 'CANDIDATE'
            if snapshot['provenance']['clock_alignment']=='declared_aligned':
                cpu_start = trace['parameters']['background_cpu_intervals'][0]['from_us']
                load_start = trace['parameters']['downstream_changes'][0]['from_us']
                planned_lag = (load_start-cpu_start)//1000000
                if abs(best['lag_cells']-planned_lag)>1:
                    raise ValueError('INVALID_FIXTURE: temporal oracle does not recover scheduled order')
        else:
            exact[path+'status'] = 'INSUFFICIENT_DATA'
    return {'exact':exact,'numeric':numeric}


def comparison_expected(baseline_trace, current_trace, baseline, current, confirmed):
    windows = [r['resources']['windows'][-1] for r in [baseline,current]]
    facts = [load_facts(t['requests'],(w['from_epoch_ms']-t['parameters'].get('start_epoch_ms',1704067200000))*1000,
                        (w['to_epoch_ms']-t['parameters'].get('start_epoch_ms',1704067200000))*1000)
             for t,w in zip([baseline_trace,current_trace],windows)]
    exact = {'/persisted_equal':True,'/comparison/comparability':'USER_CONFIRMED' if confirmed else 'UNCONFIRMED'}
    numeric = {}
    wp = '/comparison/window_comparison/'
    for name,w,f in zip(['baseline','current'],windows,facts):
        exact[wp+name+'_window'] = w['id']
        exact[wp+name+'_sample_count'] = f['sample_count']
        exact[wp+name+'_duration_ms'] = w['to_epoch_ms']-w['from_epoch_ms']
    rows = []
    for key,metric,unit in [('p50','response_time_p50_ms','ms'),('p95','response_time_p95_ms','ms'),
                            ('p99','response_time_p99_ms','ms'),('throughput_rps','throughput_rps','requests/second'),
                            ('error_rate_ratio','error_rate_ratio','ratio')]:
        rows.append((facts[0][key],facts[1][key],metric,unit,None,None))
    # Inputs bind the same neutral sensors on both members; preserve wire ordering.
    sensors = sorted(baseline['resources']['series'],key=lambda s:(s['metric']+'_median',s['id']))
    for sensor in sensors:
        for statistic,q in [('median',Fraction(1,2)),('q95',Fraction(19,20))]:
            values = []
            for run,w in zip([baseline,current],windows):
                snapshot = run['resources']
                series = next(s for s in snapshot['series'] if s['id']==sensor['id'])
                if any(series[k]!=sensor[k] for k in ['metric','unit','entity','role','aggregation']):
                    raise ValueError('INVALID_FIXTURE: paired resource binding changed')
                start = (w['from_epoch_ms']-snapshot['start_epoch_ms'])//snapshot['step_ms']
                end = (w['to_epoch_ms']-snapshot['start_epoch_ms'])//snapshot['step_ms']
                values.append(oracle.quantile([Fraction(str(v)) for v in series['values'][start:end] if v is not None],q))
            rows.append((*values,sensor['metric']+'_'+statistic,sensor['unit'],sensor['id'],sensor['entity']))
    statuses = []
    for i,(a,b,metric,unit,series_id,entity) in enumerate(rows):
        p = f'/comparison/window_comparison/metrics/{i}/'
        missing = a is None or b is None
        difference = None if missing else b-a
        percent = None if missing or a==0 else Fraction(difference)*100/Fraction(a)
        material = not missing and (abs(difference)>=Fraction('.001') if metric=='error_rate_ratio'
                                     else percent is not None and abs(percent)>=5)
        if missing:
            status,reason = 'INSUFFICIENT_DATA','MISSING_METRIC'
        elif a==0 and metric!='error_rate_ratio':
            status,reason = 'DESCRIPTIVE','ZERO_BASELINE'
        elif material:
            status,reason = ('CANDIDATE',None) if confirmed else ('DESCRIPTIVE','CONDITIONS_UNCONFIRMED')
        else:
            status,reason = 'NO_MATERIAL_CHANGE',None
        statuses.append(status)
        exact.update({p+'metric':metric,p+'unit':unit,p+'resource_series_id':series_id,p+'entity':entity,
                      p+'status':status,p+'reason':reason,p+'percent_reason':reason or ('ZERO_BASELINE' if a==0 else None)})
        for key,value in [('baseline',a),('current',b),('delta',difference),('delta_percent',percent)]:
            if value is None:
                exact[p+key] = None
            else:
                numeric[p+key] = rounded(value)
        for name,w,f in zip(['baseline','current'],windows,facts):
            exact[p+name+'_sample_count'] = f['sample_count']
            exact[p+name+'_duration_ms'] = w['to_epoch_ms']-w['from_epoch_ms']
    exact[wp+'status'] = next((s for s in ['CANDIDATE','DESCRIPTIVE','INSUFFICIENT_DATA'] if s in statuses),'NO_MATERIAL_CHANGE')
    exact[wp+'reasons'] = ([] if confirmed else ['CONDITIONS_UNCONFIRMED']) + (
        ['INCOMPLETE_METRICS'] if 'INSUFFICIENT_DATA' in statuses else [])
    return {'exact':exact,'numeric':numeric}


def prepare_case(output, configuration):
    from applicability_contracts import builder
    import synthetic_service as service
    output = Path(output)
    case_root = output/configuration['id']
    case_root.mkdir()
    runs, traces, trace_files = [], [], []
    expected = {'exact':{},'numeric':{}}
    for index,member in enumerate(configuration['members']):
        trace = service.simulate(member['parameters'],configuration['seed'])
        failures = service.validate_trace(trace)
        if failures:
            raise ValueError('INVALID_FIXTURE:'+configuration['id']+':'+str(failures))
        raw = case_root/f'raw-{index}'
        exports = service.export(trace,raw)
        snapshot = json.loads(Path(exports['resources']).read_text(encoding='utf-8'))
        run = run_input(trace,snapshot,Path(exports['load']).read_bytes().decode('utf-8'),
                        configuration['scenario']=='TEMPORAL','warmup' in configuration['variant'])
        prefix = '' if len(configuration['members'])==1 else '/baseline' if index==0 else '/current'
        facts = expected_run(trace,run,prefix)
        for kind in expected:
            expected[kind].update(facts[kind])
        with Path(exports['trace']).open('rb') as source, gzip.open(case_root/f'trace-{index}.json.gz','xb',compresslevel=1) as destination:
            shutil.copyfileobj(source,destination)
        with gzip.open(case_root/f'trace-{index}.json.gz','rb') as saved:
            trace_hash = oracle._sha(saved.read())
            if trace_hash != oracle._sha(Path(exports['trace']).read_bytes()):
                raise ValueError('TRACE_ARCHIVE_MISMATCH')
        trace_files.append({'path':configuration['id']+f'/trace-{index}.json.gz','uncompressed_sha256':trace_hash})
        for path in exports.values():
            if not Path(path).resolve().is_relative_to(raw.resolve()):
                raise ValueError('INVALID_STAGING_PATH')
            Path(path).unlink()
        raw.rmdir()
        runs.append(run)
        traces.append(trace)
    if len(runs)==1:
        operation = {'operation':'analysis','run':runs[0]}
    else:
        operation = {'operation':'comparison','baseline':runs[0],'current':runs[1],
                     'baseline_window_id':runs[0]['resources']['windows'][-1]['id'],
                     'current_window_id':runs[1]['resources']['windows'][-1]['id'],
                     'conditions_confirmed':configuration['conditions_confirmed']}
        facts = comparison_expected(traces[0],traces[1],runs[0],runs[1],configuration['conditions_confirmed'])
        for kind in expected:
            expected[kind].update(facts[kind])
    case = {'id':configuration['id'],'family':configuration['scenario'],'configuration':configuration['variant'],
            'input':operation,'expected':expected}
    contract = builder(configuration,traces,runs)
    contract['numeric_expectations'] = 'frozen expected JSON pointers cover every emitted measurement window'
    (case_root/'contract.json').write_bytes(oracle._json_bytes(contract))
    if contract['preflight']['status'] != 'PASS' or contract['trace_warnings']:
        raise ValueError('INVALID_FIXTURE:'+configuration['id']+':'+str(contract))
    oracle.freeze_cases(case_root/'frozen',[case])
    entry = json.loads((case_root/'frozen/manifest.json').read_text())['cases'][0]
    entry['contract'] = {'path':configuration['id']+'/contract.json',
                         'sha256':oracle._sha((case_root/'contract.json').read_bytes())}
    entry['traces'] = trace_files
    for kind in ['inputs','expected']:
        entry[kind]['path'] = configuration['id']+'/frozen/'+entry[kind]['path']
    return entry


def prepare(output, debug=False, workers=1):
    if workers not in range(1, 5):
        raise ValueError('workers must be between 1 and 4')
    from applicability_inventory import configurations
    output = Path(output)
    output.mkdir(parents=True,exist_ok=False)
    configs = configurations()
    if debug:
        configs = [deepcopy(c) for c in configs if c['seed']==2000]
        for c in configs:
            c['seed'] = 0
            c['id'] = 'DEBUG-'+c['id']
    inventory = {'configurations':configs,'planned_cases':len(configs),
                 'planned_runs':sum(len(c['members']) for c in configs),'debug':debug}
    (output/'inventory.json').write_bytes(oracle._json_bytes(inventory))
    # Archive exact allowlisted sources, never local connections or data directories.
    root = Path(__file__).resolve().parents[1]
    paths = subprocess.check_output(['git','ls-files','--cached','--others','--exclude-standard'],cwd=root,text=True).splitlines()
    selected = sorted({n for n in paths if (n.startswith(('src/','tools/','docs/contracts/','gradle/','ui/src/'))
                      or n in {'build.gradle.kts','settings.gradle.kts','gradle.properties','gradle.lockfile',
                               'gradlew','gradlew.bat','ui/package.json','ui/package-lock.json','ui/index.html',
                               'ui/tsconfig.json','ui/vite.config.ts','ui/env.d.ts','.editorconfig','.gitattributes',
                               'docs/superpowers/plans/2026-09-06-statistical-validation.md',
                               'docs/statistical-validation-methodology-v1.md','docs/statistical-validation-applicability-rubric-v1.md'})
                      and '__pycache__' not in n and (root/n).is_file()})
    with zipfile.ZipFile(output/'source.zip','x',zipfile.ZIP_DEFLATED) as archive:
        for name in selected:
            archive.writestr(name,(root/name).read_bytes())
    source_hash = oracle._sha((output/'source.zip').read_bytes())
    diff = subprocess.check_output(['git','diff','--binary','HEAD','--',*selected],cwd=root)
    (output/'source.diff').write_bytes(diff)
    metadata = {'source_zip_sha256':source_hash,'inventory_sha256':oracle._sha((output/'inventory.json').read_bytes()),
                'source_diff_sha256':oracle._sha(diff),
                'source_files':{name:oracle._sha((root/name).read_bytes()) for name in selected},
                'python':platform.python_version(),'platform':platform.platform(),
                'head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),
                'debug':debug,'status':'PREPARING','planned_cases':len(configs),'planned_runs':inventory['planned_runs']}
    (output/'preparation.json').write_bytes(oracle._json_bytes(metadata))
    manifest = {'schema_version':'stats-cases.v1','cases':[]}
    if workers == 1:
        entries = map(lambda c: prepare_case(output, c), configs)
        for configuration, entry in zip(configs, entries):
            manifest['cases'].append(entry)
            print(json.dumps({'prepared':len(manifest['cases']),'planned':len(configs),'id':configuration['id']}),flush=True)
    else:
        with ProcessPoolExecutor(max_workers=workers) as pool:
            entries = pool.map(prepare_case, [output] * len(configs), configs, buffersize=workers)
            for configuration, entry in zip(configs, entries):
                manifest['cases'].append(entry)
                print(json.dumps({'prepared':len(manifest['cases']),'planned':len(configs),'id':configuration['id']}),flush=True)
    (output/'manifest.json').write_bytes(oracle._json_bytes(manifest))
    metadata.update(status='FROZEN',manifest_sha256=oracle._sha((output/'manifest.json').read_bytes()))
    (output/'freeze.json').write_bytes(oracle._json_bytes(metadata))
    return metadata


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--debug',action='store_true')
    parser.add_argument('--workers',type=int,default=1)
    args = parser.parse_args()
    print(json.dumps(prepare(args.output,args.debug,args.workers),indent=2))
