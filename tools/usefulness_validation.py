"""Preregistered report-level calibration: frozen process truth, no cutoff tuning."""
import argparse
from collections import defaultdict
from decimal import Decimal
import json
from math import sqrt
from pathlib import Path
import shutil
import subprocess
import zipfile

import claims_audit
import stats_validation as wire
import usefulness_inputs as generator


def validate_output(configuration, output):
    """A missing computation is an error, never a quiet null report."""
    wire._json_bytes(output)  # Reject overflow/NaN recursively, including unscored fields.
    if not isinstance(output,dict):
        raise ValueError('output must be an object')
    def finite_strings(value):
        if isinstance(value,dict):
            for child in value.values():
                finite_strings(child)
        elif isinstance(value,list):
            for child in value:
                finite_strings(child)
        elif isinstance(value,str) and value.lower().lstrip('+-') in {'nan','snan','inf','infinity'}:
            raise ValueError('non-finite numeric string')
    finite_strings(output)
    config = configuration['config']
    kind = config['kind']
    members = [output] if kind!='comparison' else [output.get('baseline'),output.get('current')]
    for member in members:
        if not isinstance(member,dict) or any(not isinstance(member.get(key),dict) for key in
                ['resource_summaries','correlation_pairs','anomaly_checks']) or not isinstance(member.get('findings'),list):
            raise ValueError('missing analysis projection')
        if not member['resource_summaries']:
            raise ValueError('missing resource statistics')
    if kind=='correlation':
        pairs = output['correlation_pairs'].get('evaluation',{})
        if set(pairs)!={f'pair-{i:02d}' for i in range(config['pairs'])}:
            raise ValueError('missing or unexpected correlation pair')
        for pair in pairs.values():
            if not {'status','reasons','raw_rho','partial_rho','best_lag_ms','best_lag_rho','lag_profile'}<=pair.keys():
                raise ValueError('incomplete correlation evidence')
    elif kind=='episode':
        checks = output['anomaly_checks']
        if set(checks)!={f'rule-{i:02d}' for i in range(config['rules'])}:
            raise ValueError('missing or unexpected anomaly rule')
        for check in checks.values():
            if not {'status','reasons','episodes_reported','suppressed_short_episodes'}<=check.keys():
                raise ValueError('incomplete anomaly evidence')
    else:
        comparison = output.get('comparison',{})
        window = comparison.get('window_comparison',{})
        if comparison.get('comparability')!='USER_CONFIRMED' or window.get('status')=='NOT_EVALUATED':
            raise ValueError('comparison not evaluated or conditions missing')
        rows = window.get('metrics',[])
        expected = {'response_time_p50_ms','response_time_p95_ms','response_time_p99_ms',
                    'throughput_rps','error_rate_ratio','resource_median','resource_q95'}
        if len(rows)!=7 or {row.get('metric') for row in rows}!=expected:
            raise ValueError('missing or duplicate comparison metric')
        for row in rows:
            if not {'baseline','current','delta','delta_percent','status','reason','percent_reason'}<=row.keys():
                raise ValueError('incomplete comparison evidence')
    for member in members:
        for finding in member['findings']:
            if not isinstance(finding,dict) or not isinstance(finding.get('type'),str):
                raise ValueError('invalid finding')
    for row in (pairs.values() if kind=='correlation' else checks.values() if kind=='episode' else rows):
        if row.get('status') not in {'CANDIDATE','DESCRIPTIVE','BELOW_EFFECT','OPPOSITE_SIGN','INSUFFICIENT_DATA','NO_MATERIAL_CHANGE'}:
            raise ValueError('invalid evidence status')
        if kind=='comparison':
            for key in ['baseline','current','delta','delta_percent']:
                if row[key] is not None:
                    wire._numeric(row[key])
        elif kind=='correlation':
            for key in ['raw_rho','partial_rho','best_lag_rho']:
                if row[key] is not None and abs(wire._numeric(row[key]))>1:
                    raise ValueError('invalid correlation coefficient')
    return output


def wilson(successes, count):
    if not 0 <= successes <= count or count == 0:
        raise ValueError('invalid independent report counts')
    z = 1.959963984540054
    p = successes/count
    center = (p+z*z/(2*count))/(1+z*z/count)
    radius = z*sqrt(p*(1-p)/count+z*z/(4*count*count))/(1+z*z/count)
    return max(0,center-radius),min(1,center+radius)


