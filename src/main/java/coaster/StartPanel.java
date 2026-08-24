package coaster;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.List;
import java.util.function.DoubleConsumer;

/** THE START SCREEN (v19.1, D226; reworked v19.2, D231; polish round
 *  D232 — user spec): a JavaFX overlay the GraphEditor hosts OVER its
 *  canvas while the wizard's target piece/coaster is the selection.
 *  ONE form, always whole: Placement | Speed (& friction + name for a
 *  new coaster) | Section — whose header holds the two SECTION TYPE
 *  tiles ("Force/Geometric" first — SELECTED BY DEFAULT since D232 —
 *  then "Straight / Curve"; no descriptions); the picked type's
 *  options sit beneath in a compact TWO-COLUMN grid (Type+Yaw/pitch
 *  axis, Domain+Roll axis, Rate units+Roll behavior).
 *
 *  Every control applies LIVE and every change is ONE document undo
 *  step (FvdDesigner.wizard*): the target exists — visible, a lane
 *  section from the default pick — from the moment the wizard opened,
 *  so Ctrl+Z walks the settings back one by one and finally removes
 *  the piece/coaster. The panel is a VIEW of the document:
 *  syncFromModel() re-reads the target after undo/redo (muted — no
 *  re-apply). Selecting another piece/coaster hides it (the normal
 *  graphs show); returning shows it as it was. */
final class StartPanel extends StackPane {

    enum Mode { NEW_COASTER, FLOATING }

    private static final Color BG = Color.rgb(20, 22, 28);
    private static final String TILE =
        "-fx-background-color: rgb(30,34,44); -fx-background-radius: 8;"
        + " -fx-border-color: rgb(70,78,98); -fx-border-radius: 8;"
        + " -fx-border-width: 1;";
    private static final String TILE_HOT =
        "-fx-background-color: rgb(42,48,62); -fx-background-radius: 8;"
        + " -fx-border-color: rgb(190,200,225); -fx-border-radius: 8;"
        + " -fx-border-width: 1.5;";
    private static final String TILE_ON =
        "-fx-background-color: rgb(50,78,120); -fx-background-radius: 8;"
        + " -fx-border-color: rgb(120,170,240); -fx-border-radius: 8;"
        + " -fx-border-width: 2;";
    private static final String GROUP =
        "-fx-background-color: rgb(27,30,39); -fx-background-radius: 6;"
        + " -fx-border-color: rgb(55,61,78); -fx-border-radius: 6;";

    /** The panel's minimum comfortable height — the GraphEditor pins
     *  its own minimum to this while the panel shows (D231: below it
     *  the centered form starts riding over the graph's own chrome;
     *  compacted in D232 with the two-column options + bare tiles). */
    static final double MIN_HEIGHT = 240;

    private final FvdDesigner app;
    private final Mode mode;
    private final int coasterIdx, pieceIdx;

    // ---- controls (kept for syncFromModel) ----
    private VBox tileSc, tileLane;
    private VBox typeOptions;
    private RadioButton mForce, mGeo, dTime, dDist, uSec, uMet,
        yLocal, yGlobal, rLocal, rGlobal, aRate, aAbs;
    private Spinner<Double> sx, sy, sz, sYaw, sPitch, sRoll, sV, sMu, sK;
    private TextField nameField;
    private boolean muted;   // syncFromModel writes → no apply

