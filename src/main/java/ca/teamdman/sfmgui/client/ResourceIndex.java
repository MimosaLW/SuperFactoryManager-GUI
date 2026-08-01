package ca.teamdman.sfmgui.client;

import ca.teamdman.sfm.common.registry.registration.SFMResourceTypes;
import ca.teamdman.sfm.common.resourcetype.RegistryBackedResourceType;
import ca.teamdman.sfm.common.resourcetype.ResourceType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

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
            Identifier sprite,         // SPRITE
            int tint                         // SPRITE
    ) {
        static Entry item(Identifier id, ItemStack stack) {
            String name = stack.getHoverName().getString();
            String search = (name + " " + id).toLowerCase(Locale.ROOT);
            // Items return the bare namespace:path id (no type prefix), matching codegen.
            return new Entry(id.toString(), name, search, Kind.ITEM, stack, null, 0);
        }

        static Entry sprite(String sfmlId, String displayName, Identifier sprite, int tint) {
            String search = (displayName + " " + sfmlId).toLowerCase(Locale.ROOT);
            return new Entry(sfmlId, displayName, search, Kind.SPRITE, ItemStack.EMPTY, sprite, tint);
        }

        static Entry text(String sfmlId, String displayName) {
            String search = (displayName + " " + sfmlId).toLowerCase(Locale.ROOT);
            return new Entry(sfmlId, displayName, search, Kind.TEXT, ItemStack.EMPTY, null, 0);
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
                Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (id == null) continue;
                entries.add(Entry.item(id, stack.copy()));
            }
        } else {
            for (Item item : BuiltInRegistries.ITEM) {
                Identifier id = BuiltInRegistries.ITEM.getKey(item);
                ItemStack stack = new ItemStack(item);
                if (stack.isEmpty()) continue;
                entries.add(Entry.item(id, stack));
            }
        }

        // --- Fluids (tinted sprite) ---
        for (Fluid fluid : BuiltInRegistries.FLUID) {
            if (fluid == Fluids.EMPTY) continue;
            if (!fluid.isSource(fluid.defaultFluidState())) continue; // source fluids only
            Identifier fluidId = BuiltInRegistries.FLUID.getKey(fluid);
            String sfmlId = "fluid:" + fluidId.getNamespace() + ":" + fluidId.getPath();
            // 26.1.2 no longer exposes the old client fluid still-texture/tint
            // accessors. Keep fluids selectable and use the stable text fallback;
            // mod-specific fluid renderers can still be added through a future
            // renderer-state integration without breaking the catalog.
            String displayName = fluid.getFluidType().getDescription().getString();
            entries.add(Entry.text(sfmlId, displayName));
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
                Identifier typeId = entry.getKey().identifier();
                String path = typeId.getPath();
                // item/fluid/chemical already covered with icons above
                if (path.equals("item") || path.equals("fluid") || path.equals("chemical")) continue;
                ResourceType<?, ?, ?> rt = entry.getValue();
                if (rt instanceof RegistryBackedResourceType<?, ?, ?> backed) {
                    for (Identifier resId : backed.getRegistryKeys()) {
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
        try {
            Class<?> api = Class.forName("mekanism.api.MekanismAPI");
            Object registry = api.getField("CHEMICAL_REGISTRY").get(null);
            for (Object chemical : (Iterable<?>) registry) {
                Class<?> type = chemical.getClass();
                if ((Boolean) type.getMethod("isEmptyType").invoke(chemical)) continue;
                Identifier regName = (Identifier) type.getMethod("getRegistryName").invoke(chemical);
                String sfmlId = "chemical:" + regName.getNamespace() + ":" + regName.getPath();
                ComponentText text = new ComponentText(type.getMethod("getTextComponent").invoke(chemical));
                entries.add(Entry.text(sfmlId, text.value()));
            }
        } catch (ReflectiveOperationException | ClassCastException ignored) {
            // Mekanism is optional and its 26.x API is not a compile dependency.
        }
    }

    private record ComponentText(Object component) {
        String value() {
            try {
                return (String) component.getClass().getMethod("getString").invoke(component);
            } catch (ReflectiveOperationException e) {
                return String.valueOf(component);
            }
        }
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
    public static void renderIcon(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font, Entry entry, int x, int y) {
        switch (entry.kind()) {
            case ITEM -> JeiCompat.item(graphics, entry.stack(), x, y);
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
    public static void renderTextIcon(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font, Entry entry, int x, int y) {
        String name = entry.displayName();
        String abbrev = name.isEmpty() ? "?" : name.substring(0, Math.min(2, name.length()));
        graphics.fill(x, y, x + 16, y + 16, 0xFF2A2A33);
        graphics.centeredText(font, abbrev, x + 8, y + 4, 0xFFCCCCCC);
    }

    /** Renders a 16x16 sprite from the block atlas with a tint color. */
    public static void renderSpriteIcon(GuiGraphicsExtractor graphics, int x, int y, Identifier spriteLocation, int tint) {
        TextureAtlasSprite sprite = Minecraft.getInstance().getAtlasManager()
                .getAtlasOrThrow(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS)
                .getSprite(spriteLocation);
        int color = (tint & 0xFF000000) == 0 ? tint | 0xFF000000 : tint;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, 16, 16, color);
    }
}