def measure(truth, output):
    findings = [f for f in output.get('findings',[]) if f.get('type') in {'correlation_candidate','anomaly_episode'}]
    comparison = output.get('comparison',{}).get('window_comparison',{})
    rows = comparison.get('metrics',[])
    candidates = [row for row in rows if row.get('status')=='CANDIDATE']
    pairs = [pair for window in output.get('correlation_pairs',{}).values() for pair in window.values()]
    checks = list(output.get('anomaly_checks',{}).values())
    all_rows = rows+pairs+checks
    result = {'headlines':len(findings)+len(candidates),'unrelated_headlines':0,
              'descriptive_rows':sum(row.get('status')=='DESCRIPTIVE' for row in all_rows),
              'unevaluable_rows':sum(row.get('status') in {'INSUFFICIENT_DATA','NOT_EVALUATED','NOT_EVALUABLE'} for row in all_rows),
              'detected':False}
    family = truth['family']
    if truth['kind']=='null':
        result['unrelated_headlines'] = result['headlines']
        if family.startswith('T'):
            result['delta_errors'] = {row['metric']:float(Decimal(row['delta']))
                                      for row in rows if row.get('delta') is not None}
    elif family in {'P01','P02','P03'}:
        related = [f for f in findings if f.get('type')=='correlation_candidate' and f.get('pair_id')==truth['injected_pair']]
        pair = output.get('correlation_pairs',{}).get('evaluation',{}).get(truth['injected_pair'],{})
        lag = pair.get('best_lag_ms')
        rho = pair.get('best_lag_rho')
        result['lag_error_cells'] = None if lag is None else lag/1000-truth['lag_cells']
        signed = rho is not None and (Decimal(str(rho))<0 if truth['sign']=='negative' else Decimal(str(rho))>0)
        direction = lag is not None and (lag==0 if truth['lag_cells']==0 else lag*truth['lag_cells']>0)
        result['correct_direction'] = direction
        result['detected'] = bool(related) and signed and direction and abs(result['lag_error_cells'])<=1
        result['unrelated_headlines'] = len(findings)-len(related)
    elif family in {'P04','P05'}:
        related = [f for f in findings if f.get('type')=='anomaly_episode' and f.get('rule_id')==truth['injected_rule']]
        start,end = truth['interval']['from_epoch_ms'],truth['interval']['to_epoch_ms']
        def iou(finding):
            a,b = finding['from_epoch_ms'],finding['to_epoch_ms']
            overlap = max(0,min(b,end)-max(a,start))
            return overlap/((b-a)+(end-start)-overlap)
        best = max(related,key=iou) if related else None
        result.update(iou=iou(best) if best else 0,
                      fragmentation=sum(iou(f)>0 for f in related),
                      start_error_ms=best['from_epoch_ms']-start if best else None,
                      end_error_ms=best['to_epoch_ms']-end if best else None)
        result['detected'] = best is not None and result['iou']>=.5 and best['direction']==truth['sign']
        result['unrelated_headlines'] = sum(f not in related or iou(f)==0 for f in findings)
    elif family in {'P06','P07'}:
        expected_delta = 40 if family=='P06' else -40
        latency = [row for row in rows if row.get('metric') in {'response_time_p50_ms','response_time_p95_ms','response_time_p99_ms'}]
        result['delta_errors_ms'] = {row['metric']:float(Decimal(row['delta']))-expected_delta for row in latency if row.get('delta') is not None}
        # All three affected latency summaries must expose the injected direction.
        result['detected'] = len(latency)==3 and {row['metric'] for row in latency}=={
            'response_time_p50_ms','response_time_p95_ms','response_time_p99_ms'} and all(
                row.get('status')=='CANDIDATE' and Decimal(row['delta'])*expected_delta>0 for row in latency)
        result['unrelated_headlines'] = sum(row not in latency for row in candidates)
    result['unexpected_report'] = result['unrelated_headlines']>0
    return result


