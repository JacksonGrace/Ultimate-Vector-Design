# Headless gates (moved into the repo 2026-08-18, D223)

Compile:  javac -cp target/classes -d gates/out gates/*.java
Run:      java -cp "target/classes;gates/out" <Name>      (package-coaster probes: coaster.<Name>)

Core battery (run after ANY compile/reconcile/IO/placement change):
  DetermGate197 DetermGate198 DetermGate199 DetermGate200 LoadGate196
  OrientGate OrientGate2 Phase2Gate Phase3Gate DomainGate
  MultiCoasterGate SmoothRealGate
  + probes named in CLAUDE.md: coaster.DecompProbe InflMatchProbe Nl2ElemProbe

History: these lived only in per-session Temp scratchpads until Windows
Temp cleanup purged them; recovered from the session transcripts with
tools/recover_gates_from_transcripts.py. Phase2Gate is PARTIAL (163 of
the historic 258 checks — the convert/partial-convert/node-split/
connect/speed-reconcile families were authored in a session whose
transcript is gone; see Phase2Gate.lost-edits.txt). attic-noncompiling/
holds old probes against dead APIs, kept for reference.
