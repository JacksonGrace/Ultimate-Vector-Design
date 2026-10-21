package uvd;

import java.io.*;
import java.util.*;

/** D237 gate — the PHANTOM UNRESOLVABLE ≠v (the user's "Conflict not
 *  getting resolved 8.25.26.fvd", embedded below verbatim): a strict
 *  follower's FIRST section carries a small stale design speed
 *  (0.155 m/s at the seam — under DRIFT_EPS), the constant ENERGY
 *  offset amplifies to 0.357 m/s at the next section's slow crest
 *  (over DRIFT_EPS → badge), and the reconcile's real-anchor gate —
 *  which tested the SEAM gap against the same 0.2 — refused to
 *  recognize either resolve op's anchor and snapped it back every
 *  rebuild. Checks:
 *  a) the scenario reproduces (B badges, A does not);
 *  b) "Set Design to Real" (refreezeSpeed) on B STICKS through the
 *     rebuild loop — bitwise (the ops anchor at the reconcile's own
 *     seam sample now);
 *  c) "Convert Real to Design" (makeGraphsReal) on B sticks too;
 *  d) refreezing the SOURCE section (A) clears the whole chain.
 *  EXTENDED v19.19 (D248 — the INVISIBLE lateral-G true stroke under an
 *  ORIENTATION conflict): GraphEditor's true-stroke split reference
 *  keyed off the pre-v19.0 true-tier test (pitchDeg||rollDeg ≠ 0), so a
 *  DESIGN-tier conflict (D222) or a follower's HELD-SEAM tilt (D247) —
 *  both rigid tilts in the compile — compared the true felt against the
 *  already-tilted compiled felt and culled the stroke wherever the
 *  speeds matched (the user's "band in which it wasn't visible"). The
 *  editor now selects the AUTHORED lane as the reference on the
 *  deviating lanes of an orientation-CONFLICTED piece
 *  (orientConflictAt × LaneSection.tiltDeviates per lane). Pure-model
 *  twin of that selector on the real compile (e/f/g):
 *  e1-e3) fixtures A (true roll 30 / design 0 — the D100 path, worked
 *     before), B (design roll 30 / true 0), C (strict follower, held
 *     seam roll −30, entry speed matched so only the tilt speaks): the
 *     NEW rule draws ≥ 98% of the active section's samples in all
 *     three; the OLD rule drew < 60% on B and C (the bug, documented);
 *  e4) D: a follower's held YAW only (pure heading re-aim) = no
 *     conflict and nothing to draw;
 *  e5) E: lockstep design == true ≠ 0 = no conflict, authored == the
 *     compiled felt;
 *  e6) A's force LOCAL roll lane takes the AUTHORED reference like the
 *     felt (the D248 brief: the whole force section keeps D100's rule)
 *     and that reference is harmless — authored == derived body roll
 *     rate up to differencing dust (rotation-invariant);
 *  e7) F: a PITCH conflict (design pitch 20 / true 0) through the Gv
 *     lane;
 *  f) the LaneSection.tiltDeviates truth table (every force lane,
 *     geo world-axis / effective-roll / absolute lanes deviate; geo
 *     local body rates don't);
 *  g) G: a geo ABSOLUTE-bank section on a design-roll-30 piece — the
 *     authored bank sits ~30° off the tilted samples' bankOfTU, and
 *     the per-lane rule picks the authored reference for lane 2 only.
 *  EXTENDED batch 37 (the initial-speed window's "Set Design to Real"):
 *  Coaster.realEntrySpeed is the op's DRY RUN — {real seam speed, the
 *  vDesign the op settles at after the exact reconcile} — and a RUN HEAD
 *  has no upstream: refreezeSpeedOnly used to read the PREVIOUS piece's
 *  last sample (another run) for a detached head at piece index > 0.
 *  h1) hasUpstream's truth table + realEntrySpeed null for the ride
 *     entry, a detached head, a bridge section and a reversed section;
 *  h2) a pinned continuation typed +3 under friction: the prediction is
 *     the previous section's DESIGN end, and op + rebuild land ON it —
 *     which IS the real seam speed bit for bit since the clean-span
 *     rule (block i);
 *  h3) a continuation right of a conflicted section / on a tilted piece
 *     is REAL-ANCHORED: op + rebuild land bitwise on the true seam speed,
 *     the orientation conflict untouched;
 *  h4) a strict follower's first section lands bitwise on the true seam
 *     speed; a second press is a bitwise no-op;
 *  h5) an unpinned section right of a +0.05 pinned one — REVERSED in
 *     batch 38 (D256): the dry run predicts the TRUE seam speed, op +
 *     rebuild land on it BITWISE and the real anchor survives save →
 *     load; h5b) the same section UNPRESSED still settles on the design
 *     chain bitwise (old files unchanged);
 *  h6) run heads: the op writes NOTHING (piece 0 and a detached head at
 *     piece 1; refreezeRange + makeGraphsReal on a detached head too);
 *  h7) a stalled seam: real 0.0, settle 0.1 (the reconcile's floor).
 *  EXTENDED v19.26 (D255 — "make the friction case bit-exact"): Pass D
 *  RECORDS the design speed across every span whose geometry is the
 *  design solve's own and which the train enters at exactly its design
 *  speed (TrackCompiler.passD's clean variant) — an unconflicted chain's
 *  real speed IS its design speed, with friction and speed controls.
 *  i1) unconflicted chains (friction on / off / a speed control): every
 *     sample's true speed == its design speed BITWISE;
 *  i2) a pinned +3 section is NOT copied (its real flow differs), the
 *     sections before it are, and the section after it — real-anchored —
 *     is again;
 *  i3) a TILTED piece, a smoothed strict seam's two neighbors and a
 *     bridge are never copied;
 *  i4) the seam step: a control starting at x = 0 of a continuation
 *     drives the real march's first step (ctlSampleRows' seam row);
 *  i5) a bridged-in first section under friction: "Set Design to Real"
 *     lands on the real seam speed BITWISE (the op's fixed point), a
 *     second press is a bitwise no-op, and its spans then copy;
 *  i6) a rebuild at rest and save → load → rebuild reproduce every true
 *     speed bit;
 *  i7) behind a SMOOTHED seam the op writes the smoothing-suspended seam
 *     speed (Coaster.firstSectionSettle == the write, bitwise), so the
 *     window's dry run and the op agree.
 *  REVIEW ROUND 2 — ONE FLOW (the march is re-seeded where a recorded
 *  stretch ends, in documents with friction or speed controls):
 *  j1) a tilted follower behind a HELD speed control: the recorded real
 *     speed does not step at the seam (it stepped 0.88 / 0.14 m/s — a
 *     conflict nothing could close), no badge, the op a no-op;
 *  j2) a smoothed seam: the upstream span records the design speed up to
 *     the blend window, the march runs the window; a window too short to
 *     blend leaves both neighbors copying;
 *  j3) 24 bridged-in pieces (friction on / off): "Set Design to Real"
 *     lands on the real seam speed BITWISE, the piece copies, a second
 *     press writes nothing — also where the raw map has no bit-level
 *     fixed point (the seam's tolerance);
 *  j4) a bridge the train cannot cross: the op REFUSES and writes
 *     nothing (writing the 0.5 floor re-blended the bridge to half a
 *     million samples and every later press flipped 0.5 / 76 m/s);
 *  j5) a clean stretch that ends while the train is STALLED never
 *     re-seeds (the design record is clamped at 0 there): the descent
 *     stays under the energy bound and on the plain march;
 *  j6) the re-seed is CAUSAL per run: a friction-free document's
 *     anchored section keeps its bits when a DETACHED piece gains the
 *     coaster's first speed control;
 *  j7) (review round 4) a bridge the train CRAWLS off (0.55 m/s at its
 *     end — it does cross): both resolve ops refuse with the crawl
 *     sentence (Coaster.resolveRefusedWhy) and write nothing; the dry
 *     run says the same off the displayed compile;
 *  j8) the BLOW-UP BELT: a bridge crossed at 2 m/s (above the crawl
 *     threshold) whose target re-solved at that speed would multiply the
 *     bridge's arc — the op takes the write back with the numbers, the
 *     document and its sample count unchanged; a usable entry (j3) never
 *     trips it.
 *  EXTENDED batch 38 (D256 — the "~v" SLIGHT-conflict glyph, user spec:
 *  an entry gap the initial design speed box's two decimals can show
 *  (over Gestures.INIT_SPEED_SHOW_EPS 0.005) but under the 0.2 conflict
 *  threshold wears " ~v"; SectionNames.speedLevel is the rule, the
 *  editor's driftLevel reads it off the entry sample):
 *  k0-k6) the user's fixture (FVD_B38, embedded) after the settle recipe:
 *     the run head and piece 0's two continuations enter at their design
 *     speed (level 0, no glyph), the strict follower's two sections enter
 *     0.045 / 0.077 m/s off (level 1, " ~v" — nothing showed before,
 *     and never ≠v), both gaps inside (0.005, 0.2], a rebuild at rest
 *     keeps every level.
 *  EXTENDED batch 38 (D256 — the EXPLICIT REAL ANCHOR, the user's "last
 *  force section can't resolve its speed conflict" file = FVD_B38): the
 *  reconcile re-chained a resolved continuation onto the previous
 *  section's DESIGN end whenever the gap sat under its 0.2 anchor-
 *  recognition gate (0.078 m/s here), so "Set Design to Real" was
 *  rewritten on the next rebuild and the window rolled it back.
 *  Section.vRealAnchored makes the anchor explicit: set by the resolve
 *  ops and the '+' real fork, honored unconditionally, the proximity
 *  gate kept as the fallback, saved as "2" in the section line's
 *  pinned slot:
 *  l1) the bug reproduces (p1s0 0.045 off — no ≠v —, p1s1 bitwise on the
 *     design end, 0.078 under the real seam speed 13.9132; the follower
 *     is ≠o); l3a the dry run predicts max(0.1, real);
 *  l2) the op flags + un-pins; the rebuild lands p1s1 BITWISE on the
 *     DISPLAYED real seam speed; piece 0 and p1s0 untouched; a second
 *     rebuild (0 exact rewrites) and a second press are bitwise no-ops;
 *     l3b the window then reads no conflict; l2f the pre-op document
 *     (undo's content) reproduces the pre-op compile;
 *  l4) save → load → rebuild bitwise through the "2" slot, re-save
 *     byte-identical; l7) the flag is LOAD-BEARING: the slot forced to
 *     0 settles back on the design end (an old reader's view);
 *  l5) p1s0 := 24.6 pinned after the resolve: p1s1 follows the new real
 *     seam speed; l6) smoothing 0 lands the same way;
 *  l8) makeGraphsReal flags its fitted section and lands bitwise;
 *  l9) the '+' Transition-to-real hold: flagged it lands on the real
 *     end speed, unflagged (the old gate) it is snapped onto the design
 *     end; l10) ONE exact pass writes a nudged flagged section back
 *     (B's refreezeSeamSpeed rule == the reconcile's);
 *  l11-l13) item e's model states: a continuation pinned AT its real
 *     speed un-pins with its bits kept, one pinned 0.003 off lands
 *     bit-exactly, a run-head click writes nothing and sets no anchor;
 *  l14) an s/c section writes / loads the slot the same way, a pinned
 *     one writes 1 and loads unflagged; copySection and splitSectionAt
 *     carry the flag by the entry-seam rule (left half keeps, right
 *     half not), mergeSections takes A's; l14f-h) the four CONVERT
 *     carries (D256 review): partialConvertSection on BOTH sides (the
 *     entry-owning half keeps, the other half not), convertDomain,
 *     bakeAxes and convertWholeSection each install a new object
 *     carrying the anchor. */
public class ConflictGate {

    static final double DT = 0.01;
    static final double EPS = 0.2;   // GraphEditor.DRIFT_EPS

    static final String FVD = String.join("\n",
        "FVDD4",
        "ncoasters 2",
        "coaster Coaster 1",
        "v0 20.0",
        "friction 0.03 1.0E-4",
        "piece 0.0 5.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 0.0 0 0 1 1 0.0 0.0 0.0 0.0",
        "porient 1 0.0 0.0",
        "section force 20.0 time 0 0 0 0 0 0",
        "lane 0",
        "n 0.0 1.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 5.0 1.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 1",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 5.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 2",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 5.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "endsection",
        "endpiece",
        "coaster Coaster 2",
        "v0 20.0",
        "friction 0.03 1.0E-4",
        "piece 0.0 10.0 0.0 -0.0 1.0 0.0 0.0 0.0 0.0 0.0 0 0 1 1 0.0 0.0 0.0 0.0",
        "porient 1 0.0 0.0",
        "section force 20.0 time 0 0 0 0 0 0",
        "lane 0",
        "n 0.0 1.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 3.550373831775701 1.0 0.3633802276324186 0.0 0.3633802276324186 0.0 1 0 0",
        "n 5.0 1.8 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 1",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 5.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 2",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 5.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "sctl 0 0 4.12570093457944 0.2876635514018684 25.0 0.5 1 1 0.5 1",
        "endsection",
        "endpiece",
        "piece 101.00558685777081 12.525635131104872 0.0 0.0 1.0 0.0 -0.0 -2.069147755613188E-17 0.0 0.0 1 1 1 1 -0.0 -2.3710686717120474E-15 0.0 0.0",
        "porient 1 0.0 0.0",
        "section force 24.108558580194405 time 0 0 0 0 0 0",
        "lane 0",
        "n 0.0 1.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 1.0 1.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 1",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 1.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 2",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 1.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "endsection",
        "section force 23.757080736105365 time 0 0 0 0 0 0",
        "lane 0",
        "n 0.0 1.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 0.39773554885220364 2.4489795918367347 0.3633802276324186 0.0 0.3633802276324186 0.0 1 0 0",
        "n 0.7444678598607126 0.42857142857142905 0.3633802276324186 0.0 0.3633802276324186 0.0 1 0 0",
        "n 1.0 1.0 0.3633802276324186 0.0 0.3333333333333333 0.0 1 0 0",
        "n 1.583550580343065 1.0 0.3633802276324186 0.0 0.3633802276324186 0.0 1 0 0",
        "n 1.9835505803430649 3.1 0.3633802276324186 0.0 0.3633802276324186 0.0 1 0 0",
        "n 4.030671555919328 3.1 0.3633802276324186 0.0 0.3633802276324186 0.0 1 0 0",
        "lane 1",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 2.1909574811102335 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 2",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 1.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "endsection",
        "endpiece",
        "");

