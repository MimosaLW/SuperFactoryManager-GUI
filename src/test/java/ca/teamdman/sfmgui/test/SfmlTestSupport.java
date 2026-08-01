package ca.teamdman.sfmgui.test;

import ca.teamdman.sfml.ast.Program;
import ca.teamdman.sfml.program_builder.ProgramBuildResult;
import ca.teamdman.sfml.program_builder.ProgramBuilder;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Test support that compiles SFML using SFM's public {@link ProgramBuilder}
 * (available on the test classpath from the SFM jar). Mirrors the slice of SFM's
 * internal test helpers that the migrated codegen tests rely on, without
 * depending on SFM's non-published test sources.
 */
public final class SfmlTestSupport {
    private SfmlTestSupport() {
    }

    /** Assert that the given SFML compiles with no build errors. */
    public static void assertNoCompileErrors(String sfml) {
        ProgramBuildResult result = new ProgramBuilder(sfml).useCache(false).build();
        assertNotNull(result.program(), "expected a compiled program");
    }

    /** Compile SFML to a {@link Program} and return its canonical string form. */
    public static String canonical(String sfml) {
        Program program = new ProgramBuilder(sfml).useCache(false).build().program();
        assertNotNull(program, "program failed to compile: " + sfml);
        return program.toString();
    }
}
