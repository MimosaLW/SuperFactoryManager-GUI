package ca.teamdman.sfmgui.client.model;

import java.util.EnumSet;

/**
 * The label-access configuration shared by INPUT/OUTPUT statements:
 * which labels, in what round-robin mode, restricted to which sides and slots.
 * <p>
 * Maps to the SFML {@code labelAccess} rule:
 * {@code label (, label)* roundrobin? sidequalifier? slotqualifier?}
 */
public class LabelAccessData {
    public enum RoundRobin {
        NONE,
        BY_LABEL,
        BY_BLOCK
    }

    /**
     * Sides usable in a {@code sidequalifier}. Absolute faces plus block-relative
     * faces; {@code NULL} is the default no-direction handle.
     */
    public enum SideOption {
        TOP, BOTTOM, NORTH, EAST, SOUTH, WEST, LEFT, RIGHT, FRONT, BACK, NULL
    }

    /** Comma-separated label list as typed; codegen quotes entries that need it. */
    public String labels = "";

    public RoundRobin roundRobin = RoundRobin.NONE;

    /**
     * When empty, no side qualifier is emitted. When it contains every option,
     * codegen may emit {@code EACH SIDE}. Otherwise emits the listed sides.
     */
    public final EnumSet<SideOption> sides = EnumSet.noneOf(SideOption.class);

    /** When {@code true}, emit {@code EACH SIDE} regardless of {@link #sides}. */
    public boolean eachSide = false;

    /**
     * Slot range specification as typed, e.g. {@code "0,1,3-4,7-9"}.
     * Blank means no slot qualifier (all slots).
     */
    public String slots = "";
}
