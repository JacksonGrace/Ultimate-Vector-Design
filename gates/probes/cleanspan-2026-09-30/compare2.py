import struct, sys
def dbl(h):
    return struct.unpack(">d", struct.pack(">Q", int(h, 16)))[0]
def parse(path):
    docs = {}; order = []; cur = None
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if line.startswith("DOC "):
            rest = line[4:]
            name = rest[:rest.index(" fric ")] if " fric " in rest else rest
            cur = {"head": rest[len(name):], "sec": [], "piece": [], "span": [], "geom": None, "v": []}
            docs[name] = cur; order.append(name)
            continue
        p = line.split(" ")
        if p[0] == "SEC": cur["sec"].append(line)
        elif p[0] == "PIECE": cur["piece"].append(line)
        elif p[0] == "SPAN": cur["span"].append((int(p[2]), int(p[3])))
        elif p[0] == "GEOM": cur["geom"] = p[1]
        elif p[0] == "V": cur["v"].append((p[2], p[3]))
    return docs, order
old, order = parse(sys.argv[1]); new, _ = parse(sys.argv[2])
bad = 0; nclean = 0; nsame = 0; nmoved = 0
for name in order:
    o, n = old[name], new[name]
    notes = []
    for key, label in (("head", "HEAD"), ("sec", "vDesign/pin"), ("piece", "PLACEMENT"), ("span", "SPANS"), ("geom", "GEOMETRY")):
        if o[key] != n[key]:
            notes.append(label + " DIFFERS"); bad += 1
    if len(o["v"]) != len(n["v"]):
        print("%-60s SAMPLE COUNT DIFFERS %s" % (name[:60], notes)); bad += 1; continue
    desc = []
    for k, (i0, i1) in enumerate(n["span"]):
        rng = range(i0, i1 + 1)
        allc = all(n["v"][i][1] == n["v"][i][0] for i in rng)
        same = all(n["v"][i][1] == o["v"][i][1] for i in rng)
        mv = max(abs(dbl(n["v"][i][1]) - dbl(o["v"][i][1])) for i in rng)
        gap_old = max(abs(dbl(o["v"][i][1]) - dbl(o["v"][i][0])) for i in rng)
        gap_new = max(abs(dbl(n["v"][i][1]) - dbl(n["v"][i][0])) for i in rng)
        if allc:
            nclean += 1
            desc.append("%d:C(%.0e)" % (k, mv))
        elif same:
            nsame += 1
            desc.append("%d:=" % k)
        else:
            nmoved += 1
            desc.append("%d:MOVED %.1e [split %.2e->%.2e]" % (k, mv, gap_old, gap_new))
    print("%-58s %s %s" % (name[:58], " ".join(desc), "; ".join(notes)))
print("documents", len(order), "| spans clean", nclean, "unchanged", nsame, "moved-nonclean", nmoved, "| PROBLEMS", bad)
