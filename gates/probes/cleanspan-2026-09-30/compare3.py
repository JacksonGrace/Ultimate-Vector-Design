"""old-vs-new comparison with positions.
usage: compare3.py <old dump> <new dump>
Per document: geometry moved? (max position shift, m), vDesign changes,
per span: C = records the design speed (clean), = unchanged, M = march moved,
and the largest one-sample STEP of (true - design) across every span seam."""
import struct, sys, math


def dbl(h):
    return struct.unpack(">d", struct.pack(">Q", int(h, 16)))[0]


def parse(path):
    docs = {}
    order = []
    cur = None
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if line.startswith("DOC "):
            rest = line[4:]
            name = rest[:rest.index(" fric ")] if " fric " in rest else rest
            cur = {"head": rest[len(name):], "sec": [], "piece": [],
                   "span": [], "geom": None, "v": [], "p": []}
            docs[name] = cur
            order.append(name)
            continue
        p = line.split(" ")
        if p[0] == "SEC":
            cur["sec"].append(p)
        elif p[0] == "PIECE":
            cur["piece"].append(line)
        elif p[0] == "SPAN":
            cur["span"].append((int(p[2]), int(p[3])))
        elif p[0] == "GEOM":
            cur["geom"] = p[1]
        elif p[0] == "V":
            cur["v"].append((p[2], p[3]))
            cur["p"].append((float(p[4]), float(p[5]), float(p[6])))
    return docs, order


old, order = parse(sys.argv[1])
new, _ = parse(sys.argv[2])
moved_docs = 0
user_moved = []
worst_step_new = 0
for name in order:
    o, n = old[name], new[name]
    notes = []
    if len(o["v"]) != len(n["v"]):
        notes.append("SAMPLE COUNT %d -> %d" % (len(o["v"]), len(n["v"])))
    m = min(len(o["p"]), len(n["p"]))
    shift = 0.0
    for i in range(m):
        a, b = o["p"][i], n["p"][i]
        d = math.sqrt((a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2 + (a[2] - b[2]) ** 2)
        if d > shift:
            shift = d
    end_shift = 0.0
    if o["p"] and n["p"]:
        a, b = o["p"][-1], n["p"][-1]
        end_shift = math.sqrt(sum((a[k] - b[k]) ** 2 for k in range(3)))
    dv = 0.0
    nvd = 0
    for so, sn in zip(o["sec"], n["sec"]):
        if so[4] != sn[4]:
            nvd += 1
            dv = max(dv, abs(dbl(so[4]) - dbl(sn[4])))
    geom = o["geom"] != n["geom"]
    if o["piece"] != n["piece"]:
        notes.append("PLACEMENT differs")
    desc = []
    for k, (i0, i1) in enumerate(n["span"]):
        if i1 >= len(n["v"]) or i1 >= len(o["v"]):
            desc.append("%d:?" % k)
            continue
        rng = range(i0, i1 + 1)
        allc = all(n["v"][i][1] == n["v"][i][0] for i in rng)
        same = all(n["v"][i][1] == o["v"][i][1] for i in rng)
        mv = max(abs(dbl(n["v"][i][1]) - dbl(o["v"][i][1])) for i in rng)
        if allc:
            desc.append("%d:C" % k)
        elif same:
            desc.append("%d:=" % k)
        else:
            nclean = sum(1 for i in rng if n["v"][i][1] == n["v"][i][0])
            desc.append("%d:M(%.0e%s)" % (k, mv, ",part %d/%d" % (nclean, len(rng)) if nclean else ""))
    # seam continuity: (true - design) at the seam sample vs the first own sample
    step_o = step_n = 0.0
    for k in range(1, len(n["span"])):
        i0 = n["span"][k][0]
        if i0 < 1 or i0 >= len(n["v"]) or i0 >= len(o["v"]):
            continue
        # skip run heads: a span whose first sample is not a continuation
        # cannot be told from the dump; use the true-speed step itself
        tn = dbl(n["v"][i0][1]) - dbl(n["v"][i0 - 1][1])
        dn = dbl(n["v"][i0][0]) - dbl(n["v"][i0 - 1][0])
        to = dbl(o["v"][i0][1]) - dbl(o["v"][i0 - 1][1])
        do = dbl(o["v"][i0][0]) - dbl(o["v"][i0 - 1][0])
        step_n = max(step_n, abs(tn) if abs(tn) < 5 else 0)
        step_o = max(step_o, abs(to) if abs(to) < 5 else 0)
    flag = ""
    if geom or nvd:
        moved_docs += 1
        flag = "MOVED shift %.3e m (end %.3e) vDesign x%d max %.3e" % (shift, end_shift, nvd, dv)
        if name.endswith("#0") or "#" in name:
            user_moved.append((name, shift, end_shift, nvd, dv))
    print("%-56s %-52s %s %s" % (name[:56], flag, " ".join(desc), "; ".join(notes)))
print("documents", len(order), "| geometry or vDesign moved:", moved_docs)
for u in user_moved:
    print("  USER FILE", u)
