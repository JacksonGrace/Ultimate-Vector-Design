# SC-OVERHAUL-PLAN.md — the straight/curve overhaul: spec digest, open questions, recommendations, plan

Status: analysis only (2026-10-20). Nothing in the source tree was changed for
this document. It answers the four things asked in the first prompt of the
Phase 4 item 1 arc:

1. the spec re-organized (§1),
2. everything unclear or contradictory, plus answers to the questions embedded
   in the spec (§2),
3. recommendations (§3),
4. the staged plan, with the Fable 5.1 / Opus 5.5 split and the model-switch
   procedure (§4).

§0 is the list of decisions only you can make, ranked by what they block.
Answer those (or say "defaults") and Stage 0 can start.

How this was produced: a 40-agent analysis run — seven readers mapped the
existing s/c, seam, gizmo, roll-lane, key, IO and gate code; eight analysts
digested one spec topic each against those maps; a cross-check agent read the
eight digests side by side; eighteen adversarial verifiers then tried to refute
every ambiguity / contradiction / iteration claim (272 findings: 91 confirmed,
162 downgraded to "real but a documentation or implementation note", 19 refuted
and dropped from this document; 89 additional "missed" notes, folded in); three
independent planners wrote staged plans from different angles; a completeness
critic checked a draft of this document line by line against the spec and
against the three plans — its 19 coverage gaps, 3 code areas, 10 unverified
claims and 12 internal contradictions are folded in too. The raw reports
(≈3 MB) live outside the repo under
`~/.claude/projects/<project>/memory/sc-overhaul/` (§5). Every statement about
today's code was traced to a file:line by at least one agent; the ones that
decide a design choice were re-read by the cross-check or by me, and the two
that mattered most were PROBED against target/classes (the banked-turn dive and
the blend offset, §2.1 / §2.3). Claims this document still makes WITHOUT a
probe are listed in §4.6 with how to verify each cheaply.

---

## 0. Decisions that are yours (ranked by what they block)

A default is given for each so you can answer "defaults, except …". Items
marked (engineering) are listed for visibility; they will be decided in Stage 0
unless you object. Numbers are stable identifiers, not a strict rank.

### Before Stage 1 (the model core)

| # | Question | Blocks | Default if you say nothing |
|---|---|---|---|
| 1 | **Iteration policy.** The spec says "no iteration" in four places. Two readings: (a) the USER never iterates toward a value (every edit lands or is refused) — met everywhere; (b) the CODE never root-finds — impossible for unlinked (varying) curvature, sinusoidal ramps, exact landing under element smoothing, mixed local+global locks, a straight node after a pitched helix, κ/Θ-locked nodes in a fixed ortho view, and a helical/ramped tail under alignment (§2.3). The project already runs bounded internal solves (the integrator's Newton landing legs, the bake's bisection, the bridge march's fixed point, the Optimize-length knee, the lockstep settle). May a deterministic, bracketed, hard-capped INTERNAL solve with a stated tolerance and an honest refusal on non-convergence count as "hard math"? | The solver's scope in Stage 2 and everything after it. | Allowed internally, never on the hover ghost; v1 still REFUSES position-driven drags on unlinked / sinusoidal / ramped-pitch elements with a one-sentence reason (boxes and rings still edit them) and lands under element smoothing via the per-event offset predictor (§3 R6). |
| 2 | **Roll-free curve axis.** Today a curve rotates about the BODY frame, so a banked turn climbs or dives — probed: a straight then a dir-0 R20 +90° turn with a 30° GLOBAL bank ends 10.9 m LOWER than the level circle, end tangent pitched 43° (an ungated defect contrary to D69's "a pure T-roll never bends the spine"). The same probe shows today's GLOBAL-0 elements are exact circles ONLY at direction 0 or 90 from a LEVEL entry: direction 30 is 2.08 m off, direction 0 from a pitched entry 4.07 m off. Define every element's axis against the LEVEL frame at its ENTRY, fixed per element (planar: a world-fixed axis; helix: world Y), so the roll lane only twists U about T. Cost: every existing file with an off-cardinal direction, a pitched entry into a turn, a banked GLOBAL turn or a RATE-rolled turn CHANGES SHAPE (metres for the examples above) — your "UVD Test Coaster 10.16.26.uvd" has a direction −20 turn followed by a direction-0 turn entered climbing, so it moves. Stage 0 prints the per-file, per-element numbers before you decide (R2). A vertical ENTRY has no level frame: element 0 after a vertical seam inherits the previous element's plane / refuses direction edits. | Stage 1 entirely; every closed form; "no shaping". | Yes (it is also a bug fix); a `LEGACY_BODY_AXIS` switch stays for one stage as the reversal lever so you can A/B a file. |
| 3 | **Default roll for a new s/c.** The spec says °/m, distance, global. A GLOBAL RATE lane at 0 HOLDS whatever bank arrives (no snap, no entry ease needed; identical to today after a level entry). Today's GLOBAL 0 element means "0° vs the horizon = rails level" — that is an ABSOLUTE bank 0 lane, which SNAPS at the seam after a banked turn unless the seam smooths. Which default? And should seam smoothing start OFF at an s/c start (like every hand-made strict seam today)? | Migration rules, the Help "Roll" row, what a fresh s/c looks like after a banked turn. | Global rate 0, distance, °/m; smoothing off; B on the lane's first node = "level out". One documented behavior change: a station after a banked turn needs a node bringing the bank to 0 or the absolute behavior. |
| 4 | **Roll control model.** ONE roll lane per section plus a list of FLAG RUNS keyed by arc (boundaries on roll nodes; "Split roll control here" inserts a run boundary) — vs independent SpeedControl-style interval objects each with its own graph. The verifiers note the literal text favors one roll control PER ELEMENT at creation; one-per-section with explicit splits is the simplification offered. | Stage 1b's model, the strip row, the node menu, Combine. | One lane + runs; Stage 1b ships section-wide flags (exact reuse of today's lane behavior), Stage 7 adds runs. |
| 5 | **Automatic strict-seam defaults.** Today two creation defaults exist: Make-seam-strict locks all three axes (D235), Attach lets yaw follow and holds pitch/roll (D237). The spec adds a third (border split: all locked) and implies a fourth (end→start attach). Proposal: the AUTOMATIC seam that '+', Insert and migration create at an s/c start is ALL-UNLOCKED, the s/c follower's FIRST section is REAL-ANCHORED and reconciled (today a follower's first section is never reconciled — D135's honest ≠v — which would put ≠v on every s/c after any upstream speed edit), and (engineering) an all-unlocked s/c follower GENERATES AT THE FOLLOW CONTRACT from the upstream Span's exact heading/orientation pair instead of its stored design pose plus a rigid tilt (otherwise an upstream pitch/bank edit would leave the s/c at its old orientation with a ≠o, and a head yaw would turn today's bitwise in-piece chain into dust-level follower arithmetic). Together these reproduce today's seamless straight continuation under every upstream edit — BITWISE where the follower reads the Span's exact pair (the D199 mechanism), gated at creation, under head x/y/z + yaw and under upstream pitch/bank edits. Make-seam-strict and the border split stay all-locked; start→end attach yaw-follow; end→start attach all-locked. "Un-strict seam" REFUSES on an s/c-first follower (the invariant); "Split track at seam" is fine (the s/c becomes a run head). Consequence to accept: an all-locked border-split seam ENDS the downstream absorb at that point (§2.2 C15). | Stage 1b, every ≠v badge on s/c, Stage 4's border split, Stage 5/6's attach. | As stated; the border-split seam stays all-locked per the spec, with one Help line. |
| 6 | **Helix definition.** PLAN radius (heading rate per horizontal metre, the designer's and NL2's "helix radius") + END ELEVATION ("pitch" = the end node's elevation angle, not rise-per-turn) interpolated linearly over TRACK arc; the osculating ("track") radius a grey readout; a helix refuses on vertical track and clamps |end elevation| < 90°. This is the one definition whose unlinked-pitch inverse is closed form (verified by derivation only — Stage 1a's gate checks it against a quadrature). | Stage 1a's helix term, the ghost, the window rows. | Yes. |
| 7 | **Migration of your files.** At load, in-piece s/c sections become ALL-UNLOCKED followers; element 0's entry ease (2 m in new sections, but 6.08 m and 2.02 m in two of your files) is DROPPED when a dry compile shows it was inert (level entry into the same bank) and becomes seam smoothing of the same length where it actually blended a bank — decided PER FILE by the compile, not assumed; per-element GLOBAL/RATE roll becomes the lane (bitwise for single-value GLOBAL at a level entry and single-value RATE; stepped lanes with ramps — the quintic 7/15 handle preset — for mixed files, approximate, the D233 precedent); one counted warning line, document marked dirty. Note: every s/c becoming a follower RENUMBERS "Strict Seam n" ordinals after it (window titles, the junction button). Three of your four s/c files are `.fvd`, which Load no longer opens (D251) — the migration gate uses renamed copies. | Stage 1b's migration pass + gate. | As stated. |
| 8 | **File grammar.** Keep `elem straight` / `elem curve …` for the two legacy kinds INCLUDING the two now-inert roll fields in their positions (old readers index them positionally and would skip the line otherwise), plus trailing fields for locks/links/interpolation (old readers ignore extras); new kinds `elem planar` (κ = 0 planars AND unlinked / ramped planars — an unlinked planar written as `elem curve` would load in an old build as a constant-radius curve with no warning) and `elem helix` (old readers drop both as damaged lines — a file with one opens in an old build with that section shortened and a warning); a `roll …` block + `rn` node lines for the lane (never `lane`/`n`, which throw inside `section sc` in old readers); `sclose` for the loop link. | Stage 1a/1b IO. | As stated. |
| 30 | **Roll "domain" per control.** The spec lists domain among the per-control roll settings. An s/c is always DISTANCE; a TIME-keyed roll lane would reintroduce the design-speed dependence the s/c promises to avoid, its end exists only after a compile, and the time VIEW already gives the editing ergonomics. Drop "domain" from the roll settings (the time view stays in the badge menu), or keep it? | Stage 1b's Roll dialog. | Drop it. |
| 31 | **A strict seam at the s/c END too?** The spec mandates a seam at every s/c START and between two s/c sections; two other lines picture a seam at an s/c→force/geo boundary ("if there is an s/c before and a different section after", "the actual end of the section [the strict seam]"). Without one, seam smoothing — the stated replacement for the entry ease — cannot apply at an s/c→force/geo boundary unless you make that seam strict by hand, and the '+' force/geo continuation after an s/c stays an in-piece plain boundary. Start only (the explicit rule) or every s/c edge? | Stage 1b; the gap's press rank; the end-boundary menu. | Start only; the end becomes strict through the existing seam menu when you want smoothing there. |
| 32 | **Whole-run Reverse.** Reverse… is a single-piece rebuild grayed on multi-piece runs; under the seam invariant EVERY [force][s/c] design becomes multi-piece and loses Reverse (today it has it). Accept with an honest sentence for v1 (a whole-run Reverse is already an open ask from D255), or make whole-run Reverse part of this arc? | Stage 1b's gray sentence; possibly a stage of its own. | Accept + sentence; whole-run Reverse as its own later item. |

### Before Stage 2 (solver + Append Element mode)

| # | Question | Blocks | Default |
|---|---|---|---|
| 9 | **New elements' default blend.** Every new element today arrives with a 2 m inter-element blend. Probed: the compiled end then sits 6.6 mm off the sharp closed-form end per R20/90° boundary at 2 m (59 mm at 6 m), and a two-plane arc↔arc blend also ROTATES the downstream (0.014° / 0.12° / 0.49° for 2 / 6 / 12 m). So "the ghost is what you get" and "the node lands exactly under the cursor" are mm-off by default. Keep 2 m (the predictor converges the node across mouse events; the ghost shows the sharp shape and the confirm bends the previous element's tail by the blend) — or create appended elements SHARP (exact, but a curvature kink at every boundary until the user drags the diamond)? | Stage 2b's ghost, Stage 3's drag feel. | Keep 2 m + the predictor; the ghost is drawn sharp; documented. |
| 10 | **Laps.** No 360° cap anywhere — the ghost and placed nodes keep increasing the turn past 360° (a planar multi-lap self-intersects on the same circle; your choice). The lap lives in the local Turn value; a global heading box or ring is mod 360 and cannot express "one more lap" — the lap count is kept from the current value. | Stage 2b. | No cap. |
| 11 | **Append-mode scope** = ANY active s/c (the track after re-chains / follows / re-marches), not only the run tail; the element goes at the section's END (today's A inserts AFTER THE SELECTED element mid-list — that insert moves to the border menu); the graph gap and the pick buttons exist only while the mode is on (the spec places them right of "Disable Append Mode"); the '+' item is HIDDEN (not grayed) as the explicit exception to the never-silent rule and keys on the RUN TAIL (the '+' means the run end — clicking it activates the tail section and enters the mode); Esc leaves the mode. | Stage 2b. | Yes. |
| 12 | **Shift/Ctrl on PLACED element-node drags.** On every gizmo drag today Shift = a tenth of the travel and Ctrl = snap (D254, standing). The spec's XZ-plane / vertical-plane snaps are described for the hover ghost. Keep D254 on drags with the planes on the ghost only — or let the planes override D254 on element nodes too (then Alt would carry the tenth/snap there)? | Stage 3a's ball-drag law. | Planes on the ghost only; D254 unchanged on drags. |
| 13 | **Inserted / ghosted helix defaults**: plan R 20 m, 90°, radius pair LINKED, pitch pair UNLINKED with end := start (level-pitched until edited), interpolation linear. The 🔗 asymmetry between the two rows is deliberate per your line 75 (a helix is meant to climb); confirm. | Stage 2 defaults, Stage 3 rows. | Unlinked, end := start; radius linked. |