def aggregate(records, positive, errors=0):
    count = len(records)
    unexpected = sum(r['unexpected_report'] for r in records)
    detected = sum(r['detected'] for r in records)
    noise_interval = wilson(unexpected,count)
    detection_interval = wilson(detected,count) if positive else None
    headlines = [r['headlines'] for r in records]
    diagnostics = {}
    for key in ['lag_error_cells','iou','fragmentation','start_error_ms','end_error_ms']:
        values = [r[key] for r in records if r.get(key) is not None]
        if values:
            diagnostics[key] = {'count':len(values),'mean':sum(values)/len(values),
                                'median':float(wire.quantile(values,Decimal('.5'))),
                                'p95':float(wire.quantile(values,Decimal('.95'))),
                                'min':min(values),'max':max(values)}
    if any('correct_direction' in r for r in records):
        diagnostics['correct_direction_fraction'] = sum(r.get('correct_direction',False) for r in records)/count
    for key in ['delta_errors','delta_errors_ms']:
        metrics = {metric for r in records for metric in r.get(key,{})}
        if metrics:
            diagnostics[key] = {metric:{'mean':sum(v)/len(v),'min':min(v),'max':max(v)}
                                for metric in sorted(metrics)
                                for v in [[r[key][metric] for r in records if metric in r.get(key,{})]]}
    return {'planned':1000,'completed':count,'errors':errors,'missing':1000-count-errors,
            'diagnostics':diagnostics,'unexpected_reports':unexpected,'unexpected_fraction':unexpected/count,
            'unexpected_wilson95':noise_interval,'detected':detected if positive else None,
            'missed':count-detected if positive else None,'detection_wilson95':detection_interval,
            'headlines':{'median':float(wire.quantile(headlines,Decimal('.5'))),
                         'p95':float(wire.quantile(headlines,Decimal('.95'))),'max':max(headlines)},
            'descriptive_rows':sum(r['descriptive_rows'] for r in records),
            'unevaluable_reports':sum(r['unevaluable_rows']>0 for r in records),
            'status':'INCOMPLETE' if count!=1000 or errors or any(r['unevaluable_rows'] for r in records) else 'PASS' if unexpected/count<=.05 and noise_interval[1]<=.07 and (
                not positive or detected/count>=.9 and detection_interval[0]>=.85) else 'FAIL'}


def prepare(output, applicability, report, report_sha256):
    """Prepare all 28,000 only after the independently verified preceding gate."""
    output,applicability,report = Path(output),Path(applicability),Path(report)
    if wire._sha(report.read_bytes())!=report_sha256 or json.loads(report.read_bytes())['status']!='PASS':
        raise ValueError('APPLICABILITY gate does not permit calibration')
    freeze = json.loads((applicability/'freeze.json').read_bytes())
    if freeze.get('debug') is not False or freeze.get('planned_runs')!=830:
        raise ValueError('debug is not an acceptance gate')
    import applicability_score
    checked = applicability_score.score(applicability,freeze['manifest_sha256'],applicability/'actual.jsonl')
    if wire._json_bytes(checked)!=report.read_bytes():
        raise ValueError('applicability report is not bound to the supplied corpus/actual')
    root = Path(__file__).resolve().parents[1]
    output.mkdir(parents=True,exist_ok=False)
    # Separate study freeze: MC-only harness work may finish after applicability freeze.
    sources = {name:(root/name).read_bytes() for name in freeze['source_files']}
    for name,raw in sources.items():
        if name.startswith('src/main/') and wire._sha(raw)!=freeze['source_files'][name]:
            raise ValueError('production changed since applicability acceptance')
    with zipfile.ZipFile(output/'source.zip','x',zipfile.ZIP_DEFLATED) as archive:
        for name,raw in sources.items():
            archive.writestr(name,raw)
    diff = subprocess.check_output(['git','diff','--binary','HEAD','--',*sources],cwd=root)
    (output/'source.diff').write_bytes(diff)
    shutil.copyfile(report,output/'applicability-report.json')
    shutil.copyfile(applicability/'freeze.json',output/'applicability-freeze.json')
    configs = generator.configurations()
    manifest = {'schema_version':'stats-cases.v1','cases':[]}
    (output/'inputs').mkdir()
    (output/'expected').mkdir()
    (output/'inventory.json').write_bytes(wire._json_bytes(configs))
    for index,configuration in enumerate(configs):
        generated = generator.generate(configuration)
        entry = {'id':configuration['id'],'family':configuration['family'],'configuration':configuration['config']}
        for kind,value in [('inputs',generated['input']),('expected',generated['truth'])]:
            raw = wire._json_bytes(value)
            path = kind+'/'+configuration['id']+'.json'
            (output/path).write_bytes(raw)
            entry[kind] = {'path':path,'sha256':wire._sha(raw)}
        manifest['cases'].append(entry)
        if (index+1)%1000==0:
            print(json.dumps({'prepared':index+1,'planned':len(configs)}),flush=True)
    # Every configuration, first three seeds; selection is not based on outputs.
    parity_ids = {c['id'] for c in configs if c['seed'] in {1000,1001,1002}}
    (output/'parity').mkdir()
    parity = {'schema_version':'stats-cases.v1','cases':[]}
    for entry in manifest['cases']:
        if entry['id'] in parity_ids:
            copied = json.loads(json.dumps(entry))
            # Runner rejects paths escaping its root: hard links retain exact bytes.
            for kind in ['inputs','expected']:
                destination = output/'parity'/entry[kind]['path']
                destination.parent.mkdir(exist_ok=True)
                destination.hardlink_to(output/entry[kind]['path'])
            parity['cases'].append(copied)
    (output/'parity/manifest.json').write_bytes(wire._json_bytes(parity))
    metadata = {**freeze,'status':'FROZEN','debug':False,'planned_cases':len(configs),
                'applicability_report_sha256':report_sha256,
                'applicability_freeze_sha256':wire._sha((output/'applicability-freeze.json').read_bytes()),
                'applicability_actual_path':str((applicability/'actual.jsonl').resolve()),
                'applicability_actual_sha256':wire._sha((applicability/'actual.jsonl').read_bytes()),
                'source_files':{name:wire._sha(raw) for name,raw in sources.items()},
                'source_zip_sha256':wire._sha((output/'source.zip').read_bytes()),
                'source_diff_sha256':wire._sha(diff),
                'inventory_sha256':wire._sha((output/'inventory.json').read_bytes()),
                'parity_manifest_sha256':wire._sha((output/'parity/manifest.json').read_bytes())}
    metadata.pop('manifest_sha256',None)
    metadata.pop('planned_runs',None)
    manifest['freeze_context'] = metadata
    (output/'manifest.json').write_bytes(wire._json_bytes(manifest))
    metadata['manifest_sha256'] = wire._sha((output/'manifest.json').read_bytes())
    (output/'freeze.json').write_bytes(wire._json_bytes(metadata))
    return metadata


