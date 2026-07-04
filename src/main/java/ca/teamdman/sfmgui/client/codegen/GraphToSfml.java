package ca.teamdman.sfmgui.client.codegen;

import ca.teamdman.sfmgui.client.model.Condition;
import ca.teamdman.sfmgui.client.model.EditorGraph;
import ca.teamdman.sfmgui.client.model.ForgetStatementNode;
import ca.teamdman.sfmgui.client.model.IOStatementNode;
import ca.teamdman.sfmgui.client.model.IfStatementNode;
import ca.teamdman.sfmgui.client.model.LabelAccessData;
import ca.teamdman.sfmgui.client.model.RawStatementNode;
import ca.teamdman.sfmgui.client.model.ResourceLimitData;
import ca.teamdman.sfmgui.client.model.StatementContainer;
import ca.teamdman.sfmgui.client.model.StatementNode;
import ca.teamdman.sfmgui.client.model.TriggerNode;
import ca.teamdman.sfmgui.client.model.WithClause;
import ca.teamdman.sfmgui.client.model.WithData;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Converts an {@link EditorGraph} into SFML source text.
 * <p>
 * The output is plain SFML that is fed through the normal {@code ProgramBuilder}
 * pipeline for compilation/validation, so the visual editor never needs to
 * construct AST objects directly.
 */
public final class GraphToSfml {
    private GraphToSfml() {
    }

    /**
     * A label token is safe to emit bare (no quotes) when it matches the SFML
     * IDENTIFIER lexer rule: starts with a letter/underscore/{@code *} and
     * contains only letters, digits, underscores and {@code *}.
     */
    private static final Pattern BARE_IDENTIFIER = Pattern.compile("[a-zA-Z_*][a-zA-Z0-9_*]*");

    private static final String INDENT = "    ";

    public static String generate(EditorGraph graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("name ").append(quote(graph.name)).append("\n\n");
        for (TriggerNode trigger : graph.triggers) {
            appendTrigger(sb, trigger);
            sb.append("\n");
        }
        return sb.toString().stripTrailing() + "\n";
    }

    private static void appendTrigger(StringBuilder sb, TriggerNode trigger) {
        switch (trigger.kind) {
            case TIMER -> {
                sb.append("every ");
                // SFM's default minimum trigger interval is 20 ticks, but it drops to 1
                // when the block does only Forge Energy I/O. In power-transfer mode we
                // therefore allow down to 1 tick; otherwise clamp to 20 so a normal
                // program always compiles. SECONDS mode is always >= 1 effective second.
                int interval = trigger.interval;
                if (trigger.unit != TriggerNode.TimeUnit.SECONDS) {
                    interval = Math.max(trigger.powerTransfer ? 1 : 20, interval);
                } else {
                    interval = Math.max(1, interval);
                }
                sb.append(interval);
                if (trigger.global) {
                    sb.append(" global");
                }
                if (trigger.offset > 0) {
                    sb.append(" plus ").append(trigger.offset);
                }
                sb.append(" ");
                sb.append(trigger.unit == TriggerNode.TimeUnit.SECONDS ? "seconds" : "ticks");
                sb.append(" do\n");
            }
            case REDSTONE_PULSE -> sb.append("every redstone pulse do\n");
        }
        appendStatements(sb, trigger, 1);
        sb.append("end\n");
    }

    private static void appendStatements(StringBuilder sb, StatementContainer container, int depth) {
        String indent = INDENT.repeat(depth);
        for (StatementNode statement : container.getChildStatements()) {
            if (statement instanceof IfStatementNode ifNode) {
                appendIf(sb, ifNode, depth);
            } else if (statement instanceof RawStatementNode raw) {
                for (String line : raw.raw.split("\n", -1)) {
                    if (!line.isBlank()) {
                        sb.append(indent).append(line.strip()).append("\n");
                    }
                }
            } else {
                String line = statementToSfml(statement);
                if (!line.isBlank()) {
                    sb.append(indent).append(line).append("\n");
                }
            }
        }
    }

