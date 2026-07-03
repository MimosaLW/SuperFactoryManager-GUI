package ca.teamdman.sfmgui.client.codegen;

import ca.teamdman.sfmgui.client.model.Condition;
import ca.teamdman.sfmgui.client.model.EditorGraph;
import ca.teamdman.sfmgui.client.model.ForgetStatementNode;
import ca.teamdman.sfmgui.client.model.IOStatementNode;
import ca.teamdman.sfmgui.client.model.IfStatementNode;
import ca.teamdman.sfmgui.client.model.LabelAccessData;
import ca.teamdman.sfmgui.client.model.RawStatementNode;
import ca.teamdman.sfmgui.client.model.ResourceLimitData;
import ca.teamdman.sfmgui.client.model.StatementNode;
import ca.teamdman.sfmgui.client.model.TriggerNode;
import ca.teamdman.sfmgui.client.model.WithClause;
import ca.teamdman.sfml.ast.Block;
import ca.teamdman.sfml.ast.BoolExpr;
import ca.teamdman.sfml.ast.BoolFalse;
import ca.teamdman.sfml.ast.BoolHas;
import ca.teamdman.sfml.ast.BoolRedstone;
import ca.teamdman.sfml.ast.BoolTrue;
import ca.teamdman.sfml.ast.ComparisonOperator;
import ca.teamdman.sfml.ast.ForgetStatement;
import ca.teamdman.sfml.ast.IfStatement;
import ca.teamdman.sfml.ast.InputStatement;
import ca.teamdman.sfml.ast.Interval;
import ca.teamdman.sfml.ast.Label;
import ca.teamdman.sfml.ast.LabelAccess;
import ca.teamdman.sfml.ast.Limit;
import ca.teamdman.sfml.ast.NumberRange;
import ca.teamdman.sfml.ast.OutputStatement;
import ca.teamdman.sfml.ast.Program;
import ca.teamdman.sfml.ast.ResourceLimit;
import ca.teamdman.sfml.ast.ResourceLimits;
import ca.teamdman.sfml.ast.ResourceQuantity;
import ca.teamdman.sfml.ast.RoundRobin;
import ca.teamdman.sfml.ast.SetOperator;
import ca.teamdman.sfml.ast.Side;
import ca.teamdman.sfml.ast.SideQualifier;
import ca.teamdman.sfml.ast.Statement;
import ca.teamdman.sfml.ast.TimerTrigger;
import ca.teamdman.sfml.ast.Trigger;
import ca.teamdman.sfml.program_builder.ProgramBuilder;

import java.util.List;

/**
 * Reconstructs an {@link EditorGraph} from existing SFML source.
 * <p>
 * The parser is best-effort structural: triggers, INPUT/OUTPUT/FORGET/IF and the
 * common resource-limit / label-access / condition shapes are decomposed into
 * structured editor nodes. Anything that cannot be represented exactly is stored
 * verbatim in a {@link RawStatementNode} so it round-trips losslessly.
 * <p>
 * Callers should still verify faithfulness (compare canonical AST before/after)
 * because AST accessors below rely on {@code toString()} for some sub-parts.
 */
public final class SfmlToGraph {
    private SfmlToGraph() {
    }

    private static final int COL_TRIGGER_X = 40;
    private static final int COL_STATEMENT_X = 220;
    private static final int ROW_STEP = 70;

    /**
     * Parse the given SFML into an {@link EditorGraph}. If the program cannot be
     * compiled at all, returns an empty graph (the caller will show the raw text
     * and/or warnings). Never throws.
     */
    public static EditorGraph parse(String sfml) {
        EditorGraph graph = new EditorGraph();
        if (sfml == null || sfml.isBlank()) {
            // A blank disk has no program name; keep it empty so a round trip does
            // not inject a spurious NAME into the user's disk.
            graph.name = "";
            return graph;
        }
        Program program;
        try {
            program = new ProgramBuilder(sfml).useCache(false).build().program();
        } catch (Throwable t) {
            return graph;
        }
        if (program == null) {
            return graph;
        }
        // Adopt the program's actual name verbatim (may be empty) for a faithful round trip.
        graph.name = program.name() == null ? "" : program.name();

        int triggerY = 20;
        for (Trigger trigger : program.triggers()) {
            TriggerNode node = mapTrigger(trigger, COL_TRIGGER_X, triggerY);
            graph.addTrigger(node);
            int stmtY = triggerY;
            for (StatementNode stmt : node.statements) {
                stmt.x = COL_STATEMENT_X;
                stmt.y = stmtY;
                stmtY += ROW_STEP;
            }
            triggerY = Math.max(triggerY + ROW_STEP, stmtY) + 20;
        }
        return graph;
    }

