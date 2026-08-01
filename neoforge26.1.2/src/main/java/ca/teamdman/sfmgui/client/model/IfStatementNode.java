package ca.teamdman.sfmgui.client.model;

import java.util.ArrayList;
import java.util.List;

/**
 * An {@code IF <condition> THEN ... [ELSE ...] END} statement node.
 * <p>
 * The THEN and ELSE branches are statement containers holding their own ordered
 * statement lists. {@code ELSE IF} chains are represented naturally by nesting
 * another {@link IfStatementNode} inside the {@link #elseBranch}; the codegen
 * collapses that back into {@code ELSE IF} form when possible.
 */
public class IfStatementNode extends StatementNode {
    /** Whether an ELSE branch is present/emitted. */
    public boolean hasElse = false;

    public final Condition condition = Condition.has("");

    public final Branch thenBranch = new Branch("THEN");
    public final Branch elseBranch = new Branch("ELSE");

    public IfStatementNode(int x, int y) {
        super(x, y, Kind.IF);
    }

    @Override
    public String getTitle() {
        return "If";
    }

    /** A THEN/ELSE branch: an ordered statement list acting as a container. */
    public static class Branch implements StatementContainer {
        private final String label;
        public final List<StatementNode> statements = new ArrayList<>();

        public Branch(String label) {
            this.label = label;
        }

        @Override
        public List<StatementNode> getChildStatements() {
            return statements;
        }

        @Override
        public String getContainerLabel() {
            return label;
        }
    }
}
