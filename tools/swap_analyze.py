#!/usr/bin/env python3
"""Cross-reference analyzer for binary class-swap between two jars.

Builds for each jar: class index (supers, fields, methods with descriptors)
and constant-pool member references. Then validates a proposed swap set:
  S = classes taken from jar R replacing same-named classes in jar K.
Checks:
  1) every member referenced by S-classes resolves in the merged jar
  2) every member referenced by K-classes pointing into S resolves in R versions
  3) superclass links of S-classes exist
"""
import zipfile, struct, sys, json

def u2(d,i): return (d[i]<<8)|d[i+1]
def u4(d,i): return struct.unpack('>I', d[i:i+4])[0]

class JarIndex:
    def __init__(self, path):
        self.z = zipfile.ZipFile(path)
        self.classes = {}      # name -> dict(supers=[..], fields=set, methods=set, refs=set)
        for n in self.z.namelist():
            if n.endswith('.class'):
                self._parse(n)

    def _parse(self, entry):
        d = self.z.read(entry)
        cp = [None]*u2(d,8)
        utf8 = {}
        i = 10; e = 1
        while e < len(cp):
            t = d[i]; i += 1
            if t == 1:
                ln = u2(d,i); utf8[e] = d[i+2:i+2+ln].decode('utf8','replace'); i += 2+ln
            elif t == 7:  # Class
                cp[e] = ('C', u2(d,i)); i += 2
            elif t in (9,10,11):  # refs
                cp[e] = ({9:'F',10:'M',11:'IM'}[t], u2(d,i), u2(d,i+2)); i += 4
            elif t == 12:  # NameAndType
                cp[e] = ('NT', u2(d,i), u2(d,i+2)); i += 4
            elif t in (8,16,19,20): i += 2
            elif t in (3,4): i += 4
            elif t in (5,6): i += 8; e += 1
            elif t == 15: i += 3
            elif t in (17,18): i += 4
            e += 1
        acc = u2(d,i); this = u2(d,i+2); sup = u2(d,i+4); i += 6
        def clsname(ci):
            if ci == 0: return None
            tag,ix = cp[ci]; return utf8.get(ix,'?')
        name = clsname(this)
        supers = []
        if sup: supers.append(clsname(sup))
        nic = u2(d,i); i += 2
        for _ in range(nic):
            supers.append(clsname(u2(d,i))); i += 2
        fields=set(); methods=set(); refs=set()
        nf = u2(d,i); i += 2
        for _ in range(nf):
            fn = utf8[u2(d,i+2)]; fd = utf8[u2(d,i+4)]
            fields.add((fn,fd))
            i += 6; na = u2(d,i); i += 2
            for _a in range(na): i += 6 + u4(d,i+2)
        nm = u2(d,i); i += 2
        for _ in range(nm):
            mnn = utf8[u2(d,i+2)]; md = utf8[u2(d,i+4)]
            methods.add((mnn,md))
            i += 6; na = u2(d,i); i += 2
            for _a in range(na): i += 6 + u4(d,i+2)
        for e2,ent in enumerate(cp):
            if ent and ent[0] in ('F','M','IM'):
                _, cix, ntix = ent
                owner = clsname(cix)
                _, nix, dix = cp[ntix]
                refs.add((owner, ent[0], utf8[nix], utf8[dix]))
        self.classes[name] = dict(supers=supers, fields=fields, methods=methods, refs=refs)

    def has_member(self, cls, kind, name, desc, _depth=0):
        """Resolve with inheritance. kind 'F' or 'M'/'IM'."""
        if _depth > 40: return True   # cycle guard
        if cls is None: return True
        if cls.startswith('[') or cls.startswith('java/') or cls.startswith('javax/') or cls.startswith('com/'):
            return True               # JDK/API classes assumed present
        c = self.classes.get(cls)
        if c is None: return None     # class itself missing
        if kind == 'F':
            if (name,desc) in c['fields']: return True
        else:
            if (name,desc) in c['methods']: return True
        for s in c['supers']:
            r = self.has_member(s, kind, name, desc, _depth+1)
            if r: return True
        return False


def main():
    rjar, kjar = 'downloads/Quantum_RiverMap_BvhHud.jar', 'downloads/Quantum_Kordos_Full.jar'
    R = JarIndex(rjar); K = JarIndex(kjar)
    print('R classes:', len(R.classes), ' K classes:', len(K.classes))

    # swap set: all Rendering classes from R
    S = {n for n in R.classes if n.startswith('code/Rendering/')}
    print('swap set size:', len(S))

    missing_R = {}   # refs from S classes that fail in merged jar
    for cname in sorted(S):
        c = R.classes[cname]
        for s in c['supers']:
            if s.startswith('code/') and s not in S and s not in K.classes:
                missing_R.setdefault(cname, []).append(('superclass', s, '', ''))
        for (owner, kind, name, desc) in sorted(c['refs']):
            if not owner.startswith('code/'): continue
            if owner in S:
                idx = R   # resolves against R version
            else:
                idx = K
            if owner in S:
                res = R.has_member(owner, kind, name, desc)
            else:
                res = K.has_member(owner, kind, name, desc)
            if res is None:
                missing_R.setdefault(cname, []).append(('class-missing', owner, name, desc))
            elif res is False:
                missing_R.setdefault(cname, []).append(('member-missing', owner, name, desc))

    print('\n=== refs FROM swapped R render classes that FAIL in merged jar:')
    tot = 0
    for cname, items in sorted(missing_R.items()):
        print(' ', cname)
        for it in items:
            print('     ', it); tot += 1
    print('total broken refs from render side:', tot)

    missing_K = {}   # refs from remaining K classes into S
    for cname, c in sorted(K.classes.items()):
        if cname.startswith('code/Rendering/'):
            continue  # will be replaced
        for (owner, kind, name, desc) in sorted(c['refs']):
            if owner in S:
                res = R.has_member(owner, kind, name, desc)
                if res is None or res is False:
                    missing_K.setdefault(cname, []).append((owner, kind, name, desc))
    print('\n=== refs FROM K game classes INTO swapped render classes that FAIL:')
    tot = 0
    for cname, items in sorted(missing_K.items()):
        print(' ', cname)
        for it in items:
            print('     ', it); tot += 1
    print('total broken refs into render side:', tot)

    # K-only Rendering classes: what are they and who references them?
    konly = {n for n in K.classes if n.startswith('code/Rendering/')} - S
    print('\nK-only Rendering classes:', sorted(konly))
    for ko in sorted(konly):
        users = [c for c in K.classes if not c.startswith('code/Rendering/')
                 and any(ref[0]==ko for ref in K.classes[c]['refs'])]
        print('  referenced by (non-render):', users)

if __name__ == '__main__':
    main()
