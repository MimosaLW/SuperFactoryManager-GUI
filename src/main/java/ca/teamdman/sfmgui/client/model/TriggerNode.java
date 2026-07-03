package ca.teamdman.sfmgui.client.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A trigger node is the root of an execution block.
 * It owns an ordered list of statements that run when the trigger fires.
 * <p>
 * SFML equivalents:
 * <pre>
 *   EVERY 20 TICKS DO ... END        (TIMER)
 *   EVERY REDSTONE PULSE DO ... END  (PULSE)
 * </pre>
 */
public class TriggerNode extends EditorNode implements StatementContainer {
    public enum Kind {
        TIMER,
        REDSTONE_PULSE
    }

    public enum TimeUnit {
        TICKS,
        SECONDS
    }

    public Kind kind;

    // Timer-specific configuration (ignored when kind == REDSTONE_PULSE).
    public int interval = 20;
    public TimeUnit unit = TimeUnit.TICKS;
    public boolean global = false;
    /** Phase offset ({@code EVERY 20 PLUS <offset> TICKS}); 0 disables. */
    public int offset = 0;
    /**
     * Power-transfer mode: SFM allows a timer down to 1 tick when the block does
     * only Forge Energy I/O. When enabled the editor lifts the usual 20-tick
     * minimum to 1. Purely a UI mode — it does not change the emitted SFML syntax.
     */
    public boolean powerTransfer = false;

    /** Statements executed, in order, inside this trigger's block. */
    public final List<StatementNode> statements = new ArrayList<>();

    public TriggerNode(int x, int y, Kind kind) {
        super(x, y);
        this.kind = kind;
    }

    @Override
    public List<StatementNode> getChildStatements() {
        return statements;
    }

    @Override
    public String getContainerLabel() {
        return getTitle();
    }

    @Override
    public String getTitle() {
        return switch (kind) {
            case TIMER -> "Timer Trigger";
            case REDSTONE_PULSE -> "Redstone Pulse Trigger";
        };
    }
}
