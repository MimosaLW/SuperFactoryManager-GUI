package ca.teamdman.sfmgui.client.model;

/**
 * A fallback statement node that holds a verbatim SFML fragment.
 * <p>
 * Used when reverse-parsing existing programs encounters a construct the
 * structured editor cannot fully represent. The raw text is emitted unchanged by
 * codegen, guaranteeing a lossless round trip even for constructs the visual
 * editor does not model natively.
 */
public class RawStatementNode extends StatementNode {
    /** Verbatim SFML source for this statement (may span multiple lines). */
    public String raw;

    public RawStatementNode(int x, int y, String raw) {
        super(x, y, Kind.RAW);
        this.raw = raw;
    }

    @Override
    public String getTitle() {
        return "Raw SFML";
    }
}
