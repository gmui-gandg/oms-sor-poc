#!/usr/bin/env python3
import argparse
import re
from pathlib import Path


def parse_correlations(path):
    samples = []
    cur = None
    header_re = re.compile(r"--- Sample\s+(\d+)\s+value_ms=([0-9.]+)\s+timestamp=([^\s]+)\s+---")
    match_re = re.compile(r"\s*(.+?):(\d+):\s*(.*)")
    with open(path, 'r', encoding='utf-8', errors='replace') as f:
        for line in f:
            m = header_re.search(line)
            if m:
                if cur:
                    samples.append(cur)
                idx = int(m.group(1))
                val = float(m.group(2))
                ts = m.group(3)
                cur = {'idx': idx, 'value_ms': val, 'timestamp': ts, 'matches': []}
                continue
            if cur:
                mm = match_re.search(line)
                if mm:
                    fname = mm.group(1).strip()
                    lineno = mm.group(2)
                    text = mm.group(3).strip()
                    cur['matches'].append({'file': fname, 'lineno': lineno, 'text': text})
    if cur:
        samples.append(cur)
    return samples


def pick_by_keywords(matches, keywords):
    for kw in keywords:
        for m in matches:
            fn = m['file'].lower()
            if kw in fn:
                return m
    # fallback: any match containing the keyword in text
    for m in matches:
        txt = m['text'].lower()
        for kw in keywords:
            if kw in txt:
                return m
    return None


def summarize(samples, top_n=10):
    out = []
    for s in samples[:top_n]:
        m = s['matches']
        gc = pick_by_keywords(m, ['gc.log', 'zgc', 'gc'])
        th = pick_by_keywords(m, ['thread-dump', 'thread_dump', 'thread-dump-oms', 'thread', 'stacktrace'])
        pg = pick_by_keywords(m, ['postgres', 'pg_stat', 'checkpoint', 'pg-'])
        kafka = pick_by_keywords(m, ['kafka', 'consumer', 'broker'])
        out.append({'idx': s['idx'], 'value_ms': s['value_ms'], 'timestamp': s['timestamp'],
                    'gc': gc, 'thread': th, 'postgres': pg, 'kafka': kafka})
    return out


def format_summary(summ, out_path=None):
    lines = []
    lines.append('Top {} correlation summary'.format(len(summ)))
    for s in summ:
        lines.append('--- Sample {:02d} value_ms={:.1f} timestamp={}'.format(s['idx'], s['value_ms'], s['timestamp']))
        for key in ('gc', 'thread', 'postgres', 'kafka'):
            val = s.get(key)
            if val:
                lines.append('  {}: {}:{}  {}'.format(key.upper(), val['file'], val['lineno'], val['text']))
            else:
                lines.append('  {}: <none>'.format(key.upper()))
    out = '\n'.join(lines) + '\n'
    if out_path:
        Path(out_path).write_text(out, encoding='utf-8')
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--in', dest='infile', required=True, help='correlations file')
    p.add_argument('--top', type=int, default=10, help='top N samples')
    p.add_argument('--out', dest='outfile', default='logs/k6-outliers-summary.txt')
    args = p.parse_args()
    samples = parse_correlations(args.infile)
    if not samples:
        print('No samples found in', args.infile)
        return
    samples_sorted = sorted(samples, key=lambda s: -s['value_ms'])
    summ = summarize(samples_sorted, top_n=args.top)
    out = format_summary(summ, args.outfile)
    print(out)


if __name__ == '__main__':
    main()