    private static TriggerNode mapTrigger(Trigger trigger, int x, int y) {
        if (trigger instanceof TimerTrigger timer) {
            TriggerNode node = new TriggerNode(x, y, TriggerNode.Kind.TIMER);
            Interval interval = timer.interval();
            node.interval = interval.ticks();
            node.unit = TriggerNode.TimeUnit.TICKS;
            node.global = interval.alignment() == Interval.IntervalAlignment.GLOBAL;
            node.offset = interval.offset();
            // A sub-20-tick interval is only valid for Forge-Energy-only timers, so
            // reopen such programs in power-transfer mode (reverse-infer, no extra tag).
            node.powerTransfer = interval.ticks() < 20;
            mapBlockInto(timer.block(), node.statements);
            return node;
        }
        // RedstoneTrigger and any unknown trigger types map to a pulse node when possible.
        TriggerNode node = new TriggerNode(x, y, TriggerNode.Kind.REDSTONE_PULSE);
        Block block = trigger.getBlock();
        if (block != null) {
            mapBlockInto(block, node.statements);
        }
        return node;
    }

    private static void mapBlockInto(Block block, List<StatementNode> out) {
        for (Statement statement : block.getStatements()) {
            out.add(mapStatement(statement));
        }
    }

    private static StatementNode mapStatement(Statement statement) {
        try {
            if (statement instanceof InputStatement input) {
                return mapIo(StatementNode.Kind.INPUT, input.labelAccess(), input.resourceLimits(), input.each(), false);
            }
            if (statement instanceof OutputStatement output) {
                return mapIo(StatementNode.Kind.OUTPUT, output.labelAccess(), output.resourceLimits(),
                        output.each(), output.emptySlotsOnly());
            }
            if (statement instanceof ForgetStatement forget) {
                ForgetStatementNode node = new ForgetStatementNode(0, 0);
                node.labels = forget.labelToForget().stream().map(Label::name).reduce((a, b) -> a + ", " + b).orElse("");
                return node;
            }
            if (statement instanceof IfStatement ifStatement) {
                return mapIf(ifStatement);
            }
        } catch (Throwable t) {
            // fall through to raw
        }
        // Fallback: keep the exact source so nothing is lost.
        return new RawStatementNode(0, 0, statement.toString());
    }

    private static IOStatementNode mapIo(
            StatementNode.Kind kind,
            LabelAccess labelAccess,
            ResourceLimits resourceLimits,
            boolean each,
            boolean emptySlotsOnly
    ) {
        IOStatementNode node = new IOStatementNode(0, 0, kind);
        node.each = each;
        node.emptySlotsOnly = emptySlotsOnly;
        mapLabelAccess(labelAccess, node.labelAccess);
        mapResourceLimits(resourceLimits, node);
        return node;
    }

