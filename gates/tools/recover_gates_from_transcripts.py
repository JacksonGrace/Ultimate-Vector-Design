"""Gate-source recovery from Claude Code transcripts.

Replays, in timestamp order and de-duplicated by tool_use id:
  * Write / Edit / MultiEdit tool calls (any basename -> virtual FS; only
    scratch .java files are emitted)
  * Bash `cat > file <<'EOF'` heredoc writes
  * Bash python heredoc scripts (`python - <<'EOF' ... EOF`) executed against
    the virtual FS with open()/io.open()/pathlib patched
  * Bash `sed -i 's/../../'` one-liners (literal-ish BRE subset)
  * PowerShell `[IO.File]::ReadAllText` + `.Replace('a','b')` + WriteAllText
    edits (single-file `$f = "$sp\X.java"` and `$edits = @(@(...))` forms)
  * Read tool results (toolUseResult.file) as ground-truth snapshots:
    full reads seed / replace, partial reads verify + patch / stitch.
Reports per file: last write, edits replayed / failed, snapshot checks,
shell events, final line count.
"""
import sys, os, json, glob, re, io, builtins, pathlib, contextlib, shlex
sys.stdout.reconfigure(encoding='utf-8')

TRANSCRIPT_DIR = r"C:\Users\jacks\.claude\projects\C--Users-jacks-OneDrive-Documents-AI-Fun-coaster"
OUT_DIR = os.environ.get("GATES_OUT") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "out_faithful")
# NOTE: the live repo folder is C:\Users\jacks\OneDrive\Documents\AI Fun\coaster\gates — pass GATES_OUT to write there.
# Never point this at gates/ while another session is editing those files (it would clobber their edits).
PRIORITY = ["Phase2Gate.java", "Phase3Gate.java", "DomainGate.java", "DetermGate197.java",
            "DetermGate198.java", "DetermGate199.java", "DetermGate200.java", "LoadGate196.java",
            "KnotFixture.java"]
VERBOSE = "-v" in sys.argv

# ----------------------------------------------------------------- collect
events = []            # (ts, seq, kind, payload, sess)
seen_ids = set()
all_java = {}          # basename_lower -> set(paths)
results = {}           # tool_use_id -> (is_error, text)  (what REALLY happened)
seq = 0
files = sorted(glob.glob(os.path.join(TRANSCRIPT_DIR, "*.jsonl")))
for fp in files:
    sess = os.path.basename(fp)[:8]
    with open(fp, encoding='utf-8', errors='replace') as f:
        for line in f:
            if '"tool_use' not in line and 'toolUseResult' not in line:
                continue
            try:
                obj = json.loads(line)
            except Exception:
                continue
            ts = obj.get("timestamp") or ""
            t = obj.get("type")
            msg = obj.get("message") or {}
            content = msg.get("content")
            if t == "user" and isinstance(content, list):
                for item in content:
                    if isinstance(item, dict) and item.get("type") == "tool_result":
                        cont = item.get("content")
                        txt = "".join(x.get("text", "") for x in cont if isinstance(x, dict)) if isinstance(cont, list) else str(cont or "")
                        err = bool(item.get("is_error")) or txt.lstrip().startswith("<tool_use_error>")
                        rid = item.get("tool_use_id")
                        if rid and rid not in results:
                            results[rid] = (err, txt)
            if t == "assistant" and isinstance(content, list):
                for item in content:
                    if not isinstance(item, dict) or item.get("type") != "tool_use":
                        continue
                    tid = item.get("id")
                    if tid in seen_ids:
                        continue
                    seen_ids.add(tid)
                    name = item.get("name")
                    inp = item.get("input") or {}
                    seq += 1
                    if name in ("Write", "Edit", "MultiEdit"):
                        fpath = inp.get("file_path") or ""
                        base = os.path.basename(fpath.replace("\\", "/"))
                        bl = base.lower()
                        if bl.endswith(".java"):
                            all_java.setdefault(bl, set()).add(fpath)
                        events.append((ts, seq, name, (base, inp, tid), sess))
                    elif name in ("Bash", "PowerShell"):
                        cmd = inp.get("command") or ""
                        events.append((ts, seq, name, (cmd, tid), sess))
            elif t == "user":
                tur = obj.get("toolUseResult")
                if isinstance(tur, dict) and isinstance(tur.get("file"), dict):
                    fi = tur["file"]
                    fpath = fi.get("filePath") or ""
                    base = os.path.basename(fpath.replace("\\", "/"))
                    if isinstance(fi.get("content"), str):
                        # dedupe read results by tool_use_id
                        rid = None
                        if isinstance(content, list):
                            for item in content:
                                if isinstance(item, dict) and item.get("type") == "tool_result":
                                    rid = item.get("tool_use_id")
                        key = ("R", rid)
                        if rid and key in seen_ids:
                            continue
                        seen_ids.add(key)
                        seq += 1
                        events.append((ts, seq, "READ", (base, fi), sess))

