package ca.teamdman.sfmgui.client;

import ca.teamdman.sfmgui.client.model.EditorGraph;
import ca.teamdman.sfmgui.client.model.EditorNode;
import ca.teamdman.sfmgui.client.model.StatementNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists node canvas positions across editor sessions by appending an SFML
 * line comment to the saved program. SFML ignores {@code -- ...} lines, so the
 * program stays valid; on reopen we parse the comment and reapply positions in
 * the same deterministic traversal order the graph produces.
 */
public final class LayoutMemory {
    private LayoutMemory() {
    }

    private static final String TAG = "-- @sfmgui-layout ";
    private static final String CAM_TAG = "-- @sfmgui-cam ";
    private static final String NOTE_TAG = "-- @sfmgui-note ";

    /** Escape a note for single-line storage: backslash, semicolon, newline. */
    private static String escapeNote(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace("\\", "\\\\").replace(";", "\\;").replace("\n", "\\n").replace("\r", "");
    }

    private static String unescapeNote(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                out.append(n == 'n' ? '\n' : n);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Append (or replace) the layout + notes comment. */
    public static String appendTo(String sfml, EditorGraph graph, List<StatementNode> detached) {
        List<EditorNode> nodes = orderedNodes(graph, detached);
        StringBuilder sb = new StringBuilder();
        for (EditorNode n : nodes) {
            if (sb.length() > 0) sb.append(';');
            sb.append(n.x).append(',').append(n.y).append(',').append(n.collapsed ? 1 : 0).append(',').append(encodeMenus(n));
        }
        StringBuilder notes = new StringBuilder();
        boolean anyNote = false;
        for (EditorNode n : nodes) {
            if (notes.length() > 0) notes.append(';');
            String esc = escapeNote(n.note);
            if (!esc.isEmpty()) anyNote = true;
            notes.append(esc);
        }
        String base = stripLayout(sfml).stripTrailing();
        String result = base + "\n" + TAG + sb + "\n";
        if (anyNote) {
            result += NOTE_TAG + notes + "\n";
        }
        return result;
    }

    /** Append node layout + notes + camera (pan/zoom). */
    public static String appendTo(String sfml, EditorGraph graph, List<StatementNode> detached, float panX, float panY, float zoom) {
        String withLayout = appendTo(sfml, graph, detached);
        return withLayout.stripTrailing() + "\n" + CAM_TAG + panX + "," + panY + "," + zoom + "\n";
    }

    /** Parsed camera state, or null if absent. */
    public static float @org.jetbrains.annotations.Nullable [] readCamera(String sfml) {
        if (sfml == null) {
            return null;
        }
        String line = null;
        for (String l : sfml.split("\n")) {
            String t = l.trim();
            if (t.startsWith(CAM_TAG.trim())) {
                line = t.substring(CAM_TAG.trim().length()).trim();
            }
        }
        if (line == null || line.isEmpty()) {
            return null;
        }
        String[] p = line.split(",");
        if (p.length < 3) {
            return null;
        }
        try {
            return new float[]{Float.parseFloat(p[0].trim()), Float.parseFloat(p[1].trim()), Float.parseFloat(p[2].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** True if the program carries a saved node-layout comment (i.e. was placed by us before). */
    public static boolean hasLayout(String sfml) {
        if (sfml == null) {
            return false;
        }
        for (String l : sfml.split("\n")) {
            if (l.trim().startsWith(TAG.trim())) {
                return true;
            }
        }
        return false;
    }

    /** Parse layout + notes comments and apply to the graph. */
    public static void apply(String sfml, EditorGraph graph) {
        if (sfml == null) {
            return;
        }
        String layoutLine = null, noteLine = null;
        for (String l : sfml.split("\n")) {
            String t = l.trim();
            if (t.startsWith(TAG.trim())) {
                layoutLine = t.substring(TAG.trim().length()).trim();
            } else if (t.startsWith(NOTE_TAG.trim())) {
                noteLine = t.substring(NOTE_TAG.trim().length()).trim();
            }
        }
        List<EditorNode> nodes = orderedNodes(graph, new ArrayList<>());
        if (layoutLine != null && !layoutLine.isEmpty()) {
            String[] entries = layoutLine.split(";");
            for (int i = 0; i < nodes.size() && i < entries.length; i++) {
                String[] parts = entries[i].split(",");
                if (parts.length >= 2) {
                    try {
                        nodes.get(i).x = Integer.parseInt(parts[0].trim());
                        nodes.get(i).y = Integer.parseInt(parts[1].trim());
                        if (parts.length >= 3) {
                            nodes.get(i).collapsed = parts[2].trim().equals("1");
                        }
                        if (parts.length >= 4) {
                            decodeMenus(nodes.get(i), parts[3].trim());
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        if (noteLine != null && !noteLine.isEmpty()) {
            String[] rawNotes = noteLine.split("(?<!\\\\);", -1);
            for (int i = 0; i < nodes.size() && i < rawNotes.length; i++) {
                nodes.get(i).note = unescapeNote(rawNotes[i]);
            }
        }
    }

    /** Remove any prior layout/camera/note comment lines from the program text. */
    public static String stripLayout(String sfml) {
        if (sfml == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String l : sfml.split("\n", -1)) {
            String t = l.trim();
            if (t.startsWith(TAG.trim()) || t.startsWith(CAM_TAG.trim()) || t.startsWith(NOTE_TAG.trim())) {
                continue;
            }
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    /**
     * Encode a node's open menus as a {@code |}-separated index list (e.g.
     * {@code "0|2|3"}), or {@code "-1"} when none are open. Uses {@code |} so it
     * never collides with the {@code ,} field separator or {@code ;} record
     * separator.
     */
    private static String encodeMenus(EditorNode n) {
        if (n.openMenus.isEmpty()) {
            return "-1";
        }
        StringBuilder sb = new StringBuilder();
        for (int idx : n.openMenus) {
            if (sb.length() > 0) sb.append('|');
            sb.append(idx);
        }
        return sb.toString();
    }

    /**
     * Decode the menu spec written by {@link #encodeMenus}. Backward compatible
     * with the old single-int format ({@code "2"} / {@code "-1"}).
     */
    private static void decodeMenus(EditorNode n, String spec) {
        n.openMenus.clear();
        if (spec == null || spec.isEmpty()) {
            return;
        }
        for (String part : spec.split("\\|")) {
            String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                int idx = Integer.parseInt(t);
                if (idx >= 0) {
                    n.openMenus.add(idx);
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    /** Deterministic node order matching {@link EditorGraph#allNodes()} + detached. */
    private static List<EditorNode> orderedNodes(EditorGraph graph, List<StatementNode> detached) {
        List<EditorNode> list = new ArrayList<>(graph.allNodes());
        list.addAll(detached);
        return list;
    }
}
