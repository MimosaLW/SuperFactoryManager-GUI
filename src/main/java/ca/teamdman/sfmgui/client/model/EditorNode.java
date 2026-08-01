package ca.teamdman.sfmgui.client.model;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Base class for every draggable node in the visual program editor.
 * <p>
 * Nodes hold their own canvas position (in "world"/graph space, independent of pan/zoom)
 * and a collapsed flag controlling whether their configuration body is shown.
 */
public abstract class EditorNode {
    private static int IdCounter = 0;

    /**
     * Stable identifier, unique within a single editor session.
     * Used for selection tracking and (later) connection references.
     */
    public final int id = IdCounter++;

    /** Position of the node's top-left corner in graph space. */
    public int x;
    public int y;

    /** When {@code true} the node renders as a compact header only. */
    public boolean collapsed = false;

    /**
     * The set of accordion menu indices this node currently has expanded.
     * Empty means none open. Multiple menus can be open at once and stay open
     * independently (opening one never collapses another). A {@link LinkedHashSet}
     * keeps a stable open-order for deterministic layout/persistence.
     */
    public final Set<Integer> openMenus = new LinkedHashSet<>();

    /** True when menu index {@code i} is currently expanded. */
    public boolean isMenuOpen(int i) {
        return openMenus.contains(i);
    }

    /** Toggle menu index {@code i} without affecting any other open menu. */
    public void toggleMenu(int i) {
        if (!openMenus.remove(i)) {
            openMenus.add(i);
        }
    }

    /** Open only menu {@code i} (used on selection defaults); -1 closes all. */
    public void setOnlyMenu(int i) {
        openMenus.clear();
        if (i >= 0) {
            openMenus.add(i);
        }
    }

    /** Optional free-text note shown next to the label/summary (persisted as a comment). */
    public String note = "";

    protected EditorNode(int x, int y) {
        this.x = x;
        this.y = y;
    }

    /** A short human-readable title shown in the node header. */
    public abstract String getTitle();
}
