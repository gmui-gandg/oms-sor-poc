#!/usr/bin/env python3
import re
import sys
import argparse
from collections import deque
from datetime import datetime, timedelta

TS_RE = re.compile(r"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:[.,]\d+)?(?:Z|[+\-]\d{2}:\d{2})?)")
VALUE_RE = re.compile(r'"value"\s*:\s*([0-9]+(?:\.[0-9]+)?)')


def extract_candidates(k6_path, min_ms=500.0, max_candidates=200):
    buf = deque(maxlen=20)
    candidates = []
    with open(k6_path, 'r', encoding='utf-8', errors='ignore') as f:
        for line in f:
            buf.append(line)
            vs = VALUE_RE.findall(line)
            if not vs:
                continue
            # take first numeric value on line
            try:
                val = float(vs[0])
            except:
                continue
            # heuristic: treat value as ms if > min_ms
            if val < min_ms:
                continue
            ts = None
            # try same line for timestamp
            m = TS_RE.search(line)
            if m:
                ts = m.group(1)
            else:
                # search buffer for recent timestamp
                for l in reversed(buf):
                    m2 = TS_RE.search(l)
                    if m2:
                        ts = m2.group(1)
                        break
            if not ts:
                # fallback: record without timestamp
                ts = None
            candidates.append((ts, val, line.strip()))
            if len(candidates) >= max_candidates * 3:
                # keep memory bounded
                candidates = sorted(candidates, key=lambda x: x[1], reverse=True)[:max_candidates]
    # final top-N
    candidates = sorted(candidates, key=lambda x: x[1], reverse=True)[:max_candidates]
    return candidates


def parse_iso(ts_str):
    if ts_str is None:
        return None
    # normalize comma decimals
    ts_str = ts_str.replace(',', '.')
    try:
        # Python 3.11+ supports offset parsing
        return datetime.fromisoformat(ts_str)
    except Exception:
        # try trimming timezone
        try:
            if ts_str.endswith('Z'):
                return datetime.fromisoformat(ts_str[:-1])
        except Exception:
            return None


def search_logs_for_timestamp(dt, logs_dir='logs', window_s=5, max_matches=20, exclude_path=None):
    # build candidate substrings: date and time variants for seconds +/- window
    candidates = []
    for delta in range(-window_s, window_s + 1):
        t = dt + timedelta(seconds=delta)
        date_s = t.strftime('%Y-%m-%d')
        time_s = t.strftime('%H:%M:%S')
        candidates.append((date_s, time_s))
    matches = []
    import os
    for root, _, files in os.walk(logs_dir):
        for fname in files:
            fpath = os.path.join(root, fname)
            if exclude_path and os.path.abspath(fpath) == os.path.abspath(exclude_path):
                continue
            try:
                with open(fpath, 'r', encoding='utf-8', errors='ignore') as fh:
                    for lineno, l in enumerate(fh, start=1):
                        for date_s, time_s in candidates:
                            if date_s in l and time_s in l:
                                matches.append((fpath, lineno, l.strip()))
                                break
                            # also match time-only lines
                            if time_s in l and date_s not in l:
                                # include but mark
                                matches.append((fpath, lineno, l.strip()))
                                break
                        if len(matches) >= max_matches:
                            return matches
            except Exception:
                continue
    return matches


def main():
    p = argparse.ArgumentParser(description='Correlate k6 outliers with logs (heuristic).')
    p.add_argument('--k6', required=True, help='Path to k6 results JSON')
    p.add_argument('--out', default='logs/k6-outliers-correlations.txt', help='Output file')
    p.add_argument('--min-ms', type=float, default=500.0, help='Minimum ms to treat as outlier')
    p.add_argument('--top', type=int, default=50, help='Top N slowest to analyze')
    args = p.parse_args()

    cand = extract_candidates(args.k6, min_ms=args.min_ms, max_candidates=args.top)
    with open(args.out, 'w', encoding='utf-8') as out:
        out.write(f'Found {len(cand)} candidate slow samples (min_ms={args.min_ms}).\n')
        out.write('Top slowest samples:\n')
        for i, (ts_str, val, context) in enumerate(cand, start=1):
            out.write(f'{i:02d}. value_ms={val} timestamp={ts_str} context={context}\n')
        out.write('\nCorrelations (±5s) per sample:\n')
        for i, (ts_str, val, context) in enumerate(cand, start=1):
            out.write(f'--- Sample {i:02d} value_ms={val} timestamp={ts_str} ---\n')
            dt = parse_iso(ts_str)
            if not dt:
                out.write('  No parseable timestamp found; skipping log search.\n')
                continue
            matches = search_logs_for_timestamp(dt, logs_dir='logs', window_s=5, max_matches=25)
            if not matches:
                out.write('  No log lines matched within ±5s window.\n')
            else:
                out.write(f'  Found {len(matches)} matching log lines:\n')
                for fpath, lineno, line in matches:
                    out.write(f'    {fpath}:{lineno}: {line}\n')
    print('Done. Wrote correlations to', args.out)

if __name__ == '__main__':
    main()
