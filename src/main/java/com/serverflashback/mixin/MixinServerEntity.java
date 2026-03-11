package com.serverflashback.mixin;

import com.serverflashback.record.RecordingManager;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerEntity.class)
public class MixinServerEntity {

    @Shadow @Final private Entity entity;

    @SuppressWarnings("unchecked")
    @Inject(method = "broadcastAndSend", at = @At("HEAD"))
    private void serverflashback$onBroadcast(Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof ClientboundSetEntityDataPacket)
                && !(packet instanceof ClientboundSetEquipmentPacket)
                && !(packet instanceof ClientboundUpdateAttributesPacket)) {
            return;
        }
//#if MC >= 12002
        if (this.entity.level() instanceof ServerLevel serverLevel
//#else
//$$         if (this.entity.level instanceof ServerLevel serverLevel
//#endif
                && RecordingManager.getInstance().hasActiveRecordings()) {
            RecordingManager.getInstance().onEntityPacket(
                    serverLevel, this.entity,
                    (Packet<? super ClientGamePacketListener>) packet);
        }
    }
}