def is_scratch(paths):
    for p in paths:
        pl = p.replace("\\", "/").lower()
        if "src/main/java/" in pl or "src/test/java/" in pl or "/coaster/gates/" in pl:
            return False
    return True

targets = {bl for bl, paths in all_java.items() if is_scratch(paths)}
for p in PRIORITY:
    targets.add(p.lower())
events.sort(key=lambda e: (e[0], e[1]))

# ----------------------------------------------------------------- state
vfs = {}      # basename_lower -> content (all files ever written)
fragments = {}  # basename_lower -> [(ts, kind, text)] edits that could NOT be placed
canon = {}    # basename_lower -> canonical basename
stats = {}
log = {}
pending_partial = {}

def st(bl):
    return stats.setdefault(bl, {"last_write": None, "writes": 0, "edits": 0, "edits_failed": 0,
                                 "multiedits": 0, "read_full": 0, "read_partial": 0,
                                 "read_div_full": 0, "read_patch": 0, "read_stitch": 0,
                                 "shell_ok": 0, "shell_fail": 0, "shell_unreplayed": 0,
                                 "last_event": None, "read_verified": 0})

def L(bl, msg):
    log.setdefault(bl, []).append(msg)

def key_of(path):
    return os.path.basename(str(path).replace("\\", "/")).lower()

# ---------------------------------------------------- python heredoc runner
HEREDOC_RE = re.compile(
    r"python3?\s+-(?P<args>[^\n<]*)<<\s*-?\s*['\"]?(?P<tag>[A-Za-z_][A-Za-z0-9_]*)['\"]?[ \t]*\n(?P<body>.*?)\n(?P=tag)[ \t]*(?=\n|$)",
    re.S)
CAT_RE = re.compile(
    r"cat\s*>\s*(?P<path>\"[^\"\n]+\"|'[^'\n]+'|\S+)\s*<<\s*-?\s*['\"]?(?P<tag>[A-Za-z_][A-Za-z0-9_]*)['\"]?[ \t]*\n(?P<body>.*?)\n(?P=tag)[ \t]*(?=\n|$)",
    re.S)
CAT_RE2 = re.compile(
    r"cat\s*<<\s*-?\s*['\"]?(?P<tag>[A-Za-z_][A-Za-z0-9_]*)['\"]?\s*>\s*(?P<path>\"[^\"\n]+\"|'[^'\n]+'|\S+)[ \t]*\n(?P<body>.*?)\n(?P=tag)[ \t]*(?=\n|$)",
    re.S)
DANGER = re.compile(r"subprocess|shutil|os\.remove|os\.unlink|os\.system|os\.rename|rmtree|os\.chdir|os\.makedirs|urllib|requests")

class VWrite:
    def __init__(self, key, append):
        self.key = key
        if not append or key not in vfs:
            vfs[key] = ""
    def write(self, s):
        vfs[self.key] = vfs.get(self.key, "") + s
        return len(s)
    def writelines(self, ls):
        for l in ls:
            self.write(l)
    def close(self):
        pass
    def flush(self):
        pass
    def __enter__(self):
        return self
    def __exit__(self, *a):
        return False

class VRead(io.StringIO):
    def __enter__(self):
        return self
    def __exit__(self, *a):
        return False

import ast, glob as _glob, fnmatch

def tolerant_transform(code):
    """Wrap every statement inside loop bodies in try/except -> continue, so a
    failing item of a `for old, new in [...]` batch skips just that item."""
    tree = ast.parse(code)
    class T(ast.NodeTransformer):
        def _wrap(self, stmts):
            out = []
            for s in stmts:
                s = self.visit(s)
                if isinstance(s, (ast.Break, ast.Continue, ast.Return)):
                    out.append(s); continue
                out.append(ast.Try(body=[s], handlers=[ast.ExceptHandler(type=ast.Name("Exception", ast.Load()), name=None,
                                                                          body=[ast.Continue()])], orelse=[], finalbody=[]))
            return out
        def visit_For(self, node):
            node.body = self._wrap(node.body); return node
        def visit_While(self, node):
            node.body = self._wrap(node.body); return node
    tree = T().visit(tree)
    ast.fix_missing_locations(tree)
    return compile(tree, "<heredoc-tolerant>", "exec")

