package ca.teamdman.sfmgui.test;

import ca.teamdman.sfmgui.client.codegen.GraphToSfml;
import ca.teamdman.sfmgui.client.codegen.SfmlToGraph;
import ca.teamdman.sfmgui.client.model.EditorGraph;
import ca.teamdman.sfml.program_builder.ProgramBuilder;
import org.junit.jupiter.api.Test;

/**
 * Diagnostics for how blank / trivial programs are handled on import.
 */
public class ImportBlankDiagnosticsTests {

    private static void diag(String label, String sfml) {
        System.out.println("=== " + label + " === input=[" + sfml + "]");
        try {
            var result = new ProgramBuilder(sfml).useCache(false).build();
            System.out.println("  compiles=" + (result.program() != null)
                               + " errors=" + result.metadata().errors().size());
            if (result.program() != null) {
                System.out.println("  canonical=[" + result.program().toString().replace("\n", "\\n") + "]");
                System.out.println("  triggers=" + result.program().triggers().size());
            }
        } catch (Throwable t) {
            System.out.println("  threw " + t);
        }
        EditorGraph g = SfmlToGraph.parse(sfml);
        System.out.println("  parsed graph triggers=" + g.triggers.size() + " name=" + g.name);
        System.out.println("  regenerated=[" + GraphToSfml.generate(g).replace("\n", "\\n") + "]");
    }

    @Test
    public void diagnoseBlankInputs() {
        diag("empty", "");
        diag("space", " ");
        diag("newline", "\n");
        diag("comment-only", "-- hello");
        diag("name-only", "NAME \"My Program\"");
    }

    /** Blank/trivial programs must round-trip to the same canonical form (no spurious warning). */
    @Test
    public void blankInputsRoundTripCleanly() {
        for (String sfml : new String[]{"", " ", "\n", "-- hello", "NAME \"My Program\""}) {
            EditorGraph g = SfmlToGraph.parse(sfml);
            String regenerated = GraphToSfml.generate(g);
            String before = new ProgramBuilder(sfml).useCache(false).build().program().toString();
            String after = new ProgramBuilder(regenerated).useCache(false).build().program().toString();
            org.junit.jupiter.api.Assertions.assertEquals(before, after,
                    "blank/trivial input should round-trip cleanly: [" + sfml + "]");
        }
    }
}
