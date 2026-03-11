package com.serverflashback.action;

import net.minecraft.resources.ResourceLocation;

public class ActionMoveEntities implements Action {
//#if MC >= 12100
    private static final ResourceLocation NAME = ResourceLocation.fromNamespaceAndPath("flashback", "action/move_entities");
//#else
//$$     private static final ResourceLocation NAME = new ResourceLocation("flashback", "action/move_entities");
//#endif
    public static final ActionMoveEntities INSTANCE = new ActionMoveEntities();
    private ActionMoveEntities() {}

    @Override
    public ResourceLocation name() {
        return NAME;
    }
}