### Before Stage 3 (element nodes, window, locks)

| # | Question | Blocks | Default |
|---|---|---|---|
| 14 | **Node-drag depth rule** (the hidden question behind "curvature changes last"). Today every ball drag moves in a VIEW PLANE at the press depth; under that rule the arc through the target tangent at the start is unique and κ MUST follow the mouse — "κ last" has no object. Under a MOUSE-RAY rule κ is held and the depth is chosen where the ray meets the reachable torus (a quartic — closed form, 0 to 4 roots, nearest-to-old picks the branch). Which? | Stage 3a's drag law; the solver table. | View-plane point (today's ball law and the ghost's own plane rule); "κ last" applies to under-determined cases only. |
| 15 | **Refuse vs restrict, and where the reason shows.** The spec states two laws for an unreachable target: line 24 "the node's movement itself should be restricted" (project onto the reachable set) and line 36 "it simply should refuse to be edited to that value". Proposal: DRAGS restrict (the node slides on the reachable set), TYPED boxes refuse (the box reverts + one stats sentence). The spec's "a hover tool tip should appear" is honored for STRUCTURAL impossibilities (known before you type: a grayed box with a hover sentence); a VALUE-dependent refusal is only known at apply, so it goes to the stats bar — a deviation from the letter, stated here. | Stage 3. | Drags restrict, boxes refuse; structural = hover, value = stats sentence. |
| 16 | **Lock model.** Literal line 17 ("the values that are not able to be edited should still be displayed as a reference") favors padlocks on the ACTIVE chart only (+ xyz + radius/pitch), the other chart read-only; literal line 36 ("all options … should have a lock") favors locks on QUANTITIES in both charts with a DOF budget (a 4th independent orientation/scale lock refuses). Either way a MIXED set (e.g. curvature + heading, after a chart switch) can arise and leaves one free direction whose nearest point is transcendental: BALL drags then refuse ("drag the ring/arrow instead") while rings/arrows edit the free parameter in closed form — or a bounded 1-D search if #1 allows. A STRAIGHT's orientation locks are locks on the PREVIOUS curve's exit (the chart must express cross-element locks). | Stage 3b's window/rig; the solver's feasibility table. | Active-chart padlocks (locks persist as constraints across a toggle); mixed-set ball drags refuse. |
| 17 | **The global chart's third number** = ARC LENGTH (no separate "Arc length" row; chord as a grey readout) — vs chord ("distance"). | Stage 3a's rows and locks. | Arc length. |
| 18 | **Viewport access.** The spec says a single left click on an element's track opens its window. Every other track click ACTIVATES (D190 parity), balls/markers select on click and open on double-click, and the append mode makes a click CONFIRM. Proposal: single click = activate + select the element; DOUBLE-click (track or end node) = the window; the band's top-left BUTTON = the window; the band's RIGHT-CLICK menu keeps "Edit element…" / "Change type ▸" / "Delete element" (and the window's type chooser also changes the type). | Stage 3a's press rank; Stage 2b's confirm click. | Activate + select; double-click and the button open. |
| 19 | **Curve end-node arrows.** Lines 62/77 say "XYZ position by dragging center dot [no arrows]" yet list radius (start AND end when unlinked) and arc length as gizmo-editable. Rings edit orientation; a scalar needs an arrow or a ring-with-value, and the START radius belongs to the element's start point (the previous node's rig). Proposal: "no arrows" means no TRANSLATION arrows; the curve rig carries a tangent arrow (arc length) and a radial arrow (END radius); the start radius is edited in the window (a deviation from the letter) — or add a second radial arrow at the element's start. | Stage 3a's rig. | Tangent + end-radius arrows; start radius window-only. |
| 20 | **Which chart the element window opens in.** The spec shares the viewport's Local/Global toggle, which is session-only and starts GLOBAL every launch, yet asks for curvature (the LOCAL chart) by default. Proposal: an element-rig chart flag of its own, default LOCAL, mirrored onto the "Gizmos: Local/Global" button while an element rig is revealed. | Stage 3a. | Own flag, default Local. |

### Before Stages 4–6 (interactions, seams/attach, closure)

