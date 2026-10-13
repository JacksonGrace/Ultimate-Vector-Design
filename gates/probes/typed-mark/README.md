# typed-mark probes (v19.27, D256 — the TYPED MARK)

Two TOOLKIT-level probes (Platform.startup, NO Stage — the attic
ClipProbe pattern) that pin the JavaFX 26 facts uvd.TypedMark's wiring
in FvdDesigner.numericEditor rides on. They are NOT battery members
(they need the JavaFX module path); RE-RUN THEM ON ANY JavaFX UPGRADE —
a change in any line below would re-open the D247 phantom silently.

- FxOrderProbe.java — [1] the Spinner's own value→editor render runs
  BEFORE a listener registered on spinner.valueProperty() (wrapper
  listeners fire first); [2]/[2b] an equal setValue fires nothing;
  [3] an equal setText fires nothing; [4] commitValue parses the editor
  text; [4b] a same-value commit fires no VALUE event and leaves the
  text as typed; [5] the factory clamps AFTER the set; [6a] a paste of
  the IDENTICAL text over a selection fires NO text event (the one
  gesture the pre-mark exists for); [6b] delete + retype fires;
  [6c]/[6d] the formatter rejects without an event; [7] a converter
  swap re-renders before a converter listener; [8] an EQUAL setText
  resets the caret to 0; [9] increment commits the editor first;
  [10] commitValue('-') THROWS out of the default converter (why
  BlankSafeConverter try/catches base.fromString); [11] isUndoable is
  not a provenance signal.
- UndoableProbe.java — TextInputControl.isUndoable() cannot see an
  identical paste either ([a]); a Spinner render resets it ([h]).

Run (from the repo root, after `mvn compile`; M2 = ~/.m2/repository):

    javac -encoding UTF-8 -d gates/out --module-path "%M2%/org/openjfx/javafx-base/26/javafx-base-26-win.jar;%M2%/org/openjfx/javafx-graphics/26/javafx-graphics-26-win.jar;%M2%/org/openjfx/javafx-controls/26/javafx-controls-26-win.jar" --add-modules javafx.controls gates/probes/typed-mark/FxOrderProbe.java gates/probes/typed-mark/UndoableProbe.java
    java --enable-native-access=javafx.graphics --module-path "<the same three jars>" --add-modules javafx.controls -cp gates/out FxOrderProbe
    java --enable-native-access=javafx.graphics --module-path "<the same three jars>" --add-modules javafx.controls -cp gates/out UndoableProbe

Expected lines (2026-10-12, JavaFX 26): see docs/TESTING.md #173 —
[1] TEXT then VALUE, [2]/[2b]/[3]/[6a]/[6c]/[6d]/[4b] empty, [6b] two
TEXT events, [5] value=90.0, [9] value=24.0, [7] TEXT then CONV,
[10] THREW NumberFormatException, [11] false / [11b] true / [11c] false.
