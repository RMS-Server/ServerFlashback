package com.serverflashback.util;

import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.EnderDragonPart;

public class PacketHelper {

    public static boolean shouldIgnoreEntity(Entity entity) {
        return entity == null || entity.isRemoved() || entity instanceof EnderDragonPart || entity.getType().clientTrackingRange() <= 0;
    }

    public static ClientboundAddEntityPacket createAddEntity(Entity entity) {
//#if MC >= 11900
        return new ClientboundAddEntityPacket(
                entity.getId(),
                entity.getUUID(),
                entity.getX(),
                entity.getY(),
                entity.getZ(),
                entity.getXRot(),
                entity.getYRot(),
                entity.getType(),
                0,
                entity.getDeltaMovement(),
                entity.getYHeadRot()
        );
//#else
//$$         return new ClientboundAddEntityPacket(
//$$                 entity.getId(),
//$$                 entity.getUUID(),
//$$                 entity.getX(),
//$$                 entity.getY(),
//$$                 entity.getZ(),
//$$                 entity.getXRot(),
//$$                 entity.getYRot(),
//$$                 entity.getType(),
//$$                 0,
//$$                 entity.getDeltaMovement()
//$$         );
//#endif
    }
}
