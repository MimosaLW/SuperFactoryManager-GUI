package ca.teamdman.sfmgui.client.model;

/**
 * Base class for statement nodes that live inside a {@link TriggerNode}'s block.
 * <p>
 * The execution order of statements is determined by their order in
 * {@link TriggerNode#statements}; the {@link #x}/{@link #y} coordinates are purely
 * for visual placement on the canvas.
 */
public abstract class StatementNode extends EditorNode {
    public enum Kind {
        INPUT,
        OUTPUT,
        FORGET,
        IF,
        RAW
    }

    public final Kind kind;

    protected StatementNode(int x, int y, Kind kind) {
        super(x, y);
        this.kind = kind;
    }
}
