"""Builds the IMDb lookup file the TV app bundles as an asset.

Reads the IMDb non-commercial datasets (title.ratings, title.basics, title.akas; personal use only)
and writes one sorted, tab-separated line per (normalized title, kind):

    key \t kind \t rating \t votes \t primaryTitle \t year

A key can have several lines (different titles with the same name). kind is f (movie, tvMovie) or s (tvSeries, tvMiniSeries). Names come from the primary title, the
original title and every Brazilian (region BR) alternative title, so "Ruptura" finds Severance.
Lines are sorted by the UTF-8 bytes of the key: the app binary-searches the file that way.
normalize() must stay identical to Norm.java.

Usage: python -I build_db.py <dir with the three .tsv.gz files> <output imdb.tsv> [min votes]
"""
import gzip
import sys
import unicodedata

KINDS = {'movie': 'f', 'tvMovie': 'f', 'tvSeries': 's', 'tvMiniSeries': 's'}
PER_KEY = 6


def normalize(s):
    s = unicodedata.normalize('NFD', s)
    out = []
    for c in s:
        if unicodedata.category(c) == 'Mn':
            continue
        out.append(c.lower() if c.isalnum() else ' ')
    return ' '.join(''.join(out).split())


def rows(path):
    with gzip.open(path, 'rt', encoding='utf-8', newline='\n') as f:
        next(f)
        for line in f:
            yield line.rstrip('\n').split('\t')


def main():
    src, dst = sys.argv[1], sys.argv[2]
    min_votes = int(sys.argv[3]) if len(sys.argv) > 3 else 200

    ratings = {}
    for tid, rating, votes in rows(f'{src}/title.ratings.tsv.gz'):
        if int(votes) >= min_votes:
            ratings[tid] = (rating, int(votes))
    print(f'ratings >= {min_votes} votes: {len(ratings)}')

    titles = {}  # tid -> [kind, primary, year, set of names]
    for r in rows(f'{src}/title.basics.tsv.gz'):
        tid, ttype, primary, original, year = r[0], r[1], r[2], r[3], r[5]
        if tid in ratings and ttype in KINDS:
            titles[tid] = [KINDS[ttype], primary, '' if year == '\\N' else year, {primary, original}]
    print(f'movies and series: {len(titles)}')

    akas = 0
    with gzip.open(f'{src}/title.akas.tsv.gz', 'rt', encoding='utf-8', newline='\n') as f:
        next(f)
        for line in f:
            if '\tBR\t' not in line:
                continue
            r = line.split('\t')
            t = titles.get(r[0])
            if t is not None and r[3] == 'BR':
                t[3].add(r[2])
                akas += 1
    print(f'Brazilian titles added: {akas}')

    # Up to PER_KEY titles per (key, kind), most voted first, so a year can pick the right one
    # ("Street Fighter (2026)" is not the 1994 film).
    best = {}  # (key, kind) -> {tid: (votes, line)}
    for tid, (kind, primary, year, names) in titles.items():
        rating, votes = ratings[tid]
        for name in names:
            key = normalize(name)
            if key:
                best.setdefault((key, kind), {})[tid] = (votes, f'{key}\t{kind}\t{rating}\t{votes}\t{primary}\t{year}')

    lines = []
    for group in best.values():
        lines.extend(l for _, l in sorted(group.values(), reverse=True)[:PER_KEY])
    lines.sort(key=lambda l: l.encode('utf-8'))
    with open(dst, 'w', encoding='utf-8', newline='\n') as f:
        for l in lines:
            f.write(l + '\n')
    print(f'wrote {len(lines)} lines to {dst}')


if __name__ == '__main__':
    main()
