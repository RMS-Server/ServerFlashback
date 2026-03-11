package cn.net.rms.serverflashback.action;

import net.minecraft.resources.ResourceLocation;

public class ActionNextTick implements Action {
//#if MC >= 12100
    private static final ResourceLocation NAME = ResourceLocation.fromNamespaceAndPath("flashback", "action/next_tick");
//#else
//$$     private static final ResourceLocation NAME = new ResourceLocation("flashback", "action/next_tick");
//#endif
    public static final ActionNextTick INSTANCE = new ActionNextTick();
    private ActionNextTick() {}

    @Override
    public ResourceLocation name() {
        return NAME;
    }
}
