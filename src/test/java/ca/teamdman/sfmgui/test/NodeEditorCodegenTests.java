package ca.teamdman.sfmgui.test;

import ca.teamdman.sfmgui.client.codegen.GraphToSfml;
import ca.teamdman.sfmgui.client.codegen.SfmlToGraph;
import ca.teamdman.sfmgui.client.model.Condition;
import ca.teamdman.sfmgui.client.model.EditorGraph;
import ca.teamdman.sfmgui.client.model.ForgetStatementNode;
import ca.teamdman.sfmgui.client.model.IOStatementNode;
import ca.teamdman.sfmgui.client.model.IfStatementNode;
import ca.teamdman.sfmgui.client.model.LabelAccessData;
import ca.teamdman.sfmgui.client.model.ResourceLimitData;
import ca.teamdman.sfmgui.client.model.StatementNode;
import ca.teamdman.sfmgui.client.model.TriggerNode;
import ca.teamdman.sfmgui.client.model.WithClause;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the visual node editor's {@link GraphToSfml} always emits SFML
 * that the real compiler accepts. This is the core correctness guarantee for the
 * visual editor: whatever graph the player builds, the generated program must parse.
 */
public class NodeEditorCodegenTests {

    // ----- helpers -----
    private static IOStatementNode io(StatementNode.Kind kind, String labels) {
        IOStatementNode node = new IOStatementNode(0, 0, kind);
        node.labelAccess.labels = labels;
        return node;
    }

    private static ResourceLimitData limit(String quantity, String retain, String resources) {
        ResourceLimitData l = new ResourceLimitData();
        l.quantity = quantity;
        l.retain = retain;
        l.resources = resources;
        return l;
    }

    private static void assertCompiles(EditorGraph graph) {
        String sfml = GraphToSfml.generate(graph);
        System.out.println("----\n" + sfml + "----");
        SfmlTestSupport.assertNoCompileErrors(sfml);
    }

    // ----- M1/M2 basics -----
    @Test
    public void emptyProgramCompiles() {
        EditorGraph graph = new EditorGraph();
        graph.name = "Empty";
        assertCompiles(graph);
    }

    @Test
    public void timerWithInputOutputCompiles() {
        EditorGraph graph = new EditorGraph();
        graph.name = "Basic Transfer";
        TriggerNode timer = new TriggerNode(0, 0, TriggerNode.Kind.TIMER);
        timer.statements.add(io(StatementNode.Kind.INPUT, "a"));
        timer.statements.add(io(StatementNode.Kind.OUTPUT, "b"));
        graph.addTrigger(timer);
        assertCompiles(graph);
    }

    @Test
    public void secondsAndGlobalOffsetCompiles() {
        EditorGraph graph = new EditorGraph();
        graph.name = "Timed";
        TriggerNode timer = new TriggerNode(0, 0, TriggerNode.Kind.TIMER);
        timer.interval = 5;
        timer.unit = TriggerNode.TimeUnit.SECONDS;
        timer.global = true;
        timer.offset = 1;
        graph.addTrigger(timer);
        assertCompiles(graph);
    }

    @Test
    public void redstonePulseWithForgetCompiles() {
        EditorGraph graph = new EditorGraph();
        graph.name = "Pulse";
        TriggerNode pulse = new TriggerNode(0, 0, TriggerNode.Kind.REDSTONE_PULSE);
        pulse.statements.add(io(StatementNode.Kind.INPUT, "src"));
        ForgetStatementNode forget = new ForgetStatementNode(0, 0);
        forget.labels = "src";
        pulse.statements.add(forget);
        graph.addTrigger(pulse);
        assertCompiles(graph);
    }