    /** The batch-38 fixture (scratchpad b38/fixtures/conflict.uvd,
     *  FVDD3, embedded verbatim — the user's file on which the "~v"
     *  slight-conflict glyph was specified): piece 0 = three force
     *  sections (sec 1 pinned at the real seam speed, sec 2 plain), piece
     *  1 = an all-locked strict follower whose two sections enter 0.045
     *  / 0.077 m/s off their design speed — under the 0.2 conflict
     *  threshold, over the init-speed box's 0.005. */
    static final String FVD_B38 = String.join("\n",
        "FVDD3",
        "name Coaster 1",
        "v0 26.0",
        "friction 0.03 1.0E-4",
        "heart 1.0",
        "gauge 1.0",
        "tiespace 1.0",
        "railmode gradient",
        "railcolor 0.8627 0.2353 0.1961",
        "gradcolors 0.5500 0.1500 0.8500 0.2000 0.4000 1.0000 0.1500 0.8000 0.9000 0.2000 0.9000 0.3000 1.0000 0.9000 0.1000 1.0000 0.1500 0.1000",
        "gradstops -1.5000 -1.0000 -0.5000 1.0000 3.0000 5.0000",
        "latcolors 0.2000 0.9000 0.3000 1.0000 0.9000 0.1000 1.0000 0.1500 0.1000",
        "latstops 0.5000 1.0000 1.5000",
        "camera 71.8858 0.3204 43.9463 -2228.3837 -3.8392 203.1301",
        "piece 0.0 21.0 0.0 0.0 1.0 0.0 0.0 0.0 0.0 0.0 0 0 1 1 0.0 0.0 0.0 0.0",
        "porient 0 0.0 0.0 0 0",
        "section force 26.0 time 0 0 0 0 0 0 0",
        "lane 0 0",
        "n 0.0 1.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 0.5 1.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 0.7 2.5 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 0.96 2.5 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 1.16 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 3.0288485829105265 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 3.750546879389623 2.6 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 4.36 2.6 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 4.56 1.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 5.56 1.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 6.36 1.14 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 7.16 1.28 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 10.36 1.28 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 11.16 1.14 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 11.96 1.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 13.411654644630145 2.9000000000000004 0.3633802276324186 -0.0 0.3333333333333333 0.0 1 0 0",
        "n 13.806270887585464 2.9000000000000004 0.3633802276324186 -0.0 0.3633802276324186 0.0 1 0 0",
        "n 14.506270887585464 -0.6000000000000001 0.3633802276324186 -0.0 0.3633802276324186 0.0 1 0 0",
        "n 15.506270887585464 -0.6000000000000001 0.3633802276324186 -0.0 0.3633802276324186 0.0 1 0 0",
        "n 16.406270887585464 3.2 0.3633802276324186 -0.0 0.3633802276324186 0.0 1 0 0",
        "n 17.406270887585464 3.2 0.3633802276324186 -0.0 0.3633802276324186 0.0 1 0 0",
        "n 18.106270887585463 -0.5 0.3333333333333333 0.0 0.3633802276324186 0.0 1 0 0",
        "lane 1 0",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 13.46 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 2 0",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 5.56 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 6.36 50.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 7.16 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 10.36 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 11.16 -48.48345 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 11.96 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 13.46 0.0 0.3633802276324186 -0.0 0.3333333333333333 0.0 1 0 0",
        "n 14.603299589597526 0.0 0.3633802276324186 -0.0 0.3633802276324186 0.0 1 0 0",
        "n 15.454775120337459 -96.13899613899615 0.3633802276324186 -0.0 0.3633802276324186 0.0 1 0 0",
        "n 16.14432602307715 0.0 0.3633802276324186 -0.0 0.3633802276324186 0.0 1 0 0",
        "n 17.37603235350325 0.0 0.3633802276324186 -0.0 0.3633802276324186 0.0 1 0 0",
        "n 18.276032353503247 79.34362934362932 0.3638382107967268 0.0 0.3633802276324186 0.0 1 0 0",
        "n 18.765045153335727 34.749034749034735 0.32393824870332805 -22.928872826109316 0.3170737093051822 27.146064344964906 1 0 0",
        "endsection",
        "section force 14.061107698592263 time 0 0 1 0 0 1 0",
        "lane 0 0",
        "n 0.0 -0.5 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 1 0",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 2 0",
        "n 0.0 -6.351477272743489 0.31651436551981044 6.698967620233811 0.3333333333333333 0.0 1 0 0",
        "n 0.6199978353415156 0.0 0.3333333333333333 -9.489479531384575E-4 0.3333333333333333 0.008984109549465408 1 0 0",
        "endsection",
        "section force 18.483242978807404 time 0 0 0 0 0 0 0",
        "lane 0 0",
        "n 0.0 -0.5 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 0.7000000000000001 3.3000000000000003 0.34315180198911815 0.0 0.3333333333333333 0.0 1 0 0",
        "n 0.8157672808412144 3.3000000000000003 0.34648069493352773 0.0 0.3244145805079429 0.0 1 0 0",
        "lane 1 0",
        "n 0.0 0.0 0.33333333333333337 0.0 0.3333333333333333 0.0 1 0 0",
        "n 0.4200128630935964 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 2 0",
        "n 0.0 -0.13511181996870292 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 0.40289831353980077 -0.13511181996870292 0.3633802276324186 0.0 0.3633802276324186 0.0 1 0 0",
        "endsection",
        "endpiece",
        "piece 316.80565768465294 5.148169468349467 148.01659451486682 0.0 0.5646084982768063 -0.27106423706524124 0.7527575170187328 -0.20272529104588977 0.0 0.0 1 0 1 1 106.23262704826303 -39.569380533329635 -0.06573320732180414 3.0146134096276 106.232627048263 0.9409710931022821 -5.397697287362151E-4 1.9416615185691047E-4 -0.3384864441806229",
        "porient 0 -2.1316282072803006E-14 1.4932499681208355E-14 0 0",
        "section force 24.949711743136202 time 0 0 0 0 0 0 0",
        "lane 0 0",
        "n 0.0 3.3000000000000003 0.34648069493352773 0.0 0.3244145805079429 0.0 1 0 0",
        "n 1.0839727840682005 3.3000000000000003 0.3633802276324186 -0.0 0.3664923895674628 0.0 1 0 0",
        "n 1.8839727840682003 -0.6000000000000001 0.3633802276324186 -0.0 0.3633802276324186 0.0 1 0 0",
        "n 2.7766305150630464 -0.6000000000000001 0.3633802276324186 -0.0 0.3633802276324186 0.0 1 0 0",
        "n 3.624798847013853 3.3000000000000003 0.3633802276324186 -0.0 0.3633802276324186 0.0 1 0 0",
        "n 3.9787501489293673 3.3000000000000003 0.3633802276324186 0.0 0.3633802276324186 0.0 1 0 0",
        "n 4.878750148929368 -0.6000000000000001 0.3633802276324186 -0.0 0.3633802276324186 0.0 1 0 0",
        "n 5.278750148929368 -0.6000000000000001 0.3633802276324186 -0.0 0.3633802276324186 0.0 1 0 0",
        "n 5.978750148929368 3.1 0.3633802276324186 -0.0 0.3633802276324186 0.0 1 0 0",
        "n 6.611014147615303 3.1 0.3633802276324186 -0.0 0.3633802276324186 0.0 1 0 0",
        "n 7.911014147615304 -0.6000000000000001 0.3633802276324186 0.0 0.3633802276324186 0.0 1 0 0",
        "lane 1 1",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 8.93234674880415 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 2 0",
        "n 0.0 -0.13511181996870292 0.3633802276324186 0.0 0.3633802276324186 0.0 1 0 0",
        "n 0.6660389601043457 -0.13511181996870292 0.3633802276324186 0.0 0.3633802276324186 0.0 1 0 0",
        "n 2.80998527472413 -26.69902912621359 0.3633802276324186 0.0 0.3633802276324186 0.0 1 0 0",
        "n 4.564448229290646 28.640776699029146 0.3651200217359228 0.0 0.3333333333333333 0.0 1 0 0",
        "n 6.258607634462365 0.5876533992450508 0.317037339506898 -9.04564555482346 0.3204877796175039 13.12995947062142 1 0 0",
        "n 7.438470442358773 -8.252427184466114 0.3571969710498325 0.0 0.36118906134271667 0.0 1 0 0",
        "n 7.838851513958503 -5.906383003676362 0.3253236546274808 2.4396063703149644 0.3167828352296289 -1.3243768516354173 1 0 0",
        "n 8.557022265758851 0.0 0.3633802276324186 -0.0 0.3668274007319999 0.0 1 0 0",
        "n 8.93234674880415 0.0 0.3633802276324186 0.0 0.3633802276324186 0.0 1 0 0",
        "endsection",
        "section force 13.991003664685625 time 0 0 0 0 0 0 0",
        "lane 0 1",
        "n 0.0 -0.6000000000000001 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 1.0 -0.6000000000000001 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 1 1",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 1.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "lane 2 1",
        "n 0.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "n 1.0 0.0 0.3333333333333333 0.0 0.3333333333333333 0.0 1 0 0",
        "endsection",
        "endpiece",
        "");

    static int pass = 0, fail = 0;

    static void check(boolean ok, String name, String detail) {
        String tag = ok ? "PASS  " : "FAIL  ";
        if (ok) pass++; else fail++;
        System.out.println(tag + name + (detail.isEmpty()
            ? "" : "  (" + detail + ")"));
    }

    static Coaster load() throws Exception {
        return DesignIO.load(new BufferedReader(new StringReader(FVD)))
            .coasters().get(1);   // Coaster 2
    }

    static Coaster loadB38() throws Exception {
        return DesignIO.load(new BufferedReader(new StringReader(FVD_B38)))
            .coasters().get(0);
    }

    /** GraphEditor.driftLevel replicated on a compile result: the
     *  THREE-STATE speed-conflict level of the span's ENTRY sample —
     *  |max(0.1, trueV) − design v| at i0 (entrySpeeds' floor) through
     *  SectionNames.speedLevel; a bridge span → 0 (the editor hands the
     *  helper NaN there). */
    static int entryLevel(Coaster c, TrackCompiler.Result r, int p, int k) {
        if (c.pieces.get(p).sections.get(k) instanceof BridgeSection) return 0;
        int spanIdx = k;
        for (int q = 0; q < p; q++) spanIdx += c.pieces.get(q).sections.size();
        TrackCompiler.Span sp = r.spans().get(spanIdx);
        if (sp.i0() >= r.trueV().length) return 0;
        double gap = Math.abs(Math.max(0.1, r.trueV()[sp.i0()])
            - r.samples().get(sp.i0()).v());
        return SectionNames.speedLevel(gap);
    }

    static double entryGap(Coaster c, TrackCompiler.Result r, int p, int k) {
        int spanIdx = k;
        for (int q = 0; q < p; q++) spanIdx += c.pieces.get(q).sections.size();
        TrackCompiler.Span sp = r.spans().get(spanIdx);
        return Math.abs(Math.max(0.1, r.trueV()[sp.i0()])
            - r.samples().get(sp.i0()).v());
    }

    static TrackCompiler.Result rebuild(Coaster c) {
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        for (int p = 0; p < 12; p++) {
            boolean again = c.reconcileChainedSpeeds(r);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            r = TrackCompiler.compile(c, DT);
        }
        for (int p = 0; p < 40; p++) {
            boolean again = c.reconcileChainedSpeeds(r, true);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            r = TrackCompiler.compile(c, DT);
        }
        return r;
    }

    /** GraphEditor.sectionDrift replicated: whole-span max split. */
    static double drift(TrackCompiler.Result r, int spanIdx) {
        TrackCompiler.Span sp = r.spans().get(spanIdx);
        int i1 = Math.min(sp.i1(),
            Math.min(r.trueV().length, r.samples().size()) - 1);
        double worst = 0;
        for (int i = sp.i0(); i <= i1; i++) {
            worst = Math.max(worst,
                Math.abs(r.trueV()[i] - r.samples().get(i).v()));
        }
        return worst;
    }

    // ---------- D248: the TRUE-STROKE split reference (pure model) ----------

    /** ≈ GraphEditor.TRUE_SPLIT_PX (1.5 px) on a −1..1 g lane over
     *  ~300 px: the stroke draws where |true − reference| exceeds it. */
    static final double STROKE_G = 0.01;
    /** The same band on a ±90° absolute-bank lane. */
    static final double STROKE_DEG = 1.0;

