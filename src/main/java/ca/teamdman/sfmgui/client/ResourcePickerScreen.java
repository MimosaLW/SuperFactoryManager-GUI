package ca.teamdman.sfmgui.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * A single unified, searchable resource picker. Every resource (item, fluid,
 * chemical, and any other SFM registry-backed type) lives in one icon grid, drawn
 * from the shared {@link ResourceIndex}. The search box filters across all types at
 * once (global search). Selecting an entry returns its SFML resource identifier string.
 */
public class ResourcePickerScreen extends Screen {
    public static final Loc PICKER_TITLE = new Loc(
            "gui.sfmgui.node_editor.picker.title",
            "Pick a resource"
    );
    public static final Loc PICKER_SEARCH = new Loc(
            "gui.sfmgui.node_editor.picker.search",
            "Search..."
    );

    private static final int CELL = 18;
    private static final int COLS = 12;

    // Quick resource-type tags shown above the grid. Clicking one fills the bare
    // type placeholder (e.g. "item::") and closes the picker. Labels are bilingual.
    private static final int TAG_Y = 56;
    private static final int TAG_H = 16;
    private static final int TAG_GAP = 4;
    private static final int TAG_PAD = 8;
    private record TypeTag(String sfmlId, String label) {
    }
    private static final List<TypeTag> TYPE_TAGS = List.of(
            new TypeTag("item::", "物品 Item"),
            new TypeTag("fluid::", "流体 Fluid"),
            new TypeTag("forge_energy::", "能量 Forge Energy"),
            new TypeTag("chemical::", "化学品 Chemical")
    );

    private final Consumer<String> onPick;
    private final Screen previousScreen;

    private final List<ResourceIndex.Entry> filtered = new ArrayList<>();

    private EditBox searchBox;
    private int scrollRow = 0;

    public ResourcePickerScreen(Screen previousScreen, Consumer<String> onPick) {
        super(PICKER_TITLE.getComponent());
        this.previousScreen = previousScreen;
        this.onPick = onPick;
    }

    private int gridLeft() {
        return this.width / 2 - (COLS * CELL) / 2;
    }

    private int gridTop() {
        // below the quick-type tag row
        return TAG_Y + TAG_H + 6;
    }

    /**
     * Screen-space [x, width] of each quick-type tag, laid out as a centered row.
     * Render and click hit-testing share this so they never drift apart.
     */
    private int[][] tagLayout() {
        int[][] out = new int[TYPE_TAGS.size()][2];
        int totalW = 0;
        for (int i = 0; i < TYPE_TAGS.size(); i++) {
            int tw = this.font.width(TYPE_TAGS.get(i).label()) + TAG_PAD;
            out[i][1] = tw;
            totalW += tw + (i > 0 ? TAG_GAP : 0);
        }
        int x = Math.max(4, this.width / 2 - totalW / 2);
        for (int i = 0; i < TYPE_TAGS.size(); i++) {
            out[i][0] = x;
            x += out[i][1] + TAG_GAP;
        }
        return out;
    }

    private int visibleRows() {
        return Math.max(1, (this.height - gridTop() - 40) / CELL);
    }

    @Override
    protected void init() {
        super.init();
        searchBox = new EditBox(this.font, this.width / 2 - 100, 34, 200, 18, PICKER_SEARCH.getComponent());
        searchBox.setHint(PICKER_SEARCH.getComponent());
        searchBox.setResponder(this::applyFilter);
        this.addRenderableWidget(searchBox);
        this.setInitialFocus(searchBox);

        this.addRenderableWidget(new ButtonBuilder()
                .setPosition(this.width / 2 - 100, this.height - 28)
                .setSize(200, 20)
                .setText(CommonComponents.GUI_CANCEL)
                .setOnPress(b -> onClose())
                .build());
        applyFilter("");
    }

    /** Global filter across every entry regardless of type. */
    private void applyFilter(String query) {
        String raw = query == null ? "" : query.trim();
        filtered.clear();
        for (ResourceIndex.Entry entry : ResourceIndex.all()) {
            if (raw.isEmpty() || PinyinSearch.matches(entry.localizedDisplayName(), raw)
                    || entry.localizedSearchText().contains(raw.toLowerCase(Locale.ROOT))) {
                filtered.add(entry);
            }
        }
        scrollRow = 0;
    }

