package ca.teamdman.sfmgui.test;

import ca.teamdman.sfmgui.client.codegen.GraphToSfml;
import ca.teamdman.sfmgui.client.codegen.SfmlToGraph;
import ca.teamdman.sfmgui.client.model.EditorGraph;
import ca.teamdman.sfmgui.client.model.IOStatementNode;
import ca.teamdman.sfmgui.client.model.ResourceLimitData;
import ca.teamdman.sfmgui.client.model.StatementNode;
import ca.teamdman.sfmgui.client.model.TriggerNode;
import ca.teamdman.sfml.program_builder.ProgramBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the multi-limit Filter UI (#11), the EXCEPT blacklist
 * (#11), and the parse-failure guard premise for the data-loss fix (#2).
 */
public class FilterAndGuardTests {

    private static TriggerNode timer(int interval) {
        TriggerNode t = new TriggerNode(0, 0, TriggerNode.Kind.TIMER);
        t.interval = interval;
        t.unit = TriggerNode.TimeUnit.TICKS;
        return t;
    }

    private static ResourceLimitData limit(String qty, String retain, String resources) {
        ResourceLimitData l = new ResourceLimitData();
        l.quantity = qty;
        l.retain = retain;
        l.resources = resources;
        return l;
    }

    /** #11: several resource limits on one INPUT must all round-trip and compile. */
    @Test
    public void multipleResourceLimitsCompile() {
        EditorGraph graph = new EditorGraph();
        graph.name = "MultiLimit";
        TriggerNode t = timer(20);
        IOStatementNode in = new IOStatementNode(0, 0, StatementNode.Kind.INPUT);
        in.labelAccess.labels = "a";
        in.limits.add(limit("64", "", "minecraft:stone"));
        in.limits.add(limit("16", "", "minecraft:dirt"));
        in.limits.add(limit("", "1", "*ingot*"));
        t.statements.add(in);
        graph.addTrigger(t);

        String sfml = GraphToSfml.generate(graph);
        System.out.println("----\n" + sfml + "----");
        assertNotNull(new ProgramBuilder(sfml).useCache(false).build().program(),
                "multi-limit program should compile");
    }

    /** #11: the EXCEPT blacklist must round-trip and compile. */
    @Test
    public void exceptBlacklistCompiles() {
        EditorGraph graph = new EditorGraph();
        graph.name = "Blacklist";
        TriggerNode t = timer(20);
        IOStatementNode in = new IOStatementNode(0, 0, StatementNode.Kind.INPUT);
        in.labelAccess.labels = "a";
        in.limits.add(limit("", "", "*"));
        in.except = "minecraft:stone, minecraft:dirt";
        t.statements.add(in);
        graph.addTrigger(t);

        String sfml = GraphToSfml.generate(graph);
        System.out.println("----\n" + sfml + "----");
        assertNotNull(new ProgramBuilder(sfml).useCache(false).build().program(),
                "blacklist program should compile");
    }

    /**
     * #2 premise: an interval below the 20-tick minimum fails to compile (program
     * is null) and reverse-parses into an empty graph. The editor relies on exactly
     * this to detect an unparseable disk and avoid overwriting it.
     */
    @Test
    public void every19FailsAndParsesEmpty() {
        String invalid = "EVERY 19 TICKS DO\n    INPUT FROM *\n    OUTPUT TO *\nEND";
        assertNull(new ProgramBuilder(invalid).useCache(false).build().program(),
                "EVERY 19 TICKS should fail to compile (min interval 20)");
        EditorGraph graph = SfmlToGraph.parse(invalid);
        assertTrue(graph.triggers.isEmpty(),
                "an unparseable program should reverse-parse into an empty graph");
    }
}
