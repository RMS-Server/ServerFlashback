package com.serverflashback.action;

import net.minecraft.resources.ResourceLocation;

public class ActionConfigurationPacket implements Action {
//#if MC >= 12100
    private static final ResourceLocation NAME = ResourceLocation.fromNamespaceAndPath("flashback", "action/configuration_packet");
//#else
//$$     private static final ResourceLocation NAME = new ResourceLocation("flashback", "action/configuration_packet");
//#endif
    public static final ActionConfigurationPacket INSTANCE = new ActionConfigurationPacket();
    private ActionConfigurationPacket() {}

    @Override
    public ResourceLocation name() {
        return NAME;
    }
}