class _Out(io.StringIO):
    def reconfigure(self, *a, **k):
        pass

def run_python(code, argv, ts, sess, touched_targets, tolerant=False):
    """Execute a heredoc python script against the virtual FS. Returns (ok, note)."""
    if DANGER.search(code):
        return False, "skipped: dangerous ops"
    missing = set()
    written = set()
    def v_glob(pattern, *a, **k):
        base = os.path.basename(str(pattern).replace("\\", "/")).lower()
        return [k2 for k2 in sorted(vfs) if fnmatch.fnmatch(k2, base)]
    def v_open(p, mode="r", *a, **k):
        key = key_of(p)
        if "w" in mode or "a" in mode or "+" in mode:
            written.add(key)
            return VWrite(key, "a" in mode)
        if key in vfs:
            return VRead(vfs[key])
        missing.add(key)
        return VRead("")
    def p_read_text(self, *a, **k):
        key = key_of(self)
        if key in vfs:
            return vfs[key]
        missing.add(key)
        return ""
    def p_write_text(self, data, *a, **k):
        key = key_of(self)
        written.add(key)
        vfs[key] = data
        return len(data)
    def p_exists(self):
        return key_of(self) in vfs
    def p_open(self, mode="r", *a, **k):
        return v_open(str(self), mode)
    def p_read_bytes(self):
        key = key_of(self)
        if key in vfs:
            return vfs[key].encode("utf-8")
        missing.add(key)
        return b""
    def p_write_bytes(self, data):
        key = key_of(self)
        written.add(key)
        vfs[key] = data.decode("utf-8", "replace")
        return len(data)
    def os_exists(p):
        return key_of(p) in vfs
    saved = (builtins.open, io.open, pathlib.Path.read_text, pathlib.Path.write_text, pathlib.Path.exists,
             pathlib.Path.open, pathlib.Path.read_bytes, pathlib.Path.write_bytes, os.path.exists, os.path.isfile,
             sys.argv, sys.stdout, sys.stderr, _glob.glob)
    out = _Out()
    ok, note = True, ""
    try:
        builtins.open = v_open; io.open = v_open
        pathlib.Path.read_text = p_read_text; pathlib.Path.write_text = p_write_text
        pathlib.Path.exists = p_exists; pathlib.Path.open = p_open
        pathlib.Path.read_bytes = p_read_bytes; pathlib.Path.write_bytes = p_write_bytes
        os.path.exists = os_exists; os.path.isfile = os_exists
        _glob.glob = v_glob
        sys.argv = ["-"] + argv
        sys.stdout = out; sys.stderr = out
        g = {"__name__": "__main__"}
        cobj = tolerant_transform(code) if tolerant else compile(code, "<heredoc>", "exec")
        exec(cobj, g)
    except SystemExit as e:
        if e.code not in (0, None):
            ok, note = False, f"SystemExit({e.code})"
    except BaseException as e:
        ok, note = False, f"{type(e).__name__}: {str(e)[:160]}"
    finally:
        (builtins.open, io.open, pathlib.Path.read_text, pathlib.Path.write_text, pathlib.Path.exists,
         pathlib.Path.open, pathlib.Path.read_bytes, pathlib.Path.write_bytes, os.path.exists, os.path.isfile,
         sys.argv, sys.stdout, sys.stderr, _glob.glob) = saved
    o = out.getvalue().strip().replace("\n", " | ")
    if missing:
        note += (" " if note else "") + "missing-reads=" + ",".join(sorted(missing))
    if o:
        note += (" " if note else "") + "out=" + o[:200]
    touched_targets.update(k for k in written if k in targets)
    return ok, note

def heredoc_argv(argstr):
    try:
        toks = shlex.split(argstr) if argstr.strip() else []
    except ValueError:
        toks = argstr.split()
    return [t.replace("$SCRATCH", "/scratch").replace("$SP", "/scratch") for t in toks]

