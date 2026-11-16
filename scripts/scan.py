#!/usr/bin/env python3
"""Refuses to let a key out: scans release files (APK, tar.gz, and jars inside them) for anything key-shaped,
and for any exact secret listed, one per line, in the file named by the SCAN_SECRETS environment variable."""
import io, os, re, sys, tarfile, zipfile

SHAPED = re.compile(rb'sk-ant-(api|admin)\d\d-[A-Za-z0-9_-]{20,}|ghp_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|gsk_[A-Za-z0-9]{30,}|sk-or-v1-[a-f0-9]{30,}')


def secrets():
    path = os.environ.get('SCAN_SECRETS')
    if not path or not os.path.exists(path):
        return []
    return [l.strip().encode() for l in open(path) if len(l.strip()) >= 12]


def blobs(path):
    if path.endswith(('.apk', '.jar', '.zip')):
        z = zipfile.ZipFile(path)
        for n in z.namelist():
            yield n, z.read(n)
    elif path.endswith(('.tar.gz', '.tgz')):
        t = tarfile.open(path)
        for m in t.getmembers():
            if m.isfile():
                yield m.name, t.extractfile(m).read()
    else:
        yield path, open(path, 'rb').read()


def main():
    known = secrets()
    bad = False
    for path in sys.argv[1:]:
        count = 0
        for name, data in blobs(path):
            count += 1
            inner = [(name, data)]
            if name.endswith('.jar'):
                try:
                    z = zipfile.ZipFile(io.BytesIO(data))
                    inner += [(name + '!' + m, z.read(m)) for m in z.namelist()]
                except Exception:
                    pass
            for n, d in inner:
                if SHAPED.search(d) or any(s in d for s in known):
                    bad = True
                    print('  FOUND in', n)
        print(os.path.basename(path), '->', 'KEY FOUND, DO NOT PUBLISH' if bad else f'clean ({count} files, {len(known)} known secrets checked)')
    sys.exit(1 if bad else 0)


if __name__ == '__main__':
    main()