    @Test
    public void quotedLabelWithSpacesCompiles() {
        EditorGraph graph = new EditorGraph();
        graph.name = "Spaced Labels";
        TriggerNode timer = new TriggerNode(0, 0, TriggerNode.Kind.TIMER);
        timer.statements.add(io(StatementNode.Kind.INPUT, "storage a, storage b"));
        graph.addTrigger(timer);

        String sfml = GraphToSfml.generate(graph);
        System.out.println(sfml);
        assertTrue(sfml.contains("\"storage a\""), "labels with spaces should be quoted");
        SfmlTestSupport.assertNoCompileErrors(sfml);
    }

    @Test
    public void emptyLabelsDefaultToWildcardCompiles() {
        EditorGraph graph = new EditorGraph();
        graph.name = "Wildcard";
        TriggerNode timer = new TriggerNode(0, 0, TriggerNode.Kind.TIMER);
        timer.statements.add(io(StatementNode.Kind.INPUT, ""));
        graph.addTrigger(timer);
        assertCompiles(graph);
    }

    // ----- IF / conditions -----
    @Test
    public void ifHasWithThenBranchCompiles() {
        EditorGraph graph = new EditorGraph();
        graph.name = "Conditional";
        TriggerNode timer = new TriggerNode(0, 0, TriggerNode.Kind.TIMER);
        timer.statements.add(io(StatementNode.Kind.INPUT, "source"));

        IfStatementNode ifNode = new IfStatementNode(0, 0);
        ifNode.condition.kind = Condition.Kind.HAS;
        ifNode.condition.setOp = Condition.SetOp.SOME;
        ifNode.condition.labels = "source";
        ifNode.condition.comparison = Condition.Comparison.GT;
        ifNode.condition.count = "10";
        ifNode.condition.resource = "minecraft:iron_ingot";
        ifNode.thenBranch.statements.add(io(StatementNode.Kind.OUTPUT, "dest"));
        timer.statements.add(ifNode);
        graph.addTrigger(timer);
        assertCompiles(graph);
    }

    @Test
    public void ifWithElseBranchCompiles() {
        EditorGraph graph = new EditorGraph();
        graph.name = "IfElse";
        TriggerNode timer = new TriggerNode(0, 0, TriggerNode.Kind.TIMER);

        IfStatementNode ifNode = new IfStatementNode(0, 0);
        ifNode.condition.kind = Condition.Kind.HAS;
        ifNode.condition.labels = "a";
        ifNode.condition.comparison = Condition.Comparison.GE;
        ifNode.condition.count = "5";
        ifNode.hasElse = true;
        ifNode.thenBranch.statements.add(io(StatementNode.Kind.OUTPUT, "x"));
        ifNode.elseBranch.statements.add(io(StatementNode.Kind.OUTPUT, "y"));
        timer.statements.add(ifNode);
        graph.addTrigger(timer);

        String sfml = GraphToSfml.generate(graph);
        System.out.println(sfml);
        assertTrue(sfml.contains("else"), "else branch should be emitted");
        SfmlTestSupport.assertNoCompileErrors(sfml);
    }

    @Test
    public void ifRedstoneConditionCompiles() {
        EditorGraph graph = new EditorGraph();
        graph.name = "RedstoneGate";
        TriggerNode timer = new TriggerNode(0, 0, TriggerNode.Kind.TIMER);
        IfStatementNode ifNode = new IfStatementNode(0, 0);
        ifNode.condition.kind = Condition.Kind.REDSTONE;
        ifNode.condition.comparison = Condition.Comparison.GE;
        ifNode.condition.count = "2";
        ifNode.thenBranch.statements.add(io(StatementNode.Kind.INPUT, "a"));
        timer.statements.add(ifNode);
        graph.addTrigger(timer);
        assertCompiles(graph);
    }