def extract_python_c(cmd):
    """Yield python source strings from `python -c "..."` / `python -c '...'` invocations."""
    for m in re.finditer(r"python3?\s+-c\s+", cmd):
        i = m.end()
        if i >= len(cmd):
            continue
        q = cmd[i]
        if q == '"':
            j = i + 1; buf = []
            while j < len(cmd):
                c = cmd[j]
                if c == "\\" and j + 1 < len(cmd) and cmd[j + 1] in '"\\$`':
                    buf.append(cmd[j + 1]); j += 2; continue
                if c == '"':
                    break
                buf.append(c); j += 1
            src = "".join(buf)
            src = re.sub(r"\$\{?(SP|SCRATCH|SPW|SCR|DST)\}?", "/scratch", src)
            yield src
        elif q == "'":
            j = cmd.find("'", i + 1)
            if j > 0:
                yield cmd[i + 1:j]

def run_python_with_retry(code, argv, ts, sess, touched):
    snap = dict(vfs)
    ok, note = run_python(code, argv, ts, sess, touched)
    if ok:
        return "python OK" + (": " + note if note else "")
    # restore and retry tolerant (loop items that fail are skipped)
    strict_state = dict(vfs)
    vfs.clear(); vfs.update(snap)
    touched2 = set()
    ok2, note2 = run_python(code, argv, ts, sess, touched2, tolerant=True)
    if ok2:
        touched.update(touched2)
        return f"python FAILED strict ({note}); TOLERANT re-run OK" + (": " + note2 if note2 else "")
    vfs.clear(); vfs.update(strict_state)   # keep what the strict run wrote before it died
    return f"python FAILED: {note} (tolerant also failed: {note2})"

# ---------------------------------------------------- sed -i runner
SED_RE = re.compile(r"sed\s+-i\s+(?:-e\s+)?(['\"])(.+?)\1\s+(\"[^\"\n]+\"|'[^'\n]+'|\S+)", re.S)

def bre_to_py(pat):
    # minimal BRE -> python regex: \( \) groups, escaped punctuation literal, others escaped as needed
    out = []
    i = 0
    while i < len(pat):
        c = pat[i]
        if c == "\\" and i + 1 < len(pat):
            n = pat[i + 1]
            if n in "()|+?{}":
                out.append(n)            # BRE-escaped = regex-special in python
            elif n == "&":
                out.append("&")
            elif n == "n":
                out.append("\\n")
            else:
                out.append(re.escape(n))  # \. \[ \] \/ \| ... literal
            i += 2
            continue
        if c in "()|+?{}":
            out.append(re.escape(c))
        elif c == "[":
            j = pat.find("]", i + 1)
            if j < 0:
                out.append(re.escape(c))
            else:
                out.append(pat[i:j + 1]); i = j
        else:
            out.append(c)
        i += 1
    return "".join(out)

def sed_repl_to_py(rep):
    """sed replacement -> python callable (handles &, \\&, \\N, \\n)."""
    segs = []   # ("lit", text) | ("grp", n)
    i = 0
    lit = ""
    while i < len(rep):
        c = rep[i]
        if c == "\\" and i + 1 < len(rep):
            n = rep[i + 1]
            if n == "&":
                lit += "&"
            elif n.isdigit():
                segs.append(("lit", lit)); lit = ""; segs.append(("grp", int(n)))
            elif n == "n":
                lit += "\n"
            else:
                lit += n
            i += 2
            continue
        if c == "&":
            segs.append(("lit", lit)); lit = ""; segs.append(("grp", 0))
        else:
            lit += c
        i += 1
    segs.append(("lit", lit))
    def fn(m):
        return "".join(t if k == "lit" else (m.group(t) or "") for k, t in segs)
    return fn

