package cn.net.rms.serverflashback.action;

import net.minecraft.resources.ResourceLocation;

public class ActionLevelChunkCached implements Action {
//#if MC >= 12100
    private static final ResourceLocation NAME = ResourceLocation.fromNamespaceAndPath("flashback", "action/level_chunk_cached");
//#else
//$$     private static final ResourceLocation NAME = new ResourceLocation("flashback", "action/level_chunk_cached");
//#endif
    public static final ActionLevelChunkCached INSTANCE = new ActionLevelChunkCached();
    private ActionLevelChunkCached() {}

    @Override
    public ResourceLocation name() {
        return NAME;
    }
}
