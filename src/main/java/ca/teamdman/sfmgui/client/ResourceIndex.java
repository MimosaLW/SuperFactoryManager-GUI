package ca.teamdman.sfmgui.client;

import ca.teamdman.sfm.common.registry.registration.SFMResourceTypes;
import ca.teamdman.sfm.common.resourcetype.RegistryBackedResourceType;
import ca.teamdman.sfm.common.resourcetype.ResourceType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A shared, lazily-built catalog of every pickable resource (items, fluids,
 * chemicals, and any other SFM registry-backed type). Both {@link ResourcePickerScreen}
 * and the SFM code-editor resource bar draw from this so the registry is only
 * enumerated once per session.
 * <p>
 * Entries carry everything needed to render an icon (item model, tinted block-atlas
 * sprite, or a text fallback) and to search/tooltip by name. {@link #lookup(String)}
 * resolves a stored SFML id back to its entry so callers can show the icon/name of a
 * previously selected resource.
 */
public final class ResourceIndex {
    /** How a grid entry is drawn. */
    public enum Kind {ITEM, SPRITE, TEXT}

    /**
     * One pickable resource. {@code sfmlId} is what gets returned on selection;
     * {@code displayName} feeds search + tooltip; the icon fields are used per kind.
     */
    public record Entry(
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

        public String localizedDisplayName() {
            if (kind == Kind.ITEM && !stack.isEmpty()) {
                return stack.getHoverName().getString();
            }
            return displayName;
        }

        public String localizedSearchText() {
            return (localizedDisplayName() + " " + sfmlId).toLowerCase(Locale.ROOT);
        }
    }

    private static List<Entry> ENTRIES = null;
    private static Map<String, Entry> BY_ID = null;

    private ResourceIndex() {
    }

    /** All entries, built on first access (client thread; registries must be ready). */
    public static List<Entry> all() {
        if (ENTRIES == null) {
            build();
        }
        return ENTRIES;
    }

    /** Resolve a stored SFML id back to its entry, or null if unknown. */
    public static Entry lookup(String sfmlId) {
        if (sfmlId == null) {
            return null;
        }
        if (BY_ID == null) {
            build();
        }
        return BY_ID.get(sfmlId);
    }

    private static synchronized void build() {
        if (ENTRIES != null) {
            return;
        }
        List<Entry> entries = new ArrayList<>();

        // --- Items (icon) ---
        // Prefer JEI's display-ready ingredient list when JEI is installed: it renders
        // correctly (no blank icons) and covers subtypes/variants. Fall back to the
        // registry otherwise. The sfmlId is always the item's registry id so SFML
        // matching is unaffected by which source we used.
        List<ItemStack> jeiStacks = JeiCompat.itemStacksOrNull();
        if (jeiStacks != null && !jeiStacks.isEmpty()) {
            for (ItemStack stack : jeiStacks) {
                if (stack == null || stack.isEmpty()) continue;
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (id == null) continue;
                entries.add(Entry.item(id, stack.copy()));
            }
        } else {
            for (Item item : BuiltInRegistries.ITEM) {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                ItemStack stack = new ItemStack(item);
                if (stack.isEmpty()) continue;
                entries.add(Entry.item(id, stack));
            }
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
                    entries.add(Entry.sprite(sfmlId, displayName, stillTex, ext.getTintColor()));
                } else {
                    entries.add(Entry.text(sfmlId, displayName));
                }
            } catch (Throwable ignored) {
                entries.add(Entry.text(sfmlId, fluidId.toString()));
            }
        }

        // --- Chemicals (Mekanism, runtime guarded, tinted sprite) ---
        try {
            if (isClassPresent("mekanism.api.chemical.Chemical")) {
                buildChemicals(entries);
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
                        entries.add(Entry.text(sfmlId, sfmlId));
                    }
                }
            }
        } catch (Throwable ignored) {
            // SFM registry not yet available — items/fluids only
        }

        Map<String, Entry> byId = new HashMap<>(entries.size() * 2);
        for (Entry e : entries) {
            byId.putIfAbsent(e.sfmlId(), e);
        }
        ENTRIES = entries;
        BY_ID = byId;
    }

    /** Isolated so all Mekanism class references stay behind one guard. */
    private static void buildChemicals(List<Entry> entries) {
        for (String registryMethod : List.of("gasRegistry", "infuseTypeRegistry", "pigmentRegistry", "slurryRegistry")) {
            try {
                Object registry = mekanism.api.MekanismAPI.class.getMethod(registryMethod).invoke(null);
                if (registry instanceof Iterable<?> iterable) {
                    for (Object value : iterable) {
                        if (value instanceof mekanism.api.chemical.Chemical<?> chemical) {
                            addChemical(entries, chemical);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static void addChemical(List<Entry> entries, mekanism.api.chemical.Chemical<?> chemical) {
        if (chemical.isEmptyType()) return;
        ResourceLocation regName = chemical.getRegistryName();
        String sfmlId = "chemical:" + regName.getNamespace() + ":" + regName.getPath();
        String displayName = chemical.getTextComponent().getString();
        entries.add(Entry.sprite(sfmlId, displayName, chemical.getIcon(), chemical.getTint()));
    }

    private static boolean isClassPresent(String className) {
        try {
            Class.forName(className, false, ResourceIndex.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    // ===== shared icon rendering (used by picker grid and the code-editor bar) =====

    /** Draw a single entry's icon in a 16x16 box at (x,y): item model, sprite, or text. */
    public static void renderIcon(GuiGraphics graphics, net.minecraft.client.gui.Font font, Entry entry, int x, int y) {
        switch (entry.kind()) {
            case ITEM -> JeiCompat.renderItem(graphics, entry.stack(), x, y);
            case SPRITE -> {
                if (entry.sprite() != null) {
                    renderSpriteIcon(graphics, x, y, entry.sprite(), entry.tint());
                } else {
                    renderTextIcon(graphics, font, entry, x, y);
                }
            }
            case TEXT -> renderTextIcon(graphics, font, entry, x, y);
        }
    }

    /** Text fallback: draw the first 2 chars of the display name centered in the cell. */
    public static void renderTextIcon(GuiGraphics graphics, net.minecraft.client.gui.Font font, Entry entry, int x, int y) {
        String name = entry.localizedDisplayName();
        String abbrev = name.isEmpty() ? "?" : name.substring(0, Math.min(2, name.length()));
        graphics.fill(x, y, x + 16, y + 16, 0xFF2A2A33);
        graphics.drawCenteredString(font, abbrev, x + 8, y + 4, 0xFFCCCCCC);
    }

    /** Renders a 16x16 sprite from the block atlas with a tint color. */
    public static void renderSpriteIcon(GuiGraphics graphics, int x, int y, ResourceLocation spriteLocation, int tint) {
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
}
