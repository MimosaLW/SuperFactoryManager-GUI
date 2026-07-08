package ca.teamdman.sfmgui.client;

import ca.teamdman.sfmgui.SFMGui;
import ca.teamdman.sfmgui.client.codegen.GraphToSfml;
import ca.teamdman.sfmgui.client.codegen.SfmlToGraph;
import ca.teamdman.sfmgui.client.model.Condition;
import ca.teamdman.sfmgui.client.model.EditorGraph;
import ca.teamdman.sfmgui.client.model.EditorNode;
import ca.teamdman.sfmgui.client.model.ForgetStatementNode;
import ca.teamdman.sfmgui.client.model.IOStatementNode;
import ca.teamdman.sfmgui.client.model.IfStatementNode;
import ca.teamdman.sfmgui.client.model.LabelAccessData;
import ca.teamdman.sfmgui.client.model.RawStatementNode;
import ca.teamdman.sfmgui.client.model.ResourceLimitData;
import ca.teamdman.sfmgui.client.model.StatementContainer;
import ca.teamdman.sfmgui.client.model.StatementNode;
import ca.teamdman.sfmgui.client.model.TriggerNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Classic Steve's Factory Manager style node editor (ModJam3 look), rebuilt with
 * a content-driven layout: each node's height grows to fit its open menu, and all
 * content is positioned relative to the node so dragging keeps it attached and
 * nothing overflows the frame.
 * <p>
 * Rendering/interaction only; the {@link EditorGraph} model, SFML codegen and
 * reverse-parse are unchanged.
 */
public class NodeEditorScreen extends Screen {
    public static final Loc TITLE = new Loc("gui.sfmgui.node_editor.title", "Visual Program Editor");
    public static final Loc EXIT_CONFIRM_TITLE = new Loc("gui.sfmgui.node_editor.exit_confirm.title", "Exit without saving?");
    public static final Loc EXIT_CONFIRM_MESSAGE = new Loc("gui.sfmgui.node_editor.exit_confirm.message", "You have unsaved changes.");
    public static final Loc IMPORT_LOSSY = new Loc("gui.sfmgui.node_editor.import_lossy", "Warning: cannot fully show this program; saving may change it.");
    public static final Loc IMPORT_FAILED = new Loc("gui.sfmgui.node_editor.import_failed", "Warning: could not fully parse the existing program.");
    public static final Loc HINT_EMPTY = new Loc("gui.sfmgui.node_editor.hint_empty", "Use the left toolbar to add a trigger.");

    private enum ToolKind {
        TIMER(0, "gui.sfmgui.tool.timer"),
        PULSE(8, "gui.sfmgui.tool.pulse"),
        INPUT(1, "gui.sfmgui.tool.input"),
        OUTPUT(2, "gui.sfmgui.tool.output"),
        IF(3, "gui.sfmgui.tool.if"),
        FORGET(17, "gui.sfmgui.tool.forget"),
        // Power blueprint: a custom procedurally-drawn lightning icon (icon = -1 marks it).
        POWER_BLUEPRINT(-1, "gui.sfmgui.tool.power_blueprint"),
        // Item blueprint: a custom procedurally-drawn box/arrow icon (icon = -2 marks it).
        ITEM_BLUEPRINT(-2, "gui.sfmgui.tool.item_blueprint");

        final int icon;
        final String nameKey;

        ToolKind(int icon, String nameKey) {
            this.icon = icon;
            this.nameKey = nameKey;
        }
    }

    // ----- geometry -----
    private static final int NODE_W = 124;          // expanded/collapsed node width (matches classic large frame)
    private static final int TITLE_H = 16;          // title bar height
    private static final int STRIP_H = 12;          // menu strip height
    private static final int PAD = 4;               // content padding
    private static final int ROW = 20;              // a content row (label+field or a control)
    private static final int TOOLBAR_W = 24;        // left icon toolbar width in graph space
    private static final int TOOL_STEP = 18;
    private static final int CONN_W = ClassicTextures.CONN_W;
    private static final int CONN_H = ClassicTextures.CONN_H;
    /** #1: grey-green overlay drawn on a connected endpoint nub. */
    private static final int NUB_CONNECTED_TINT = 0x9088BB88;
    /** #3/#5: leading icon size + gap for collapsed-body resource rows. */
    private static final int BODY_ICON = 10;
    /** #2: top padding below the title divider before collapsed-body content. */
    private static final int BODY_PAD_TOP = 6;

    // ----- persistence context -----
    private String initialProgram;
    private final Consumer<String> saveWriter;
    private final @Nullable Screen previousScreen;
    private final EditorGraph graph;
    private @Nullable String importWarning;

    /** True once the user makes any real change; gates saving so we never overwrite the disk with an unedited/failed parse. */
    private boolean dirty = false;
    /** True when the disk program is non-blank but could not be parsed into a graph (e.g. invalid SFML). */
    private boolean parseFailed = false;

    /** Undo/redo history of full-program SFML snapshots (with layout/camera comments). */
    private final java.util.Deque<String> undoStack = new java.util.ArrayDeque<>();
    private final java.util.Deque<String> redoStack = new java.util.ArrayDeque<>();
    private static final int UNDO_LIMIT = 100;
    /** Guards against re-entrant snapshotting while restoring a snapshot. */
    private boolean restoring = false;

    // ----- transient bottom-left toast (undo/redo/etc. feedback) -----
    private @Nullable Component toastText = null;
    private long toastStart = 0L;
    private static final long TOAST_MS = 1600L;
    private static final long TOAST_FADE_MS = 400L;

    /** Flash a short message in the bottom-left corner that fades out on its own. */
    private void showToast(Component text) {
        toastText = text;
        toastStart = System.currentTimeMillis();
    }

    // ----- canvas transform -----
    private float scale = 1f;
    private int originX = 0, originY = 0;
    private float zoom = 1f, panX = 0, panY = 0;
    /** Zoom limits (raised max so the camera can zoom in much closer). */
    private static final float ZOOM_MIN = 0.3f, ZOOM_MAX = 6.0f;

    // interaction state
    private @Nullable EditorNode draggingNode = null;
    private double dragDX, dragDY;
    private boolean panning = false;
    private double panStartMX, panStartMY;
    private float panStartX, panStartY;
    private @Nullable EditorNode connectSource = null;
    private boolean showPreview = false;
    private int previewScroll = 0;
    /**
     * #1: the node currently showing its delete-confirm X. Right-clicking a node
     * arms it (shows the X); left-clicking that X performs the delete. Any other
     * click clears it. Only one node is armed at a time.
     */
    private @Nullable EditorNode pendingDelete = null;

    /**
     * #5: a wire segment armed for deletion by right-clicking anywhere along it. The X
     * button is drawn right where the user clicked ({@link #wireDeleteGX}/{@link #wireDeleteGY},
     * graph space); left-clicking it breaks the chain: {@code child} and every sibling
     * after it in the same container are moved to {@link #detached}. {@code parent} is the
     * wire's upstream node (container head or previous sibling).
     */
    private @Nullable WireRef pendingWireDelete = null;
    /** #5: graph-space position of the armed delete-X (the point the user right-clicked). */
    private float wireDeleteGX, wireDeleteGY;

    /** A single {@code parent -> child} wire within a container's chain. */
    private record WireRef(EditorNode parent, StatementNode child, StatementContainer container) {
    }

    /** #2: whether the top-bar labels dropdown is expanded. */
    private boolean labelsDropdownOpen = false;
    /** #2: screen-space rect of the "Labels ▾" toggle button, updated each render. */
    private int labelsBtnX, labelsBtnY, labelsBtnW, labelsBtnH;
    /** #2: the label list shown in the open dropdown (recomputed when drawn). */
    private final List<String> labelsDropdownItems = new ArrayList<>();

    /** #8: IO nodes whose Sides menu has the full 6-face grid expanded. */
    private final java.util.Set<EditorNode> sidesExpanded = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** #7: the label to highlight, and until when (ms). */
    private @Nullable String highlightLabel = null;
    private long highlightUntil = 0L;

    private final List<StatementNode> detached = new ArrayList<>();

    // built each frame/rebuild: interactive elements in graph space
    private final List<CanvasField> canvasFields = new ArrayList<>();
    private final List<MenuControl> menuControls = new ArrayList<>();
    private final List<int[]> labelPos = new ArrayList<>();
    private final List<String> labelText = new ArrayList<>();
    private final List<EditorNode> labelOwner = new ArrayList<>();
    /** #3: in-menu resource icon preview rows. */
    private final List<IconRow> iconRows = new ArrayList<>();
    /** The node whose menu is currently being built; owns any controls/labels added. */
    private @Nullable EditorNode currentOwner = null;

    /** The field currently receiving keyboard input, or null. */
    private @Nullable CanvasField activeField = null;
    /**
     * A real (but off-screen, never painted) EditBox that receives keyboard + OS IME
     * input for the active in-canvas field. Hand-rolled char handling can't compose
     * CJK via the IME; delegating to a focused vanilla EditBox makes Chinese input
     * work. It stays visible+active (required by EditBox#canConsumeInput) and is only
     * moved off-screen so it isn't seen. Value/caret are mirrored to {@link #activeField}.
     */
    private @Nullable EditBox imeProxy = null;
    /** Screen-space program-name editor in the top bar (#3). */
    private @Nullable EditBox nameBox = null;
    /** Wall-clock time (ms) the caret blink cycle started; reset on edit so the caret stays lit right after typing. */
    private long caretBlinkStart = System.currentTimeMillis();

    /**
     * A custom in-canvas text field. Rendered inside the scaled pose so its text
     * scales with zoom (unlike a vanilla EditBox which is screen-space). Holds its
     * own editable value and pushes changes through {@code responder}.
     */
    private static final class CanvasField {
        int gx, gy, gw;
        /** The minimum/base width; the field grows past this to fit its content. */
        final int baseGw;
        final int maxLen;
        String value;
        final Consumer<String> responder;
        int caret;
        /** Selection anchor; -1 means no active selection. Selection spans [min(anchor,caret), max(anchor,caret)). */
        int selAnchor = -1;
        /**
         * When true this field never wraps (numeric / short fields): it stays a
         * single 12px row and just truncates overflow. Text fields (labels,
         * resource, except, slots, raw, note) wrap to multiple lines instead.
         */
        boolean singleLine = false;
        /** The node this field belongs to (for z-ordered draw/hit-testing). */
        EditorNode owner;

        CanvasField(int gx, int gy, int gw, String initial, int maxLen, Consumer<String> responder) {
            this.gx = gx;
            this.gy = gy;
            this.gw = gw;
            this.baseGw = gw;
            this.maxLen = maxLen;
            this.value = initial == null ? "" : initial;
            this.responder = responder;
            this.caret = this.value.length();
        }

        void set(String v) {
            if (v.length() > maxLen) {
                v = v.substring(0, maxLen);
            }
            value = v;
            // NOTE: intentionally does NOT touch caret — callers manage the caret
            // explicitly. (Auto-clamping here caused an off-by-one on backspace.)
            if (selAnchor > value.length()) {
                selAnchor = value.length();
            }
            responder.accept(value);
        }

        boolean hasSelection() {
            return selAnchor >= 0 && selAnchor != caret;
        }

        int selMin() {
            return Math.min(selAnchor, caret);
        }

        int selMax() {
            return Math.max(selAnchor, caret);
        }

        void clearSelection() {
            selAnchor = -1;
        }
    }

    /**
     * One collapsed-body line: coloured {@code text} plus an optional leading icon
     * (#3/#5). The icon is drawn once at the first wrapped row; the text still wraps
     * fully across as many rows as needed (never truncated).
     */
    private static final class BodyLine {
        final Component text;
        final @Nullable ItemStack icon;          // item icon, or null
        final @Nullable ResourceLocation sprite; // fluid sprite, or null
        final int spriteTint;

        BodyLine(Component text, @Nullable ItemStack icon, @Nullable ResourceLocation sprite, int spriteTint) {
            this.text = text;
            this.icon = icon;
            this.sprite = sprite;
            this.spriteTint = spriteTint;
        }

        static BodyLine text(Component text) {
            return new BodyLine(text, null, null, 0);
        }

        boolean hasIcon() {
            return icon != null || sprite != null;
        }
    }

    /** #3: an in-menu row that previews the icons of a resource field's value. */
    private static final class IconRow {
        final int gx, gy, gw;
        final java.util.function.Supplier<String> resources;
        EditorNode owner;

        IconRow(int gx, int gy, int gw, java.util.function.Supplier<String> resources) {
            this.gx = gx;
            this.gy = gy;
            this.gw = gw;
            this.resources = resources;
        }
    }

    private static final class MenuControl {
        final int gx, gy, gw, gh;
        final java.util.function.Supplier<String> label;
        final Runnable onClick;
        /** The node this control belongs to (for z-ordered draw/hit-testing). */
        EditorNode owner;

        MenuControl(int gx, int gy, int gw, int gh, java.util.function.Supplier<String> label, Runnable onClick) {
            this.gx = gx;
            this.gy = gy;
            this.gw = gw;
            this.gh = gh;
            this.label = label;
            this.onClick = onClick;
        }
    }

    public NodeEditorScreen(
            String initialProgram,
            Consumer<String> saveWriter,
            @Nullable Screen previousScreen
    ) {
        super(TITLE.getComponent());
        this.initialProgram = initialProgram == null ? "" : initialProgram;
        this.saveWriter = saveWriter;
        this.previousScreen = previousScreen;
        this.graph = SfmlToGraph.parse(this.initialProgram);
        this.importWarning = computeImportWarning(this.initialProgram);
        LayoutMemory.apply(this.initialProgram, this.graph);
        // #2: de-overlap runs in init() (needs this.font for real node heights), not here.
        // Detect a disk we must NOT silently overwrite: a non-blank program that either
        // fails to compile, yields no triggers, OR does not round-trip faithfully (the
        // regenerated SFML would differ semantically). In any of these states we keep the
        // original program verbatim unless the user actually edits something — this is the
        // #1 safeguard against label/behaviour loss on a plain open+save.
        if (!this.initialProgram.isBlank()) {
            boolean compiles = canonical(this.initialProgram) != null;
            if (!compiles || this.graph.triggers.isEmpty() || !roundTripsFaithfully(this.initialProgram)) {
                this.parseFailed = true;
            }
        }
        // default-expand the newest input/output style nodes on open? keep as saved.
    }