    StartPanel(FvdDesigner app, FvdDesigner.WizardState ws) {
        this.app = app;
        this.mode = Mode.values()[ws.mode()];
        this.coasterIdx = ws.coasterIdx();
        this.pieceIdx = ws.pieceIdx();
        setBackground(new javafx.scene.layout.Background(
            new javafx.scene.layout.BackgroundFill(BG, null, null)));
        setPadding(new Insets(10));
        setMinHeight(MIN_HEIGHT);
        addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED,
            e -> requestFocus());
        build();
        syncFromModel();
    }

    /** Does this panel belong to that wizard record? */
    boolean targets(FvdDesigner.WizardState ws) {
        return ws != null && ws.mode() == mode.ordinal()
            && ws.coasterIdx() == coasterIdx && ws.pieceIdx() == pieceIdx;
    }

    private TrackPiece piece() { return app.wizardPiece(); }
    private Coaster coasterRef() { return app.wizardCoaster(); }

    // -----------------------------------------------------------------
    // build
    // -----------------------------------------------------------------

    private void build() {
        // ---- Placement ----
        VBox placeCard = group("Placement");
        sx = spin(0, -1e6, 1e6, 1, true, v -> applyPlace());
        sy = spin(0, -1e6, 1e6, 1, true, v -> applyPlace());
        sz = spin(0, -1e6, 1e6, 1, true, v -> applyPlace());
        sYaw = spin(0, -360, 360, 5, true, v -> applyPlace());
        sPitch = spin(0, -90, 90, 1, true, v -> applyPlace());
        sRoll = spin(0, -360, 360, 1, true, v -> applyPlace());
        placeCard.getChildren().addAll(
            row("Position x / y / z (m)", sx, sy, sz),
            row("Yaw / pitch / roll (°)", sYaw, sPitch, sRoll));

        // ---- Speed (& friction, name) ----
        boolean newC = mode == Mode.NEW_COASTER;
        VBox dynCard = group(newC ? "Coaster" : "Speed");
        if (newC) {
            nameField = new TextField();
            nameField.setPrefColumnCount(12);
            nameField.setPromptText("Coaster name");
            nameField.focusedProperty().addListener((o, was, is) -> {
                if (!is && !muted) app.wizardSetName(nameField.getText());
            });
            nameField.setOnAction(e -> {
                if (!muted) app.wizardSetName(nameField.getText());
            });
            dynCard.getChildren().add(row("Name", nameField));
        }
        sV = spin(FvdDesigner.NEW_START_SPEED, 1, 100, 1, false,
            v -> { if (!muted) app.wizardSetSpeed(v); });
        dynCard.getChildren().add(row("Starting speed (m/s)", sV));
        if (newC) {
            sMu = spin(FvdDesigner.FRIC_ROLL_DEFAULT, 0, 0.2, 0.001, false,
                v -> { if (!muted) app.wizardSetFriction(v, sK.getValue()); });
            ((SpinnerValueFactory.DoubleSpinnerValueFactory) sMu.getValueFactory())
                .setConverter(FvdDesigner.fixedDecimalConverter(3));
            FvdDesigner.wrapBlankConverter(sMu);
            sK = spin(FvdDesigner.FRIC_AIR_DEFAULT, 0, 0.02, 0.00001, false,
                v -> { if (!muted) app.wizardSetFriction(sMu.getValue(), v); });
            ((SpinnerValueFactory.DoubleSpinnerValueFactory) sK.getValueFactory())
                .setConverter(FvdDesigner.fixedDecimalConverter(5));
            FvdDesigner.wrapBlankConverter(sK);
            sK.setPrefWidth(92);
            dynCard.getChildren().addAll(
                row("Rolling friction (m/m)", sMu),
                row("Air resistance (m⁻¹)", sK));
            Label fh = new Label("Friction applies to the whole coaster.");
            fh.setTextFill(Color.rgb(140, 148, 170));
            fh.setStyle("-fx-font-size: 10px;");
            dynCard.getChildren().add(fh);
        }

        // ---- Section: TYPE tiles + the type's options (D232: no
        // descriptions, Force/Geometric FIRST + the default pick) ----
        VBox secCard = group("Section Type");
        tileLane = tile("Force/Geometric", () -> pick(false));
        tileSc = tile("Straight / Curve", () -> pick(true));
        HBox tiles = new HBox(10, tileLane, tileSc);
        tiles.setAlignment(Pos.CENTER_LEFT);
        typeOptions = new VBox(6);
        secCard.getChildren().addAll(tiles, typeOptions);
        buildLaneOptions();

        // ---- footer ----
        Button cancel = new Button("Cancel");
        cancel.setFocusTraversable(false);
        cancel.setStyle("-fx-font-size: 11px;");
        cancel.setOnAction(e -> app.wizardCancel());
        Button done = new Button(newC ? "Create Coaster  ✓" : "Create Track Piece  ✓");
        done.setDefaultButton(true);
        done.setFocusTraversable(false);
        done.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;"
            + " -fx-background-color: rgb(78,140,220); -fx-text-fill: white;"
            + " -fx-background-radius: 6;");
        done.setOnAction(e -> confirm());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox foot = new HBox(10, cancel, gap, done);
        foot.setAlignment(Pos.CENTER_LEFT);

        // ORDER (D231): Placement, Speed & friction, Section (far right)
        HBox cards = new HBox(12, placeCard, dynCard, secCard);
        cards.setAlignment(Pos.TOP_LEFT);
        Label title = new Label(newC ? "New Coaster" : "New Track Piece");
        title.setTextFill(Color.WHITE);
        title.setFont(Font.font("System", FontWeight.BOLD, 14));
        VBox all = new VBox(8, title, cards, foot);
        all.setAlignment(Pos.TOP_LEFT);
        all.setMaxWidth(Region.USE_PREF_SIZE);
        StackPane.setAlignment(all, Pos.CENTER);
        getChildren().setAll(all);
    }

    /** The lane family's option rows (shown under the tiles once
     *  Geometric/Force is picked; a Straight/Curve pick shows a note). */
    private void buildLaneOptions() {
        ToggleGroup tg = new ToggleGroup();
        mForce = radio("Force", tg, true);
        mGeo = radio("Geometric", tg, false);
        ToggleGroup dg = new ToggleGroup();
        dTime = radio("Time", dg, true);
        dDist = radio("Distance", dg, false);
        ToggleGroup ug = new ToggleGroup();
        uSec = radio("°/s", ug, true);
        uMet = radio("°/m", ug, false);
        ToggleGroup yg = new ToggleGroup();
        yLocal = radio("Local", yg, true);
        yGlobal = radio("Global (heading/elevation)", yg, false);
        ToggleGroup rg = new ToggleGroup();
        rLocal = radio("Local", rg, true);
        rGlobal = radio("Global (bank vs horizon)", rg, false);
        ToggleGroup ag = new ToggleGroup();
        aRate = radio("Roll rate", ag, true);
        aAbs = radio("Absolute bank (°)", ag, false);
        for (ToggleGroup g : new ToggleGroup[]{ tg, dg, ug, yg, rg, ag }) {
            g.selectedToggleProperty().addListener((o, a, b) -> {
                if (b != null && !muted) applyLaneOptions();
            });
        }
    }

    private void syncGray() {
        boolean g = mGeo.isSelected();
        yLocal.setDisable(!g);
        yGlobal.setDisable(!g);
        boolean rgl = rGlobal.isSelected();
        aAbs.setDisable(!rgl);
        if (!rgl && aAbs.isSelected()) {
            muted = true;
            aRate.setSelected(true);
            muted = false;
        }
    }

    private VBox tile(String head, Runnable onPick) {
        Label h = new Label(head);
        h.setTextFill(Color.WHITE);
        h.setFont(Font.font("System", FontWeight.BOLD, 12));
        VBox t = new VBox(h);
        t.setAlignment(Pos.CENTER_LEFT);
        t.setPadding(new Insets(7, 14, 7, 14));
        t.setStyle(TILE);
        t.setCursor(Cursor.HAND);
        t.setOnMouseEntered(e -> { if (!isOn(t)) t.setStyle(TILE_HOT); });
        t.setOnMouseExited(e -> { if (!isOn(t)) t.setStyle(TILE); });
        t.setOnMouseClicked(e -> onPick.run());
        return t;
    }

    private boolean isOn(VBox t) {
        FvdDesigner.WizardState ws = app.wizardState;
        if (ws == null || !ws.picked()) return false;
        return t == (ws.sc() ? tileSc : tileLane);
    }

    // -----------------------------------------------------------------
    // model glue
    // -----------------------------------------------------------------

    /** Dev-hook entry (FvdDesigner.devShots). */
    void devPick(boolean sc) { pick(sc); }

    private void pick(boolean sc) {
        FvdDesigner.WizardState ws = app.wizardState;
        if (ws != null && ws.picked() && ws.sc() == sc) return;   // same tile
        app.wizardPickType(sc, buildSection(sc));
        syncFromModel();
    }

    private Section buildSection(boolean sc) {
        double speed = sV.getValue();
        if (sc) return StraightCurveSection.straight(10, speed);
        boolean geo = mGeo.isSelected();
        boolean dist = dDist.isSelected();
        boolean rollG = rGlobal.isSelected();
        double len = dist ? Coaster.CONT_HOLD_DIST : FvdDesigner.NEW_START_LEN;
        List<List<BezierBaker.Node>> lanes = LaneSection.holdLanes(
            geo ? 0 : 1, 0, 0, len);
        LaneSection s = geo ? new GeoSection(lanes, speed)
                            : new ForceSection(lanes, speed);
        s.domain(dist ? Section.Domain.DISTANCE : Section.Domain.TIME);
        s.rollGlobal = rollG;
        s.yawPitchGlobal = geo && yGlobal.isSelected();
        s.ratePerMeter = uMet.isSelected();
        s.rollAbsolute = rollG && aAbs.isSelected();
        return s;
    }

    private void applyLaneOptions() {
        FvdDesigner.WizardState ws = app.wizardState;
        if (ws == null || !ws.picked() || ws.sc()) return;
        syncGray();
        app.wizardReplaceSection(buildSection(false));
    }

    private void applyPlace() {
        if (muted) return;
        app.wizardPlace(sx.getValue(), sy.getValue(), sz.getValue(),
            sYaw.getValue(), sPitch.getValue(), sRoll.getValue());
    }

    private void confirm() {
        // a type is ALWAYS picked since D232 (Force/Geometric default)
        app.wizardFinish();
    }

    /** Re-read the target from the document (after undo/redo, after a
     *  pick) — no apply. */
    void syncFromModel() {
        TrackPiece p = piece();
        Coaster c = coasterRef();
        FvdDesigner.WizardState ws = app.wizardState;
        if (p == null || c == null || ws == null) return;
        muted = true;
        try {
            sx.getValueFactory().setValue(p.x);
            sy.getValueFactory().setValue(p.y);
            sz.getValueFactory().setValue(p.z);
            sYaw.getValueFactory().setValue(-p.yawDeg + 0.0);   // reported (D186; no "-0")
            sPitch.getValueFactory().setValue(p.designPitchDeg);
            sRoll.getValueFactory().setValue(p.designRollDeg);
            if (!p.sections.isEmpty()) {
                sV.getValueFactory().setValue(p.sections.get(0).vDesign());
            }
            if (nameField != null && !nameField.isFocused()) {
                nameField.setText(c.name);
            }
            if (sMu != null) {
                sMu.getValueFactory().setValue(c.fricRoll);
                sK.getValueFactory().setValue(c.fricAir);
            }
            // tiles + options (a type is always picked since D232)
            tileSc.setStyle(ws.sc() ? TILE_ON : TILE);
            tileLane.setStyle(!ws.sc() ? TILE_ON : TILE);
            typeOptions.getChildren().clear();
            if (ws.sc()) {
                Label note = new Label(
                    "A 10 m straight to begin with — edit it or append\n"
                    + "elements once you're in.");
                note.setTextFill(Color.rgb(170, 178, 200));
                note.setStyle("-fx-font-size: 11px;");
                typeOptions.getChildren().add(note);
            } else {
                Section s = p.sections.isEmpty() ? null : p.sections.get(0);
                if (s instanceof LaneSection ls) {
                    (ls instanceof GeoSection ? mGeo : mForce).setSelected(true);
                    (ls.domain() == Section.Domain.DISTANCE ? dDist : dTime).setSelected(true);
                    (ls.ratePerMeter ? uMet : uSec).setSelected(true);
                    (ls.yawPitchGlobal ? yGlobal : yLocal).setSelected(true);
                    (ls.rollGlobal ? rGlobal : rLocal).setSelected(true);
                    (ls.rollAbsolute ? aAbs : aRate).setSelected(true);
                }
                syncGray();
                // TWO-COLUMN grid (D232; label|radios as SEPARATE grid
                // cells since D233 — the first radio dot of every row
                // lines up per column, like the dialogs)
                javafx.scene.layout.GridPane og = new javafx.scene.layout.GridPane();
                og.setHgap(10);
                og.setVgap(5);
                og.addRow(0, optLbl("Type", 0), optPair(mForce, mGeo),
                    optLbl("Yaw/pitch axis", 14), optPair(yLocal, yGlobal));
                og.addRow(1, optLbl("Domain", 0), optPair(dTime, dDist),
                    optLbl("Roll axis", 14), optPair(rLocal, rGlobal));
                og.addRow(2, optLbl("Rate units", 0), optPair(uSec, uMet),
                    optLbl("Roll behavior", 14), optPair(aRate, aAbs));
                typeOptions.getChildren().add(og);
            }
        } finally {
            muted = false;
        }
    }

    // -----------------------------------------------------------------
    // widgets
    // -----------------------------------------------------------------

    private static VBox group(String head) {
        Label h = new Label(head);
        h.setTextFill(Color.rgb(200, 208, 230));
        h.setFont(Font.font("System", FontWeight.BOLD, 12));
        VBox g = new VBox(6, h);
        g.setPadding(new Insets(8, 12, 10, 12));
        g.setStyle(GROUP);
        return g;
    }

    private static HBox row(String label, javafx.scene.Node... controls) {
        Label l = new Label(label);
        l.setTextFill(Color.rgb(200, 205, 218));
        l.setStyle("-fx-font-size: 11px;");
        l.setMinWidth(118);
        l.setMinHeight(Region.USE_PREF_SIZE);
        HBox r = new HBox(6, l);
        r.getChildren().addAll(controls);
        r.setAlignment(Pos.CENTER_LEFT);
        return r;
    }

    private static RadioButton radio(String text, ToggleGroup g, boolean sel) {
        RadioButton rb = new RadioButton(text);
        rb.setToggleGroup(g);
        rb.setSelected(sel);
        rb.setTextFill(Color.WHITE);
        rb.setStyle("-fx-font-size: 11px; -fx-text-fill: white;");
        rb.setFocusTraversable(false);
        rb.setMinWidth(Region.USE_PREF_SIZE);   // never ellipsize (D232)
        return rb;
    }

    /** The options grid's label cell (D233: labels and radio pairs
     *  are SEPARATE grid cells, so every row's first radio dot lines
     *  up per column — the dialogs' look). */
    private static Label optLbl(String text, double leftPad) {
        Label l = new Label(text);
        l.setTextFill(Color.rgb(200, 205, 218));
        l.setStyle("-fx-font-size: 11px;");
        l.setMinWidth(Region.USE_PREF_SIZE);
        l.setPadding(new Insets(0, 0, 0, leftPad));
        return l;
    }

    private static HBox optPair(RadioButton a, RadioButton b) {
        HBox h = new HBox(6, a, b);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    private static Spinner<Double> spin(double init, double lo, double hi,
                                        double step, boolean signed,
                                        DoubleConsumer sink) {
        Spinner<Double> sp = new Spinner<>(
            new SpinnerValueFactory.DoubleSpinnerValueFactory(lo, hi, init, step));
        sp.setPrefWidth(74);
        FvdDesigner.typable(sp, signed);
        sp.valueProperty().addListener((o, a, b) -> {
            if (b != null) sink.accept(b);
        });
        Tooltip.install(sp, new Tooltip("Applies live · Ctrl+Z undoes"));
        return sp;
    }
}