def run_sed(cmd, ts, sess, touched_targets):
    notes = []
    for m in SED_RE.finditer(cmd):
        expr, fpath = m.group(2), m.group(3).strip("\"'")
        key = key_of(fpath)
        if key not in targets:
            continue
        if not expr.startswith("s"):
            notes.append(f"sed unsupported expr on {key}: {expr[:60]}"); continue
        d = expr[1]
        parts = []
        cur = ""
        i = 2
        while i < len(expr):
            c = expr[i]
            if c == "\\" and i + 1 < len(expr):
                cur += c + expr[i + 1]; i += 2; continue
            if c == d:
                parts.append(cur); cur = ""
            else:
                cur += c
            i += 1
        parts.append(cur)
        if len(parts) < 3:
            notes.append(f"sed parse fail on {key}"); continue
        pat, rep, flags = parts[0], parts[1], parts[2]
        if key not in vfs:
            notes.append(f"sed on {key}: no content yet"); continue
        try:
            rx = re.compile(bre_to_py(pat))
        except re.error as e:
            notes.append(f"sed regex error on {key}: {e}"); continue
        pyrep = sed_repl_to_py(rep)
        count = 0 if "g" in flags else 1
        # apply per line like sed
        lines = vfs[key].split("\n")
        n = 0
        newlines = []
        for ln in lines:
            nl, k = rx.subn(pyrep, ln, count=count)
            n += k
            newlines.append(nl)
        vfs[key] = "\n".join(newlines)
        touched_targets.add(key)
        notes.append(f"sed on {key}: {n} substitution(s)")
    return notes

# ---------------------------------------------------- PowerShell .Replace runner
def ps_unquote(s):
    return s[1:-1].replace("''", "'")

PS_STR = r"'(?:[^']|'')*'"
PS_EDITS_RE = re.compile(r"@\(\s*(" + PS_STR + r")\s*,\s*(" + PS_STR + r")\s*,\s*(" + PS_STR + r")\s*\)", re.S)
PS_F_RE = re.compile(r"\$f\s*=\s*\"\$sp\\([A-Za-z0-9_]+\.java)\"")
PS_REPL_RE = re.compile(r"\.Replace\(\s*(" + PS_STR + r")\s*,\s*(" + PS_STR + r")\s*\)", re.S)

def run_powershell(cmd, ts, sess, touched_targets):
    notes = []
    if "WriteAllText" not in cmd:
        return notes
    if "$edits" in cmd and PS_EDITS_RE.search(cmd):
        for m in PS_EDITS_RE.finditer(cmd):
            fn, old, new = ps_unquote(m.group(1)), ps_unquote(m.group(2)), ps_unquote(m.group(3))
            key = key_of(fn)
            if key not in targets:
                continue
            if key not in vfs:
                notes.append(f"ps edit on {key}: no content"); continue
            if old in vfs[key]:
                vfs[key] = vfs[key].replace(old, new); touched_targets.add(key)
                notes.append(f"ps edit {key}: replaced {old[:40]!r}")
            else:
                notes.append(f"ps edit {key}: MISS {old[:40]!r}")
        return notes
    fm = PS_F_RE.search(cmd)
    if fm:
        key = key_of(fm.group(1))
        if key in targets:
            if key not in vfs:
                notes.append(f"ps edit on {key}: no content"); return notes
            for m in PS_REPL_RE.finditer(cmd):
                old, new = ps_unquote(m.group(1)), ps_unquote(m.group(2))
                if old in vfs[key]:
                    vfs[key] = vfs[key].replace(old, new); touched_targets.add(key)
                    notes.append(f"ps edit {key}: replaced {old[:40]!r}")
                else:
                    notes.append(f"ps edit {key}: MISS {old[:40]!r}")
    return notes

# ---------------------------------------------------- replay
strong_kw = re.compile(r"WriteAllText|Set-Content|Out-File|Add-Content|sed\s+-i|python3?\s+-\s|python3?\s*<<|-replace|\.replace\(|write_text|open\([^)]*['\"]w|cat\s*>", re.I)

