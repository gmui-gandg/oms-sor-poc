#!/usr/bin/env python3
import json
import argparse
from pathlib import Path


def summarize_k6(path):
    # We'll stream the NDJSON and compute distribution for http_req_duration points
    values = []
    other_counts = {}
    with open(path, 'r', encoding='utf-8', errors='replace') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except Exception:
                continue
            if obj.get('type') == 'Point' and obj.get('metric') == 'http_req_duration':
                data = obj.get('data', {})
                tags = data.get('tags', {}) or {}
                name = tags.get('name') or tags.get('url') or tags.get('group')
                # target POST /api/v1/orders or group ::Order Submission
                if name and ('/api/v1/orders' in str(name) or 'Order Submission' in str(name) or 'POST /api/v1/orders' in str(name)):
                    val = data.get('value')
                    if isinstance(val, (int, float)):
                        values.append(float(val))
            # count other event types for quick reference
            t = obj.get('type')
            if t:
                other_counts[t] = other_counts.get(t, 0) + 1

    def pct(sorted_vals, p):
        if not sorted_vals:
            return None
        k = (len(sorted_vals)-1) * (p/100.0)
        f = int(k)
        c = min(f+1, len(sorted_vals)-1)
        if f == c:
            return sorted_vals[int(k)]
        d0 = sorted_vals[f] * (c-k)
        d1 = sorted_vals[c] * (k-f)
        return d0 + d1

    summary = {}
    if values:
        vals = sorted(values)
        summary['count'] = len(vals)
        summary['min'] = vals[0]
        summary['max'] = vals[-1]
        summary['avg'] = sum(vals)/len(vals)
        summary['p50'] = pct(vals, 50)
        summary['p90'] = pct(vals, 90)
        summary['p95'] = pct(vals, 95)
        summary['p99'] = pct(vals, 99)
    else:
        summary['count'] = 0
    summary['other_counts'] = other_counts
    return summary


def format_summary(s, path):
    lines = []
    lines.append(f'File: {path}')
    if 'count' in s:
        lines.append('http_req_duration (POST /api/v1/orders):')
        lines.append(f"  count: {s.get('count')}")
        lines.append(f"  min: {s.get('min')}")
        lines.append(f"  max: {s.get('max')}")
        lines.append(f"  avg: {s.get('avg')}")
        lines.append(f"  p50: {s.get('p50')}")
        lines.append(f"  p90: {s.get('p90')}")
        lines.append(f"  p95: {s.get('p95')}")
        lines.append(f"  p99: {s.get('p99')}")
    else:
        lines.append('http_req_duration (POST /api/v1/orders): <not found>')
    lines.append('other_event_counts:')
    for k,v in s.get('other_counts', {}).items():
        lines.append(f'  {k}: {v}')
    return '\n'.join(lines) + '\n'


def main():
    p = argparse.ArgumentParser()
    p.add_argument('file', help='k6 result JSON')
    p.add_argument('--out', help='output text file', required=True)
    args = p.parse_args()
    s = summarize_k6(args.file)
    out = format_summary(s, args.file)
    Path(args.out).write_text(out, encoding='utf-8')
    print(out)


if __name__ == '__main__':
    main()
