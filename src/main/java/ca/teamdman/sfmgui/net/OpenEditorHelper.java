package ca.teamdman.sfmgui.net;

import ca.teamdman.sfm.client.screen.ManagerScreen;
import ca.teamdman.sfm.common.containermenu.ManagerContainerMenu;
import ca.teamdman.sfm.common.net.ServerboundManagerProgramPacket;
import ca.teamdman.sfm.common.registry.registration.SFMPackets;
import ca.teamdman.sfmgui.SFMGui;
import ca.teamdman.sfmgui.client.NodeEditorScreen;
import ca.teamdman.sfml.ast.Program;
import net.minecraft.client.Minecraft;

/**
 * Bridges the addon's visual editor to SFM's manager screen using only SFM's
 * public API: reads the current program string and the disk labels, opens the
 * node editor, and saves by sending SFM's own program packet.
 */
public final class OpenEditorHelper {
    private OpenEditorHelper() {
    }

    public static void open(ManagerScreen managerScreen) {
        ManagerContainerMenu menu = managerScreen.getMenu();
        String program = menu.program == null ? "" : menu.program;

        NodeEditorScreen editor = new NodeEditorScreen(
                program,
                sfml -> sendProgram(menu, sfml),
                managerScreen
        );
        Minecraft.getInstance().setScreen(editor);
    }

    private static void sendProgram(ManagerContainerMenu menu, String program) {
        try {
            String truncated = truncate(program, Program.MAX_PROGRAM_LENGTH);
            SFMPackets.sendToServer(new ServerboundManagerProgramPacket(
                    menu.containerId,
                    menu.MANAGER_POSITION,
                    truncated
            ));
            // Optimistically update the local mirror so reopening reflects the save.
            menu.program = truncated;
        } catch (Throwable t) {
            SFMGui.LOGGER.error("Failed to send program to server", t);
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
