package ca.teamdman.sfmgui.client.model;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The complete visual program: a name plus a collection of trigger nodes,
 * each owning a (possibly nested) tree of statements.
 * <p>
 * This is the editor's source of truth. It is converted to SFML text by
 * {@code GraphToSfml} on save, and (in a later milestone) reconstructed from
 * existing SFML by {@code SfmlToGraph} on open.
 */
public class EditorGraph {
    /** Program name emitted as {@code NAME "..."}. */
    public String name = "My Program";

    /** Root trigger nodes. */
    public final List<TriggerNode> triggers = new ArrayList<>();

    /** Currently selected node, or {@code null}. */
    public @Nullable EditorNode selected = null;

    public void addTrigger(TriggerNode trigger) {
        triggers.add(trigger);
    }

    /**
     * Remove a node from the graph. Triggers are removed from the root list;
     * statements are searched for recursively across every container.
     */
    public void removeNode(EditorNode node) {
        if (node instanceof TriggerNode trigger) {
            triggers.remove(trigger);
        } else if (node instanceof StatementNode statement) {
            for (TriggerNode trigger : triggers) {
                if (removeStatementFrom(trigger, statement)) {
                    break;
                }
            }
        }
        if (selected == node) {
            selected = null;
        }
    }

    private boolean removeStatementFrom(StatementContainer container, StatementNode target) {
        List<StatementNode> children = container.getChildStatements();
        if (children.remove(target)) {
            return true;
        }
        for (StatementNode child : children) {
            for (StatementContainer sub : subContainers(child)) {
                if (removeStatementFrom(sub, target)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The direct child containers of a statement node (IF has THEN/ELSE). */
    public static List<StatementContainer> subContainers(StatementNode node) {
        if (node instanceof IfStatementNode ifNode) {
            return List.of(ifNode.thenBranch, ifNode.elseBranch);
        }
        return List.of();
    }

    /**
     * Find the container that directly owns the given statement, or {@code null}.
     */
    public @Nullable StatementContainer findOwningContainer(StatementNode statement) {
        for (TriggerNode trigger : triggers) {
            StatementContainer found = findOwningContainer(trigger, statement);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private @Nullable StatementContainer findOwningContainer(StatementContainer container, StatementNode target) {
        if (container.getChildStatements().contains(target)) {
            return container;
        }
        for (StatementNode child : container.getChildStatements()) {
            for (StatementContainer sub : subContainers(child)) {
                StatementContainer found = findOwningContainer(sub, target);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** All nodes in the graph, depth-first, for rendering/iteration. */
    public List<EditorNode> allNodes() {
        List<EditorNode> rtn = new ArrayList<>();
        for (TriggerNode trigger : triggers) {
            rtn.add(trigger);
            collectStatements(trigger, rtn);
        }
        return rtn;
    }

    private void collectStatements(StatementContainer container, List<EditorNode> out) {
        for (StatementNode child : container.getChildStatements()) {
            out.add(child);
            for (StatementContainer sub : subContainers(child)) {
                collectStatements(sub, out);
            }
        }
    }
}