def projection(output):
    keys = ['resource_summaries','correlation_pairs','anomaly_checks','findings']
    if 'comparison' not in output:
        return {key:output[key] for key in keys}
    compared = output['comparison']
    return {'baseline':{key:output['baseline'][key] for key in keys},
            'current':{key:output['current'][key] for key in keys},
            'comparison':{key:compared[key] for key in ['comparability','metrics','window_comparison']}}


def parity(corpus, core_actual, persisted_actual):
    expected = {c['id'] for c in generator.configurations() if c['seed'] in {1000,1001,1002}}
    def selected(path, persisted):
        found,seen = {},set()
        with Path(path).open(encoding='utf-8') as stream:
            for line in stream:
                record = wire._read_json(line)
                wire._json_bytes(record)
                case_id = record['id']
                if case_id in seen or persisted and case_id not in expected:
                    raise ValueError('parity actual inventory')
                seen.add(case_id)
                if case_id in expected:
                    if 'error' in record or persisted and record['output'].get('persisted_equal') is not True:
                        raise ValueError('parity execution/reload error')
                    found[case_id] = projection(record['output'])
        if set(found)!=expected:
            raise ValueError('parity requires exactly 84 declared first-three cases')
        return found
    core,real = selected(core_actual,False),selected(persisted_actual,True)
    mismatches = [case_id for case_id in sorted(expected) if wire._json_bytes(core[case_id])!=wire._json_bytes(real[case_id])]
    return {'status':'FAIL' if mismatches else 'PASS','planned':84,'completed':len(core),'mismatches':mismatches,
            'core_actual_sha256':wire._sha(Path(core_actual).read_bytes()),
            'persisted_actual_sha256':wire._sha(Path(persisted_actual).read_bytes())}


def verify_freeze(corpus, manifest_sha256):
    freeze = json.loads((corpus/'freeze.json').read_bytes())
    manifest = (corpus/'manifest.json').read_bytes()
    if wire._sha(manifest)!=manifest_sha256 or json.loads(manifest)['freeze_context']!={
            key:value for key,value in freeze.items() if key!='manifest_sha256'}:
        raise ValueError('manifest does not bind frozen source/gate metadata')
    if freeze['manifest_sha256']!=manifest_sha256 or freeze.get('debug') is not False:
        raise ValueError('freeze manifest mismatch')
    files = {'source.zip':'source_zip_sha256','source.diff':'source_diff_sha256',
             'inventory.json':'inventory_sha256','applicability-report.json':'applicability_report_sha256',
             'applicability-freeze.json':'applicability_freeze_sha256','parity/manifest.json':'parity_manifest_sha256'}
    for path,key in files.items():
        if wire._sha((corpus/path).read_bytes())!=freeze[key]:
            raise ValueError('frozen gate/source hash mismatch: '+path)
    if wire._sha(Path(freeze['applicability_actual_path']).read_bytes())!=freeze['applicability_actual_sha256']:
        raise ValueError('applicability actual hash mismatch')
    if json.loads((corpus/'applicability-report.json').read_bytes())['status']!='PASS':
        raise ValueError('applicability gate not passed')
    with zipfile.ZipFile(corpus/'source.zip') as archive:
        if set(archive.namelist())!=set(freeze['source_files']) or any(
                wire._sha(archive.read(name))!=digest for name,digest in freeze['source_files'].items()):
            raise ValueError('source file hash mismatch')
    if json.loads((corpus/'inventory.json').read_bytes())!=generator.configurations():
        raise ValueError('configuration inventory mismatch')


