# Headless gates (moved into the repo 2026-08-18, D223)

Compile:  javac -cp target/classes -d gates/out gates/*.java
Run:      java -cp "target/classes;gates/out" <Name>      (package-uvd probes: uvd.<Name>; package coaster → uvd in v19.21, D250)

Core battery (run after ANY compile/reconcile/IO/placement change —
33 members since D249 (OrientModeGate new; 32 since D248: StripGate new, ConflictGate extended by item S3), all green on wiped classes 2026-09-24):
  DetermGate197 DetermGate198 DetermGate199 DetermGate200 LoadGate196
  OrientGate OrientGate2 Phase2Gate Phase3Gate DomainGate
  MultiCoasterGate SmoothRealGate
  InflSplitGate ReanchorGate MirrorGate YpAbsGate  (influence splits, the
                          delete-first re-anchor, Mirror piece, absolute yaw/pitch)
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
              the bridge's "Bridge settings…" item + "Bridge settings
              (Strict Seam n)" window title (live/orphan/chain) beside the
              toolbar's kept "(bridge) ▴" form, and the .nl2elem
              description's "Ultimate Vector Design Export"; 103 checks)
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
            66 checks (15 new))
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
                108 checks. Edge tables: the batch-34 scratchpad's
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
              −15 all under), purity + a −30 m translation; 18 checks.
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
                         JavaFX-side (GraphEditor.splitRef); package uvd)
  + probes named in CLAUDE.md: uvd.DecompProbe InflMatchProbe Nl2ElemProbe

JavaFX DEV HOOKS ride the app, not gates/ (no JavaFX on this classpath):
  java --module-path <~/.m2 openjfx base/graphics/controls 26-win jars> --add-modules javafx.controls -D<hook> -cp target/classes uvd.Main
  -Dfvd.devshot=<dir> (start-screen PNGs) · -Dfvd.devmenu=1 (Robot-drives the Coaster menu) · -Dfvd.gridshot=<dir> (floor-grid pose ladder, 15 poses)
  -Dfvd.gridshot.sweep=<dir> (D249 — the ladder's TEMPORAL twin: moving + rest poses with a printed per-frame line-pixel count; the only
   reproduction of a floor artefact that shows up while the camera moves) · -Dfvd.winshot=<dir> (D248 — renders the three live windows)
  -Dfvd.pickprobe=1 (D248 — the SCREEN-SPACE gizmo picker on the default document: part registration, pick bands incl.
   the ball-first center rule, a drag-reveal that hides the pressed ball with the drag alive, a ring drag; prints PICKPROBE
   lines, exits 0 when green — run it after any Viewport3D gizmo/marker change)

History: these lived only in per-session Temp scratchpads until Windows
Temp cleanup purged them; recovered from the session transcripts with
tools/recover_gates_from_transcripts.py. Phase2Gate is PARTIAL (163 of
the historic 258 checks — the convert/partial-convert/node-split/
connect/speed-reconcile families were authored in a session whose
transcript is gone; see Phase2Gate.lost-edits.txt). attic-noncompiling/
holds old probes against dead APIs, kept for reference.
