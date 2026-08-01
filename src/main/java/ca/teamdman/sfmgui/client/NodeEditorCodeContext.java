package ca.teamdman.sfmgui.client;

import ca.teamdman.sfm.client.text_editor.ISFMTextEditScreenOpenContext;
import ca.teamdman.sfm.common.label.LabelPositionHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Open-context that bridges the visual node editor to SFM's text (code) editor while
 * keeping full control of the screen stack and the graph state.
 * <p>
 * Unlike SFM's default disk context (which pushes a GUI layer and pops it on close),
 * this context is opened via {@code Minecraft#setScreen}, and its close/save hooks
 * return to the {@link NodeEditorScreen} explicitly. On save it both persists the
 * program (via the shared writer) and reloads the edited SFML back into the visual
 * graph, so code edits are reflected as nodes (and vice-versa).
 */
public final class NodeEditorCodeContext implements ISFMTextEditScreenOpenContext {
    private final String initialValue;
    private final NodeEditorScreen nodeEditor;
    private final Consumer<String> persist;

    public NodeEditorCodeContext(String initialValue, NodeEditorScreen nodeEditor, Consumer<String> persist) {
        this.initialValue = initialValue == null ? "" : initialValue;
        this.nodeEditor = nodeEditor;
        this.persist = persist;
    }

    @Override
    public String initialValue() {
        return initialValue;
    }

    @Override
    public Consumer<String> saveWriter() {
        return persist;
    }

    @Override
    public LabelPositionHolder labelPositionHolder() {
        return LabelPositionHolder.empty();
    }

    /** "Done" pressed: persist, reload the graph from the edited text, return to the editor. */
    @Override
    public void onSaveAndClose(String program) {
        try {
            persist.accept(program);
        } catch (Throwable ignored) {
            // persistence failures shouldn't strand the user in the code editor
        }
        nodeEditor.reloadFromSfml(program);
        Minecraft.getInstance().setScreen(nodeEditor);
    }

    /**
     * ESC / Cancel pressed: if unchanged, just return to the visual editor. If changed,
     * confirm — "yes" discards the code edits and returns; "no" keeps editing. We ignore
     * SFM's own close runnable because we manage the screen transition ourselves.
     */
    @Override
    public void onTryClose(String program, Runnable ignoredCloser) {
        if (program == null || program.equals(initialValue)) {
            Minecraft.getInstance().setScreen(nodeEditor);
            return;
        }
        Minecraft.getInstance().setScreen(new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        // discard code changes, back to the visual editor unchanged
                        Minecraft.getInstance().setScreen(nodeEditor);
                    } else {
                        // keep editing: reopen the code editor with the current text
                        NodeEditorCodeContext ctx = new NodeEditorCodeContext(program, nodeEditor, persist);
                        Minecraft.getInstance().setScreen(
                                ca.teamdman.sfm.client.screen.SFMScreenChangeHelpers
                                        .createProgramEditScreen(ctx).asScreen());
                    }
                },
                Component.translatable("gui.sfmgui.code_edit.discard_confirm.title"),
                Component.translatable("gui.sfmgui.code_edit.discard_confirm.message"),
                Component.translatable("gui.sfmgui.code_edit.discard_yes"),
                Component.translatable("gui.sfmgui.code_edit.discard_no")
        ));
    }
}
