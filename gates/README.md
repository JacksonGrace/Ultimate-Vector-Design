# Headless gates (moved into the repo 2026-08-18, D223)

Compile:  javac -cp target/classes -d gates/out gates/*.java
Run:      java -cp "target/classes;gates/out" <Name>      (package-uvd probes: uvd.<Name>; package coaster → uvd in v19.21, D250)

Core battery (run after ANY compile/reconcile/IO/placement change —
D257 (batch 39) added no member; it REMOVED the translational smoothing mode — SmoothRealGate 24 (section d = d1 the recorded closure
checksums + d2 the make-real round trip), LabelGate 182 (section 17 = 13 checks) —, grew Phase3Gate to 88 PASS lines (55k/55l, the
held-control knife edge closed) and RE-PINNED Nl2ElemProbe (22 checks, count unchanged); all green on wiped classes 2026-10-13; the
recorded clean-span dump (gates/probes/cleanspan-2026-09-30) is batch 39's new.txt (54 documents, the two held-ACCEL files moved);
LabelGate + uvd.ConflictGate + SmoothRealGate re-run on the merged tree as the docs belt ("LabelGate: 182 passed, 0 failed" /
"ALL CONFLICT GATES PASS (87)" / "ALL SMOOTH-REAL GATES PASS (24)").
D256 (batch 38) added TWO members — uvd.KeyRouteGate (103 checks; 131 with the JavaFX module path) and TypedMarkGate (42) — and grew
three: LabelGate 190, uvd.ConflictGate 87, SmoothRealGate 77 — see their entries; all green on wiped classes 2026-10-12.
D255 (batch 37) added no member but grew seven: GestureGate 180, FloorGate 34, LabelGate 129, uvd.ConflictGate 51, RangeResolveGate 115,
MirrorGate 100, RunGate 78 — see their entries; all green on wiped classes 2026-09-30. Compile the gates with `-encoding UTF-8`.
(Nl2Check, a recovered pre-D184 script, is NOT a battery member: it fails 158 sign checks by design of the later lateral / yaw flips.)
39 members since D259.1b (uvd.ScRollLaneGate 70 — the roll lane per s/c element: lane / integrator / cut-shadow (e7 + e10
re-pinned to his note 1's "the graph never changes on growth", e11 scaleValues) / bake / IO rows + q1-q4 the KINKS (an absolute
element border / a plain boundary into an absolute lane = a declared kink, Pass A one-sided, the roll rate finite); uvd.ScSeamGate
37 — the straight/curve seam invariant: automatic followers == the in-piece twin bitwise (a-c; c4 behind a CONFLICT-TILTED upstream to
1e-9 m with the inherited tilt), smoothing's dry run (e), the k == 0 real anchor (f), the L4 refusals (g), one compile per '+' (h),
the forward align (i*), freeze / unlock (j/k), the seam ball (l), copy / mirror (o); grew LabelGate 229 (section 20 = §3.10's
sentences + 19k/19l), uvd.ConflictGate 90 (m1/m2, i7 + l14a re-pinned), InsertGate 45 (d3 re-pinned, d7 generalized, d8/d9),
RunGate 82 (l1-l4), CopyGate 74 (k1); PlacementGate c1 / OrientModeGate c1 re-pinned to the 6-field `porient` line; DetermGate198 d,
Phase2Gate scRoundTrip, ScElemGate g/k build their s/c through Coaster.appendSectionWithInvariant (the loader's invariant pass cuts
an in-piece s/c); all 39 + Nl2ElemProbe green on wiped classes 2026-10-21); 37 since D259.1a (uvd.ScElemGate new — v20.0, the s/c element model; Phase2Gate's
clamped-blend bound RE-PINNED 1e-6° → 1e-5° (2.96e-6° at dt 0.01 from the new border-centre landing, 0 at dt ≤ 0.005
— BlendTurnProbe) and RangeResolveGate's g2 hinge RE-PINNED to a DISTANCE °/m geo section with g2c at 2e-4 (an s/c is
gravity-referenced under D2, no longer frame-independent); all 37 + Nl2ElemProbe green on wiped classes 2026-10-21);
36 since D256 (uvd.KeyRouteGate + TypedMarkGate new); 34 since D254 (uvd.PulseClockGate new — 30 checks, the POV/fly ride clock: the even-window median + bounded drift, hitches whole,
the hitch threshold's nominal floor, the drift surviving a hitch; GestureGate grew to 115 with section 10 = Gestures.slowDebt, the gizmo
Shift law; LabelGate to 111 with section 12 = File > Rename…'s name refusals); 33 since D249
(OrientModeGate new; 32 since D248: StripGate new, ConflictGate extended by item S3), all green on wiped classes 2026-09-29):
  uvd.ScElemGate  (D259.1a, v20.0 — the STRAIGHT/CURVE ELEMENT MODEL: the
                  curvature-defined Straight / Planar / Helical elements on the
                  ROLL-FREE axis. a1 ElementChain.advance vs the integrator over
                  kind × dir {0,30,45,90,210} × entry {level, pitched 20°, banked
                  30°} × κ × helix pitch pairs + a 450° lap — pos ≤ 1e-7 m, T/U ≤
                  1e-6°, every PLANAR sample on the analytic circle ≤ 1e-7 m
                  (worst 9.1e-9; the old body-axis law dove 10.9 m on the banked
                  R20/90°/30° turn); a2 κ = 0 == straight 1e-12; b helix closed
                  forms vs a 64-panel GL-8 quadrature 1e-12 + the inverse identity;
                  c constant-κ SPLIT BITWISE (Coaster.cutElement: L1 + L2 == L, the
                  right half's dir re-based, the equal-axis merge) for planar,
                  constant-pitch helix and straight, ramped 5.3e-13 m; d equal-κ
                  merge bitwise / blend or differing dir = a landed kink; e Bound
                  records (compile twice bitwise, exit == last sample, landed vs
                  sharp 1e-7, dt-independence, merged vs an un-merged twin
                  2.2e-10); f the `el` grammar round trip byte-identical + load →
                  rebuild bitwise + a clamp sweep; g old `elem` lines best-effort
                  (legacyScSections 2, SC_LEGACY_CONVERTED); h the LEGACY switch —
                  a FILE comparison: gates/probes/legacy-axis-2026-10-21/
                  new-legacy-on.txt (LegacyAxisProbe under -Dfvd.sc.legacyAxis=1)
                  == legacy-checksums.txt recorded on bfc70fd, 56 lines BITWISE,
                  new-legacy-off.txt within 1e-9 m on the level cardinal fixtures;
                  i vertical entry (inherited plane, changeKind(HELICAL) refuses,
                  HELIX_VERTICAL_WHY); j mirror == the z-negated samples bitwise,
                  mirror² identity; k determinism (compile twice, rebuild no-op,
                  x/y/z + yaw rigid, save → load → rebuild). 122 checks. RE-RUN the
                  LegacyAxisProbe pair before it after any ScIntegrator edit.)
  DetermGate197 DetermGate198 DetermGate199 DetermGate200 LoadGate196
  OrientGate OrientGate2 Phase2Gate DomainGate
  Phase3Gate  (Phase 3 — friction + speed controls; + D257 55k/55l — the
                          held-ACCEL DIVE fixture (geo pitch −50 °/s for 1 s
                          then a straight ~55° drop, 90 m start, 10 m/s;
                          target 20, 0.5 g tied, both flags, 1.5 s to the
                          5 s end): 55k every design AND real sample within
                          EPS of the target from the first at-target sample
                          to the control's end, ≥ 1 held sample one ulp
                          ABOVE it (the old sign test's "not crossed" step),
                          end 20.000000000 — the old build leaked 312 of 333
                          samples, 9.8 m/s; 55l the same fixture min-only
                          runs free to 46.8 (floor semantics kept); 88 PASS
                          lines)
  MultiCoasterGate SmoothRealGate  (SmoothRealGate: D232 — Make smoothing
                          real + the s/c entry ease, 15 checks; + section d
                          since D256, REDUCED in D257 (the translational mode
                          is gone): d1 the smoothing compile's settled
                          checksums == the pre-D256 snapshot BITWISE (64-bit
                          sample checksums 28808918c0612fbe at 8 m /
                          0fc8182da8f4a418 at 20 m over 1601 samples — the D140
                          closure; re-record on any deliberate blend change),
                          d2 the make-real round trip on the smoothed compile —
                          applies, structure, the fresh seam's smoothing off, B
                          re-anchors at the window-end sample (0.0 m), hold
                          0.0042 m, tail 0.0042 m; 24 checks)
  uvd.KeyRouteGate TypedMarkGate  (D256 — see their entries below)
  InflSplitGate ReanchorGate MirrorGate YpAbsGate  (influence splits, the
                          delete-first re-anchor, Mirror piece, absolute yaw/pitch;
                          MirrorGate + D255: g = Coaster.mirrorRun on STRICT
                          runs (held yaw/pitch/roll, mixed locks, smoothing,
                          follower design tier, absolute lanes, an s/c
                          follower; head yaw 0 and 37°) vs the geometric
                          reflection ≤ 1e-9 m with a decoy run bitwise
                          untouched, save → load bitwise, mirror twice ≤ 1e-9
                          with every lane node bitwise; h = runs with bridges
                          (three modes × Optimize length) ≤ 1e-6, the target
                          not moved by the rebuild, plus BANKED CRESTS at 45 m
                          and 58 m: mirrored-then-edited == the reflection of
                          an equally edited twin; i = refusals leave the
                          document field-identical; j = a single-piece run ==
                          mirrorPiece field-for-field and compile-bitwise;
                          h-stash (D255 review) = the manual-mode noneStash is
                          REFLECTED with its target: an untouched stash still
                          reads untouched bit for bit, a hand-moved one stays
                          hand-moved by the same distance and yaw; 100 checks)
  uvd.SeamShapeGate  (D247 — follower design tier: attach keeps a
                          design-tilted piece's shape, seamRealToDesign /
                          bakeFollow, seamDesignLockstep + its pole guard,
                          run-head translate, attach→detach in place with
                          the conflict kept (8a-c), bakeFollow on an
                          abs-bank follower (9a-c); package uvd; carries
                          recorded pre-D247 sample checksums for the
                          designed=false follower paths — re-record them on
                          any deliberate physics change; 31 checks)
  RangeResolveGate  (D247 — range-scoped orientation resolve: isolateRange
                     keeps the track put, refreezeRange == today's op on
                     the range, the chained Convert Real to Design's
                     composite steps, one-section bitwise equivalence, the
                     makeGraphsReal refuse-before-bake leak fix; EXTENDED
                     by batch 34 item C (D249, section s — RESOLVE UNDER
                     SEAM SMOOTHING, Coaster.withSeamsSuspended): on a
                     smoothed chain {H, P0 4 m, P1 conflicted 6 m, P2 5 m}
                     Set Design to Real (isolate + refreezeRange under one
                     suspension), refreezeSpeed / makeGraphsReal (+ the
                     last-piece tail) and both chained-Convert composite
                     steps each compile BITWISE the manual suspend → ops →
                     restore sequence, the lengths come back bit-exact on
                     the OBJECTS that own the seams after the splits (new
                     pieces own sharp 0 seams), the unrelated H→P0 seam is
                     never zeroed (scoped), and the smoothed result stays
                     [D255 adds section b — RESOLVE BEHIND A BRIDGE: hill
                     piece → bridge → tilted three-section piece; under
                     "No optimization", for six ops × friction off / on:
                     the target's entry pose and position BITWISE, the
                     bridge's stored fields untouched, landing < 1e-9 m,
                     the conflict closed, a second rebuild a bitwise
                     no-op, the chained convert, save → load → rebuild
                     bitwise; b8 = the CONTRAST (the same op under an
                     optimize mode moves the target > 1e-3 m — why those
                     modes stay blocked); 115 checks in all]
                     within the fit residual (6 mm), plus the wrapper's own
                     contract — nested no-op, throwing op restored, out-of-
                     range index inert (s6, the D249 review); 43 checks
                     (20 new).
                     Edge table: scratchpad b34_edges_C-resolve.md)
  CopyGate  (D247 — the Track Piece panel's Copy: Coaster.copyRun appends a
             run's deep copies +5 m along Z — copy samples bitwise the
             source's (z + 5), original untouched, first rebuild writes
             nothing, save→load bitwise, orphan-bridge refusal; tilted /
             held-angle followers to 1e-9 pos dust, smoothed seams 1e-9,
             bridge spans 1e-6 — the D199 corners; the LIST-end
             orphan-bridge refusal (g3/g4); 73 checks)
  MergeGate  (D247 — Coaster.mergeSections: part 1 recreates the LOST D131
              connect fixture (split→connect round-trips bitwise incl. the
              held lane with dirtied dead handles, force+geo refuse, the
              Phase3Gate 55f controls/marks re-cover, B-matched holds
              lossless); part 2 gates the keep-both-values STEP over
              mergeStepLen (0.01 s / 0.1 m): node layout, exactAt/slopeAt on
              both sides, broken flags, the equal-value broken node, held-lane
              hold node, DISTANCE δ, the mirror / both-short / both-zero
              fallbacks, save→load bitwise; 23 checks)
  InsertGate  (D247 — the seam menu's "Insert section…":
               Coaster.insertSectionAtSeam — a LOCK insert freezes the
               downstream as an all-locked follower at its pre-insert shape
               (world samples = pre-insert ones translated by the new exit to
               Euler dust, local shape to dust, held angles = the pre-insert
               boundary's), the strict-seam append keeps the held angles,
               a no-lock insert re-chains + re-freezes the downstream speed
               and settles bitwise, s/c totalLen / lane rideEnd / compiled
               extent == len, refusals mutate nothing, save→load bitwise;
               40 checks)
  LabelGate  (D248 — section + junction NAMING, pure model: SectionNames'
              per-type run-wide "Force/Geometric/S/C/Bridge Section n"
              titles, the badge tags in order,
              "Strict Seam n" / "Strict Seam n (bridge)" junctions (bridge
              test first — a stale followPrev is inert), the owner button
              labels incl. a bridge section's own exit + the orphan
              would-be exit, per-run restarts, guards, and Coaster's ONE
              run-topology definition (runStartOf/runEndOf/followsPrev/
              endsWithBridge) == the historic GraphEditor loops on every
              fixture; + D249 (batch 34 item L): the DRAWN badge text
              badgeLabel = short word + spaced tags, no number ("Geo ·m
              ◀ fx", "Bridge !" — the D248 "Geo 2" short form is gone),
              the bridge's "Bridge settings…" item + "Bridge Settings
              (Strict Seam n)" window title (capital S since D254;
              live/orphan/chain) beside the toolbar's kept "(bridge) ▴"
              form, and the .nl2elem description's "Ultimate Vector
              Design Export"; + D254 section 12: DesignIO.fileNameWhy —
              File > Rename…'s refusal sentences (blank, path-like incl.
              backslash, illegal characters, control characters, Windows
              device names) and two passing names; + D255 section 13:
              SectionNames.bridgeResolveWhy (null under "No
              optimization", the two optimize sentences ending with the
              user's hint), MARCH_OWNS_RESOLVE, MARCH_OWNS_R2D,
              REVERSE_MULTI_WHY, SWITCH_DEST_ORPHAN_WHY (129 checks);
              + D256 sections 15-16 (batch 38): the badge's THREE-STATE
              speed glyph — SPEED_GLYPH " ≠v" / SLIGHT_SPEED_GLYPH " ~v"
              / ORIENT_GLYPH " ≠o", speedLevel / speedGlyph at 0, 0.005
              (level 0), 0.0050001 and 0.2 (level 1), 0.2000001 and 5.0
              (level 2), NaN (0), Gestures.DRIFT_EPS == 0.2 (its new
              home), the composed badge texts "Force ~v" / "Geo ·m ~v
              ≠o" / "Force ·m ◀ fx ≠v" / a bridge's bare "Bridge";
              Reverse's gray sentences — the four new REVERSE_*
              constants verbatim and reverseWhy over all eight (multi,
              orphanTail, controls) combinations (null for none, multi
              beats orphan, controls appended to both), every sentence
              in the "Reverse works on … first." form (169 checks);
              + D256 section 17, shrunk in D257: the Smoothing Options
              window's strings (title word, the two seam-menu items, the
              length label, the two buttons, the teardown reason, the two
              D141 sentences — the D255 texts —, smoothWindowTitle on a
              plain seam / past a bridge / run head / out of range; 13
              checks); 182 checks)
  uvd.PulseClockGate  (D254 — the POV/fly RIDE CLOCK, pure: the measured
              Windows sawtooth (24 × 16 ms + one swallowed 32 ms tick)
              advances within 3 % of its mean and sums to wall time; the
              24/8 ms hint alternation; mixed + 30 %-hint stress
              streams; a hitch passes whole and stays out of the
              window; the 0.1 s cap; the seeding pulse / seed phase;
              a steady cadence reproduced exactly; reset(); busy
              startup and sub-hitch busy stretches bounded by BLEED;
              the hitch threshold's NOMINAL floor (a 32 ms tick over a
              dipped median is smoothed) and the owed drift surviving a
              hitch; 30 checks)
  uvd.KeyRouteGate  (D256 — KEY ROUTING, pure: the "would this key TYPE
              into the focused box" predicate behind every secondary
              scene's ride-key forwarder (FvdDesigner.keyTypes →
              KeyRoute). (a) the numeric-box TEXT table on signed and
              unsigned boxes — digits, numpad digits, period / decimal,
              Backspace / Delete, the arrows, Home / End, Enter / Esc /
              Tab; (b) minus / numpad subtract type ONLY on a signed
              box; (c) never text: W S A D, Space, Shift, Control, every
              letter, F1-F12; (d) typesInto's rules — a release never
              types, a shortcut combo never types, a non-text owner
              never types, a disabled / read-only box never types, a
              FREE-TEXT box takes everything (D235 kept), a numeric box
              exactly the table; (e) with JavaFX on the classpath the 28
              KeyCode.getCode() ints behind the table (SKIP on the plain
              battery classpath); 103 checks (131 with the module path).
              Its UI twin is the `-Dfvd.keyprobe=1` hook: 45 PASS lines
              covering the density preview, fly, the view digits, a
              typed box, Esc, Enter = Apply from the parked root (e2),
              the speed popup, the node editor, Coaster Options, the
              Shortcuts capture and the five save-on-release drills —
              run it after any change to forwardViewKey, handleKey's key
              filters or the two popups' wiring.)
  TypedMarkGate  (D256 — the TYPED MARK, pure: uvd.TypedMark through a
              stub spinner modelling the two JavaFX facts (the Spinner
              renders before our value listener; equal setValue /
              setText is silent) — sections A–G the prototype's 29-check
              transition table (typed-identical commits exactly,
              untouched keeps full precision, program writes never
              mark, typed-equal IS typing, equal restore keeps the mark,
              Spinner-first commit then ours inert, same-value commit,
              arrow commits then steps, discard clears when equal,
              unmarked non-rendering text parses) + section H the paste
              / drop model (identical paste marks, rejected / signed-
              into-unsigned / empty clips never, filterInput stripping,
              dropMarks); 42 checks — "42 checks, 0 failures — ALL OK".
              Its UI twin is the `-Dfvd.typedprobe=1` hook: 36 PASS
              lines, "TYPEDPROBE fails=0".)
  RunGate  (D248 — RUN AS PIECE, pure model: Coaster.runHeads/runCount/
            runOrdinal on a 3-run fixture (strict follower + smoothed held
            seam + an OPT_POS_YAW bridge, a lone piece, head + follower)
            and runStartOf/runEndOf == the historic GraphEditor loops;
            moveRun moves the block contiguously with every TrackPiece
            field bitwise and the head's inert followPrev normalized;
            per-piece compiles bitwise across the move (bridge incl.) and
            the second rebuild a no-op; connectStrictRun's locks + intact
            internal links; refusals (non-head, inside, bridge-ending
            destination, a destination that is not its run's END — D248
            review round 2, orphan-bridge block not landing last) leave
            the list untouched, landing last allowed; run-of-one ==
            movePiece / connectStrict; save→load bitwise after a move;
            runTailEnd (the floating wizard's placement) + runSampleRange /
            runLengthM (the export picker + labels); 51 checks;
            EXTENDED by batch 34 item C (D249, section j —
            Coaster.deleteRun, the Track Piece window's Delete…): a
            middle / last / first run, a run holding a bridge + its
            target, the ONLY run (empty coaster, −1), the orphan-bridge
            run, survivors' fields bitwise, refusals (−2, list
            untouched), the run-head topology invariant (no bridge-
            ending piece before a head — no re-target on a run delete),
            the undo model (re-inserting the removed objects compiles
            bitwise), the untouched runs' samples bitwise and a run-after-
            run delete down to the empty coaster (j10, the D249 review);
            66 checks (15 new); + D255 k: Coaster.moveRunTo — the block
            contiguous and in order at the destination's end, every
            field bit-identical but the head's followPrev, refusals leave
            both lists untouched, an orphan-bridge run moves and stays
            orphan, save → load bitwise; k4 / k4b / k4c pin what is and
            is not bitwise (a smoothed or bridged run landing BEHIND
            other pieces differs by ~1.2e-11 m — the compiler's global
            clock / arc offset; to an empty coaster it is bitwise whole);
            78 checks)
  PlacementGate  (D248 — the three placement WINDOWS' model half: the
                  UNLOCKED default + orientOnlyToSeam (Coaster.
                  carriesDownstream / carryAfterEdit = placementEdit's
                  choke: no carry unlocked or with Only-Affect on — bridge
                  target + follower fields bitwise — and bitwise today's
                  carryDownstream with it off), the 4-field porient line
                  (every combo byte-identical; 3-field and D221 interim
                  lines; a MISSING line = unlocked + Only-Affect and
                  compiles bitwise), TrackPiece.copy, the bridge-target
                  routing through editPitch (unlocked = both tiers, ==
                  bakeTilt's shape; locked = conflict), the target's
                  Design → Real re-march via SIG_STALE (lands, closes,
                  next rebuild a no-op; OPT_NONE inert), Real → Design
                  under held padlocks, and WHY the window's SIG_STALE
                  stamp must follow the undo push — BridgeSection.copy
                  carries optSig, a restored null-signed bridge is
                  recorded, a restored stale one re-marches (D248 review
                  round 2); 28 checks). The default flip made
                  OrientGate / OrientGate2 / RangeResolveGate /
                  SeamShapeGate set shapeLocked = true explicitly in their
                  "locked: pure conflict" fixtures (OrientGate a / h2
                  expectations flipped); CopyGate / RunGate compare the
                  new flag. Since D249 "locked" = orientMode REAL_ONLY and
                  the carry flag is the inverted affectSubsequentSeams —
                  every fixture migrated; the porient line is 5 fields.
                  EXTENDED by batch 34 item B (D249, section h — BRIDGE
                  LOCKS, the seam rule): BridgeSection.axisEditable /
                  axisAligned truth table; padlock flips under OPT_POS_YAW
                  (held axes never touched by the march, unlocking aligns
                  on both tiers, re-locking CAPTURES bitwise + the march
                  meets it + rests); a TYPED held value under OPT_POS_YAW
                  through the bridgeTargetWrite tail (survives the
                  re-march, lands, LOCKSTEP design == true, REAL_ONLY twin
                  conflicted); OPT_POS free-yaw / OPT_NONE held-pitch typed;
                  flips under OPT_POS / OPT_NONE bitwise no-ops; a blen
                  line without fields 6-8 = defaults, compile bitwise;
                  41 checks (13 new). Edge table: scratchpad
                  b34_edges_B-bridgelocks.md.
  OrientModeGate  (D249, batch 34 item M — the 3-STATE ORIENTATION MODE:
                   TrackPiece.orientMode {LOCKSTEP, REAL_ONLY, DESIGN_ONLY}
                   replaces shapeLocked (kept as the derived accessor
                   shapeLocked()), orientOnlyToSeam becomes the INVERTED
                   affectSubsequentSeams, and EVERY pitch/roll writer incl.
                   the bridge march routes through editPitch/editRoll (the
                   one tier rule): routing per mode incl. a riding conflict;
                   the setDisp*/setRawAbs* wrappers derive the delta from
                   the tier the mode edits (DESIGN_ONLY boxes/rings
                   round-trip on the design tier incl. its own pole branch,
                   rawAbsDesignPitch/Roll); porient IO — the 5-field line
                   byte-identical for every mode × carry, fail-soft /
                   inconsistent 5th field, the D248 4-field line INVERTS
                   field 4, 3-field / D221 interim / missing lines (compile
                   bitwise), a DESIGN_ONLY target under OPT_POS_YAW loads
                   record-only; copy; carriesDownstream per mode × carry
                   (DESIGN_ONLY inert); followers — seamDesignLockstep
                   under LOCKSTEP / REAL_ONLY, the DESIGN_ONLY divert's
                   model twin (applyDesignEulerDelta + held values restored
                   = the real seam entry bitwise where it was) and
                   Coaster.seamDesignSet + its refusals; the MARCH — LOCKSTEP
                   aligns BOTH tiers (no conflict) and the rebuild loop sees
                   a fixed point in ≤ 1 pass (Coaster.settleLockstepAlign's
                   inner re-capture; 13-23 whole-document passes before it),
                   also after an upstream edit; REAL_ONLY = the recorded
                   pre-D249 numbers (−27.507015°) and design untouched;
                   DESIGN_ONLY bitwise the REAL_ONLY write (defensive);
                   yaw-only / OPT_POS marches re-solve nothing; 41 checks.
                   EXTENDED by batch 34 item O (D249, section h — the
                   orientation UI's model half): the seam window's DESIGN
                   box contract (value = eulerOf(designPose) elevation/bank,
                   a typed value lands exactly, held angles / locks / the
                   real seam entry bitwise), and the FULL-SCOPE Cancel
                   record PlacementSnapshot (every piece's placement +
                   every trailing bridge's fields incl. the transient
                   optSig, restored BY IDENTITY with direct writes: a
                   carried head edit + mode/flag/lock flips + bridge writes
                   + an emulated Design → Real bake all come back bitwise,
                   the compile == the open-time compile, the next rebuild a
                   no-op; untouched = no difference; vanished pieces
                   skipped, appended ones untouched, a replaced bridge
                   object skipped, SIG_STALE / null identities kept);
                   + the D249 REVIEW ROUND 2 rows: g7 the REST GUARANTEE
                   (five slow pitched LOCKSTEP targets under OPT_POS_YAW —
                   repelling fixed points — rest in ≤ 2 passes with a
                   second rebuild BITWISE), g7b Optimize length re-kneed
                   off the settled seams (a second explicit optimize at
                   that knee keeps it), h6 the seam rig's DESIGN_ONLY
                   roll / unbanked pitch ring heading dust below the
                   held-yaw write guard; 51 checks (7 + 3 new). Edge
                   tables: the batch-34 scratchpad's
                   b34_edges_M-orientmodel.md + b34_edges_O-orientui.md)
  GestureGate  (D248 — the graph editor's GESTURE arithmetic, pure model
                via uvd.Gestures: arrow nudges (plain = v ± q bitwise,
                Shift tenth, Ctrl = the NEXT round multiple from the current
                value — never skips a cell, on-grid dust lands one cell over,
                2000 random snaps land on the grid —, Shift+Ctrl, the idle
                axis never moves, the absolute-time/previous-node/absolute-
                value references on s, g, °/s and °/m steps); Append's C1
                slope carry (continueOut: smooth sloped last node → C1
                through the joint, the new node holds flat; broken / locked
                / 1-node / flat-in = the historic flat out bitwise; the
                distance twin; the insert-hold recipe C1 at BOTH joints,
                last-node and node-0 selections); the speed-control START
                diamond resize-from-the-left (end fixed, previous-control /
                end − minLen / 0 clamps, later controls untouched, complex
                graphs == the end-diamond rescale bitwise, dormant graphs
                untouched); the one-sided band-edge length (off past the
                seam, symmetric form kept for the s/c diamond); the init-
                speed [0.1, 1e4] clamp; + item S1's VIEWPORT DISKS (7x):
                the END diamond's clamp factored to Gestures.resizeEndLen
                (minLen / section end / next control / ripple-time; 500
                random clamps bitwise the historic inline arithmetic), the
                ViewDisks native↔arc round trip on a REAL compile (TIME +
                DISTANCE spans, ≤ 1e-9; arcAtNative == the plates' historic
                lerp for interior x, the seam rule spanLo lands a
                continuation's x = 0 ON the seam sample and reads back 0),
                DISK WRITE == STRIP WRITE under every clamp end-to-end
                (start + end, both domains, clamped writes bitwise on the
                wall), the drag's nearestOnPolyline mapping (vertex / mid /
                past-the-ends clamps, NaN vertices skipped, no-projection
                null) and the boundary-disk sample set — PLAIN interior
                boundaries only since D249 (item U6: every section's exit
                except the last; the piece entry / last exit never; a
                bridge tail's ENTRY gets one; never the dropped
                continuation entry; index + 1e-6 m dedupe, clamped interior
                i1); + D249 item U4's gizmo pick metric (Gestures.
                ballEdgeDist / ballEdgeBand: a center ball measures from
                its silhouette EDGE — inside 0 px —, the edge band keeps
                the D248 zone's outer reach, inside-beats-ring /
                outside-loses contests); + D249 item S3's WHOLE-DISK
                PICK (ViewDisks.rimPoints in the disk plane at the ring
                radius, pickRadiusPx = the projected rim's bounding
                radius floored at PICK_PX with behind-camera NaN rim
                points skipped, pickScore's normalized d²/pickR² — a
                small far disk beats a big near one, the rim itself
                misses, ties keep build order, pickR ≤ 0 never hits —
                and the doubled thickness / lighten-factor constants);
                + D254 section 10: Gestures.slowDebt / SLOW_K — the
                gizmo Shift law (a gesture that never sees Shift is
                bitwise the raw travel, Shift re-bases per event,
                toggles compose); + D255: 6c/6d the init-speed helpers
                (speedDiffers, realSpeedText), section 11 the graph VIEW
                math (panToInclude: width kept, minimum shift, the
                margin, clamps, NaN; adoptWindow: the domain
                intersection, domain-wide = auto-fit; growRightCapped),
                section 12 the DEPTH-AWARE GIZMO PICK (raySphereFront,
                rayCapsuleFront, arrowRadius, arrowFront, ringFront,
                pickFront's two tiers + the layer rule; 8c re-labelled:
                the edge metric is the FALLBACK tier); 180 checks. Edge
                tables: the
                batch-34 scratchpad's
                b34_edges_U-uibugs.md + b34_edges_S-strip.md)
  EndFollowGate  (D248 — PRISTINE-LANE END FOLLOW, pure model via
                  LaneSection.endFollows / pristineHold / touchLane /
                  syncFollowingEnds: continuation + holdSection create
                  flagged, TrackPiece.copy deep-copies, splitSectionAt
                  hands both halves the flags (a hold's cut halves stay
                  pristine — fractions 1/3 ± ulps, values/dv exact),
                  mergeSections' output and plain constructors unflagged;
                  the write: nothing moves while every lane follows, an
                  edited lane's end drags the untouched lanes UP and DOWN
                  (the section shrinks), two defining lanes = the later
                  end, the belt clears (never moves) a value-/handle-/
                  lock-edited follower, 1-node lanes neither define nor
                  follow, delta lanes untouched, the minGap floor, the
                  DISTANCE meters, touchLane's identity semantics, the
                  clear-only redraw belt clearStaleFollows (never moves);
                  IO: the `lane k f` 3rd field written 1/0 and read
                  verbatim (an explicit 0 on a hold-shaped lane survives),
                  DERIVED from the loaded shape when missing (pre-D248
                  files) as a FIXED POINT of the sync — a hold short of
                  the end, the sole longest hold and a time-dragged
                  sibling derive false; every stripped fixture asserts one
                  sync pass moves nothing and the save is unchanged (D248
                  review round 2) — a healed lane unflagged,
                  save→load→save byte-identical, flags feed no geometry
                  (compiles bitwise across flipped flags); 73 checks)
  FloorGate  (D249 — RE-BASELINED: the D248 depth-lift model is retired
              (see FloorDepth's postmortem — the floor is back to the
              D242-D246 depth-disabled submission-order build BITWISE,
              underground track reads as behind it by ORDER: the
              GROUND-SPLIT track layer). Pure model via coaster.
              FloorDepth: the D241-D246 strength law's sanity (which
              decades are alive at 2 m / 60 m / 2.4 km), the D243/D245
              ladders + the 70 mm GROUND_DEADBAND, eyeBelowGround's
              signs (±0 = above), faceWorldY over scene points, the
              split routing on a 500-ring tube dipping +5 → −15 m
              (wholly-below faces under, wholly-above faces above,
              STRADDLERS by centroid on both sides, splitCounts sums),
              the deadband family (a tube centred at −5 mm whole, −5 cm
              sheds its bottom faces, −8 cm half/half, ON y = 0 one
              clean tube, wholly above = the point fast path, a quad at
              −15 all under), purity + a −30 m translation; + D255:
              f7 itemUnder (strict, ties / NaN toward above), f8 the
              truth table beforeFloor, f9 clipAtGround on a comb tooth
              crossing the ground (every above face ≥ the plane, every
              under face ≤ it, area conserved, no winding flip; the
              centroid mask's DIAGONAL split pinned as the reason), f10
              the 500-ring tube (area, shared cut vertices, source-face
              maps, level tubes, purity) — underMask / splitCounts (f4-
              f6) no longer describe the render; 34 checks.
              The scene-graph order, the world-order swap on the eye's
              side of the ground and the MeshView cut are JavaFX-side —
              the -Dfvd.gridshot ladder (15 poses incl. the underground
              fixture; its first 9 pixel-diffed against a HEAD-f84061a
              build: identical) + the -Dfvd.gridshot.sweep hook (moving
              + rest poses with a line-pixel count) are their
              verification)
  StripGate  (D248, item S2 — the speed strip's DESIGN-speed line, pure
              model via uvd.SpeedStrip + real compiles: an unconflicted
              section never splits at any strip scale (design == true
              bitwise), a PINNED section splits at EVERY sample and its
              neighbor at none (purely pixel-based, no ≠v/≠o flag read), a
              shape-locked TILTED piece (≠o only) keeps its entry sample
              unsplit and splits inside (the line appears mid-section),
              the split predicate's strict > TRUE_SPLIT_PX / NaN rules,
              design == true inside a constant-SPEED control (the line
              ends at the snap by itself), fitHi (design joins the fit,
              window clamp, shorter-array read, null inert, never lowers),
              the " (design 12.34)" hover suffix, and the line styles
              (design = the old true style / 1.5, true wider + brighter,
              (3, 4) kept); EXTENDED by batch 34 item S (D249 — the
              speed-control CROSS-DOMAIN-VIEW parity + position readouts):
              SpeedStrip.snapDisplay with a0 = 0 == the strip's historic
              native snap bitwise (500 random), the DISPLAY-axis snap
              round trip on a REAL compile through Coaster.sectionMap /
              mapCol (a TIME-native control viewed in meters lands on the
              1 m display grid ≤ 1e-9, a DISTANCE-native one viewed in
              seconds on the 0.1 s grid, the map origin a0 exactly 0),
              and the position readout formats ("t = 3.20 s" two
              decimals / "s = 12.5 m" one, the z3 zero-dust rule, larger
              dust keeps its sign, Locale.US under a comma default
              locale, posValue = the readout minus its head); 49 checks.
              The drawing itself, the hover hit, the warp freeze during
              strip gestures, the popup twins and the viewport's live
              drag readout are JavaFX-side. Edge table: the batch-34
              scratchpad's b34_edges_S-strip.md)
  uvd.ConflictGate  (D237 — the phantom unresolvable ≠v (8 checks); EXTENDED
                         D248, item S3 — the INVISIBLE lateral-G TRUE STROKE under an
                         ORIENTATION conflict: GraphEditor's split reference keyed off
                         the pre-v19.0 true-tier tilt test, so a DESIGN-tier conflict
                         (D222) or a follower's HELD-SEAM tilt (D247) culled the thin
                         true stroke wherever the speeds matched; pure-model twin of
                         the new selector (orientConflictAt × LaneSection.tiltDeviates
                         per lane) on real compiles: true roll 30 / design roll 30 /
                         held seam roll −30 draw ≥ 98% under the new rule while the old
                         rule drew < 60% on the last two (the bug, pinned), a heading-
                         only re-aim and a lockstep tilt draw nothing, the force local
                         roll lane keeps the derived reference, a PITCH conflict via Gv,
                         the tiltDeviates truth table, a geo ABSOLUTE-bank section ~30°
                         off its tilted samples on lane 2 only; 9 new checks — 17 total.
                         The drawing / hover / inactive-strip / B-guard wiring is
                         JavaFX-side (GraphEditor.splitRef); package uvd;
                         + D255 block h — "Set Design to Real": h1
                         realEntrySpeed null for the ride entry / a detached
                         head / a bridge / a reversed section; h2 a pinned
                         continuation with friction settles on the dry run's
                         prediction (the previous DESIGN end, 2e-6 off real);
                         h3 / h4 the real-anchored continuation and a strict
                         follower land BITWISE on the real seam speed; h5 a
                         sub-gate unpinned continuation: the op changes
                         nothing; h6 the RUN-HEAD fix (a detached head keeps
                         17.0 — the op used to write the previous piece's end
                         speed); h7 a stalled seam;
                         + D255 block i — THE BIT-EXACT REAL SPEED (Pass D's
                         clean-span rule): i1 unconflicted chains (friction
                         on / off / a holding control) — every true speed ==
                         the design speed BITWISE; i2 a pinned section keeps
                         the march, its neighbors copy; i3 a tilted piece, a
                         smoothed seam's neighbors and a bridge never copy, a
                         lockstep tilt does; i4 the SEAM STEP of a control
                         starting at x = 0 (ctlSampleRows' 4-arg form) against
                         the historic rows; i5 a bridged-in first section
                         under friction lands bitwise (the op's fixed point);
                         i6 rebuild + save → load bitwise; i7 behind a
                         smoothed seam the op's write ==
                         Coaster.firstSectionSettle; h2c is bitwise since;
                         block j — ONE FLOW (the march re-seeded where a
                         recorded stretch ends): j1 a tilted follower
                         behind a HELD control — no step at the seam, no
                         conflict, the op writes nothing; j2 a smoothed
                         seam copies up to its window, a window too short
                         to blend changes nothing; j3 24 bridged-in pieces
                         land bitwise, copy, and stay under a second
                         press; j4 a bridge the train cannot cross: the op
                         refuses; j5 a clean stretch ending in a STALL
                         never re-seeds (energy bound kept); j6 the
                         re-seed is causal per run (a detached piece's
                         first control moves no bit of another run);
                         j7-j8 (review round 4) a bridge the train CRAWLS
                         off at 0.55 m/s: both resolve ops refuse with the
                         crawl sentence, nothing written; at 2 m/s the
                         BLOW-UP BELT takes the write back (bridge arc 38 m
                         -> 2028 m in the op's own compile, restored) —
                         the round-4 fixtures bisect the target height;
                         51 checks; + D256 block k (batch 38) — the "~v"
                         SLIGHT-conflict glyph on the user's fixture (b38
                         conflict.uvd embedded verbatim as FVD_B38;
                         entryLevel = GraphEditor.driftLevel's recipe on a
                         compile result): after the settle recipe the run
                         head and piece 0's two continuations enter at
                         their design speed (level 0, no glyph), the strict
                         follower's two sections enter 0.045 / 0.077 m/s
                         off (level 1, " ~v" — nothing showed before, never
                         ≠v), both gaps inside (0.005, 0.2], a rebuild at
                         rest keeps every level (58 checks); + D256 block l
                         (the EXPLICIT REAL ANCHOR, Section.vRealAnchored,
                         on the same conflict.uvd): l1 the bug reproduces
                         (p1s0 0.045 off, p1s1 bitwise on the design end,
                         0.078 under the real seam speed; the follower's
                         seam tilt is heading-only — not ≠o); l2 the op
                         flags + un-pins and the rebuild lands p1s1 BITWISE
                         on the DISPLAYED real seam speed (1 exact rewrite
                         corrects the op's suspended read, then 0), piece 0
                         + p1s0 untouched, a second rebuild / second press
                         bitwise no-ops, the pre-op document reproduces
                         the pre-op compile; l3 the dry run predicts
                         max(0.1, real) and reads no conflict after; l4
                         save → load → rebuild bitwise through the "2"
                         slot, re-save byte-identical; l5 an upstream edit
                         after the resolve is followed; l6 smoothing 0
                         lands the same; l7 the flag is LOAD-BEARING (slot
                         forced to 0 settles back on the design end); l8
                         makeGraphsReal flags its fit; l9 the '+'
                         Transition-to-real hold flagged lands on the real
                         end speed, unflagged on the design end; l10 one
                         exact pass writes a nudged flagged section back
                         (B's rule); l11-l13 item e's model states (pinned
                         at real un-pins with bits kept, 0.003 off lands
                         bit-exactly, a run head writes nothing); l14 the
                         s/c slot, copySection, splitSectionAt (left keeps
                         / right not), mergeSections (A's); l14f-h the four
                         CONVERT carries (the review round):
                         partialConvertSection on BOTH sides — the
                         entry-owning half keeps, the other half not —,
                         convertDomain, bakeAxes and convertWholeSection
                         each install a new object carrying the anchor.
                         h5 REWRITTEN (the +0.05 pinned-upstream click
                         STICKS bitwise, the anchor survives save → load)
                         + h5b (unpressed it settles on the design chain
                         bitwise). 87 total — "ALL CONFLICT GATES PASS
                         (87)". RE-RUN IT — and the old-vs-new dump of
                         real documents, gates/probes/cleanspan-2026-09-30
                         (CleanProbe2.java + compare3.py: positions, design
                         and real speeds per sample) — for ANY change to
                         passD, the integrators' speed recording or
                         SpeedControl's step logic)
  + probes named in CLAUDE.md: uvd.DecompProbe InflMatchProbe Nl2ElemProbe (Nl2ElemProbe RE-PINNED D257: its fixture FULL TEST
   COASTER 7.30.26.fvd runs 1.3 km out under the held-control fix — the position checks take the writer's %e format floor 2q + 1e-5
   at the measured extent (q 1e-5 → 1e-3), the D202 jitter pin is the 1 %-trimmed rms of the shared resample read through the CSV's
   %.6f text against a lerp twin (Hermite 0.00021 / 0.00013 vs lerp 0.0043 / 0.0050, bound 0.001 and a quarter of the lerp's;
   @935 m 0.00035 / 0.00006 < 0.002); 22 checks on both builds)
  gates/probes/typed-mark/ — FxOrderProbe + UndoableProbe (D256; toolkit-level, Platform.startup, no Stage; need the JavaFX
  module path — own README): pin the JavaFX 26 ordering facts the typed mark rides on (render before listener,
  equal-value / equal-text silence, commitValue parse + clamp order, increment commits first, converter re-render order,
  equal setText resets the caret, identical replaceSelection is silent, commitValue('-') throws, isUndoable is no provenance
  signal). Re-run on any JavaFX upgrade. Not compiled by the gates/*.java line.

JavaFX DEV HOOKS ride the app, not gates/ (no JavaFX on this classpath):
  java --module-path <~/.m2 openjfx base/graphics/controls 26-win jars> --add-modules javafx.controls -D<hook> -cp target/classes uvd.Main
  -Dfvd.devshot=<dir> (start-screen PNGs) · -Dfvd.devmenu=1 (Robot-drives the Coaster menu) · -Dfvd.gridshot=<dir> (floor-grid pose ladder, 15 poses)
  -Dfvd.gridshot.sweep=<dir> (D249 — the ladder's TEMPORAL twin: moving + rest poses with a printed per-frame line-pixel count; the only
   reproduction of a floor artefact that shows up while the camera moves) · -Dfvd.winshot=<dir> (D248 — renders the three live windows;
   D255: its fixture gives the head, the follower and the bridge target an orientation conflict so the resolve rows render, opens the
   Track Piece window with the head / a follower / the bridged-in target selected (win-trackpiece[-follower|-target].png — the printed
   rows lines must be identical and the PNGs pixel-equal), lists every label with CLIPPED / MISSING flags, and prints each yaw
   separator's width against the ↺'s right edge and the x/y/z-to-ops gap; D257: its tail prints THREE "WINSHOT povrow" measures —
   the POV bar as rendered at the ride start (design hidden), its WORST case (the hidden suffix's slot counted) and the row with the
   design-speed suffix ON SCREEN over the fixture's bridged-in ≠v target ("-conflict", design shown — asserted: "WINSHOT
   povrow-conflict MISSING" + exit 1 when the suffix is not shown there) — 1127.3 / 1197.3 / 1197.3 px against the row's 1213.3,
   plus povrow-hidden.png / povrow-shown.png)
  -Dfvd.pickprobe=1 (D248 — the SCREEN-SPACE gizmo picker on the default document: part registration, pick bands incl.
   the ball-first center rule, a drag-reveal that hides the pressed ball with the drag alive, a ring drag; prints PICKPROBE
   lines, exits 0 when green — run it after any Viewport3D gizmo/marker change; D255 adds the DEPTH cases, zoomed onto the rig: a ray
   canary per camera state (the pick ray must agree with projectWorld), front arrow over the ball → arrow, arrow half behind the ball →
   ball, ring near arc → ring, ring far arc → ball, and the same in true ortho. It fires events AT the pane, so it cannot see a
   press-target bug — the D255 disk-drag failure needed injection through the Scene (SceneHelper.processMouseEvent))
  -Dfvd.bridgeringprobe=1 (D257 — the BRIDGE-END RIG's ring rule on the default document + a bridged 1 s hold placed 14 m past the
   end under "No optimization": the ring set per mode × padlocks (NONE / POS three rings whatever the locks; POS_YAW the held axes
   only), a Global yaw-ring drag through the pane filter == the typed heading bitwise (yawDeg + a positions fold) under NONE and
   POS, the D253 knee hold mid-drag under Optimize length, a held pitch-ring drag under optimize-orientation, Local drags, the
   bridge window's box re-sync; prints PASS/FAIL lines + "PROBE bridgering fails=N", exits 0 when green — 41 PASS; run it after
   any bridge-rig / bridge-lock change)
   Launch hooks with --enable-native-access=javafx.graphics (pom.xml passes it to mvn javafx:run since D255) and, for anything that
   reads settings, -Duser.home=<scratch dir> so the run never touches ~/.fvd-designer.properties.
  -Dfvd.povprobe=<seconds> (D254 — enters POV on the default layout, holds W, records every pulse's wall delta + ride advance;
   prints the outlier counts, the largest pulse-to-pulse step and sum(advance)/sum(wall); + -Dfvd.povprobe.raw=1 = the historic raw clock)
  -Dfvd.timing=1 (D252 — uvd.Prof per-phase profiler: compileLoop / meshes / hooks / bridgeCtl / redraw / refitSize /
   refreshGizmo / undoSnap / optimizeBridge; zero cost when off) · -Dfvd.bridgeprobe=<file.uvd> (D252 — opens the
   file, opens the bridge window on piece 0 then 1, steps the next-piece spinners like the arrows, Robot-clicks the
   pitch arrow, the Length arrow and the Optimize length check; prints per-step sync ms + render pulses + the Prof
   report; + -Dfvd.renameprobe=1 = a Robot-driven File > Rename… on THAT file — pass a scratch copy;
   + -Dfvd.bridgeprobe.csstrace=1 = a stack trace per JavaFX CSS warning, D254 §8)
  -Dfvd.keyprobe=1 (D256 — in-JVM KeyEvents through Event.fireEvent at the main scene's focus owner, so the Window →
   EventRedirector → popup path is exercised: the density preview / fly / view digits / a typed box / Esc / Enter = Apply from the
   parked root / the speed popup / the node editor / Coaster Options / the Shortcuts capture, then the five save-on-release drills
   S1-S5 through the saveDesign test seam; prints KEYPROBE PASS/FAIL lines + "KEYPROBE fails=N" — 45 PASS, fails=0)
  -Dfvd.typedprobe=1 (D256 — the typed mark's real wiring on the Track Piece pitch box at 12.3456: click in/out keeps the bits,
   KEY_TYPED 12.35 + Enter stores exactly 12.35 with one undo step, Esc / typed-identical Esc / a lone '-' keep the bits, the Coaster
   menu's heart box through its ActionEvent; never touches the system clipboard; "TYPEDPROBE fails=0", 36 PASS)
  -Dfvd.scprobe=1 (D259.1b — the per-element roll lane strip on the default document + a model-appended s/c of three elements:
   activation binds element 0's lane (mode-3 buttons), the lane element switches by identity, insert / A (the hold halves the next
   transition on a default lane) / Delete / B at an interior border, the cut / shadow round trip through Element.setLength (bitwise
   return), the element dialog + the Roll dialog open / close, both band buttons recorded, the Bank tint after a flag switch, the 3D
   double-click's lane switch then insert, an element delete clamps the lane element; "PROBE sc fails=N", 21 checks; 26 since round 2 — S7d the chips on the badge row, S11a-d the R20 → R2 shrink of a many-node absolute lane, the POV ride over it and Ctrl+Z: no exception, the canvas alpha 1, the restored lane re-bound)
  -Dfvd.initspeedprobe=<file.uvd> (D256 — opens the file, the init-speed window on piece 1 section 1, Button.fire on "Set Design to
   Real", editor.undo / redo: the bitwise landing on the displayed real seam speed with the anchor set, the no-op second click,
   Ctrl+Z / Ctrl+Shift+Z through the DocState snapshot; + D257's LAYOUT checks: on piece 0 section 1 (no conflict) the readout
   unmanaged and Cancel's right edge on the box's within 1 px, the button at the left padding; a stepped value widens the window
   with the box unmoved, Ctrl+Z narrows it, Cancel after a width change reverts + closes; on piece 1 section 1 the readout present,
   gone + narrow after the click, back after undo, gone after redo — geometry read one pulse after each change off layout bounds;
   prints fails=N — 39 checks, fails=0 on the user's conflict.uvd)
  -Dfvd.smoothprobe=<file.uvd> (D256, reduced D257 — the smoothing options window on piece 1: title / box / no check box / focus,
   typed 5 and 0 (clamp 0.5), Cancel, undo / redo of a typed length with the window alive, Disable, re-enable, the teardown reason,
   hideJunctionWindows, Make real → the density preview; prints "PROBE smooth fails=N" — 28 checks)

Timing probe (D252/D253): uvd.BridgeSlowProbe <file.uvd> [fixed|drag] — the shell's rebuild loop verbatim + the
bridge-window click route headlessly; `fixed` compares the OLD click route (explicit optimizeBridge on the
previous compile's capture) against the NEW one (Coaster.captureBridgeTarget first) — same fixed point,
fewer passes; `drag` = six live node-drag events + the release settle on the section between the bridges and
the one before the first, per-event knee (LIVE_KNEE=false) vs the live-held knee (D253), settled max |Δ|. The 2026-09-25 cascade hunt's 25 research probes live in probes/cascade-2026-09-25/ (own README;
not compiled by the gates/*.java line).

History: these lived only in per-session Temp scratchpads until Windows
Temp cleanup purged them; recovered from the session transcripts with
tools/recover_gates_from_transcripts.py. Phase2Gate is PARTIAL (163 of
the historic 258 checks — the convert/partial-convert/node-split/
connect/speed-reconcile families were authored in a session whose
transcript is gone; see Phase2Gate.lost-edits.txt). attic-noncompiling/
holds old probes against dead APIs, kept for reference.