    @Test
    public void nestedIfCompiles() {
        EditorGraph graph = new EditorGraph();
        graph.name = "Nested";
        TriggerNode timer = new TriggerNode(0, 0, TriggerNode.Kind.TIMER);

        IfStatementNode outer = new IfStatementNode(0, 0);
        outer.condition.kind = Condition.Kind.HAS;
        outer.condition.labels = "a";
        outer.condition.comparison = Condition.Comparison.GT;
        outer.condition.count = "0";

        IfStatementNode inner = new IfStatementNode(0, 0);
        inner.condition.kind = Condition.Kind.REDSTONE;
        inner.condition.redstoneHasComparison = false;
        inner.thenBranch.statements.add(io(StatementNode.Kind.OUTPUT, "b"));

        outer.thenBranch.statements.add(inner);
        timer.statements.add(outer);
        graph.addTrigger(timer);
        assertCompiles(graph);
    }

    @Test
    public void ifTrueAndFalseConstantsCompile() {
        EditorGraph graph = new EditorGraph();
        graph.name = "Constants";
        TriggerNode timer = new TriggerNode(0, 0, TriggerNode.Kind.TIMER);
        IfStatementNode t = new IfStatementNode(0, 0);
        t.condition.kind = Condition.Kind.TRUE;
        t.thenBranch.statements.add(io(StatementNode.Kind.OUTPUT, "b"));
        timer.statements.add(t);
        graph.addTrigger(timer);
        assertCompiles(graph);
    }

    // ----- M3: full resource-limit surface -----
    @Test
    public void quantityRetainResourceAndEmptySlotsCompiles() {
        EditorGraph graph = new EditorGraph();
        graph.name = "Filtered";
        TriggerNode timer = new TriggerNode(0, 0, TriggerNode.Kind.TIMER);

        IOStatementNode input = io(StatementNode.Kind.INPUT, "chest");
        ResourceLimitData l = limit("64", "1", "minecraft:stone");
        input.limits.add(l);
        input.each = true;
        timer.statements.add(input);

        IOStatementNode output = io(StatementNode.Kind.OUTPUT, "barrel");
        output.emptySlotsOnly = true;
        timer.statements.add(output);

        graph.addTrigger(timer);
        assertCompiles(graph);
    }

    @Test
    public void multipleLimitsWithEachAndExceptCompiles() {
        EditorGraph graph = new EditorGraph();
        graph.name = "MultiLimit";
        TriggerNode timer = new TriggerNode(0, 0, TriggerNode.Kind.TIMER);

        IOStatementNode input = io(StatementNode.Kind.INPUT, "a");
        ResourceLimitData l1 = limit("5", "", "*ingot*");
        l1.quantityEach = true;
        ResourceLimitData l2 = limit("", "3", "stone");
        l2.retainEach = true;
        input.limits.add(l1);
        input.limits.add(l2);
        input.except = "iron_ingot, gold_ingot";
        timer.statements.add(input);

        graph.addTrigger(timer);
        assertCompiles(graph);
    }

    @Test
    public void resourceDisjunctionCompiles() {
        EditorGraph graph = new EditorGraph();
        graph.name = "Disjunction";
        TriggerNode timer = new TriggerNode(0, 0, TriggerNode.Kind.TIMER);
        IOStatementNode input = io(StatementNode.Kind.INPUT, "a");
        input.limits.add(limit("", "", "minecraft:stone, minecraft:cobblestone"));
        timer.statements.add(input);
        graph.addTrigger(timer);

        String sfml = GraphToSfml.generate(graph);
        System.out.println(sfml);
        assertTrue(sfml.contains(" or "), "multiple resources should be OR-joined");
        SfmlTestSupport.assertNoCompileErrors(sfml);
    }

    // ----- M3: label access (round robin, sides, slots) -----
    @Test
    public void sidesAndSlotsCompile() {
        EditorGraph graph = new EditorGraph();
        graph.name = "SidesSlots";
        TriggerNode timer = new TriggerNode(0, 0, TriggerNode.Kind.TIMER);

        IOStatementNode input = io(StatementNode.Kind.INPUT, "a");
        input.labelAccess.sides.add(LabelAccessData.SideOption.TOP);
        input.labelAccess.sides.add(LabelAccessData.SideOption.WEST);
        input.labelAccess.slots = "0,1,3-4,7-9,21";
        timer.statements.add(input);

        IOStatementNode output = io(StatementNode.Kind.OUTPUT, "a");
        output.labelAccess.sides.add(LabelAccessData.SideOption.BOTTOM);
        output.labelAccess.slots = "2";
        timer.statements.add(output);

        graph.addTrigger(timer);
        assertCompiles(graph);
    }

