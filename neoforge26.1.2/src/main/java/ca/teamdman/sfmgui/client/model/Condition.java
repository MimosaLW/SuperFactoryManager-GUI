package ca.teamdman.sfmgui.client.model;

/**
 * A boolean condition used by {@link IfStatementNode} and (later) redstone-gated
 * logic. Maps to the SFML {@code boolexpr} grammar rule.
 * <p>
 * M2 models the most common leaf forms plus boolean combinators. The full
 * condition builder (nested parens, set operators, WITH filters, EXCEPT) is
 * expanded in M3; the structure here is intentionally a small recursive tree so
 * it can grow without a rewrite.
 */
public class Condition {
    public enum Kind {
        /** Literal {@code TRUE}. */
        TRUE,
        /** Literal {@code FALSE}. */
        FALSE,
        /** {@code [setOp] <labels> HAS <cmp> <count> [resource]}. */
        HAS,
        /** {@code REDSTONE [<cmp> <count>]}. */
        REDSTONE,
        /** {@code NOT <child>}. */
        NOT,
        /** {@code <left> AND <right>}. */
        AND,
        /** {@code <left> OR <right>}. */
        OR,
        /**
         * A verbatim boolean expression kept as-is (used when reverse-parsing a
         * condition the structured editor cannot represent). Emitted unchanged.
         */
        RAW
    }

    public enum SetOp {
        OVERALL, SOME, EVERY, EACH, ONE, LONE
    }

    public enum Comparison {
        GT(">"), LT("<"), EQ("="), GE(">="), LE("<=");

        public final String symbol;

        Comparison(String symbol) {
            this.symbol = symbol;
        }
    }

    public Kind kind = Kind.TRUE;

    // HAS fields
    public SetOp setOp = SetOp.OVERALL;
    public String labels = "";
    public Comparison comparison = Comparison.GE;
    public String count = "1";
    public String resource = "";
    /** Optional WITH tag filter on the HAS condition. */
    public final WithData with = new WithData();
    /** Optional EXCEPT resource-id list on the HAS condition, comma-separated. */
    public String except = "";

    // REDSTONE fields (reuses comparison + count; hasComparison toggles the optional part)
    public boolean redstoneHasComparison = true;

    // Combinator children
    public Condition left;
    public Condition right;
    public Condition child;

    /** Verbatim boolexpr text for {@link Kind#RAW}. */
    public String raw = "TRUE";

    public Condition() {
    }

    public static Condition has(String labels) {
        Condition c = new Condition();
        c.kind = Kind.HAS;
        c.labels = labels;
        return c;
    }
}