    static List<BezierBaker.Node> lane(double[][] pts) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] q : pts) l.add(new BezierBaker.Node(q[0], q[1]));
        return l;
    }
    static List<List<BezierBaker.Node>> hillLanes() {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {1.2, 1}, {2.4, 3.2}, {4, 0.4}, {5.5, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {2, 0.4}, {5.5, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {2, 40}, {5.5, 0} }));
        return ls;
    }
    /** A straight LEVEL run (Gv 1, Gl 0, roll 0) — an exit with zero
     *  elevation and bank, so a held seam roll reads as the plain
     *  bank band sin 30° = 0.5 g (the hill's lateral through its
     *  pitch change drifts the bank ~95° with local roll 0). */
    static List<List<BezierBaker.Node>> levelLanes() {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {5.5, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {5.5, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {5.5, 0} }));
        return ls;
    }
    static List<List<BezierBaker.Node>> turnLanes() {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {1.5, 2.2}, {3, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {1.5, -0.5}, {3, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {3, 0} }));
        return ls;
    }
    /** Geo lanes: pitch 0, yaw 0, ABSOLUTE bank held at `bank`°. */
    static List<List<BezierBaker.Node>> bankLanes(double bank) {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 0}, {3, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {3, 0} }));
        ls.add(lane(new double[][]{ {0, bank}, {3, bank} }));
        return ls;
    }

    /** The PRE-D248 selector (GraphEditor.pieceTilted): true-tier
     *  deltas only — the bug. */
    static boolean oldTilted(TrackPiece tp) {
        return tp.pitchDeg != 0 || tp.rollDeg != 0;
    }

    /** GraphEditor.orientConflictAt's model twin (D247): a follower's
     *  seam tilt with a horizontal component, else the two tiers. */
    static boolean conflictAt(Coaster c, TrackCompiler.Result r, int p) {
        TrackPiece tp = c.pieces.get(p);
        if (tp.followPrev) {
            int prevLast = spanIdx(c, p) - 1;
            return Coaster.isSeamOrientConflict(
                c.seamConflictTilt(p, r.spans().get(prevLast).exit()));
        }
        return tp.orientConflict();
    }

    /** Flattened span index of piece p's section 0. */
    static int spanIdx(Coaster c, int p) {
        int k = 0;
        for (int q = 0; q < p; q++) k += c.pieces.get(q).sections.size();
        return k;
    }

    /** Fraction of the span's samples where the true FORCE felt
     *  splits off the chosen reference on lane L (0 = Gv, 1 = Gl):
     *  reference = the AUTHORED lane when `authored`, else the
     *  compiled (tilt-re-projected) felt — GraphEditor.splitRef's
     *  model twin for a force section. */
    static double drawnFelt(TrackCompiler.Result r, TrackCompiler.Span sp,
            LaneSection ls, int L, boolean authored) {
        int n = 0, drawn = 0;
        for (int i = sp.i0(); i <= sp.i1(); i++) {
            FVDIntegrator.Sample sm = r.samples().get(i);
            double auth = BezierBaker.evalAt(ls.lanes.get(L), sm.t() - sp.t0());
            double der = L == 0 ? sm.Gv() : sm.Gl();
            double tru = r.trueFelt()[i][L];
            if (Math.abs(tru - (authored ? auth : der)) > STROKE_G) drawn++;
            n++;
        }
        return n == 0 ? 0 : drawn / (double) n;
    }

    /** drawnFelt over the span's FIRST `n` samples only (the entry
     *  window, where a free piece's speeds still match — the band the
     *  user reported). */
    static double drawnFeltHead(TrackCompiler.Result r, TrackCompiler.Span sp,
            LaneSection ls, int L, boolean authored, int n) {
        int drawn = 0, cnt = 0;
        for (int i = sp.i0(); i <= Math.min(sp.i1(), sp.i0() + n - 1); i++) {
            FVDIntegrator.Sample sm = r.samples().get(i);
            double auth = BezierBaker.evalAt(ls.lanes.get(L), sm.t() - sp.t0());
            double der = L == 0 ? sm.Gv() : sm.Gl();
            double tru = r.trueFelt()[i][L];
            if (Math.abs(tru - (authored ? auth : der)) > STROKE_G) drawn++;
            cnt++;
        }
        return cnt == 0 ? 0 : drawn / (double) cnt;
    }

    /** max |authored − compiled felt| over the span on lane L. */
    static double maxAuthVsDer(TrackCompiler.Result r, TrackCompiler.Span sp,
            LaneSection ls, int L) {
        double m = 0;
        for (int i = sp.i0(); i <= sp.i1(); i++) {
            FVDIntegrator.Sample sm = r.samples().get(i);
            double auth = BezierBaker.evalAt(ls.lanes.get(L), sm.t() - sp.t0());
            double der = L == 0 ? sm.Gv() : sm.Gl();
            m = Math.max(m, Math.abs(auth - der));
        }
        return m;
    }

    /** max |true − compiled| felt over the span on lane L (the
     *  derived-reference split — zero wherever the speeds match). */
    static double maxTrueVsDer(TrackCompiler.Result r, TrackCompiler.Span sp, int L) {
        double m = 0;
        for (int i = sp.i0(); i <= sp.i1(); i++) {
            FVDIntegrator.Sample sm = r.samples().get(i);
            double der = L == 0 ? sm.Gv() : sm.Gl();
            m = Math.max(m, Math.abs(r.trueFelt()[i][L] - der));
        }
        return m;
    }

    /** Free-piece fixture: turn + hill force sections at (0, 21, 0). */
    static Coaster freePiece() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        p.sections.add(new ForceSection(turnLanes(), 24));
        p.sections.add(new ForceSection(hillLanes(), 24));
        c.pieces.add(p);
        return c;
    }

    /** Follower fixture: hill piece + a strict follower (turn + hill)
     *  whose held seam angles seed from the follow contract, then
     *  roll −30 / yaw +30 per the flags; the follower's entry design
     *  speed := its true entry speed so only the tilt speaks. */
    static Coaster follower(double dRoll, double dYaw, boolean lockPitchRoll) {
        Coaster c = new Coaster();
        TrackPiece pa = new TrackPiece(0, 21, 0, 0);
        pa.sections.add(new ForceSection(levelLanes(), 26));
        c.pieces.add(pa);
        TrackPiece pb = new TrackPiece(0, 0, 0, 0);
        pb.sections.add(new ForceSection(turnLanes(), 24));
        pb.sections.add(new ForceSection(hillLanes(), 24));
        pb.followPrev = true;
        pb.sLockYaw = dYaw != 0;
        pb.sLockPitch = lockPitchRoll;
        pb.sLockRoll = lockPitchRoll;
        c.pieces.add(pb);
        TrackCompiler.Result r0 = TrackCompiler.compile(c, DT);
        // the follower's DESIGN pose = the boundary pose (the D71
        // makeSeamStrict recipe) so the seam tilt is exactly the held
        // gap — an identity baseOrient would read the upstream's exit
        // elevation as a conflict of its own
        pb.baseOrient = r0.spans().get(0).exit().orient();
        // held angles seed from the INCOMING frame's own triple (the
        // D247 creation rule — reading strictEntry with the locks
        // already on would return the default 0/0 held values)
        double[] seed = Coaster.eulerOf(r0.spans().get(0).exit().orient());
        pb.sYawDeg = (Double.isNaN(seed[0]) ? 0 : seed[0]) + dYaw;
        pb.sPitchDeg = seed[1];
        pb.sRollDeg = (Double.isNaN(seed[2]) ? 0 : seed[2]) + dRoll;
        // the follower's design entry speed := the TRUE speed AT the
        // seam sample (span 0's last sample IS the seam)
        pb.sections.get(0).vDesign(r0.trueV()[r0.spans().get(0).i1()]);
        return c;
    }

    static void trueRefBlock() {
        // ---- e1: A — TRUE-tier roll 30 (the D100 path) ----
        {
            Coaster c = freePiece();
            TrackPiece p = c.pieces.get(0);
            p.orientMode = TrackPiece.OrientMode.REAL_ONLY;
            p.editRoll(30);
            TrackCompiler.Result r = TrackCompiler.compile(c, DT);
            TrackCompiler.Span sp = r.spans().get(0);
            LaneSection ls = (LaneSection) p.sections.get(0);
            boolean conf = conflictAt(c, r, 0);
            double dNew = drawnFelt(r, sp, ls, 1, conf && LaneSection.tiltDeviates(ls, 1));
            double dOld = drawnFelt(r, sp, ls, 1, oldTilted(p));
            check(conf && dNew >= 0.98 && dOld >= 0.98,
                "e1 A true roll 30 / design 0: Gl stroke draws the whole section (both rules)",
                String.format("conflict %b new %.3f old %.3f band %.3f g",
                    conf, dNew, dOld, maxAuthVsDer(r, sp, ls, 1)));
            // e6: the force LOCAL roll lane takes the AUTHORED
            // reference with the rest of the force section (the D248
            // brief keeps D100's whole-section rule) — harmless,
            // because a rigid tilt leaves the BODY roll rate exactly
            // the authored lane up to the differencing dust, so the
            // authored and derived references draw the same stroke
            // (true vs either still splits by the SPEED term — a tilt
            // changes heights — the honest speed split, not a band)
            double mRoll = 0;
            for (int i = sp.i0(); i <= sp.i1(); i++) {
                double auth = BezierBaker.evalAt(ls.lanes.get(2),
                    r.samples().get(i).t() - sp.t0());
                mRoll = Math.max(mRoll, Math.abs(auth - r.rates()[i][3]));
            }
            check(LaneSection.tiltDeviates(ls, 2) && mRoll < 0.5,
                "e6 A force local roll lane: authored reference (D248 brief), harmless — authored == derived body roll rate",
                String.format("deviates %b max|authored-derivedRoll| %.2e deg/s",
                    LaneSection.tiltDeviates(ls, 2), mRoll));
        }
        // ---- e2: B — DESIGN-tier roll 30 / true 0 ----
        {
            Coaster c = freePiece();
            TrackPiece p = c.pieces.get(0);
            p.designRollDeg = 30;
            p.rollDeg = 0;
            TrackCompiler.Result r = TrackCompiler.compile(c, DT);
            TrackCompiler.Span sp = r.spans().get(0);
            LaneSection ls = (LaneSection) p.sections.get(0);
            boolean conf = conflictAt(c, r, 0);
            double dNew = drawnFelt(r, sp, ls, 1, conf && LaneSection.tiltDeviates(ls, 1));
            double dOld = drawnFelt(r, sp, ls, 1, oldTilted(p));
            check(conf && dNew >= 0.98 && dOld < 0.60,
                "e2 B design roll 30 / true 0: the NEW rule draws the whole section, the OLD rule culled it",
                String.format("conflict %b new %.3f old %.3f band %.3f g",
                    conf, dNew, dOld, maxAuthVsDer(r, sp, ls, 1)));
        }
        // ---- e3: C — strict follower, held seam roll −30 ----
        {
            Coaster c = follower(-30, 0, true);
            TrackPiece p = c.pieces.get(1);
            TrackCompiler.Result r = TrackCompiler.compile(c, DT);
            TrackCompiler.Span sp = r.spans().get(spanIdx(c, 1));
            LaneSection ls = (LaneSection) p.sections.get(0);
            boolean conf = conflictAt(c, r, 1);
            double dNew = drawnFelt(r, sp, ls, 1, conf && LaneSection.tiltDeviates(ls, 1));
            double dOld = drawnFelt(r, sp, ls, 1, oldTilted(p));
            check(conf && dNew >= 0.98 && dOld < 0.60,
                "e3 C follower held roll -30: NEW rule draws the whole first section, OLD rule culled it",
                String.format("conflict %b new %.3f old %.3f band %.3f g speedSplit %.2e g",
                    conf, dNew, dOld, maxAuthVsDer(r, sp, ls, 1), maxTrueVsDer(r, sp, 1)));
        }
        // ---- e4: D — follower, held YAW only (pure heading re-aim) ----
        {
            Coaster c = follower(0, 30, false);
            TrackCompiler.Result r = TrackCompiler.compile(c, DT);
            TrackCompiler.Span sp = r.spans().get(spanIdx(c, 1));
            LaneSection ls = (LaneSection) c.pieces.get(1).sections.get(0);
            boolean conf = conflictAt(c, r, 1);
            double sGv = maxTrueVsDer(r, sp, 0), sGl = maxTrueVsDer(r, sp, 1);
            double dNew = drawnFelt(r, sp, ls, 1, conf && LaneSection.tiltDeviates(ls, 1));
            // (the entry speed is matched one dt inside the seam, so a
            // ~3e-3 g residual speed split remains — below the stroke
            // threshold: nothing draws)
            check(!conf && sGv < STROKE_G && sGl < STROKE_G && dNew == 0,
                "e4 D follower heading-only re-aim: no conflict, derived reference, nothing draws",
                String.format("conflict %b max|true-derived| Gv %.2e Gl %.2e drawn %.3f",
                    conf, sGv, sGl, dNew));
        }
        // ---- e5: E — lockstep design == true == 30 ----
        {
            Coaster c = freePiece();
            TrackPiece p = c.pieces.get(0);
            p.orientMode = TrackPiece.OrientMode.LOCKSTEP;
            p.editRoll(30);
            TrackCompiler.Result r = TrackCompiler.compile(c, DT);
            TrackCompiler.Span sp = r.spans().get(0);
            LaneSection ls = (LaneSection) p.sections.get(0);
            boolean conf = conflictAt(c, r, 0);
            double bGv = maxAuthVsDer(r, sp, ls, 0), bGl = maxAuthVsDer(r, sp, ls, 1);
            // baseline: the same recording dust on an UNtilted piece
            Coaster c0 = freePiece();
            TrackCompiler.Result r0 = TrackCompiler.compile(c0, DT);
            LaneSection ls0 = (LaneSection) c0.pieces.get(0).sections.get(0);
            double b0 = Math.max(maxAuthVsDer(r0, r0.spans().get(0), ls0, 0),
                                 maxAuthVsDer(r0, r0.spans().get(0), ls0, 1));
            check(!conf && p.designRollDeg == 30 && p.rollDeg == 30
                    && bGv < STROKE_G && bGl < STROKE_G && oldTilted(p),
                "e5 E lockstep design == true: no conflict, no tilt band (authored == compiled felt below the stroke threshold; the old rule fired needlessly)",
                String.format("conflict %b max|auth-der| Gv %.2e Gl %.2e (untilted baseline %.2e) g",
                    conf, bGv, bGl, b0));
        }
        // ---- e7: F — PITCH conflict (design pitch 20 / true 0) ----
        {
            Coaster c = freePiece();
            TrackPiece p = c.pieces.get(0);
            p.designPitchDeg = 20;
            p.pitchDeg = 0;
            TrackCompiler.Result r = TrackCompiler.compile(c, DT);
            TrackCompiler.Span sp = r.spans().get(0);
            LaneSection ls = (LaneSection) p.sections.get(0);
            boolean conf = conflictAt(c, r, 0);
            boolean authored = conf && LaneSection.tiltDeviates(ls, 0);
            double dNew = drawnFelt(r, sp, ls, 0, authored);
            // the pitch tilt drops the whole piece's heights, so the
            // speed term alone draws most of the section under EITHER
            // rule — the bug is the ENTRY window, where the speeds still
            // match and the old derived reference culled the stroke
            double hNew = drawnFeltHead(r, sp, ls, 0, authored, 20);
            double hOld = drawnFeltHead(r, sp, ls, 0, oldTilted(p), 20);
            check(conf && dNew >= 0.90 && hNew >= 0.90 && hOld < 0.50,
                "e7 F design pitch 20 / true 0: the Gv stroke draws from the ENTRY under the NEW rule, the OLD rule culled the entry window",
                String.format("conflict %b whole new %.3f entry-20 new %.3f old %.3f band %.3f g",
                    conf, dNew, hNew, hOld, maxAuthVsDer(r, sp, ls, 0)));
        }
        // ---- f: the tiltDeviates truth table ----
        {
            ForceSection fl = new ForceSection(turnLanes(), 24);
            ForceSection fg = new ForceSection(turnLanes(), 24);
            fg.rollGlobal = true;
            GeoSection gl = new GeoSection(turnLanes(), 24);
            GeoSection gr = new GeoSection(turnLanes(), 24);
            gr.rollGlobal = true;
            GeoSection gy = new GeoSection(turnLanes(), 24);
            gy.yawPitchGlobal = true;
            GeoSection gb = new GeoSection(bankLanes(20), 24);
            gb.rollGlobal = true;
            gb.rollAbsolute = true;
            GeoSection ga = new GeoSection(turnLanes(), 24);
            ga.yawPitchGlobal = true;
            ga.ypAbsolute = true;
            boolean ok = true;
            ok &= tt(fl, true, true, true);     // force local: every lane (D100 rule kept, brief)
            ok &= tt(fg, true, true, true);     // force + global roll (bank rate)
            ok &= tt(gl, false, false, false);  // geo local rates: invariant
            ok &= tt(gr, false, false, true);   // geo + global roll
            ok &= tt(gy, true, true, true);     // geo yaw/pitch global: heading/elevation + effective roll (D177)
            ok &= tt(gb, false, false, true);   // geo absolute bank
            ok &= tt(ga, true, true, true);     // geo absolute elevation/heading
            ok &= LaneSection.tiltDeviatesAny(fl) && !LaneSection.tiltDeviatesAny(gl)
                && LaneSection.tiltDeviatesAny(gr);
            check(ok, "f1 LaneSection.tiltDeviates truth table (7 sections × 3 lanes + tiltDeviatesAny)", "");
        }
        // ---- g: G — geo ABSOLUTE bank on a design-roll-30 piece ----
        {
            Coaster c = new Coaster();
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            GeoSection gb = new GeoSection(bankLanes(20), 24);
            gb.rollGlobal = true;
            gb.rollAbsolute = true;
            p.sections.add(gb);
            c.pieces.add(p);
            p.designRollDeg = 30;
            p.rollDeg = 0;
            TrackCompiler.Result r = TrackCompiler.compile(c, DT);
            TrackCompiler.Span sp = r.spans().get(0);
            boolean conf = conflictAt(c, r, 0);
            int n = 0, drawn = 0;
            double maxBand = 0;
            for (int i = sp.i0(); i <= sp.i1(); i++) {
                FVDIntegrator.Sample sm = r.samples().get(i);
                double b = FVDIntegrator.bankOfTU(sm.T(), sm.U());
                double der = Double.isNaN(b) ? 0 : Math.toDegrees(b);
                double auth = BezierBaker.evalAt(gb.lanes.get(2), sm.t() - sp.t0());
                maxBand = Math.max(maxBand, Math.abs(auth - der));
                if (Math.abs(auth - der) > STROKE_DEG) drawn++;
                n++;
            }
            boolean lanes = conf && LaneSection.tiltDeviates(gb, 2)
                && !LaneSection.tiltDeviates(gb, 0) && !LaneSection.tiltDeviates(gb, 1);
            check(lanes && drawn / (double) n >= 0.90 && maxBand > 25 && maxBand < 35,
                "g1 G geo absolute bank 20 on a design-roll-30 piece: authored bank ~30 deg off the tilted samples, lane 2 only",
                String.format("conflict %b drawn %.3f maxBand %.2f deg", conf, drawn / (double) n, maxBand));
        }
    }

    /** tiltDeviates truth-table row. */
    static boolean tt(LaneSection ls, boolean l0, boolean l1, boolean l2) {
        boolean ok = LaneSection.tiltDeviates(ls, 0) == l0
            && LaneSection.tiltDeviates(ls, 1) == l1
            && LaneSection.tiltDeviates(ls, 2) == l2;
        if (!ok) System.out.println("   truth-table miss on " + ls.getClass().getSimpleName()
            + " rollGlobal " + ls.rollGlobal + " ypGlobal " + ls.yawPitchGlobal);
        return ok;
    }

    // ---------- batch 37: the init-speed window's dry run ----------

    /** Gestures.INIT_SPEED_SHOW_EPS's twin (half the box's %.2f
     *  quantum) — inlined so this gate reads the MODEL alone. */
    static final double SHOW_EPS = 0.005;

    static boolean speedDiffers(double vDesign, double vTarget) {
        return !Double.isNaN(vDesign) && !Double.isNaN(vTarget)
            && Math.abs(vDesign - vTarget) > SHOW_EPS;
    }

    static boolean bits(double a, double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    static List<List<BezierBaker.Node>> flatLanes(double len) {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(lane(new double[][]{ {0, 1}, {len, 1} }));
        ls.add(lane(new double[][]{ {0, 0}, {len, 0} }));
        ls.add(lane(new double[][]{ {0, 0}, {len, 0} }));
        return ls;
    }

    /** One piece, three chained force sections (turn, hill, 2 s flat). */
    static Coaster chain(boolean fric) {
        Coaster c = new Coaster();
        if (fric) { c.fricRoll = 0.03; c.fricAir = 1.0e-4; }
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        p.sections.add(new ForceSection(turnLanes(), 24));
        p.sections.add(new ForceSection(hillLanes(), 24));
        p.sections.add(new ForceSection(flatLanes(2), 24));
        c.pieces.add(p);
        c.v0 = 24;
        return c;
    }

    /** A turn piece + a detached hill piece at index 1 (vDesign 17). */
    static Coaster detached() {
        Coaster c = new Coaster();
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        p.sections.add(new ForceSection(turnLanes(), 24));
        c.pieces.add(p);
        TrackPiece q = new TrackPiece(50, 30, 20, 40);
        q.sections.add(new ForceSection(hillLanes(), 17));
        c.pieces.add(q);
        return c;
    }

    static double[] dry(Coaster c, TrackCompiler.Result r, int p, int k) {
        return c.realEntrySpeed(p, k, r.samples(), r.trueV(), r.spans());
    }

    /** The seam sample entering piece p's section k. */
    static int seam(Coaster c, TrackCompiler.Result r, int p, int k) {
        return r.spans().get(spanIdx(c, p) + k - 1).i1();
    }

    /** Samples of span k whose true speed is NOT the design speed's
     *  bits. */
    static int unequal(TrackCompiler.Result r, int k) {
        TrackCompiler.Span sp = r.spans().get(k);
        int n = 0;
        for (int i = sp.i0(); i <= sp.i1(); i++) {
            if (!bits(r.trueV()[i], r.samples().get(i).v())) n++;
        }
        return n;
    }

    static int unequalAll(TrackCompiler.Result r) {
        int n = 0;
        for (int k = 0; k < r.spans().size(); k++) n += unequal(r, k);
        return n;
    }

    static boolean sameTrue(TrackCompiler.Result a, TrackCompiler.Result b) {
        if (a.trueV().length != b.trueV().length) return false;
        for (int i = 0; i < a.trueV().length; i++) {
            if (!bits(a.trueV()[i], b.trueV()[i])) return false;
            if (!bits(a.samples().get(i).v(), b.samples().get(i).v())) {
                return false;
            }
        }
        return true;
    }

    static Coaster roundTrip(Coaster c) throws Exception {
        java.io.StringWriter sw = new java.io.StringWriter();
        DesignIO.save(c, DesignIO.Cosmetics.defaults(),
            new java.io.PrintWriter(sw));
        return DesignIO.load(new BufferedReader(
            new StringReader(sw.toString()))).coasters().get(0);
    }

    /** turn piece → bridge (No optimization) → hill piece at vB. */
    static Coaster bridged(boolean fric, double vB) {
        Coaster b = new Coaster();
        if (fric) { b.fricRoll = 0.03; b.fricAir = 1.0e-4; }
        TrackPiece o = new TrackPiece(0, 21, 0, 0);
        o.sections.add(new ForceSection(turnLanes(), 24));
        b.pieces.add(o);
        TrackCompiler.Result r0 = TrackCompiler.compile(b, DT);
        TrackPose exit = r0.spans().get(0).exit();
        Vec3 tp = exit.pos().add(new Vec3(30, -4, 6));
        TrackPiece t = new TrackPiece(tp.x(), tp.y(), tp.z(), 0);
        t.baseOrient = exit.orient();
        t.sections.add(new ForceSection(hillLanes(), vB));
        t.sections.add(new ForceSection(flatLanes(2), vB));
        BridgeSection bs = BridgeSection.solve(exit,
            new TrackPose(tp, exit.orient()),
            r0.samples().get(r0.spans().get(0).i1()).v());
        bs.optMode = BridgeSection.OPT_NONE;
        o.sections.add(bs);
        b.pieces.add(t);
        return b;
    }

    static List<BezierBaker.Node> laneH(double[][] pts) {
        List<BezierBaker.Node> l = new ArrayList<>();
        for (double[] q : pts) l.add(new BezierBaker.Node(q[0], q[1]));
        BezierBaker.applyDefaultHandles(l);
        return l;
    }

    static List<List<BezierBaker.Node>> lanesH(double[][] a, double[][] b,
                                               double[][] c) {
        List<List<BezierBaker.Node>> ls = new ArrayList<>();
        ls.add(laneH(a)); ls.add(laneH(b)); ls.add(laneH(c));
        return ls;
    }

    /** Round 2's fixture: turn, hill with a max-only 0.3 g brake
     *  holding 25 m/s down its drop (weaker than the slope — the
     *  hold's knife edge), turn, flat. */
    static Coaster heldControl(boolean fric) {
        Coaster c = new Coaster();
        if (fric) { c.fricRoll = 0.03; c.fricAir = 1.0e-4; }
        c.v0 = 24;
        TrackPiece p = new TrackPiece(0, 21, 0, 0);
        double[][] t1 = { {0, 1}, {1.5, 2.2}, {3, 1} };
        double[][] t2 = { {0, 0}, {1.5, -0.5}, {3, 0} };
        double[][] z3 = { {0, 0}, {3, 0} };
        p.sections.add(new ForceSection(lanesH(t1, t2, z3), 24));
        ForceSection h = new ForceSection(lanesH(
            new double[][]{ {0, 1}, {1.2, 1}, {2.4, 3.2}, {4, 0.4},
                {5.5, 1} },
            new double[][]{ {0, 0}, {2, 0.4}, {5.5, 0} },
            new double[][]{ {0, 0}, {2, 40}, {5.5, 0} }), 24);
        SpeedControl sc = new SpeedControl(3.5, 2.0);
        sc.type = SpeedControl.ACCEL;
        sc.speed = 25;
        sc.accel = 0.3;
        sc.decel = 0.3;
        sc.minLimit = false;
        sc.maxLimit = true;
        h.speedCtls.add(sc);
        p.sections.add(h);
        p.sections.add(new ForceSection(lanesH(t1, t2, z3), 24));
        p.sections.add(new ForceSection(lanesH(
            new double[][]{ {0, 1}, {2, 1} }, new double[][]{ {0, 0},
                {2, 0} }, new double[][]{ {0, 0}, {2, 0} }), 24));
        c.pieces.add(p);
        return c;
    }

    /** turn piece -> bridge (No optimization) -> hill piece at vB, the
     *  target offset (dx, dy, dz) from the exit. */
    static Coaster bridgedAt(boolean fric, double vB, double dx, double dy,
                             double dz) {
        Coaster b = new Coaster();
        if (fric) { b.fricRoll = 0.03; b.fricAir = 1.0e-4; }
        TrackPiece o = new TrackPiece(0, 21, 0, 0);
        o.sections.add(new ForceSection(turnLanes(), 24));
        b.pieces.add(o);
        TrackCompiler.Result r0 = TrackCompiler.compile(b, DT);
        TrackPose exit = r0.spans().get(0).exit();
        Vec3 tp = exit.pos().add(new Vec3(dx, dy, dz));
        TrackPiece t = new TrackPiece(tp.x(), tp.y(), tp.z(), 0);
        t.baseOrient = exit.orient();
        t.sections.add(new ForceSection(hillLanes(), vB));
        BridgeSection bs = BridgeSection.solve(exit,
            new TrackPose(tp, exit.orient()),
            r0.samples().get(r0.spans().get(0).i1()).v());
        bs.optMode = BridgeSection.OPT_NONE;
        o.sections.add(bs);
        b.pieces.add(t);
        return b;
    }

    static void oneFlowBlock() {
        // ---- j1: a tilted follower behind a held control ----
        for (boolean fric : new boolean[]{ false, true }) {
            Coaster c = heldControl(fric);
            rebuild(c);
            int f = c.makeSeamStrict(0, 2, DT);
            rebuild(c);
            c.pieces.get(f).sPitchDeg += 2;
            TrackCompiler.Result r = rebuild(c);
            int sm = seam(c, r, f, 0);
            double stepT = r.trueV()[sm + 1] - r.trueV()[sm];
            double stepD = r.samples().get(sm + 1).v()
                - r.samples().get(sm).v();
            double gap = Math.abs(Math.max(0.1, r.trueV()[sm + 1])
                - r.samples().get(sm + 1).v());
            Section s0 = c.pieces.get(f).sections.get(0);
            long b0 = Double.doubleToLongBits(s0.vDesign());
            double[] re = dry(c, r, f, 0);
            boolean ok = c.refreezeSpeedOnly(f, 0, DT);
            rebuild(c);
            check(unequal(r, 0) == 0 && unequal(r, 1) == 0
                    && Math.abs(stepT - stepD) < 0.01 && gap < 0.05
                    && re != null && !speedDiffers(s0.vDesign(), re[1])
                    && ok && Double.doubleToLongBits(s0.vDesign()) == b0,
                "j1 tilted follower behind a held control (friction "
                + (fric ? "on" : "off") + "): the real speed does not"
                + " step at the seam, no conflict, the op writes nothing",
                String.format("true step %+.4f design step %+.4f gap"
                    + " %.4f", stepT, stepD, gap));
        }
        // ---- j2: a smoothed seam copies up to its window ----
        {
            Coaster c = chain(true);
            rebuild(c);
            int f = c.makeSeamStrict(0, 1, DT);
            rebuild(c);
            c.pieces.get(f).sSmoothLen = 4;
            TrackCompiler.Result r = rebuild(c);
            TrackCompiler.Span up = r.spans().get(0);
            int copied = 0, firstOff = -1;
            for (int i = up.i0(); i <= up.i1(); i++) {
                if (bits(r.trueV()[i], r.samples().get(i).v())) {
                    copied++;
                } else if (firstOff < 0) {
                    firstOff = i;
                }
            }
            boolean prefix = firstOff > up.i0() && copied == firstOff
                - up.i0();
            double arc = r.samples().get(up.i1()).s()
                - r.samples().get(Math.max(up.i0(), firstOff - 1)).s();
            int sm = up.i1();
            c.pieces.get(f).sSmoothLen = 0.02;
            TrackCompiler.Result rs = rebuild(c);
            check(prefix && arc > 1.5 && arc < 2.6
                    && Math.abs(r.trueV()[sm] - r.samples().get(sm).v())
                        < 0.05
                    && unequal(r, 1) > 0 && unequalAll(rs) == 0,
                "j2 a 4 m smoothing window: the upstream span copies up"
                + " to the window (its last ~2 m run the march), and a"
                + " 0.02 m window — too short to blend — leaves both"
                + " neighbors copying",
                String.format("copied %d of %d, window arc %.2f m",
                    copied, up.i1() - up.i0() + 1, arc));
        }
        // ---- j3: bridged-in pieces land bitwise ----
        {
            int n = 0, landed = 0, stable = 0, copies = 0;
            long seed = 12345;
            StringBuilder bad = new StringBuilder();
            for (int k = 0; k < 24; k++) {
                seed = (seed * 1103515245L + 12345L) & 0x7fffffffL;
                double dx = 22 + (seed % 1000) / 1000.0 * 24;
                seed = (seed * 1103515245L + 12345L) & 0x7fffffffL;
                double dy = -7 + (seed % 1000) / 1000.0 * 8;
                seed = (seed * 1103515245L + 12345L) & 0x7fffffffL;
                double dz = -9 + (seed % 1000) / 1000.0 * 18;
                seed = (seed * 1103515245L + 12345L) & 0x7fffffffL;
                double vB = 14 + (seed % 1000) / 1000.0 * 14;
                Coaster b = bridgedAt(k % 2 == 0, vB, dx, dy, dz);
                TrackCompiler.Result r = rebuild(b);
                if (r.trueV()[seam(b, r, 1, 0)] < 1) continue;   // stalls
                n++;
                b.refreezeSpeedOnly(1, 0, DT);
                TrackCompiler.Result r2 = rebuild(b);
                double v1 = b.pieces.get(1).sections.get(0).vDesign();
                boolean land = bits(v1, r2.trueV()[seam(b, r2, 1, 0)]);
                boolean copy = unequal(r2, 2) == 0;
                b.refreezeSpeedOnly(1, 0, DT);
                TrackCompiler.Result r3 = rebuild(b);
                boolean same = bits(v1,
                    b.pieces.get(1).sections.get(0).vDesign())
                    && sameTrue(r2, r3);
                if (land) landed++;
                if (copy) copies++;
                if (same) stable++;
                if (!(land && copy && same)) {
                    bad.append(String.format(" #%d(%s%s%s)", k,
                        land ? "" : "land ", copy ? "" : "copy ",
                        same ? "" : "stable"));
                }
            }
            check(n >= 16 && landed == n && copies == n && stable == n,
                "j3 bridged-in first sections (friction on / off): Set"
                + " Design to Real lands BITWISE on the real seam speed,"
                + " the piece copies, a second press writes nothing",
                String.format("%d bridges: landed %d, copying %d, stable"
                    + " %d%s", n, landed, copies, stable, bad));
        }
        // ---- j4: a bridge the train cannot cross ----
        {
            Coaster b = bridgedAt(true, 22, 30, 48, 6);
            TrackCompiler.Result r = rebuild(b);
            double real = r.trueV()[seam(b, r, 1, 0)];
            Section s0 = b.pieces.get(1).sections.get(0);
            s0.vPinned(true);
            boolean ok = b.refreezeSpeedOnly(1, 0, DT);
            check(!ok && real < 0.5 && bits(s0.vDesign(), 22.0)
                    && s0.vPinned(),
                "j4 a bridge the train cannot cross: the op refuses and"
                + " writes nothing (design speed and pin kept)",
                String.format("real %.4f, vDesign %.1f", real,
                    s0.vDesign()));
        }
        // ---- j5: a clean stretch ending in a stall never re-seeds ----
        {
            Coaster c = new Coaster();
            c.fricRoll = 0.03;
            c.fricAir = 1.0e-4;
            c.v0 = 9;
            TrackPiece p = new TrackPiece(0, 21, 0, 0);
            p.sections.add(new ForceSection(lanesH(
                new double[][]{ {0, 1}, {1, 1} },
                new double[][]{ {0, 0}, {1, 0} },
                new double[][]{ {0, 0}, {1, 0} }), 9));
            // per-metre pitch-up to ~45 deg, then straight up the
            // slope for 12 m of arc: past the run's energy height
            GeoSection up = new GeoSection(lanesH(
                new double[][]{ {0, 0}, {3, 15}, {6, 0}, {12, 0} },
                new double[][]{ {0, 0}, {12, 0} },
                new double[][]{ {0, 0}, {12, 0} }), 9);
            up.domain(Section.Domain.DISTANCE);
            up.ratePerMeter = true;
            p.sections.add(up);
            GeoSection dn = new GeoSection(lanesH(
                new double[][]{ {0, 0}, {3, -30}, {6, 0}, {30, 0} },
                new double[][]{ {0, 0}, {30, 0} },
                new double[][]{ {0, 0}, {30, 0} }), 9);
            dn.domain(Section.Domain.DISTANCE);
            dn.ratePerMeter = true;
            p.sections.add(dn);
            c.pieces.add(p);
            TrackCompiler.Result r = rebuild(c);
            List<FVDIntegrator.Sample> ss = r.samples();
            double hE = ss.get(0).pos().y() + 81 / (2 * FVDIntegrator.G0);
            TrackCompiler.Span s1 = r.spans().get(1);
            TrackCompiler.Span s2 = r.spans().get(2);
            double over = 0;
            for (int i = s2.i0(); i <= s2.i1(); i++) {
                double bound = Math.sqrt(Math.max(0, 2 * FVDIntegrator.G0
                    * (hE - ss.get(i).pos().y())));
                over = Math.max(over, r.trueV()[i] - bound);
            }
            List<double[]> runs = new ArrayList<>();
            runs.add(new double[]{ 0, 9 });
            double[] plain = TrackCompiler.passD(ss, runs, c.fricRoll,
                c.fricAir, null, null);
            int e = ss.size() - 1;
            check(ss.get(s1.i1()).v() == 0 && ss.get(s1.i1()).pos().y()
                        > hE + 1
                    && over < 1e-6
                    && Math.abs(r.trueV()[e] - plain[e]) < 1e-3,
                "j5 a clean stretch ending in a STALL (the design 2 m"
                + " above its energy height): no re-seed — the descent"
                + " stays under the energy bound and on the plain march",
                String.format("ride end true %.4f plain %.4f, worst over"
                    + " the bound %+.2e", r.trueV()[e], plain[e], over));
        }
        // ---- j6: the re-seed is causal, per run ----
        {
            Coaster c = chain(false);
            rebuild(c);
            Section s1 = c.pieces.get(0).sections.get(1);
            s1.vDesign(s1.vDesign() + 3.0);
            s1.vPinned(true);
            rebuild(c);
            c.refreezeSpeedOnly(0, 2, DT);   // anchored on the march
            TrackCompiler.Result r = rebuild(c);
            int n = r.samples().size();
            long vd = Double.doubleToLongBits(
                c.pieces.get(0).sections.get(2).vDesign());
            // a DETACHED piece with the coaster's first speed control
            TrackPiece q = new TrackPiece(80, 30, 40, 25);
            ForceSection f = new ForceSection(flatLanes(3), 20);
            SpeedControl sc = new SpeedControl(0.5, 2.0);
            sc.speed = 24;
            f.speedCtls.add(sc);
            q.sections.add(f);
            c.pieces.add(q);
            TrackCompiler.Result r2 = rebuild(c);
            boolean same = r2.samples().size() > n;
            for (int i = 0; same && i < n; i++) {
                FVDIntegrator.Sample a = r.samples().get(i);
                FVDIntegrator.Sample b = r2.samples().get(i);
                same = bits(a.pos().x(), b.pos().x())
                    && bits(a.pos().y(), b.pos().y())
                    && bits(a.pos().z(), b.pos().z())
                    && bits(a.v(), b.v())
                    && bits(r.trueV()[i], r2.trueV()[i]);
            }
            check(same && Double.doubleToLongBits(
                    c.pieces.get(0).sections.get(2).vDesign()) == vd,
                "j6 a friction-free run with an anchored section keeps"
                + " every bit (track, design and real speed) when a"
                + " DETACHED piece gains the coaster's first control", "");
        }
        // ---- j7: a bridge the train crawls off (review round 4) ----
        {
            Coaster b = slowBridge(0.55);
            TrackCompiler.Result r = rebuild(b);
            int sm = seam(b, r, 1, 0);
            double real = r.trueV()[sm];
            int n0 = r.samples().size();
            Section s0 = b.pieces.get(1).sections.get(0);
            s0.vPinned(true);
            String dryWhy = b.bridgeCrawlWhy(1, 0, r.samples(), r.trueV(),
                r.spans());
            boolean ok = b.refreezeSpeedOnly(1, 0, DT);
            String why = b.resolveRefusedWhy;
            boolean ok2 = b.makeGraphsReal(1, 0, DT, 0.1, 0);
            String why2 = b.resolveRefusedWhy;
            TrackCompiler.Result r2 = rebuild(b);
            check(!ok && !ok2 && real > 0.5 && real < 0.6
                    && why != null && why.startsWith("The train crawls off"
                        + " the bridge at 0.55 m/s")
                    && why.equals(dryWhy) && why.equals(why2)
                    && bits(s0.vDesign(), 18.0) && s0.vPinned()
                    && r2.samples().size() == n0,
                "j7 a bridge the train crawls off (0.55 m/s): both resolve"
                + " ops refuse with the crawl sentence, the dry run agrees,"
                + " nothing written",
                String.format("real %.4f, ok %b/%b, vDesign %.1f, n %d -> %d,"
                    + " why \"%s\"", real, ok, ok2, s0.vDesign(), n0,
                    r2.samples().size(), why));
        }
        // ---- j8: the blow-up belt (review round 4) ----
        {
            Coaster b = slowBridge(2.0);
            TrackCompiler.Result r = rebuild(b);
            int sm = seam(b, r, 1, 0);
            double real = r.trueV()[sm];
            int n0 = r.samples().size();
            double arc0 = r.spans().get(1).sLen();
            Section s0 = b.pieces.get(1).sections.get(0);
            String dryWhy = b.bridgeCrawlWhy(1, 0, r.samples(), r.trueV(),
                r.spans());
            boolean ok = b.refreezeSpeedOnly(1, 0, DT);
            String why = b.resolveRefusedWhy;
            TrackCompiler.Result r2 = rebuild(b);
            check(!ok && real > 1.9 && real < 2.1 && dryWhy == null
                    && why != null && why.startsWith("at 2.00 m/s the bridge"
                        + " re-solves to ")
                    && why.contains(String.format(java.util.Locale.US,
                        "(it was %.0f m)", arc0))
                    && bits(s0.vDesign(), 18.0) && !s0.vPinned()
                    && r2.samples().size() == n0
                    && bits(r2.spans().get(1).sLen(), arc0),
                "j8 a bridge crossed at 2 m/s whose target would blow it up:"
                + " the belt takes the write back with the numbers, the"
                + " document and sample count unchanged",
                String.format("real %.4f, ok %b, vDesign %.1f, arc %.1f ->"
                    + " %.1f, n %d -> %d, why \"%s\"", real, ok,
                    s0.vDesign(), arc0, r2.spans().get(1).sLen(), n0,
                    r2.samples().size(), why));
        }
    }

    /** bridgedAt(false, 18, 30, dy, 4) with dy bisected so the train
     *  crosses the bridge at `want` m/s (dy low = fast, high = stalled;
     *  the review round 4 fixture recipe). */
    static Coaster slowBridge(double want) {
        double lo = -6, hi = 40;
        for (int i = 0; i < 60; i++) {
            double mid = 0.5 * (lo + hi);
            Coaster c = bridgedAt(false, 18, 30, mid, 4);
            TrackCompiler.Result r = rebuild(c);
            double v = r.trueV()[r.spans().get(1).i1()];
            if (v > want) lo = mid; else hi = mid;
        }
        return bridgedAt(false, 18, 30, lo, 4);
    }

    static void cleanSpanBlock() throws Exception {
        // ---- i1: unconflicted chains copy everywhere ----
        {
            Coaster f = chain(true), n = chain(false), k = chain(true);
            SpeedControl sc = new SpeedControl(0.0, 1.5);
            sc.type = SpeedControl.ACCEL;
            sc.speed = 23;
            sc.accel = 0.6;
            sc.decel = 0.6;
            ((ForceSection) k.pieces.get(0).sections.get(1))
                .speedCtls.add(sc);
            TrackCompiler.Result rf = rebuild(f), rn = rebuild(n),
                rk = rebuild(k);
            check(unequalAll(rf) == 0 && unequalAll(rn) == 0
                    && unequalAll(rk) == 0,
                "i1 unconflicted chains (friction / none / a control"
                + " holding 23 m/s): every true speed == the design"
                + " speed BITWISE",
                String.format("unequal %d / %d / %d of %d",
                    unequalAll(rf), unequalAll(rn), unequalAll(rk),
                    rf.samples().size()));
        }
        // ---- i2: a conflicted section is not copied ----
        {
            Coaster c = chain(true);
            rebuild(c);
            Section s = c.pieces.get(0).sections.get(1);
            s.vDesign(s.vDesign() + 3.0);
            s.vPinned(true);
            TrackCompiler.Result r = rebuild(c);
            TrackCompiler.Span s1 = r.spans().get(1);
            double gap = Math.abs(r.trueV()[s1.i0()]
                - r.samples().get(s1.i0()).v());
            check(unequal(r, 0) == 0 && gap > 2.5
                    && unequal(r, 1) == s1.i1() - s1.i0() + 1,
                "i2a pinned +3: the section before copies, the pinned"
                + " one keeps the real march (no sample equal)",
                String.format("gap %.3f unequal %d", gap, unequal(r, 1)));
            // the section right of it, set to real, anchors on the
            // march's seam speed — entered at its design speed again
            check(c.refreezeSpeedOnly(0, 2, DT), "i2b the op applies", "");
            TrackCompiler.Result r2 = rebuild(c);
            check(bits(c.pieces.get(0).sections.get(2).vDesign(),
                    r2.trueV()[seam(c, r2, 0, 2)])
                    && unequal(r2, 2) == 0 && unequal(r2, 1) > 0,
                "i2c right of it, Set Design to Real: the section is"
                + " entered at its design speed and copies again",
                "unequal " + unequal(r2, 2));
        }
        // ---- i3: tilt, smoothing and bridges never copy ----
        {
            Coaster c = chain(true);
            TrackPiece p = c.pieces.get(0);
            p.orientMode = TrackPiece.OrientMode.REAL_ONLY;
            p.editPitch(3);
            TrackCompiler.Result r = rebuild(c);
            Coaster l = chain(true);
            TrackPiece q = l.pieces.get(0);
            q.orientMode = TrackPiece.OrientMode.LOCKSTEP;
            q.editPitch(3);   // design == true: no conflict tilt
            TrackCompiler.Result rl = rebuild(l);
            check(unequal(r, 0) > 0 && unequal(r, 1) > 0
                    && unequal(r, 2) > 0 && unequalAll(rl) == 0,
                "i3a a TILTED piece (real only, pitch 3) is never"
                + " copied; the same pitch in lockstep (no conflict)"
                + " copies everywhere",
                String.format("tilted unequal %d, lockstep %d",
                    unequalAll(r), unequalAll(rl)));
            Coaster s = chain(true);
            rebuild(s);
            int f = s.makeSeamStrict(0, 1, DT);
            TrackCompiler.Result rs0 = rebuild(s);
            boolean sharp = unequalAll(rs0) == 0;
            s.pieces.get(f).sSmoothLen = 6;
            TrackCompiler.Result rs = rebuild(s);
            check(sharp && unequal(rs, 0) > 0 && unequal(rs, 1) > 0,
                "i3b an untilted strict seam copies on both sides; with"
                + " 6 m of smoothing the follower's span and the"
                + " upstream window run the march",
                String.format("sharp unequal %d, smoothed %d + %d",
                    unequalAll(rs0), unequal(rs, 0), unequal(rs, 1)));
            Coaster b = bridged(true, 19);
            TrackCompiler.Result rb = rebuild(b);
            TrackCompiler.Span bsp = rb.spans().get(1);
            check(unequal(rb, 0) == 0 && unequal(rb, 1) > (bsp.i1()
                    - bsp.i0()) / 2 && unequal(rb, 2) > 0,
                "i3c the head copies, the BRIDGE never does, and the"
                + " conflicted piece behind it (19 vs the real speed)"
                + " keeps the march",
                String.format("bridge unequal %d of %d", unequal(rb, 1),
                    bsp.i1() - bsp.i0() + 1));
        }
        // ---- i4: the seam step of a control starting at x = 0 ----
        {
            Coaster c = chain(true);
            rebuild(c);
            ForceSection s1 = (ForceSection) c.pieces.get(0).sections.get(1);
            SpeedControl sc = new SpeedControl(0.0, 2.0);
            sc.type = SpeedControl.ACCEL;
            sc.speed = 60;
            sc.accel = 0.9;
            sc.decel = 0.9;
            s1.speedCtls.add(sc);
            // a conflict, so the span records the MARCH
            s1.vDesign(s1.vDesign() + 3.0);
            s1.vPinned(true);
            TrackCompiler.Result r = rebuild(c);
            int sm = seam(c, r, 0, 1);
            List<Boolean> cont = new ArrayList<>();
            for (int k = 0; k < r.spans().size(); k++) cont.add(k > 0);
            double[][] own = TrackCompiler.ctlSampleRows(r.samples(),
                r.spans(), c);
            double[][] all = TrackCompiler.ctlSampleRows(r.samples(),
                r.spans(), c, cont);
            // the march both ways (one run from sample 0, world
            // heights): the historic rows leave the seam step natural
            List<double[]> runs = new ArrayList<>();
            runs.add(new double[]{ 0,
                c.pieces.get(0).sections.get(0).vDesign() });
            double[] vo = TrackCompiler.passD(r.samples(), runs,
                c.fricRoll, c.fricAir, own, null);
            double[] vn = TrackCompiler.passD(r.samples(), runs,
                c.fricRoll, c.fricAir, all, null);
            double seamOld = vo[sm + 1] - vo[sm];
            double seamNew = vn[sm + 1] - vn[sm];
            double nextNew = vn[sm + 2] - vn[sm + 1];
            // the drive replaces friction over the seam step's arc:
            // (0.9 g + mu g + k v^2) * ds / v
            double ds = r.samples().get(sm + 1).s()
                - r.samples().get(sm).s();
            double want = (0.9 * FVDIntegrator.G0
                + c.fricRoll * FVDIntegrator.G0
                + c.fricAir * vn[sm] * vn[sm]) * ds / vn[sm];
            check(own != null && own[sm] == null && all != null
                    && all[sm] != null && all[sm][2] == 0.9
                    && all[sm + 1] != null && want > 0.05
                    && Math.abs(seamNew - seamOld - want) < 0.05 * want
                    && Math.abs(seamNew - nextNew) < 0.01
                    // compile's march enters this span from the
                    // DESIGN end of the one before (one flow), so its
                    // STEP is the comparable number
                    && Math.abs(r.trueV()[sm + 1] - r.trueV()[sm]
                        - seamNew) < 1e-5
                    && Math.abs(r.trueV()[sm + 1] - r.trueV()[sm]
                        - seamOld) > 0.07,
                "i4 a control starting at x = 0: the seam sample takes"
                + " the NEW section's row and the real march drives the"
                + " seam step like the next one (compile uses it)",
                String.format("seam step %+.4f (historic rows %+.4f),"
                    + " next %+.4f m/s", seamNew, seamOld, nextNew));
        }
        // ---- i5: bridged-in first section, friction ----
        {
            Coaster b = bridged(true, 19);
            TrackCompiler.Result r = rebuild(b);
            double real0 = r.trueV()[seam(b, r, 1, 0)];
            boolean ok = b.refreezeSpeedOnly(1, 0, DT);
            TrackCompiler.Result r2 = rebuild(b);
            double v1 = b.pieces.get(1).sections.get(0).vDesign();
            double real1 = r2.trueV()[seam(b, r2, 1, 0)];
            b.refreezeSpeedOnly(1, 0, DT);
            TrackCompiler.Result r3 = rebuild(b);
            double v2 = b.pieces.get(1).sections.get(0).vDesign();
            check(ok && Math.abs(real0 - 19) > 1 && bits(v1, real1)
                    && bits(v1, v2) && sameTrue(r2, r3)
                    && unequal(r2, 2) == 0 && unequal(r2, 3) == 0,
                "i5 bridged-in first section (19 typed, friction): Set"
                + " Design to Real lands on the real seam speed BITWISE,"
                + " a second press is a bitwise no-op, the piece copies",
                String.format("real before %.6f, vDesign %.12f real %.12f",
                    real0, v1, real1));
        }
        // ---- i6: at rest + through the file ----
        {
            Coaster c = chain(true);
            Section s = c.pieces.get(0).sections.get(1);
            rebuild(c);
            s.vDesign(s.vDesign() + 3.0);
            s.vPinned(true);
            rebuild(c);
            c.refreezeSpeedOnly(0, 1, DT);
            TrackCompiler.Result r = rebuild(c);
            TrackCompiler.Result again = rebuild(c);
            Coaster l = roundTrip(c);
            TrackCompiler.Result rl = rebuild(l);
            Coaster b = bridged(true, 19);
            rebuild(b);
            b.refreezeSpeedOnly(1, 0, DT);
            TrackCompiler.Result rb = rebuild(b);
            TrackCompiler.Result rbl = rebuild(roundTrip(b));
            check(sameTrue(r, again) && sameTrue(r, rl) && unequalAll(rl) == 0
                    && sameTrue(rb, rbl),
                "i6 a second rebuild and save → load → rebuild reproduce"
                + " every true speed bit (chain and bridged run)", "");
        }
        // ---- i7: behind a SMOOTHED seam ----
        {
            Coaster c = chain(true);
            rebuild(c);
            int f = c.makeSeamStrict(0, 1, DT);
            TrackPiece fol = c.pieces.get(f);
            fol.sPitchDeg += 8;
            fol.sSmoothLen = 10;
            Section s = fol.sections.get(0);
            s.vDesign(s.vDesign() + 2.5);
            TrackCompiler.Result r = rebuild(c);
            double live = r.trueV()[seam(c, r, f, 0)];
            // RE-PINNED v20.1 (D259 §3.5 A4): a strict FOLLOWER's first
            // section is RECONCILED once it is real-anchored — the op
            // flags it, so its smoothing-suspended write is not final:
            // the at-rest EXACT pass lands it on the DISPLAYED seam speed
            // like a continuation, which is what realEntrySpeed predicts
            // (max(0.1, the displayed real)). firstSectionSettle reads
            // NaN for a follower (the window's supplier then keeps that
            // prediction); a run head / bad index read NaN as before.
            // Until D259 the follower kept the op's suspended write.
            double settle = c.firstSectionSettle(f, DT);
            boolean ok = c.refreezeSpeedOnly(f, 0, DT);
            Section s7 = fol.sections.get(0);
            int[] n7 = { 0 };
            TrackCompiler.Result r2 = rebuildCount(c, n7);
            double v1 = s7.vDesign();
            double live2 = r2.trueV()[seam(c, r2, f, 0)];
            double[] re2 = dry(c, r2, f, 0);
            long b1 = Double.doubleToLongBits(v1);
            int[] n7b = { 0 };
            TrackCompiler.Result r3 = rebuildCount(c, n7b);
            check(c.anySmoothing() && ok && Double.isNaN(settle)
                    && s7.vRealAnchored() && !s7.vPinned()
                    && bits(v1, live2) && re2 != null && bits(re2[1], v1)
                    && Double.doubleToLongBits(s7.vDesign()) == b1
                    && n7b[0] == 0 && sumAll(r3) == sumAll(r2)
                    && Double.isNaN(c.firstSectionSettle(0, DT))
                    && Double.isNaN(c.firstSectionSettle(9, DT)),
                "i7 behind a smoothed seam (8° kink, 10 m): the strict"
                + " follower's first section is RECONCILED (D259 §3.5 A4)"
                + " — the op flags it, the rebuild lands vDesign on the"
                + " DISPLAYED seam speed BITWISE, the dry run then reads"
                + " no difference, firstSectionSettle reads NaN for a"
                + " follower (run head / bad index NaN too), a second"
                + " rebuild is a bitwise no-op",
                String.format("live %.6f -> %.6f vDesign %.9f exact"
                    + " rewrites %d then %d", live, live2, v1, n7[0], n7b[0]));
        }
    }

    static void initSpeedBlock() throws Exception {
        // ---- h1: topology + the null cases ----
        {
            Coaster c = chain(true);
            TrackCompiler.Result r = rebuild(c);
            Coaster d = detached();
            TrackCompiler.Result rd = rebuild(d);
            // a bridged fixture: turn piece → bridge → hill piece
            Coaster b = new Coaster();
            TrackPiece o = new TrackPiece(0, 21, 0, 0);
            o.sections.add(new ForceSection(turnLanes(), 24));
            b.pieces.add(o);
            TrackCompiler.Result r0 = TrackCompiler.compile(b, DT);
            TrackPose exit = r0.spans().get(0).exit();
            Vec3 tp = exit.pos().add(new Vec3(30, -4, 6));
            TrackPiece t = new TrackPiece(tp.x(), tp.y(), tp.z(), 0);
            t.baseOrient = exit.orient();
            t.sections.add(new ForceSection(hillLanes(), 19));
            BridgeSection bs = BridgeSection.solve(exit,
                new TrackPose(tp, exit.orient()),
                r0.samples().get(r0.spans().get(0).i1()).v());
            bs.optMode = BridgeSection.OPT_NONE;
            o.sections.add(bs);
            b.pieces.add(t);
            TrackCompiler.Result rb = rebuild(b);
            // a strict follower
            Coaster f = chain(true);
            rebuild(f);
            int fi = f.makeSeamStrict(0, 1, DT);
            rebuild(f);
            check(!c.hasUpstream(0, 0) && c.hasUpstream(0, 1)
                    && !d.hasUpstream(1, 0) && b.hasUpstream(1, 0)
                    && fi == 1 && f.hasUpstream(1, 0),
                "h1a hasUpstream: ride entry / detached head false;"
                + " continuation / bridged-in / follower true", "");
            Coaster rv = chain(true);
            rv.pieces.get(0).sections.get(1).reversed(true);
            TrackCompiler.Result rr = rebuild(rv);
            check(dry(c, r, 0, 0) == null && dry(d, rd, 1, 0) == null
                    && dry(b, rb, 0, 1) == null && dry(rv, rr, 0, 1) == null
                    && dry(c, r, 0, 9) == null && dry(c, r, 3, 0) == null
                    && c.realEntrySpeed(0, 1, null, null, null) == null
                    && dry(c, r, 0, 1) != null && dry(b, rb, 1, 0) != null,
                "h1b realEntrySpeed null: ride entry, detached head,"
                + " bridge section, reversed section, bad index,"
                + " nothing compiled", "");
        }
        // ---- h2: pinned continuation typed +3, friction ----
        {
            Coaster c = chain(true);
            rebuild(c);
            Section s = c.pieces.get(0).sections.get(1);
            s.vDesign(s.vDesign() + 3.0);
            s.vPinned(true);
            TrackCompiler.Result r = rebuild(c);
            int ip = seam(c, r, 0, 1);
            double[] re = dry(c, r, 0, 1);
            check(re != null && bits(re[0], r.trueV()[ip])
                    && bits(re[1], Math.max(0.1, r.samples().get(ip).v()))
                    && speedDiffers(s.vDesign(), re[1]),
                "h2a pinned +3 (friction): dry run = {true seam speed,"
                + " the previous section's DESIGN end}",
                re == null ? "null" : String.format(
                    "real %.12f settle %.12f vDesign %.6f", re[0], re[1],
                    s.vDesign()));
            check(c.refreezeSpeedOnly(0, 1, DT), "h2b the op applies", "");
            TrackCompiler.Result r2 = rebuild(c);
            Section s2 = c.pieces.get(0).sections.get(1);
            check(re != null && bits(s2.vDesign(), re[1]) && !s2.vPinned()
                    && bits(s2.vDesign(), r2.trueV()[seam(c, r2, 0, 1)])
                    && bits(re[0], re[1]),
                "h2c op + rebuild land ON the predicted bits, un-pinned"
                + " — the REAL seam speed bit for bit, friction on",
                String.format("vDesign %.12f real %.12f", s2.vDesign(),
                    r2.trueV()[seam(c, r2, 0, 1)]));
        }
        // ---- h3: REAL-ANCHORED continuations ----
        {
            Coaster c = chain(true);
            rebuild(c);
            Section s1 = c.pieces.get(0).sections.get(1);
            s1.vDesign(s1.vDesign() + 3.0);
            s1.vPinned(true);
            TrackCompiler.Result r = rebuild(c);
            double[] re = dry(c, r, 0, 2);
            boolean pred = re != null
                && bits(re[1], Math.max(0.1, r.trueV()[seam(c, r, 0, 2)]));
            boolean ok = c.refreezeSpeedOnly(0, 2, DT);
            TrackCompiler.Result r2 = rebuild(c);
            double v = c.pieces.get(0).sections.get(2).vDesign();
            check(pred && ok && bits(v, r2.trueV()[seam(c, r2, 0, 2)]),
                "h3a right of a +3 pinned section: predicted = the true"
                + " seam speed, op + rebuild land on it BITWISE",
                String.format("vDesign %.12f", v));
        }
        {
            Coaster c = chain(true);
            TrackPiece p = c.pieces.get(0);
            p.orientMode = TrackPiece.OrientMode.REAL_ONLY;
            p.editPitch(3);
            TrackCompiler.Result r = rebuild(c);
            double pd = p.pitchDeg, dd = p.designPitchDeg;
            double[] re = dry(c, r, 0, 1);
            boolean pred = re != null
                && bits(re[1], Math.max(0.1, r.trueV()[seam(c, r, 0, 1)]));
            boolean ok = c.refreezeSpeedOnly(0, 1, DT);
            TrackCompiler.Result r2 = rebuild(c);
            double v = c.pieces.get(0).sections.get(1).vDesign();
            check(pred && ok && bits(v, r2.trueV()[seam(c, r2, 0, 1)])
                    && bits(p.pitchDeg, pd) && bits(p.designPitchDeg, dd)
                    && p.orientConflict(),
                "h3b tilted piece (pitch 3, real only): lands bitwise on"
                + " the true seam speed, the orientation conflict stays",
                String.format("vDesign %.12f pitch %.1f design %.1f", v,
                    p.pitchDeg, p.designPitchDeg));
        }
        // ---- h4: strict follower's first section ----
        {
            Coaster c = chain(true);
            rebuild(c);
            int f = c.makeSeamStrict(0, 1, DT);
            rebuild(c);
            Section s = c.pieces.get(f).sections.get(0);
            s.vDesign(s.vDesign() + 2.5);
            TrackCompiler.Result r = rebuild(c);
            double[] re = dry(c, r, f, 0);
            boolean pred = re != null
                && bits(re[0], r.trueV()[seam(c, r, f, 0)])
                && bits(re[1], Math.max(0.5, re[0]))
                && speedDiffers(s.vDesign(), re[1]);
            boolean ok = c.refreezeSpeedOnly(f, 0, DT);
            TrackCompiler.Result r2 = rebuild(c);
            double v1 = c.pieces.get(f).sections.get(0).vDesign();
            boolean land = bits(v1, r2.trueV()[seam(c, r2, f, 0)])
                && bits(v1, re == null ? Double.NaN : re[1]);
            c.refreezeSpeedOnly(f, 0, DT);
            rebuild(c);
            double v2 = c.pieces.get(f).sections.get(0).vDesign();
            check(pred && ok && land && bits(v1, v2),
                "h4 strict follower (+2.5 stale): lands bitwise on the"
                + " true seam speed == the dry run; a second press is a"
                + " bitwise no-op", String.format("vDesign %.12f", v1));
        }
        // ---- h5: a sub-0.2 click STICKS (D256 — the verdict REVERSED) ----
        {
            Coaster c = chain(true);
            rebuild(c);
            Section s1 = c.pieces.get(0).sections.get(1);
            s1.vDesign(s1.vDesign() + 0.05);
            s1.vPinned(true);
            TrackCompiler.Result r = rebuild(c);
            Section s2 = c.pieces.get(0).sections.get(2);
            double v0 = s2.vDesign();
            int ip = seam(c, r, 0, 2);
            double[] re = dry(c, r, 0, 2);
            // h5b: UNPRESSED the section right of the +0.05 pin settles
            // on the DESIGN chain — bitwise the previous design end,
            // 0.005..0.2 off the real seam speed (the proximity gate's
            // fallback: every old file unchanged)
            check(bits(v0, r.samples().get(ip).v()) && !s2.vRealAnchored()
                    && Math.abs(v0 - r.trueV()[ip]) > SHOW_EPS
                    && Math.abs(v0 - r.trueV()[ip]) < EPS,
                "h5b UNPRESSED, right of a +0.05 pinned section: settles on"
                + " the design chain bitwise (the proximity gate's fallback)",
                String.format(Locale.US, "vDesign %.6f real %.6f", v0,
                    r.trueV()[ip]));
            boolean live = re != null
                && bits(re[1], Math.max(0.1, r.trueV()[ip]))
                && speedDiffers(v0, re[1]);
            boolean ok = c.refreezeSpeedOnly(0, 2, DT);
            TrackCompiler.Result r2 = rebuild(c);
            double v1 = s2.vDesign();
            boolean land = bits(v1, r2.trueV()[seam(c, r2, 0, 2)])
                && s2.vRealAnchored() && !s2.vPinned();
            Coaster l = roundTrip(c);
            TrackCompiler.Result rl = rebuild(l);
            Section ls2 = l.pieces.get(0).sections.get(2);
            check(live && ok && land && ls2.vRealAnchored()
                    && bits(ls2.vDesign(), v1) && sameTrue(rl, r2),
                "h5 unpinned, right of a +0.05 pinned section: the dry run"
                + " predicts the TRUE seam speed, op + rebuild land on it"
                + " BITWISE, the real anchor survives save -> load",
                re == null ? "null" : String.format(Locale.US,
                    "vDesign %.9f -> %.9f real %.9f", v0, v1, re[0]));
        }
        // ---- h6: RUN HEADS write nothing ----
        {
            Coaster d = detached();
            rebuild(d);
            Section s = d.pieces.get(1).sections.get(0);
            s.vPinned(true);
            boolean ok = d.refreezeSpeedOnly(1, 0, DT);
            rebuild(d);
            check(ok && bits(d.pieces.get(1).sections.get(0).vDesign(), 17.0)
                    && !d.pieces.get(1).sections.get(0).vPinned(),
                "h6a detached head at piece 1: the op returns true,"
                + " vDesign stays 17.0 bitwise (it took the previous"
                + " piece's end speed), un-pinned",
                "vDesign " + d.pieces.get(1).sections.get(0).vDesign());
            Coaster e = chain(true);
            rebuild(e);
            long b0 = Double.doubleToLongBits(
                e.pieces.get(0).sections.get(0).vDesign());
            boolean ok0 = e.refreezeSpeedOnly(0, 0, DT);
            rebuild(e);
            check(ok0 && Double.doubleToLongBits(
                    e.pieces.get(0).sections.get(0).vDesign()) == b0,
                "h6b the ride entry: vDesign bits untouched", "");
            Coaster t = detached();
            TrackPiece q = t.pieces.get(1);
            q.orientMode = TrackPiece.OrientMode.REAL_ONLY;
            q.editRoll(15);
            rebuild(t);
            boolean okR = t.refreezeRange(1, DT);
            rebuild(t);
            check(okR && bits(t.pieces.get(1).sections.get(0).vDesign(), 17.0)
                    && !t.pieces.get(1).orientConflict(),
                "h6c refreezeRange on a TILTED detached head: the tilt"
                + " bakes, vDesign stays 17.0 bitwise",
                "vDesign " + t.pieces.get(1).sections.get(0).vDesign());
            Coaster m = detached();
            rebuild(m);
            boolean okM = m.makeGraphsReal(1, 0, DT, 0.1, 0.003);
            rebuild(m);
            check(okM && bits(m.pieces.get(1).sections.get(0).vDesign(), 17.0),
                "h6d makeGraphsReal on a detached head anchors at its OWN"
                + " entry: the refit's vDesign is 17.0 bitwise",
                "vDesign " + m.pieces.get(1).sections.get(0).vDesign());
        }
        // ---- h7: a STALLED seam ----
        {
            Coaster c = chain(true);
            TrackPiece p = c.pieces.get(0);
            p.orientMode = TrackPiece.OrientMode.REAL_ONLY;
            p.editPitch(12);
            TrackCompiler.Result r = rebuild(c);
            double[] re = dry(c, r, 0, 1);
            boolean pred = re != null && re[0] == 0.0 && re[1] == 0.1;
            boolean ok = c.refreezeSpeedOnly(0, 1, DT);
            TrackCompiler.Result r2 = rebuild(c);
            double v = c.pieces.get(0).sections.get(1).vDesign();
            double[] re2 = dry(c, r2, 0, 1);
            check(pred && ok && v == 0.1 && re2 != null
                    && !speedDiffers(v, re2[1]),
                "h7 stalled seam: real 0.0, settle 0.1; op + rebuild"
                + " land on 0.1 and the dry run agrees (button grays)",
                re == null ? "null" : String.format(
                    "real %.3f settle %.3f vDesign %.3f", re[0], re[1], v));
        }
    }

    // ---------- batch 38 (D256): the "~v" SLIGHT-conflict glyph ----------
    /** The badge's three-state level on the user's fixture after the
     *  app's settle recipe (rebuild): the run head and the two
     *  continuations of piece 0 enter AT their design speed (level 0 —
     *  no glyph), while the strict follower's two sections enter 0.045
     *  / 0.077 m/s off (level 1 — " ~v"; nothing showed before batch
     *  38, and the 0.2 glyph ≠v must NOT appear). */
    static void slightGlyphBlock() throws Exception {
        Coaster c = loadB38();
        TrackCompiler.Result r = rebuild(c);
        check(c.pieces.size() == 2 && c.pieces.get(0).sections.size() == 3
                && c.pieces.get(1).sections.size() == 2
                && c.followsPrev(1),
            "k0 the fixture loads: 3 + 2 sections, piece 1 a strict follower",
            c.pieces.size() + " pieces");
        for (int p = 0; p < c.pieces.size(); p++) {
            for (int k = 0; k < c.pieces.get(p).sections.size(); k++) {
                System.out.printf(Locale.US,
                    "      piece %d sec %d: entry gap %.6f level %d%n",
                    p, k, entryGap(c, r, p, k), entryLevel(c, r, p, k));
            }
        }
        check(entryLevel(c, r, 0, 0) == 0,
            "k1 the run head enters at its design speed: level 0",
            String.format(Locale.US, "gap %.6f", entryGap(c, r, 0, 0)));
        check(entryLevel(c, r, 0, 1) == 0 && entryLevel(c, r, 0, 2) == 0,
            "k2 piece 0 secs 1-2 (a pinned-at-real + a plain continuation): level 0",
            String.format(Locale.US, "gaps %.6f / %.6f",
                entryGap(c, r, 0, 1), entryGap(c, r, 0, 2)));
        check(entryLevel(c, r, 1, 0) == 1,
            "k3 the follower's first section: a SLIGHT conflict (\" ~v\")",
            String.format(Locale.US, "gap %.6f", entryGap(c, r, 1, 0)));
        check(entryLevel(c, r, 1, 1) == 1,
            "k4 the follower's second section: a SLIGHT conflict (\" ~v\")",
            String.format(Locale.US, "gap %.6f", entryGap(c, r, 1, 1)));
        double g0 = entryGap(c, r, 1, 0), g1 = entryGap(c, r, 1, 1);
        check(g0 > Gestures.INIT_SPEED_SHOW_EPS && g0 <= Gestures.DRIFT_EPS
                && g1 > Gestures.INIT_SPEED_SHOW_EPS && g1 <= Gestures.DRIFT_EPS,
            "k5 both gaps sit between the box's 0.005 and the 0.2 threshold (neither ≠v)",
            String.format(Locale.US, "%.6f / %.6f", g0, g1));
        // the levels are a pure function of the displayed compile: a
        // second rebuild at rest reads the same
        TrackCompiler.Result r2 = rebuild(c);
        boolean same = true;
        for (int p = 0; p < c.pieces.size(); p++) {
            for (int k = 0; k < c.pieces.get(p).sections.size(); k++) {
                if (entryLevel(c, r2, p, k) != entryLevel(c, r, p, k)) same = false;
            }
        }
        check(same, "k6 a rebuild at rest keeps every level", "");
    }

    // ---------- batch 38 (D256): the EXPLICIT REAL ANCHOR ----------

    /** Checksum of a sample range's positions + speeds + true speeds
     *  (bitwise). */
    static long sum(TrackCompiler.Result r, int i0, int i1) {
        long h = 17;
        for (int i = i0; i <= i1; i++) {
            FVDIntegrator.Sample s = r.samples().get(i);
            h = h * 31 + Double.doubleToLongBits(s.pos().x());
            h = h * 31 + Double.doubleToLongBits(s.pos().y());
            h = h * 31 + Double.doubleToLongBits(s.pos().z());
            h = h * 31 + Double.doubleToLongBits(s.v());
            h = h * 31 + Double.doubleToLongBits(r.trueV()[i]);
        }
        return h;
    }

    static long sumAll(TrackCompiler.Result r) {
        return sum(r, 0, Math.min(r.samples().size(), r.trueV().length) - 1);
    }

    static String saveText(Coaster c) {
        java.io.StringWriter sw = new java.io.StringWriter();
        DesignIO.save(c, DesignIO.Cosmetics.defaults(),
            new java.io.PrintWriter(sw));
        return sw.toString();
    }

    static Coaster loadText(String t) throws Exception {
        return DesignIO.load(new BufferedReader(new StringReader(t)))
            .coasters().get(0);
    }

    /** rebuild() with the EXACT pass's rewrite count in out[0]. */
    static TrackCompiler.Result rebuildCount(Coaster c, int[] out) {
        TrackCompiler.Result r = TrackCompiler.compile(c, DT);
        for (int p = 0; p < 12; p++) {
            boolean again = c.reconcileChainedSpeeds(r);
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            r = TrackCompiler.compile(c, DT);
        }
        int n = 0;
        for (int p = 0; p < 40; p++) {
            boolean again = c.reconcileChainedSpeeds(r, true);
            if (again) n++;
            if (c.autoReoptimizeBridges(r)) again = true;
            if (!again) break;
            r = TrackCompiler.compile(c, DT);
        }
        out[0] = n;
        return r;
    }

    static TrackCompiler.Span span(Coaster c, TrackCompiler.Result r,
                                   int p, int k) {
        return r.spans().get(spanIdx(c, p) + k);
    }

    /** The section line DesignIO writes for a section at vDesign v
     *  with the given pinned-slot value (force / sc). */
    static String secLine(String kind, double v, int slot) {
        // an s/c line ENDS on the slot (no trailing field); a force line
        // carries the six flag fields after it
        return "section " + kind + " " + Double.toString(v)
            + (kind.equals("sc") ? " distance 0 " + slot
                                 : " time 0 " + slot + " ");
    }

    static void realAnchorBlock() throws Exception {
        // ---- l1: the user's bug reproduces ----
        Coaster c = loadB38();
        TrackCompiler.Result r = rebuild(c);
        Section s0 = c.pieces.get(1).sections.get(0);
        Section s1 = c.pieces.get(1).sections.get(1);
        int ip = seam(c, r, 1, 1);
        double designEnd = r.samples().get(ip).v();
        double realSeam = r.trueV()[ip];
        double g0 = entryGap(c, r, 1, 0);
        double gap = Math.abs(designEnd - realSeam);
        check(g0 > SHOW_EPS && g0 < EPS && bits(s1.vDesign(), designEnd)
                && gap > SHOW_EPS && gap < EPS
                && Math.abs(realSeam - 13.913246110656) < 1e-9
                && !s1.vPinned() && !s1.vRealAnchored(),
            "l1 reproduces: p1s0 enters 0.045 off (no ≠v), p1s1 sits bitwise"
            + " on p1s0's DESIGN end, 0.078 under the real seam speed 13.9132",
            String.format(Locale.US,
                "p1s0 gap %.6f p1s1 vDesign %.9f real %.9f",
                g0, s1.vDesign(), realSeam));
        Quat tq = c.seamConflictTilt(1,
            r.spans().get(spanIdx(c, 1) - 1).exit());
        // a NON-IDENTITY seam tilt (TrackCompiler's clean-span rule keys
        // on tilt == IDENTITY, heading re-aims included) — the follower's
        // interior can never be a clean span, so only the SEAM speed is
        // asserted bitwise below; whether the tilt has a horizontal
        // component (the ≠o glyph) is reported, not asserted
        check(tq != null && Math.abs(tq.w()) < 1 - 1e-14,
            "l1b piece 1 is a TILTED follower (its interior can never be"
            + " a clean span)",
            tq == null ? "null" : String.format(Locale.US,
                "w %.6f x %.3e y %.3e z %.3e orient-conflict(≠o) %b",
                tq.w(), tq.x(), tq.y(), tq.z(), conflictAt(c, r, 1)));
        // ---- l3a: the dry run BEFORE the press ----
        double[] re0 = dry(c, r, 1, 1);
        check(re0 != null && bits(re0[0], realSeam)
                && bits(re0[1], Math.max(0.1, realSeam))
                && speedDiffers(s1.vDesign(), re0[1]),
            "l3a the dry run: real = the seam speed, settle = max(0.1, real),"
            + " the click would change the design speed (the window shows"
            + " a conflict)",
            re0 == null ? "null" : String.format(Locale.US,
                "settle %.9f", re0[1]));
        // ---- l2: the op lands BITWISE and sticks ----
        String preText = saveText(c);
        long p0sum = sum(r, 0, span(c, r, 0, 2).i1());
        long p1s0sum = sum(r, span(c, r, 1, 0).i0(), span(c, r, 1, 0).i1());
        double v00 = s0.vDesign();
        boolean ok = c.refreezeSpeedOnly(1, 1, DT);
        check(ok && s1.vRealAnchored() && !s1.vPinned(),
            "l2a Set Design to Real applies: un-pinned + REAL-ANCHORED", "");
        int[] n1 = { 0 };
        TrackCompiler.Result r1 = rebuildCount(c, n1);
        check(bits(s1.vDesign(), r1.trueV()[seam(c, r1, 1, 1)])
                && bits(s1.vDesign(), realSeam),
            "l2b after the rebuild p1s1's vDesign == the DISPLAYED real seam"
            + " speed BITWISE (the exact pass lands the flagged section)",
            String.format(Locale.US, "vDesign %.12f exact rewrites %d",
                s1.vDesign(), n1[0]));
        check(sum(r1, 0, span(c, r1, 0, 2).i1()) == p0sum
                && sum(r1, span(c, r1, 1, 0).i0(),
                       span(c, r1, 1, 0).i1()) == p1s0sum
                && bits(s0.vDesign(), v00),
            "l2c piece 0 and p1s0 (samples + true speeds + vDesign) bitwise"
            + " untouched — p1s0's own 0.045 stays", "");
        long b1 = Double.doubleToLongBits(s1.vDesign());
        int[] n2 = { 0 };
        TrackCompiler.Result r2 = rebuildCount(c, n2);
        check(Double.doubleToLongBits(s1.vDesign()) == b1 && n2[0] == 0
                && sumAll(r2) == sumAll(r1),
            "l2d a second rebuild is a bitwise no-op (0 exact rewrites)", "");
        boolean ok2 = c.refreezeSpeedOnly(1, 1, DT);
        TrackCompiler.Result r3 = rebuild(c);
        check(ok2 && Double.doubleToLongBits(s1.vDesign()) == b1
                && sumAll(r3) == sumAll(r1) && s1.vRealAnchored()
                && !s1.vPinned(),
            "l2e a second press + rebuild is a bitwise no-op (the op's"
            + " suspended read is corrected by the exact pass)", "");
        double[] re1 = dry(c, r3, 1, 1);
        check(re1 != null && !speedDiffers(s1.vDesign(), re1[1])
                && !speedDiffers(s1.vDesign(), re1[0]),
            "l3b after the press the window reads no conflict"
            + " (real == vDesign)", "");
        // the pre-op document — what the undo snapshot holds — rebuilds
        // bitwise to the pre-op compile (DocState itself is JavaFX-side;
        // the IO round trip is its headless twin)
        Coaster u = loadText(preText);
        TrackCompiler.Result ru = rebuild(u);
        check(sameTrue(ru, r) && sumAll(ru) == sumAll(r)
                && bits(u.pieces.get(1).sections.get(1).vDesign(), designEnd)
                && !u.pieces.get(1).sections.get(1).vRealAnchored(),
            "l2f the pre-op document reproduces the pre-op compile bitwise"
            + " (undo's content: unflagged, on the design end)", "");
        // ---- l4: save -> load -> rebuild through the "2" slot ----
        String t1 = saveText(c);
        String flagged = secLine("force", s1.vDesign(), 2);
        boolean slot2 = t1.contains(flagged);
        Coaster l = loadText(t1);
        TrackCompiler.Result rl = rebuild(l);
        Section ls1 = l.pieces.get(1).sections.get(1);
        check(slot2 && ls1.vRealAnchored() && !ls1.vPinned()
                && bits(ls1.vDesign(), s1.vDesign()) && sameTrue(rl, r1)
                && sumAll(rl) == sumAll(r1) && saveText(l).equals(t1),
            "l4 save -> load -> rebuild bitwise: the pinned slot writes 2,"
            + " loads as the anchor, re-saves byte-identical", "");
        // ---- l7: the flag is LOAD-BEARING ----
        String t0 = t1.replace(flagged, secLine("force", s1.vDesign(), 0));
        Coaster m = loadText(t0);
        TrackCompiler.Result rm = rebuild(m);
        Section ms1 = m.pieces.get(1).sections.get(1);
        int ipm = seam(m, rm, 1, 1);
        check(!t0.equals(t1) && !ms1.vRealAnchored()
                && bits(ms1.vDesign(), rm.samples().get(ipm).v())
                && !bits(ms1.vDesign(), rm.trueV()[ipm])
                && Math.abs(ms1.vDesign() - designEnd) < 1e-9,
            "l7 without the flag (slot forced to 0) the reconcile snaps the"
            + " same speed back onto the design end — the bug, an old"
            + " reader's view",
            String.format(Locale.US, "vDesign %.9f", ms1.vDesign()));
        // ---- l5: robustness — an upstream edit after the resolve ----
        s0.vDesign(24.6);
        s0.vPinned(true);
        TrackCompiler.Result r5 = rebuild(c);
        int ip5 = seam(c, r5, 1, 1);
        check(bits(s1.vDesign(), r5.trueV()[ip5])
                && !bits(s1.vDesign(), r5.samples().get(ip5).v())
                && Math.abs(r5.trueV()[ip5] - r5.samples().get(ip5).v())
                   > SHOW_EPS,
            "l5 p1s0 := 24.6 pinned: the flagged p1s1 FOLLOWS the new real"
            + " seam speed bitwise (not the design end)",
            String.format(Locale.US, "vDesign %.9f design end %.9f",
                s1.vDesign(), r5.samples().get(ip5).v()));
        // ---- l10: the reconcile IS B's rule (refreezeSeamSpeed's twin) ----
        s1.vDesign(s1.vDesign() + 0.001);   // nudge the flagged section
        TrackCompiler.Result rz = TrackCompiler.compile(c, DT);
        boolean wrote = c.reconcileChainedSpeeds(rz, true);
        int ipz = seam(c, rz, 1, 1);
        check(wrote && bits(s1.vDesign(), Math.max(0.1, rz.trueV()[ipz])),
            "l10 a flagged section nudged 0.001 off is written back to the"
            + " real seam speed by ONE exact pass (B's rule == the"
            + " reconcile's)", "");
        // ---- l6: smoothing 0 on a copy ----
        Coaster d = loadB38();
        for (TrackPiece tp : d.pieces) tp.sSmoothLen = 0;
        rebuild(d);
        d.refreezeSpeedOnly(1, 1, DT);
        TrackCompiler.Result rd = rebuild(d);
        Section ds1 = d.pieces.get(1).sections.get(1);
        check(ds1.vRealAnchored()
                && bits(ds1.vDesign(), rd.trueV()[seam(d, rd, 1, 1)]),
            "l6 with the seam smoothing off the op lands bitwise the same"
            + " way (the smoothing was never the cause)", "");
        // ---- l8: Convert Real to Design flags the same way ----
        Coaster e = loadB38();
        rebuild(e);
        boolean okE = e.makeGraphsReal(1, 1, DT, 0.1, 0.01);
        Section es1 = e.pieces.get(1).sections.get(1);
        TrackCompiler.Result rE = rebuild(e);
        check(okE && es1.vRealAnchored() && !es1.vPinned()
                && bits(es1.vDesign(), rE.trueV()[seam(e, rE, 1, 1)]),
            "l8 makeGraphsReal(1,1): the fitted section is real-anchored"
            + " and lands bitwise on the real seam speed", "");
        // ---- l9: the '+' real fork ----
        Coaster f = loadB38();
        TrackCompiler.Result rf = rebuild(f);
        LaneSection fPrev = (LaneSection) f.pieces.get(1).sections.get(1);
        double vEndTrue = Math.max(0.1, rf.trueV()[span(f, rf, 1, 1).i1()]);
        LaneSection add = Coaster.continuation(fPrev, vEndTrue);
        add.vRealAnchored(true);
        f.pieces.get(1).sections.add(add);
        TrackCompiler.Result rf2 = rebuild(f);
        int ipf = seam(f, rf2, 1, 2);
        Coaster g = loadB38();
        TrackCompiler.Result rg = rebuild(g);
        LaneSection gPrev = (LaneSection) g.pieces.get(1).sections.get(1);
        LaneSection addG = Coaster.continuation(gPrev,
            Math.max(0.1, rg.trueV()[span(g, rg, 1, 1).i1()]));
        g.pieces.get(1).sections.add(addG);
        TrackCompiler.Result rg2 = rebuild(g);
        int ipg = seam(g, rg2, 1, 2);
        check(bits(add.vDesign(), rf2.trueV()[ipf])
                && bits(addG.vDesign(), rg2.samples().get(ipg).v())
                && Math.abs(rg2.trueV()[ipg] - rg2.samples().get(ipg).v())
                   > SHOW_EPS,
            "l9 the '+' Transition-to-real hold: flagged it lands on the real"
            + " end speed; unflagged (the old gate) the same hold is snapped"
            + " onto the design end",
            String.format(Locale.US, "flagged %.9f unflagged %.9f real %.9f",
                add.vDesign(), addG.vDesign(), rg2.trueV()[ipg]));
        // ---- l11-l13: item e's model states (the always-enabled click) ----
        Coaster h = loadB38();
        TrackCompiler.Result rh = rebuild(h);
        Section hs = h.pieces.get(0).sections.get(1);
        double[] reh = dry(h, rh, 0, 1);
        hs.vDesign(Math.max(0.5, reh[0]));   // pinned AT the real speed
        hs.vPinned(true);
        rh = rebuild(h);
        reh = dry(h, rh, 0, 1);
        long hb = Double.doubleToLongBits(hs.vDesign());
        boolean okH = h.refreezeSpeedOnly(0, 1, DT);
        TrackCompiler.Result rh2 = rebuild(h);
        check(okH && !speedDiffers(hs.vDesign(), reh[1]) && !hs.vPinned()
                && hs.vRealAnchored()
                && Double.doubleToLongBits(hs.vDesign()) == hb
                && bits(hs.vDesign(), rh2.trueV()[seam(h, rh2, 0, 1)]),
            "l11 a continuation pinned AT its real seam speed (no conflict):"
            + " the click un-pins + flags, the bits stay through the rebuild",
            String.format(Locale.US, "vDesign %.9f", hs.vDesign()));
        hs.vDesign(Math.max(0.5, reh[0]) + 0.003);   // under the box's 0.005
        hs.vPinned(true);
        rh = rebuild(h);
        okH = h.refreezeSpeedOnly(0, 1, DT);
        rh2 = rebuild(h);
        check(okH && !hs.vPinned() && hs.vRealAnchored()
                && bits(hs.vDesign(), rh2.trueV()[seam(h, rh2, 0, 1)]),
            "l12 pinned 0.003 off the real speed: the click lands"
            + " BIT-EXACTLY on it and un-pins",
            String.format(Locale.US, "vDesign %.9f", hs.vDesign()));
        Section head = h.pieces.get(0).sections.get(0);
        long hb0 = Double.doubleToLongBits(head.vDesign());
        boolean ph = head.vPinned();
        okH = h.refreezeSpeedOnly(0, 0, DT);
        check(okH && Double.doubleToLongBits(head.vDesign()) == hb0
                && head.vPinned() == ph && !head.vRealAnchored(),
            "l13 the run head's click: writes nothing, un-pins only, sets no"
            + " anchor", "");
        // ---- l14: the slot on s/c lines + the carries ----
        {
            Coaster sc = chain(true);
            TrackPiece tp = sc.pieces.get(0);
            StraightCurveSection st = StraightCurveSection.straight(10, 20);
            st.vRealAnchored = true;
            tp.sections.add(st);
            StraightCurveSection pn = StraightCurveSection.straight(10, 20);
            pn.vPinned = true;
            pn.vRealAnchored = true;   // a pin wins on the line too
            tp.sections.add(pn);
            String t = saveText(sc);
            Coaster back = loadText(t);
            Section b3 = back.pieces.get(0).sections.get(3);
            Section b4 = back.pieces.get(0).sections.get(4);
            check(t.contains(secLine("sc", 20, 2)) && t.contains(secLine("sc", 20, 1))
                    && b3.vRealAnchored() && !b3.vPinned()
                    && b4.vPinned() && !b4.vRealAnchored(),
                "l14a an s/c section writes 2 / loads anchored; a pinned one"
                + " writes 1 and loads pinned, unflagged", "");
            Section cp = Coaster.copySection(st, 5);
            Section cp2 = Coaster.copySection(tp.sections.get(1), 5);
            tp.sections.get(1).vRealAnchored(true);
            Section cp3 = Coaster.copySection(tp.sections.get(1), 5);
            check(cp.vRealAnchored() && !cp2.vRealAnchored() && cp3.vRealAnchored(),
                "l14b copySection carries the flag (s/c + lanes)", "");
            tp.sections.remove(4);
            tp.sections.remove(3);
            rebuild(sc);
            Section s1c = tp.sections.get(1);
            boolean split = sc.splitSectionAt(0, 1, 1.0, DT);
            check(split && tp.sections.get(1).vRealAnchored()
                    && !tp.sections.get(2).vRealAnchored(),
                "l14c splitSectionAt: the LEFT half keeps the anchor, the"
                + " right half (the design profile at the cut) does not", "");
            boolean merged = sc.mergeSections(0, 1);
            check(merged && tp.sections.get(1).vRealAnchored()
                    && tp.sections.size() == 3,
                "l14d mergeSections takes A's flag", "");
            tp.sections.get(1).vRealAnchored(false);
            tp.sections.get(2).vRealAnchored(true);
            boolean merged2 = sc.mergeSections(0, 1);
            check(merged2 && !tp.sections.get(1).vRealAnchored(),
                "l14e mergeSections: B's flag does not survive (its seam is"
                + " gone)", "");
        }
        // ---- l14f-h: the four CONVERT carries (D256 review — the
        // entry-seam rule on partialConvertSection's two sides,
        // convertDomain, bakeAxes and convertWholeSection) ----
        {
            Coaster pc = chain(true);
            rebuild(pc);
            TrackPiece tp = pc.pieces.get(0);
            tp.sections.get(1).vRealAnchored(true);
            boolean okL = pc.partialConvertSection(0, 1, 0.5, true, false,
                DT, 0.1, 0.01);
            check(okL && tp.sections.size() == 4
                    && tp.sections.get(1).vRealAnchored()
                    && !tp.sections.get(2).vRealAnchored(),
                "l14f partialConvertSection LEFT: the converted half (the"
                + " entry's) keeps the anchor, the kept right half does not",
                "");
            Coaster pc2 = chain(true);
            rebuild(pc2);
            TrackPiece tp2 = pc2.pieces.get(0);
            tp2.sections.get(1).vRealAnchored(true);
            boolean okR = pc2.partialConvertSection(0, 1, 0.5, false, false,
                DT, 0.1, 0.01);
            check(okR && tp2.sections.size() == 4
                    && tp2.sections.get(1).vRealAnchored()
                    && !tp2.sections.get(2).vRealAnchored(),
                "l14g partialConvertSection RIGHT: the kept left half (the"
                + " entry's) keeps the anchor, the converted right half does"
                + " not", "");
            Coaster pc3 = chain(true);
            rebuild(pc3);
            TrackPiece tp3 = pc3.pieces.get(0);
            tp3.sections.get(1).vRealAnchored(true);
            Section before = tp3.sections.get(1);
            boolean okD = pc3.convertDomain(0, 1, DT, 0.2, 0.01);
            Section afterD = tp3.sections.get(1);
            boolean okA = pc3.bakeAxes(0, 1, true, false, DT, 0.2, 0.01);
            Section afterA = tp3.sections.get(1);
            boolean okW = pc3.convertWholeSection(0, 1, true, DT, 0.2, 0.01);
            Section afterW = tp3.sections.get(1);
            check(okD && afterD != before && afterD.vRealAnchored()
                    && okA && afterA != afterD && afterA.vRealAnchored()
                    && okW && afterW != afterA && afterW.vRealAnchored()
                    && afterW instanceof GeoSection,
                "l14h convertDomain, bakeAxes and convertWholeSection each"
                + " install a NEW object carrying the anchor",
                "D " + okD + " A " + okA + " W " + okW);
        }
    }

    public static void main(String[] args) throws Exception {
        // ---- (a) the scenario reproduces ----
        Coaster c = load();
        TrackCompiler.Result r = rebuild(c);
        double dA = drift(r, 1), dB = drift(r, 2);
        check(dB > EPS && dA < EPS,
            "a1 amplified badge on B only (the phantom's shape)",
            String.format("A %.3f B %.3f", dA, dB));

        // ---- (b) Set Design to Real on B STICKS bitwise ----
        Coaster c2 = load();
        rebuild(c2);
        check(c2.refreezeSpeed(1, 1, DT), "b1 refreeze applies", "");
        TrackCompiler.Result r2 = rebuild(c2);
        double vAfter = c2.pieces.get(1).sections.get(1).vDesign();
        double dB2 = drift(r2, 2);
        check(dB2 < 0.05,
            "b2 the resolved conflict STAYS resolved",
            String.format("B drift %.5f (was 0.357, snapped back)", dB2));
        TrackCompiler.Result r3 = rebuild(c2);
        check(Double.doubleToLongBits(vAfter) == Double.doubleToLongBits(
                c2.pieces.get(1).sections.get(1).vDesign()),
            "b3 the anchor is a BITWISE fixed point", "");

        // ---- (c) Convert Real to Design on B sticks too ----
        Coaster c3 = load();
        rebuild(c3);
        check(c3.makeGraphsReal(1, 1, DT, 0.1, 0.003),
            "c1 make-real applies", "");
        TrackCompiler.Result r4 = rebuild(c3);
        double dB3 = drift(r4, 2);
        check(dB3 < 0.1,
            "c2 make-real's resolution survives the rebuild",
            String.format("B drift %.5f", dB3));

        // ---- (d) refreezing the SOURCE clears the whole chain ----
        Coaster c4 = load();
        rebuild(c4);
        check(c4.refreezeSpeed(1, 0, DT), "d1 source refreeze applies", "");
        TrackCompiler.Result r5 = rebuild(c4);
        check(drift(r5, 1) < 0.05 && drift(r5, 2) < 0.05,
            "d2 whole chain clean from the source",
            String.format("A %.5f B %.5f", drift(r5, 1), drift(r5, 2)));

        // ---- (e-g) D248: the true-stroke split reference ----
        trueRefBlock();

        // ---- (h) batch 37: the init-speed window's dry run ----
        initSpeedBlock();

        // ---- (i) v19.26: the clean-span rule (bit-exact real speed) ----
        cleanSpanBlock();

        // ---- (j) review round 2: one flow ----
        oneFlowBlock();

        // ---- (k) batch 38: the "~v" slight-conflict glyph ----
        slightGlyphBlock();

        // ---- (l) batch 38: the explicit real anchor (D256) ----
        realAnchorBlock();

        System.out.println();
        System.out.println(fail == 0
            ? "ALL CONFLICT GATES PASS (" + pass + ")"
            : fail + " FAILURES (" + pass + " passed)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