def score(corpus, manifest_sha256, actual, persisted_actual):
    corpus,actual = Path(corpus).resolve(),Path(actual)
    report = {'status':'INCOMPLETE','planned':28000,'completed':0,'errors':[], 'configurations':{},'cases':[]}
    raw = (corpus/'manifest.json').read_bytes()
    if wire._sha(raw)!=manifest_sha256:
        raise ValueError('manifest hash mismatch')
    verify_freeze(corpus,manifest_sha256)
    manifest = json.loads(raw)
    entries = {e['id']:e for e in manifest['cases']}
    configs = {c['id']:c for c in generator.configurations()}
    if len(manifest['cases'])!=len(configs) or set(entries)!=set(configs):
        raise ValueError('incomplete or duplicate inventory')
    for entry in entries.values():
        for kind in ['inputs','expected']:
            path = (corpus/entry[kind]['path']).resolve()
            if not path.is_relative_to(corpus) or wire._sha(path.read_bytes())!=entry[kind]['sha256']:
                raise ValueError('frozen artifact mismatch')
    groups,errors_by_group,seen = defaultdict(list),defaultdict(int),set()
    with actual.open(encoding='utf-8') as stream:
        for line in stream:
            record = wire._read_json(line)
            wire._json_bytes(record)
            case_id = record['id']
            if case_id not in entries or case_id in seen:
                raise ValueError('unknown or duplicate actual record')
            seen.add(case_id)
            config = configs[case_id]
            key = config['family']+'/'+json.dumps(config['config'],sort_keys=True,separators=(',',':'))
            try:
                if 'error' in record:
                    raise ValueError(record['error'])
                validate_output(config,record.get('output'))
            except (ValueError,TypeError,KeyError) as error:
                report['errors'].append({'id':case_id,'error':str(error)})
                errors_by_group[key] += 1
                continue
            truth = json.loads((corpus/entries[case_id]['expected']['path']).read_bytes())
            measured = measure(truth,record['output'])
            measured['forbidden_claims'] = claims_audit.audit_output(record['output'])
            wire._json_bytes(measured)
            groups[key].append(measured)
            report['cases'].append({'id':case_id,**measured})
    report['completed'] = len(report['cases'])
    if seen!=set(entries):
        report['errors'].append({'missing':sorted(set(entries)-seen)})
    all_keys = {c['family']+'/'+json.dumps(c['config'],sort_keys=True,separators=(',',':')) for c in configs.values()}
    for key in sorted(all_keys):
        records = groups[key]
        report['configurations'][key] = aggregate(records,key.startswith('P'),errors_by_group[key]) if records else {
            'planned':1000,'completed':0,'errors':errors_by_group[key],'missing':1000-errors_by_group[key],'status':'INCOMPLETE'}
    report['parity'] = parity(corpus,actual,persisted_actual)
    if report['completed']==28000 and len(groups)==28 and all(len(r)==1000 for r in groups.values()) and not report['errors']:
        report['status'] = 'PASS' if report['parity']['status']=='PASS' and all(g['status']=='PASS' for g in report['configurations'].values()) and not any(
            c['forbidden_claims'] for c in report['cases']) else 'FAIL'
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command',required=True)
    preparation = commands.add_parser('prepare')
    for name in ['output','applicability','report']:
        preparation.add_argument('--'+name,type=Path,required=True)
    preparation.add_argument('--report-sha256',required=True)
    scoring = commands.add_parser('score')
    for name in ['corpus','actual','persisted-actual','output']:
        scoring.add_argument('--'+name,type=Path,required=True)
    scoring.add_argument('--manifest-sha256',required=True)
    args = parser.parse_args()
    if args.command=='prepare':
        print(json.dumps(prepare(args.output,args.applicability,args.report,args.report_sha256),indent=2))
    else:
        result = score(args.corpus,args.manifest_sha256,args.actual,args.persisted_actual)
        with args.output.open('xb') as stream:
            stream.write(wire._json_bytes(result))
        print(json.dumps({k:v for k,v in result.items() if k not in {'cases','configurations'}}))
        raise SystemExit(0 if result['status']=='PASS' else 1)