    private static void appendIf(StringBuilder sb, IfStatementNode ifNode, int depth) {
        String indent = INDENT.repeat(depth);
        sb.append(indent).append("if ").append(conditionToSfml(ifNode.condition)).append(" then\n");
        appendStatements(sb, ifNode.thenBranch, depth + 1);
        if (ifNode.hasElse && !ifNode.elseBranch.statements.isEmpty()) {
            sb.append(indent).append("else\n");
            appendStatements(sb, ifNode.elseBranch, depth + 1);
        }
        sb.append(indent).append("end\n");
    }

    private static String statementToSfml(StatementNode statement) {
        if (statement instanceof IOStatementNode io) {
            return ioToSfml(io);
        } else if (statement instanceof ForgetStatementNode forget) {
            return forgetToSfml(forget);
        }
        return "";
    }

    // ----- IO statements -----
    private static String ioToSfml(IOStatementNode io) {
        StringBuilder sb = new StringBuilder();
        boolean isInput = io.kind == StatementNode.Kind.INPUT;
        sb.append(isInput ? "input" : "output");

        String limits = buildLimitList(io);
        if (!limits.isEmpty()) {
            sb.append(" ").append(limits);
        }

        String except = buildResourceIdList(io.except);
        if (!except.isEmpty()) {
            sb.append(" except ").append(except);
        }

        sb.append(isInput ? " from" : " to");

        if (!isInput && io.emptySlotsOnly) {
            sb.append(" empty slots in");
        }
        if (io.each) {
            sb.append(" each");
        }

        sb.append(" ").append(labelAccessToSfml(io.labelAccess));
        return sb.toString();
    }

    private static String buildLimitList(IOStatementNode io) {
        List<String> parts = new ArrayList<>();
        for (ResourceLimitData limit : io.limits) {
            String s = limitToSfml(limit);
            if (!s.isEmpty()) {
                parts.add(s);
            }
        }
        return String.join(", ", parts);
    }

    private static String limitToSfml(ResourceLimitData limit) {
        List<String> parts = new ArrayList<>();
        String quantity = trimOrEmpty(limit.quantity);
        String retain = trimOrEmpty(limit.retain);
        if (!quantity.isEmpty()) {
            parts.add(quantity + (limit.quantityEach ? " each" : ""));
        }
        if (!retain.isEmpty()) {
            parts.add("retain " + retain + (limit.retainEach ? " each" : ""));
        }
        String resources = buildResourceDisjunction(limit.resources);
        if (!resources.isEmpty()) {
            parts.add(resources);
        }
        String with = withToSfml(limit.with);
        if (!with.isEmpty()) {
            parts.add(with);
        }
        return String.join(" ", parts);
    }

    private static String forgetToSfml(ForgetStatementNode forget) {
        // #4: FORGET always clears everything in the visual editor (no label list).
        return "forget";
    }

    // ----- label access -----
    static String labelAccessToSfml(LabelAccessData la) {
        StringBuilder sb = new StringBuilder();
        String labels = buildLabelList(la.labels);
        sb.append(labels.isEmpty() ? "*" : labels);

        if (la.roundRobin == LabelAccessData.RoundRobin.BY_LABEL) {
            sb.append(" round robin by label");
        } else if (la.roundRobin == LabelAccessData.RoundRobin.BY_BLOCK) {
            sb.append(" round robin by block");
        }

        String sides = buildSides(la);
        if (!sides.isEmpty()) {
            sb.append(" ").append(sides);
        }

        String slots = trimOrEmpty(la.slots);
        if (!slots.isEmpty()) {
            sb.append(" slots ").append(slots);
        }
        return sb.toString();
    }