for ts, sq, kind, payload, sess in events:
    if kind in ("Bash", "PowerShell"):
        cmd, tid = payload
        mentioned = {tbl for tbl in targets if re.search(r"(?<![\w])" + re.escape(tbl[:-5]) + r"(?![\w])", cmd, re.I)}
        if not mentioned:
            continue
        real_err, real_txt = results.get(tid, (False, ""))
        real_traceback = bool(re.search(r"Traceback \(most recent call last\)|^\s*(AssertionError|ValueError|KeyError|IndexError|FileNotFoundError|NameError|SyntaxError)\b|Python was not found|python3?: command not found", real_txt, re.M))
        touched = set()
        notes = []
        pre_snapshot = dict(vfs)
        if kind == "Bash":
            # cat heredocs
            for m in list(CAT_RE.finditer(cmd)) + list(CAT_RE2.finditer(cmd)):
                key = key_of(m.group("path").strip("\"'"))
                vfs[key] = m.group("body") + "\n"
                if key in targets:
                    touched.add(key); notes.append(f"cat-heredoc WRITE {key}")
                    s = st(key); s["writes"] += 1; s["last_write"] = ts
            scripts = [(m.group("body"), heredoc_argv(m.group("args"))) for m in HEREDOC_RE.finditer(cmd)]
            scripts += [(src, []) for src in extract_python_c(cmd)]
            for code, argv in scripts:
                if not any(re.search(r"(?<![\w])" + re.escape(tbl[:-5]) + r"(?![\w])", code, re.I) for tbl in targets):
                    continue
                if real_traceback:
                    # the script died in reality (its writes sit after the asserts): no effect
                    err_line = next((l for l in real_txt.splitlines() if re.match(r"\s*\w*(Error|Exception)|Python was not found", l)), "")[:120]
                    notes.append(f"python SKIPPED — failed in reality: {err_line}")
                    continue
                res = run_python_with_retry(code, argv, ts, sess, touched)
                notes.append(res)
                ok = not res.startswith("python FAILED:")
                for tbl in mentioned:
                    if tbl[:-5] in code.lower():
                        st(tbl)["shell_ok" if ok else "shell_fail"] += 1
                        if not ok and re.search(re.escape(tbl), code, re.I):   # the script names the FILE
                            fragments.setdefault(tbl, []).append((ts, "python script (failed: " + res[:120] + ")", code))
            if "sed -i" in cmd:
                sn = run_sed(cmd, ts, sess, touched)
                notes += sn
        else:
            notes += run_powershell(cmd, ts, sess, touched)
        # unreplayed strong rewrite?
        docs_only = bool(re.search(r"(docs[\\/](DECISIONS|TESTING|SECTIONS-DESIGN|PHYSICS)\.md|CLAUDE\.md|memory[\\/]|\.claude[\\/]projects)", cmd)) \
            and not re.search(r"scratchpad", cmd)
        if not notes and strong_kw.search(cmd) and not docs_only and not re.search(r"^\s*(cd\s+\S+\s*&&\s*)?(cp|copy)\b", cmd) :
            for tbl in mentioned:
                st(tbl)["shell_unreplayed"] += 1
                L(tbl, f"{ts} SHELL[{kind}] NOT REPLAYED ({sess}): " + cmd[:220].replace("\n", " | "))
        for tbl in (touched | (mentioned if notes else set())):
            for n in notes:
                L(tbl, f"{ts} SHELL[{kind}] ({sess}) {n}")
            st(tbl)["last_event"] = ts
        continue

    base = payload[0]
    bl = base.lower()
    if kind in ("Write", "Edit", "MultiEdit"):
        real_err, real_txt = results.get(payload[2], (False, ""))
        if real_err:
            if bl in targets:
                canon.setdefault(bl, base)
                st(bl)["edits_skipped_real_error"] = st(bl).get("edits_skipped_real_error", 0) + 1
                L(bl, f"{ts} {kind.upper()} SKIPPED — failed in reality ({sess}): {real_txt.strip()[:100]!r}")
            continue
    if kind == "Write":
        vfs[bl] = payload[1].get("content") or ""
        if bl in targets:
            canon.setdefault(bl, base)
            s = st(bl); s["last_write"] = ts; s["writes"] += 1; s["last_event"] = ts
            pending_partial.pop(bl, None)
        continue
    if bl not in targets:
        continue
    canon.setdefault(bl, base)
    s = st(bl); s["last_event"] = ts
    if kind == "Edit":
        inp = payload[1]
        old = inp.get("old_string") or ""; new = inp.get("new_string") or ""
        ra = bool(inp.get("replace_all"))
        cur = vfs.get(bl)
        if cur is None:
            L(bl, f"{ts} EDIT before any known content ({sess}): {old[:80]!r}"); s["edits_failed"] += 1; continue
        if old not in cur:
            L(bl, f"{ts} EDIT old_string NOT FOUND ({sess}): {old[:80]!r}"); s["edits_failed"] += 1
            fragments.setdefault(bl, []).append((ts, "Edit", f"OLD (anchor, not found):\n{old}\n\nNEW:\n{new}"))
            continue
        vfs[bl] = cur.replace(old, new) if ra else cur.replace(old, new, 1)
        s["edits"] += 1
        pending_partial.pop(bl, None)
    elif kind == "MultiEdit":
        inp = payload[1]
        cur = vfs.get(bl)
        if cur is None:
            L(bl, f"{ts} MULTIEDIT before any known content"); s["edits_failed"] += 1; continue
        for ed in inp.get("edits") or []:
            old = ed.get("old_string") or ""; new = ed.get("new_string") or ""
            ra = bool(ed.get("replace_all"))
            if old not in cur:
                L(bl, f"{ts} MULTIEDIT old_string NOT FOUND ({sess}): {old[:80]!r}"); s["edits_failed"] += 1; continue
            cur = cur.replace(old, new) if ra else cur.replace(old, new, 1)
            s["multiedits"] += 1
        vfs[bl] = cur
        pending_partial.pop(bl, None)
    elif kind == "READ":
        fi = payload[1]
        cont = fi["content"]; start = int(fi.get("startLine") or 1); num = int(fi.get("numLines") or 0)
        total = int(fi.get("totalLines") or 0)
        rlines = cont.split("\n")
        if len(rlines) == num + 1 and rlines[-1] == "":
            rlines = rlines[:-1]
        cur = vfs.get(bl)
        full = (start == 1 and num >= total)
        # NOTE: the Read tool counts a trailing empty line (file ending in "\n" -> last line ""),
        # so `content.split("\n")` of the replayed text has exactly `total` elements.
        if full:
            s["read_full"] += 1
            snap = "\n".join(rlines)
            if cur is None:
                vfs[bl] = snap; L(bl, f"{ts} READ(full) seeded content ({total} lines) ({sess})")
            elif cur.rstrip("\n") != snap.rstrip("\n"):
                s["read_div_full"] += 1
                L(bl, f"{ts} READ(full) DIVERGED from replayed state (replayed {len(cur.split(chr(10)))} lines vs read {total}); adopting snapshot ({sess})")
                vfs[bl] = snap
            else:
                s["read_verified"] += 1
            pending_partial.pop(bl, None)
        else:
            s["read_partial"] += 1
            if cur is not None:
                cl = cur.split("\n")
                if len(cl) == total:
                    seg = cl[start - 1:start - 1 + num]
                    if seg != rlines[:len(seg)]:
                        cl[start - 1:start - 1 + num] = rlines
                        vfs[bl] = "\n".join(cl)
                        s["read_patch"] += 1
                        L(bl, f"{ts} READ(partial {start}..{start+num-1}/{total}) differed from replayed state; patched slice ({sess})")
                    else:
                        s["read_verified"] += 1
                        if VERBOSE:
                            L(bl, f"{ts} READ(partial {start}..{start+num-1}/{total}) verified ({sess})")
                    continue
                else:
                    # diagnose: where do the read lines sit in the replayed text?
                    diag = ""
                    patched_region = False
                    if rlines:
                        cands = [i for i, l in enumerate(cl) if l == rlines[0]]
                        if cands:
                            best = min(cands, key=lambda i: abs(i - (start - 1)))
                            off = best - (start - 1)
                            mism = next((k for k in range(min(len(rlines), len(cl) - best)) if cl[best + k] != rlines[k]), None)
                            diag = f" first-line offset {off:+d}" + (f", first differing line at read#{start+mism}: replayed {cl[best+mism][:60]!r} vs read {rlines[mism][:60]!r}" if mism is not None else ", slice otherwise identical")
                            if mism is not None and len(rlines) >= 3:
                                # ANCHORED REGION PATCH: locate the read's last non-blank line (+ its successor)
                                # after `best` within a generous window, then replace the replayed region with
                                # the read slice (recovers lost insertions between two known anchors).
                                k = len(rlines) - 1
                                while k > mism and not rlines[k].strip():
                                    k -= 1
                                tail_n = len(rlines) - k          # lines from anchor k to the end of the slice
                                def match_at(j):
                                    if cl[j] != rlines[k]:
                                        return False
                                    if k + 1 < len(rlines):
                                        return j + 1 < len(cl) and cl[j + 1] == rlines[k + 1]
                                    return True
                                ends = [j for j in range(best + 1, min(len(cl), best + len(rlines) + 600)) if match_at(j)]
                                if ends and k > mism and rlines[k].strip():
                                    j = min(ends, key=lambda j: abs((j + tail_n - best) - len(rlines)))
                                    cl[best:j + tail_n] = rlines
                                    vfs[bl] = "\n".join(cl)
                                    s["read_patch"] += 1
                                    patched_region = True
                                    diag += f" -> REGION-PATCHED replayed lines {best+1}..{j+tail_n} with the read slice (now {len(cl)} lines)"
                        else:
                            diag = " first read line not found in replayed text"
                    L(bl, f"{ts} READ(partial {start}..{start+num-1}/{total}) line-count MISMATCH: replayed has {len(cl) if not patched_region else len(cur.split(chr(10)))} lines ({sess}){diag}")
                    if VERBOSE:
                        dbg = os.path.join(os.path.dirname(os.path.abspath(__file__)), "debug")
                        os.makedirs(dbg, exist_ok=True)
                        tag = ts.replace(":", "").replace("-", "")[:15]
                        with open(os.path.join(dbg, f"{bl}_{tag}_read.txt"), "w", encoding="utf-8") as f:
                            f.write("\n".join(rlines))
                        with open(os.path.join(dbg, f"{bl}_{tag}_state.txt"), "w", encoding="utf-8") as f:
                            f.write(cur)
                    if patched_region:
                        continue
            pp = pending_partial.setdefault(bl, [])
            if pp and pp[0][2] != total:
                pp.clear()
            pp.append((start, num, total, rlines))
            cov = [None] * total
            for (a, n, tt, ls) in pp:
                for i, ln in enumerate(ls):
                    if a - 1 + i < total:
                        cov[a - 1 + i] = ln
            if total > 0 and all(x is not None for x in cov):
                snap = "\n".join(cov)
                if cur is None or cur.rstrip("\n") != snap.rstrip("\n"):
                    vfs[bl] = snap
                    s["read_stitch"] += 1
                    L(bl, f"{ts} READ stitched {len(pp)} partial reads into a full {total}-line snapshot" + ("" if cur is None else " (replaced diverged state)") + f" ({sess})")
                pending_partial.pop(bl, None)