    @Test
    public void eachSideCompiles() {
        EditorGraph graph = new EditorGraph();
        graph.name = "EachSide";
        TriggerNode timer = new TriggerNode(0, 0, TriggerNode.Kind.TIMER);
        IOStatementNode input = io(StatementNode.Kind.INPUT, "a");
        input.labelAccess.eachSide = true;
        timer.statements.add(input);
        graph.addTrigger(timer);
        assertCompiles(graph);
    }

    @Test
    public void roundRobinByLabelAndBlockCompile() {
        EditorGraph graph = new EditorGraph();
        graph.name = "RoundRobin";
        TriggerNode timer = new TriggerNode(0, 0, TriggerNode.Kind.TIMER);

        IOStatementNode input = io(StatementNode.Kind.INPUT, "storage a, storage b");
        input.labelAccess.roundRobin = LabelAccessData.RoundRobin.BY_LABEL;
        timer.statements.add(input);

        IOStatementNode output = io(StatementNode.Kind.OUTPUT, "dest");
        output.limits.add(limit("128", "", "dirt"));
        output.labelAccess.roundRobin = LabelAccessData.RoundRobin.BY_BLOCK;
        timer.statements.add(output);

        graph.addTrigger(timer);
        assertCompiles(graph);
    }

    // ----- M3: WITH tag filters -----
    @Test
    public void withTagFilterCompiles() {
        EditorGraph graph = new EditorGraph();
        graph.name = "TagFilter";
        TriggerNode timer = new TriggerNode(0, 0, TriggerNode.Kind.TIMER);
        IOStatementNode input = io(StatementNode.Kind.INPUT, "chest");
        ResourceLimitData l = new ResourceLimitData();
        l.with.clause = WithClause.tag("forge:ingots");
        input.limits.add(l);
        timer.statements.add(input);
        graph.addTrigger(timer);
        assertCompiles(graph);
    }

    @Test
    public void withoutNegatedTagFilterCompiles() {
        EditorGraph graph = new EditorGraph();
        graph.name = "WithoutFilter";
        TriggerNode timer = new TriggerNode(0, 0, TriggerNode.Kind.TIMER);
        IOStatementNode input = io(StatementNode.Kind.INPUT, "chest");
        ResourceLimitData l = new ResourceLimitData();
        l.with.without = true;
        l.with.clause = WithClause.tag("needs_stone_tool");
        input.limits.add(l);
        timer.statements.add(input);
        graph.addTrigger(timer);
        assertCompiles(graph);
    }

    @Test
    public void withAndOrNotTreeCompiles() {
        EditorGraph graph = new EditorGraph();
        graph.name = "TagTree";
        TriggerNode timer = new TriggerNode(0, 0, TriggerNode.Kind.TIMER);
        IOStatementNode input = io(StatementNode.Kind.INPUT, "chest");
        ResourceLimitData l = new ResourceLimitData();

        WithClause and = new WithClause();
        and.kind = WithClause.Kind.AND;
        WithClause notClause = new WithClause();
        notClause.kind = WithClause.Kind.NOT;
        notClause.child = WithClause.tag("needs_stone_tool");
        and.left = notClause;
        and.right = WithClause.tag("ores/*");
        l.with.clause = and;

        input.limits.add(l);
        timer.statements.add(input);
        graph.addTrigger(timer);
        assertCompiles(graph);
    }