    private static void mapLabelAccess(LabelAccess labelAccess, LabelAccessData out) {
        out.labels = labelAccess.labels().stream().map(Label::name).reduce((a, b) -> a + ", " + b).orElse("");
        RoundRobin rr = labelAccess.roundRobin();
        out.roundRobin = switch (rr.getBehaviour()) {
            case BY_LABEL -> LabelAccessData.RoundRobin.BY_LABEL;
            case BY_BLOCK -> LabelAccessData.RoundRobin.BY_BLOCK;
            case UNMODIFIED -> LabelAccessData.RoundRobin.NONE;
        };
        SideQualifier sides = labelAccess.sides();
        if (sides.equals(SideQualifier.ALL)) {
            out.eachSide = true;
        } else if (!sides.equals(SideQualifier.NULL)) {
            for (Side side : sides.sides()) {
                if (side == Side.NULL) {
                    continue;
                }
                try {
                    out.sides.add(LabelAccessData.SideOption.valueOf(side.name()));
                } catch (IllegalArgumentException ignored) {
                    // unknown side name; skip
                }
            }
        }
        // slots
        NumberRange[] ranges = labelAccess.slots().ranges();
        if (ranges.length != 1 || !ranges[0].equals(NumberRange.MAX_RANGE)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ranges.length; i++) {
                if (i > 0) {
                    sb.append(",");
                }
                NumberRange r = ranges[i];
                if (r.start() == r.end()) {
                    sb.append(r.start());
                } else {
                    sb.append(r.start()).append("-").append(r.end());
                }
            }
            out.slots = sb.toString();
        }
    }

    private static void mapResourceLimits(ResourceLimits resourceLimits, IOStatementNode node) {
        // EXCEPT (statement-wide exclusions)
        if (!resourceLimits.exclusions().isEmpty()) {
            node.except = resourceLimits.exclusions().stream()
                    .map(id -> id.toStringCondensed())
                    .reduce((a, b) -> a + ", " + b).orElse("");
        }
        for (ResourceLimit rl : resourceLimits.resourceLimitList()) {
            node.limits.add(mapResourceLimit(rl));
        }
    }

    private static ResourceLimitData mapResourceLimit(ResourceLimit rl) {
        ResourceLimitData data = new ResourceLimitData();
        // Map any WITH clause structurally; a trivial "always true" filter means none.
        var with = rl.with();
        if (with != null && with != ca.teamdman.sfml.ast.With.ALWAYS_TRUE
            && !(with.condition() instanceof ca.teamdman.sfml.ast.WithAlwaysTrue)) {
            mapWithInto(with, data.with);
        }
        Limit limit = rl.limit();
        ResourceQuantity quantity = limit.quantity();
        ResourceQuantity retention = limit.retention();

        // quantity: only emit if it is a real (non-max, set) value
        if (quantity != null && quantity != ResourceQuantity.UNSET && quantity.number() != null) {
            long v = quantity.number().value();
            if (v != Long.MAX_VALUE) {
                data.quantity = String.valueOf(v);
            }
            data.quantityEach = quantity.idExpansionBehaviour() == ResourceQuantity.IdExpansionBehaviour.EXPAND;
        }
        // retention: only emit if non-zero and set
        if (retention != null && retention != ResourceQuantity.UNSET && retention.number() != null) {
            long v = retention.number().value();
            if (v != 0 && v != Long.MAX_VALUE) {
                data.retain = String.valueOf(v);
            }
            data.retainEach = retention.idExpansionBehaviour() == ResourceQuantity.IdExpansionBehaviour.EXPAND;
        }
        // resources
        if (!rl.resourceIds().isEmpty()) {
            String condensed = rl.resourceIds().toStringCondensed(); // already "a OR b"
            // Represent OR-joined resources as comma-separated in the model (codegen re-joins with OR).
            data.resources = condensed.replace(" OR ", ", ");
        }
        return data;
    }

    /** Thrown internally to signal a construct that must fall back to raw SFML. */
    private static final class UnmappableException extends RuntimeException {
    }

    private static boolean isTrivialWith(ca.teamdman.sfml.ast.With with) {
        return with == null
               || with == ca.teamdman.sfml.ast.With.ALWAYS_TRUE
               || with.condition() instanceof ca.teamdman.sfml.ast.WithAlwaysTrue;
    }

    private static void mapWithInto(ca.teamdman.sfml.ast.With with, ca.teamdman.sfmgui.client.model.WithData out) {
        out.without = with.mode() == ca.teamdman.sfml.ast.With.WithMode.WITHOUT;
        out.clause = mapWithClause(with.condition());
    }

    private static WithClause mapWithClause(ca.teamdman.sfml.ast.WithClause clause) {
        if (clause instanceof ca.teamdman.sfml.ast.With inner) {
            // nested top-level With; descend into its condition
            return mapWithClause(inner.condition());
        }
        if (clause instanceof ca.teamdman.sfml.ast.WithTag tag) {
            return WithClause.tag(tag.tagMatcher().toString());
        }
        if (clause instanceof ca.teamdman.sfml.ast.WithNegation neg) {
            WithClause c = new WithClause();
            c.kind = WithClause.Kind.NOT;
            c.child = mapWithClause(neg.inner());
            return c;
        }
        if (clause instanceof ca.teamdman.sfml.ast.WithConjunction and) {
            WithClause c = new WithClause();
            c.kind = WithClause.Kind.AND;
            c.left = mapWithClause(and.left());
            c.right = mapWithClause(and.right());
            return c;
        }
        if (clause instanceof ca.teamdman.sfml.ast.WithDisjunction or) {
            WithClause c = new WithClause();
            c.kind = WithClause.Kind.OR;
            c.left = mapWithClause(or.left());
            c.right = mapWithClause(or.right());
            return c;
        }
        if (clause instanceof ca.teamdman.sfml.ast.WithParen paren) {
            return mapWithClause(paren.inner());
        }
        // Unknown clause type: signal raw fallback for the whole statement.
        throw new UnmappableException();
    }

    private static IfStatementNode mapIf(IfStatement ifStatement) {
        IfStatementNode node = new IfStatementNode(0, 0);
        mapCondition(ifStatement.condition(), node.condition);
        mapBlockInto(ifStatement.trueBlock(), node.thenBranch.statements);
        List<Statement> falseStatements = ifStatement.falseBlock().getStatements();
        if (!falseStatements.isEmpty()) {
            node.hasElse = true;
            mapBlockInto(ifStatement.falseBlock(), node.elseBranch.statements);
        }
        return node;
    }

    private static void mapCondition(BoolExpr expr, Condition out) {
        if (expr instanceof BoolTrue) {
            out.kind = Condition.Kind.TRUE;
        } else if (expr instanceof BoolFalse) {
            out.kind = Condition.Kind.FALSE;
        } else if (expr instanceof BoolRedstone redstone) {
            out.kind = Condition.Kind.REDSTONE;
            out.redstoneHasComparison = true;
            out.comparison = mapComparison(redstone.operator());
            out.count = String.valueOf(redstone.number());
        } else if (expr instanceof BoolHas has
                   && isTrivialWith(has.with())
                   && has.except().isEmpty()) {
            out.kind = Condition.Kind.HAS;
            out.setOp = mapSetOp(has.setOperator());
            out.labels = has.labelAccess().labels().stream().map(Label::name)
                    .reduce((a, b) -> a + ", " + b).orElse("");
            out.comparison = mapComparison(has.comparisonOperator());
            out.count = String.valueOf(has.quantity());
            if (!has.resourceIdSet().isEmpty()) {
                out.resource = has.resourceIdSet().toStringCondensed().replace(" OR ", ", ");
            }
        } else {
            // Complex/unsupported condition (AND/OR/NOT/paren, WITH/EXCEPT):
            // signal the caller to keep the whole IF statement as raw SFML, which
            // uses IfStatement#toString() and is guaranteed to re-parse.
            throw new UnmappableException();
        }
    }

    private static Condition.Comparison mapComparison(ComparisonOperator op) {
        // ComparisonOperator.toString() yields the symbolic form (>, <, =, >=, <=)
        String s = op.toString().trim();
        return switch (s) {
            case ">" -> Condition.Comparison.GT;
            case "<" -> Condition.Comparison.LT;
            case "=" -> Condition.Comparison.EQ;
            case ">=" -> Condition.Comparison.GE;
            case "<=" -> Condition.Comparison.LE;
            default -> Condition.Comparison.GE;
        };
    }

    private static Condition.SetOp mapSetOp(SetOperator op) {
        try {
            return Condition.SetOp.valueOf(op.name());
        } catch (IllegalArgumentException e) {
            return Condition.SetOp.OVERALL;
        }
    }
}