| # | Question | Blocks | Default |
|---|---|---|---|
| 21 | **B's meanings** keyed to the SELECTION class: roll-lane node → today's lane match (value + slope toward the neighbor / the run head at a closure); element END node → the orientation solve (align the previous section to the next, or the loop target); a highlighted strict seam (revealed seam rig or the seam window's button) → forward align (held angles := the upstream end); the lane's FIRST node → both in one undo step. Shift+B on an element node = B; the one-shot "Align all incl. roll" is a window BUTTON, not a key chord. The same selection-class rule governs A and Delete on an s/c (§2.2 C30). | Stage 5/6 key routing + the seam window. | As stated. |
| 22 | **Loop "break".** "Editing a section before the final s/c should break the connection" — literally delete the link, or mark it OPEN (re-measured per compile, re-closable by the align buttons or a fresh snap; the Break button the only deletion)? And which edits count: an anchor LOCKSTEP/DESIGN_ONLY pitch/roll edit (re-solves the head), deleting the head's first section, a bridge inside the run, a held-yaw follower under an anchor yaw, dragging the connected END node itself away from the anchor. | Stage 6's model. | OPEN; every listed edit opens it (detected by the residual, not by the edit site). |
| 23 | **Appending to a loop-closed tail**: the ghost/gap append and a '+' SECTION append both OPEN the link with a stats sentence (the ghost shows the truth — the new element goes at the end); "insert before the closure" is an explicit border-menu insert at the last border. | Stage 6. | Open + sentence. |
| 24 | **Combine across differing roll flags** (Stage 5, before flag runs exist): refuse with one sentence — a run boundary once Stage 7 lands. | Stage 5. | Refuse. |
| 25 | **Element smoothing law.** Today's κ-component sine ease makes "later track translates" exactly true only for straight↔arc; two-plane arc↔arc blends rotate later track (the numbers in #9). Keep + document, or make the blend a rotation interpolation (exact, and then a smoothed border could migrate into seam smoothing of the same length)? | Stage 4's border split at smoothed borders; a Phase2Gate re-baseline. | Keep + document; revisit after feel-testing. |
| 26 | **Change type** = replace in place keeping arc length, blend and compatible locks, then the absorb — NOT delete + insert (your bracketed question). | Stage 4. | Replace in place. |
| 27 | **A passive END ball on every run tail** (force/geo too) as the drop target for anchor→end drags and the loop glyph's center; plus a non-drag route to close a loop ("Connect end to start" in the last element's window and the '+' menu — the Attach submenu skips the active run's own head today). | Stage 5/6. | Yes. |
| 28 | **The loop's target and solving frame.** The target is the DRAWN start of the run — the head's REAL entry (the spec's "the orientation at the start of the track"); the solver converts that target into the tail's DESIGN frame (consistent with section-local locks) and the window says "(design frame)" on a conflicted piece. (A draft of this document had the design entry as the target; the critic caught it — the loop would visibly miss by the conflict tilt.) | Stage 6. | Real target, design-frame solve. |
| 33 | **Attach option labels.** The spec names them "Move section to be attached" / "Move end of current section"; R15 proposes naming them by what moves ("Move Piece 2 here (its start to this end)" / "Move this end to Piece 2's start") because "section" means a run here and direction words survive reversals. | Stage 5. | The spec's labels unless you prefer R15's. |

### The Resulting slice (rides Stage 1b)

| # | Question | Blocks | Default |
|---|---|---|---|
| 29 | **Resulting over s/c**: per-TYPE pick sets with DEFAULTS (s/c set = Gv + Gl on; lane sets = all off; the D181 clear-on-open becomes "reset each set to its default", so a loaded and a created s/c look alike); five picks over s/c only; per-unit-family scale pairs (D214 generalized); the overlays REPLACE the strip's solid derived Gv/Gl/roll curves. | Stage 1b. | Yes. |

Not a decision after all: the s/c follower's orientation mode — LOCKSTEP is already every new piece's default (no change needed).

---

## 1. The spec, re-organized

Your vocabulary is kept: a RUN (the "track piece") starts at its ANCHOR; a
STRICT SEAM joins pieces; a SECTION is one of force / geo / s/c / bridge; an
ELEMENT is one straight / planar / helical piece of an s/c section; the element
NODE is its end point. (Where the spec says "section" for an element — "a
straight section", "the length of the section" — it is read as the element.)

### 1.1 Element model
- Three kinds: **Straight**, **Planar curve**, **Helical curve**.
- Under the hood curves are defined by **curvature** (°/m), never radius, so a
  curve can pass through straight (κ → 0 → sign flip) seamlessly; the radius is
  shown and typeable as a twin and reads ∞ when huge.
- Planar: curvature (start/end), arc length (turn), direction (the curve plane
  tilted about the track axis); start and end curvature LINKED by default,
  unlinkable; interpolation linear | sinusoidal, **linear by default**
  (your closing correction). Unlinked curvature is INSTANTANEOUS — the center
  drifts, the axis direction is kept (no spiral about one center).
- Helical: axis = the DESIGNED world vertical (tilted under an orientation
  conflict); curvature start/end as for planar (the axis's XZ position drifts
  when they differ); END PITCH start/end UNLINKED by default (the start pitch is
  the previous element's end and is never editable; linking forces end = start);
  interpolation linear | sinusoidal, linear default.
- Linking buttons look exactly like the speed control's accel/decel 🔗 (yellow
  linked / grey unlinked); linking an unlinked pair sets end := start.
- When a node is dragged around a full circle the turn keeps increasing past
  360° (multiple laps) — never a snap back to a short arc.

### 1.2 Roll as a graph lane
- Geometry and roll are completely separate; roll is a graph lane with ALL the
  features and exact behaviors of the force/geo roll lanes (reuse the code).
- Roll settings (domain — see decision 30 —, rate units, orientation, behavior)
  are editable per roll control; a "Roll" button sits right of the element-type
  button; a roll control can be split mid-element ("Split roll control here" on
  a roll node); roll control is independent of element boundaries, which are
  still drawn.
- Defaults: °/m, distance, global.
- The roll can be converted independently ("no shaping, still s/c").

### 1.3 Element nodes in the viewport
- Each element's END NODE represents the element; its orientation is editable
  LOCALLY (curvature + angle + direction) or GLOBALLY (elevation + heading +
  distance), chosen by the existing gizmo Local/Global toggle in the viewport
  and by a button above the orientation boxes in the element window; the other
  chart's values are shown as a reference.
- The node's XYZ is editable by dragging the center dot (no translation
  arrows) and by boxes.
- Position and orientation are two views of the same thing (the node's place in
  space); in restricted cases editing one value adjusts the others of its
  chart; impossible adjustments show a hover tooltip; NO ITERATION (ask first).
- Shift/box select of several element nodes (translate with the primary) AND of
  run anchors, as two separate move classes.
- Ortho views restrict node movement to the view plane; a node never leaves its
  original plane.
- Smoothing between elements legitimately moves later track translationally
  (unlike strict-seam smoothing) and must be considered throughout.

### 1.4 Element-to-element interactions
- BEFORE: moving a CURVE node never changes the element before it; the curve's
  start stays; under locks / ortho the node's movement is restricted; curvature
  changes LAST. Moving a STRAIGHT node may change every value of the previous
  curve (curvature last; the straight's own length is free unless locked);
  straights between keep their length and pass the change to the nearest
  previous curve.
- AFTER: following elements absorb the change so that as early as possible
  later elements only translate; a following curve absorbs with every unlocked
  value and should never need its radius to change; a following straight keeps
  its length and passes the change on.
- Keep it as simple as possible; report missed edge cases.

### 1.5 Element window + locks + gizmos
- One window per element, opened from a button at the top-left of its band
  (element 0's right of the section badge), by double-clicking its end node, or
  by clicking its track (see decision 18). Labels: kind ICON + the element's
  length — straight line with an arrowhead, incomplete circle with an
  arrowhead, spring with an arrowhead.
- Boxes behave like the node editor's (steppers, wheel, Shift/Ctrl, instant
  updates).
- Every option has a lock; all unlocked by default; a locked value changes only
  by its own box and outranks unlocked ones and the value being edited; an
  unreachable request is REFUSED rather than moving a locked box; locks are
  stored relative to the s/c section's start (position + heading), so moving /
  yawing the seam never changes the shape; pitch/roll per the existing seam
  rules.
- Straight: length, end xyz, end orientation (shared with the previous
  element's end / the seam, which always wins). Gizmo: editable orientations, a
  length arrow, center dot = xyz.
- Planar: orientation (with the chart toggle), xyz, curvature pair +
  interpolation, arc length. Gizmo: center dot, editable orientations, radius
  (start/end when unlinked — see decision 19) + arc length.
- Helical: the same + end pitch when unlinked + its interpolation; gizmo adds
  the end pitch.

### 1.6 Element list operations
- Insert an element of a chosen kind at an element border (border dropdown)
  with default parameters, adjusted by hard math when later locks forbid the
  default; refuse with a brief pointed reason if impossible.
- Change an element's type (works "like inserting" — see decision 26).
- Delete an element (edge cases requested).
- Split an element at any roll node into two of the same kind, bitwise;
  refused under sinusoidal interpolation with a "set it to linear" message.
- Split an s/c SECTION at an element border → a strict seam where the node
  was, s/c on both sides, all seam locks locked, no change in position or
  orientation.
- Combine two s/c sections at a seam when their orientations match (seam
  right-click menu).

### 1.7 Seams between sections
- Within an s/c section element boundaries always match in position and
  orientation.
- ALWAYS a strict seam at the start of an s/c section and between two s/c
  sections (and at the end? — decision 31); locked seam values never shift
  under upstream edits (emphasis).
- Remove the per-element "entry ease"; strict-seam smoothing replaces it and
  must work on s/c edges; "Make smoothing real" stays disabled next to an s/c.
- Every existing strict-seam function/behavior identical where possible;
  unlock = follow the previous end, lock = custom value; a seam's / anchor's
  orientation is never changed by editing an element.
- B / Shift+B with the first roll node or the seam node highlighted → the s/c
  start matches the previous section's end (button "B" in the seam window);
  with the FINAL node of an s/c highlighted and the next section an s/c → align
  the PREVIOUS section to the next without moving the next, following the
  loop-closure alignment rules only; it stays attached under later edits.

### 1.8 Connecting runs and closing loops
- Drag-and-snap (all section types): drag a run's anchor over another run's
  end node or vice versa; an indicator while hovering; release attaches with a
  strict seam. Start→end = today's "Attach floating piece". End→start exists
  only for s/c ends: the end node moves onto the anchor (position only), the
  anchor does not move; "Attach floating piece" offers "Move section to be
  attached" / "Move end of current section".
- Loop closure (s/c ends only): "connect" the run's end to its own start —
  purely a placement (optionally orientation) match with no other impact on the
  compiled track; editing a section before the final s/c breaks it; a Break
  button in the end node's window; the connected end node drawn/placed
  differently on the heartline; drag-and-snap end→start of the SAME run
  applies this match instead of attaching; per-axis snap buttons next to the
  locks ("both" align heading and elevation; roll via the lane); while
  connected, editing nodes in the last s/c makes the nodes AFTER follow the
  "before" rules; B / Shift+B on the roll lane aligns value + slope to the
  run's start; "would robotic-arm theory help?".

### 1.9 Append Element mode (viewport + graph, one shared mode)
- A toggles the mode on an s/c; 1/2/3 pick Straight / Planar / Helical; a
  ghost follows the mouse (no iteration); left click confirms; downstream
  updates naturally; in the mode a viewport click confirms and selects nothing
  else, EXCEPT the view-label menu, the graph collapse button, sub windows,
  pane borders.
- The ghost hides (click does not confirm) in fly / "track view" (= the POV
  ride) / while the view moves / over the menu bar, toolbar and stats bar, sub
  windows, borders; the mode stays on until the Disable button, A again, or a
  section switch; it persists after each append.
- Ghost placement: orbit = the plane through the element start perpendicular to
  the camera; Shift = the horizontal XZ plane; Ctrl = the vertical plane along
  the heading; ortho = the view plane if possible (bypassable); helical in
  ortho views — suggestions requested; curves must pass through straight.

### 1.10 Graph editor UX
- Toolbar: on an s/c the Append button reads "Append Element (A)" / "Disable
  Append Mode (A)"; pick buttons "Straight (1)" / "Planar (2)" / "Helical (3)"
  tinted in the kind colors, the selected one saturated, Straight default; the
  '+' menu gets "Append Element" at the top only when an s/c is selected
  (absent otherwise); a revealed GAP between the last element and the section
  end tinted in the pick's color with an "Append [kind]" button that adds the
  default element; the ghost shows the default element while the mouse is over
  the graph.
- Resulting: new s/c sections show the dotted resulting vertical + lateral G by
  default (toggleable); resulting yaw/pitch and a roll of another setting also
  available — five resulting graphs over s/c.
- Colors: straight light blue, planar = today's curve tint, helical purple at
  the same intensity.

---

## 2. Unclear, contradictory, or mathematically impossible as written

### 2.1 The five load-bearing choices the spec implies but does not state

Every analyst arrived at the same five; the verifiers confirmed all five; they
are prerequisites, not options:

1. **A roll-free curve axis fixed at each element's entry** (decision 2).
   Without it "geometry completely separate from roll" and "convert roll with
   no shaping" are false by construction, none of the closed forms below hold,
   and today's banked and off-cardinal turns bend (probed: 10.9 m on
   R20/90°/30° bank; 2.08 m at direction 30; 4.07 m for direction 0 entered
   pitched).
2. **Elements store signed curvature + arc length (+ direction)**, not radius +
   angle. Radius/angle has a pole exactly where "pass through a straight" lives.
   Use L·sinc(κL) forms so κ = 0 is a regular point; cap |κ| at the existing
   0.1 m radius floor. The curvature box's sign follows the reported-LEFT rule
   (D185/D187); today's chip folds the sign into dir ± 180 — the chip arrow
   flips with κ's sign.
3. **The helix is defined by its plan radius with the end elevation linear over
   track arc** (decision 6). With plan curvature c_h and β(s) = β0 + b·s the
   horizontal arc is s_h = (sin β1 − sin β0)/b, the climb
   Δy = (cos β0 − cos β1)/b, so Δy / s_h = tan((β0 + β1)/2) and
   β1 = 2·atan2(Δy, s_h) − β0; the plan view is a circle of radius 1/|c_h|
   whatever the pitch does. Pitch linear in HORIZONTAL arc or in turn angle is
   transcendental — the interpolation variable must be fixed as track arc.
4. **Every solve runs in the section's canonical (start) frame**; the world
   target is converted once per event from the current compile's Span data.
   This is what makes "locks relative to the section start" free (the compiler
   already generates every section at the origin with its heading stripped,
   D199) and what prevents a hidden fixed point through bridges, smoothing or
   the lockstep settle. The conversion is a floating rotation, so "bitwise
   identical element parameters after a world-frame drag on a yawed copy" is a
   1e-12 gate, not a bitwise one.
5. **One roll lane per section** (decision 4) — the only shape under which
   "exact behaviors as existing roll lanes" is literally true.

### 2.2 Contradictions and collisions (spec vs spec, spec vs code it says to keep)

C1 **Roll "for each element" vs "independent of element boundaries" vs "exact
roll-lane behaviors".** Three readings of what a roll control is. Resolution:
one lane + flag runs whose boundaries sit on roll nodes; "for each element" is
realized as "for each roll control, which you split wherever you like".

C2 **"ALWAYS a strict seam at an s/c start" + "exactly today's seam behaviors"
vs today's seamless '+' straight (a plain in-piece boundary), two existing lock
defaults, a follower's first section never being reconciled (D135), and a
follower generating at its STORED design pose.** Taken literally, every s/c
would wear ≠v after any upstream speed edit, KINK after the first upstream
re-aim under the all-locked default, and sit at a stale orientation (≠o) after
an upstream pitch/bank edit even when unlocked. Resolution: decision 5
(all-unlocked automatic seam + a real-anchored, reconciled s/c follower first +
generation at the follow contract from the Span's exact pair). Gate: the '+'
s/c as an all-unlocked follower == today's in-piece s/c BITWISE at creation,
under head x/y/z + yaw and under upstream pitch/bank edits. Cost: the
automatic seam needs the boundary's compiled Span (one extra whole-document
compile per '+' / Insert / convert, UNMEASURED — `-Dfvd.timing=1` says;
migration shares one compile per piece) — the design should create the
follower from the compile already in hand where possible. Side effects to
handle: ties, combs and the heartline anchor per PIECE today (the tie grid and
comb loop start at each piece's first sample; one heartline strand per piece),
so every s/c start would re-anchor the ties and combs (a short tie gap or
double at each new seam) and break the heartline strand — anchor them per RUN
instead; the gray section-boundary disks at s/c starts vanish (those
boundaries become seams).

C3 **"Remove the entry ease, replace it by seam smoothing" vs "element
smoothing moves later track, seam smoothing must not".** The replacement is a
different law: the entry ease was a pure bank projection that never moved the
spine; seam smoothing slerps frames AND re-marches positions inside the window
(0.2 rad / ±5 % caps) and would also blend the curvature step at the seam.
Whether a new s/c needs ANY entry blend depends on decision 3: a global-rate
lane holds the incoming bank from the first sample (nothing to ease), an
absolute lane snaps. Also: a border split at a smoothed element boundary cannot
honor "no change in position/orientation" because the two blend laws differ —
refuse there (or migrate after decision 25). Once seam smoothing reaches s/c
edges the strip carries TWO diamond kinds with two laws (element blends move
the track; the seam diamond does not) — they must read differently. D232's ban
had two reasons — "conflicting eases" (gone with the ease) and "no lane form
for the window" (still true: make-real cannot produce a lane form from s/c,
which is why make-real stays refused).

C4 **The key B gets three meanings on one strip** (lane edge match / seam
forward align / align-previous-or-closure), and "Shift+B does the same as B"
collides with Shift+B = all three lanes today. Resolution: decision 21.

C5 **"Should actually still be attached" means three different things** in the
align sentence (one-shot write of held angles, then ordinary follow), the
end→start attach (a strict seam — the anchor piece becomes a follower, so later
s/c edits DO move it, which contradicts "no bitwise movement" except at
creation — and even then only to RK4 dust, C26) and the loop (a flag the
compile never reads, OPEN/SATISFIED by residual). One table in Help + D259.

C6 **Locks relative to the s/c start vs the loop target = the run start.** A
locked value holds the s/c's shape relative to ITS start; the closure holds the
END relative to the RUN's start. When the tail s/c is a follower whose seam
position moves, the locks keep the shape and the end leaves the anchor — the
loop OPENS exactly when the locks do their job; inside a closed tail every
interior lock removes a DOF the tail solve needs. Keep locks section-relative;
say in Help "a locked value inside a closed loop can make the loop impossible
to keep — the edit then refuses"; only anchor x/y/z/yaw moves keep a loop
closed for free (D199 rigidity).

C7 **Keys 1/2/3** are the rebindable lane keys over the graph and the FIXED
view digits (Top/Front/Back) over a view or while captured; today they run
BEFORE the lane keys. With a roll lane on s/c, "3" over the graph off-mode
would select the roll lane while on-mode it means Helical. Resolution: fixed
pick keys intercepted only while the mode is ON and no pane is captured, main
scene only; off-mode `1`/`2` over an s/c refuse with a sentence ("straight/
curve sections author their shape as elements — only the roll lane exists");
`3` selects the roll lane. Record the s/c-only supersession of D190's "A over a
view never appends".

C8 **Resulting "enabled by default when created" vs the per-user picks cleared
on EVERY file open (D181) vs the s/c strip's un-toggleable solid derived Gv/Gl
vs five comps of two unit families on one scale (D214).** Resolution: decision
29. Engineering note: the pick machinery has four hard-coded comp = 1..3 sites
and a statically built popup.

C9 **Default interpolation linear (your correction) + radius LINKED by default
+ pitch UNLINKED by default.** Consistent and deliberate (line 75 gives the
reason); decision 13 only asks you to confirm.

C10 **One Local/Global toggle, two meanings** (ring axes today; the element
parameter chart in the spec) — acceptable, but it starts GLOBAL each launch
while the spec wants curvature by default (decision 20). **Shift/Ctrl mean
planes on the ghost but tenth/snap on every drag (D254)** — decision 12.

C11 **"Left clicking the element's track opens its window" vs click = activate
(D190) vs the append mode's "a click purely confirms" vs the glyphs that
coincide at an element end.** Decision 18; ONE glyph where several coincide:
the element end node, the next piece's seam ball, at a loop the anchor ball,
the roll lane's pristine last-node marker (it follows the section end) and, at
an s/c→force in-piece boundary, the gray boundary disk — single click = the
element rig + activate, double-click = the element window; the seam window
stays reachable through the junction button, the seam line and its own rings;
at a loop the end node is a ring around the anchor ball. Note: today's seam
ball does NOT sit at the junction — it sits one integration step inside the
follower (the follower's first compiled sample; a continuation's first sample
is dropped) — invisible today, visible the moment element end nodes exist; fix
it to the upstream span's exit.

C12 **"No iteration" vs the math** — §2.3.

C13 **"Moving a curve node never impacts the element before" vs "in a closed
loop the nodes AFTER follow the before-rules".** The before-rules solve a
POSITION (3 constraints) with one curve + one straight; a closed end with the
snap buttons is a POSE (5 constraints) that one curve cannot meet. Resolution:
position-only closure = the before-rules run from the far end (closed form);
orientation-matched closure = the 3D biarc with a FIXED tie-break (keep the
first curve's κ when unlocked, else equal magnitudes); fewer than two free
curves after the edited node → refuse with "insert a curve before the end or
break the loop". Straight–curve–straight meets a full pose only when the anchor
lies in the (T0, T1) plane — refuse generically in 3D. A SINGLE snap button
(position + one angle = 4 constraints) is unanalyzed — the design doc works it
out (one curve + one straight is a square system).

C14 **"All existing seam behaviors exactly the same" vs the spec's own
make-real exclusion vs today's two s/c bans.** Lift the ENABLE ban (seam
smoothing is type-agnostic sample arithmetic), keep make-real refused (already
refused today — the one spec item with no collision; see C3 for the surviving
reason), and expose a `smoothEngages` dry run so the hollow diamond on an s/c
edge never silently does nothing (a 2 m window at 50 m/s has fewer than three
samples per side and the blend silently skips).

C15 **"Absorb as early as possible" can never cross a strict seam.** Splitting
one long s/c into two (all locks locked, per the spec) therefore ENDS the
downstream absorb the user had before the split — moving a node in the first
half now kinks the seam (locked axes hold WORLD angles) instead of being
absorbed by the second half's first curve. Accept + document ("a strict seam
ends the absorb; unlock the seam's axes to let the next section re-aim"), or
default the border-split seam to all-unlocked — folded into decision 5.

C16 **"Curvature changes last" vs a free 3D drag of a lone curve node.** Under
today's view-plane drag the arc through a point tangent at the start is UNIQUE
(κ = 2Δ_N/(Δ_T² + Δ_N²), Θ = 2·atan2(Δ_N, Δ_T)) — κ has no choice. The
sentence is consistent only under a mouse-RAY drag (decision 14). Under the
default, the priority rule has an object only in under-determined edits
(straight-after-curve, the after-rule, typed box edits): restate it as "κ is
held only where the problem is under-determined or nothing else is free".

C17 **A straight with no curve before it** (element 0, or straights all the way
back to the seam): its orientation is the seam's; the node can only slide on
the tangent ray; orientation boxes refuse with the seam sentence. A straight
has no LOCAL chart of its own (curvature/turn/direction are a curve's) — its
window shows the global chart, and its orientation locks are locks on the
PREVIOUS curve's exit.

C18 **"Smoothing between elements moves later track translationally"** is true
only for straight↔arc (symmetric sine ⇒ mean curvature ⇒ total turn preserved);
arc↔arc in different planes also ROTATES later track (probed: 0.014° / 0.12° /
0.49° for 2 / 6 / 12 m blends). Decision 25.

C19 **"Split an element at a roll node … bitwise"** is not bitwise today: every
element border is an RK4 breakpoint, so the legs re-cut and the sample bits
change although κ(s) is identical; AND the integrator re-sums element lengths
into every later element's start and the section total, so even with
L1 + L2 == L in doubles the later starts re-round. A bitwise constant-κ split
needs the profile to MERGE equal-κ adjacent elements into one leg schedule (no
break, the parent's single length) — otherwise promise "to dust" (1e-12 class)
honestly. Your sinusoidal refusal is correct: a linear ramp restricted to a
sub-interval is again a linear ramp; a sinusoidal one is a phase-shifted
partial cosine the model cannot represent.

C20 **"Split an s/c section at a border … no change in position/orientation."**
Closed form via the existing machinery (partition the elements, compile, take
the Span exit, Make-seam-strict) — "to dust" (1e-12 m class), never bitwise:
the right half generates in a new frame; impossible at a smoothed border
without a shape change (C3); element-value LOCKS on the right half must be
RE-BASED into the new seam frame (the rebaseAbsHeading precedent), the roll
lane is cut at the border (the splitLanes de Casteljau identity) and speed
controls / marks are partitioned (the SpeedControl.portion recipe); the inverse
Combine must undo all three.

C21 **"Change type … delete then insert?"** No — decision 26.

C22 **"The actual end of the section [the strict seam]."** The end of an s/c is
a strict seam only when another s/c follows (or under decision 31); before a
force/geo continuation it is a plain boundary, before a bridge it is the
bridge's entry, at a run end it is nothing. Define the gap against "the
section's end boundary". Today the strip claims its own end boundary before the
seam line (the seam menu / diamond at an active s/c's end is unreachable from
the graph) — the press ladder must be fixed with the seam invariant.

C23 **Helix words.** "Pitch" = end ELEVATION angle (the only reading that fits
"[global]", "same as the end of the element before it" and "already included
in orientation?"); "radius" = PLAN radius (the osculating radius is
r_h / cos²β); "deg/m" on a helix names three different numbers (plan heading
rate, per-track-metre heading rate, body curvature) — label the box "Plan
curvature (°/m)" with a "Plan radius" twin and a grey "track R" readout.

C24 **Append-mode scope**: "the last s/c section of the run" vs "if the s/c is
not the last section … update naturally" in the next sentence — decision 11.

C25 **"Drag … or vice versa"** (an END node) exists only for s/c tails;
force/geo tails have no end glyph — decision 27 (a passive END ball).

C26 **"No bitwise movement of the anchor" at end→start attach**: the solver
lands the end analytically, the compile integrates with RK4 (~1e-7 m / 1e-6°
apart) plus the blend offset (6.6 mm at the default blend), and the follower's
position is the COMPILED exit — the anchor moves by that at creation and rides
the s/c end afterwards. Also: end→start attach REORDERS the timeline (the
target run moves behind the tail run) and can move the ride entry if the target
was piece 0. Accept + document.

C27 **The loop's speed.** A closed run has a design speed at its start and a
real speed at its end; under friction v_end < v_start strictly whatever v_start
is (raising the start speed raises the end speed by less) — so WITHOUT a speed
control inside the loop there is no fixed point and only a read-only gap line
is honest. WITH an exact-speed control inside the loop (a lift or launch to a
target) the end speed no longer depends on the start speed, and a one-shot
"Set start speed to the loop's end speed" closes the gap in one write — on a
run whose HEAD shape is speed-independent (an s/c head); a force-headed run
re-solves its head's shape under the new speed and reopens the loop. "No other
impact" forbids a silent speed write either way.

C28 **"Arc length [already included in local orientation?]"** — yes,
L = Θ/|κ|; and "distance" in the global chart is undefined (decision 17).

C29 **Refuse vs restrict** for an unreachable target — two laws in the spec
(lines 24 and 36); decision 15.

C30 **The A and Delete keys collide between the roll lane and the mode.** "All
features and exact behaviors as existing roll lanes" includes A = a 1 s/10 m
hold-insert after the selected ROLL node (the D255 append chain) and Delete =
delete the selected node; the spec also makes A toggle Append Element mode on
an s/c, and today's Delete on an s/c deletes the selected ELEMENT first.
Resolution: keyed to the selection class — a selected roll node → the lane's
A / Delete; otherwise A = the mode toggle and Delete = the selected element
(never silent).

C31 **Today's banked and off-cardinal s/c turns bend the spine** (the probes in
decision 2) — not a spec contradiction but an existing defect the "no shaping"
requirement exposes; its fix (decision 2) re-baselines every gate and old file
with such turns — the Stage 0 inventory names them.

C32 **"Convert Real to Design (keep track shape)" on an s/c** is a speed
re-freeze only today (an s/c's shape is speed-independent). With a °/s RATE
roll lane the twist depends on the design speed, so the re-freeze would change
the bank — "keep shape" breaks. Resolution: the op re-times a °/s lane to the
same twist at the new speed (°/m lanes — the default — are unaffected), or
refuses with a sentence.

### 2.3 Where "no iteration" meets the math (your request to be told first)

For the DEFAULT elements — constant curvature, linked values — in perspective
views, and for every lock combination within one chart or on XYZ alone,
nothing iterates: the circle through a point tangent at the start, the tangent
line from a point (straight-after-curve), the helix inverse above, the
single-curve absorb (orientation only) and the biarc (two free planar curves
meeting a full pose) are all closed form; the forward kinematics of a chain is
a product of rigid transforms (exactly the robot-arm picture — each constant-κ
element is exp(axis, κL) plus a translation). Two singular sets need a refusal
or clamp rather than a solve: the backward tangent half-line (unreachable by
one arc) and, for a free curve followed by a FIXED-length straight, the sphere
|Δ| = L (κ → ∞ on it; inside it the only solution turns past 180°).

What is NOT closed form:

| Case | Why | What to do (v1) |
|---|---|---|
| Unlinked (varying) curvature in a plane, end-node drag or typed xyz | Linear κ(s) is an Euler spiral — the end position is a Fresnel integral; sinusoidal κ gives Anger/Bessel-type integrals. Forward evaluation is a quadrature (fine); the inverse is a 2–3 unknown Newton. (A lever worth noting: RADIUS linear in TURN ANGLE has an elementary position integral — ∫(r0 + aψ)cos ψ dψ = (r0 + aψ)sin ψ + a cos ψ — so that interpolation variable would make the forward map closed form and deterministic; the inverse still root-finds.) | Refuse position-driven drags on such elements with one sentence; boxes (κ0, κ1, L) and the orientation rings still work in closed form. Bounded internal Newton as v2 if decision 1 allows. |
| Sinusoidal pitch on a helix, end-node drag | Same class (Anger integrals). | Same. |
| A STRAIGHT node after a pitch-linked HELIX | The straight continues at the helix's elevation while its heading comes from the helix turn — the before-rule system is transcendental. | Length-only drag (restrict) + the sentence; boxes work. |
| Exact landing under INTER-ELEMENT smoothing (ON by default: every new element blends 2 m) | The boundary sits inside its own blend window and is the CENTER of its exit blend — the compiled end node depends on the NEXT element's curvature too; probed 6.6 mm off the sharp node per R20/90° boundary at 2 m, 59 mm at 6 m; inverting the blended position is Anger-type. | Solve on the SHARP chain; the glyph sits on the compiled heartline; the mouse target is corrected by the (compiled − sharp) offset frozen at the press and re-measured per event — converges across mouse events (the D69/D154 precedent "a press or two"). A typed box lands one offset off and says so, or runs the corrector ≤ 3 times internally if allowed. Decision 9 also allows appending sharp. |
| Mixed local + global locks leaving one free direction | The reachable set is a 1-D curve (torus ∩ plane etc.) whose nearest point is transcendental except in special planes. | Decision 16: ball drags refuse, rings/arrows edit the free parameter exactly; or a bounded golden section (≤ 40 closed-form evaluations). A locked arc length under a position drag has a closed-form rule if the drag keeps the cursor's bearing (κ = 2θ/L). |
| A helical / ramped tail under align-previous or closure | Transcendental inverse (as above) with 5 constraints. | Refuse with a sentence naming the element pattern needed; the planar biarc is closed form. |
| κ- or Θ-locked nodes dragged in a FIXED ortho view | The in-plane reachable curve is torus ∩ plane (a quartic) or cone ∩ plane; no clean nearest point unless the plane contains T0 or is ⊥ T0. | Lock the direction to the view plane while in ortho (exact), or a bounded 1-D search if allowed; unlocked nodes land exactly in-plane (κ free). |

Everything the compile already does internally (Newton landing legs, the
reconcile's bounded passes, the bridge march) stays as it is; none of it is
"the user iterating to a value".

### 2.4 Your embedded questions, answered

- **Cases where adjustment is impossible** (orientation/position): everything
  locked; element 0's direction (owned by the seam); a straight with only
  straights behind it (ray only); the backward tangent half-line and the
  radius-floor horn torus around the start point (unreachable by one arc); a
  straight-node target inside the previous curve's circle with its κ locked
  (with κ UNLOCKED it is 4 unknowns for 3 equations — tie-break: the straight's
  length goes to its floor first, then κ tightens); an over-constrained lock
  set (a 4th independent orientation/scale/xyz lock under quantity locks; a
  mixed set after a chart switch under active-chart locks); position +
  orientation both demanded of ONE element (five constraints, three DOF —
  needs two elements); a helix from a vertical end or with |end elevation| ≥
  90°; a heading or bank edit on a vertical END tangent (loop tops and pull-up
  apexes have no heading — the global chart has a pole there, the local chart
  does not); a closed loop with fewer free curves than constraints. Each gets a
  one-sentence hover reason (structural) or a stats sentence at apply
  (value-dependent); drags restrict instead (decision 15).
- **Robotic arms.** Yes as a framework, no as a necessity. A chain of
  constant-curvature arcs is the piecewise-constant-curvature continuum-robot
  model: forward kinematics is the product of exponentials; one-segment
  position IK is closed form; two-segment full-pose IK is the biarc family
  (one free parameter — fix it by rule); general n-segment IK with limits is
  numeric (Jacobian / damped least squares), which is exactly the thing the
  spec forbids. The useful import is the classification (which DOF each joint
  offers, which locks over-constrain) and the single-absorber discipline.
- **Helical curves in orthographic views.** Top view: the horizontal problem
  is fully specified (plan curvature and heading change in closed form); keep
  the end pitch, let the climb follow. Side views: keep the plan radius, solve
  the heading change from the in-plane horizontal coordinate (one trig
  equation, two branches, nearest to the current), the climb from the vertical
  one; the Tangent view counts as a side view. Modifiers are no-ops in ortho
  with a readout note ("ortho: view plane"). On an orientation-conflicted
  piece the helix axis is the DESIGN vertical — the ghost must be built in the
  design frame and rotated by the piece's tilt, not built from the true exit.
  An explicit SNAP (drag-and-snap or the loop snap) onto an anchor outside the
  view plane is exempt from the ortho plane rule — a snap is a deliberate
  landing (one Help sentence).
- **Curvature vs radius under the hood.** Store κ, L, dir; no dynamic switch
  is needed: a tiny radius is a large κ bounded by the radius floor, a tiny κ
  is regular with sinc forms.
- **Smoothing between elements and locks.** Locks and solves act on the SHARP
  chain; the blend moves the compiled node by a sub-element offset handled by
  the predictor (§2.3). A blend-length drag with a locked downstream XYZ
  violates it by centimetres inside the window — documented.
- **Edge cases of the element interactions** (the ones that change rules): the
  absorbing curve's turn collapsing to zero (hold L at the 0.5 m floor and let
  κ → 0 continuously — the one place the absorb changes κ); an antiparallel
  re-aim (keep direction, Θ = 180°, lap count kept); no curve AFTER the node
  (nothing absorbs; the section exit rotates; a strict follower keeps its
  locked angles — that IS the absorb at the section level); a target on the
  backward ray; multi-select across two sections (solve in chain order, each
  in its own frame — and a "translational" group move is not rigid: a selected
  curve node reaches old+Δ only by changing its parameters, so its exit
  tangent moves and the next selected node's own target then overrides the
  implied translation; the law must be stated and gated); a helix as absorber
  has reduced DOF (a pitch-linked helix cannot absorb elevation); the absorb
  is complete only with the element planes referenced to the level frame
  (decision 2) — in the body frame a residual twist about T remains.
- **Deleting an element.** The only element (refused today, but the Delete KEY
  path is silent — post the reason); element 0 (its successor's entry blend
  vanishes and today its smoothIn would be re-read as the entry ease — gone
  with the ease); under a loop closure the last element's deletion opens the
  link; speed controls and roll nodes past the shortened end clamp onto the
  wall with a hint; an open element window on a later index re-resolves or
  tears down with a sentence; with a roll node selected Delete deletes the
  NODE (C30).
- **Splitting an element at a roll node.** Simple in the model; bitwise only
  with the two fixes in C19; refuse on a border, inside a blend window and
  under sinusoidal interpolation (three sentences); the roll node is only a
  POSITION pick — the lane is untouched.
- **Append-mode click exceptions missed**: the "Gizmos: Local/Global" button
  and the tangent ↻/↔ buttons (overlay controls, same class as the view
  label); a graph context menu's closing click (consumed by auto-hide, no
  confirm); the multi-coaster "Switch to editing" prompt (the confirm wins; the
  Coaster menu keeps the switch). And the HIDDEN-ghost click: "purely for
  confirming" applies only while the ghost SHOWS; a click with the ghost hidden
  keeps its normal meaning (activate), never a silent nothing.
- **Ghost-hide cases missed**: any live gesture (node, gizmo, disk, box
  select); the convert preview and the start wizard (the mode cannot start);
  the window losing focus (cosmetic); the toolbar row and the stats/POV bar
  (not a view: hidden, like the menu bar); a stray typed spinner committed by
  the confirm press itself (every scene commits a typed-but-unentered box on
  MOUSE_PRESSED) can move the exit under the click — the confirm re-reads the
  current exit and refuses if it moved since the last ghost.
- **Mode-disable cases missed**: undo/redo that changes the active section or
  replaces it with a non-s/c; File > New / Load / Switch Coaster / Delete run;
  the convert preview or the split line opening; the wizard opening; riding the
  POV into another piece (its activation ends the mode — document); the '+'
  pressed while an interior s/c is active (it activates the tail first).
- **Vertical end under Ctrl**: the spec's "vertical plane in the same heading
  direction as the end of the element" is circular (the ghost's END heading
  depends on the ghost) — read it as the START tangent's heading; a vertical
  start has no heading (the horizontal-right fallback picks world X below
  |T.xz| ≤ 1e-9) — fall back to the camera plane with a readout note; never
  snap to infinity (|dir·n| < 1e-4 guard, t > 0 in perspective). The default
  plane also degenerates when the start tangent points at the camera (every
  in-plane target gives a 180° semicircle) — fall back to the plane through the
  tangent and the view horizontal.
- **Downstream "updates naturally" after an append**: it already does — every
  downstream section re-chains from the edited exit, followers follow in
  position and keep locked angles, bridges re-capture and re-march; the edge
  cases are a trailing ORPHAN bridge (stays frozen, the gap opens, badge "!")
  and an optimizing bridge under Optimize length (one at-rest knee per
  append).
- **Entry-ease removal and old files**: decision 7.
- **Combine conditions**: both s/c, the follower active, every LOCKED axis
  within 1e-6° of the incoming frame, smoothing off at the seam, neither
  reversed, no orientation conflict; grayed with ONE measured sentence per
  cause ("Orientations differ by 3.2° — align them first (B).").

### 2.5 Smaller gaps the spec leaves open (defaults chosen; say if you disagree)

- The time VIEW stays in the badge menu (decision 30 on "domain").
- The Roll button opens the EXISTING Orientation-settings dialog grown by the
  Rate-units and Roll-behavior rows, keeping its two verbs (Switch = flags
  only, Bake = refit the same compiled twist); Bake IS "convert roll with no
  shaping".
- "Interpolation over" = the element's arc length (for radius: see the lever in
  §2.3).
- Roll nodes and speed controls keep their ARC x when elements change length
  (they slide relative to elements) — one Help sentence.
- Sections added or inserted AFTER an s/c inherit its roll flags (today the
  continuation carries flags only from a lane section); the start wizard's s/c
  tile and the Insert dialog's s/c radio show the roll rows (they gray every
  lane row for s/c today) and seed the default lane.
- A locked value's box shows the WORLD number with a grey section-local twin;
  under an orientation conflict the orientation block carries a "(design
  frame)" note and the lock tooltip says which frame it holds.
- One element window at a time (the node editor rule); it has its own
  ElementSnapshot for Cancel (the three placement windows' Cancel never touches
  sections). Its boxes are LiveNum, which today lacks the Spinner path's
  typed-mark / FOCUSED≠TYPING protection (the D254 bug class) — the element
  window's per-event resyncs during a viewport drag would expose it; add the
  protection first. The band's top-left button opens the window; the band's
  right-click menu keeps Edit element… / Change type ▸ / Delete element.
- "Insert before element 0" lives on element 0's band menu; border menus exist
  for interior borders only (border 0 = the seam menu, the end border = the gap).
- Element 0's top row holds THREE buttons ([badge][element][Roll]) — a measured
  width ladder, the Roll chip hidden first.
- Element 0 of a strict-seam follower: a STRAIGHT's orientation rows gray with
  "The strict seam owns this orientation — edit it there."
- Under an absolute s/c bank lane the seam window's ROLL row grays ("The bank
  lane sets the bank at this seam — edit its first node.") — two authorities
  over the entry bank otherwise.
- B at a closure on a RATE lane lines up the rate graph but cannot close the
  bank exactly; a closed-form "Close bank at the track start" node-menu item
  does (raise the last node by 2·Δbank/L).
- Viewport BOX SELECT (no gesture exists today; a left-drag on empty space does
  nothing in orbit, middle-drag orbits, right-drag pans in ortho): a PRIMARY
  drag on empty 3D space = the box (Shift adds), in both lenses; rig parts and
  glyphs under the press take precedence (the picker runs first); in Append
  Element mode a drag is inert (a confirm is a clean ≤ 3 px release); the box
  collects element nodes and run anchors as two classes.
- Every s/c becomes its own piece, so the POV's piece-follow re-activates the
  editor at EVERY s/c start (and restarts the ride at the active piece's start
  when it changed) — the follow must treat follower pieces as one run for the
  ride, or the s/c-heavy layouts will flicker activation.
- Reverse… grays on every multi-piece run today; under the seam invariant every
  [force][s/c] layout is multi-piece (decision 32).
- Every "a piece-first section is never reconciled" site goes stale with
  decision 5 (refreezeSpeedOnly's k == 0 inertness, hasUpstream, the
  init-speed window's dry run and its sentences, Section.java's javadoc) —
  one list in the design doc.
- Dragging the connected END node itself away from the anchor opens the loop
  (decision 22).
- NL2 export of a CLOSED run: both exporters write an open strip (the element
  exporter marks both endpoints strict) whose end sits within the loop
  tolerance plus the blend offset of its start — NL2 would show a seam; v1
  documents it, a later "closed" flag can mark it.

---

## 3. Recommendations

R1 **Model**: (κ signed, L, dir) per planar element, (plan κ_h signed, L or Δψ,
end elevation + link flag) per helix; Straight stays a kind for the UI but is
the κ = 0 planar element mathematically (Straight ↔ Planar at κ = 0 is a
geometric no-op). Radius is a display twin.

R2 **Roll-free axis** per decision 2, fixed at the element entry; one gate pins
"identical spine for every roll-lane setting"; a 54-document SHAPE INVENTORY
run in Stage 0 BEFORE you decide — for every file in the recorded clean-span
dump plus your `.uvd`/`.fvd` coasters, per s/c element: today's deviation from
the exact circle its parameters name (that deviation IS how far it will move)
— so decisions 2 and 7 are answered with numbers, not adjectives; a
`LEGACY_BODY_AXIS` switch lives for one stage as the reversal lever.

R3 **Helix** per decision 6; label the row "End elevation (°)" with a grey
"start 12.3°" beside it.

R4 **Roll** = one lane + flag runs, section-wide first (decision 4); default
two-node hold at 0 that FOLLOWS the section end (the D248 pristine-hold rule);
the Roll button = the grown Orientation dialog; the lane, its flags, end-follow,
locks/links and the loop flag ride copySection / TrackPiece.copy /
mirrorPieceCore / SecState, with MirrorGate and CopyGate re-pinned.

R5 **Seams** per decision 5 incl. the generate-at-the-contract branch; the
four lock defaults in one Help line; lift the s/c smoothing ban; keep make-real
refused and pin its sentence; `smoothEngages`; the seam ball at the junction
(not one step inside the follower); the strip's press ladder reaching both
seams of an active s/c; "Un-strict seam" refusing on an s/c-first follower;
ties, combs and the heartline anchored per run; the junction renumbering
stated in the migration warning.

R6 **One pure-Java solver (ScSolve)** with a PUBLISHED table (lock combination
→ reachable set → closed form → refusal sentence) that every surface calls
(ball, rings, arrows, boxes, ghost, insert, align, closure); its result is
{EXACT | PROJECTED | REFUSED(reason)}; gated by ScSolveGate (inverse∘forward
identity ≤ 1e-12, lap sweep, pass-through-straight sweep, projections
idempotent, analytic-vs-integrator agreement ≤ 1e-7 m, the helix inverse
against a quadrature, world-frame drag on a yawed copy ≤ 1e-12). Node glyphs
sit on the integrator's LANDED boundary states (recorded per boundary); the
solver reads the sharp chain and corrects the mouse target by the frozen
press-time offset. A `-Dfvd.scsolveprobe=<file>` hook prints every element's
end node in both charts, every feasibility verdict under a set of lock
combinations and the predictor's residual per iteration on your files — the
"readable report" you can hand-test before any UI exists (the spec's "bring it
up to me before implementing").

R7 **Breakpoints only where κ(s) is non-smooth** and equal-κ neighbors merged
into one leg schedule, so constant-κ splits are bitwise; record both the sharp
and the landed pose per boundary.

R8 **Locks** per decision 16; one absorbing curve; hard locks; refusal naming
the element and value; κ changes only when under-determined or nothing else is
free; drags restrict, boxes refuse (decision 15).

R9 **Append mode**: owned by GraphEditor (the viewports read it through
`app.editor`); A toggles on s/c only unless a roll node is selected (D190 kept
for lanes; C30); 1/2/3 FIXED picks in-mode, uncaptured, main scene; Esc exits;
the pick buttons and the gap exist only in-mode (the spec's placement); the
whole tinted gap is the button; the ghost is sampled ANALYTICALLY (~0.25 m)
and re-aimed once per pulse from the axes timer — never through the integrator;
a bottom-left readout in combLabel ("Planar R 20.0 m · 90° (left, flat)" + the
plane in force + refusal notes); a faint disc shows the chosen plane while
Shift/Ctrl is held; the mode is visible on the strip (outline + gap tint); a
held A toggles once (the saveKeyHeld latch); the confirm re-reads the exit;
right-click on the gap or the ghost opens the pick as a small menu.

R10 **Element window** on LiveWindow + LiveNum (with the typed-mark protection
added): type chooser = the three icons as toggles; a grey "starts heading 45° ·
elevation 12° · bank 0°" reference line; ONE orientation block of three rows
[label][box][🔒][↺][grey other-chart value] with the Local/Global button above
(local: Curvature (twin R) / Turn (twin L) / Direction; global: Heading /
Elevation / Arc length); pairs on one row [start][🔒] 🔗 [end][🔒] [lin|sin]
(the end box mirrors while linked); position rows End x/y/z (world) with the
local twin; ~8 rows straight / 11 planar / 12 helical. Refusals: structural =
gray + hover sentence; value-dependent = the box reverts + one stats sentence.
Ctrl-snap steps for the new boxes: 1 °/m curvature, 1 m length, 5° angles, the
element-length step for xyz.

R11 **Viewport**: end-node glyphs for the active s/c (fixed-size world-layer
balls, the reveal-mark recipe); a rig per kind = center ball (xyz, view plane)
+ rings per EDITABLE orientation DOF + one tangent arrow (L / straight length)
+ one radial arrow (end radius) (decision 19); single click = activate +
select, double-click = window; one glyph where the end node and the next seam
ball / disk / marker coincide; at a loop the end node is a ring around the
anchor ball; in ortho the ball lands exactly in-plane (κ free), rings show only
when their motion stays in-plane, the Tangent view counts as a side view; box
select per §2.5.

R12 **Interactions**: the before-rules as the node drag itself; the absorb
orientation-only on the first free curve; straights translate; insert =
quadratic in L against a locked next curve or the single-curve inverse; change
type = replace in place; Delete key never silent; the border click law = the
seam diamond's (≤ 3 px click = menu, drag = resize); border menu = Split
section here · Insert ▸ Straight / Planar / Helical (interior borders only);
the band's right-click menu keeps Edit / Change type ▸ / Delete.

R13 **B** per decision 21; "Align to previous end (B)" on EVERY strict seam
window (type-agnostic: held angles := the upstream exit's Euler triple, locks
kept); a seam-selection state (revealed seam rig or open seam window) so B has
a target without a node; "Align all" (heading + elevation + the roll lane's B)
as a window button at the loop.

R14 **Loop link** = a section flag the compile never reads, SATISFIED/OPEN by
residual (1e-4 m / 1e-3°), Break the only deletion; the REAL start as the
target, solved in the design frame (decision 28); carried by Copy / Mirror /
Switch Coaster; cleared with a sentence by Reverse… / a left-half partial
convert; a read-only loop-speed gap line (C27); a non-drag "Connect end to
start" route (decision 27).

R15 **Attach**: option labels per decision 33; drag-and-snap start→end =
exactly `attachFloating`'s tail (one seam state for both routes); end→start
seeds all three locks; snap radius = the candidate's own pick band; indicator =
both balls hot-tinted + a combLabel sentence ("Release to attach to Piece 2" /
"Release to close the loop" / the refusal in gray).

R16 **Resulting** per decision 29; `resRollRedundant` reads the s/c roll flags
(redundant iff every run matches the Resulting settings).

R17 **Colors**: one `SC_KIND_COLOR[kind]` source (bands, chips, pick buttons,
gap, ghost, window accent, Help legend) with the 0.09/0.17 alphas; proposed
straight rgb(120,185,235), planar rgb(235,130,180) (today's rose — you called it
red), helical a violet shifted toward magenta (purple is the TIME color
everywhere — hand-test against the POV marker).

R18 **Migration** = ONE load-time pass in the order geometry (κ-form carrying
the legacy radius/angle pair so re-saves stay bitwise) → roll lane → follower
+ locks; the ease dropped when the dry compile shows it inert, else becomes
seam smoothing; one warning line (incl. the junction renumbering); gate: each
of your files opens with exactly the rewrites the Stage 0 inventory predicted
(renamed `.uvd` copies of the `.fvd` ones).

R19 **Simplifications without lost function**: no separate Arc-length row; no
per-control domain (decision 30); planes on the ghost only; the badge's
"Append element (10 m straight)" → "Append element ▸ Straight / Planar /
Helical" (direct add) or dropped; Shift+B on element nodes = B (no fourth key
meaning); the mid-list "insert after the selected element" moves from A to the
border menu.

R20 **Scope discipline for v1**: refuse what is not closed form (with the
sentence saying which element pattern would make it possible); ship the
bounded Newton only as an opt-in v2 after decision 1.

R21 **A reversal lever per stage**: every stage's summary names the ONE switch
(a constant, a flag, a menu row) that undoes its UX if you hate it after the
hand test — your decisions here are feel-driven and have reversed before.

R22 **A reuse map in the design doc** (spec item → existing function → size of
the change) as the recipe table every Opus brief cites.

---

## 4. The plan

### 4.1 Shape of the work

Eight implementation stages (Stage 1, 2 and 3 each split into two RUNS with a
resume point between) after a decisions stage, each run sized to ONE usage
window (≈ 5 h, roughly ≤ 8 M subagent tokens), each ending in something you can
hand-test or a printed headless report, each carrying the standing rules: an
edge-case ENUMERATION before coding (section type × domain view × roll flags ×
follower / bridged-in / tilted (≠o) × multi-coaster × undo/redo × open live
windows × legacy reversed × ortho views incl. Tangent × fly/POV × the convert
preview × the wizard × speed controls on the arc × a vertical tangent at the
start or end), ONE lean reviewer right after each larger item, a ~3-lens final
review, gates green on wiped classes (DetermGate197-200 + the core battery + the
new gates), docs (DECISIONS D259 sub-numbered per stage, TESTING #176+,
CLAUDE.md, Help), tooltips one sentence, never a silent click, a reversal lever
named per stage, nothing committed until you ask.

Three independent plans were written from different angles and judged against
this one:
- **Foundation-first** (14 windows): a standalone solver stage before any
  roll/seam work; the first 3D feature arrives ~6.5 windows in; its best ideas
  (the shape inventory, the solver probe hook, the generate-at-the-contract
  compile branch, lettered runs with pure-Java resume points, the list of every
  stale "k == 0" site, whole-run Reverse as a decision) are adopted here.
- **Reuse-maximal** (10 windows, 13 with the deferred novelties): the
  graph-side append mode before the viewport ghost; the loop's orientation
  closure optional; it found the A/Delete selection-class rule, the
  stale-commit re-read, the continuation-flag gap and the un-strict refusal —
  adopted; its reuse map becomes part of the design doc; it defers box select
  and flag runs, which this plan keeps.
- **User-visible increments** (18.5 windows, 13 stages): the solver sliced by
  kind (straight-only drags → planar → helical → locks), locks as their own
  late batch, a reversal lever per stage and a `LEGACY_BODY_AXIS` switch — the
  lever, the switch and the "locks last" split are adopted; the finer kind
  slicing is the fallback if Stage 3a's hand test goes badly.

The order below keeps the foundation-first spine (math proven headlessly, then
UI) but puts the Append Element mode right after the solver because it is the
cheapest feel test of the closed forms and touches no locks.

Estimated total: about 15–17 usage windows plus your feedback rounds (the
verification and critic rounds added scope to Stages 1b, 3 and 4, and the
first sizes were optimistic). This analysis itself cost ≈ 20 M subagent tokens
across its two runs.

### 4.2 Stages

**Stage 0 — Decisions + design doc + inventory (≈ 0.5–1 window; Fable).** The
SHAPE INVENTORY probe first (R2: today's per-element deviation from the named
circle over the clean-span dump and your coasters; the per-file entry-ease
dry-compile result for decision 7) — pasted into the design doc so decisions 2
and 7 are taken on numbers. Your answers to §0 → `docs/SC-OVERHAUL-DESIGN.md`
(≤ ~600 lines, so it is re-read on every model switch): the element model and
file grammar; the roll-lane host and flag runs; the seam invariant, the
generate-at-the-contract branch and the four lock defaults; the migration pass;
the DOF/lock table (every lock combination → reachable set → closed form →
sentence); the B / A / Delete selection-class table; the key table; the ghost
rules; the Resulting pick sets; the reuse map (R22); the gate list per stage;
every stale "k == 0" site. D259 opened. This file is also the model-switch
handoff artifact (§4.4).

**Stage 1a — Geometry core (1.5–2 windows; Fable for the math, Opus candidates
below).** Pure Java: κ/L/dir storage + kinds STRAIGHT/PLANAR/HELICAL with their
forward maps; the roll-free axis fixed per element entry in ScIntegrator.deriv
(+ the `LEGACY_BODY_AXIS` switch); the helix term (the GeoIntegrator
global-axes recipe); κ(s)/β(s) ramps (linear, sinusoidal) evaluated per leg;
breakpoints only at κ-discontinuities + equal-κ merging; the per-boundary state
record (sharp + landed); an ElementChain forward-kinematics class gated against
the integrator (≤ 1e-9); IO grammar with the legacy pair and the inert roll
slots carried, the new kinds; undo / copy / mirror / splitElements / scCutClamp
/ partial convert for the new fields; chips show the kinds. Gates: ScElemGate
(new; incl. the helix inverse vs a quadrature), Phase2Gate s/c re-pin
(direction fixtures, round trip), MirrorGate/CopyGate/InsertGate growth,
DetermGate197-200 on fixtures with the new kinds. Hand-testable: the inventory
numbers and the chips — this stage is proven headlessly. Opus-with-Fable-review
candidates: the IO grammar writer/reader, copy/mirror/undo plumbing, gate
boilerplate from the written recipes. Reversal lever: `LEGACY_BODY_AXIS`; the
legacy pair keeps re-saves byte-identical until an element is edited.

**Stage 1b — Roll lane + seams + migration (≈ 3 windows in two runs; Fable).**
Run 1 (pure Java, resume point = ScRollLaneGate + ScSeamGate + MigrationGate
green): the RollLaneHost on StraightCurveSection ([empty, empty, roll] through
lanesFor); ScIntegrator consumes baked roll keyframes with FVDIntegrator.rollTerm
/ rollTermPhi / bankHermite / projectBank (the lane semantics verbatim, flags
section-wide); the entry-ease retirement in the model (Profile.cT / rollTarget
/ rollEase / globalRoll / RollTracker, scCutClamp windows); the automatic
strict seam at every s/c start (`ensureScFollower` = makeSeamStrict's body with
lock + smoothing parameters; all-unlocked; the s/c follower's first section
real-anchored and admitted to the reconcile at k == 0 with every stale k == 0
site updated; the generate-at-the-contract compile branch; `unstrictSeam`
refusing on an s/c-first follower; Reverse's new gray sentence); `smoothEngages`
beside smoothStrictSeam; "Convert Real to Design" re-timing a °/s lane (C32);
continuation flags from an s/c; the lane / flags / end-follow / locks on
copySection, mirrorPieceCore, SecState; ONE migration pass + its LoadResult
warning. Run 2 (editor + viewport): the chokes that let lane 2 bind over s/c
(lane buttons, the end wall incl. the empty-sibling-lane guard, end-follow,
markers, B routing incl. the cross-piece neighbor fetch, the cross-rate view,
node editor names, the A / Delete selection-class routing); the Roll button →
the grown Orientation dialog (Switch / Bake, roll-only Bake through the density
preview); the wizard's and the Insert dialog's roll rows; the entry-ease UI
retirement (the diamond, band edge, dialog rows); lift `seamScEdge`; the seam
ball at the junction; the strip press ladder reaching both seams; the POV
follow treating the run as one ride; per-run tie / comb / heartline anchoring;
the Resulting per-type pick sets + family scales + overlays replacing the
strip's derived context. Gates: ScRollLaneGate, ScSeamGate (the '+' s/c as a
follower == the in-piece s/c bitwise at creation and under head x/y/z+yaw and
upstream pitch edits), MigrationGate (renamed copies of your files, rewrites ==
the inventory's prediction), SmoothRealGate c re-pin, ConflictGate growth (s/c
follower first; the smoothing × clean-span × real-anchor interplay),
DetermGates, LabelGate. Hand-testable (after run 2): a roll lane over s/c with
B / locks / node editor / markers, the Roll button, the double seam line at
every s/c start, the overlays, your old files opening. Reversal lever: the
automatic seam's lock default and the default roll triple are two constants.

**Stage 2a — ScSolve (1.5 windows; Fable).** ScSolve: forward / inverse /
projections / branch hints (laps, κ sign, dir representation — the
decomposeNear idiom) / Feasibility, the canonical-frame conversion from Span
data, the predictor; ScSolveGate (the full DOF table, one gate row per lock
combination); the `-Dfvd.scsolveprobe` report on your files = the resume
artifact you read before any UI (the spec's "bring it up to me"). No UI.

**Stage 2b — Append Element mode (1.5 windows; Fable for keys/press routing
and the ghost rules; Opus candidates for the toolbar/buttons/gap/menu wiring
from the exact spec).** The mode object (GraphEditor), keys (A toggle on s/c
keyed to the selection class; 1/2/3 in-mode; Esc; the held-key latch), the
toolbar relabel + mode-only pick buttons + gap button with its measured width
ladder (fixed-width labels — the no-jump rule), the '+' item (hidden; keyed on
the run tail), the badge item's fate; the ghost (analytic samples, rail tubes
in the kind color, per-pulse re-aim by signature, the three planes incl. the
degenerate-plane fallbacks, the ortho rule, the helical rules incl. the design
vertical, the fresh-ghost confirm on a clean release, the exit re-read, the
readout, every refusal sentence); every disable/hide case of §2.4. Gates:
GestureGate (gap/ladder/deadband arithmetic), KeyProbe growth ("2 while
captured = Front" stays green), LabelGate. Hand-testable: the whole append
flow in both surfaces. Reversal lever: the plane chords and the
sharp-vs-blended append are flags.

**Stage 3a — Element nodes, window, gizmos, unlocked drags (1.5 windows;
Fable; the window's row construction from R10 is an Opus candidate).**
End-node glyphs (landed states); the rig per kind (decision 19); the element
chart flag (decision 20); the LiveWindow element window (R10) with LiveNum
typed-mark protection, ElementSnapshot Cancel, object-identity resolution,
modelHooks resync; the before-rules as the node drag (depth rule per decision
14; drags restrict); the ortho table; single / double-click routes; the band
button and right-click menu (decision 18). No locks yet. Gates: an in-JVM
element-window probe (the initspeedprobe pattern), ScSolveGate growth,
LabelGate. Hand-testable: drag / type every element kind.

**Stage 3b — Locks (1 window; Fable).** Locks per decision 16 incl. a
straight's cross-element locks, the DOF budget, every "impossible" tooltip,
the plane-snap chord per decision 12, the mixed-set ball refusal. Gates:
ScSolveGate lock rows, LabelGate. Hand-testable: every padlock. Reversal
lever: the lock model is one switch (active-chart vs quantity) in the window
builder.

**Stage 4 — Element-to-element interactions + border menus + list ops (2
windows; Fable).** The absorb (one absorbing curve, hard locks, refusals, the
residual-rotation sentence); multi-select + anchors (the box-select gesture of
§2.5; run heads only; carry suppressed for selected pieces; chain-order
sequential solves with the stated group-move law); insert / delete /
change-type with the absorb; element split at a roll node (bitwise constant-κ,
refusals); the border click law + border menu; the border SECTION split (landed
boundary pose + the makeSeamStrict recipe, all locked; locks re-based; the roll
lane cut; controls and marks partitioned; refuse at a smoothed border) and its
inverse Combine round trip. Gates: ElementSplitGate, BorderSplitGate,
MultiSelectGate, LabelGate. Hand-testable: everything in §1.4 and §1.6.

**Stage 5 — Seam alignment + attach (1.5 windows; Fable; menu/window wiring
Opus candidates).** "Align to previous end (B)" on every seam window; the
seam-selection B; "Connect sections" for s/c‖s/c under the measured conditions;
drag-and-snap start→end (today's connectStrictRun tail + a candidate test in the
ball drag); the passive END balls; the two '+' attach options (decision 33);
align-previous-to-next (the biarc enters ScSolve here, shared with Stage 6; the
single-button 4-constraint case worked out). Gates: RunGate / SeamShapeGate
growth, ScSolveGate (biarc), LabelGate.

**Stage 6 — Loop closure (1.5 windows; Fable).** The section flag, residual,
OPEN/SATISFIED glyph (the ring around the anchor), the window's Loop block
(per-axis snaps to the REAL start solved in the design frame, Align all,
Break, the speed-gap line), the non-drag Connect route, the roll B at the
closure + "Close bank", end→start attach (all locked; the timeline reorder and
ride-entry cases), the closed-tail edit rule (before-rules from the far end;
biarc for pose), append-to-closed-tail (element and section), the NL2 export
note. Gates: LoopLinkGate, CopyGate/MirrorGate growth, LabelGate.

**Stage 7 — Roll flag runs + polish (1 window; Fable for the integrator's run
boundaries, Opus candidates for chips/menus).** "Split roll control here",
per-run Roll chips (their own x; the ladder hides them before type icons),
run-boundary semantics in the integrator (bank0 re-bind, seeding the new run's
first node from the old semantics so nothing moves at creation), Combine across
runs, `resRollRedundant` over runs; the leftover feel items from your rounds.

### 4.3 What each stage must prove (the determinism contract)

Per stage: compile(doc) bit-deterministic; a rebuild at the fixed point after
one element edit is a bitwise no-op (the solver runs once per event and never
inside compile/rebuild); save → load → rebuild bitwise (Double.toString, load
clamps admitting κ = 0 and every κ the app writes); piece x/y/z + yaw and seam
pitch/roll edits leave element bits untouched (canonical storage); the
all-unlocked s/c follower keeps today's in-piece chain BITWISE at creation and
under head x/y/z + yaw and upstream pitch/bank edits (it reads the Span's exact
pair). Known accepted non-bitwise corners to document: the first open of a
migrated file (the follower split and, where the ease was active, the
smoothing re-march; files with off-cardinal / pitched-entry / banked turns move
by the inventory's numbers); the end→start attach's RK4 + blend dust (C26); a
border split "to dust"; the 1e-12 world→canonical conversion of a drag target.

### 4.4 Fable 5.1 vs Opus 5.5, and how to switch without losing anything

Honest assessment: Fable 5.1 is the more capable model; Opus 5.5 is cheaper and
faster but weaker. Per the current model price table the ratio is about 2.5×
($10/$50 vs $4/$20 per million input/output tokens) — and the limit messages
this analysis hit were Fable-specific ("You've reached your Fable limit. Switch
to another model"), which suggests Opus work draws on a separate allowance on
your plan; worth confirming on the usage page, because it changes the trade
from "cheaper" to "additional budget". This codebase's history is that
"mechanical" wiring has repeatedly hidden subtle bugs (focus/typing guards,
undo chokes, null-before-hide, the ECJ stale-class trap), and the verification
round found exactly that class of hazard in the spec's "simple" parts
(LiveNum's missing typed-mark protection, the Delete/A routing, the
stray-commit confirm), so the saving must be weighed against the review it
requires.

Keep on Fable: Stage 0; every piece of math (ScSolve, the roll-free axis, the
helix, the biarc, the migration decisions); the lock/DOF model; the interaction
rules; the seam/closure semantics; key and press routing; every edge-case
enumeration and every review; root-causing anything you report.

Good Opus 5.5 work (an exact written brief citing the reuse map, then a Fable
review before it counts): the IO grammar writer/reader and the copy/mirror/undo
plumbing in Stage 1a; the Resulting per-type pick sets and the wizard/Insert
roll rows in 1b; the toolbar relabel, pick buttons, gap rendering, '+' items
and key relabels in Stage 2b (not the ghost math or the key order); the element
window's row construction from R10 in Stage 3a (not the lock semantics);
menu/window wiring in Stage 5; chips and menus in Stage 7; Help / TESTING /
DECISIONS prose drafts from a written brief; LabelGate and GestureGate pinning
from recipes; icon strokes and colors; gate harness boilerplate.

How to do it without information loss:

1. The cheapest safe pattern is NOT to switch the main session at all: keep this
   session on Fable and delegate the mechanical sub-tasks to Opus SUBAGENTS
   (the Agent tool's `model: "opus"`, or a Workflow stage with `model:
   'opus'`), each with a self-contained brief, then review the diff on Fable.
   The main context never leaves Fable, nothing is re-explained, and the review
   is where the risk is caught. (The completeness critic of this very document
   ran on Opus that way and found 19 gaps the Fable agents had left.)
2. If you want a whole stage on Opus (e.g. Stage 7, or a docs pass), start a
   FRESH session on Opus 5.5 at a stage boundary only — after the previous
   stage's gates are green on wiped classes, DECISIONS/TESTING/CLAUDE.md are
   updated, and the stage brief is written into `docs/SC-OVERHAUL-DESIGN.md`
   (the brief is the handoff; the new model reads the repo docs, not this
   conversation). Switching the model of THIS session mid-stage is possible in
   the app (the session's model setting; the transcript persists), but the new
   model would inherit a very long context it did not build, and the prompt
   cache is per model so the first turn re-reads everything — a fresh session
   with a written brief is both cheaper and safer.
3. Never switch mid-stage, mid-gesture-rework or with gates red; never let an
   Opus pass touch ScIntegrator, ScSolve, TrackCompiler or Coaster's seam/attach
   code without a Fable review of the exact diff; keep the per-stage memory note
   (`memory/coaster-sc-overhaul-session.md`) current so a resumed session of
   either model knows where things stand.
4. Usage limits: each run is sized to one 5-hour window; a run that is cut off
   resumes from its journal (this analysis was cut off twice and lost no
   finished work). Your weekly limit, not the session limit, is the budget
   that decides how many runs fit in a week — roughly one run per window.

### 4.5 What happens next

1. You answer §0 (or say "defaults", with exceptions).
2. Stage 0 runs the inventory, writes the design doc and D259's header, and
   lists the gates.
3. Stage 1a begins; each stage ends with a hand-test list in TESTING.md and a
   summary naming the tunable constants, the reversal lever and every judgment
   call, so you can send the usual batch of adjustments.

### 4.6 Claims in this document that are not yet probed (and how to check each)

| Claim | Where | Cheap check |
|---|---|---|
| "Bitwise under upstream pitch/bank edits" for an all-unlocked s/c follower generating at the contract | decision 5, §4.3 | a ScSeamGate prototype on the default layout + your Test Coaster before Stage 1b's design is final |
| The helix's closed-form unlinked-pitch inverse | decision 6, §2.1 | a 30-line quadrature of the forward map vs the inverse (Stage 1a's gate) |
| Decision 7's per-file ease verdicts | decision 7 | the Stage 0 dry compiles |
| A constant-κ element split can be made bitwise with breaks-at-kinks + equal-κ merging | C19, R7 | a 20-line split probe on target/classes |
| The extra compile per '+' / Insert is affordable | C2 | `-Dfvd.timing=1` on your largest file |
| The stage sizes (15–17 windows) | §4.1 | the first two runs calibrate them |
| An unlinked planar written as `elem curve` loads silently wrong in an old build | decision 8 | load one in the bfc70fd build |

---

## 5. Where the raw analysis lives

Outside the repo (Temp gets purged after ~14 days; the memory folder is
durable): `~/.claude/projects/C--Users-jacks-OneDrive-Documents-AI-Fun-Ultimate-Vector-Design/memory/sc-overhaul/`
— `spec.md` (your spec verbatim), `maps/M1-M7*.md` (the existing subsystems
with file:line anchors), `digest/A-H*.md` (one report per topic: summary,
ambiguities, contradictions, your questions answered, recommendations, edge
cases, iteration risks, implementation notes), `cross/cross.md` (the cross-
check: 25 cross-topic contradictions, 26 ambiguities, 53 duplicate groups, the
dependency order, the decisions), `verify/*.md` (18 adversarial verifier
reports incl. the two probes `verify/probe*/` that measured the banked-turn
dive and the blend offset against target/classes), `plans/plan-*.md` (the
three alternative plans), `critic.md` (the completeness check of this
document's draft), `structured-maps-digests.json` + `verification.json` (the
structured results), `workflow-script.js` (the run).