    @Test
    public void hasConditionWithTagAndExceptCompiles() {
        EditorGraph graph = new EditorGraph();
        graph.name = "HasWith";
        TriggerNode timer = new TriggerNode(0, 0, TriggerNode.Kind.TIMER);
        IfStatementNode ifNode = new IfStatementNode(0, 0);
        ifNode.condition.kind = Condition.Kind.HAS;
        ifNode.condition.setOp = Condition.SetOp.EVERY;
        ifNode.condition.labels = "a";
        ifNode.condition.comparison = Condition.Comparison.GE;
        ifNode.condition.count = "1";
        ifNode.condition.with.clause = WithClause.tag("forge:ingots");
        ifNode.condition.except = "iron_ingot";
        ifNode.thenBranch.statements.add(io(StatementNode.Kind.OUTPUT, "b"));
        timer.statements.add(ifNode);
        graph.addTrigger(timer);
        assertCompiles(graph);
    }

    // ----- exhaustive enum coverage -----
    @Test
    public void allSetOperatorsCompile() {
        for (Condition.SetOp op : Condition.SetOp.values()) {
            EditorGraph graph = new EditorGraph();
            graph.name = "SetOp " + op;
            TriggerNode timer = new TriggerNode(0, 0, TriggerNode.Kind.TIMER);
            IfStatementNode ifNode = new IfStatementNode(0, 0);
            ifNode.condition.kind = Condition.Kind.HAS;
            ifNode.condition.setOp = op;
            ifNode.condition.labels = "a";
            ifNode.condition.comparison = Condition.Comparison.EQ;
            ifNode.condition.count = "5";
            ifNode.condition.resource = "stone";
            ifNode.thenBranch.statements.add(io(StatementNode.Kind.OUTPUT, "b"));
            timer.statements.add(ifNode);
            graph.addTrigger(timer);
            assertCompiles(graph);
        }
    }

    @Test
    public void allComparisonOperatorsCompile() {
        for (Condition.Comparison cmp : Condition.Comparison.values()) {
            EditorGraph graph = new EditorGraph();
            graph.name = "Cmp " + cmp;
            TriggerNode timer = new TriggerNode(0, 0, TriggerNode.Kind.TIMER);
            IfStatementNode ifNode = new IfStatementNode(0, 0);
            ifNode.condition.kind = Condition.Kind.HAS;
            ifNode.condition.labels = "a";
            ifNode.condition.comparison = cmp;
            ifNode.condition.count = "3";
            ifNode.thenBranch.statements.add(io(StatementNode.Kind.OUTPUT, "b"));
            timer.statements.add(ifNode);
            graph.addTrigger(timer);
            assertCompiles(graph);
        }
    }

    @Test
    public void allSidesCompile() {
        for (LabelAccessData.SideOption side : LabelAccessData.SideOption.values()) {
            EditorGraph graph = new EditorGraph();
            graph.name = "Side " + side;
            TriggerNode timer = new TriggerNode(0, 0, TriggerNode.Kind.TIMER);
            IOStatementNode input = io(StatementNode.Kind.INPUT, "a");
            input.labelAccess.sides.add(side);
            timer.statements.add(input);
            graph.addTrigger(timer);
            assertCompiles(graph);
        }
    }

    // ----- M4: round-trip (parse existing SFML -> graph -> SFML) -----
    private void assertRoundTrip(String sfml) {
        EditorGraph graph = SfmlToGraph.parse(sfml);
        String regenerated = GraphToSfml.generate(graph);
        System.out.println("REGEN:\n" + regenerated);
        SfmlTestSupport.assertNoCompileErrors(regenerated);
        // canonical AST comparison ensures semantics are preserved
        var before = SfmlTestSupport.canonical(sfml);
        var after = SfmlTestSupport.canonical(regenerated);
        org.junit.jupiter.api.Assertions.assertEquals(before, after,
                "round trip should preserve canonical AST");
    }

