package com.limelight.binding.video;

/**
 * Pure helper that derives the active render mode from observable state.
 *
 * <p>This is the SINGLE source of truth for what render mode the game is in.
 * {@link com.limelight.Game} calls this instead of storing a separate
 * {@code currentBfiMode} field, keeping the system DRY.</p>
 */
public final class RenderModeResolver {
    private RenderModeResolver() {}

    public enum RenderMode {
        DIRECT,
        POSTPROCESS,
        POSTPROCESS_BFI
    }

    /**
     * Derive the active render mode from the presence of a post-process
     * renderer and the BFI preference flag. When there is no post-process
     * renderer, BFI is irrelevant — the mode is always DIRECT.
     */
    public static RenderMode resolve(boolean hasPostProcessRenderer, boolean videoBfi) {
        if (!hasPostProcessRenderer) {
            return RenderMode.DIRECT;
        }
        return videoBfi ? RenderMode.POSTPROCESS_BFI : RenderMode.POSTPROCESS;
    }
}
