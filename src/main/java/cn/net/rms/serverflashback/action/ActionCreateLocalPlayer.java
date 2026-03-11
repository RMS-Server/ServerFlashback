package cn.net.rms.serverflashback.action;

import net.minecraft.resources.ResourceLocation;

public class ActionCreateLocalPlayer implements Action {
//#if MC >= 12100
    private static final ResourceLocation NAME = ResourceLocation.fromNamespaceAndPath("flashback", "action/create_local_player");
//#else
//$$     private static final ResourceLocation NAME = new ResourceLocation("flashback", "action/create_local_player");
//#endif
    public static final ActionCreateLocalPlayer INSTANCE = new ActionCreateLocalPlayer();
    private ActionCreateLocalPlayer() {}

    @Override
    public ResourceLocation name() {
        return NAME;
    }
}
