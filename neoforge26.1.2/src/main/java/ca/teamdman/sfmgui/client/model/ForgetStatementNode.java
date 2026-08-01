package ca.teamdman.sfmgui.client.model;

/**
 * A {@code FORGET [label, ...]} statement node.
 * <p>
 * Clears previously accumulated INPUT state. A blank {@link #labels} forgets everything.
 */
public class ForgetStatementNode extends StatementNode {
    /** Comma-separated label list; blank = forget all. */
    public String labels = "";

    public ForgetStatementNode(int x, int y) {
        super(x, y, Kind.FORGET);
    }

    @Override
    public String getTitle() {
        return "Forget";
    }
}