    public void renderBackground(GuiGraphics graphics, int mx, int my, float partialTick) {
    }

    @Override
    public void render(GuiGraphics graphics, int mx, int my, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, 0xF0101012);
        super.render(graphics, mx, my, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);

        renderTypeTags(graphics, mx, my);
        renderGrid(graphics, mx, my);
    }

    /** Draw the centered row of quick resource-type tags above the grid. */
    private void renderTypeTags(GuiGraphics graphics, int mx, int my) {
        int[][] tl = tagLayout();
        for (int i = 0; i < TYPE_TAGS.size(); i++) {
            int tx = tl[i][0], tw = tl[i][1];
            boolean hover = mx >= tx && mx < tx + tw && my >= TAG_Y && my < TAG_Y + TAG_H;
            graphics.fill(tx, TAG_Y, tx + tw, TAG_Y + TAG_H, hover ? 0xFF4A4A5A : 0xFF2A2A33);
            graphics.drawCenteredString(this.font, TYPE_TAGS.get(i).label(),
                    tx + tw / 2, TAG_Y + 4, hover ? 0xFFFFFFFF : 0xFFCCE0FF);
        }
    }

    private void renderGrid(GuiGraphics graphics, int mx, int my) {
        int left = gridLeft();
        int top = gridTop();
        int rows = visibleRows();
        int startIndex = scrollRow * COLS;
        graphics.fill(left - 2, top - 2, left + COLS * CELL + 2, top + rows * CELL + 2, 0xCC101010);
        ResourceIndex.Entry hovered = null;
        for (int i = 0; i < rows * COLS; i++) {
            int index = startIndex + i;
            if (index >= filtered.size()) break;
            ResourceIndex.Entry entry = filtered.get(index);
            int col = i % COLS;
            int row = i / COLS;
            int cx = left + col * CELL;
            int cy = top + row * CELL;
            boolean over = mx >= cx && mx < cx + CELL && my >= cy && my < cy + CELL;
            if (over) {
                graphics.fill(cx, cy, cx + CELL, cy + CELL, 0x80FFFFFF);
                hovered = entry;
            }
            ResourceIndex.renderIcon(graphics, this.font, entry, cx + 1, cy + 1);
        }
        graphics.drawCenteredString(this.font,
                Component.literal(filtered.size() + " results"),
                this.width / 2, this.height - 42, 0xFFAAAAAA);
        if (hovered != null) {
            graphics.renderTooltip(this.font,
                    List.of(Component.literal(hovered.localizedDisplayName()), Component.literal(hovered.sfmlId())),
                    java.util.Optional.empty(), mx, my);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) return true;

        // quick resource-type tags (above the grid): fill the bare type and close
        int[][] tl = tagLayout();
        for (int i = 0; i < TYPE_TAGS.size(); i++) {
            int tx = tl[i][0], tw = tl[i][1];
            if (mx >= tx && mx < tx + tw && my >= TAG_Y && my < TAG_Y + TAG_H) {
                onPick.accept(TYPE_TAGS.get(i).sfmlId());
                Minecraft.getInstance().setScreen(previousScreen);
                return true;
            }
        }

        int left = gridLeft();
        int top = gridTop();
        int rows = visibleRows();
        if (mx >= left && mx < left + COLS * CELL && my >= top && my < top + rows * CELL) {
            int col = (int) ((mx - left) / CELL);
            int row = (int) ((my - top) / CELL);
            int index = (scrollRow + row) * COLS + col;
            if (index >= 0 && index < filtered.size()) {
                onPick.accept(filtered.get(index).sfmlId());
                Minecraft.getInstance().setScreen(previousScreen);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int totalRows = (filtered.size() + COLS - 1) / COLS;
        int maxScroll = Math.max(0, totalRows - visibleRows());
        scrollRow = Mth.clamp(scrollRow - (int) Math.signum(delta), 0, maxScroll);
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(previousScreen);
    }
}
