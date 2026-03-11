package com.serverflashback.action;

import net.minecraft.resources.ResourceLocation;

public class ActionAccuratePlayerPosition implements Action {
//#if MC >= 12100
    private static final ResourceLocation NAME = ResourceLocation.fromNamespaceAndPath("flashback", "action/accurate_player_position_optional");
//#else
//$$     private static final ResourceLocation NAME = new ResourceLocation("flashback", "action/accurate_player_position_optional");
//#endif
    public static final ActionAccuratePlayerPosition INSTANCE = new ActionAccuratePlayerPosition();
    private ActionAccuratePlayerPosition() {}

    @Override
    public ResourceLocation name() {
        return NAME;
    }
}
