package ca.teamdman.sfmgui.client.model;

/**
 * A single resource limit within an INPUT/OUTPUT statement's limit list.
 * <p>
 * Maps to the SFML {@code resourceLimit} rule:
 * {@code [quantity] [RETAIN retain] [resourceIds] [with]} where quantity/retain
 * may each carry an {@code EACH} flag, resource ids are OR-joined, and an
 * optional {@code with}/{@code without} tag filter applies.
 */
public class ResourceLimitData {
    /** Quantity as typed; blank means no explicit quantity. */
    public String quantity = "";
    /** {@code EACH} flag on the quantity. */
    public boolean quantityEach = false;

    /** Retain amount as typed; blank means no RETAIN. */
    public String retain = "";
    /** {@code EACH} flag on the retain amount. */
    public boolean retainEach = false;

    /**
     * Resource id disjunction as typed, OR-separated by commas in the UI and
     * emitted with {@code OR}, e.g. {@code "minecraft:stone, *ingot*"}.
     * Blank means no resource id (subject to quantity only).
     */
    public String resources = "";

    /** Optional tag filter. */
    public final WithData with = new WithData();

    public boolean isEmpty() {
        return blank(quantity) && blank(retain) && blank(resources) && !with.isPresent();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
