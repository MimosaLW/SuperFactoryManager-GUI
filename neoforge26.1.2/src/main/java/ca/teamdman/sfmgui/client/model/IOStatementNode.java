package ca.teamdman.sfmgui.client.model;

import java.util.ArrayList;
import java.util.List;

/**
 * An {@code INPUT ... FROM} or {@code OUTPUT ... TO} statement node.
 * <p>
 * Full SFML surface:
 * <pre>
 *   INPUT  [limits] [EXCEPT excludes] FROM [EACH] labelAccess
 *   OUTPUT [limits] [EXCEPT excludes] TO [EMPTY SLOTS IN] [EACH] labelAccess
 * </pre>
 * where {@code limits} is a comma-separated {@link ResourceLimitData} list,
 * {@code excludes} is a statement-wide resource-id exclusion list, and
 * {@code labelAccess} carries labels, round robin, sides and slots.
 */
public class IOStatementNode extends StatementNode {
    /** One or more resource limits. Empty list = no limit (matches everything). */
    public final List<ResourceLimitData> limits = new ArrayList<>();

    /**
     * Statement-wide EXCEPT list, comma-separated resource ids as typed.
     * Blank means no exclusion. Note: EXCEPT does not support WITH clauses.
     */
    public String except = "";

    /** {@code EACH} keyword before the label access. */
    public boolean each = false;

    /** OUTPUT-only: emit {@code EMPTY SLOTS IN} to only fill empty slots. */
    public boolean emptySlotsOnly = false;

    /** Target label access (labels, round robin, sides, slots). */
    public final LabelAccessData labelAccess = new LabelAccessData();

    public IOStatementNode(int x, int y, Kind kind) {
        super(x, y, kind);
        if (kind != Kind.INPUT && kind != Kind.OUTPUT) {
            throw new IllegalArgumentException("IOStatementNode only supports INPUT/OUTPUT, got " + kind);
        }
    }

    /** Convenience accessor for the labels string (used widely in the UI/summary). */
    public String labels() {
        return labelAccess.labels;
    }

    @Override
    public String getTitle() {
        return kind == Kind.INPUT ? "Input" : "Output";
    }
}
