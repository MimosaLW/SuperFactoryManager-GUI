package ca.teamdman.sfmgui.client.model;

import java.util.List;

/**
 * Something that owns an ordered list of statements: a trigger block, or one of
 * the branches of an {@link IfStatementNode}.
 * <p>
 * Statement execution order within SFML is determined by list order here, not by
 * canvas coordinates.
 */
public interface StatementContainer {
    /** The direct child statements of this container, in execution order. */
    List<StatementNode> getChildStatements();

    /** A short label describing this container, used in the UI. */
    String getContainerLabel();
}
