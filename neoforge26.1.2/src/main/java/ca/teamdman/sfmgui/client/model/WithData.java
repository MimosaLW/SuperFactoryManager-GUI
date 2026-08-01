package ca.teamdman.sfmgui.client.model;

import org.jetbrains.annotations.Nullable;

/**
 * A {@code WITH} / {@code WITHOUT} filter attached to a resource limit or HAS
 * condition. {@code WITHOUT} negates the whole {@link #clause} expression.
 */
public class WithData {
    public boolean without = false;

    /** The filter expression; {@code null} means no WITH filter is present. */
    public @Nullable WithClause clause = null;

    public boolean isPresent() {
        return clause != null;
    }
}