# ---------------------------------------------------- emit
os.makedirs(OUT_DIR, exist_ok=True)
print("=== Replay results ===")
summary = []
for bl in sorted(targets):
    name = canon.get(bl, bl)
    if bl not in vfs:
        print(f"{name}: NO CONTENT RECOVERED")
        for m in log.get(bl, []):
            print("   ", m)
        continue
    content = vfs[bl]
    lines = content.split("\n")
    kept = [l for l in lines if "nodesDirty" not in l]
    removed = len(lines) - len(kept)
    if removed:
        content = "\n".join(kept)
    if not content.endswith("\n"):
        content += "\n"
    stem = name[:-5]
    if not re.search(r"\bclass\s+" + re.escape(stem) + r"\b", content):
        print(f"{name}: SKIPPED (not a standalone class file — code fragment), {len(content.splitlines())} lines")
        continue
    with open(os.path.join(OUT_DIR, name), "w", encoding="utf-8", newline="\n") as f:
        f.write(content)
    s = stats.get(bl) or st(bl)
    print(f"{name}: lastWrite={s['last_write']} writes={s['writes']} edits={s['edits']}+{s['multiedits']}me failedEdits={s['edits_failed']} skippedRealErr={s.get('edits_skipped_real_error',0)} "
          f"readVerified={s['read_verified']} readFull={s['read_full']} readPartial={s['read_partial']} divFull={s['read_div_full']} patched={s['read_patch']} stitched={s['read_stitch']} "
          f"shellOK={s['shell_ok']} shellFail={s['shell_fail']} shellUnreplayed={s['shell_unreplayed']} lastEvent={s['last_event']} lines={len(content.splitlines())} nodesDirtyRemoved={removed}")
    for m in log.get(bl, []):
        print("   ", m)
    if bl in {p.lower() for p in PRIORITY} and fragments.get(bl):
        fpath = os.path.join(OUT_DIR, stem + ".lost-edits.txt")
        with open(fpath, "w", encoding="utf-8", newline="\n") as f:
            f.write(f"UNPLACEABLE EDITS for {name} — recovered from the session transcripts but their\n"
                    f"anchor text was NOT present in the replayed file (the anchor came from a session whose\n"
                    f"transcript no longer exists). Listed chronologically; hand-merge if wanted.\n\n")
            for ts_, kind_, text_ in fragments[bl]:
                f.write("=" * 78 + f"\n{ts_}  {kind_}\n" + "=" * 78 + "\n" + text_.rstrip("\n") + "\n\n")
        print(f"    -> {len(fragments[bl])} unplaceable edit(s) written to {fpath}")