    @Test
    public void roundTripSimpleTransfer() {
        assertRoundTrip("NAME \"x\"\nEVERY 20 TICKS DO\n INPUT FROM a\n OUTPUT TO b\nEND");
    }

    @Test
    public void roundTripLimitsAndEach() {
        assertRoundTrip("NAME \"x\"\nEVERY 20 TICKS DO\n INPUT 5 RETAIN 1 EACH *ingot FROM a\n OUTPUT TO b\nEND");
    }

    @Test
    public void roundTripSidesSlotsRoundRobin() {
        assertRoundTrip("NAME \"x\"\nEVERY 20 TICKS DO\n"
                        + " INPUT FROM a TOP, WEST SIDE SLOTS 0,1,3-4\n"
                        + " OUTPUT 128 dirt TO dest ROUND ROBIN BY BLOCK\nEND");
    }

    private void assertRoundTripCompiles(String sfml) {
        EditorGraph graph = SfmlToGraph.parse(sfml);
        String regenerated = GraphToSfml.generate(graph);
        System.out.println("REGEN:\n" + regenerated);
        SfmlTestSupport.assertNoCompileErrors(regenerated);
    }

    @Test
    public void roundTripIfHasAndRedstone() {
        assertRoundTrip("NAME \"x\"\nEVERY 20 TICKS DO\n"
                        + " IF SOME a HAS > 10 minecraft:iron_ingot THEN\n  OUTPUT TO b\n END\n"
                        + " IF REDSTONE >= 2 THEN\n  INPUT FROM a\n END\nEND");
    }

    @Test
    public void roundTripExceptAndForget() {
        assertRoundTrip("NAME \"x\"\nEVERY 20 TICKS DO\n"
                        + " INPUT *ingot* EXCEPT iron_ingot, gold_ingot FROM a\n FORGET a\nEND");
    }

    @Test
    public void roundTripGlobWildcardsStayBare() {
        // #1: the user's original glob form (*configurable_*, *raw*) must survive a
        // parse->regen round trip as a BARE glob, not a re-quoted/double-converted regex.
        String src = "NAME \"x\"\nEVERY REDSTONE PULSE DO\n"
                     + " INPUT FROM \"缓存\"\n"
                     + " OUTPUT *raw* TO \"熔炉\" TOP SIDE\n"
                     + " OUTPUT *configurable_* TO \"离心输入\"\n"
                     + " OUTPUT EXCEPT *configurable_*, *raw* TO \"接口\"\nEND";
        EditorGraph graph = SfmlToGraph.parse(src);
        String regen = GraphToSfml.generate(graph);
        System.out.println("REGEN:\n" + regen);
        SfmlTestSupport.assertNoCompileErrors(regen);
        // Must emit bare globs, never a quoted ".*...*" regex form.
        assertTrue(regen.contains("*configurable_*"), "glob *configurable_* should round-trip bare");
        assertTrue(regen.contains("*raw*"), "glob *raw* should round-trip bare");
        assertTrue(!regen.contains(".*configurable_.*") && !regen.contains("\".*"),
                "must not emit the quoted/regex .* form");
        // Semantics must be identical to the source.
        org.junit.jupiter.api.Assertions.assertEquals(
                SfmlTestSupport.canonical(src), SfmlTestSupport.canonical(regen),
                "glob round trip should preserve canonical AST");
    }

    @Test
    public void roundTripWithTagFallsBackButPreserves() {
        // WITH clauses fall back to raw statements; must still preserve semantics.
        assertRoundTrip("NAME \"x\"\nEVERY 20 TICKS DO\n INPUT WITH #forge:ingots FROM chest\n OUTPUT TO b\nEND");
    }

    @Test
    public void roundTripComplexBoolExprFallsBackButPreserves() {
        assertRoundTrip("NAME \"x\"\nEVERY 20 TICKS DO\n"
                        + " IF a HAS > 5 stone AND b HAS < 3 dirt THEN\n  OUTPUT TO c\n END\nEND");
    }
}
