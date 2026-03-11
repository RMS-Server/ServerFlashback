package com.serverflashback.action;

import net.minecraft.resources.ResourceLocation;

public class ActionGamePacket implements Action {
//#if MC >= 12100
    private static final ResourceLocation NAME = ResourceLocation.fromNamespaceAndPath("flashback", "action/game_packet");
//#else
//$$     private static final ResourceLocation NAME = new ResourceLocation("flashback", "action/game_packet");
//#endif
    public static final ActionGamePacket INSTANCE = new ActionGamePacket();
    private ActionGamePacket() {}

    @Override
    public ResourceLocation name() {
        return NAME;
    }
}
