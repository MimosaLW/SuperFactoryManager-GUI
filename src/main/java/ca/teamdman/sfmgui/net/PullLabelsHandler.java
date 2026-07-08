package ca.teamdman.sfmgui.net;

import ca.teamdman.sfm.common.blockentity.ManagerBlockEntity;
import ca.teamdman.sfm.common.item.LabelGunItem;
import ca.teamdman.sfm.common.label.LabelPositionHolder;
import ca.teamdman.sfmgui.SFMGui;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server-side handling for {@link PullLabelsPayload}: copies the manager disk's
 * labels onto the label gun in the player's inventory.
 */
public final class PullLabelsHandler {
    private PullLabelsHandler() {
    }

    public static void handle(PullLabelsPayload msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            BlockEntity be = player.level().getBlockEntity(msg.pos());
            if (!(be instanceof ManagerBlockEntity manager)) {
                feedback(player, "gui.sfmgui.pull_labels.no_manager", ChatFormatting.RED);
                return;
            }
            ItemStack disk = manager.getDisk();
            if (disk == null || disk.isEmpty()) {
                feedback(player, "gui.sfmgui.pull_labels.no_disk", ChatFormatting.RED);
                return;
            }
            // Collect the label NAMES referenced by the program on the disk, plus any
            // names already bound on the disk's holder. We push names (not positions)
            // so the player can then bind them to blocks with the label gun.
            java.util.LinkedHashSet<String> names = collectLabelNames(disk);
            if (names == null) {
                // Non-blank program that failed to compile.
                feedback(player, "gui.sfmgui.pull_labels.compile_failed", ChatFormatting.RED);
                return;
            }
            SFMGui.LOGGER.info("PullLabels: {} label names from disk: {}", names.size(), names);
            if (names.isEmpty()) {
                feedback(player, "gui.sfmgui.pull_labels.empty", ChatFormatting.YELLOW);
                return;
            }
            ItemStack gun = findLabelGun(player);
            if (gun == null) {
                feedback(player, "gui.sfmgui.pull_labels.no_gun", ChatFormatting.RED);
                return;
            }
            LabelPositionHolder gunLabels = LabelPositionHolder.from(gun);
            for (String name : names) {
                gunLabels.addReferencedLabel(name);
            }
            gunLabels.save(gun);
            feedback(player, "gui.sfmgui.pull_labels.success", ChatFormatting.GREEN, names.size());
        });
        ctx.setPacketHandled(true);
    }

    /**
     * Collects the label names to push onto the gun from the compiled program's
     * {@code referencedLabels()}, unioned with any names already bound on the
     * disk's own holder. Returns {@code null} when the disk has a non-blank
     * program that fails to compile, so the caller can tell the player to fix it
     * first rather than silently pushing nothing.
     */
    private static java.util.LinkedHashSet<String> collectLabelNames(ItemStack disk) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        // Names already bound on the disk's own holder (if any).
        try {
            LabelPositionHolder.from(disk).labels().keySet().forEach(names::add);
        } catch (Exception ignored) {
        }
        String program = "";
        try {
            program = ca.teamdman.sfm.common.item.DiskItem.getProgramString(disk);
        } catch (Exception ignored) {
        }
        if (program == null || program.isBlank()) {
            return names;
        }
        try {
            var result = new ca.teamdman.sfml.program_builder.ProgramBuilder(program)
                    .useCache(false).build();
            if (result.program() != null) {
                names.addAll(result.program().referencedLabels());
                return names;
            }
        } catch (Exception e) {
            SFMGui.LOGGER.debug("PullLabels: program compile threw", e);
        }
        // Non-blank program that failed to compile: signal the caller.
        return null;
    }

    private static ItemStack findLabelGun(ServerPlayer player) {
        var inv = player.getInventory();
        // prefer the currently held item, else first gun in inventory
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof LabelGunItem) {
            return main;
        }
        ItemStack off = player.getOffhandItem();
        if (off.getItem() instanceof LabelGunItem) {
            return off;
        }
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof LabelGunItem) {
                return s;
            }
        }
        return null;
    }

    private static void feedback(ServerPlayer player, String key, ChatFormatting color, Object... args) {
        player.displayClientMessage(Component.translatable(key, args).withStyle(color), false);
        SFMGui.LOGGER.debug("PullLabels: {} for {}", key, player.getGameProfile().getName());
    }
}
