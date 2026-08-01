package ca.teamdman.sfmgui.client.codegen;

/**
 * Helpers for emitting SFM resource identifiers in the same wildcard style the user
 * originally typed.
 * <p>
 * SFM parses a <em>bare</em> token like {@code *configurable_*} by rewriting the glob
 * {@code *} to the regex {@code .*} (so it is stored as {@code .*configurable_.*}), while
 * a <em>quoted</em> token {@code ".*configurable_.*"} is treated as a raw regex. When the
 * addon regenerates SFML from the parsed AST it gets back the {@code .*}-form; naively
 * re-quoting it (or letting it be re-globbed) can double-convert to {@code ..*configurable_..*}
 * and break boundary matches.
 * <p>
 * To round-trip faithfully we detect ids whose only regex feature is {@code .*} wildcard
 * runs ("pure globs") and emit them <em>bare</em> with {@code .*} turned back into {@code *}
 * (reproducing the user's original {@code *configurable_*}). Anything containing other regex
 * metacharacters is a genuine regex and is emitted quoted, verbatim.
 */
public final class SfmlResourceIds {
    private SfmlResourceIds() {
    }

    /** Regex metacharacters other than {@code *} and {@code .} that force a quoted-regex emit. */
    private static final String HARD_META = "?+^$[](){}|\\";

    /**
     * Convert one resource id into the preferred emit form. Input may be:
     * <ul>
     *   <li>a user-typed glob like {@code *ingot*} (literal {@code *} wildcards),</li>
     *   <li>parser output like {@code .*ingot.*} (regex {@code .*} wildcards, maybe quoted),</li>
     *   <li>a genuine regex like {@code "[a-z]+"},</li>
     *   <li>a plain literal like {@code minecraft:stone}.</li>
     * </ul>
     * Rules:
     * <ul>
     *   <li>pure-glob ids (only {@code *} / {@code .*} wildcards, no other regex meta) -> bare
     *       glob with wildcards normalized to a single {@code *} (e.g. {@code *ingot*});</li>
     *   <li>genuine regexes -> quoted verbatim;</li>
     *   <li>plain literals -> unchanged.</li>
     * </ul>
     */
    public static String toEmitForm(String id) {
        if (id == null) {
            return "";
        }
        String s = id.trim();
        if (s.isEmpty()) {
            return s;
        }
        boolean wasQuoted = s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"");
        String inner = wasQuoted ? s.substring(1, s.length() - 1) : s;

        if (isPureGlob(inner)) {
            // Normalize both regex wildcard runs (.*) and bare globs (*) to a single bare *.
            // SFM's bare-token path converts * -> .* on parse, so this round-trips faithfully.
            return inner.replace(".*", "*");
        }
        // Genuine regex: keep quoted so SFM parses it via the raw-regex (string) path.
        if (isGenuineRegex(inner)) {
            return "\"" + inner.replace("\"", "\\\"") + "\"";
        }
        // Plain literal (e.g. "mysticalagriculture", "fe"): emit as-is.
        return inner;
    }

    /**
     * A "pure glob" uses only {@code *} and/or {@code .*} as wildcards and has no other
     * regex-significant character. After removing every {@code .*} run and every bare
     * {@code *}, the remainder must contain no regex metacharacter (including a stray
     * {@code .}). Must contain at least one wildcard (else it is a plain literal).
     */
    private static boolean isPureGlob(String s) {
        if (s.isEmpty()) {
            return false;
        }
        boolean hasWildcard = s.contains("*"); // covers both "*" and ".*"
        if (!hasWildcard) {
            return false;
        }
        String leftover = s.replace(".*", "").replace("*", "");
        // A leftover '.' means a regex dot that is NOT part of a .* wildcard -> genuine regex.
        if (leftover.indexOf('.') >= 0) {
            return false;
        }
        for (int i = 0; i < leftover.length(); i++) {
            if (HARD_META.indexOf(leftover.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isGenuineRegex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.' || c == '*' || HARD_META.indexOf(c) >= 0) {
                return true;
            }
        }
        return false;
    }
}
