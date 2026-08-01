package ca.teamdman.sfmgui.client.model;

/**
 * A {@code with} filter tree used by resource limits and HAS conditions.
 * <p>
 * Maps to the SFML {@code withClause} rule: tag matching combined with
 * AND / OR / NOT / parentheses. The top-level {@link WithData} chooses between
 * {@code WITH} and {@code WITHOUT} (the latter negates the whole expression).
 */
public class WithClause {
    public enum Kind {
        /** {@code TAG <matcher>} / {@code #<matcher>}. */
        TAG,
        NOT,
        AND,
        OR
    }

    public Kind kind = Kind.TAG;

    /**
     * Tag matcher text for {@link Kind#TAG}, e.g. {@code "forge:ingots"},
     * {@code "*:ingots"}, {@code "refinedstorage:disks/items/*"}. The leading
     * {@code #} / {@code TAG} keyword is added by codegen.
     */
    public String tag = "";

    public WithClause left;
    public WithClause right;
    public WithClause child;

    public static WithClause tag(String tag) {
        WithClause c = new WithClause();
        c.kind = Kind.TAG;
        c.tag = tag;
        return c;
    }
}