    /**
     * #1: whether parsing then regenerating {@code sfml} yields a semantically identical
     * program (same canonical AST). When it does not, the visual round trip is lossy and
     * we must not overwrite the disk with the regenerated form unless the user edits.
     */
    private static boolean roundTripsFaithfully(String sfml) {
        String before = canonical(sfml);
        if (before == null) {
            return false;
        }
        try {
            String after = canonical(GraphToSfml.generate(SfmlToGraph.parse(sfml)));
            return after != null && before.equals(after);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Gap (graph px) kept between de-overlapped nodes. */
    private static final int OVERLAP_GAP = 6;

    /**
     * #2: push apart nodes whose frames physically intersect, so their text never
     * shows through one another. Uses real node heights (this.font must be ready).
     * Runs unconditionally: intersecting nodes are never intentional, while nodes the
     * user spaced out don't intersect and are left where they are.
     */
    private void resolveOverlaps() {
        List<EditorNode> placed = new ArrayList<>();
        for (EditorNode n : allVisibleNodes()) {
            int guard = 0;
            EditorNode hit;
            // Re-scan after every move: pushing n down may make it hit a different node.
            while (guard++ < 500 && (hit = firstOverlap(n, placed)) != null) {
                NodeLayout ol = layout(hit);
                if (guard < 400) {
                    // prefer pushing straight down (keeps the vertical chain readable)
                    n.y = hit.y + ol.height + OVERLAP_GAP;
                } else {
                    // fallback if we somehow can't resolve vertically: nudge sideways
                    n.x += NODE_W + OVERLAP_GAP;
                }
            }
            placed.add(n);
        }
    }

    /** First already-placed node whose frame AABB intersects n's, or null. */
    private @Nullable EditorNode firstOverlap(EditorNode n, List<EditorNode> placed) {
        NodeLayout nl = layout(n);
        int nx = n.x, ny = n.y, nw = nl.width, nh = nl.height;
        for (EditorNode o : placed) {
            NodeLayout ol = layout(o);
            if (nx < o.x + ol.width && nx + nw > o.x
                    && ny < o.y + ol.height && ny + nh > o.y) {
                return o;
            }
        }
        return null;
    }

    // ===== transforms =====
    private float unit() {
        return scale * zoom;
    }

    private int gx2sx(float gx) {
        return Math.round(originX + (gx + panX) * unit());
    }

    private int gy2sy(float gy) {
        return Math.round(originY + (gy + panY) * unit());
    }

    private float sx2gx(double sx) {
        return (float) ((sx - originX) / unit() - panX);
    }

    private float sy2gy(double sy) {
        return (float) ((sy - originY) / unit() - panY);
    }

    private static final int TOP_BAR_H = 24; // reserved screen-space top bar for action buttons

    @Override
    protected void init() {
        super.init();
        // Infinite canvas: nodes live in graph space; the view is pure pan+zoom.
        // Origin sits just below the fixed top bar; default 1:1 scale.
        scale = 1f;
        originX = 0;
        originY = TOP_BAR_H;
        if (!initialised) {
            float[] cam = LayoutMemory.readCamera(this.initialProgram);
            if (cam != null) {
                panX = cam[0];
                panY = cam[1];
                zoom = Mth.clamp(cam[2], ZOOM_MIN, ZOOM_MAX);
            } else {
                // start slightly padded so nodes near (0,0) aren't under the toolbar
                panX = TOOLBAR_W + 8;
                panY = 8;
            }
            // #10/#1: only spread nodes apart for a freshly pasted program (no saved
            // layout) — that's the paste "don't stack them" case. Programs with a saved
            // layout keep their positions; any overlaps there are handled visually by
            // the node layer's painter-order occlusion (later node fully covers earlier).
            if (!LayoutMemory.hasLayout(this.initialProgram)) {
                resolveOverlaps();
            }
            initialised = true;
        }
        // #3: program-name editor in the top bar. Positioned after the command
        // count label, left of the action buttons.
        int nameX = 150;
        int nameW = 120;
        nameBox = new EditBox(this.font, nameX, 4, nameW, 16, Component.translatable("gui.sfmgui.node_editor.name_label"));
        nameBox.setMaxLength(64);
        nameBox.setValue(graph.name == null ? "" : graph.name);
        nameBox.setResponder(v -> {
            if (!v.equals(graph.name)) {
                graph.name = v;
                markDirty();
            }
        });
        this.addRenderableWidget(nameBox);

        // Off-screen IME proxy: receives keyboard + OS IME for the active canvas field
        // so CJK composition works. Kept visible+active (required to consume input),
        // positioned far off-screen and never drawn. Its value/caret mirror to activeField.
        imeProxy = new EditBox(this.font, -10000, -10000, 100, 16, Component.empty());
        imeProxy.setMaxLength(256);
        imeProxy.setBordered(false);
        imeProxy.setResponder(v -> {
            if (activeField != null && !v.equals(activeField.value)) {
                activeField.set(v);
                markDirty();
            }
        });
        this.addRenderableWidget(imeProxy);

        rebuild();
    }

    private boolean initialised = false;

    // ================= LAYOUT =================
    // A per-node layout: strip Y offsets, open content height, total height.
    private static final class NodeLayout {
        int width;
        int height;                          // total node height
        final List<Integer> stripY = new ArrayList<>(); // y (relative to node top) of each strip
        /** menu index -> y of its open content start (relative to node top). #5 */
        final Map<Integer, Integer> openContentY = new java.util.LinkedHashMap<>();
    }

    private NodeLayout layout(EditorNode node) {
        NodeLayout L = layoutCache.get(node);
        if (L != null) {
            return L;
        }
        L = new NodeLayout();
        L.width = NODE_W;
        if (node.collapsed) {
            // collapsed: title + fully-wrapped summary lines; node height fits them all.
            int wrapped = 0;
            for (BodyLine line : collapsedLines(node)) {
                wrapped += wrapToWidth(line.text, bodyTextWidth(L.width, line.hasIcon())).size();
            }
            wrapped = Math.max(1, wrapped);
            // #2: BODY_PAD_TOP above the content so it clears the title divider; the
            // trailing margin keeps the last row off the bottom border.
            L.height = TITLE_H + BODY_PAD_TOP + wrapped * 10 + 4;
            layoutCache.put(node, L);
            return L;
        }
        int y = TITLE_H;
        List<String> menus = menusFor(node);
        L.openContentY.clear();
        for (int i = 0; i < menus.size(); i++) {
            L.stripY.add(y);
            y += STRIP_H;
            // #5: any number of menus may be open at once; each open menu's
            // content is stacked directly under its own strip.
            if (node.isMenuOpen(i)) {
                L.openContentY.put(i, y);
                y += menuContentHeight(node, menus.get(i));
            }
        }
        L.height = Math.max(y + 6, TITLE_H + 10);
        layoutCache.put(node, L);
        return L;
    }

    /** Cache cleared whenever selection/open-menu/graph changes (via rebuild()). */
    private final Map<EditorNode, NodeLayout> layoutCache = new java.util.IdentityHashMap<>();

    /**
     * Height (px) of a menu's open content area. For menus with wrapping text fields
     * this mirrors the exact accumulator layout in {@link #buildMenu} via the shared
     * {@link #textFieldHeight} helper, so the reserved height always matches what is
     * actually drawn (no overlap, no gaps).
     */
    private int menuContentHeight(EditorNode node, String menu) {
        int cw = NODE_W - 2 * PAD - 2;
        return switch (menu) {
            case "Interval" -> PAD + 86 + PAD;                     // fixed rows incl. power-transfer toggle
            case "Labels" -> {
                if (node instanceof IOStatementNode io) {
                    // field(wrap) + round_robin + each + [empty_slots]
                    int h = 9 + textFieldHeight(io.labelAccess.labels, cw) + 4 + 16 + 16;
                    if (io.kind == StatementNode.Kind.OUTPUT) h += 16;
                    yield PAD + h + PAD;
                }
                yield PAD + ROW + PAD;
            }
            case "Filter" -> {
                if (node instanceof IOStatementNode io) {
                    int h = 0;
                    for (var lim : io.limits.isEmpty() ? List.of(new ResourceLimitData()) : io.limits) {
                        h += filterBlockHeight(lim, cw);
                    }
                    yield PAD + h + 12 + PAD;   // + trailing "add limit" button
                }
                yield PAD + ROW + PAD;
            }
            case "Sides" -> {
                boolean expanded = sidesExpanded.contains(node);
                boolean eachSide = node instanceof IOStatementNode io && io.labelAccess.eachSide;
                if (eachSide) {
                    yield PAD + 16 + PAD;
                }
                if (expanded) {
                    yield PAD + 16 + 18 + 5 * 14 + 6 + PAD;
                }
                yield PAD + 16 + 18 + PAD;
            }
            case "Slots" -> {
                if (node instanceof IOStatementNode io) {
                    yield PAD + 9 + textFieldHeight(io.labelAccess.slots, cw) + PAD;
                }
                yield PAD + ROW + PAD;
            }
            case "Raw" -> {
                if (node instanceof RawStatementNode r) {
                    yield PAD + 9 + textFieldHeight(r.raw, cw) + PAD;
                }
                yield PAD + ROW + PAD;
            }
            case "Note" -> PAD + 9 + textFieldHeight(node.note, cw) + PAD;
            case "Except" -> {
                if (node instanceof IOStatementNode io) {
                    // field(wrap) + icon row + pick
                    yield PAD + 9 + textFieldHeight(io.except, cw) + 3 + ICON_ROW_H + 12 + PAD;
                }
                yield PAD + ROW + PAD;
            }
            case "Condition" -> {
                if (node instanceof IfStatementNode ifn && ifn.condition.kind == Condition.Kind.HAS) {
                    var c = ifn.condition;
                    // kind + quant + labels(wrap) + cmp/count + resource(wrap) + pick + else
                    int h = 16 + 18
                            + textFieldHeight(c.labels, cw) + 3
                            + 18
                            + textFieldHeight(c.resource, cw) + 3
                            + 17 + 16;
                    yield PAD + h + PAD;
                }
                yield PAD + 16 + ROW + 16 + PAD;
            }
            default -> PAD + ROW + PAD;
        };
    }

    /** Height of one Filter resource-limit block (matches the buildMenu accumulator). */
    private int filterBlockHeight(ResourceLimitData lim, int cw) {
        // quantity/retain row(24) + resource label(9) + resource field(wrap) + 3
        // + pick row(17) + icon row(ICON_ROW_H) + qty_each(16) + retain_each(16) + gap(4)
        return 24 + 9 + textFieldHeight(lim.resources, cw) + 3 + 17 + ICON_ROW_H + 16 + 16 + 4;
    }

    // ===== rendering =====
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        // A: keep the active field's caret in sync with the proxy before drawing.
        syncActiveFieldFromProxy();
        // Flat classic gray background (no fixed canvas / black borders).
        renderFullscreenBackground(g);

        // #6: clip all canvas drawing to below the fixed top bar so node text can't
        // bleed over it. #4: the left toolbar is drawn AFTER this scissor is disabled,
        // so its 6 buttons already sit on top of the canvas — no need to clip the whole
        // left column (that hid nodes behind an empty strip).
        g.enableScissor(0, TOP_BAR_H + 1, this.width, this.height);

        var pose = g.pose();
        pose.pushPose();
        pose.translate(originX + panX * unit(), originY + panY * unit(), 0);
        pose.scale(unit(), unit(), 1f);

        // #1: draw the whole node layer as pure 2D painter's order. GuiGraphics#renderItem
        // (used for body/menu icons) renders a 3D item model at z=150 and WRITES the depth
        // buffer, which makes a later node's frame (z=0, "farther") fail the LEQUAL depth
        // test and bleed through. Disabling depth test + depth writes for the node layer
        // makes every later node fully occlude the earlier ones (frame/text/icons alike).
        g.flush();
        com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();
        com.mojang.blaze3d.systems.RenderSystem.depthMask(false);

        renderWires(g);
        // Z-ordered draw: each node's frame is immediately followed by its own
        // menu controls/labels/fields, so an upper node fully covers a lower one.
        // Selected node last.
        for (EditorNode node : allVisibleNodes()) {
            if (node != graph.selected) {
                renderNode(g, node, mx, my);
                renderNodeControls(g, node, mx, my);
            }
        }
        if (graph.selected != null) {
            renderNode(g, graph.selected, mx, my);
            renderNodeControls(g, graph.selected, mx, my);
        }

        // #7: red outlines around nodes referencing the highlighted label
        renderLabelHighlights(g);

        // #5: armed wire-delete X on top of the node layer (still in graph space)
        renderWireDeleteOverlay(g);

        // restore depth state before drawing the rest of the UI
        g.flush();
        com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
        com.mojang.blaze3d.systems.RenderSystem.depthMask(true);

        pose.popPose();
        g.disableScissor();

        // left toolbar fixed to screen (not canvas)
        renderToolbar(g, mx, my);

        // pending wire
        if (connectSource != null) {
            int[] a = anchorScreen(connectSource, true);
            g.fill(Math.min(a[0], mx), a[1], Math.max(a[0], mx) + 1, a[1] + 1, 0xFF888888);
            g.fill(mx, Math.min(a[1], my), mx + 1, Math.max(a[1], my) + 1, 0xFF888888);
        }

        // ===== screen-space top bar (fixed, does not follow canvas) =====
        renderTopBar(g, mx, my);

        if (importWarning != null) {
            // Draw below the top bar so it's never obscured by the name box or buttons.
            int warnY = TOP_BAR_H + 2;
            g.fill(0, warnY, this.width, warnY + this.font.lineHeight + 2, 0xE0300000);
            g.drawString(this.font, this.font.plainSubstrByWidth(importWarning, this.width - 12), 6, warnY + 1, 0xFFFFD0D0, false);
        }

        if (graph.triggers.isEmpty() && detached.isEmpty()) {
            g.drawCenteredString(this.font, HINT_EMPTY.getComponent(), this.width / 2, this.height - 16, 0xFFAaAaAa);
        }

        if (showPreview) {
            renderPreview(g);
        }

        // #2: labels dropdown overlay (above canvas/preview, below tooltips)
        if (labelsDropdownOpen) {
            renderLabelsDropdown(g, mx, my);
        }

        // toolbar hover tooltip (drawn last so it sits above everything)
        int hoveredTool = hoverToolButton(mx, my);
        if (hoveredTool >= 0) {
            ToolKind tk = ToolKind.values()[hoveredTool];
            g.renderTooltip(this.font, Component.translatable(tk.nameKey), mx, my);
        }

        renderToast(g);

        super.render(g, mx, my, pt);
    }

    /** #3: bottom-left transient toast; alpha fades out over the last {@link #TOAST_FADE_MS}. */
    private void renderToast(GuiGraphics g) {
        if (toastText == null) {
            return;
        }
        long age = System.currentTimeMillis() - toastStart;
        if (age >= TOAST_MS) {
            toastText = null;
            return;
        }
        long remain = TOAST_MS - age;
        float a = remain >= TOAST_FADE_MS ? 1f : (remain / (float) TOAST_FADE_MS);
        int alpha = Mth.clamp((int) (a * 255f), 0, 255);
        int textW = this.font.width(toastText);
        int pad = 4;
        int boxX = 6;
        int boxY = this.height - 6 - (this.font.lineHeight + pad * 2);
        int boxW = textW + pad * 2;
        int boxH = this.font.lineHeight + pad * 2;
        g.fill(boxX, boxY, boxX + boxW, boxY + boxH, (alpha / 2 << 24) | 0x000000);
        g.drawString(this.font, toastText, boxX + pad, boxY + pad, (alpha << 24) | 0xFFFFFF, false);
    }

    // ===== #2 labels dropdown =====
    private static final int LABELS_DD_ROW_H = 12;
    private static final int LABELS_DD_MAX_ROWS = 16;

    /** Draw the expanded labels dropdown list under the toggle button. */
    private void renderLabelsDropdown(GuiGraphics g, int mx, int my) {
        labelsDropdownItems.clear();
        labelsDropdownItems.addAll(collectAllLabelsList());
        int x = labelsBtnX;
        int y = labelsBtnY + labelsBtnH + 1;
        int rows = Math.min(labelsDropdownItems.size(), LABELS_DD_MAX_ROWS);
        // width fits the longest label, clamped to a sane range
        int w = Math.max(labelsBtnW, 80);
        for (String s : labelsDropdownItems) {
            w = Math.max(w, this.font.width(s) + 12);
        }
        w = Math.min(w, this.width - x - 4);
        if (labelsDropdownItems.isEmpty()) {
            int h = LABELS_DD_ROW_H + 4;
            g.fill(x, y, x + w, y + h, 0xFF000000);
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF202028);
            g.drawString(this.font, Loc.tr("gui.sfmgui.node_editor.labels_empty"), x + 5, y + 3, 0xFF888888, false);
            return;
        }
        int h = rows * LABELS_DD_ROW_H + 4;
        g.fill(x, y, x + w, y + h, 0xFF000000);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF202028);
        for (int i = 0; i < rows; i++) {
            String label = labelsDropdownItems.get(i);
            int ry = y + 2 + i * LABELS_DD_ROW_H;
            boolean hover = mx >= x && mx <= x + w && my >= ry && my < ry + LABELS_DD_ROW_H;
            if (hover) {
                g.fill(x + 1, ry, x + w - 1, ry + LABELS_DD_ROW_H, 0xFF3A5A80);
            }
            g.drawString(this.font, this.font.plainSubstrByWidth(label, w - 8), x + 5, ry + 2, 0xFFCCE0FF, false);
        }
    }

    /**
     * Handle a click while the labels dropdown is open. Returns true if the click
     * was consumed (on the button or an item), false otherwise (caller closes it).
     */
    private boolean handleLabelsDropdownClick(double mx, double my) {
        int x = labelsBtnX;
        int y = labelsBtnY + labelsBtnH + 1;
        int rows = Math.min(labelsDropdownItems.size(), LABELS_DD_MAX_ROWS);
        int w = Math.max(labelsBtnW, 80);
        for (String s : labelsDropdownItems) {
            w = Math.max(w, this.font.width(s) + 12);
        }
        w = Math.min(w, this.width - x - 4);
        for (int i = 0; i < rows; i++) {
            int ry = y + 2 + i * LABELS_DD_ROW_H;
            if (mx >= x && mx <= x + w && my >= ry && my < ry + LABELS_DD_ROW_H) {
                highlightLabelNodes(labelsDropdownItems.get(i));
                labelsDropdownOpen = false;
                return true;
            }
        }
        return false;
    }

    /**
     * #7: instead of jumping the camera, flag every node referencing {@code label}
     * to be outlined in red for 5 seconds. Does not move the camera or selection.
     */
    private void highlightLabelNodes(String label) {
        highlightLabel = label;
        highlightUntil = System.currentTimeMillis() + 5000L;
    }

    private static final int HIGHLIGHT_COLOR = 0xFFFF3030;

    /** Draw a red outline around every node referencing the highlighted label (#7). */
    private void renderLabelHighlights(GuiGraphics g) {
        if (highlightLabel == null) {
            return;
        }
        if (System.currentTimeMillis() > highlightUntil) {
            highlightLabel = null;
            return;
        }
        for (EditorNode node : allVisibleNodes()) {
            if (!nodeLabels(node).contains(highlightLabel)) {
                continue;
            }
            NodeLayout L = layout(node);
            int x = node.x, y = node.y, w = L.width, h = L.height;
            int t = 2; // outline thickness in graph units
            g.fill(x - t, y - t, x + w + t, y, HIGHLIGHT_COLOR);            // top
            g.fill(x - t, y + h, x + w + t, y + h + t, HIGHLIGHT_COLOR);    // bottom
            g.fill(x - t, y, x, y + h, HIGHLIGHT_COLOR);                    // left
            g.fill(x + w, y, x + w + t, y + h, HIGHLIGHT_COLOR);            // right
        }
    }

    /** Draw the open-menu labels/controls/fields belonging to one node (z-order safe). */
    private void renderNodeControls(GuiGraphics g, EditorNode node, int mx, int my) {
        // No per-node scissor: menu content is sized to fit inside the node frame
        // (content-driven height + wrapping), so clipping isn't needed.
        for (int i = 0; i < labelText.size(); i++) {
            if (labelOwner.get(i) != node) {
                continue;
            }
            int[] p = labelPos.get(i);
            g.drawString(this.font, labelText.get(i), p[0], p[1], 0xFF303030, false);
        }
        for (MenuControl c : menuControls) {
            if (c.owner != node) {
                continue;
            }
            boolean hover = hoverControl(c, mx, my);
            ClassicTextures.wideButton(g, c.gx, c.gy, c.gw, hover);
            g.drawString(this.font, this.font.plainSubstrByWidth(c.label.get(), c.gw - 4), c.gx + 3, c.gy + 2, 0xFF303030, false);
        }
        // #3: resource icon-preview rows belonging to this node
        for (IconRow ir : iconRows) {
            if (ir.owner == node) {
                renderIconRow(g, ir);
            }
        }
        for (CanvasField f : canvasFields) {
            if (f.owner != node || f == activeField) {
                continue;
            }
            renderField(g, f, false);
        }
        // active field drawn last so its expanded overlay sits above sibling controls
        if (activeField != null && activeField.owner == node) {
            renderField(g, activeField, true);
        }
    }

    /** Fixed field width (#7: fields wrap downward instead of growing wider). */
    private int fieldWidth(CanvasField f) {
        return f.baseGw;
    }

    /** Inner text width available for wrapping. */
    private int fieldInnerWidth(CanvasField f) {
        return Math.max(4, f.baseGw - 6);
    }

    private static final int LINE_H = 10;

    /**
     * Character-wrap the field value into visual lines that each fit the field's
     * inner width. Returns start indices too so caret math can map index-&gt;line.
     * Always returns at least one (possibly empty) line.
     */
    private List<int[]> fieldLineRanges(CanvasField f) {
        List<int[]> ranges = new ArrayList<>();
        String v = f.value;
        int innerW = fieldInnerWidth(f);
        int start = 0;
        int i = 0;
        int lineW = 0;
        while (i < v.length()) {
            int cw = this.font.width(v.substring(i, i + 1));
            if (lineW + cw > innerW && i > start) {
                ranges.add(new int[]{start, i});
                start = i;
                lineW = 0;
            }
            lineW += cw;
            i++;
        }
        ranges.add(new int[]{start, v.length()});
        return ranges;
    }

    /**
     * Field height. Single-line (numeric/short) fields are always one 12px row.
     * Text fields grow to fit all wrapped lines, both focused and unfocused, so the
     * value is always fully visible and the menu layout below it follows suit.
     */
    private int fieldHeight(CanvasField f, boolean active) {
        if (f.singleLine) {
            return 12;
        }
        return 2 + fieldLineRanges(f).size() * LINE_H;
    }

    /**
     * The number of rows a text value wraps to at the given field width (single
     * data source used by both {@code buildMenu} offsets and {@code menuContentHeight}).
     */
    private int fieldRows(String value, int graphWidth) {
        if (value == null || value.isEmpty()) {
            return 1;
        }
        int innerW = Math.max(4, graphWidth - 6);
        int rows = 1;
        int lineW = 0;
        int lineStart = 0;
        for (int i = 0; i < value.length(); i++) {
            int cw = this.font.width(value.substring(i, i + 1));
            if (lineW + cw > innerW && i > lineStart) {
                rows++;
                lineStart = i;
                lineW = 0;
            }
            lineW += cw;
        }
        return rows;
    }

    /** Pixel height of a text field with the given value/width (for layout math). */
    private int textFieldHeight(String value, int graphWidth) {
        return 2 + fieldRows(value, graphWidth) * LINE_H;
    }

    /** Draw one in-canvas text field (box, selection highlight, multi-line text, caret). */
    private void renderField(GuiGraphics g, CanvasField f, boolean active) {
        int w = fieldWidth(f);
        if (!active) {
            int uh = fieldHeight(f, false);
            g.fill(f.gx, f.gy, f.gx + w, f.gy + uh, 0xFF000000);
            g.fill(f.gx + 1, f.gy + 1, f.gx + w - 1, f.gy + uh - 1, 0xFF101010);
            if (f.singleLine) {
                // numeric/short field: single row, truncate overflow
                String shown = this.font.plainSubstrByWidth(f.value, w - 6);
                g.drawString(this.font, shown, f.gx + 3, f.gy + 2, 0xFFE0E0E0, false);
            } else {
                // text field: draw every wrapped line (box is sized to fit them all)
                List<int[]> ranges = fieldLineRanges(f);
                for (int li = 0; li < ranges.size(); li++) {
                    int[] r = ranges.get(li);
                    g.drawString(this.font, f.value.substring(r[0], r[1]),
                            f.gx + 3, f.gy + 2 + li * LINE_H, 0xFFE0E0E0, false);
                }
            }
            return;
        }
        int h = fieldHeight(f, true);
        g.fill(f.gx, f.gy, f.gx + w, f.gy + h, 0xFF000000);
        g.fill(f.gx + 1, f.gy + 1, f.gx + w - 1, f.gy + h - 1, 0xFF202020);
        List<int[]> lines = fieldLineRanges(f);
        int caret = Math.min(f.caret, f.value.length());
        for (int li = 0; li < lines.size(); li++) {
            int[] r = lines.get(li);
            String lineStr = f.value.substring(r[0], r[1]);
            int ly = f.gy + 2 + li * LINE_H;
            // selection highlight within this line
            if (active && f.hasSelection()) {
                int s0 = Math.max(f.selMin(), r[0]);
                int s1 = Math.min(f.selMax(), r[1]);
                if (s0 < s1) {
                    int hx0 = f.gx + 3 + this.font.width(f.value.substring(r[0], s0));
                    int hx1 = f.gx + 3 + this.font.width(f.value.substring(r[0], s1));
                    g.fill(hx0, ly, hx1, ly + LINE_H, 0xFF3060A0);
                }
            }
            g.drawString(this.font, lineStr, f.gx + 3, ly, 0xFFE0E0E0, false);
            // caret on this line
            if (active && caret >= r[0] && caret <= r[1]
                    && ((System.currentTimeMillis() - caretBlinkStart) / 530) % 2 == 0) {
                // avoid drawing caret twice at a soft-wrap boundary: only on the line
                // where caret is strictly inside, or the last line for end-of-text.
                boolean isBoundaryDup = caret == r[1] && li < lines.size() - 1 && caret == lines.get(li + 1)[0];
                if (!isBoundaryDup) {
                    int cxp = f.gx + 3 + this.font.width(f.value.substring(r[0], caret));
                    g.fill(cxp, ly, cxp + 1, ly + LINE_H - 1, 0xFFFFFFFF);
                }
            }
        }
        return;
    }

    // screen-space action buttons (fixed at top): [Code Edit][Save][Preview][Exit]
    private static final int BTN_W = 64, BTN_H = 18, BTN_GAP = 4;
    private static final int TOP_BTN_COUNT = 4;

    private void renderTopBar(GuiGraphics g, int mx, int my) {
        g.fill(0, 0, this.width, TOP_BAR_H, 0xFF2A2A2E);
        g.fill(0, TOP_BAR_H, this.width, TOP_BAR_H + 1, 0xFF000000);
        int x = this.width - (BTN_W + BTN_GAP) * TOP_BTN_COUNT;
        drawTopButton(g, mx, my, x, Loc.tr("gui.sfmgui.node_editor.code_edit"));
        drawTopButton(g, mx, my, x + (BTN_W + BTN_GAP), Loc.tr("gui.sfmgui.node_editor.save"));
        drawTopButton(g, mx, my, x + (BTN_W + BTN_GAP) * 2, Loc.tr("gui.sfmgui.node_editor.toggle_preview") + ": " + onOff(showPreview));
        drawTopButton(g, mx, my, x + (BTN_W + BTN_GAP) * 3, Loc.tr("gui.sfmgui.node_editor.exit"));
        // info: command count (vertically centered in the top bar)
        g.drawString(this.font, Loc.tr("gui.sfmgui.info.commands", countStatements()), 6, 8, 0xFFBBBBBB, false);
        // name box label (to the left of the box)
        if (nameBox != null) {
            String nl = Loc.tr("gui.sfmgui.node_editor.name_label");
            g.drawString(this.font, nl, nameBox.getX() - this.font.width(nl) - 5, 8, 0xFFBBBBBB, false);
            // #2: labels dropdown toggle button, right of the name box. Replaces the
            // old truncated one-line labels bar so long label lists stay reachable.
            drawLabelsButton(g, mx, my);
        }
    }

    /** #2: draw the "Labels (n) ▾" toggle button in the top bar. */
    private void drawLabelsButton(GuiGraphics g, int mx, int my) {
        int count = collectAllLabelsList().size();
        String text = Loc.tr("gui.sfmgui.node_editor.labels_button", count) + (labelsDropdownOpen ? " \u25B2" : " \u25BC");
        int bx = nameBox.getX() + nameBox.getWidth() + 8;
        int by = 3;
        int bw = Math.min(this.font.width(text) + 10, this.width - (BTN_W + BTN_GAP) * TOP_BTN_COUNT - bx - 6);
        if (bw < 24) {
            // not enough horizontal room; hide the button (dropdown still openable? no)
            labelsBtnW = 0;
            return;
        }
        labelsBtnX = bx;
        labelsBtnY = by;
        labelsBtnW = bw;
        labelsBtnH = BTN_H;
        boolean hover = mx >= bx && mx <= bx + bw && my >= by && my <= by + BTN_H;
        g.fill(bx, by, bx + bw, by + BTN_H, 0xFF000000);
        g.fill(bx + 1, by + 1, bx + bw - 1, by + BTN_H - 1, hover ? 0xFF5A5A6A : 0xFF3A3A4A);
        g.drawString(this.font, this.font.plainSubstrByWidth(text, bw - 6), bx + 4, by + 5, 0xFF88AACC, false);
    }

    private void drawTopButton(GuiGraphics g, int mx, int my, int x, String text) {
        int y = 3;
        boolean hover = mx >= x && mx <= x + BTN_W && my >= y && my <= y + BTN_H;
        g.fill(x, y, x + BTN_W, y + BTN_H, 0xFF000000);
        g.fill(x + 1, y + 1, x + BTN_W - 1, y + BTN_H - 1, hover ? 0xFF6A6A6A : 0xFF4A4A4A);
        g.drawCenteredString(this.font, text, x + BTN_W / 2, y + 5, 0xFFFFFFFF);
    }

    private int topButtonAt(double mx, double my) {
        if (my < 3 || my > 3 + BTN_H) {
            return -1;
        }
        int x = this.width - (BTN_W + BTN_GAP) * TOP_BTN_COUNT;
        for (int i = 0; i < TOP_BTN_COUNT; i++) {
            int bx = x + (BTN_W + BTN_GAP) * i;
            if (mx >= bx && mx <= bx + BTN_W) {
                return i;
            }
        }
        return -1;
    }

    private static final int PREVIEW_VISIBLE_LINES = 5;

    private void renderPreview(GuiGraphics g) {
        // Preview the program body only (strip our layout/camera/note comments), with
        // SFM syntax highlighting so it matches the vanilla program view (#6).
        String sfml = LayoutMemory.stripLayout(generateSfml()).stripTrailing();
        List<net.minecraft.network.chat.MutableComponent> lines = SfmlHighlight.lines(sfml);
        if (lines.isEmpty()) {
            lines.add(Component.literal(" "));
        }
        int lh = this.font.lineHeight + 1;
        int ph = 8 + PREVIEW_VISIBLE_LINES * lh;
        int py = this.height - ph - 2;
        int pw = this.width - 14; // leave room for scrollbar
        g.fill(2, py, this.width - 2, this.height - 2, 0xF0101015);
        // clamp scroll
        previewScroll = Math.max(0, Math.min(previewScroll, Math.max(0, lines.size() - PREVIEW_VISIBLE_LINES)));
        int ty = py + 4;
        for (int i = previewScroll; i < previewScroll + PREVIEW_VISIBLE_LINES && i < lines.size(); i++) {
            g.drawString(this.font, trimToWidth(lines.get(i), pw - 12), 8, ty, 0xFFFFFFFF, false);
            ty += lh;
        }
        // scrollbar (right edge)
        if (lines.size() > PREVIEW_VISIBLE_LINES) {
            int sbx = this.width - 8;
            int sbh = ph - 8;
            float ratio = (float) PREVIEW_VISIBLE_LINES / lines.size();
            int thumbH = Math.max(6, (int) (sbh * ratio));
            float pos = lines.size() <= PREVIEW_VISIBLE_LINES ? 0 : (float) previewScroll / (lines.size() - PREVIEW_VISIBLE_LINES);
            int thumbY = py + 4 + (int) ((sbh - thumbH) * pos);
            g.fill(sbx, py + 4, sbx + 4, py + 4 + sbh, 0xFF303030);
            g.fill(sbx, thumbY, sbx + 4, thumbY + thumbH, 0xFF909090);
        }
    }

    private void renderFullscreenBackground(GuiGraphics g) {
        // Flat classic panel gray filling the whole screen. We intentionally do not
        // tile the bordered background texture (its beveled edges would show as a
        // grid of seams); the interior gray is the classic canvas look.
        g.fill(0, 0, this.width, this.height, 0xFFC6C6C6);
    }

    // Screen-space left toolbar (fixed; does not pan/zoom with the canvas).
    private static final int TOOLBAR_SCREEN_X = 4;
    private static final int TOOLBAR_SCREEN_Y = TOP_BAR_H + 4;
    private static final int TOOLBAR_BTN = 16;
    private static final int TOOLBAR_GAP = 2;

    private void renderToolbar(GuiGraphics g, int mx, int my) {
        int hovered = hoverToolButton(mx, my);
        for (ToolKind tk : ToolKind.values()) {
            int x = TOOLBAR_SCREEN_X;
            int y = TOOLBAR_SCREEN_Y + tk.ordinal() * (TOOLBAR_BTN + TOOLBAR_GAP);
            boolean hover = hovered == tk.ordinal();
            if (tk.icon < 0) {
                // custom icon: draw just the frame, then a procedural glyph
                ClassicTextures.toolbarFrame(g, x, y, hover);
                if (tk == ToolKind.ITEM_BLUEPRINT) {
                    drawItemBlueprintIcon(g, x + 1, y + 1);
                } else {
                    drawLightningIcon(g, x + 1, y + 1);
                }
            } else {
                ClassicTextures.toolbarButton(g, x, y, tk.icon, hover);
            }
        }
    }

    /** Draw a small yellow lightning bolt inside a 12px toolbar icon cell (power blueprint). */
    private void drawLightningIcon(GuiGraphics g, int x, int y) {
        int yellow = 0xFFFFD21A;
        int shade = 0xFFC8960A;
        // A blocky zig-zag bolt within the 12x12 cell (x..x+12, y..y+12).
        // upper diagonal stroke
        g.fill(x + 6, y + 1, x + 8, y + 3, yellow);
        g.fill(x + 5, y + 3, x + 7, y + 5, yellow);
        g.fill(x + 4, y + 5, x + 6, y + 6, yellow);
        // middle cross bar
        g.fill(x + 4, y + 5, x + 9, y + 6, yellow);
        // lower diagonal stroke
        g.fill(x + 6, y + 6, x + 8, y + 8, yellow);
        g.fill(x + 5, y + 8, x + 7, y + 10, yellow);
        g.fill(x + 4, y + 10, x + 6, y + 11, yellow);
        // subtle shade on the right edge for depth
        g.fill(x + 7, y + 3, x + 8, y + 5, shade);
        g.fill(x + 6, y + 8, x + 7, y + 10, shade);
    }

    /** Draw a small crate + right-arrow inside a 12px toolbar icon cell (item blueprint). */
    private void drawItemBlueprintIcon(GuiGraphics g, int x, int y) {
        int crate = 0xFFB07B3A;   // wooden crate brown
        int edge = 0xFF6E4A1E;    // darker crate edge
        int arrow = 0xFF3A6EA5;   // blue transfer arrow
        // crate body (left)
        g.fill(x + 1, y + 3, x + 6, y + 10, crate);
        // crate frame edges
        g.fill(x + 1, y + 3, x + 6, y + 4, edge);
        g.fill(x + 1, y + 9, x + 6, y + 10, edge);
        g.fill(x + 1, y + 3, x + 2, y + 10, edge);
        g.fill(x + 5, y + 3, x + 6, y + 10, edge);
        // cross-brace
        g.fill(x + 2, y + 6, x + 5, y + 7, edge);
        // transfer arrow (right): shaft + head
        g.fill(x + 6, y + 6, x + 10, y + 7, arrow);
        g.fill(x + 8, y + 4, x + 9, y + 6, arrow);
        g.fill(x + 8, y + 7, x + 9, y + 9, arrow);
        g.fill(x + 9, y + 5, x + 10, y + 8, arrow);
    }

    private int hoverToolButton(double mx, double my) {
        for (ToolKind tk : ToolKind.values()) {
            int x = TOOLBAR_SCREEN_X;
            int y = TOOLBAR_SCREEN_Y + tk.ordinal() * (TOOLBAR_BTN + TOOLBAR_GAP);
            if (mx >= x && mx <= x + TOOLBAR_BTN && my >= y && my <= y + TOOLBAR_BTN) {
                return tk.ordinal();
            }
        }
        return -1;
    }


    private int countStatements() {
        int n = 0;
        for (EditorNode node : allVisibleNodes()) {
            if (node instanceof StatementNode) {
                n++;
            }
        }
        return n;
    }

    /** Collect all referenced label names from the program, de-duplicated, in first-seen order. */
    private List<String> collectAllLabelsList() {
        java.util.LinkedHashSet<String> labels = new java.util.LinkedHashSet<>();
        for (EditorNode node : allVisibleNodes()) {
            for (String lbl : nodeLabels(node)) {
                labels.add(lbl);
            }
        }
        return new ArrayList<>(labels);
    }

    /** The label tokens referenced by a single node (IO labels, IF condition labels). */
    private List<String> nodeLabels(EditorNode node) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        if (node instanceof IOStatementNode io) {
            addSplit(set, io.labelAccess.labels);
        } else if (node instanceof IfStatementNode ifn && ifn.condition != null) {
            addSplit(set, ifn.condition.labels);
        }
        // #4: FORGET carries no editable labels in the visual editor, so it never
        // participates in label collection/highlighting.
        return new ArrayList<>(set);
    }

    private static void addSplit(java.util.LinkedHashSet<String> set, String csv) {
        if (csv == null || csv.isBlank()) return;
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) set.add(t);
        }
    }

    private void renderWires(GuiGraphics g) {
        for (WireSeg seg : collectWireSegments()) {
            int[] out = anchorGraph(seg.ref.parent(), true);
            int[] in = anchorGraph(seg.ref.child(), false);
            drawWire(g, out[0], out[1], in[0], in[1]);
        }
    }

    /**
     * #5: draw the armed wire-delete X. Called AFTER all nodes so it is never hidden
     * behind a node body. Runs inside the panned/scaled pose, so it draws in GRAPH space.
     * The X sits at the point the user right-clicked (next to the cursor), not the midpoint.
     */
    private void renderWireDeleteOverlay(GuiGraphics g) {
        if (pendingWireDelete != null && wireStillExists(pendingWireDelete)) {
            drawWireDeleteX(g, Math.round(wireDeleteGX), Math.round(wireDeleteGY));
        }
    }

    /** A wire segment plus its logical {@link WireRef} (parent -> child in a container). */
    private record WireSeg(WireRef ref) {
    }

    /** Walk every trigger/branch chain and list its parent->child wire segments. */
    private List<WireSeg> collectWireSegments() {
        List<WireSeg> segs = new ArrayList<>();
        for (TriggerNode t : graph.triggers) {
            collectContainerSegments(t, t, segs);
        }
        return segs;
    }

    private void collectContainerSegments(EditorNode parent, StatementContainer container, List<WireSeg> out) {
        EditorNode prev = parent;
        for (StatementNode child : container.getChildStatements()) {
            out.add(new WireSeg(new WireRef(prev, child, container)));
            for (StatementContainer sub : EditorGraph.subContainers(child)) {
                collectContainerSegments(child, sub, out);
            }
            prev = child;
        }
    }

    /** Whether a wire segment still exists in the live chain. */
    private boolean wireStillExists(WireRef ref) {
        return ref.container().getChildStatements().contains(ref.child());
    }

    private void drawWire(GuiGraphics g, int x1, int y1, int x2, int y2) {
        int color = 0xFF555555;
        int midY = (y1 + y2) / 2;
        g.fill(x1, Math.min(y1, midY), x1 + 1, Math.max(y1, midY) + 1, color);
        g.fill(Math.min(x1, x2), midY, Math.max(x1, x2) + 1, midY + 1, color);
        g.fill(x2, Math.min(midY, y2), x2 + 1, Math.max(midY, y2) + 1, color);
    }

    /** #5: half-size (graph units) of the square wire-delete X button. */
    private static final int WIRE_X_HALF = 5;

    /** Draw the red "X" delete button centered on a wire midpoint (GRAPH space). */
    private void drawWireDeleteX(GuiGraphics g, int cx, int cy) {
        int x0 = cx - WIRE_X_HALF, y0 = cy - WIRE_X_HALF;
        int x1 = cx + WIRE_X_HALF, y1 = cy + WIRE_X_HALF;
        g.fill(x0, y0, x1, y1, 0xFF902020);
        g.fill(x0, y0, x1, y0 + 1, 0xFFB04040); // top highlight
        g.drawString(this.font, "\u2715", x0 + 1, y0 + 1, 0xFFFFFFFF, false);
    }

    /** Whether screen point (mx,my) is within the armed wire-delete X hitbox. */
    private boolean hitWireDeleteX(double mx, double my) {
        if (pendingWireDelete == null || !wireStillExists(pendingWireDelete)) {
            return false;
        }
        int cx = gx2sx(wireDeleteGX), cy = gy2sy(wireDeleteGY);
        int r = Math.round(WIRE_X_HALF * unit());
        return mx >= cx - r && mx <= cx + r
                && my >= cy - r && my <= cy + r;
    }

    /** #5: screen-space pick radius for right-clicking a wire (any segment). */
    private static final int WIRE_PICK_PX = 6;

    /**
     * #5: find a wire whose drawn polyline (vertical/horizontal/vertical) passes near
     * (mx,my) in screen space. Returns the nearest such wire, or null. Matching any of
     * the three segments means a right-click anywhere along the wire triggers it.
     */
    private @Nullable WireRef wireAt(double mx, double my) {
        double pick = Math.max(WIRE_PICK_PX, WIRE_PICK_PX * unit());
        double best = pick * pick;
        WireRef found = null;
        for (WireSeg seg : collectWireSegments()) {
            int[] out = anchorScreen(seg.ref.parent(), true);
            int[] in = anchorScreen(seg.ref.child(), false);
            int x1 = out[0], y1 = out[1];
            int x2 = in[0], y2 = in[1];
            int midY = (y1 + y2) / 2;
            // three segments: (x1,y1)->(x1,midY), (x1,midY)->(x2,midY), (x2,midY)->(x2,y2)
            double d = Math.min(
                    distToSegSq(mx, my, x1, y1, x1, midY),
                    Math.min(
                            distToSegSq(mx, my, x1, midY, x2, midY),
                            distToSegSq(mx, my, x2, midY, x2, y2)));
            if (d <= best) {
                best = d;
                found = seg.ref;
            }
        }
        return found;
    }

    /** Squared distance from point (px,py) to the segment (ax,ay)-(bx,by). */
    private static double distToSegSq(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : ((px - ax) * dx + (py - ay) * dy) / len2;
        t = Math.max(0, Math.min(1, t));
        double cx = ax + t * dx, cy = ay + t * dy;
        double ex = px - cx, ey = py - cy;
        return ex * ex + ey * ey;
    }

    /**
     * #5: break the chain at {@code ref}: move {@code child} and every sibling after
     * it (within the same container) into {@link #detached}, severing everything from
     * this wire onward. Series re-linking of the remaining prefix is unaffected.
     */
    private void breakWireAt(WireRef ref) {
        pushUndo();
        List<StatementNode> siblings = ref.container().getChildStatements();
        int idx = siblings.indexOf(ref.child());
        if (idx < 0) {
            return;
        }
        List<StatementNode> tail = new ArrayList<>(siblings.subList(idx, siblings.size()));
        siblings.subList(idx, siblings.size()).clear();
        detached.addAll(tail);
        pendingWireDelete = null;
        markDirty();
        rebuild();
    }

    private void renderNode(GuiGraphics g, EditorNode node, int mx, int my) {
        NodeLayout L = layout(node);
        int x = node.x, y = node.y;
        ClassicTextures.nodeFrameSized(g, x, y, L.width, L.height);

        // #6: title + summary as ONE component, drawn in a single pass so both use
        // the same font metrics/baseline (no size/spacing mismatch between the two).
        // Single line, truncated to reserve the arrow/X gutter on the right.
        net.minecraft.network.chat.MutableComponent titleLine = Component.empty()
                .append(Component.literal(nodeTitle(node)).withStyle(s -> s.withColor(0xFF303030)));
        Component head = summarizeColored(node);
        if (head != null) {
            titleLine.append(Component.literal("  ")).append(head);
        }
        g.drawString(this.font, trimToWidth(titleLine, L.width - 14 - 6), x + 6, y + 4, 0xFFFFFFFF, false);
        // expand arrow
        ClassicTextures.nodeArrow(g, x + L.width - 12, y + 4, !node.collapsed, false);
        // #1(old): delete-confirm X — only shown after right-click arms the node.
        if (node == pendingDelete) {
            int bx = x + L.width - 22, by = y + 4;
            g.fill(bx, by, bx + 8, by + 8, 0xFF902020);
            g.fill(bx, by, bx + 8, by + 1, 0xFFB04040);
            g.drawString(this.font, "\u2715", bx + 1, by, 0xFFFFFFFF, false);
        }
        // nubs (aligned to anchor centers). #1: a nub whose wire is actually
        // connected gets a light grey-green overlay so connected endpoints read
        // differently from free ones. Input(top) nub = this node is some wire's
        // child; output(bottom) nub = this node is some wire's parent.
        if (!(node instanceof TriggerNode)) {
            int[] p = anchorGraph(node, false);
            int nx = p[0] - CONN_W / 2, ny = p[1] - CONN_H / 2;
            ClassicTextures.connection(g, nx, ny, true, false);
            if (isInputConnected(node)) {
                g.fill(nx, ny, nx + CONN_W, ny + CONN_H, NUB_CONNECTED_TINT);
            }
        }
        int[] po = anchorGraph(node, true);
        int ox = po[0] - CONN_W / 2, oy = po[1] - CONN_H / 2;
        ClassicTextures.connection(g, ox, oy, false, false);
        if (isOutputConnected(node)) {
            g.fill(ox, oy, ox + CONN_W, oy + CONN_H, NUB_CONNECTED_TINT);
        }

        if (node.collapsed) {
            // #D/#3/#5: wrapped body rows. Lines with an icon reserve a left indent
            // for it on EVERY wrapped row (consistent left margin); the icon itself is
            // drawn once at the first row. Text still wraps fully (never truncated).
            List<BodyLine> cl = collapsedLines(node);
            int ty = y + TITLE_H + BODY_PAD_TOP; // #2: pad below the title divider
            for (BodyLine line : cl) {
                boolean icon = line.hasIcon();
                int textX = x + 6 + (icon ? BODY_ICON : 0);
                int textW = bodyTextWidth(L.width, icon);
                var seqs = wrapToWidth(line.text, textW);
                for (int r = 0; r < seqs.size(); r++) {
                    if (r == 0 && icon) {
                        drawBodyIcon(g, line, x + 5, ty - 1);
                    }
                    g.drawString(this.font, seqs.get(r), textX, ty, 0xFFFFFFFF, false);
                    ty += 10;
                }
            }
            return;
        }

        // menu strips
        List<String> menus = menusFor(node);
        for (int i = 0; i < menus.size(); i++) {
            int sy = y + L.stripY.get(i);
            int sx = x + 2;
            ClassicTextures.menuStrip(g, sx, sy);
            boolean open = node.isMenuOpen(i);
            ClassicTextures.menuArrow(g, sx + ClassicTextures.MENU_ARROW_X, sy + 2, open, false);
            g.drawString(this.font, menuDisplayName(menus.get(i)), sx + 5, sy + 3, 0xFF303030, false);
        }
    }

    // ===== anchors (centered on the nub sprite) =====
    private int[] anchorGraph(EditorNode n, boolean output) {
        NodeLayout L = layout(n);
        int cx = n.x + L.width / 2;
        int cy = output ? n.y + L.height + CONN_H / 2 : n.y - CONN_H / 2;
        return new int[]{cx, cy};
    }

    private int[] anchorScreen(EditorNode n, boolean output) {
        int[] gp = anchorGraph(n, output);
        return new int[]{gx2sx(gp[0]), gy2sy(gp[1])};
    }

    // ===== menus =====
    private List<String> menusFor(EditorNode node) {
        if (node instanceof TriggerNode t) {
            return t.kind == TriggerNode.Kind.TIMER ? List.of("Interval", "Note") : List.of("Note");
        }
        if (node instanceof IOStatementNode) {
            return List.of("Labels", "Filter", "Sides", "Slots", "Except", "Note");
        }
        if (node instanceof IfStatementNode) {
            return List.of("Condition", "Note");
        }
        if (node instanceof ForgetStatementNode) {
            // #4: FORGET no longer configures labels in the visual editor; it
            // always forgets everything, so only the Note menu remains.
            return List.of("Note");
        }
        if (node instanceof RawStatementNode) {
            return List.of("Raw", "Note");
        }
        return List.of();
    }

    private String menuDisplayName(String key) {
        return Loc.tr("gui.sfmgui.menu." + key.toLowerCase(Locale.ROOT));
    }

    private String nodeTitle(EditorNode node) {
        if (node instanceof TriggerNode t) {
            return t.kind == TriggerNode.Kind.TIMER ? Loc.tr("gui.sfmgui.node.timer") : Loc.tr("gui.sfmgui.node.pulse");
        }
        if (node instanceof IOStatementNode io) {
            return io.kind == StatementNode.Kind.INPUT ? Loc.tr("gui.sfmgui.node.input") : Loc.tr("gui.sfmgui.node.output");
        }
        if (node instanceof IfStatementNode) {
            return Loc.tr("gui.sfmgui.node.if");
        }
        if (node instanceof ForgetStatementNode) {
            return Loc.tr("gui.sfmgui.node.forget");
        }
        if (node instanceof RawStatementNode) {
            return Loc.tr("gui.sfmgui.node.raw");
        }
        return node.getTitle();
    }

    /**
     * The node's header summary as a raw SFML fragment (keywords uppercase so SFM's
     * highlighter colours them). Used by {@link #summarizeColored}.
     */
    private String nodeSummarySfml(EditorNode node) {
        if (node instanceof TriggerNode t) {
            if (t.kind == TriggerNode.Kind.TIMER) {
                return "EVERY " + t.interval + (t.unit == TriggerNode.TimeUnit.SECONDS ? " SECONDS" : " TICKS");
            }
            return "EVERY REDSTONE PULSE";
        }
        if (node instanceof IOStatementNode io) {
            String labels = io.labels().isBlank() ? "*" : io.labels();
            return (io.kind == StatementNode.Kind.INPUT ? "FROM " : "TO ") + labels;
        }
        if (node instanceof IfStatementNode ifn) {
            return GraphToSfml.conditionToSfml(ifn.condition);
        }
        if (node instanceof ForgetStatementNode) {
            // #4: FORGET no longer carries a label list.
            return "FORGET";
        }
        if (node instanceof RawStatementNode r) {
            return r.raw.replace("\n", " ");
        }
        return "";
    }

    /** The node header summary, syntax-highlighted (#6); null when there is nothing to show. */
    private @Nullable Component summarizeColored(EditorNode node) {
        String sfml = nodeSummarySfml(node);
        if (sfml == null || sfml.isBlank()) {
            return null;
        }
        return SfmlHighlight.fragment(sfml);
    }

    /**
     * Lines shown below the title when the node is collapsed. Coloured with SFM
     * highlighting (#6). The header already shows the labels summary, so these
     * lines never repeat it (#3); notes are prefixed with a "Note:" label (#7).
     */
    private List<BodyLine> collapsedLines(EditorNode node) {
        List<BodyLine> lines = new ArrayList<>();
        if (node instanceof IOStatementNode io) {
            // #5: one resource-name row per resource (fully wrapped in render), with a
            // leading icon (#3/#5); then a type + direction summary line.
            java.util.LinkedHashSet<String> types = new java.util.LinkedHashSet<>();
            for (var lim : io.limits) {
                if (lim.resources == null || lim.resources.isBlank()) {
                    continue;
                }
                for (String piece : lim.resources.split(",")) {
                    String r = piece.trim();
                    if (r.isEmpty()) {
                        continue;
                    }
                    types.add(resourceTypeOf(r));
                    String name = resourceNameOf(r);
                    if (name.isBlank()) {
                        // Typeless/nameless resources (e.g. "forge_energy::") show no name row.
                        continue;
                    }
                    lines.add(resourceBodyLine(r, name));
                }
            }
            // type(s) + direction line, reflecting the chosen sides / each-side mode.
            String typeStr = String.join("/", types);
            String sideStr = "";
            if (io.labelAccess.eachSide) {
                sideStr = Loc.tr("gui.sfmgui.sides.each");
            } else if (!io.labelAccess.sides.isEmpty()) {
                sideStr = io.labelAccess.sides.stream()
                        .map(NodeEditorScreen::sideName)
                        .reduce((a, b) -> a + "," + b).orElse("");
            }
            String line2 = joinNonBlank(typeStr, sideStr);
            if (!line2.isBlank()) {
                lines.add(BodyLine.text(Component.literal(line2).withStyle(s -> s.withColor(0xFF88CCFF))));
            }
            addNoteLine(lines, node);
        } else {
            // Trigger/Forget/If: header already shows the summary; only notes add info.
            addNoteLine(lines, node);
        }
        return lines;
    }

    /** Build a resource body line with a leading icon (#3/#5) when one can be resolved. */
    private BodyLine resourceBodyLine(String resourceId, String name) {
        Component text = SfmlHighlight.fragment(name);
        String type = resourceTypeOf(resourceId);
        if (type.equals("item")) {
            ResourceLocation id = parseResourceId(resourceId);
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(id));
                if (!stack.isEmpty()) {
                    return new BodyLine(text, stack, null, 0);
                }
            }
        } else if (type.equals("fluid")) {
            ResourceLocation id = parseResourceId(resourceId);
            if (id != null && BuiltInRegistries.FLUID.containsKey(id)) {
                try {
                    var ext = net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions
                            .of(BuiltInRegistries.FLUID.get(id));
                    ResourceLocation still = ext.getStillTexture();
                    if (still != null) {
                        return new BodyLine(text, null, still, ext.getTintColor());
                    }
                } catch (Throwable ignored) {
                    // no sprite — text only
                }
            }
        }
        return BodyLine.text(text);
    }

    /** Parse the namespace:path of a resource id (with or without a type prefix). */
    private static @Nullable ResourceLocation parseResourceId(String resourceId) {
        String[] parts = resourceId.split(":", -1);
        String ns, path;
        if (parts.length >= 3) {
            ns = parts[1];
            path = parts[2];
        } else if (parts.length == 2) {
            ns = parts[0];
            path = parts[1];
        } else {
            return null;
        }
        if (ns.isBlank() || path.isBlank() || ns.contains("*") || path.contains("*")) {
            return null;
        }
        return ResourceLocation.tryBuild(ns, path);
    }

    /**
     * The resource type of a SFML resource id (#5). Ids are {@code type:ns:path},
     * {@code ns:path} (type defaults to "item"), or with wildcards. Returns the
     * leading type segment.
     */
    private static String resourceTypeOf(String resourceId) {
        String[] parts = resourceId.split(":", -1);
        // type:ns:path -> 3 segments; ns:path -> 2 segments (implicit item)
        if (parts.length >= 3) {
            return parts[0].isBlank() ? "item" : parts[0];
        }
        return "item";
    }

    /**
     * The concrete resource name (namespace:path) of a SFML resource id, with the
     * leading type segment stripped (#5).
     */
    private static String resourceNameOf(String resourceId) {
        String[] parts = resourceId.split(":", -1);
        if (parts.length >= 3) {
            // drop the type segment, keep ns:path
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < parts.length; i++) {
                if (i > 1) {
                    sb.append(':');
                }
                sb.append(parts[i]);
            }
            String name = sb.toString();
            // "forge_energy::" -> ":" which is not a real name; treat as blank.
            return name.replace(":", "").isBlank() ? "" : name;
        }
        return resourceId;
    }

    private static String joinNonBlank(String a, String b) {
        if (a == null || a.isBlank()) {
            return b == null ? "" : b;
        }
        if (b == null || b.isBlank()) {
            return a;
        }
        return a + " \u00b7 " + b;
    }

    /** #4: append a picked resource to a comma-separated list (used by EXCEPT). */
    private static String appendResource(String existing, String pick) {
        if (pick == null || pick.isBlank()) {
            return existing == null ? "" : existing;
        }
        if (existing == null || existing.isBlank()) {
            return pick.trim();
        }
        return existing.trim() + ", " + pick.trim();
    }

    /** Append a white "Note: ..." line (bold prefix) when the node has a note (#1). */
    private void addNoteLine(List<BodyLine> lines, EditorNode node) {
        if (node.note != null && !node.note.isBlank()) {
            Component line = Component.empty()
                    .append(Component.literal(Loc.tr("gui.sfmgui.note_prefix"))
                            .withStyle(s -> s.withColor(0xFFFFFF).withBold(true)))
                    .append(Component.literal(node.note)
                            .withStyle(s -> s.withColor(0xFFFFFF)));
            lines.add(BodyLine.text(line));
        }
    }

    /**
     * Truncate a styled component to {@code maxWidth} px, preserving colours, and
     * return it as a {@link net.minecraft.util.FormattedCharSequence} ready to draw.
     */
    private net.minecraft.util.FormattedCharSequence trimToWidth(Component text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text.getVisualOrderText();
        }
        net.minecraft.network.chat.FormattedText clipped = this.font.substrByWidth(text, maxWidth);
        return net.minecraft.locale.Language.getInstance().getVisualOrder(clipped);
    }

    /**
     * Wrap a styled component to {@code maxWidth} px, preserving colours, into one
     * or more visual lines (#4). Never returns empty.
     */
    private List<net.minecraft.util.FormattedCharSequence> wrapToWidth(Component text, int maxWidth) {
        List<net.minecraft.util.FormattedCharSequence> out = this.font.split(text, Math.max(1, maxWidth));
        if (out.isEmpty()) {
            out = new ArrayList<>();
            out.add(net.minecraft.util.FormattedCharSequence.EMPTY);
        }
        return out;
    }

    /** Wrap width for a collapsed-body line, reserving the icon indent when present. */
    private int bodyTextWidth(int nodeWidth, boolean hasIcon) {
        return nodeWidth - 12 - (hasIcon ? BODY_ICON : 0);
    }

    /** Draw a body line's leading icon (item model or fluid sprite) scaled to ~9px. */
    private void drawBodyIcon(GuiGraphics g, BodyLine line, int x, int y) {
        if (line.icon != null) {
            var pose = g.pose();
            pose.pushPose();
            float s = 9f / 16f;
            pose.translate(x, y, 0);
            pose.scale(s, s, 1f);
            g.renderItem(line.icon, 0, 0);
            pose.popPose();
        } else if (line.sprite != null) {
            var atlas = Minecraft.getInstance()
                    .getTextureAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS)
                    .apply(line.sprite);
            int tint = line.spriteTint;
            float a = ((tint >> 24) & 0xFF) / 255f;
            float r = ((tint >> 16) & 0xFF) / 255f;
            float gg = ((tint >> 8) & 0xFF) / 255f;
            float b = (tint & 0xFF) / 255f;
            if (a == 0f) a = 1f;
            g.blit(x, y, 0, 9, 9, atlas, r, gg, b, a);
        }
    }

    // ===== control hit tests =====
    private boolean hoverControl(MenuControl c, double mx, double my) {
        int sx = gx2sx(c.gx), sy = gy2sy(c.gy);
        int sw = Math.round(c.gw * unit()), sh = Math.round(c.gh * unit());
        return mx >= sx && mx <= sx + sw && my >= sy && my <= sy + sh;
    }

    // ===== node creation =====
    private int spawnCascade = 0;

    /** A spawn position near the current view centre, cascaded to avoid overlap. */
    private int[] spawnPos() {
        // centre of the visible canvas in graph space
        float cxScreen = (TOOLBAR_SCREEN_X + TOOLBAR_BTN + this.width) / 2f;
        float cyScreen = (TOP_BAR_H + this.height) / 2f;
        int gx = Math.round(sx2gx(cxScreen));
        int gy = Math.round(sy2gy(cyScreen));
        // Cascade by roughly a node's footprint so consecutive spawns (which open
        // their menu and are tall) don't stack on top of each other.
        int step = spawnCascade % 6;
        spawnCascade++;
        int[] pos = {gx - 60 + step * 40, gy - 60 + step * 34};
        // if still landing on an existing node, nudge by a node-sized offset
        for (EditorNode n : allVisibleNodes()) {
            if (Math.abs(n.x - pos[0]) < NODE_W && Math.abs(n.y - pos[1]) < 40) {
                pos[0] += 40;
                pos[1] += 34;
            }
        }
        return pos;
    }

    private void createNode(ToolKind tk) {
        pushUndo();
        int[] sp = spawnPos();
        int gxp = sp[0], gyp = sp[1];
        switch (tk) {
            case TIMER, PULSE -> {
                TriggerNode t = new TriggerNode(gxp, gyp, tk == ToolKind.TIMER ? TriggerNode.Kind.TIMER : TriggerNode.Kind.REDSTONE_PULSE);
                graph.addTrigger(t);
                graph.selected = t;
                t.collapsed = false;
                // Timer opens its Interval menu by default; pulse has no menus.
                t.setOnlyMenu((tk == ToolKind.TIMER) ? 0 : -1);
            }
            case INPUT, OUTPUT, IF, FORGET -> {
                StatementContainer target = resolveContainer();
                StatementNode node;
                // Place new statements at the cascaded spawn spot so they never
                // land on a fixed overlapping position.
                int px = gxp, py = gyp;
                node = switch (tk) {
                    case INPUT -> new IOStatementNode(px, py, StatementNode.Kind.INPUT);
                    case OUTPUT -> new IOStatementNode(px, py, StatementNode.Kind.OUTPUT);
                    case IF -> new IfStatementNode(px, py);
                    case FORGET -> new ForgetStatementNode(px, py);
                    default -> null;
                };
                if (node != null && target != null) {
                    target.getChildStatements().add(node);
                    graph.selected = node;
                    // #2: default-expand statements with their first menu open.
                    // FORGET only has a Note menu now, so leave it collapsed-closed.
                    node.collapsed = false;
                    node.setOnlyMenu((node instanceof IOStatementNode || node instanceof IfStatementNode) ? 0 : -1);
                }
            }
        }
        markDirty();
        rebuild();
    }

    /**
     * #2: "Power blueprint" — one click spawns a ready-to-use power-transfer program:
     * a 1-tick power timer chained to an INPUT and an OUTPUT, with the timer's Interval
     * menu and both IO nodes' Labels + Sides menus opened by default.
     */
    private void createPowerBlueprint() {
        pushUndo();
        int[] sp = spawnPos();
        int bx = sp[0], by = sp[1];

        // 1-tick power-transfer timer
        TriggerNode timer = new TriggerNode(bx, by, TriggerNode.Kind.TIMER);
        timer.powerTransfer = true;
        timer.interval = 1;
        timer.collapsed = false;
        timer.setOnlyMenu(0); // Interval menu open
        graph.addTrigger(timer);

        // INPUT forge_energy:: FROM *  — Labels(0) + Sides(2) open
        IOStatementNode in = new IOStatementNode(bx + 160, by, StatementNode.Kind.INPUT);
        in.collapsed = false;
        in.limits.add(forgeEnergyLimit());
        in.openMenus.clear();
        in.openMenus.add(0);
        in.openMenus.add(2);
        timer.statements.add(in);

        // OUTPUT forge_energy:: TO *  — chained after the input; Labels(0) + Sides(2) open
        IOStatementNode out = new IOStatementNode(bx + 320, by, StatementNode.Kind.OUTPUT);
        out.collapsed = false;
        out.limits.add(forgeEnergyLimit());
        out.openMenus.clear();
        out.openMenus.add(0);
        out.openMenus.add(2);
        timer.statements.add(out);

        graph.selected = timer;
        markDirty();
        rebuild();
    }

    /** A resource limit preset to the bare Forge-Energy type (power). */
    private static ResourceLimitData forgeEnergyLimit() {
        ResourceLimitData lim = new ResourceLimitData();
        lim.resources = "forge_energy::";
        return lim;
    }

    /**
     * #4: "Item blueprint" — one click spawns a general item-moving program:
     * a plain 20-tick timer chained to INPUT -> OUTPUT -> FORGET. The two IO nodes
     * open their Labels(0) + Filter(1) + Sides(2) menus and the FORGET opens its
     * Note(0) menu, so every knob a normal item route needs is visible up front.
     */
    private void createItemBlueprint() {
        pushUndo();
        int[] sp = spawnPos();
        int bx = sp[0], by = sp[1];

        // Plain 20-tick timer (item transfer keeps the vanilla minimum interval).
        TriggerNode timer = new TriggerNode(bx, by, TriggerNode.Kind.TIMER);
        timer.powerTransfer = false;
        timer.interval = 20;
        timer.collapsed = false;
        timer.setOnlyMenu(0); // Interval menu open
        graph.addTrigger(timer);

        // INPUT FROM *  — Labels(0) + Filter(1) + Sides(2) open
        IOStatementNode in = new IOStatementNode(bx + 160, by, StatementNode.Kind.INPUT);
        in.collapsed = false;
        in.openMenus.clear();
        in.openMenus.add(0);
        in.openMenus.add(1);
        in.openMenus.add(2);
        timer.statements.add(in);

        // OUTPUT TO *  — chained after the input; Labels(0) + Filter(1) + Sides(2) open
        IOStatementNode out = new IOStatementNode(bx + 320, by, StatementNode.Kind.OUTPUT);
        out.collapsed = false;
        out.openMenus.clear();
        out.openMenus.add(0);
        out.openMenus.add(1);
        out.openMenus.add(2);
        timer.statements.add(out);

        // FORGET  — chained last; Note(0) menu open
        ForgetStatementNode forget = new ForgetStatementNode(bx + 480, by);
        forget.collapsed = false;
        forget.openMenus.clear();
        forget.openMenus.add(0);
        timer.statements.add(forget);

        graph.selected = timer;
        markDirty();
        rebuild();
    }

    private @Nullable StatementContainer resolveContainer() {
        EditorNode sel = graph.selected;
        if (sel instanceof TriggerNode t) {
            return t;
        }
        if (sel instanceof IfStatementNode ifn) {
            return ifn.hasElse ? ifn.elseBranch : ifn.thenBranch;
        }
        if (sel instanceof StatementNode s) {
            StatementContainer c = graph.findOwningContainer(s);
            if (c != null) {
                return c;
            }
        }
        return graph.triggers.isEmpty() ? null : graph.triggers.get(graph.triggers.size() - 1);
    }

    // ===== rebuild interactive elements for the open menu =====
    private void rebuild() {
        layoutCache.clear();
        canvasFields.clear();
        menuControls.clear();
        labelPos.clear();
        labelText.clear();
        labelOwner.clear();
        iconRows.clear();
        activeField = null;
        this.clearWidgets();
        // clearWidgets() also drops the top-bar name box + IME proxy; re-register them.
        if (nameBox != null) {
            this.addRenderableWidget(nameBox);
        }
        if (imeProxy != null) {
            imeProxy.setValue("");
            imeProxy.setFocused(false);
            this.addRenderableWidget(imeProxy);
        }
        // Build interactive content for every node that has an open menu, so that
        // opening a menu is remembered per node and multiple can be open at once (#5).
        for (EditorNode node : allVisibleNodes()) {
            if (node.collapsed || node.openMenus.isEmpty()) {
                continue;
            }
            List<String> menus = menusFor(node);
            NodeLayout L = layout(node);
            currentOwner = node;
            for (int mi : node.openMenus) {
                if (mi < 0 || mi >= menus.size()) {
                    continue;
                }
                Integer contentY = L.openContentY.get(mi);
                if (contentY == null) {
                    continue;
                }
                int gx = node.x + PAD + 2;
                int gy = node.y + contentY + PAD;
                buildMenu(node, menus.get(mi), gx, gy);
            }
        }
        currentOwner = null;
    }

    private void addLabel(int gx, int gy, String text) {
        labelPos.add(new int[]{gx, gy});
        labelText.add(text);
        labelOwner.add(currentOwner);
    }

    /** A wrapping text field (labels/resource/except/slots/raw/note). Grows vertically. */
    private void addField(int gx, int gy, int wGraph, String initial, Consumer<String> onChange) {
        CanvasField f = new CanvasField(gx, gy, wGraph, initial, 256, v -> {
            markDirty();
            onChange.accept(v);
        });
        f.owner = currentOwner;
        canvasFields.add(f);
    }

    /**
     * A single-line numeric/short field (interval/offset/quantity/retain/count).
     * Never wraps — stays a fixed 12px row so menus using hard-coded offsets around
     * it can't be pushed apart by a long value.
     */
    private void addNumberField(int gx, int gy, int wGraph, String initial, Consumer<String> onChange) {
        CanvasField f = new CanvasField(gx, gy, wGraph, initial, 256, v -> {
            markDirty();
            onChange.accept(v);
        });
        f.owner = currentOwner;
        f.singleLine = true;
        canvasFields.add(f);
    }

    /** #3: height reserved for a menu icon-preview row. */
    private static final int ICON_ROW_H = 12;

    /** #3: add an icon-preview row reflecting a resource field's current value. */
    private void addIconRow(int gx, int gy, int gw, java.util.function.Supplier<String> resources) {
        IconRow ir = new IconRow(gx, gy, gw, resources);
        ir.owner = currentOwner;
        iconRows.add(ir);
    }

    /** #3: render a resource icon-preview row (side-by-side item/fluid icons). */
    private void renderIconRow(GuiGraphics g, IconRow ir) {
        String csv = ir.resources.get();
        if (csv == null || csv.isBlank()) {
            return;
        }
        int cx = ir.gx;
        for (String piece : csv.split(",")) {
            String r = piece.trim();
            if (r.isEmpty()) {
                continue;
            }
            if (cx + 9 > ir.gx + ir.gw) {
                break;
            }
            // reuse the body-line icon resolver + drawer for consistency
            BodyLine bl = resourceBodyLine(r, "");
            if (bl.hasIcon()) {
                drawBodyIcon(g, bl, cx, ir.gy);
                cx += 11;
            }
        }
    }

    private void addToggle(int gx, int gy, int gw, java.util.function.Supplier<String> label, Runnable action) {
        MenuControl c = new MenuControl(gx, gy, gw, 12, label, () -> {
            pushUndo();
            markDirty();
            action.run();
            rebuild();
        });
        c.owner = currentOwner;
        menuControls.add(c);
    }

    private void addPick(int gx, int gy, int gw, Consumer<String> onPick) {
        MenuControl c = new MenuControl(gx, gy, gw, 12, () -> Loc.tr("gui.sfmgui.field.pick"), () ->
                Minecraft.getInstance().setScreen(new ResourcePickerScreen(this, p -> {
                    markDirty();
                    onPick.accept(p);
                })));
        c.owner = currentOwner;
        menuControls.add(c);
    }

    private void buildMenu(EditorNode node, String menu, int gx, int gy) {
        int cw = NODE_W - 2 * PAD - 2;
        switch (menu) {
            case "Interval" -> {
                if (node instanceof TriggerNode t) {
                    // Rows laid out with no vertical overlap (see menuContentHeight):
                    // every+field @0, min-hint @13, unit @24, global @40, offset @58, power @74.
                    addLabel(gx, gy, Loc.tr("gui.sfmgui.field.every"));
                    addNumberField(gx + 34, gy - 1, 30, String.valueOf(t.interval), v -> t.interval = Math.max(1, parseInt(v, t.interval)));
                    // hint reflects the current minimum (1 in power-transfer mode, else 20)
                    addLabel(gx, gy + 13, t.powerTransfer
                            ? Loc.tr("gui.sfmgui.field.min_interval_power")
                            : Loc.tr("gui.sfmgui.field.min_interval"));
                    addToggle(gx, gy + 24, cw, () -> Loc.tr("gui.sfmgui.toggle.unit",
                            t.unit == TriggerNode.TimeUnit.SECONDS ? Loc.tr("gui.sfmgui.unit.seconds") : Loc.tr("gui.sfmgui.unit.ticks")),
                            () -> t.unit = t.unit == TriggerNode.TimeUnit.TICKS ? TriggerNode.TimeUnit.SECONDS : TriggerNode.TimeUnit.TICKS);
                    addToggle(gx, gy + 40, cw, () -> Loc.tr("gui.sfmgui.toggle.global", onOff(t.global)), () -> t.global = !t.global);
                    addLabel(gx, gy + 58, Loc.tr("gui.sfmgui.field.offset"));
                    addNumberField(gx + 34, gy + 57, 30, String.valueOf(t.offset), v -> t.offset = Math.max(0, parseInt(v, t.offset)));
                    // B: power-transfer toggle — lifts the 20-tick minimum to 1 (default 1).
                    addToggle(gx, gy + 74, cw, () -> Loc.tr("gui.sfmgui.toggle.power_transfer", onOff(t.powerTransfer)), () -> {
                        t.powerTransfer = !t.powerTransfer;
                        if (t.powerTransfer) {
                            // entering power mode: default to the 1-tick minimum
                            if (t.interval >= 20) {
                                t.interval = 1;
                            }
                        } else if (t.interval < 20) {
                            // leaving power mode: bounce back so a normal program stays valid
                            t.interval = 20;
                        }
                    });
                }
            }
            case "Labels" -> {
                addLabel(gx, gy, Loc.tr("gui.sfmgui.field.labels"));
                if (node instanceof IOStatementNode io) {
                    // #E: accumulator layout — the labels field wraps, so everything
                    // below it follows the field's actual height (no fixed offsets).
                    int ry = gy + 9;
                    addField(gx, ry, cw, io.labelAccess.labels, v -> io.labelAccess.labels = v);
                    ry += textFieldHeight(io.labelAccess.labels, cw) + 4;
                    addToggle(gx, ry, cw, () -> Loc.tr("gui.sfmgui.toggle.round_robin", rrName(io.labelAccess.roundRobin)),
                            () -> io.labelAccess.roundRobin = nextEnum(io.labelAccess.roundRobin, LabelAccessData.RoundRobin.values()));
                    ry += 16;
                    addToggle(gx, ry, cw, () -> Loc.tr("gui.sfmgui.toggle.each", onOff(io.each)), () -> io.each = !io.each);
                    ry += 16;
                    if (io.kind == StatementNode.Kind.OUTPUT) {
                        addToggle(gx, ry, cw, () -> Loc.tr("gui.sfmgui.toggle.empty_slots", onOff(io.emptySlotsOnly)), () -> io.emptySlotsOnly = !io.emptySlotsOnly);
                    }
                }
            }
            case "Filter" -> {
                if (node instanceof IOStatementNode io) {
                    if (io.limits.isEmpty()) {
                        io.limits.add(new ResourceLimitData());
                    }
                    // #E: accumulate each limit block's height from the running y so the
                    // wrapping resource field never overlaps the controls beneath it.
                    int by = gy;
                    for (int li = 0; li < io.limits.size(); li++) {
                        final int idx = li;
                        var lim = io.limits.get(li);
                        int ry = by;
                        addLabel(gx, ry, Loc.tr("gui.sfmgui.field.quantity"));
                        addNumberField(gx, ry + 9, 34, lim.quantity, v -> lim.quantity = v);
                        addLabel(gx + 44, ry, Loc.tr("gui.sfmgui.field.retain"));
                        addNumberField(gx + 44, ry + 9, 34, lim.retain, v -> lim.retain = v);
                        ry += 24;
                        addLabel(gx, ry, Loc.tr("gui.sfmgui.field.resource"));
                        ry += 9;
                        // resource field wraps to fit its full value (#5)
                        addField(gx, ry, cw, lim.resources, v -> lim.resources = v);
                        ry += textFieldHeight(lim.resources, cw) + 3;
                        // pick (left) + remove-this-limit (right) share a row.
                        addPick(gx, ry, cw - 40, p -> {
                            lim.resources = p == null ? "" : p.trim();
                            rebuild();
                        });
                        addToggle(gx + cw - 36, ry, 36, () -> Loc.tr("gui.sfmgui.filter.remove"), () -> {
                            if (io.limits.size() > 1) {
                                io.limits.remove(idx);
                            } else {
                                io.limits.set(0, new ResourceLimitData());
                            }
                        });
                        ry += 17;
                        // #3: resource icon preview row under the pick/remove row
                        addIconRow(gx, ry, cw, () -> lim.resources);
                        ry += ICON_ROW_H;
                        addToggle(gx, ry, cw, () -> Loc.tr("gui.sfmgui.toggle.qty_each", eachOrTotal(lim.quantityEach)),
                                () -> lim.quantityEach = !lim.quantityEach);
                        ry += 16;
                        addToggle(gx, ry, cw, () -> Loc.tr("gui.sfmgui.toggle.retain_each", eachOrTotal(lim.retainEach)),
                                () -> lim.retainEach = !lim.retainEach);
                        ry += 16;
                        by = ry + 4; // gap before the next limit block
                    }
                    // trailing "add another limit" button
                    addToggle(gx, by, cw, () -> Loc.tr("gui.sfmgui.filter.add"), () -> io.limits.add(new ResourceLimitData()));
                }
            }
            case "Sides" -> {
                if (node instanceof IOStatementNode io) {
                    addToggle(gx, gy, cw, () -> Loc.tr("gui.sfmgui.toggle.each_side", onOff(io.labelAccess.eachSide)), () -> io.labelAccess.eachSide = !io.labelAccess.eachSide);
                    if (!io.labelAccess.eachSide) {
                        // #8: compact single-row summary of selected sides that toggles the
                        // full 6-face grid open/closed, so the menu stays small when unused.
                        final EditorNode owner = node;
                        addToggle(gx, gy + 18, cw, () -> sidesSummaryLabel(io), () -> {
                            if (!sidesExpanded.remove(owner)) {
                                sidesExpanded.add(owner);
                            }
                        });
                        if (sidesExpanded.contains(node)) {
                            buildSideGrid(io, gx, gy + 36, cw);
                        }
                    }
                }
            }
            case "Slots" -> {
                addLabel(gx, gy, Loc.tr("gui.sfmgui.field.slots"));
                if (node instanceof IOStatementNode io) {
                    addField(gx, gy + 9, cw, io.labelAccess.slots, v -> io.labelAccess.slots = v);
                }
            }
            case "Except" -> {
                addLabel(gx, gy, Loc.tr("gui.sfmgui.field.except"));
                if (node instanceof IOStatementNode io) {
                    // #E: accumulator layout — except field wraps, pick/icons follow it.
                    int ry = gy + 9;
                    addField(gx, ry, cw, io.except, v -> io.except = v);
                    ry += textFieldHeight(io.except, cw) + 3;
                    // #3: icon preview row for the excluded resources
                    addIconRow(gx, ry, cw, () -> io.except);
                    ry += ICON_ROW_H;
                    // #4: EXCEPT is a list — picking ADDS another resource (append),
                    // unlike Filter/Condition which replace the single value.
                    addPick(gx, ry, cw, p -> {
                        io.except = appendResource(io.except, p);
                        rebuild();
                    });
                }
            }
            case "Condition" -> {
                if (node instanceof IfStatementNode ifn) {
                    var c = ifn.condition;
                    addToggle(gx, gy, cw, () -> Loc.tr("gui.sfmgui.toggle.kind", condKindName(c.kind)), () -> {
                        c.kind = nextConditionKind(c.kind);
                        rebuild();
                    });
                    if (c.kind == Condition.Kind.HAS) {
                        // #E: accumulator layout — the labels and resource fields both
                        // wrap, so every element below follows their actual heights.
                        int ry = gy + 16;
                        addToggle(gx, ry, cw, () -> Loc.tr("gui.sfmgui.toggle.quantifier", c.setOp.name()), () -> c.setOp = nextEnum(c.setOp, Condition.SetOp.values()));
                        ry += 18;
                        addField(gx, ry, cw, c.labels, v -> c.labels = v);
                        ry += textFieldHeight(c.labels, cw) + 3;
                        addToggle(gx, ry, 46, () -> Loc.tr("gui.sfmgui.toggle.cmp", c.comparison.symbol), () -> c.comparison = nextEnum(c.comparison, Condition.Comparison.values()));
                        addNumberField(gx + 50, ry, 34, c.count, v -> c.count = v);
                        ry += 18;
                        addField(gx, ry, cw, c.resource, v -> c.resource = v);
                        ry += textFieldHeight(c.resource, cw) + 3;
                        // #5: replace the field content with the picked resource.
                        addPick(gx, ry, cw, p -> {
                            c.resource = p == null ? "" : p.trim();
                            rebuild();
                        });
                        ry += 17;
                        addToggle(gx, ry, cw, () -> Loc.tr("gui.sfmgui.toggle.else", onOff(ifn.hasElse)), () -> ifn.hasElse = !ifn.hasElse);
                    } else if (c.kind == Condition.Kind.REDSTONE) {
                        addToggle(gx, gy + 16, 46, () -> Loc.tr("gui.sfmgui.toggle.cmp", c.comparison.symbol), () -> c.comparison = nextEnum(c.comparison, Condition.Comparison.values()));
                        addNumberField(gx + 50, gy + 16, 34, c.count, v -> c.count = v);
                        addToggle(gx, gy + 34, cw, () -> Loc.tr("gui.sfmgui.toggle.else", onOff(ifn.hasElse)), () -> ifn.hasElse = !ifn.hasElse);
                    } else {
                        addToggle(gx, gy + 16, cw, () -> Loc.tr("gui.sfmgui.toggle.else", onOff(ifn.hasElse)), () -> ifn.hasElse = !ifn.hasElse);
                    }
                }
            }
            case "Raw" -> {
                if (node instanceof RawStatementNode r) {
                    addLabel(gx, gy, Loc.tr("gui.sfmgui.field.raw"));
                    addField(gx, gy + 9, cw, r.raw, v -> r.raw = v);
                }
            }
            case "Note" -> {
                addLabel(gx, gy, Loc.tr("gui.sfmgui.field.note"));
                addField(gx, gy + 9, cw, node.note, v -> node.note = v);
            }
        }
    }

    /** #8: one-line summary of the selected sides, used as the expand/collapse row. */
    private String sidesSummaryLabel(IOStatementNode io) {
        String chosen;
        if (io.labelAccess.sides.isEmpty()) {
            chosen = Loc.tr("gui.sfmgui.sides.none");
        } else {
            chosen = io.labelAccess.sides.stream()
                    .map(NodeEditorScreen::sideName)
                    .reduce((a, b) -> a + "," + b).orElse("");
        }
        String arrow = sidesExpanded.contains(io) ? " \u25B2" : " \u25BC";
        return Loc.tr("gui.sfmgui.sides.summary", chosen) + arrow;
    }

    private void buildSideGrid(IOStatementNode io, int gx, int gy, int cw) {
        // Exclude NULL: it is the implicit "no direction / default" and picking
        // nothing already means default (codegen omits the side qualifier).
        List<LabelAccessData.SideOption> sides = new ArrayList<>();
        for (LabelAccessData.SideOption s : LabelAccessData.SideOption.values()) {
            if (s != LabelAccessData.SideOption.NULL) {
                sides.add(s);
            }
        }
        int colW = (cw - 2) / 2;
        for (int i = 0; i < sides.size(); i++) {
            var side = sides.get(i);
            int col = i % 2, row = i / 2;
            int bx = gx + col * (colW + 2);
            int by = gy + row * 14;
            addToggle(bx, by, colW, () -> (io.labelAccess.sides.contains(side) ? "\u2611 " : "\u2610 ") + sideName(side), () -> {
                if (io.labelAccess.sides.contains(side)) {
                    io.labelAccess.sides.remove(side);
                } else {
                    io.labelAccess.sides.add(side);
                }
            });
        }
    }

    // ===== localized enum display names =====
    private static String onOff(boolean b) {
        return b ? Loc.tr("gui.sfmgui.on") : Loc.tr("gui.sfmgui.off");
    }

    /** "each" vs "total" wording for the quantity/retain EACH flags. */
    private static String eachOrTotal(boolean each) {
        return each ? Loc.tr("gui.sfmgui.each") : Loc.tr("gui.sfmgui.total");
    }

    private static String sideName(LabelAccessData.SideOption s) {
        return Loc.tr("gui.sfmgui.side." + s.name().toLowerCase(Locale.ROOT));
    }

    private static String rrName(LabelAccessData.RoundRobin rr) {
        return Loc.tr("gui.sfmgui.rr." + rr.name().toLowerCase(Locale.ROOT));
    }

    private static String condKindName(Condition.Kind k) {
        return Loc.tr("gui.sfmgui.condkind." + k.name().toLowerCase(Locale.ROOT));
    }

    private static int parseInt(String v, int fallback) {
        try {
            return Math.max(0, Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static <E extends Enum<E>> E nextEnum(E cur, E[] vals) {
        return vals[(cur.ordinal() + 1) % vals.length];
    }

    private static Condition.Kind nextConditionKind(Condition.Kind cur) {
        Condition.Kind[] sup = {Condition.Kind.TRUE, Condition.Kind.FALSE, Condition.Kind.HAS, Condition.Kind.REDSTONE};
        int idx = 0;
        for (int i = 0; i < sup.length; i++) {
            if (sup[i] == cur) {
                idx = i;
            }
        }
        return sup[(idx + 1) % sup.length];
    }

    // ===== input =====
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // top bar buttons first (screen space)
        int tb = topButtonAt(mx, my);
        if (tb >= 0 && button == 0) {
            if (tb == 0) {
                openCodeEditor();
            } else if (tb == 1) {
                save();
                Minecraft.getInstance().setScreen(previousScreen);
            } else if (tb == 2) {
                showPreview = !showPreview;
            } else {
                onClose();
            }
            return true;
        }
        // #2: labels dropdown (screen space, above everything else)
        if (button == 0) {
            // toggle button
            if (labelsBtnW > 0 && mx >= labelsBtnX && mx <= labelsBtnX + labelsBtnW
                    && my >= labelsBtnY && my <= labelsBtnY + labelsBtnH) {
                labelsDropdownOpen = !labelsDropdownOpen;
                return true;
            }
            // an open dropdown consumes clicks: pick an item, or close on outside click
            if (labelsDropdownOpen) {
                if (handleLabelsDropdownClick(mx, my)) {
                    return true;
                }
                labelsDropdownOpen = false;
                // fall through so the click can still act on whatever is beneath
            }
        }
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        int tool = hoverToolButton(mx, my);
        if (tool >= 0 && button == 0) {
            ToolKind tk = ToolKind.values()[tool];
            if (tk == ToolKind.POWER_BLUEPRINT) {
                createPowerBlueprint();
            } else if (tk == ToolKind.ITEM_BLUEPRINT) {
                createItemBlueprint();
            } else {
                createNode(tk);
            }
            return true;
        }
        // #5: left-click the armed wire-delete X breaks the chain there.
        if (button == 0 && hitWireDeleteX(mx, my)) {
            breakWireAt(pendingWireDelete);
            return true;
        }
        if (button == 0) {
            // Z-order aware hit-testing: find the top-most node under the cursor and
            // only interact with ITS controls, so overlapping lower nodes never
            // steal clicks that visually belong to the node on top.
            EditorNode top = nodeUnder(mx, my);
            // Fields/controls live inside a node body; only consider the top node's.
            if (top != null) {
                for (CanvasField f : canvasFields) {
                    if (f.owner != top) {
                        continue;
                    }
                    int sx = gx2sx(f.gx), sy = gy2sy(f.gy);
                    // hit the field by its expanded height if already active, else its single row
                    int sw = Math.round(fieldWidth(f) * unit());
                    int sh = Math.round(fieldHeight(f, f == activeField) * unit());
                    if (mx >= sx && mx <= sx + sw && my >= sy && my <= sy + sh) {
                        if (activeField != f) {
                            pushUndo();
                        }
                        activeField = f;
                        caretBlinkStart = System.currentTimeMillis();
                        int relX = (int) ((mx - sx) / unit()) - 3;
                        int relY = (int) ((my - sy) / unit()) - 2;
                        f.caret = caretForClick(f, relX, relY);
                        f.clearSelection();
                        beginFieldEditing(f);
                        return true;
                    }
                }
                for (MenuControl c : menuControls) {
                    if (c.owner != top) {
                        continue;
                    }
                    if (hoverControl(c, mx, my)) {
                        endFieldEditing();
                        c.onClick.run();
                        return true;
                    }
                }
            }
            // Nubs sit just outside node bodies; test after in-body controls.
            NubHit nub = nubAt(mx, my);
            if (nub != null) {
                endFieldEditing();
                handleNubClick(nub.node(), nub.isOutput());
                return true;
            }
            if (top != null) {
                EditorNode n = top;
                NodeLayout L = layout(n);
                // #1: left-click the red X (only shown when this node is armed) to delete.
                if (n == pendingDelete) {
                    int dx = gx2sx(n.x + L.width - 22), dy = gy2sy(n.y + 4);
                    int dw = Math.round(8 * unit()), dh = Math.round(8 * unit());
                    if (mx >= dx && mx <= dx + dw && my >= dy && my <= dy + dh) {
                        pendingDelete = null;
                        deleteNode(n);
                        return true;
                    }
                }
                // Any other left-click on a node clears a pending delete on a different node.
                if (pendingDelete != null && pendingDelete != n) {
                    pendingDelete = null;
                }
                // expand arrow
                int ax = gx2sx(n.x + L.width - 12), ay = gy2sy(n.y + 4);
                int aw = Math.round(ClassicTextures.ARROW_W * unit()), ah = Math.round(ClassicTextures.ARROW_H * unit());
                if (mx >= ax && mx <= ax + aw && my >= ay && my <= ay + ah) {
                    n.collapsed = !n.collapsed;
                    graph.selected = n;
                    rebuild();
                    return true;
                }
                if (!n.collapsed && handleStripClick(n, mx, my)) {
                    return true;
                }
                graph.selected = n;
                draggingNode = n;
                dragDX = sx2gx(mx) - n.x;
                dragDY = sy2gy(my) - n.y;
                endFieldEditing();
                rebuild();
                return true;
            }
            // Clicking empty canvas clears any armed delete.
            pendingDelete = null;
            pendingWireDelete = null;
            if (graph.selected != null) {
                graph.selected = null;
                rebuild();
            }
            return true;
        }
        if (button == 1 || button == 2) {
            if (button == 1) {
                if (connectSource != null) {
                    connectSource = null;
                    return true;
                }
                EditorNode hit = nodeUnder(mx, my);
                if (hit != null) {
                    // #1: right-click no longer deletes directly; it arms the
                    // delete-confirm X on this node (right-click again toggles it off).
                    pendingDelete = (pendingDelete == hit) ? null : hit;
                    pendingWireDelete = null;
                    graph.selected = hit;
                    rebuild();
                    return true;
                }
                // #5: right-click anywhere along a wire arms that wire's delete-X,
                // placed right where the cursor is (next to the click).
                WireRef wire = wireAt(mx, my);
                if (wire != null) {
                    boolean toggleOff = pendingWireDelete != null
                            && pendingWireDelete.child() == wire.child();
                    pendingWireDelete = toggleOff ? null : wire;
                    if (!toggleOff) {
                        wireDeleteGX = sx2gx(mx);
                        wireDeleteGY = sy2gy(my);
                    }
                    pendingDelete = null;
                    return true;
                }
                // right-click on empty canvas clears any armed delete
                pendingDelete = null;
                pendingWireDelete = null;
            }
            panning = true;
            panStartMX = mx;
            panStartMY = my;
            panStartX = panX;
            panStartY = panY;
            return true;
        }
        return false;
    }

    private @Nullable EditorNode nodeUnder(double mx, double my) {
        // Match render z-order: the selected node is drawn last (on top), so it
        // wins hit-testing when the cursor is over it.
        if (graph.selected != null && nodeBodyContains(graph.selected, mx, my)) {
            return graph.selected;
        }
        List<EditorNode> nodes = allVisibleNodes();
        for (int i = nodes.size() - 1; i >= 0; i--) {
            EditorNode n = nodes.get(i);
            if (nodeBodyContains(n, mx, my)) {
                return n;
            }
        }
        return null;
    }

    /** True if screen point (mx,my) is inside node n's drawn frame. */
    private boolean nodeBodyContains(EditorNode n, double mx, double my) {
        NodeLayout L = layout(n);
        int sx = gx2sx(n.x), sy = gy2sy(n.y);
        int sw = Math.round(L.width * unit()), sh = Math.round(L.height * unit());
        return mx >= sx && mx <= sx + sw && my >= sy && my <= sy + sh;
    }

    private boolean handleStripClick(EditorNode node, double mx, double my) {
        NodeLayout L = layout(node);
        List<String> menus = menusFor(node);
        for (int i = 0; i < menus.size(); i++) {
            int sy = gy2sy(node.y + L.stripY.get(i));
            int sh = Math.round(STRIP_H * unit());
            int sx = gx2sx(node.x + 2);
            int sw = Math.round(ClassicTextures.MENU_ITEM_W * unit());
            if (mx >= sx && mx <= sx + sw && my >= sy && my <= sy + sh) {
                graph.selected = node;
                // #5: toggle just this menu; other open menus stay open.
                node.toggleMenu(i);
                rebuild();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (draggingNode != null) {
            draggingNode.x = Math.round(sx2gx(mx) - (float) dragDX);
            draggingNode.y = Math.round(sy2gy(my) - (float) dragDY);
            markDirty();
            // move the open-menu fields with the node
            rebuildFieldPositions();
            return true;
        }
        if (panning) {
            panX = panStartX + (float) ((mx - panStartMX) / unit());
            panY = panStartY + (float) ((my - panStartMY) / unit());
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    /** Recompute field graph-anchors when a node with an open menu moves while dragging. */
    private void rebuildFieldPositions() {
        if (draggingNode != null && !draggingNode.openMenus.isEmpty()) {
            rebuild();
        }
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        draggingNode = null;
        panning = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        // preview scroll (when preview open and cursor in preview panel area)
        if (showPreview && delta != 0) {
            int lh = this.font.lineHeight + 1;
            int ph = 8 + PREVIEW_VISIBLE_LINES * lh;
            int py = this.height - ph - 2;
            if (my >= py && my <= this.height - 2) {
                previewScroll -= (int) Math.signum(delta);
                previewScroll = Math.max(0, previewScroll);
                return true;
            }
        }
        if (delta != 0) {
            float old = zoom;
            float nz = Mth.clamp(zoom * (delta > 0 ? 1.1f : 0.9f), ZOOM_MIN, ZOOM_MAX);
            if (nz != old) {
                float gx = sx2gx(mx), gy = sy2gy(my);
                zoom = nz;
                panX = (float) ((mx - originX) / unit() - gx);
                panY = (float) ((my - originY) / unit() - gy);
                rebuild();
                return true;
            }
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int key, int scancode, int mods) {
        // A: while a canvas field is being edited, ESC/ENTER end editing; every other
        // key (backspace/delete/arrows/Home/End/Ctrl-combos + IME composition) is
        // handled natively by the focused imeProxy via super.keyPressed.
        if (activeField != null) {
            caretBlinkStart = System.currentTimeMillis();
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                endFieldEditing();
                return true;
            }
            // C: intercept undo/redo even mid-edit so a global history step wins over
            // the field. Ctrl+A/C/V/X are NOT caught here — they fall through to the
            // focused imeProxy (super.keyPressed) for select-all/clipboard handling.
            if ((mods & GLFW.GLFW_MOD_CONTROL) != 0 && key == GLFW.GLFW_KEY_Z) {
                endFieldEditing();
                if (undo()) {
                    showToast(Component.translatable("gui.sfmgui.toast.undo"));
                }
                return true;
            }
            if ((mods & GLFW.GLFW_MOD_CONTROL) != 0 && key == GLFW.GLFW_KEY_Y) {
                endFieldEditing();
                if (redo()) {
                    showToast(Component.translatable("gui.sfmgui.toast.redo"));
                }
                return true;
            }
            // #5: explicit clipboard shortcuts on the focused proxy, so select-all/copy/
            // cut/paste work deterministically. We drive the proxy directly then mirror
            // the value/caret (and selection for select-all) back to the on-canvas field.
            if ((mods & GLFW.GLFW_MOD_CONTROL) != 0 && imeProxy != null) {
                switch (key) {
                    case GLFW.GLFW_KEY_A -> {
                        int len = imeProxy.getValue().length();
                        imeProxy.setCursorPosition(len);
                        imeProxy.setHighlightPos(0);
                        // reflect the full selection on the visible field
                        activeField.caret = len;
                        activeField.selAnchor = len == 0 ? -1 : 0;
                        return true;
                    }
                    case GLFW.GLFW_KEY_C -> {
                        String sel = imeProxy.getHighlighted();
                        if (sel != null && !sel.isEmpty()) {
                            Minecraft.getInstance().keyboardHandler.setClipboard(sel);
                        }
                        return true;
                    }
                    case GLFW.GLFW_KEY_X -> {
                        String sel = imeProxy.getHighlighted();
                        if (sel != null && !sel.isEmpty()) {
                            Minecraft.getInstance().keyboardHandler.setClipboard(sel);
                            imeProxy.insertText(""); // delete selection
                            activeField.selAnchor = -1;
                            syncActiveFieldFromProxy();
                        }
                        return true;
                    }
                    case GLFW.GLFW_KEY_V -> {
                        String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
                        if (clip != null && !clip.isEmpty()) {
                            imeProxy.insertText(clip); // replaces selection if any
                            activeField.selAnchor = -1;
                            syncActiveFieldFromProxy();
                        }
                        return true;
                    }
                    default -> {
                    }
                }
            }
            return super.keyPressed(key, scancode, mods);
        }
        if (super.keyPressed(key, scancode, mods)) {
            return true;
        }
        // ---- undo / redo (no field focused) ----
        if ((mods & GLFW.GLFW_MOD_CONTROL) != 0 && key == GLFW.GLFW_KEY_Z) {
            if (undo()) {
                showToast(Component.translatable("gui.sfmgui.toast.undo"));
            }
            return true;
        }
        if ((mods & GLFW.GLFW_MOD_CONTROL) != 0 && key == GLFW.GLFW_KEY_Y) {
            if (redo()) {
                showToast(Component.translatable("gui.sfmgui.toast.redo"));
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (connectSource != null) {
                connectSource = null;
                return true;
            }
            onClose();
            return true;
        }
        if ((key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) && graph.selected != null) {
            deleteNode(graph.selected);
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char c, int mods) {
        // A: typed characters (incl. IME-composed CJK) go to the focused imeProxy.
        if (activeField != null) {
            caretBlinkStart = System.currentTimeMillis();
        }
        return super.charTyped(c, mods);
    }

    // ===== A: canvas-field editing delegated to the off-screen imeProxy =====

    /** Start editing a canvas field: mirror its value/caret into the proxy and focus it. */
    private void beginFieldEditing(CanvasField f) {
        activeField = f;
        if (imeProxy != null) {
            imeProxy.setValue(f.value);
            imeProxy.setCursorPosition(Math.min(f.caret, f.value.length()));
            imeProxy.setHighlightPos(imeProxy.getCursorPosition());
            this.setFocused(imeProxy);
            imeProxy.setFocused(true);
        }
    }

    /** Stop editing: drop focus from the proxy. */
    private void endFieldEditing() {
        activeField = null;
        if (imeProxy != null) {
            imeProxy.setFocused(false);
        }
        this.setFocused(null);
    }

    /** Mirror the proxy's caret back to the active field each frame (for caret rendering). */
    private void syncActiveFieldFromProxy() {
        if (activeField != null && imeProxy != null) {
            // value is mirrored via the proxy's responder; keep the caret in sync here.
            activeField.caret = Math.min(imeProxy.getCursorPosition(), activeField.value.length());
        }
    }

    /** Caret index within {@code value} nearest to graph-pixel x (by character midpoint). */
    private int caretForX(String value, int relX) {
        if (relX <= 0) {
            return 0;
        }
        for (int i = 0; i < value.length(); i++) {
            int left = this.font.width(value.substring(0, i));
            int right = this.font.width(value.substring(0, i + 1));
            // land on index i if the click falls in the left half of char i,
            // otherwise continue; clicking in the right half advances past it.
            if (relX < (left + right) / 2) {
                return i;
            }
        }
        return value.length();
    }

    /** Map a click at field-local (relX,relY) to a caret index across wrapped lines. */
    private int caretForClick(CanvasField f, int relX, int relY) {
        List<int[]> lines = fieldLineRanges(f);
        int li = Math.max(0, Math.min(lines.size() - 1, relY / LINE_H));
        int[] r = lines.get(li);
        String lineStr = f.value.substring(r[0], r[1]);
        int col = caretForX(lineStr, relX);
        return r[0] + col;
    }

    private void deleteNode(EditorNode node) {
        pushUndo();
        if (node instanceof StatementNode s && detached.contains(s)) {
            detached.remove(s);
        } else if (node instanceof TriggerNode trigger) {
            // Deleting a trigger must NOT delete its statements. Detach them so
            // they survive (float free) and can be re-attached to another trigger.
            detached.addAll(trigger.statements);
            trigger.statements.clear();
            graph.triggers.remove(trigger);
        } else if (node instanceof IfStatementNode ifn) {
            // Detach the branch statements too, then remove the IF node itself.
            detached.addAll(ifn.thenBranch.statements);
            detached.addAll(ifn.elseBranch.statements);
            ifn.thenBranch.statements.clear();
            ifn.elseBranch.statements.clear();
            graph.removeNode(ifn);
        } else {
            graph.removeNode(node);
        }
        if (graph.selected == node) {
            graph.selected = null;
        }
        if (connectSource == node) {
            connectSource = null;
        }
        if (pendingDelete == node) {
            pendingDelete = null;
        }
        sidesExpanded.remove(node);
        markDirty();
        rebuild();
    }

    // ===== connections =====
    private static final int NUB_HIT = 6;

    /** A concrete nub hit: which node and whether it was the output (bottom) nub. */
    private record NubHit(EditorNode node, boolean isOutput) {
    }

    /**
     * #1: find the specific nub under the cursor (output bottom / input top), or
     * null. Returns the exact nub hit rather than guessing by proximity, so a click
     * that isn't actually on a nub never spawns a phantom endpoint.
     */
    private @Nullable NubHit nubAt(double mx, double my) {
        for (EditorNode n : allVisibleNodes()) {
            if (hitNub(mx, my, anchorGraph(n, true))) {
                return new NubHit(n, true);
            }
            if (!(n instanceof TriggerNode) && hitNub(mx, my, anchorGraph(n, false))) {
                return new NubHit(n, false);
            }
        }
        return null;
    }

    private boolean hitNub(double mx, double my, int[] gp) {
        int sx = gx2sx(gp[0]), sy = gy2sy(gp[1]);
        int r = Math.round(NUB_HIT * unit());
        return mx >= sx - r && mx <= sx + r && my >= sy - r && my <= sy + r;
    }

    private void handleNubClick(EditorNode node, boolean isOutput) {
        // #2: clicking the SAME output nub we're wiring from just cancels the
        // pending wire — existing connections are left untouched (no detach).
        if (connectSource != null && node == connectSource && isOutput) {
            connectSource = null;
            return;
        }
        if (connectSource == null) {
            // Start a wire from an output nub. Clicking an input nub no longer
            // detaches anything — connections are only changed by completing a
            // new wire onto a target input (which re-parents it).
            if (isOutput) {
                connectSource = node;
            }
            return;
        }
        // Completing a wire onto a target input: (re)connect it after the source.
        if (!isOutput && node instanceof StatementNode target && node != connectSource) {
            attachAfter(connectSource, target);
            connectSource = null;
            rebuild();
            return;
        }
        // Clicking another output nub re-points the pending wire to that source;
        // clicking anything else (e.g. the source's own input) keeps things as-is.
        if (isOutput) {
            connectSource = node;
        } else {
            connectSource = null;
        }
    }

    private void attachAfter(EditorNode source, StatementNode target) {
        pushUndo();
        markDirty();
        StatementContainer prev = graph.findOwningContainer(target);
        if (prev != null) {
            prev.getChildStatements().remove(target);
        }
        detached.remove(target);
        if (source instanceof TriggerNode t) {
            t.statements.add(target);
        } else if (source instanceof IfStatementNode ifn) {
            ifn.thenBranch.statements.add(target);
        } else if (source instanceof StatementNode s) {
            StatementContainer owner = graph.findOwningContainer(s);
            if (owner != null) {
                List<StatementNode> list = owner.getChildStatements();
                list.add(list.indexOf(s) + 1, target);
            }
        }
    }

    private List<EditorNode> allVisibleNodes() {
        List<EditorNode> list = new ArrayList<>(graph.allNodes());
        list.addAll(detached);
        return list;
    }

    /**
     * #1: whether a node's INPUT (top) nub has a wire attached — i.e. this node is the
     * child endpoint of some drawn wire segment (a trigger or previous sibling feeds it).
     */
    private boolean isInputConnected(EditorNode node) {
        for (WireSeg seg : collectWireSegments()) {
            if (seg.ref().child() == node) {
                return true;
            }
        }
        return false;
    }

    /**
     * #1: whether a node's OUTPUT (bottom) nub has a wire attached — i.e. this node is
     * the parent endpoint of some drawn wire segment (it feeds a child / next sibling).
     */
    private boolean isOutputConnected(EditorNode node) {
        for (WireSeg seg : collectWireSegments()) {
            if (seg.ref().parent() == node) {
                return true;
            }
        }
        return false;
    }

    // ===== persistence =====
    private String generateSfml() {
        // Guard against destroying an unparseable disk: if the source could not be
        // parsed and the user hasn't edited anything, round-trip the original text
        // verbatim instead of emitting an (empty) generated program.
        if (parseFailed && !dirty) {
            return initialProgram;
        }
        String base = GraphToSfml.generate(graph);
        return LayoutMemory.appendTo(base, graph, detached, panX, panY, zoom);
    }

    private void save() {
        // Never overwrite the disk with generated output when the original failed
        // to parse and nothing was edited.
        if (parseFailed && !dirty) {
            return;
        }
        saveWriter.accept(generateSfml());
    }

    /**
     * #task4/#5/#6: hand off to SFM's own text (code) editor. We build the editor
     * screen ourselves (via {@code createProgramEditScreen(...).asScreen()}) and show
     * it with {@code setScreen} — NOT SFM's push/replace helper — so the screen stack
     * stays fully under our control (opening the resource picker and returning no
     * longer loses this editor's background). Our {@link NodeEditorCodeContext} returns
     * here on close and reloads the edited SFML back into the graph, so code edits are
     * reflected as nodes. SFM is present at runtime, so these refs are safe.
     */
    private void openCodeEditor() {
        String sfml = generateSfml();
        try {
            NodeEditorCodeContext ctx = new NodeEditorCodeContext(sfml, this, saveWriter);
            Screen editor = ca.teamdman.sfm.client.screen.SFMScreenChangeHelpers
                    .createProgramEditScreen(ctx).asScreen();
            Minecraft.getInstance().setScreen(editor);
        } catch (Throwable t) {
            SFMGui.LOGGER.error("Failed to open SFM code editor", t);
        }
    }

    /** Mark the graph as edited so saving is allowed and the exit prompt appears. */
    private void markDirty() {
        dirty = true;
    }

    /** Snapshot the current program (for undo) before a mutation. Coalescing is the
     *  caller's responsibility (e.g. text edits push once on focus). */
    private void pushUndo() {
        if (restoring) {
            return;
        }
        String snap = currentSnapshot();
        if (!undoStack.isEmpty() && undoStack.peek().equals(snap)) {
            return; // no-op change
        }
        undoStack.push(snap);
        while (undoStack.size() > UNDO_LIMIT) {
            undoStack.removeLast();
        }
        redoStack.clear();
    }

    /** A full SFML snapshot (program + layout + camera), independent of parseFailed guard. */
    private String currentSnapshot() {
        String base = GraphToSfml.generate(graph);
        return LayoutMemory.appendTo(base, graph, detached, panX, panY, zoom);
    }

    private boolean undo() {
        if (undoStack.isEmpty()) {
            return false;
        }
        redoStack.push(currentSnapshot());
        restoreFrom(undoStack.pop());
        return true;
    }

    private boolean redo() {
        if (redoStack.isEmpty()) {
            return false;
        }
        undoStack.push(currentSnapshot());
        restoreFrom(redoStack.pop());
        return true;
    }

    /** Rebuild the graph/camera from a snapshot and refresh the UI. */
    private void restoreFrom(String snapshot) {
        restoring = true;
        try {
            EditorGraph g = SfmlToGraph.parse(snapshot);
            LayoutMemory.apply(snapshot, g);
            // swap graph contents in place (graph is final)
            graph.name = g.name;
            graph.triggers.clear();
            graph.triggers.addAll(g.triggers);
            graph.selected = null;
            detached.clear();
            // detached statements were serialized as extra roots; SfmlToGraph keeps
            // them inside triggers, so nothing extra to restore here.
            float[] cam = LayoutMemory.readCamera(snapshot);
            if (cam != null) {
                panX = cam[0];
                panY = cam[1];
                zoom = Mth.clamp(cam[2], ZOOM_MIN, ZOOM_MAX);
            }
            dirty = true;
            activeField = null;
            // Parsed nodes are fresh instances, so any armed delete refs are stale.
            pendingDelete = null;
            pendingWireDelete = null;
            connectSource = null;
            rebuild();
        } finally {
            restoring = false;
        }
    }


    /**
     * #6: reload the visual graph from externally-edited SFML (e.g. after the user
     * edits the program in SFM's code editor and returns). Reparses the text into the
     * graph in place, applies any embedded layout, resolves overlaps for nodes that
     * came from plain code (no layout comments), and refreshes the UI.
     */
    public void reloadFromSfml(String sfml) {
        String text = sfml == null ? "" : sfml;
        restoring = true;
        try {
            EditorGraph g = SfmlToGraph.parse(text);
            LayoutMemory.apply(text, g);
            graph.name = g.name;
            graph.triggers.clear();
            graph.triggers.addAll(g.triggers);
            graph.selected = null;
            detached.clear();
            float[] cam = LayoutMemory.readCamera(text);
            if (cam != null) {
                panX = cam[0];
                panY = cam[1];
                zoom = Mth.clamp(cam[2], ZOOM_MIN, ZOOM_MAX);
            }
            // Reflect the new source as the baseline and recompute parse state.
            initialProgram = text;
            importWarning = computeImportWarning(text);
            parseFailed = false;
            if (!text.isBlank()) {
                boolean compiles = canonical(text) != null;
                if (!compiles || graph.triggers.isEmpty()) {
                    parseFailed = true;
                }
            }
            dirty = true;
            activeField = null;
            pendingDelete = null;
            pendingWireDelete = null;
            connectSource = null;
            rebuild();
            // Code from the text editor usually carries no layout comments; spread any
            // overlapping nodes so they're all readable (font is ready post-init).
            resolveOverlaps();
            layoutCache.clear();
        } finally {
            restoring = false;
        }
    }


    @Override
    public void onClose() {
        // No real edits: just leave without touching the disk or prompting.
        if (!dirty) {
            Minecraft.getInstance().setScreen(previousScreen);
            return;
        }
        String latest = generateSfml();
        if (isUnchanged(latest)) {
            Minecraft.getInstance().setScreen(previousScreen);
            return;
        }
        Minecraft.getInstance().setScreen(new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        save();
                    }
                    Minecraft.getInstance().setScreen(previousScreen);
                },
                EXIT_CONFIRM_TITLE.getComponent(),
                EXIT_CONFIRM_MESSAGE.getComponent(),
                Component.translatable("gui.sfmgui.node_editor.save_exit_yes"),
                Component.translatable("gui.sfmgui.node_editor.save_exit_no")
        ));
    }

    private boolean isUnchanged(String latest) {
        if (latest.equals(initialProgram)) {
            return true;
        }
        String before = canonical(initialProgram);
        String after = canonical(latest);
        return before != null && before.equals(after);
    }

    private static @Nullable String computeImportWarning(String initial) {
        if (initial == null || initial.isBlank()) {
            return null;
        }
        String before = canonical(initial);
        if (before == null) {
            SFMGui.LOGGER.warn("Visual editor: existing program failed to compile");
            return IMPORT_FAILED.getString();
        }
        try {
            EditorGraph parsed = SfmlToGraph.parse(initial);
            String after = canonical(GraphToSfml.generate(parsed));
            if (after == null || !before.equals(after)) {
                return IMPORT_LOSSY.getString();
            }
        } catch (Throwable t) {
            SFMGui.LOGGER.warn("Visual editor: import failed", t);
            return IMPORT_FAILED.getString();
        }
        return null;
    }

    private static @Nullable String canonical(String sfml) {
        try {
            var p = new ca.teamdman.sfml.program_builder.ProgramBuilder(sfml).useCache(false).build().program();
            return p == null ? null : p.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