    private static String buildSides(LabelAccessData la) {
        if (la.eachSide) {
            return "each side";
        }
        if (la.sides.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (LabelAccessData.SideOption side : la.sides) {
            names.add(side.name().toLowerCase(java.util.Locale.ROOT));
        }
        return String.join(", ", names) + " side";
    }

    // ----- conditions (boolexpr) -----
    public static String conditionToSfml(Condition condition) {
        if (condition == null) {
            return "true";
        }
        return switch (condition.kind) {
            case TRUE -> "true";
            case FALSE -> "false";
            case HAS -> hasToSfml(condition);
            case REDSTONE -> redstoneToSfml(condition);
            case NOT -> "not (" + conditionToSfml(condition.child) + ")";
            case AND -> "(" + conditionToSfml(condition.left) + ") and (" + conditionToSfml(condition.right) + ")";
            case OR -> "(" + conditionToSfml(condition.left) + ") or (" + conditionToSfml(condition.right) + ")";
            case RAW -> (condition.raw == null || condition.raw.isBlank()) ? "true" : condition.raw.strip();
        };
    }

    private static String hasToSfml(Condition c) {
        StringBuilder sb = new StringBuilder();
        if (c.setOp != null && c.setOp != Condition.SetOp.OVERALL) {
            sb.append(c.setOp.name().toLowerCase(java.util.Locale.ROOT)).append(" ");
        }
        String labels = buildLabelList(c.labels);
        sb.append(labels.isEmpty() ? "*" : labels);
        sb.append(" has ");
        sb.append(c.comparison == null ? ">=" : c.comparison.symbol);
        sb.append(" ");
        String count = trimOrEmpty(c.count);
        sb.append(count.isEmpty() ? "0" : count);
        String resources = buildResourceDisjunction(c.resource);
        if (!resources.isEmpty()) {
            sb.append(" ").append(resources);
        }
        String with = withToSfml(c.with);
        if (!with.isEmpty()) {
            sb.append(" ").append(with);
        }
        String except = buildResourceIdList(c.except);
        if (!except.isEmpty()) {
            sb.append(" except ").append(except);
        }
        return sb.toString();
    }

    private static String redstoneToSfml(Condition c) {
        if (!c.redstoneHasComparison) {
            return "redstone";
        }
        String count = trimOrEmpty(c.count);
        return "redstone " + (c.comparison == null ? ">=" : c.comparison.symbol) + " " + (count.isEmpty() ? "0" : count);
    }

    // ----- with clauses -----
    static String withToSfml(WithData with) {
        if (with == null || !with.isPresent()) {
            return "";
        }
        String keyword = with.without ? "without" : "with";
        return keyword + " " + withClauseToSfml(with.clause);
    }

    private static String withClauseToSfml(WithClause clause) {
        if (clause == null) {
            return "tag *:*";
        }
        return switch (clause.kind) {
            case TAG -> {
                String tag = trimOrEmpty(clause.tag);
                yield "tag " + (tag.isEmpty() ? "*:*" : tag);
            }
            case NOT -> "not (" + withClauseToSfml(clause.child) + ")";
            case AND -> "(" + withClauseToSfml(clause.left) + ") and (" + withClauseToSfml(clause.right) + ")";
            case OR -> "(" + withClauseToSfml(clause.left) + ") or (" + withClauseToSfml(clause.right) + ")";
        };
    }

    // ----- resource id lists -----
    /** Comma-separated resource id list (used by EXCEPT). */
    private static String buildResourceIdList(String raw) {
        return joinResources(raw, ", ");
    }

    /** OR-joined resource id disjunction (used by limits and HAS). */
    private static String buildResourceDisjunction(String raw) {
        return joinResources(raw, " or ");
    }

    private static String joinResources(String raw, String sep) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        List<String> out = new ArrayList<>();
        for (String piece : raw.split(",")) {
            String r = piece.trim();
            if (!r.isEmpty()) {
                // #1: emit glob wildcards bare (*), genuine regexes quoted, so a parsed
                // *configurable_* round-trips back to *configurable_* (not ".*configurable_.*").
                out.add(SfmlResourceIds.toEmitForm(r));
            }
        }
        return String.join(sep, out);
    }

    // ----- labels -----
    private static String buildLabelList(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        List<String> out = new ArrayList<>();
        for (String piece : raw.split(",")) {
            String label = piece.trim();
            if (label.isEmpty()) {
                continue;
            }
            out.add(formatLabel(label));
        }
        return String.join(", ", out);
    }

    private static String formatLabel(String label) {
        String inner = label;
        if (inner.length() >= 2 && inner.startsWith("\"") && inner.endsWith("\"")) {
            inner = inner.substring(1, inner.length() - 1);
        }
        if (BARE_IDENTIFIER.matcher(inner).matches()) {
            return inner;
        }
        return quote(inner);
    }

    private static String quote(String value) {
        String escaped = value.replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }

    private static String trimOrEmpty(String s) {
        return s == null ? "" : s.trim();
    }
}
