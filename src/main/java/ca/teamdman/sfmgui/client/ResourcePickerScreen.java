package ca.teamdman.sfmgui.client;

import ca.teamdman.sfm.common.registry.registration.SFMResourceTypes;
import ca.teamdman.sfm.common.resourcetype.RegistryBackedResourceType;
import ca.teamdman.sfm.common.resourcetype.ResourceType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * A single unified, searchable resource picker. Every resource (item, fluid,
 * chemical, and any other SFM registry-backed type) lives in one icon grid:
 * <ul>
 *   <li>items render with the vanilla item model,</li>
 *   <li>fluids/chemicals render as a tinted block-atlas sprite,</li>
 *   <li>anything without an icon falls back to a text cell.</li>
 * </ul>
 * The search box filters across all types at once (global search). Selecting an
 * entry returns its SFML resource identifier string.
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

    /** How a grid entry is drawn. */
    private enum Kind {ITEM, SPRITE, TEXT}

    /**
     * One pickable resource. {@code sfmlId} is what gets returned on selection;
     * {@code displayName} feeds search + tooltip; the icon fields are used per kind.
     */
    private record Entry(
            String sfmlId,
            String displayName,
            String searchText,
            Kind kind,
            ItemStack stack,                 // ITEM
            ResourceLocation sprite,         // SPRITE
            int tint                         // SPRITE
    ) {
        static Entry item(ResourceLocation id, ItemStack stack) {
            String name = stack.getHoverName().getString();
            String search = (name + " " + id).toLowerCase(Locale.ROOT);
            // Items return the bare namespace:path id (no type prefix), matching codegen.
            return new Entry(id.toString(), name, search, Kind.ITEM, stack, null, 0);
        }

        static Entry sprite(String sfmlId, String displayName, ResourceLocation sprite, int tint) {
            String search = (displayName + " " + sfmlId).toLowerCase(Locale.ROOT);
            return new Entry(sfmlId, displayName, search, Kind.SPRITE, ItemStack.EMPTY, sprite, tint);
        }

        static Entry text(String sfmlId, String displayName) {
            String search = (displayName + " " + sfmlId).toLowerCase(Locale.ROOT);
            return new Entry(sfmlId, displayName, search, Kind.TEXT, ItemStack.EMPTY, null, 0);
        }
    }

    private final List<Entry> allEntries = new ArrayList<>();
    private final List<Entry> filtered = new ArrayList<>();

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
        if (allEntries.isEmpty()) {
            buildEntries();
        }
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

    private void buildEntries() {
        // --- Items (icon) ---
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            ItemStack stack = new ItemStack(item);
            if (stack.isEmpty()) continue;
            allEntries.add(Entry.item(id, stack));
        }

        // --- Fluids (tinted sprite) ---
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            if (fluid == Fluids.EMPTY) continue;
            if (!fluid.isSource(fluid.defaultFluidState())) continue; // source fluids only
            ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(fluid);
            String sfmlId = "fluid:" + fluidId.getNamespace() + ":" + fluidId.getPath();
            try {
                IClientFluidTypeExtensions ext = IClientFluidTypeExtensions.of(fluid);
                ResourceLocation stillTex = ext.getStillTexture();
                String displayName = fluid.getFluidType().getDescription().getString();
                if (stillTex != null) {
                    allEntries.add(Entry.sprite(sfmlId, displayName, stillTex, ext.getTintColor()));
                } else {
                    allEntries.add(Entry.text(sfmlId, displayName));
                }
            } catch (Throwable ignored) {
                allEntries.add(Entry.text(sfmlId, fluidId.toString()));
            }
        }

        // --- Chemicals (Mekanism, runtime guarded, tinted sprite) ---
        try {
            if (isClassPresent("mekanism.api.chemical.Chemical")) {
                buildChemicals();
            }
        } catch (Throwable ignored) {
            // Mekanism not present — skip
        }

        // --- Other SFM registry-backed types (text fallback) ---
        try {
            var registry = SFMResourceTypes.registry();
            for (var entry : registry.entries()) {
                ResourceLocation typeId = entry.getKey().location();
                String path = typeId.getPath();
                // item/fluid/chemical already covered with icons above
                if (path.equals("item") || path.equals("fluid") || path.equals("chemical")) continue;
                ResourceType<?, ?, ?> rt = entry.getValue();
                if (rt instanceof RegistryBackedResourceType<?, ?, ?> backed) {
                    for (ResourceLocation resId : backed.getRegistryKeys()) {
                        String sfmlId = path + ":" + resId.getNamespace() + ":" + resId.getPath();
                        allEntries.add(Entry.text(sfmlId, sfmlId));
                    }
                }
            }
        } catch (Throwable ignored) {
            // SFM registry not yet available — items/fluids only
        }
    }

    /** Isolated so all Mekanism class references stay behind one guard. */
    private void buildChemicals() {
        var registry = mekanism.api.MekanismAPI.CHEMICAL_REGISTRY;
        for (mekanism.api.chemical.Chemical chemical : registry) {
            if (chemical.isEmptyType()) continue;
            ResourceLocation regName = chemical.getRegistryName();
            String sfmlId = "chemical:" + regName.getNamespace() + ":" + regName.getPath();
            String displayName = chemical.getTextComponent().getString();
            allEntries.add(Entry.sprite(sfmlId, displayName, chemical.getIcon(), chemical.getTint()));
        }
    }

    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, ResourcePickerScreen.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Global filter across every entry regardless of type. */
    private void applyFilter(String query) {
        String raw = query == null ? "" : query.trim();
        filtered.clear();
        for (Entry entry : allEntries) {
            if (raw.isEmpty() || PinyinSearch.matches(entry.displayName(), raw)
                    || entry.searchText().contains(raw.toLowerCase(Locale.ROOT))) {
                filtered.add(entry);
            }
        }
        scrollRow = 0;
    }

    @Override
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
        Entry hovered = null;
        for (int i = 0; i < rows * COLS; i++) {
            int index = startIndex + i;
            if (index >= filtered.size()) break;
            Entry entry = filtered.get(index);
            int col = i % COLS;
            int row = i / COLS;
            int cx = left + col * CELL;
            int cy = top + row * CELL;
            boolean over = mx >= cx && mx < cx + CELL && my >= cy && my < cy + CELL;
            if (over) {
                graphics.fill(cx, cy, cx + CELL, cy + CELL, 0x80FFFFFF);
                hovered = entry;
            }
            renderCell(graphics, entry, cx + 1, cy + 1);
        }
        graphics.drawCenteredString(this.font,
                Component.literal(filtered.size() + " results"),
                this.width / 2, this.height - 42, 0xFFAAAAAA);
        if (hovered != null) {
            graphics.renderTooltip(this.font,
                    List.of(Component.literal(hovered.displayName()), Component.literal(hovered.sfmlId())),
                    java.util.Optional.empty(), mx, my);
        }
    }

    /** Draw a single grid cell: item model, tinted sprite, or text fallback. */
    private void renderCell(GuiGraphics graphics, Entry entry, int x, int y) {
        switch (entry.kind()) {
            case ITEM -> graphics.renderItem(entry.stack(), x, y);
            case SPRITE -> {
                if (entry.sprite() != null) {
                    renderSpriteCell(graphics, x, y, entry.sprite(), entry.tint());
                } else {
                    renderTextCell(graphics, entry, x, y);
                }
            }
            case TEXT -> renderTextCell(graphics, entry, x, y);
        }
    }

    /** Text fallback: draw the first 2 chars of the display name centered in the cell. */
    private void renderTextCell(GuiGraphics graphics, Entry entry, int x, int y) {
        String name = entry.displayName();
        String abbrev = name.isEmpty() ? "?" : name.substring(0, Math.min(2, name.length()));
        graphics.fill(x, y, x + 16, y + 16, 0xFF2A2A33);
        graphics.drawCenteredString(this.font, abbrev, x + 8, y + 4, 0xFFCCCCCC);
    }

    /** Renders a 16x16 sprite from the block atlas with a tint color. */
    private void renderSpriteCell(GuiGraphics graphics, int x, int y, ResourceLocation spriteLocation, int tint) {
        TextureAtlasSprite sprite = Minecraft.getInstance()
                .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                .apply(spriteLocation);
        float a = ((tint >> 24) & 0xFF) / 255f;
        float r = ((tint >> 16) & 0xFF) / 255f;
        float g = ((tint >> 8) & 0xFF) / 255f;
        float b = (tint & 0xFF) / 255f;
        if (a == 0f) a = 1f; // treat fully transparent tint as opaque (0xRRGGBB without alpha)
        graphics.blit(x, y, 0, 16, 16, sprite, r, g, b, a);
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
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        int totalRows = (filtered.size() + COLS - 1) / COLS;
        int maxScroll = Math.max(0, totalRows - visibleRows());
        scrollRow = Mth.clamp(scrollRow - (int) Math.signum(scrollY), 0, maxScroll);
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(previousScreen);
    }
}
